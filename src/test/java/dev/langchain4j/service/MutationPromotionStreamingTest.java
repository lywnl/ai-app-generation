package dev.langchain4j.service;

import com.lyw.appgeneration.ai.tools.ReadOnlyMutationPromotionTest.Harness;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.guardrail.ChatExecutor;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MutationPromotionStreamingTest {
    @Test
    void 初始只读真实修改绑定计划反馈后成功构建() throws Exception {
        try (Harness h = new Harness(true)) {
            Fixture f = new Fixture(h, false, null, false, false);
            f.root.onCompleteResponse(tools("modifyFile"));
            assertNotNull(f.controller.pendingPromotionFeedback());
            assertTrue(f.requests.getFirst().messages().stream().anyMatch(MutationPromotionStreamingTest::promotionMessage));
            assertFalse(f.memory.messages.stream().anyMatch(MutationPromotionStreamingTest::promotionMessage));
            f.last.get().onPartialResponse("继续构建");
            assertNull(f.controller.pendingPromotionFeedback());
            f.last.get().onCompleteResponse(tools("buildProject"));
            assertEquals(List.of(ToolLoopTerminationProtocol.ControlledTerminationReason.BUILD_SUCCEEDED), f.terminations);
            assertEquals(1, h.context.lease().snapshot().buildAttempt());
            assertEquals(1, f.requests.size());
            assertTrue(f.errors.isEmpty(), f.errors.toString());
        }
    }

    @Test
    void 初始化失败先保留真实结果然后跳过同批后续工具() throws Exception {
        try (Harness h = new Harness(false)) {
            Files.writeString(h.root.resolve(".plan.json"), "invalid");
            Fixture f = new Fixture(h, false, null, false, false);
            f.pauseAfterMutation = true;
            f.root.onCompleteResponse(tools("modifyFile", "buildProject"));
            assertTrue(f.memory.messages.stream().filter(ToolExecutionResultMessage.class::isInstance)
                    .map(ToolExecutionResultMessage.class::cast).anyMatch(m -> m.text().contains("APPLIED")));
            assertTrue(f.terminations.isEmpty());
            assertEquals(0, h.context.lease().snapshot().buildAttempt());
            f.disclosure.resumePublishing();
            assertEquals(List.of(ToolLoopTerminationProtocol.ControlledTerminationReason.PLAN_INITIALIZATION_FAILED), f.terminations);
            assertEquals(List.of("modifyFile", "buildProject", "terminal"), f.events);
            assertTrue(f.requests.isEmpty());
            assertNull(f.controller.pendingPromotionFeedback());
        }
    }

    @Test
    void 成功结果持久化失败不得安排升级反馈或继续请求() throws Exception {
        try (Harness h = new Harness(true)) {
            Fixture f = new Fixture(h, true, null, false, false);
            f.root.onCompleteResponse(tools("modifyFile"));
            assertEquals("显著登录按钮", Files.readString(h.root.resolve(h.path)));
            assertTrue(f.requests.isEmpty());
            assertNull(f.controller.pendingPromotionFeedback());
            assertFalse(f.errors.isEmpty());
        }
    }

    @Test
    void 压缩准备和SDK无响应失败不算送达() throws Exception {
        var preparation = new CompletableFuture<ModelRequestGate.Decision>();
        var captured = new AtomicReference<ModelRequestGate.Request>();
        try (Harness h = new Harness(true);
             var gate = new ManagedModelRequestGate(request -> { captured.set(request); return preparation; })) {
            Fixture f = new Fixture(h, false, gate, false, false);
            f.failStart = true;
            f.root.onCompleteResponse(tools("modifyFile"));
            assertTrue(f.requests.isEmpty());
            assertNotNull(f.controller.pendingPromotionFeedback());
            var messages = new ArrayList<>(captured.get().latestMemory().get().messages());
            messages.addAll(captured.get().transientMessages());
            preparation.complete(new ModelRequestGate.Decision(ModelRequestGate.Status.ALLOWED, messages, 1, ""));
            gate.awaitIdle();
            assertEquals(1, f.requests.size());
            assertFalse(f.controller.promotionDelivered());
            assertFalse(f.errors.isEmpty());
        }
    }

    @Test
    void 两条恢复路径携带未送达升级反馈且同步回调可确认() throws Exception {
        for (boolean protocol : List.of(false, true)) {
            try (Harness h = new Harness(true);
                 var gate = new ManagedModelRequestGate(request -> {
                     var messages = new ArrayList<>(request.latestMemory().get().messages());
                     messages.addAll(request.transientMessages());
                     return CompletableFuture.completedFuture(new ModelRequestGate.Decision(ModelRequestGate.Status.ALLOWED, messages, 1, ""));
                 })) {
                Fixture f = new Fixture(h, false, gate, protocol, !protocol);
                var actual = h.modify();
                f.controller.observeMutationPromotion(f.controller.latestModelRequestGeneration(), "first", actual.mutationPromotion());
                f.synchronousResponse = true;
                f.root.onCompleteResponse(text(protocol ? "[工具调用] buildProject {}" : "已经改完"));
                gate.awaitIdle();
                assertTrue(f.requests.getFirst().messages().stream().anyMatch(MutationPromotionStreamingTest::promotionMessage));
                assertNull(f.controller.pendingPromotionFeedback());
                assertFalse(f.memory.messages.stream().anyMatch(MutationPromotionStreamingTest::promotionMessage));
            }
        }
    }

    @Test
    void 同批后续构建不丢首次进展且成功后无需额外模型请求() throws Exception {
        try (Harness h = new Harness(true)) {
            Fixture f = new Fixture(h, false, null, false, false);
            f.root.onCompleteResponse(tools("modifyFile", "buildProject"));
            assertEquals(List.of(ToolLoopTerminationProtocol.ControlledTerminationReason.BUILD_SUCCEEDED), f.terminations);
            assertTrue(f.requests.isEmpty());
            assertEquals(1, h.context.lease().snapshot().buildAttempt());
        }
    }

    @Test
    void 准备拒绝或剥离反馈的请求不标记送达() throws Exception {
        for (boolean reject : List.of(true, false)) {
            try (Harness h = new Harness(true);
                 var gate = new ManagedModelRequestGate(request -> CompletableFuture.completedFuture(
                         new ModelRequestGate.Decision(reject ? ModelRequestGate.Status.COMPRESSION_FAILED : ModelRequestGate.Status.ALLOWED,
                                 request.latestMemory().get().messages(), 1, "测试门禁")))) {
                Fixture f = new Fixture(h, false, gate, false, false);
                f.root.onCompleteResponse(tools("modifyFile"));
                gate.awaitIdle();
                if (!reject) {
                    assertEquals(1, f.requests.size());
                    f.last.get().onPartialResponse("有效但未携反馈的正文");
                } else assertTrue(f.requests.isEmpty());
                assertFalse(f.controller.promotionDelivered());
            }
        }
    }

    @Test
    void 初始化失败与取消竞争只有一个终态() throws Exception {
        for (int i = 0; i < 10; i++) {
            try (Harness h = new Harness(false)) {
                Files.writeString(h.root.resolve(".plan.json"), "invalid");
                Fixture f = new Fixture(h, false, null, false, false);
                var start = new java.util.concurrent.CountDownLatch(1);
                try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                    var modify = executor.submit(() -> { start.await(); f.root.onCompleteResponse(tools("modifyFile", "buildProject")); return null; });
                    var cancel = executor.submit(() -> { start.await(); f.controller.cancel(); h.context.lease().cancel(); return null; });
                    start.countDown();
                    modify.get(3, java.util.concurrent.TimeUnit.SECONDS);
                    cancel.get(3, java.util.concurrent.TimeUnit.SECONDS);
                }
                assertEquals(1, f.terminations.size());
                assertTrue(Set.of(ToolLoopTerminationProtocol.ControlledTerminationReason.CANCELLED,
                        ToolLoopTerminationProtocol.ControlledTerminationReason.PLAN_INITIALIZATION_FAILED).contains(f.terminations.getFirst()));
                assertEquals(0, h.context.lease().snapshot().buildAttempt());
                assertTrue(f.requests.isEmpty());
            }
        }
    }

    @Test
    void 票据去重准备重取及取消后迟到响应不推进() {
        StreamingRequestController controller = new StreamingRequestController();
        assertTrue(controller.beforeModelRequest());
        long first = controller.latestModelRequestGeneration();
        var summary = new AtomicReference<>("计划 version=1");
        var promotion = new ToolExecutionGuard.MutationPromotion("turn", "src/A.vue", summary::get);
        controller.observeMutationPromotion(first, "tool", promotion);
        var initial = controller.pendingPromotionFeedback();
        summary.set("计划 version=2");
        assertTrue(controller.pendingPromotionFeedback().message().text().contains("version=2"));
        controller.observeMutationPromotion(first, "tool", new ToolExecutionGuard.MutationPromotion("turn", "wrong", () -> "wrong"));
        assertFalse(controller.pendingPromotionFeedback().message().text().contains("wrong"));
        assertTrue(controller.beforeModelRequest(first));
        long second = controller.latestModelRequestGeneration();
        controller.promotionRequestStarted(second, initial);
        controller.buildFeedbackResponseAccepted(first);
        assertNotNull(controller.pendingPromotionFeedback());
        controller.cancel();
        controller.buildFeedbackResponseAccepted(second);
        assertNull(controller.pendingPromotionFeedback());
        assertTrue(controller.isCancelled());
    }

    private static boolean promotionMessage(ChatMessage message) {
        return message instanceof dev.langchain4j.data.message.SystemMessage system && system.text().contains("内部执行阶段更新");
    }
    private static ChatResponse tools(String... names) {
        List<ToolExecutionRequest> requests = new ArrayList<>();
        for (int i = 0; i < names.length; i++) requests.add(ToolExecutionRequest.builder().id(names[i] + i).name(names[i]).arguments("{}").build());
        return ChatResponse.builder().aiMessage(AiMessage.from(requests)).metadata(ChatResponseMetadata.builder().tokenUsage(new TokenUsage()).build()).build();
    }
    private static ChatResponse text(String content) {
        return ChatResponse.builder().aiMessage(AiMessage.from(content)).metadata(ChatResponseMetadata.builder().tokenUsage(new TokenUsage()).build()).build();
    }

    private static final class Fixture {
        final StreamingRequestController controller = new StreamingRequestController();
        final List<ChatRequest> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        final AtomicReference<StreamingChatResponseHandler> last = new AtomicReference<>();
        final List<ToolLoopTerminationProtocol.ControlledTerminationReason> terminations = new ArrayList<>();
        final List<Throwable> errors = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> events = new ArrayList<>();
        final Memory memory;
        final GenerationDisclosureBuffer disclosure = new GenerationDisclosureBuffer();
        final AiServiceStreamingResponseHandler root;
        boolean failStart;
        boolean synchronousResponse;
        boolean pauseAfterMutation;

        Fixture(Harness h, boolean failMemory, ModelRequestGate gate, boolean protocol, boolean incomplete) {
            memory = new Memory(failMemory);
            controller.bindBuildProgressGuard(h.context.buildProgressGuard());
            controller.bindReplanContext(h.context.replanContext());
            assertTrue(controller.beforeModelRequest());
            controller.onControlledTermination(termination -> { terminations.add(termination.reason()); events.add("terminal"); });
            var context = new AiServiceContext(Object.class);
            context.streamingChatModel = new StreamingChatModel() {
                @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                    requests.add(request); last.set(handler);
                    if (failStart) throw new IllegalStateException("模拟 SDK 无回调失败");
                    if (synchronousResponse) handler.onCompleteResponse(tools("buildProject"));
                }
            };
            var publisher = new GenerationSignalPublisher(disclosure, signal -> {
                if (signal instanceof GenerationStreamSignal.ToolExecuted executed) events.add(executed.execution().request().name());
            });
            root = new AiServiceStreamingResponseHandler(new ChatExecutor() {
                public ChatResponse execute() { return null; }
                public ChatResponse execute(List<ChatMessage> messages) { return null; }
            }, context, h.appId, chunk -> { }, (i, req) -> { }, (i, req) -> { }, execution -> { },
                    response -> { }, errors::add, memory, new TokenUsage(), List.of(),
                    Map.of("modifyFile", (request, id) -> "真实动作由作用域包装器执行", "buildProject", (request, id) -> "真实动作由作用域包装器执行"),
                    null, "method", controller, (name, id, action) -> {
                        var result = name.equals("modifyFile") ? h.modify() : h.build();
                        if (name.equals("modifyFile") && pauseAfterMutation) disclosure.pausePublishing();
                        var trusted = ToolExecutionGuard.direct().execute(name, id, result::toolResult);
                        return new ToolExecutionGuard.GuardedToolExecution(result.toolResult(),
                                result.controlledTermination() == null ? trusted.controlledTermination() : result.controlledTermination(),
                                result.buildObservation(), result.mutationPromotion());
                    }, controller.latestModelRequestGeneration(), gate,
                    gate == null ? null : com.lyw.appgeneration.ai.memory.ContextContinuationGate.alwaysOpen(),
                    protocol ? new ToolProtocolRecoveryCoordinator(new ToolProtocolRecoveryPolicy(Set.of("buildProject"), ignored -> { }), Set.of("buildProject")) : null,
                    incomplete ? new IncompleteToolChainRecoveryCoordinator(new IncompleteToolChainRecoveryPolicy(h.context::requiresBuild,
                            () -> IncompleteToolChainRecoveryPolicy.BuildState.GENERATING, ignored -> { })) : null,
                    new com.lyw.appgeneration.ai.memory.ContextCompressionAttemptState(), publisher);
        }
    }

    private static final class Memory implements ChatMemory {
        final List<ChatMessage> messages = new java.util.concurrent.CopyOnWriteArrayList<>();
        final boolean fail;
        Memory(boolean fail) { this.fail = fail; }
        public Object id() { return "test"; }
        public void add(ChatMessage message) {
            if (fail && message instanceof ToolExecutionResultMessage) throw new IllegalStateException("模拟持久化失败");
            messages.add(message);
        }
        public List<ChatMessage> messages() { return List.copyOf(messages); }
        public void clear() { messages.clear(); }
    }
}
