package dev.langchain4j.service;

import com.lyw.appgeneration.ai.plan.BuildBlockDiagnostic;
import com.lyw.appgeneration.ai.tools.BuildProjectToolResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.guardrail.ChatExecutor;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BuildProgressStreamingTest {
    @Test
    void 统一发布队列延迟时最后拒绝和跳过记录必须先于终态() {
        Fixture f = new Fixture(false, false, null, false, false, true);
        f.root.onCompleteResponse(response(tool("a", "buildProject"), tool("b", "buildProject")));
        assertEquals(1, f.model.requests.size());
        f.pauseOnNextBuild = true;
        f.model.lastHandler.onCompleteResponse(response(tool("c", "buildProject"), tool("write", "writeFile")));
        assertTrue(f.events.contains("memory:c"));
        assertFalse(f.events.contains("signal:c"));
        assertTrue(f.terminations.isEmpty(), "队列未披露最后拒绝时不能提前关闭流");
        f.disclosure.resumePublishing();
        assertEquals(List.of(ToolLoopTerminationProtocol.ControlledTerminationReason.BUILD_STALLED), f.terminations);
        assertTrue(f.events.indexOf("signal:c") < f.events.indexOf("termination"));
        assertTrue(f.events.indexOf("signal:write") < f.events.indexOf("termination"));
        assertEquals(0, f.writeCalls.get());
        assertEquals(1, f.model.requests.size());
        assertTrue(f.errors.isEmpty());
    }
    @Test
    void 停滞与取消竞争只产生一个终态且没有下一次模型请求() throws Exception {
        for (int index = 0; index < 20; index++) {
            Fixture f = new Fixture(false, false);
            f.root.onCompleteResponse(response(tool("a", "buildProject"), tool("b", "buildProject")));
            f.model.lastHandler.onPartialResponse("处理构建条件");
            assertTrue(f.guard.correctionDelivered());
            var start = new java.util.concurrent.CountDownLatch(1);
            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var blocked = executor.submit(() -> { start.await(); f.model.lastHandler.onCompleteResponse(response(tool("c", "buildProject"))); return null; });
                var cancel = executor.submit(() -> { start.await(); f.controller.cancel(); return null; });
                start.countDown();
                blocked.get(2, java.util.concurrent.TimeUnit.SECONDS);
                cancel.get(2, java.util.concurrent.TimeUnit.SECONDS);
            }
            assertEquals(1, f.terminations.size());
            assertTrue(Set.of(ToolLoopTerminationProtocol.ControlledTerminationReason.CANCELLED,
                    ToolLoopTerminationProtocol.ControlledTerminationReason.BUILD_STALLED).contains(f.terminations.getFirst()));
            assertEquals(1, f.model.requests.size());
            assertTrue(f.controller.awaitQuiescence(java.time.Duration.ofSeconds(1)));
        }
    }

    @Test
    void 等待上下文门禁时不算送达且允许后仍等待模型响应() throws Exception {
        var preparation = new CompletableFuture<ModelRequestGate.Decision>();
        var captured = new AtomicReference<ModelRequestGate.Request>();
        try (var gate = new ManagedModelRequestGate(request -> { captured.set(request); return preparation; })) {
            Fixture f = new Fixture(false, false, gate, false, false);
            f.root.onCompleteResponse(response(tool("a", "buildProject"), tool("b", "buildProject")));
            assertNotNull(f.guard.pendingFeedback());
            assertFalse(f.guard.correctionDelivered());
            assertTrue(f.model.requests.isEmpty());
            var messages = new ArrayList<>(captured.get().latestMemory().get().messages());
            messages.addAll(captured.get().transientMessages());
            preparation.complete(new ModelRequestGate.Decision(ModelRequestGate.Status.ALLOWED, messages, 1, ""));
            gate.awaitIdle();
            assertEquals(1, f.model.requests.size());
            assertFalse(f.guard.correctionDelivered());
            f.model.lastHandler.onPartialResponse("现在检查阻塞");
            assertTrue(f.guard.correctionDelivered());
        }
    }

    @Test
    void 门禁失败或准备期间取消不能确认送达() throws Exception {
        for (boolean cancel : List.of(false, true)) {
            var preparation = new CompletableFuture<ModelRequestGate.Decision>();
            try (var gate = new ManagedModelRequestGate(request -> preparation)) {
                Fixture f = new Fixture(false, false, gate, false, false);
                f.root.onCompleteResponse(response(tool("a", "buildProject"), tool("b", "buildProject")));
                if (cancel) f.controller.cancel();
                preparation.complete(new ModelRequestGate.Decision(cancel ? ModelRequestGate.Status.ALLOWED
                        : ModelRequestGate.Status.COMPRESSION_FAILED, List.of(), 0, "测试准备失败"));
                gate.awaitIdle();
                assertFalse(f.guard.correctionDelivered());
                assertTrue(f.model.requests.isEmpty());
            }
        }
    }

    @Test
    void 两条恢复路径都携带当前票据且不写入记忆() throws Exception {
        for (boolean protocol : List.of(false, true)) {
            try (var gate = new ManagedModelRequestGate(request -> {
                var messages = new ArrayList<>(request.latestMemory().get().messages());
                messages.addAll(request.transientMessages());
                return CompletableFuture.completedFuture(new ModelRequestGate.Decision(ModelRequestGate.Status.ALLOWED, messages, 1, ""));
            })) {
                Fixture f = new Fixture(false, false, gate, protocol, !protocol);
                var diagnostic = BuildBlockDiagnostic.single(BuildBlockDiagnostic.Reason.NO_PLAN);
                f.guard.observe(0, "previous-a", BuildProgressGuard.Observation.rejected("t", diagnostic, 0));
                f.guard.observe(0, "previous-b", BuildProgressGuard.Observation.rejected("t", diagnostic, 0));
                var ticket = f.guard.pendingFeedback();
                f.root.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(protocol ? "[工具调用] buildProject {}" : "已完成"))
                        .metadata(ChatResponseMetadata.builder().tokenUsage(new TokenUsage()).build()).build());
                gate.awaitIdle();
                assertEquals(1, f.model.requests.size(), "恢复路径必须提交真实测试SDK请求，protocol=" + protocol + "，errors=" + f.errors);
                assertTrue(f.model.requests.getFirst().messages().contains(ticket.message()));
                assertFalse(f.guard.correctionDelivered());
                f.model.lastHandler.onPartialToolExecutionRequest(0, tool("response", "buildProject"));
                assertFalse(f.guard.correctionDelivered());
                f.model.lastHandler.onCompleteResponse(response(tool("response", "buildProject")));
                assertTrue(f.guard.correctionDelivered());
                assertFalse(f.memory.messages.contains(ticket.message()));
                assertTrue(f.errors.isEmpty());
            }
        }
    }

    @Test
    void 携纠偏的响应若为伪工具正文恢复仍须携带未送达票据() throws Exception {
        try (var gate = new ManagedModelRequestGate(request -> {
            var messages = new ArrayList<>(request.latestMemory().get().messages());
            messages.addAll(request.transientMessages());
            return CompletableFuture.completedFuture(new ModelRequestGate.Decision(ModelRequestGate.Status.ALLOWED, messages, 1, ""));
        })) {
            Fixture f = new Fixture(false, false, gate, true, false);
            f.root.onCompleteResponse(response(tool("a", "buildProject"), tool("b", "buildProject")));
            gate.awaitIdle();
            var ticket = f.guard.pendingFeedback();
            assertNotNull(ticket);
            f.model.lastHandler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("[工具调用] buildProject {}"))
                    .metadata(ChatResponseMetadata.builder().tokenUsage(new TokenUsage()).build()).build());
            gate.awaitIdle();
            assertEquals(2, f.model.requests.size());
            assertFalse(f.guard.correctionDelivered());
            assertTrue(f.model.requests.getLast().messages().contains(ticket.message()));
            f.model.lastHandler.onCompleteResponse(response(tool("c", "buildProject")));
            assertEquals(List.of(ToolLoopTerminationProtocol.ControlledTerminationReason.BUILD_STALLED), f.terminations);
            assertTrue(f.errors.isEmpty());
        }
    }

    @Test
    void 同批拒绝只安排反馈下一请求响应后停止且先提交工具结果() {
        Fixture f = new Fixture(false, false);
        f.root.onCompleteResponse(response(tool("a", "buildProject")));
        assertEquals(1, f.guard.blockedCount());
        f.model.lastHandler.onCompleteResponse(response(tool("b", "buildProject"), tool("c", "buildProject")));
        assertEquals(3, f.guard.blockedCount());
        assertEquals(2, f.model.requests.size());
        assertNotNull(f.guard.pendingFeedback());
        assertFalse(f.guard.correctionDelivered());
        assertTrue(f.model.requests.getLast().messages().contains(f.guard.pendingFeedback().message()));
        assertFalse(f.memory.messages.stream().anyMatch(message -> message instanceof dev.langchain4j.data.message.SystemMessage));

        StreamingChatResponseHandler last = f.model.lastHandler;
        last.onCompleteResponse(response(tool("d", "buildProject"), tool("write", "writeFile")));
        assertEquals(List.of(ToolLoopTerminationProtocol.ControlledTerminationReason.BUILD_STALLED), f.terminations);
        assertEquals(0, f.writeCalls.get());
        assertEquals(2, f.model.requests.size());
        assertTrue(f.events.indexOf("memory:d") < f.events.indexOf("termination"));
        assertTrue(f.events.indexOf("tool:buildProject:d") < f.events.indexOf("termination"));
        int count = f.guard.blockedCount();
        last.onCompleteResponse(response(tool("late", "buildProject")));
        assertEquals(count, f.guard.blockedCount());
        assertTrue(f.errors.isEmpty());
    }

    @Test
    void 结果持久化失败不观察也不安排下一请求() {
        Fixture f = new Fixture(true, false);
        f.root.onCompleteResponse(response(tool("a", "buildProject")));
        assertEquals(0, f.guard.blockedCount());
        assertTrue(f.model.requests.isEmpty());
        assertTrue(f.terminations.isEmpty());
        assertFalse(f.errors.isEmpty());
    }

    @Test
    void SDK启动无回调抛错不会确认反馈送达() {
        Fixture f = new Fixture(false, true);
        f.root.onCompleteResponse(response(tool("a", "buildProject"), tool("b", "buildProject")));
        assertEquals(2, f.guard.blockedCount());
        assertFalse(f.guard.correctionDelivered());
        assertTrue(f.terminations.isEmpty());
        assertFalse(f.errors.isEmpty());
    }

    @Test
    void 取消先赢后迟到响应不确认反馈或执行工具() {
        Fixture f = new Fixture(false, false);
        f.root.onCompleteResponse(response(tool("a", "buildProject"), tool("b", "buildProject")));
        f.controller.cancel();
        f.model.lastHandler.onPartialResponse("迟到正文");
        f.model.lastHandler.onCompleteResponse(response(tool("c", "buildProject")));
        assertFalse(f.guard.correctionDelivered());
        assertEquals(2, f.guard.blockedCount());
        assertEquals(List.of(ToolLoopTerminationProtocol.ControlledTerminationReason.CANCELLED), f.terminations);
    }

    @Test
    void 同步有效回调可以确认送达并在第三次拒绝停止() {
        Fixture f = new Fixture(false, false);
        f.model.synchronousBuild = true;
        f.root.onCompleteResponse(response(tool("a", "buildProject"), tool("b", "buildProject")));
        assertEquals(1, f.model.requests.size());
        assertEquals(3, f.guard.blockedCount());
        assertEquals(List.of(ToolLoopTerminationProtocol.ControlledTerminationReason.BUILD_STALLED), f.terminations);
        assertTrue(f.errors.isEmpty());
    }

    private static ToolExecutionRequest tool(String id, String name) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments("{}").build();
    }

    private static ChatResponse response(ToolExecutionRequest... requests) {
        return ChatResponse.builder().aiMessage(AiMessage.from(List.of(requests)))
                .metadata(ChatResponseMetadata.builder().tokenUsage(new TokenUsage()).build()).build();
    }

    private static final class Fixture {
        final List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<Throwable> errors = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<ToolLoopTerminationProtocol.ControlledTerminationReason> terminations = new java.util.concurrent.CopyOnWriteArrayList<>();
        final StreamingRequestController controller = new StreamingRequestController();
        final BuildProgressGuard guard = new BuildProgressGuard(7, "t");
        final PendingModel model;
        final RecordingMemory memory;
        final AtomicInteger writeCalls = new AtomicInteger();
        final GenerationDisclosureBuffer disclosure = new GenerationDisclosureBuffer();
        boolean pauseOnNextBuild;
        final AiServiceStreamingResponseHandler root;

        Fixture(boolean failMemory, boolean failStart) {
            this(failMemory, failStart, null, false, false);
        }

        Fixture(boolean failMemory, boolean failStart, ModelRequestGate gate, boolean protocol, boolean incomplete) {
            this(failMemory, failStart, gate, protocol, incomplete, false);
        }

        Fixture(boolean failMemory, boolean failStart, ModelRequestGate gate, boolean protocol, boolean incomplete, boolean unified) {
            model = new PendingModel(failStart);
            memory = new RecordingMemory(events, failMemory);
            controller.bindBuildProgressGuard(guard);
            controller.onControlledTermination(termination -> {
                events.add("termination"); terminations.add(termination.reason());
            });
            AiServiceContext context = new AiServiceContext(Object.class);
            context.streamingChatModel = model;
            var diagnostic = BuildBlockDiagnostic.single(BuildBlockDiagnostic.Reason.NO_PLAN);
            if (unified) assertTrue(controller.beforeModelRequest());
            GenerationSignalPublisher publisher = unified ? new GenerationSignalPublisher(disclosure, signal -> {
                if (signal instanceof GenerationStreamSignal.ToolExecuted executed) events.add("signal:" + executed.execution().request().id());
            }) : null;
            root = new AiServiceStreamingResponseHandler(new ChatExecutor() {
                public ChatResponse execute() { return null; }
                public ChatResponse execute(List<ChatMessage> messages) { return null; }
            }, context, "7", text -> { }, (index, request) -> { }, (index, request) -> { },
                    execution -> events.add("tool:" + execution.request().name() + ":" + execution.request().id()),
                    response -> { }, errors::add, memory, new TokenUsage(), List.of(),
                    Map.of("buildProject", (request, memoryId) -> {
                        if (pauseOnNextBuild) { pauseOnNextBuild = false; disclosure.pausePublishing(); }
                        return dev.langchain4j.internal.Json.toJson(BuildProjectToolResult.mutationRequired(diagnostic.message()));
                    },
                            "writeFile", (request, memoryId) -> { writeCalls.incrementAndGet(); return "不应执行"; }),
                    null, "method", controller, (name, memoryId, action) ->
                    new ToolExecutionGuard.GuardedToolExecution(action.get(), null,
                            name.equals("buildProject") ? BuildProgressGuard.Observation.rejected("t", diagnostic, 0) : null),
                    controller.latestModelRequestGeneration(), gate, gate == null ? null : com.lyw.appgeneration.ai.memory.ContextContinuationGate.alwaysOpen(),
                    protocol ? new ToolProtocolRecoveryCoordinator(new ToolProtocolRecoveryPolicy(Set.of("buildProject"), ignored -> { }), Set.of("buildProject")) : null,
                    incomplete ? new IncompleteToolChainRecoveryCoordinator(new IncompleteToolChainRecoveryPolicy(
                            () -> IncompleteToolChainRecoveryPolicy.BuildState.GENERATING, ignored -> { })) : null,
                    new com.lyw.appgeneration.ai.memory.ContextCompressionAttemptState(), publisher);
        }
    }

    private static final class PendingModel implements StreamingChatModel {
        final List<ChatRequest> requests = new ArrayList<>();
        final boolean failStart;
        StreamingChatResponseHandler lastHandler;
        boolean synchronousBuild;
        PendingModel(boolean failStart) { this.failStart = failStart; }
        @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            requests.add(request); lastHandler = handler;
            if (failStart) throw new IllegalStateException("测试启动失败");
            if (synchronousBuild) handler.onCompleteResponse(response(tool("sync", "buildProject")));
        }
    }

    private static final class RecordingMemory implements ChatMemory {
        final List<ChatMessage> messages = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> events;
        final boolean fail;
        RecordingMemory(List<String> events, boolean fail) { this.events = events; this.fail = fail; }
        public Object id() { return "7"; }
        public void add(ChatMessage message) {
            if (message instanceof ToolExecutionResultMessage result) {
                if (fail) throw new IllegalStateException("测试持久化失败");
                events.add("memory:" + result.id());
            }
            messages.add(message);
        }
        public List<ChatMessage> messages() { return List.copyOf(messages); }
        public void clear() { messages.clear(); }
    }
}
