package dev.langchain4j.service;

import cn.hutool.json.JSONUtil;
import com.lyw.appgeneration.ai.VueToolNames;
import com.lyw.appgeneration.ai.memory.ContextCompressionAttemptState;
import com.lyw.appgeneration.ai.skill.SkillCatalog;
import com.lyw.appgeneration.ai.tools.FileToolBudgetGuard;
import com.lyw.appgeneration.ai.tools.FileToolExecutionScopeManager;
import com.lyw.appgeneration.ai.tools.ReadSkillTool;
import com.lyw.appgeneration.ai.tools.SkillToolProtocolSupport;
import com.lyw.appgeneration.ai.tools.SkillToolResult;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiServiceTokenStreamSkillTest {

    private static final String DUPLICATE_PSEUDO_SKILL = """
            [工具调用]
            readSkill
            {"skillName":"vue-portfolio"}
            [工具调用]
            readSkill
            {"skillName":"vue-portfolio"}""";

    @Test
    void 普通续调末次请求包含两份真实Skill正文且不含被拒正文()
            throws Exception {
        long appId = 8L;
        String turnId = "ordinary-dual-skill-turn";
        List<ChatRequest> requests = new CopyOnWriteArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> streamError = new AtomicReference<>();
        AtomicInteger callIndex = new AtomicInteger();
        FileToolExecutionScopeManager manager =
                new FileToolExecutionScopeManager(new FileToolBudgetGuard());
        AppOperationLeaseManager operations = new AppOperationLeaseManager();
        VueBuildSessionManager sessions = new VueBuildSessionManager();
        SkillCatalog catalog = new SkillCatalog();
        String designBody = catalog.load("vue-frontend-design").body();
        String storeBody = catalog.load("vue-online-store").body();
        String rejectedBody = catalog.load("vue-portfolio").body();
        ReadSkillTool readSkillTool = new ReadSkillTool(catalog, manager);
        AiServiceContext context = new AiServiceContext(Object.class);
        context.streamingChatModel = new StreamingChatModel() {
            @Override
            public void doChat(
                    ChatRequest request,
                    StreamingChatResponseHandler handler) {
                requests.add(request);
                int current = callIndex.getAndIncrement();
                if (current == 0) {
                    handler.onCompleteResponse(toolResponse(toolRequest(
                            "ordinary-design", "vue-frontend-design")));
                } else if (current == 1) {
                    handler.onCompleteResponse(toolResponse(toolRequest(
                            "ordinary-store", "vue-online-store")));
                } else if (current == 2) {
                    handler.onCompleteResponse(toolResponse(toolRequest(
                            "ordinary-third", "vue-portfolio")));
                } else if (current == 3) {
                    handler.onCompleteResponse(toolResponse(toolRequest(
                            "ordinary-reread", "vue-frontend-design")));
                } else {
                    handler.onCompleteResponse(textResponse("生成完成"));
                }
            }
        };
        try (var operation = operations.acquire(
                appId, AppOperationLeaseManager.AppOperationType.GENERATE, turnId);
             var lease = sessions.open(operation, 10L, turnId)) {
            var scope = manager.online(lease, turnId, appId,
                    Set.copyOf(VueToolNames.ONLINE));
            ToolExecutor executor = (request, memoryId) -> {
                String skillName = JSONUtil.parseObj(request.arguments())
                        .getStr("skillName");
                return manager.callInScope(scope, "readSkill",
                        () -> readSkillTool.readSkill(skillName, appId));
            };
            AiServiceTokenStream stream = new AiServiceTokenStream(
                    AiServiceTokenStreamParameters.builder()
                            .messages(List.of(UserMessage.from("生成在线商城")))
                            .toolSpecifications(ToolSpecifications
                                    .toolSpecificationsFrom(readSkillTool))
                            .toolExecutors(Map.of("readSkill", executor))
                            .retrievedContents(List.of())
                            .context(context)
                            .memoryId(appId)
                            .methodKey("method")
                            .build());

            stream.onPartialResponse(ignored -> { })
                    .onCompleteResponse(ignored -> completed.countDown())
                    .onError(error -> {
                        streamError.set(error);
                        completed.countDown();
                    })
                    .start();

            assertTrue(completed.await(3, TimeUnit.SECONDS));
        }

        assertNull(streamError.get());
        assertEquals(5, requests.size());
        List<String> finalResults = requests.getLast().messages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .map(ToolExecutionResultMessage::text)
                .toList();
        assertEquals(4, finalResults.size());
        List<SkillToolResult> parsed = finalResults.stream()
                .map(SkillToolProtocolSupport::parse)
                .toList();
        assertEquals(List.of(
                        SkillToolResult.Status.APPLIED,
                        SkillToolResult.Status.APPLIED,
                        SkillToolResult.Status.REJECTED,
                        SkillToolResult.Status.APPLIED),
                parsed.stream().map(SkillToolResult::status).toList());
        assertEquals(designBody, parsed.get(0).content());
        assertEquals(storeBody, parsed.get(1).content());
        assertTrue(parsed.stream().map(SkillToolResult::content)
                .noneMatch(rejectedBody::equals));
        assertEquals(designBody, parsed.get(3).content());
        assertEquals("TOO_MANY_SKILLS", parsed.get(2).failureReason());
        assertNull(parsed.get(2).content());
    }

    @Test
    void 协议恢复和压缩续调不重置真实Skill双配额()
            throws Exception {
        long appId = 7L;
        String turnId = "dual-skill-turn";
        List<ChatRequest> requests = new CopyOnWriteArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicInteger callIndex = new AtomicInteger();
        AtomicInteger gateIndex = new AtomicInteger();
        AtomicReference<Throwable> streamError = new AtomicReference<>();
        List<ModelRequestGate.Request> gateRequests =
                new CopyOnWriteArrayList<>();
        List<ToolProtocolRecoveryPolicy.Phase> recoveryPhases =
                new CopyOnWriteArrayList<>();
        FileToolExecutionScopeManager manager =
                new FileToolExecutionScopeManager(new FileToolBudgetGuard());
        AppOperationLeaseManager operations = new AppOperationLeaseManager();
        VueBuildSessionManager sessions = new VueBuildSessionManager();
        ReadSkillTool readSkillTool = new ReadSkillTool(new SkillCatalog(), manager);
        AiServiceContext context = new AiServiceContext(Object.class);
        context.streamingChatModel = new StreamingChatModel() {
            @Override
            public void doChat(
                    ChatRequest request,
                    StreamingChatResponseHandler handler) {
                requests.add(request);
                int current = callIndex.getAndIncrement();
                if (current == 0) {
                    handler.onCompleteResponse(toolResponse(toolRequest(
                            "read-design", "vue-frontend-design")));
                } else if (current == 1) {
                    handler.onCompleteResponse(toolResponse(toolRequest(
                            "read-store", "vue-online-store")));
                } else if (current == 2) {
                    handler.onPartialResponse(DUPLICATE_PSEUDO_SKILL);
                } else if (current == 3) {
                    handler.onCompleteResponse(toolResponse(toolRequest(
                            "read-third", "vue-portfolio")));
                } else if (current == 4) {
                    handler.onCompleteResponse(toolResponse(toolRequest(
                            "reread-design", "vue-frontend-design")));
                } else {
                    handler.onCompleteResponse(textResponse("生成完成"));
                }
            }
        };
        try (var gate = new ManagedModelRequestGate(request -> {
                 gateRequests.add(request);
                 ContextCompressionAttemptState state =
                         request.contextCompressionAttemptState();
                 if (gateIndex.getAndIncrement() == 0) {
                     var claim = state.tryEnterCheckpointMode();
                     assertEquals(ContextCompressionAttemptState
                                     .EnterDecision.FIRST_ENTRY,
                             claim.decision());
                     assertTrue(state.markCheckpointReady(claim));
                 }
                 List<ChatMessage> checkpoint = new ArrayList<>();
                 checkpoint.add(SystemMessage.from("压缩恢复检查点"));
                 checkpoint.addAll(request.transientMessages());
                 return CompletableFuture.completedFuture(
                         new ModelRequestGate.Decision(
                                 ModelRequestGate.Status.ALLOWED,
                                 checkpoint, checkpoint.size(), ""));
             });
             var operation = operations.acquire(
                appId, AppOperationLeaseManager.AppOperationType.GENERATE, turnId);
             var lease = sessions.open(operation, 9L, turnId)) {
            var scope = manager.online(lease, turnId, appId,
                    Set.copyOf(VueToolNames.ONLINE));
            ToolExecutor executor = (request, memoryId) -> {
                String skillName = JSONUtil.parseObj(request.arguments())
                        .getStr("skillName");
                return manager.callInScope(scope, "readSkill",
                        () -> readSkillTool.readSkill(skillName, appId));
            };
            AiServiceTokenStream stream = new AiServiceTokenStream(
                    AiServiceTokenStreamParameters.builder()
                            .messages(List.of(UserMessage.from("生成在线商城")))
                            .toolSpecifications(ToolSpecifications
                                    .toolSpecificationsFrom(readSkillTool))
                            .toolExecutors(Map.of("readSkill", executor))
                            .retrievedContents(List.of())
                            .context(context)
                            .memoryId(appId)
                            .methodKey("method")
                            .build());

            stream.onPartialResponse(ignored -> { })
                    .modelRequestGate(gate, action -> {
                        action.run();
                        return true;
                    })
                    .toolProtocolRecoveryPolicy(new ToolProtocolRecoveryPolicy(
                            Set.of("readSkill"), recoveryPhases::add))
                    .onCompleteResponse(ignored -> completed.countDown())
                    .onError(error -> {
                        streamError.set(error);
                        completed.countDown();
                    })
                    .start();

            assertTrue(completed.await(3, TimeUnit.SECONDS));
            gate.awaitIdle();
        }

        assertNull(streamError.get());
        assertEquals(6, requests.size());
        assertEquals(List.of(
                        ToolProtocolRecoveryPolicy.Phase.STARTED,
                        ToolProtocolRecoveryPolicy.Phase.RECOVERED),
                recoveryPhases);
        assertTrue(requests.get(2).messages().toString()
                .contains("压缩恢复检查点"));
        assertTrue(requests.get(3).messages().toString()
                .contains(ToolProtocolRecoveryCoordinator.CORRECTION_INSTRUCTION));
        Object compressionState = gateRequests.getFirst()
                .contextCompressionAttemptState();
        assertTrue(((ContextCompressionAttemptState) compressionState)
                .checkpointProjectionRequired());
        assertTrue(gateRequests.stream().allMatch(request ->
                request.contextCompressionAttemptState() == compressionState));
        List<String> toolResults = requests.getLast().messages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .map(ToolExecutionResultMessage::text)
                .toList();
        assertEquals(0, toolResults.size(),
                "压缩门禁返回检查点视图，正文不应绕过门禁重新注入");
        List<String> committedToolResults = gateRequests.getLast()
                .latestMemory().get().messages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .map(ToolExecutionResultMessage::text)
                .toList();
        assertEquals(4, committedToolResults.size());
        List<SkillToolResult> results = committedToolResults.stream()
                .map(SkillToolProtocolSupport::parse)
                .toList();
        assertEquals(List.of(
                        SkillToolResult.Status.APPLIED,
                        SkillToolResult.Status.APPLIED,
                        SkillToolResult.Status.REJECTED,
                        SkillToolResult.Status.APPLIED),
                results.stream().map(SkillToolResult::status).toList());
        assertEquals(List.of(
                        "vue-frontend-design", "vue-online-store",
                        "vue-portfolio", "vue-frontend-design"),
                results.stream().map(SkillToolResult::skillName).toList());
        assertTrue(results.get(0).content().contains("本项目边界"));
        assertTrue(results.get(1).content().contains("本项目边界"));
        assertEquals(results.get(0).content(), results.get(3).content());
        assertEquals("TOO_MANY_SKILLS", results.get(2).failureReason());
        assertNull(results.get(2).content());
    }

    @Test
    void 回合临时Skill消息进入模型请求但不改变原始消息() {
        AiServiceContext context = new AiServiceContext(Object.class);
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        context.streamingChatModel = new StreamingChatModel() {
            @Override
            public void doChat(
                    ChatRequest request,
                    StreamingChatResponseHandler handler) {
                captured.set(request);
            }
        };
        AiServiceTokenStream stream = new AiServiceTokenStream(
                AiServiceTokenStreamParameters.builder()
                        .messages(List.of(UserMessage.from("生成后台")))
                        .toolSpecifications(List.of())
                        .toolExecutors(Map.of())
                        .retrievedContents(List.of())
                        .context(context)
                        .memoryId("skill-test")
                        .methodKey("method")
                        .build());
        stream.turnTransientMessages(List.of(
                SystemMessage.from("Vue 前端设计 Skill")));

        stream.onPartialResponse(ignored -> { })
                .ignoreErrors()
                .start();

        assertEquals(2, captured.get().messages().size());
        ChatMessage original = captured.get().messages().get(0);
        assertTrue(original instanceof UserMessage);
        assertTrue(((UserMessage) original).singleText().contains("生成后台"));
        assertTrue(captured.get().messages().get(1).toString()
                .contains("Vue 前端设计 Skill"));
    }

    @Test
    void 门禁请求包含Skill但活动记忆不包含Skill() {
        AiServiceContext context = new AiServiceContext(Object.class);
        AtomicReference<ModelRequestGate.Request> gateRequest =
                new AtomicReference<>();
        context.streamingChatModel = new StreamingChatModel() {
            @Override
            public void doChat(
                    ChatRequest request,
                    StreamingChatResponseHandler handler) {
            }
        };
        AiServiceTokenStream stream = new AiServiceTokenStream(
                AiServiceTokenStreamParameters.builder()
                        .messages(List.of(UserMessage.from("生成后台")))
                        .toolSpecifications(List.of())
                        .toolExecutors(Map.of())
                        .retrievedContents(List.of())
                        .context(context)
                        .memoryId("skill-gate-test")
                        .methodKey("method")
                        .build());
        stream.turnTransientMessages(List.of(
                SystemMessage.from("Vue 前端设计 Skill")));
        stream.modelRequestGate(request -> {
            gateRequest.set(request);
            List<ChatMessage> messages = new ArrayList<>(
                    request.latestMemory().get().messages());
            messages.addAll(request.transientMessages());
            return CompletableFuture.completedFuture(
                    new ModelRequestGate.Decision(
                            ModelRequestGate.Status.ALLOWED, messages, 2, ""));
        }, action -> {
            action.run();
            return true;
        });

        stream.onPartialResponse(ignored -> { })
                .ignoreErrors()
                .start();

        assertEquals(1, gateRequest.get().transientMessages().size());
        assertTrue(gateRequest.get().transientMessages().get(0).toString()
                .contains("Vue 前端设计 Skill"));
        assertEquals(1, gateRequest.get().latestMemory().get().messages().size());
        assertTrue(gateRequest.get().latestMemory().get().messages().get(0)
                .toString().contains("生成后台"));
    }

    private ToolExecutionRequest toolRequest(String id, String skillName) {
        return ToolExecutionRequest.builder()
                .id(id)
                .name("readSkill")
                .arguments("{\"skillName\":\"" + skillName + "\"}")
                .build();
    }

    private ChatResponse toolResponse(ToolExecutionRequest request) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(List.of(request)))
                .metadata(metadata())
                .build();
    }

    private ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(text))
                .metadata(metadata())
                .build();
    }

    private ChatResponseMetadata metadata() {
        return ChatResponseMetadata.builder()
                .tokenUsage(new TokenUsage(1, 1, 2))
                .build();
    }

}
