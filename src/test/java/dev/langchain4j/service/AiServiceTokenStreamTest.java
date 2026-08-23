package dev.langchain4j.service;

import com.lyw.appgeneration.ai.memory.ContextCompressionAttemptState;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiServiceTokenStreamTest {

    private static final String INTERNAL_PREFIX = "[[internal.";
    private static final String INTERNAL_MARKER = "<internal-ack>";

    private static final String CORRECTION_INSTRUCTION = """
            上一响应未遵守工具调用协议。你在普通正文 content 中输出了工具调用内容，
            这些文本不会被系统执行，也不会展示给用户。

            请重新处理用户的原始请求：
            1. 如果任务需要工具，立即通过接口原生的结构化 tool_calls 调用工具。
            2. 工具名称必须来自当前提供的工具列表。
            3. arguments 必须是符合对应 JSON Schema 的真实 JSON 对象。
            4. 文件源码、路径和修改内容只能放入结构化 arguments。
            5. 不要复制或续写上下文中的历史工具调用格式。
            6. 不要在普通正文输出“[工具调用]”、工具参数 JSON、调用代码块或伪造执行结果。
            7. 只有收到系统返回的真实工具结果后，才能声称操作已经完成。
            8. 如果确实不需要工具，直接返回最终答复。

            不要复述本提示，不要解释错误原因。立即返回正确的结构化工具调用或最终答复。""";
    private static final String DUPLICATE_PSEUDO_TOOL = """
            [工具调用]
            writeFile
            {"path":"src/App.vue"}
            [工具调用]
            writeFile
            {"path":"src/App.vue"}""";

    @Test
    void 统一信号模式与旧流式回调互斥且策略监听器只能安装一次() {
        AiServiceTokenStream stream = tokenStreamWithTools(
                List.of(), Map.of());
        InternalOutputRecoveryPolicy policy = internalPolicy(
                InternalOutputRecoveryPolicy.Mode.RECOVER_ONCE);

        stream.internalOutputRecoveryPolicy(policy)
                .onGenerationStreamSignal(ignored -> { })
                .onPartialResponse(ignored -> { })
                .onError(ignored -> { });

        assertThrows(IllegalConfigurationException.class, stream::start);
        assertThrows(IllegalConfigurationException.class, () ->
                tokenStreamWithTools(List.of(), Map.of())
                        .internalOutputRecoveryPolicy(policy)
                        .internalOutputRecoveryPolicy(policy)
                        .onGenerationStreamSignal(ignored -> { })
                        .onError(ignored -> { })
                        .start());
        assertThrows(IllegalConfigurationException.class, () ->
                tokenStreamWithTools(List.of(), Map.of())
                        .internalOutputRecoveryPolicy(policy)
                        .onGenerationStreamSignal(ignored -> { })
                        .onGenerationStreamSignal(ignored -> { })
                        .onError(ignored -> { })
                        .start());
    }

    @Test
    void 内部正文跨分片命中必须按码点回滚并只恢复一次() throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        List<GenerationStreamSignal> signals = new CopyOnWriteArrayList<>();
        AtomicReference<ToolLoopTerminationProtocol.ControlledTermination>
                terminal = new AtomicReference<>();
        try (ManagedModelRequestGate gate = allowingGate(
                new CopyOnWriteArrayList<>())) {
            recoveryService(model, memory).chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .internalOutputRecoveryPolicy(internalPolicy(
                            InternalOutputRecoveryPolicy.Mode.RECOVER_ONCE))
                    .onGenerationStreamSignal(signals::add)
                    .onControlledTermination(terminal::set)
                    .onError(error -> org.junit.jupiter.api.Assertions.fail(
                            "内部输出首次恢复不应报普通错误", error))
                    .start();
            assertTrue(model.awaitCalls(1));

            model.handler(0).onPartialResponse("开头😀[[inte");
            model.handler(0).onPartialResponse("rnal.");
            assertTrue(model.awaitCalls(2));
            model.handler(1).onCompleteResponse(response(
                    "安全完成", new TokenUsage(2, 3, 5)));
            gate.awaitIdle();

            assertEquals(2, model.callCount());
            assertEquals(new GenerationStreamSignal.AiText(
                    1, "开头😀"), signals.get(0));
            assertEquals(new GenerationStreamSignal.Rollback(
                    1, 3, Set.of()), signals.get(1));
            assertEquals(GenerationStreamSignal.Recovery.Phase.STARTED,
                    ((GenerationStreamSignal.Recovery) signals.get(2)).phase());
            assertEquals(GenerationStreamSignal.Recovery.Phase.RECOVERED,
                    ((GenerationStreamSignal.Recovery) signals.get(3)).phase());
            assertEquals(new GenerationStreamSignal.AiText(
                    2, "安全完成"), signals.get(4));
            assertNull(terminal.get());
            assertFalse(containsAiTextFragment(memory.messages(),
                    INTERNAL_PREFIX));
        }
    }

    @Test
    void 工具参数候选必须阻塞后续请求且命中前不得回调入记忆或执行()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        AtomicInteger toolCalls = new AtomicInteger();
        List<GenerationStreamSignal> signals = new CopyOnWriteArrayList<>();
        try (ManagedModelRequestGate gate = allowingGate(
                new CopyOnWriteArrayList<>())) {
            RecoveryAiService service = AiServices.builder(
                            RecoveryAiService.class)
                    .streamingChatModel(model)
                    .chatMemoryProvider(ignored -> memory)
                    .tools(new RecoveryTools(toolCalls))
                    .build();
            service.chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .internalOutputRecoveryPolicy(internalPolicy(
                            InternalOutputRecoveryPolicy.Mode.RECOVER_ONCE))
                    .onGenerationStreamSignal(signals::add)
                    .onError(error -> org.junit.jupiter.api.Assertions.fail(
                            "工具参数泄漏首次恢复不应报普通错误", error))
                    .start();
            assertTrue(model.awaitCalls(1));

            StreamingChatResponseHandler handler = model.handler(0);
            handler.onPartialToolExecutionRequest(0,
                    toolRequest("leaking", "writeFile",
                            "{\"text\":\"\\u005b\\u005binte"));
            handler.onPartialToolExecutionRequest(1,
                    toolRequest("safe", "writeFile", "{\"text\":\"安全\"}"));
            assertTrue(signals.isEmpty(), "后续 request id 不得越过候选参数");
            handler.onPartialToolExecutionRequest(0,
                    toolRequest("leaking", "writeFile", "rnal.\"}"));
            assertTrue(model.awaitCalls(2));
            gate.awaitIdle();

            assertEquals(0, toolCalls.get());
            assertEquals(2, memory.messages().size(),
                    "内部参数不得形成工具请求或结果记忆");
            assertEquals(2, signals.size());
            assertEquals(new GenerationStreamSignal.Rollback(
                    1, 0, Set.of()), signals.getFirst());
            assertEquals(GenerationStreamSignal.Recovery.Phase.STARTED,
                    ((GenerationStreamSignal.Recovery) signals.get(1)).phase());
        }
    }

    @Test
    void 快速失败正文不得进入旧回调完整响应或记忆() throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        List<String> partials = new CopyOnWriteArrayList<>();
        AtomicReference<ChatResponse> completed = new AtomicReference<>();
        AtomicReference<ToolLoopTerminationProtocol.ControlledTermination>
                terminal = new AtomicReference<>();

        recoveryService(model, memory).chat(7L, "本轮问题")
                .internalOutputRecoveryPolicy(internalPolicy(
                        InternalOutputRecoveryPolicy.Mode.FAIL_FAST))
                .onPartialResponse(partials::add)
                .onCompleteResponse(completed::set)
                .onControlledTermination(terminal::set)
                .onError(error -> org.junit.jupiter.api.Assertions.fail(
                        "快速失败应走受控终止", error))
                .start();
        assertTrue(model.awaitCalls(1));

        model.handler(0).onPartialResponse("可信正文<internal-");
        model.handler(0).onPartialResponse("ack>");

        assertEquals(List.of("可信正文"), partials);
        assertNull(completed.get());
        assertEquals(ToolLoopTerminationProtocol.ControlledTerminationReason
                        .PROTOCOL_ERROR,
                terminal.get().reason());
        assertFalse(containsAiTextFragment(memory.messages(),
                INTERNAL_MARKER));
        assertEquals(1, model.callCount());
    }

    @Test
    void 工具参数分片必须按请求累计扫描但统一回调仍收到原始delta()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        List<GenerationStreamSignal> signals = new CopyOnWriteArrayList<>();
        AtomicInteger toolCalls = new AtomicInteger();
        try (ManagedModelRequestGate gate = allowingGate(
                new CopyOnWriteArrayList<>())) {
            RecoveryAiService service = AiServices.builder(
                            RecoveryAiService.class)
                    .streamingChatModel(model)
                    .chatMemoryProvider(ignored -> memory)
                    .tools(new TextRecoveryTools(toolCalls))
                    .build();
            service.chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .internalOutputRecoveryPolicy(internalPolicy(
                            InternalOutputRecoveryPolicy.Mode.RECOVER_ONCE))
                    .onGenerationStreamSignal(signals::add)
                    .onError(error -> org.junit.jupiter.api.Assertions.fail(
                            "安全 delta 不应报错", error))
                    .start();
            assertTrue(model.awaitCalls(1));

            ToolExecutionRequest first = toolRequest(
                    "delta-tool", "writeFile", "{\"text\":\"安");
            ToolExecutionRequest second = toolRequest(
                    "delta-tool", "writeFile", "全\"}");
            ToolExecutionRequest complete = toolRequest(
                    "delta-tool", "writeFile", "{\"text\":\"安全\"}");
            StreamingChatResponseHandler handler = model.handler(0);
            handler.onPartialToolExecutionRequest(0, first);
            handler.onPartialToolExecutionRequest(0, second);
            handler.onCompleteToolExecutionRequest(0, complete);
            handler.onCompleteResponse(toolResponse(complete));
            assertTrue(model.awaitCalls(2));
            gate.awaitIdle();

            List<GenerationStreamSignal.PartialToolRequest> partials = signals
                    .stream()
                    .filter(GenerationStreamSignal.PartialToolRequest.class
                            ::isInstance)
                    .map(GenerationStreamSignal.PartialToolRequest.class
                            ::cast)
                    .toList();
            assertEquals(List.of(first, second), partials.stream()
                    .map(GenerationStreamSignal.PartialToolRequest::request)
                    .toList());
            assertEquals(complete, signals.stream()
                    .filter(GenerationStreamSignal.CompleteToolRequest.class
                            ::isInstance)
                    .map(GenerationStreamSignal.CompleteToolRequest.class
                            ::cast)
                    .findFirst().orElseThrow().request());
            assertEquals(1, toolCalls.get());
        }
    }

    @Test
    void 完整工具参数单独泄漏必须在披露入记忆和执行前恢复()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        List<GenerationStreamSignal> signals = new CopyOnWriteArrayList<>();
        AtomicInteger toolCalls = new AtomicInteger();
        try (ManagedModelRequestGate gate = allowingGate(
                new CopyOnWriteArrayList<>())) {
            RecoveryAiService service = AiServices.builder(
                            RecoveryAiService.class)
                    .streamingChatModel(model)
                    .chatMemoryProvider(ignored -> memory)
                    .tools(new TextRecoveryTools(toolCalls))
                    .build();
            service.chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .internalOutputRecoveryPolicy(internalPolicy(
                            InternalOutputRecoveryPolicy.Mode.RECOVER_ONCE))
                    .onGenerationStreamSignal(signals::add)
                    .onError(error -> org.junit.jupiter.api.Assertions.fail(
                            "complete-only 泄漏应走内部恢复", error))
                    .start();
            assertTrue(model.awaitCalls(1));

            ToolExecutionRequest leaked = toolRequest(
                    "complete-only", "writeFile",
                    "{\"text\":\"<internal-ack>\"}");
            model.handler(0).onCompleteToolExecutionRequest(0, leaked);
            assertTrue(model.awaitCalls(2));
            gate.awaitIdle();

            assertEquals(0, toolCalls.get());
            assertFalse(memory.messages().stream()
                    .filter(AiMessage.class::isInstance)
                    .map(AiMessage.class::cast)
                    .anyMatch(AiMessage::hasToolExecutionRequests));
            assertEquals(GenerationStreamSignal.Rollback.class,
                    signals.getFirst().getClass());
            assertEquals(GenerationStreamSignal.Recovery.Phase.STARTED,
                    ((GenerationStreamSignal.Recovery) signals.get(1)).phase());
        }
    }

    @Test
    void 工具参数partial与complete不一致必须失败关闭且不得执行()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger toolCalls = new AtomicInteger();
        RecoveryAiService service = AiServices.builder(RecoveryAiService.class)
                .streamingChatModel(model)
                .chatMemoryProvider(ignored -> memory)
                .tools(new TextRecoveryTools(toolCalls))
                .build();
        service.chat(7L, "本轮问题")
                .internalOutputRecoveryPolicy(internalPolicy(
                        InternalOutputRecoveryPolicy.Mode.FAIL_FAST))
                .onPartialResponse(ignored -> { })
                .onError(failure::set)
                .start();
        assertTrue(model.awaitCalls(1));

        StreamingChatResponseHandler handler = model.handler(0);
        handler.onPartialToolExecutionRequest(0, toolRequest(
                "mismatch", "writeFile", "{\"text\":\"安全\"}"));
        handler.onCompleteToolExecutionRequest(0, toolRequest(
                "mismatch", "writeFile", "{\"text\":\"不同\"}"));

        assertInstanceOf(StreamingResponseConsistencyException.class,
                failure.get());
        assertEquals(0, toolCalls.get());
        assertFalse(memory.messages().stream()
                .filter(AiMessage.class::isInstance)
                .map(AiMessage.class::cast)
                .anyMatch(AiMessage::hasToolExecutionRequests));
    }

    @Test
    void 恢复分支再次泄漏必须回滚失败并以协议错误收口且无第三次请求()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        List<GenerationStreamSignal> signals = new CopyOnWriteArrayList<>();
        AtomicReference<ToolLoopTerminationProtocol.ControlledTermination>
                terminal = new AtomicReference<>();
        try (ManagedModelRequestGate gate = allowingGate(
                new CopyOnWriteArrayList<>())) {
            recoveryService(model, memory).chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .internalOutputRecoveryPolicy(internalPolicy(
                            InternalOutputRecoveryPolicy.Mode.RECOVER_ONCE))
                    .onGenerationStreamSignal(signals::add)
                    .onControlledTermination(terminal::set)
                    .onError(error -> org.junit.jupiter.api.Assertions.fail(
                            "二次泄漏应走受控协议错误", error))
                    .start();
            assertTrue(model.awaitCalls(1));
            model.handler(0).onPartialResponse("首代<internal-ack>");
            assertTrue(model.awaitCalls(2));

            model.handler(1).onPartialResponse("恢复😀");
            model.handler(1).onPartialResponse("<internal-ack>");
            gate.awaitIdle();

            assertEquals(2, model.callCount());
            GenerationStreamSignal.Rollback lastRollback = signals.stream()
                    .filter(GenerationStreamSignal.Rollback.class::isInstance)
                    .map(GenerationStreamSignal.Rollback.class::cast)
                    .reduce((first, second) -> second).orElseThrow();
            assertEquals(2, lastRollback.failedGeneration());
            assertEquals(3, lastRollback.codePoints());
            GenerationStreamSignal.Recovery failed = signals.stream()
                    .filter(GenerationStreamSignal.Recovery.class::isInstance)
                    .map(GenerationStreamSignal.Recovery.class::cast)
                    .filter(signal -> signal.phase()
                            == GenerationStreamSignal.Recovery.Phase.FAILED)
                    .findFirst().orElseThrow();
            assertEquals(2L, failed.failedGeneration());
            assertEquals(ToolLoopTerminationProtocol
                            .ControlledTerminationReason.PROTOCOL_ERROR,
                    terminal.get().reason());
        }
    }

    @Test
    void 内部恢复模型同步启动失败必须在STARTED后FAILED并受控协议关闭()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        model.failOnCall(2);
        List<GenerationStreamSignal> signals = new CopyOnWriteArrayList<>();
        AtomicReference<ToolLoopTerminationProtocol.ControlledTermination>
                terminal = new AtomicReference<>();
        AtomicInteger errors = new AtomicInteger();
        try (ManagedModelRequestGate gate = allowingGate(
                new CopyOnWriteArrayList<>())) {
            recoveryService(model, memory).chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .internalOutputRecoveryPolicy(internalPolicy(
                            InternalOutputRecoveryPolicy.Mode.RECOVER_ONCE))
                    .onGenerationStreamSignal(signals::add)
                    .onControlledTermination(terminal::set)
                    .onError(ignored -> errors.incrementAndGet())
                    .start();
            assertTrue(model.awaitCalls(1));

            model.handler(0).onPartialResponse("<internal-ack>");
            gate.awaitIdle();

            assertEquals(2, model.callCount());
            assertEquals(List.of(
                            GenerationStreamSignal.Recovery.Phase.STARTED,
                            GenerationStreamSignal.Recovery.Phase.FAILED),
                    recoveryPhases(signals));
            assertEquals(0, errors.get());
            assertEquals(ToolLoopTerminationProtocol
                            .ControlledTerminationReason.PROTOCOL_ERROR,
                    terminal.get().reason());
        }
    }

    @Test
    void 内部恢复异步失败必须由控制器持有唯一协议终态()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        List<GenerationStreamSignal> signals = new CopyOnWriteArrayList<>();
        AtomicInteger terminations = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        try (ManagedModelRequestGate gate = allowingGate(
                new CopyOnWriteArrayList<>())) {
            TokenStream stream = recoveryService(model, memory)
                    .chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .internalOutputRecoveryPolicy(internalPolicy(
                            InternalOutputRecoveryPolicy.Mode.RECOVER_ONCE))
                    .onGenerationStreamSignal(signals::add)
                    .onCompleteResponse(ignored ->
                            completions.incrementAndGet())
                    .onControlledTermination(ignored ->
                            terminations.incrementAndGet())
                    .onError(ignored -> errors.incrementAndGet());
            StreamingRequestController controller =
                    requestController(stream);
            stream.start();
            assertTrue(model.awaitCalls(1));

            model.handler(0).onPartialResponse("<internal-ack>");
            assertTrue(model.awaitCalls(2));
            model.handler(1).onError(
                    new IllegalStateException("恢复模型异步失败"));
            gate.awaitIdle();

            assertEquals(List.of(
                            GenerationStreamSignal.Recovery.Phase.STARTED,
                            GenerationStreamSignal.Recovery.Phase.FAILED),
                    recoveryPhases(signals));
            assertEquals(1, terminations.get());
            assertEquals(0, errors.get());
            assertEquals(0, completions.get());
            assertFalse(controller.isOpen());
            assertEquals(ToolLoopTerminationProtocol
                            .ControlledTerminationReason.PROTOCOL_ERROR,
                    controller.controlledTermination().reason());
        }
    }

    @Test
    void 内部恢复门禁准备失败必须启动前FAILED并受控协议关闭且无g2()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        List<GenerationStreamSignal> signals = new CopyOnWriteArrayList<>();
        AtomicReference<ToolLoopTerminationProtocol.ControlledTermination>
                terminal = new AtomicReference<>();
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger gateCalls = new AtomicInteger();
        try (ManagedModelRequestGate gate = new ManagedModelRequestGate(
                request -> {
                    if (gateCalls.incrementAndGet() == 1) {
                        return CompletableFuture.completedFuture(
                                new ModelRequestGate.Decision(
                                        ModelRequestGate.Status.ALLOWED,
                                        request.latestMemory().get().messages(),
                                        1, ""));
                    }
                    return CompletableFuture.completedFuture(
                            new ModelRequestGate.Decision(
                                    ModelRequestGate.Status.HARD_LIMIT_REJECTED,
                                    request.latestMemory().get().messages(),
                                    32_768, "恢复上下文过长"));
                })) {
            recoveryService(model, memory).chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .internalOutputRecoveryPolicy(internalPolicy(
                            InternalOutputRecoveryPolicy.Mode.RECOVER_ONCE))
                    .onGenerationStreamSignal(signals::add)
                    .onControlledTermination(terminal::set)
                    .onError(ignored -> errors.incrementAndGet())
                    .start();
            assertTrue(model.awaitCalls(1));

            model.handler(0).onPartialResponse("<internal-ack>");
            gate.awaitIdle();

            assertEquals(1, model.callCount());
            List<GenerationStreamSignal.Recovery> recoveries = signals.stream()
                    .filter(GenerationStreamSignal.Recovery.class::isInstance)
                    .map(GenerationStreamSignal.Recovery.class::cast)
                    .toList();
            assertEquals(1, recoveries.size());
            assertEquals(GenerationStreamSignal.Recovery.Phase.FAILED,
                    recoveries.getFirst().phase());
            assertNull(recoveries.getFirst().recoveryGeneration());
            assertEquals(1L, recoveries.getFirst().failedGeneration());
            assertEquals(0, errors.get());
            assertEquals(ToolLoopTerminationProtocol
                            .ControlledTerminationReason.PROTOCOL_ERROR,
                    terminal.get().reason());
        }
    }

    @Test
    void 并发正文披露期间命中标记必须按Unicode码点生成完整回滚快照()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        CountDownLatch textListenerEntered = new CountDownLatch(1);
        CountDownLatch releaseTextListener = new CountDownLatch(1);
        CountDownLatch violationCallbackReturned = new CountDownLatch(1);
        List<GenerationStreamSignal> signals = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        String disclosedText = "安全\ud83d\ude00";
        try (ManagedModelRequestGate gate = allowingGate(
                new CopyOnWriteArrayList<>())) {
            recoveryService(model, memory).chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .internalOutputRecoveryPolicy(internalPolicy(
                            InternalOutputRecoveryPolicy.Mode.RECOVER_ONCE))
                    .onGenerationStreamSignal(signal -> {
                        signals.add(signal);
                        if (signal instanceof GenerationStreamSignal.AiText) {
                            textListenerEntered.countDown();
                            awaitLatch(releaseTextListener,
                                    "等待释放正文信号监听器超时");
                        }
                    })
                    .onError(threadFailure::set)
                    .start();
            assertTrue(model.awaitCalls(1));
            StreamingChatResponseHandler handler = model.handler(0);

            Thread textThread = Thread.startVirtualThread(() -> {
                try {
                    handler.onPartialResponse(disclosedText);
                } catch (Throwable failure) {
                    threadFailure.compareAndSet(null, failure);
                }
            });
            assertTrue(textListenerEntered.await(2, TimeUnit.SECONDS));
            Thread violationThread = Thread.startVirtualThread(() -> {
                try {
                    handler.onPartialResponse(INTERNAL_MARKER);
                } catch (Throwable failure) {
                    threadFailure.compareAndSet(null, failure);
                } finally {
                    violationCallbackReturned.countDown();
                }
            });

            boolean violationReturnedWhileTextBlocked;
            try {
                violationReturnedWhileTextBlocked =
                        violationCallbackReturned.await(2, TimeUnit.SECONDS);
            } finally {
                releaseTextListener.countDown();
            }
            textThread.join(2_000);
            violationThread.join(2_000);
            gate.awaitIdle();

            assertTrue(violationReturnedWhileTextBlocked,
                    "标记回调应只提交状态和信号，不得等待外部监听器返回");
            assertFalse(textThread.isAlive());
            assertFalse(violationThread.isAlive());
            assertNull(threadFailure.get());
            assertEquals(new GenerationStreamSignal.AiText(
                    1, disclosedText), signals.getFirst());
            GenerationStreamSignal.Rollback rollback = signals.stream()
                    .filter(GenerationStreamSignal.Rollback.class::isInstance)
                    .map(GenerationStreamSignal.Rollback.class::cast)
                    .findFirst().orElseThrow();
            assertEquals(disclosedText.codePointCount(
                            0, disclosedText.length()),
                    rollback.codePoints(),
                    "回滚快照必须包含已进入监听器的全部 Unicode 码点");
        }
    }

    @Test
    void 正文与多个工具信号必须按回调认领顺序由单一发布者披露()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        CountDownLatch textListenerEntered = new CountDownLatch(1);
        CountDownLatch releaseTextListener = new CountDownLatch(1);
        CountDownLatch toolCallbacksReturned = new CountDownLatch(1);
        AtomicInteger activeListeners = new AtomicInteger();
        AtomicInteger maximumListeners = new AtomicInteger();
        List<String> enteredSignals = new CopyOnWriteArrayList<>();
        List<String> completedSignals = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        try (ManagedModelRequestGate gate = allowingGate(
                new CopyOnWriteArrayList<>())) {
            recoveryService(model, memory).chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .internalOutputRecoveryPolicy(internalPolicy(
                            InternalOutputRecoveryPolicy.Mode.RECOVER_ONCE))
                    .onGenerationStreamSignal(signal -> {
                        String label = switch (signal) {
                            case GenerationStreamSignal.AiText text ->
                                    "正文:" + text.text();
                            case GenerationStreamSignal.PartialToolRequest tool ->
                                    "工具:" + tool.request().id();
                            default -> "其他:" + signal.getClass()
                                    .getSimpleName();
                        };
                        int active = activeListeners.incrementAndGet();
                        maximumListeners.accumulateAndGet(active, Math::max);
                        enteredSignals.add(label);
                        try {
                            if (signal instanceof GenerationStreamSignal.AiText) {
                                textListenerEntered.countDown();
                                awaitLatch(releaseTextListener,
                                        "等待释放正文信号监听器超时");
                            }
                            completedSignals.add(label);
                        } finally {
                            activeListeners.decrementAndGet();
                        }
                    })
                    .onError(threadFailure::set)
                    .start();
            assertTrue(model.awaitCalls(1));
            StreamingChatResponseHandler handler = model.handler(0);

            Thread textThread = Thread.startVirtualThread(() -> {
                try {
                    handler.onPartialResponse("先\ud83d\ude00");
                } catch (Throwable failure) {
                    threadFailure.compareAndSet(null, failure);
                }
            });
            assertTrue(textListenerEntered.await(2, TimeUnit.SECONDS));
            Thread toolThread = Thread.startVirtualThread(() -> {
                try {
                    handler.onPartialToolExecutionRequest(0, toolRequest(
                            "ordered-tool-1", "writeFile", "{}"));
                    handler.onPartialToolExecutionRequest(1, toolRequest(
                            "ordered-tool-2", "writeFile", "{}"));
                } catch (Throwable failure) {
                    threadFailure.compareAndSet(null, failure);
                } finally {
                    toolCallbacksReturned.countDown();
                }
            });

            boolean toolsReturnedWhileTextBlocked;
            try {
                toolsReturnedWhileTextBlocked =
                        toolCallbacksReturned.await(2, TimeUnit.SECONDS);
            } finally {
                releaseTextListener.countDown();
            }
            textThread.join(2_000);
            toolThread.join(2_000);
            gate.awaitIdle();

            List<String> expectedOrder = List.of(
                    "正文:先\ud83d\ude00",
                    "工具:ordered-tool-1",
                    "工具:ordered-tool-2");
            assertTrue(toolsReturnedWhileTextBlocked,
                    "后续回调应完成信号认领，不得等待外部监听器返回");
            assertFalse(textThread.isAlive());
            assertFalse(toolThread.isAlive());
            assertNull(threadFailure.get());
            assertEquals(1, maximumListeners.get(),
                    "同一 TokenStream 的外部监听器必须只有一个发布者");
            assertEquals(expectedOrder, enteredSignals,
                    "正文与多工具信号必须保持回调认领顺序");
            assertEquals(expectedOrder, completedSignals,
                    "监听器完成顺序不得越过更早认领的正文信号");
        }
    }

    @Test
    void 并发正文泄漏与工具披露必须由单一发布者线性化且回滚快照完整()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        AtomicInteger activeListeners = new AtomicInteger();
        AtomicInteger maximumListeners = new AtomicInteger();
        CountDownLatch toolListenerEntered = new CountDownLatch(1);
        CountDownLatch releaseToolListener = new CountDownLatch(1);
        List<GenerationStreamSignal> signals = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        try (ManagedModelRequestGate gate = allowingGate(
                new CopyOnWriteArrayList<>())) {
            recoveryService(model, memory).chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .internalOutputRecoveryPolicy(internalPolicy(
                            InternalOutputRecoveryPolicy.Mode.RECOVER_ONCE))
                    .onGenerationStreamSignal(signal -> {
                        int active = activeListeners.incrementAndGet();
                        maximumListeners.accumulateAndGet(active, Math::max);
                        signals.add(signal);
                        if (signal instanceof GenerationStreamSignal
                                .PartialToolRequest) {
                            toolListenerEntered.countDown();
                            awaitLatch(releaseToolListener,
                                    "等待释放工具信号监听器超时");
                        }
                        activeListeners.decrementAndGet();
                    })
                    .onError(threadFailure::set)
                    .start();
            assertTrue(model.awaitCalls(1));
            StreamingChatResponseHandler handler = model.handler(0);

            Thread toolThread = Thread.startVirtualThread(() -> {
                try {
                    handler.onPartialToolExecutionRequest(0, toolRequest(
                            "concurrent-tool", "writeFile", "{}"));
                } catch (Throwable failure) {
                    threadFailure.compareAndSet(null, failure);
                }
            });
            assertTrue(toolListenerEntered.await(2, TimeUnit.SECONDS));
            Thread violationThread = Thread.startVirtualThread(() -> {
                try {
                    handler.onPartialResponse("<internal-ack>");
                } catch (Throwable failure) {
                    threadFailure.compareAndSet(null, failure);
                }
            });
            violationThread.join(250);
            releaseToolListener.countDown();
            toolThread.join(2_000);
            violationThread.join(2_000);
            gate.awaitIdle();

            assertFalse(toolThread.isAlive());
            assertFalse(violationThread.isAlive());
            assertNull(threadFailure.get());
            assertEquals(1, maximumListeners.get(),
                    "同一 TokenStream 的 generation listener 必须只有一个发布者");
            GenerationStreamSignal.Rollback rollback = signals.stream()
                    .filter(GenerationStreamSignal.Rollback.class::isInstance)
                    .map(GenerationStreamSignal.Rollback.class::cast)
                    .findFirst().orElseThrow();
            assertEquals(Set.of("concurrent-tool"),
                    rollback.provisionalToolRequestIds());
        }
    }

    @Test
    void 恢复代完整安全工具批次必须提交后先RECOVERED再披露完整请求()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        List<GenerationStreamSignal> signals = new CopyOnWriteArrayList<>();
        AtomicInteger toolCalls = new AtomicInteger();
        try (ManagedModelRequestGate gate = allowingGate(
                new CopyOnWriteArrayList<>())) {
            RecoveryAiService service = AiServices.builder(
                            RecoveryAiService.class)
                    .streamingChatModel(model)
                    .chatMemoryProvider(ignored -> memory)
                    .tools(new RecoveryTools(toolCalls))
                    .build();
            service.chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .internalOutputRecoveryPolicy(internalPolicy(
                            InternalOutputRecoveryPolicy.Mode.RECOVER_ONCE))
                    .onGenerationStreamSignal(signals::add)
                    .onError(error -> org.junit.jupiter.api.Assertions.fail(
                            "恢复代安全工具批次不应报错", error))
                    .start();
            assertTrue(model.awaitCalls(1));
            model.handler(0).onPartialResponse("<internal-ack>");
            assertTrue(model.awaitCalls(2));

            ToolExecutionRequest request = toolRequest(
                    "recovery-tool", "writeFile", "{}");
            model.handler(1).onCompleteResponse(toolResponse(request));
            assertTrue(model.awaitCalls(3));
            gate.awaitIdle();

            int recovered = signalIndex(signals,
                    signal -> signal instanceof GenerationStreamSignal.Recovery recovery
                            && recovery.phase()
                            == GenerationStreamSignal.Recovery.Phase.RECOVERED);
            int completed = signalIndex(signals,
                    GenerationStreamSignal.CompleteToolRequest.class::isInstance);
            assertTrue(recovered >= 0 && completed > recovered,
                    "工具批次提交成功后必须先发布 RECOVERED 再发布完整工具请求");
            assertEquals(1, toolCalls.get());
        }
    }

    @Test
    void generation监听器对任一信号抛异常都必须唯一协议终止且不走普通收口()
            throws Exception {
        assertGenerationListenerFailure(
                GenerationStreamSignal.AiText.class,
                (handler, model) -> handler.onPartialResponse("安全正文"));
        assertGenerationListenerFailure(
                GenerationStreamSignal.Rollback.class,
                (handler, model) -> handler.onPartialResponse(
                        "<internal-ack>"));
        assertGenerationListenerFailure(
                GenerationStreamSignal.Recovery.class,
                (handler, model) -> handler.onPartialResponse(
                        "<internal-ack>"));
        assertGenerationListenerFailure(
                GenerationStreamSignal.PartialToolRequest.class,
                (handler, model) -> handler.onPartialToolExecutionRequest(
                        0, toolRequest("partial-failure", "writeFile", "{}")));
        assertGenerationListenerFailure(
                GenerationStreamSignal.CompleteToolRequest.class,
                (handler, model) -> handler.onCompleteResponse(toolResponse(
                        toolRequest("complete-failure", "writeFile", "{}"))));
        assertGenerationListenerFailure(
                GenerationStreamSignal.ToolExecuted.class,
                (handler, model) -> handler.onCompleteResponse(toolResponse(
                        toolRequest("executed-failure", "writeFile", "{}"))));
    }

    @Test
    void generation监听器首次失败后已排队后续信号必须熔断且不得续行()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        CountDownLatch textListenerEntered = new CountDownLatch(1);
        CountDownLatch releaseTextListener = new CountDownLatch(1);
        CountDownLatch toolCallbackReturned = new CountDownLatch(1);
        List<Class<?>> attemptedSignals = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        AtomicInteger terminations = new AtomicInteger();
        try (ManagedModelRequestGate gate = allowingGate(
                new CopyOnWriteArrayList<>())) {
            TokenStream stream = recoveryService(model, memory)
                    .chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .internalOutputRecoveryPolicy(internalPolicy(
                            InternalOutputRecoveryPolicy.Mode.RECOVER_ONCE))
                    .onGenerationStreamSignal(signal -> {
                        attemptedSignals.add(signal.getClass());
                        if (signal instanceof GenerationStreamSignal.AiText) {
                            textListenerEntered.countDown();
                            awaitLatch(releaseTextListener,
                                    "等待释放失败正文监听器超时");
                            throw new IllegalStateException("正文 listener 失败");
                        }
                    })
                    .onControlledTermination(ignored ->
                            terminations.incrementAndGet())
                    .onError(threadFailure::set);
            StreamingRequestController controller =
                    requestController(stream);
            stream.start();
            assertTrue(model.awaitCalls(1));
            StreamingChatResponseHandler handler = model.handler(0);

            Thread textThread = Thread.startVirtualThread(() -> {
                try {
                    handler.onPartialResponse("安全正文");
                } catch (Throwable failure) {
                    threadFailure.compareAndSet(null, failure);
                }
            });
            assertTrue(textListenerEntered.await(2, TimeUnit.SECONDS));
            Thread toolThread = Thread.startVirtualThread(() -> {
                try {
                    handler.onPartialToolExecutionRequest(0, toolRequest(
                            "queued-after-failure", "writeFile", "{}"));
                } catch (Throwable failure) {
                    threadFailure.compareAndSet(null, failure);
                } finally {
                    toolCallbackReturned.countDown();
                }
            });
            try {
                assertTrue(toolCallbackReturned.await(2, TimeUnit.SECONDS));
            } finally {
                releaseTextListener.countDown();
            }
            textThread.join(2_000);
            toolThread.join(2_000);
            gate.awaitIdle();

            assertAll(
                    () -> assertNull(threadFailure.get()),
                    () -> assertEquals(List.of(
                                    GenerationStreamSignal.AiText.class),
                            attemptedSignals,
                            "首次 listener 失败后已排队信号不得继续披露"),
                    () -> assertEquals(1, terminations.get()),
                    () -> assertFalse(controller.isOpen()),
                    () -> assertEquals(ToolLoopTerminationProtocol
                                    .ControlledTerminationReason
                                    .PROTOCOL_ERROR,
                            controller.controlledTermination().reason()),
                    () -> assertEquals(1, model.callCount()));
        }
    }

    @Test
    void 旧代正文监听器跨代后失败必须终止当前恢复代且唯一通知协议错误()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        CountDownLatch oldTextEntered = new CountDownLatch(1);
        CountDownLatch releaseOldText = new CountDownLatch(1);
        AtomicInteger terminations = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        try (ManagedModelRequestGate gate = allowingGate(
                new CopyOnWriteArrayList<>())) {
            TokenStream stream = recoveryService(model, memory)
                    .chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .internalOutputRecoveryPolicy(internalPolicy(
                            InternalOutputRecoveryPolicy.Mode.RECOVER_ONCE))
                    .onGenerationStreamSignal(signal -> {
                        if (signal instanceof GenerationStreamSignal.AiText text
                                && text.generation() == 1L) {
                            oldTextEntered.countDown();
                            awaitLatch(releaseOldText,
                                    "等待释放旧代正文监听器超时");
                            throw new IllegalStateException(
                                    "旧代正文 listener 跨代失败");
                        }
                    })
                    .onControlledTermination(termination -> {
                        assertEquals(ToolLoopTerminationProtocol
                                        .ControlledTerminationReason
                                        .PROTOCOL_ERROR,
                                termination.reason());
                        terminations.incrementAndGet();
                    })
                    .onError(ignored -> errors.incrementAndGet());
            StreamingRequestController controller =
                    requestController(stream);
            stream.start();
            assertTrue(model.awaitCalls(1));
            StreamingChatResponseHandler oldHandler = model.handler(0);

            Thread textThread = Thread.startVirtualThread(() ->
                    oldHandler.onPartialResponse("旧代正文"));
            assertTrue(oldTextEntered.await(2, TimeUnit.SECONDS));
            Thread violationThread = Thread.startVirtualThread(() ->
                    oldHandler.onPartialResponse(INTERNAL_MARKER));
            assertTrue(model.awaitCalls(2),
                    "旧 listener 阻塞期间内部恢复代必须能够启动");
            assertEquals(2L, controller.latestModelRequestGeneration());

            releaseOldText.countDown();
            textThread.join(2_000);
            violationThread.join(2_000);
            gate.awaitIdle();

            assertAll(
                    () -> assertFalse(textThread.isAlive()),
                    () -> assertFalse(violationThread.isAlive()),
                    () -> assertEquals(1, terminations.get()),
                    () -> assertEquals(0, errors.get()),
                    () -> assertFalse(controller.isOpen()),
                    () -> assertEquals(ToolLoopTerminationProtocol
                                    .ControlledTerminationReason
                                    .PROTOCOL_ERROR,
                            controller.controlledTermination().reason()),
                    () -> assertEquals(2, model.callCount()));
        }
    }

    @Test
    void 恢复成功监听器阻塞时再次泄漏不得让旧正文越过回滚()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        CountDownLatch recoveredEntered = new CountDownLatch(1);
        CountDownLatch releaseRecovered = new CountDownLatch(1);
        CountDownLatch violationReturned = new CountDownLatch(1);
        List<GenerationStreamSignal> signals =
                new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        AtomicInteger terminations = new AtomicInteger();
        try (ManagedModelRequestGate gate = allowingGate(
                new CopyOnWriteArrayList<>())) {
            recoveryService(model, memory).chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .internalOutputRecoveryPolicy(internalPolicy(
                            InternalOutputRecoveryPolicy.Mode.RECOVER_ONCE))
                    .onGenerationStreamSignal(signal -> {
                        signals.add(signal);
                        if (signal instanceof GenerationStreamSignal
                                .Recovery recovery
                                && recovery.phase()
                                == GenerationStreamSignal.Recovery.Phase
                                .RECOVERED) {
                            recoveredEntered.countDown();
                            awaitLatch(releaseRecovered,
                                    "等待释放恢复成功监听器超时");
                        }
                    })
                    .onControlledTermination(termination -> {
                        assertEquals(ToolLoopTerminationProtocol
                                        .ControlledTerminationReason
                                        .PROTOCOL_ERROR,
                                termination.reason());
                        terminations.incrementAndGet();
                    })
                    .onError(threadFailure::set)
                    .start();
            assertTrue(model.awaitCalls(1));

            model.handler(0).onPartialResponse(INTERNAL_MARKER);
            assertTrue(model.awaitCalls(2));
            StreamingChatResponseHandler recoveryHandler = model.handler(1);

            Thread safeTextThread = Thread.startVirtualThread(() -> {
                try {
                    recoveryHandler.onPartialResponse("恢复正文");
                } catch (Throwable failure) {
                    threadFailure.compareAndSet(null, failure);
                }
            });
            assertTrue(recoveredEntered.await(2, TimeUnit.SECONDS));

            Thread violationThread = Thread.startVirtualThread(() -> {
                try {
                    recoveryHandler.onPartialResponse(INTERNAL_MARKER);
                } catch (Throwable failure) {
                    threadFailure.compareAndSet(null, failure);
                } finally {
                    violationReturned.countDown();
                }
            });
            try {
                assertTrue(violationReturned.await(2, TimeUnit.SECONDS),
                        "再次泄漏回调只提交状态，不得等待外部监听器返回");
            } finally {
                releaseRecovered.countDown();
            }
            safeTextThread.join(2_000);
            violationThread.join(2_000);
            gate.awaitIdle();

            int recoveredIndex = indexOfRecoveryPhase(
                    signals,
                    GenerationStreamSignal.Recovery.Phase.RECOVERED);
            int textIndex = signals.indexOf(
                    new GenerationStreamSignal.AiText(2L, "恢复正文"));
            int rollbackIndex = signals.indexOf(
                    new GenerationStreamSignal.Rollback(
                            2L, 4, Set.of()));

            assertAll(
                    () -> assertNull(threadFailure.get()),
                    () -> assertFalse(safeTextThread.isAlive()),
                    () -> assertFalse(violationThread.isAlive()),
                    () -> assertTrue(recoveredIndex >= 0),
                    () -> assertTrue(textIndex > recoveredIndex,
                            "恢复正文必须紧随 RECOVERED 进入共享总线"),
                    () -> assertTrue(rollbackIndex > textIndex,
                            "再次泄漏的 rollback 必须覆盖此前已提交正文"),
                    () -> assertEquals(1, terminations.get()),
                    () -> assertEquals(2, model.callCount(),
                            "恢复分支再次泄漏不得启动第三次请求"));
        }
    }

    @Test
    void 未完成工具链普通总结必须隔离并自动续行一次() throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        List<IncompleteToolChainRecoveryPolicy.RecoveryPhase> phases =
                new CopyOnWriteArrayList<>();
        List<ModelRequestGate.Request> gateRequests =
                new CopyOnWriteArrayList<>();
        List<String> partials = new CopyOnWriteArrayList<>();
        AtomicReference<IncompleteToolChainRecoveryPolicy.BuildState> state =
                new AtomicReference<>(
                        IncompleteToolChainRecoveryPolicy.BuildState.GENERATING);
        try (ManagedModelRequestGate gate = allowingGate(gateRequests)) {
            recoveryService(model, memory).chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .incompleteToolChainRecoveryPolicy(
                            new IncompleteToolChainRecoveryPolicy(
                                    state::get, phases::add))
                    .onPartialResponse(partials::add)
                    .onError(error -> org.junit.jupiter.api.Assertions.fail(
                            "未完成工具链首次续行不应报错", error))
                    .start();
            assertTrue(model.awaitCalls(1));

            model.handler(0).onPartialResponse("Vue 项目回合结果：成功");
            model.handler(0).onCompleteResponse(response(
                    "Vue 项目回合结果：成功",
                    new TokenUsage(2, 3, 5)));

            assertTrue(model.awaitCalls(2));
            gate.awaitIdle();
            assertFalse(model.request(0).toolChoice() == ToolChoice.REQUIRED,
                    "初始请求不能强制工具调用");
            assertEquals(ToolChoice.REQUIRED,
                    model.request(1).toolChoice(),
                    "未完成工具链自动续行必须在协议层强制工具调用");
            assertTrue(partials.isEmpty(), "提前总结不得下发前端");
            assertFalse(containsAiText(
                    memory.messages(), "Vue 项目回合结果：成功"));
            assertEquals(List.of(
                    IncompleteToolChainRecoveryPolicy.RecoveryPhase.STARTED),
                    phases);
            assertEquals(1, gateRequests.get(1).transientMessages().size());
            String instruction = ((SystemMessage) gateRequests.get(1)
                    .transientMessages().getFirst()).text();
            assertTrue(instruction.contains("尚未达到受信构建终态"));
            assertTrue(instruction.contains("单独调用 buildProject"));
            assertTrue(instruction.contains("不得复述"));
            assertFalse(memory.messages().contains(
                    gateRequests.get(1).transientMessages().getFirst()));
        }
    }

    @Test
    void 未完成工具链续行返回真实工具调用必须发布恢复阶段() throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        AtomicInteger toolCalls = new AtomicInteger();
        List<IncompleteToolChainRecoveryPolicy.RecoveryPhase> phases =
                new CopyOnWriteArrayList<>();
        try (ManagedModelRequestGate gate = allowingGate(
                new CopyOnWriteArrayList<>())) {
            RecoveryAiService service = AiServices.builder(
                            RecoveryAiService.class)
                    .streamingChatModel(model)
                    .chatMemoryProvider(ignored -> memory)
                    .tools(new RecoveryTools(toolCalls))
                    .build();
            service.chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .incompleteToolChainRecoveryPolicy(
                            new IncompleteToolChainRecoveryPolicy(
                                    () -> IncompleteToolChainRecoveryPolicy
                                            .BuildState.GENERATING,
                                    phases::add))
                    .onPartialResponse(ignored -> { })
                    .onError(error -> org.junit.jupiter.api.Assertions.fail(
                            "真实工具调用应恢复未完成工具链", error))
                    .start();
            assertTrue(model.awaitCalls(1));

            model.handler(0).onCompleteResponse(response(
                    "提前总结", new TokenUsage(2, 3, 5)));
            assertTrue(model.awaitCalls(2));
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .id("incomplete-recovery-write")
                    .name("writeFile")
                    .arguments("{}")
                    .build();
            model.handler(1).onCompleteResponse(toolResponse(request));
            assertTrue(model.awaitCalls(3));
            gate.awaitIdle();

            assertEquals(ToolChoice.REQUIRED,
                    model.request(1).toolChoice());
            assertFalse(model.request(2).toolChoice() == ToolChoice.REQUIRED,
                    "首个真实结构化工具调用后必须恢复 AUTO，避免模型被困在工具模式");
            assertEquals(1, toolCalls.get());
            assertEquals(List.of(
                    IncompleteToolChainRecoveryPolicy.RecoveryPhase.STARTED,
                    IncompleteToolChainRecoveryPolicy.RecoveryPhase.RECOVERED),
                    phases);
        }
    }

    @Test
    void 未完成工具链真实构建终态后必须恢复自动选择并允许最终答复()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        AtomicReference<IncompleteToolChainRecoveryPolicy.BuildState> state =
                new AtomicReference<>(
                        IncompleteToolChainRecoveryPolicy.BuildState.GENERATING);
        AtomicReference<ChatResponse> completed = new AtomicReference<>();
        try (ManagedModelRequestGate gate = allowingGate(
                new CopyOnWriteArrayList<>())) {
            RecoveryAiService service = AiServices.builder(
                            RecoveryAiService.class)
                    .streamingChatModel(model)
                    .chatMemoryProvider(ignored -> memory)
                    .tools(new TerminalRecoveryTools(state))
                    .build();
            service.chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .incompleteToolChainRecoveryPolicy(
                            new IncompleteToolChainRecoveryPolicy(
                                    state::get, ignored -> { }))
                    .onPartialResponse(ignored -> { })
                    .onCompleteResponse(completed::set)
                    .onError(error -> org.junit.jupiter.api.Assertions.fail(
                            "构建终态后应允许普通最终答复", error))
                    .start();
            assertTrue(model.awaitCalls(1));

            model.handler(0).onCompleteResponse(response(
                    "提前总结", new TokenUsage(2, 3, 5)));
            assertTrue(model.awaitCalls(2));
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .id("incomplete-recovery-build")
                    .name("buildProject")
                    .arguments("{}")
                    .build();
            model.handler(1).onCompleteResponse(toolResponse(request));
            assertTrue(model.awaitCalls(3));

            assertEquals(ToolChoice.REQUIRED,
                    model.request(1).toolChoice());
            assertFalse(model.request(2).toolChoice() == ToolChoice.REQUIRED,
                    "真实构建进入终态后必须恢复 AUTO，避免被迫继续调用工具");
            model.handler(2).onCompleteResponse(response(
                    "项目构建完成", new TokenUsage(2, 3, 5)));
            gate.awaitIdle();

            assertEquals("项目构建完成",
                    completed.get().aiMessage().text());
        }
    }

    @Test
    void 未完成工具链续行经过伪工具校正后必须同时闭合两种恢复状态()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        AtomicInteger toolCalls = new AtomicInteger();
        List<IncompleteToolChainRecoveryPolicy.RecoveryPhase>
                incompletePhases = new CopyOnWriteArrayList<>();
        List<ToolProtocolRecoveryPolicy.Phase> protocolPhases =
                new CopyOnWriteArrayList<>();
        try (ManagedModelRequestGate gate = allowingGate(
                new CopyOnWriteArrayList<>())) {
            RecoveryAiService service = AiServices.builder(
                            RecoveryAiService.class)
                    .streamingChatModel(model)
                    .chatMemoryProvider(ignored -> memory)
                    .tools(new RecoveryTools(toolCalls))
                    .build();
            service.chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .toolProtocolRecoveryPolicy(
                            recoveryPolicy(protocolPhases))
                    .incompleteToolChainRecoveryPolicy(
                            new IncompleteToolChainRecoveryPolicy(
                                    () -> IncompleteToolChainRecoveryPolicy
                                            .BuildState.GENERATING,
                                    incompletePhases::add))
                    .onPartialResponse(ignored -> { })
                    .onError(error -> org.junit.jupiter.api.Assertions.fail(
                            "嵌套恢复应继续执行真实工具", error))
                    .start();
            assertTrue(model.awaitCalls(1));

            model.handler(0).onCompleteResponse(response(
                    "提前总结", new TokenUsage(2, 3, 5)));
            assertTrue(model.awaitCalls(2));
            model.handler(1).onPartialResponse(DUPLICATE_PSEUDO_TOOL);
            assertTrue(model.awaitCalls(3));
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .id("nested-recovery-write")
                    .name("writeFile")
                    .arguments("{}")
                    .build();
            model.handler(2).onCompleteResponse(toolResponse(request));
            gate.awaitIdle();

            assertEquals(1, toolCalls.get());
            assertEquals(List.of(
                    ToolProtocolRecoveryPolicy.Phase.STARTED,
                    ToolProtocolRecoveryPolicy.Phase.RECOVERED),
                    protocolPhases);
            assertEquals(List.of(
                    IncompleteToolChainRecoveryPolicy.RecoveryPhase.STARTED,
                    IncompleteToolChainRecoveryPolicy.RecoveryPhase.RECOVERED),
                    incompletePhases);
        }
    }

    @Test
    void 未完成工具链第二次提前结束必须独立熔断() throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        List<IncompleteToolChainRecoveryPolicy.RecoveryPhase> phases =
                new CopyOnWriteArrayList<>();
        AtomicReference<ToolLoopTerminationProtocol.ControlledTermination>
                terminal = new AtomicReference<>();
        try (ManagedModelRequestGate gate = allowingGate(
                new CopyOnWriteArrayList<>())) {
            recoveryService(model, memory).chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .incompleteToolChainRecoveryPolicy(
                            new IncompleteToolChainRecoveryPolicy(
                                    () -> IncompleteToolChainRecoveryPolicy
                                            .BuildState.GENERATING,
                                    phases::add))
                    .onPartialResponse(ignored -> { })
                    .onControlledTermination(terminal::set)
                    .onError(error -> org.junit.jupiter.api.Assertions.fail(
                            "二次提前结束应走受控终态", error))
                    .start();
            assertTrue(model.awaitCalls(1));
            model.handler(0).onCompleteResponse(response(
                    "第一次提前总结", new TokenUsage(2, 3, 5)));
            assertTrue(model.awaitCalls(2));

            model.handler(1).onCompleteResponse(response(
                    "第二次提前总结", new TokenUsage(3, 4, 7)));
            gate.awaitIdle();

            assertEquals(2, model.callCount());
            assertEquals(List.of(
                    IncompleteToolChainRecoveryPolicy.RecoveryPhase.STARTED,
                    IncompleteToolChainRecoveryPolicy.RecoveryPhase.FAILED),
                    phases);
            assertEquals(ToolLoopTerminationProtocol
                            .ControlledTerminationReason.INCOMPLETE_TOOL_CHAIN,
                    terminal.get().reason());
            assertFalse(containsAiText(memory.messages(), "第一次提前总结"));
            assertFalse(containsAiText(memory.messages(), "第二次提前总结"));
        }
    }

    @Test
    void 已达到构建终态时普通响应保持原行为() throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        AtomicReference<ChatResponse> completed = new AtomicReference<>();
        List<String> partials = new CopyOnWriteArrayList<>();
        try (ManagedModelRequestGate gate = allowingGate(
                new CopyOnWriteArrayList<>())) {
            recoveryService(model, memory).chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .incompleteToolChainRecoveryPolicy(
                            new IncompleteToolChainRecoveryPolicy(
                                    () -> IncompleteToolChainRecoveryPolicy
                                            .BuildState.SUCCEEDED,
                                    ignored -> { }))
                    .onPartialResponse(partials::add)
                    .onCompleteResponse(completed::set)
                    .onError(error -> org.junit.jupiter.api.Assertions.fail(
                            "构建终态普通响应不应报错", error))
                    .start();
            assertTrue(model.awaitCalls(1));
            model.handler(0).onPartialResponse("构建完成");
            model.handler(0).onCompleteResponse(response(
                    "构建完成", new TokenUsage(2, 3, 5)));
            gate.awaitIdle();

            assertEquals(List.of("构建完成"), partials);
            assertEquals("构建完成", completed.get().aiMessage().text());
            assertTrue(containsAiText(memory.messages(), "构建完成"));
            assertEquals(1, model.callCount());
        }
    }

    @Test
    void 首次重复认领必须原子绑定来源代且合并同代并发确认() {
        ToolProtocolRecoveryCoordinator coordinator =
                new ToolProtocolRecoveryCoordinator(
                        recoveryPolicy(new CopyOnWriteArrayList<>()),
                        Set.of("writeFile"));

        assertEquals(ToolProtocolRecoveryCoordinator.ViolationAction.START_RECOVERY,
                coordinator.claimViolation(7L));
        assertEquals(ToolProtocolRecoveryCoordinator.ViolationAction.IGNORE,
                coordinator.claimViolation(7L));

        coordinator.recoveryStarted();

        assertEquals(ToolProtocolRecoveryCoordinator.ViolationAction.IGNORE,
                coordinator.claimViolation(7L));
        assertEquals(ToolProtocolRecoveryCoordinator.ViolationAction.FAIL,
                coordinator.claimViolation(8L));
    }

    @Test
    void 恢复Policy必须防御性复制工具名并校验公共契约() {
        Set<String> mutableTools = new java.util.HashSet<>(Set.of("writeFile"));
        ToolProtocolRecoveryPolicy policy = new ToolProtocolRecoveryPolicy(
                mutableTools, ignored -> { });

        mutableTools.clear();

        assertEquals(Set.of("writeFile"), policy.registeredToolNames());
        assertThrows(UnsupportedOperationException.class,
                () -> policy.registeredToolNames().add("readFile"));
        assertThrows(NullPointerException.class,
                () -> new ToolProtocolRecoveryPolicy(Set.of("writeFile"), null));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolProtocolRecoveryPolicy(Set.of(" "), ignored -> { }));
    }

    @Test
    void 恢复Policy多填工具名必须在安装时拒绝() {
        AiServiceTokenStream stream = tokenStreamWithTools(
                List.of(toolSpecification("writeFile")),
                Map.of("writeFile", successfulToolExecutor()));

        assertThrows(IllegalArgumentException.class,
                () -> stream.toolProtocolRecoveryPolicy(
                        new ToolProtocolRecoveryPolicy(
                                Set.of("writeFile", "readFile"),
                                ignored -> { })));
    }

    @Test
    void 恢复Policy少填工具名必须在安装时拒绝() {
        AiServiceTokenStream stream = tokenStreamWithTools(
                List.of(toolSpecification("writeFile"),
                        toolSpecification("readFile")),
                Map.of("writeFile", successfulToolExecutor(),
                        "readFile", successfulToolExecutor()));

        assertThrows(IllegalArgumentException.class,
                () -> stream.toolProtocolRecoveryPolicy(
                        new ToolProtocolRecoveryPolicy(
                                Set.of("writeFile"), ignored -> { })));
    }

    @Test
    void 恢复Policy安装时工具执行器错配必须拒绝() {
        AiServiceTokenStream stream = tokenStreamWithTools(
                List.of(toolSpecification("writeFile")),
                Map.of("readFile", successfulToolExecutor()));

        assertThrows(IllegalArgumentException.class,
                () -> stream.toolProtocolRecoveryPolicy(
                        new ToolProtocolRecoveryPolicy(
                                Set.of("writeFile"), ignored -> { })));
    }

    @Test
    void 首次重复伪工具块必须通过临时纠正消息发起且只发起一次恢复请求()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        List<ModelRequestGate.Request> gateRequests = new CopyOnWriteArrayList<>();
        List<ToolProtocolRecoveryPolicy.Phase> phases = new CopyOnWriteArrayList<>();
        List<String> partials = new CopyOnWriteArrayList<>();
        SystemMessage auditedMarker =
                SystemMessage.from("仅存在于门禁 Decision 的审核标记");
        try (ManagedModelRequestGate gate = new ManagedModelRequestGate(
                request -> {
                    gateRequests.add(request);
                    List<ChatMessage> prepared = new ArrayList<>(
                            request.latestMemory().get().messages());
                    prepared.add(auditedMarker);
                    prepared.addAll(request.transientMessages());
                    return CompletableFuture.completedFuture(
                            new ModelRequestGate.Decision(
                                    ModelRequestGate.Status.ALLOWED,
                                    prepared,
                                    prepared.size(),
                                    ""));
                })) {
            TokenStream stream = recoveryService(model, memory).chat(7L, "本轮问题");
            stream.modelRequestGate(gate, directContinuation())
                    .toolProtocolRecoveryPolicy(recoveryPolicy(phases))
                    .onPartialResponse(partials::add)
                    .onError(error -> org.junit.jupiter.api.Assertions.fail(
                            "恢复成功不应报错", error))
                    .start();
            assertTrue(model.awaitCalls(1));

            model.handler(0).onPartialResponse(DUPLICATE_PSEUDO_TOOL);

            assertTrue(model.awaitCalls(2));
            gate.awaitIdle();
            assertEquals(2, model.callCount());
            assertEquals(2, gateRequests.size());
            assertEquals(List.of(ToolProtocolRecoveryPolicy.Phase.STARTED), phases);
            assertTrue(partials.isEmpty(), "两个伪工具块都不能下发给用户");
            ModelRequestGate.Request recoveryGateRequest = gateRequests.get(1);
            assertEquals(1, recoveryGateRequest.transientMessages().size());
            assertEquals(SystemMessage.from(CORRECTION_INSTRUCTION),
                    recoveryGateRequest.transientMessages().getFirst());
            assertEquals(SystemMessage.from(CORRECTION_INSTRUCTION),
                    model.request(1).messages().getLast());
            assertTrue(model.request(1).messages().contains(auditedMarker),
                    "SDK 必须原样使用 Decision.messages 而不是重读 memory");
            assertFalse(memory.messages().contains(auditedMarker));
            assertFalse(memory.messages().contains(
                    SystemMessage.from(CORRECTION_INSTRUCTION)));
            assertFalse(containsAiText(memory.messages(), DUPLICATE_PSEUDO_TOOL));
        }
    }

    @Test
    void 各类流结束伪工具候选都必须隔离并自动纠正一次()
            throws Exception {
        List<String> candidates = List.of(
                "[工具调用] writeFile {\"path\":\"src/App.vue\"}",
                "[工具调用] writeFile {\"path\":\"src/A.vue\"}"
                        + "候选之间的未受信正文"
                        + "[工具调用] writeFile {\"path\":\"src/B.vue\"}",
                "[工具调用] deleteFile {\"path\":\"src/App.vue\"}",
                "[工具调用] writeFile {\"path\":\"a\",\"path\":\"b\"}",
                "[工具调用] writeFile {\"path\":\"src/App.vue\"");

        for (String candidate : candidates) {
            assertStreamFinishedCandidateRecovers(candidate);
        }
    }

    @Test
    void 隔离内容达到上限必须立即自动纠正而不等待流结束()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        List<ToolProtocolRecoveryPolicy.Phase> phases =
                new CopyOnWriteArrayList<>();
        List<String> partials = new CopyOnWriteArrayList<>();
        String quarantined = "[工具调用]" + "x".repeat(
                ToolProtocolRecoveryDetector.QUARANTINE_LIMIT
                        - "[工具调用]".length());
        try (ManagedModelRequestGate gate = allowingGate(
                new CopyOnWriteArrayList<>())) {
            recoveryService(model, memory).chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .toolProtocolRecoveryPolicy(recoveryPolicy(phases))
                    .onPartialResponse(partials::add)
                    .onError(error -> org.junit.jupiter.api.Assertions.fail(
                            "隔离上限应自动纠正", error))
                    .start();
            assertTrue(model.awaitCalls(1));

            model.handler(0).onPartialResponse(quarantined);
            assertTrue(model.awaitCalls(2),
                    "达到隔离上限时必须立即启动纠正 generation");

            model.handler(1).onCompleteResponse(response(
                    "纠正完成", new TokenUsage(4, 5, 9)));
            gate.awaitIdle();

            assertEquals(2, model.callCount());
            assertEquals(List.of(
                    ToolProtocolRecoveryPolicy.Phase.STARTED,
                    ToolProtocolRecoveryPolicy.Phase.RECOVERED), phases);
            assertEquals(List.of("纠正完成"), partials);
            assertFalse(containsAiTextFragment(
                    memory.messages(), "[工具调用]"));
        }
    }

    @Test
    void 混合响应必须只保留可信正文并执行真实结构化工具()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        AtomicInteger toolCalls = new AtomicInteger();
        List<String> partials = new CopyOnWriteArrayList<>();
        List<ToolProtocolRecoveryPolicy.Phase> phases =
                new CopyOnWriteArrayList<>();
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("write-mixed-1")
                .name("writeFile")
                .arguments("{}")
                .build();
        String pseudoTool = DUPLICATE_PSEUDO_TOOL;
        try (ManagedModelRequestGate gate = allowingGate(
                new CopyOnWriteArrayList<>())) {
            RecoveryAiService service = AiServices.builder(
                            RecoveryAiService.class)
                    .streamingChatModel(model)
                    .chatMemoryProvider(ignored -> memory)
                    .tools(new RecoveryTools(toolCalls))
                    .build();
            service.chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .toolProtocolRecoveryPolicy(recoveryPolicy(phases))
                    .onPartialResponse(partials::add)
                    .onError(error -> org.junit.jupiter.api.Assertions.fail(
                            "混合响应中的真实工具应正常执行", error))
                    .start();
            assertTrue(model.awaitCalls(1));

            model.handler(0).onCompleteResponse(toolResponse(
                    "可信前缀" + pseudoTool, request));
            assertTrue(model.awaitCalls(2));
            model.handler(1).onCompleteResponse(response(
                    "完成", new TokenUsage(2, 2, 4)));
            gate.awaitIdle();

            AiMessage storedToolRequest = memory.messages().stream()
                    .filter(AiMessage.class::isInstance)
                    .map(AiMessage.class::cast)
                    .filter(AiMessage::hasToolExecutionRequests)
                    .findFirst()
                    .orElseThrow();
            assertEquals(List.of("可信前缀", "完成"), partials);
            assertEquals("可信前缀", storedToolRequest.text());
            assertEquals(List.of(request),
                    storedToolRequest.toolExecutionRequests());
            assertEquals(1, toolCalls.get());
            assertTrue(phases.isEmpty());
            assertFalse(containsAiTextFragment(
                    memory.messages(), pseudoTool));
        }
    }

    @Test
    void 真实工具分片先到后续伪正文仍必须隔离()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        AtomicInteger toolCalls = new AtomicInteger();
        List<String> partials = new CopyOnWriteArrayList<>();
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("write-before-pseudo")
                .name("writeFile")
                .arguments("{}")
                .build();
        String pseudoTool = DUPLICATE_PSEUDO_TOOL;
        try (ManagedModelRequestGate gate = allowingGate(
                new CopyOnWriteArrayList<>())) {
            RecoveryAiService service = AiServices.builder(
                            RecoveryAiService.class)
                    .streamingChatModel(model)
                    .chatMemoryProvider(ignored -> memory)
                    .tools(new RecoveryTools(toolCalls))
                    .build();
            service.chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .toolProtocolRecoveryPolicy(recoveryPolicy(
                            new CopyOnWriteArrayList<>()))
                    .onPartialResponse(partials::add)
                    .onError(error -> org.junit.jupiter.api.Assertions.fail(
                            "真实工具先到时不应触发恢复失败", error))
                    .start();
            assertTrue(model.awaitCalls(1));

            StreamingChatResponseHandler handler = model.handler(0);
            handler.onPartialToolExecutionRequest(0, request);
            handler.onPartialResponse(pseudoTool);
            handler.onCompleteResponse(toolResponse(pseudoTool, request));
            assertTrue(model.awaitCalls(2));
            model.handler(1).onCompleteResponse(response(
                    "完成", new TokenUsage(2, 2, 4)));
            gate.awaitIdle();

            assertEquals(List.of("完成"), partials);
            assertEquals(1, toolCalls.get());
            assertFalse(containsAiTextFragment(
                    memory.messages(), pseudoTool));
            assertFalse(hasUnpairedToolRequests(memory.messages()));
        }
    }

    @Test
    void 单个伪工具候选先到后续真实工具仍必须执行且不得恢复()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        AtomicInteger toolCalls = new AtomicInteger();
        List<String> partials = new CopyOnWriteArrayList<>();
        List<ToolProtocolRecoveryPolicy.Phase> phases =
                new CopyOnWriteArrayList<>();
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("pseudo-before-write")
                .name("writeFile")
                .arguments("{}")
                .build();
        String pseudoTool =
                "[工具调用] writeFile {\"path\":\"src/App.vue\"}";
        String mixedText = "可信前缀" + pseudoTool;
        try (ManagedModelRequestGate gate = allowingGate(
                new CopyOnWriteArrayList<>())) {
            RecoveryAiService service = AiServices.builder(
                            RecoveryAiService.class)
                    .streamingChatModel(model)
                    .chatMemoryProvider(ignored -> memory)
                    .tools(new RecoveryTools(toolCalls))
                    .build();
            service.chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .toolProtocolRecoveryPolicy(recoveryPolicy(phases))
                    .onPartialResponse(partials::add)
                    .onError(error -> org.junit.jupiter.api.Assertions.fail(
                            "真实工具随后到达时应继续正常执行", error))
                    .start();
            assertTrue(model.awaitCalls(1));

            StreamingChatResponseHandler handler = model.handler(0);
            handler.onPartialResponse(mixedText);
            handler.onPartialToolExecutionRequest(0, request);
            handler.onCompleteResponse(toolResponse(mixedText, request));
            assertTrue(model.awaitCalls(2));
            model.handler(1).onCompleteResponse(response(
                    "完成", new TokenUsage(2, 2, 4)));
            gate.awaitIdle();

            AiMessage storedToolRequest = memory.messages().stream()
                    .filter(AiMessage.class::isInstance)
                    .map(AiMessage.class::cast)
                    .filter(AiMessage::hasToolExecutionRequests)
                    .findFirst()
                    .orElseThrow();
            assertEquals(List.of("可信前缀", "完成"), partials);
            assertEquals("可信前缀", storedToolRequest.text());
            assertEquals(List.of(request),
                    storedToolRequest.toolExecutionRequests());
            assertEquals(1, toolCalls.get());
            assertTrue(phases.isEmpty());
            assertFalse(containsAiTextFragment(
                    memory.messages(), pseudoTool));
            assertFalse(hasUnpairedToolRequests(memory.messages()));
        }
    }

    @Test
    void 不同用户回合必须创建彼此隔离的压缩状态() throws Exception {
        List<ModelRequestGate.Request> firstRequests =
                new CopyOnWriteArrayList<>();
        List<ModelRequestGate.Request> secondRequests =
                new CopyOnWriteArrayList<>();
        RecordingRecoveryModel firstModel = new RecordingRecoveryModel();
        RecordingRecoveryModel secondModel = new RecordingRecoveryModel();
        try (ManagedModelRequestGate firstGate = allowingGate(firstRequests);
             ManagedModelRequestGate secondGate = allowingGate(secondRequests)) {
            recoveryService(firstModel, memoryWithQuestion())
                    .chat(7L, "第一回合")
                    .modelRequestGate(firstGate, directContinuation())
                    .onPartialResponse(ignored -> { })
                    .ignoreErrors()
                    .start();
            recoveryService(secondModel, memoryWithQuestion())
                    .chat(7L, "第二回合")
                    .modelRequestGate(secondGate, directContinuation())
                    .onPartialResponse(ignored -> { })
                    .ignoreErrors()
                    .start();

            assertTrue(firstModel.awaitCalls(1));
            assertTrue(secondModel.awaitCalls(1));
            firstGate.awaitIdle();
            secondGate.awaitIdle();

            assertEquals(1, firstRequests.size());
            assertEquals(1, secondRequests.size());
            assertNotSame(
                    firstRequests.getFirst()
                            .contextCompressionAttemptState(),
                    secondRequests.getFirst()
                            .contextCompressionAttemptState());
        }
    }

    @Test
    void ACTIVE状态下多个续调基于最新完整批次重建临时检查点视图()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        List<ModelRequestGate.Request> gateRequests =
                new CopyOnWriteArrayList<>();
        List<Integer> completedResultCounts =
                new CopyOnWriteArrayList<>();
        AtomicInteger gateCalls = new AtomicInteger();
        try (ManagedModelRequestGate gate = new ManagedModelRequestGate(
                request -> {
                    gateRequests.add(request);
                    int call = gateCalls.getAndIncrement();
                    ContextCompressionAttemptState state =
                            request.contextCompressionAttemptState();
                    if (call == 0) {
                        ContextCompressionAttemptState.CheckpointClaim claim =
                                state.tryEnterCheckpointMode();
                        assertEquals(ContextCompressionAttemptState
                                        .EnterDecision.FIRST_ENTRY,
                                claim.decision());
                        assertTrue(state.markCheckpointReady(claim));
                        return CompletableFuture.completedFuture(
                                new ModelRequestGate.Decision(
                                        ModelRequestGate.Status.ALLOWED,
                                        request.latestMemory().get().messages(),
                                        100,
                                        ""));
                    }
                    assertTrue(state.checkpointProjectionRequired());
                    long resultCount = request.latestMemory().get().messages()
                            .stream()
                            .filter(ToolExecutionResultMessage.class::isInstance)
                            .count();
                    completedResultCounts.add(Math.toIntExact(resultCount));
                    List<ChatMessage> checkpointMessages = List.of(
                            SystemMessage.from("请求级检查点-" + resultCount),
                            UserMessage.from("继续完成任务"));
                    return CompletableFuture.completedFuture(
                            new ModelRequestGate.Decision(
                                    ModelRequestGate.Status.ALLOWED,
                                    checkpointMessages,
                                    checkpointMessages.size(),
                                    ""));
                })) {
            RecoveryAiService service = AiServices.builder(
                            RecoveryAiService.class)
                    .streamingChatModel(model)
                    .chatMemoryProvider(ignored -> memory)
                    .tools(new SourceReturningTools())
                    .build();
            service.chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .onPartialResponse(ignored -> { })
                    .onError(error -> org.junit.jupiter.api.Assertions.fail(
                            "多批次检查点续调不应失败", error))
                    .start();
            assertTrue(model.awaitCalls(1));

            model.handler(0).onCompleteResponse(toolResponse(
                    ToolExecutionRequest.builder().id("source-1")
                            .name("writeFile").arguments("{}").build()));
            assertTrue(model.awaitCalls(2));
            model.handler(1).onCompleteResponse(toolResponse(
                    ToolExecutionRequest.builder().id("source-2")
                            .name("writeFile").arguments("{}").build()));
            assertTrue(model.awaitCalls(3));
            gate.awaitIdle();

            assertEquals(List.of(1, 2), completedResultCounts,
                    "每次续调必须读取到最新完整工具批次");
            Object state = compressionAttemptState(gateRequests.getFirst());
            assertSame(state, compressionAttemptState(gateRequests.get(1)));
            assertSame(state, compressionAttemptState(gateRequests.get(2)));
            assertTrue(model.request(1).messages().toString()
                    .contains("请求级检查点-1"));
            assertTrue(model.request(2).messages().toString()
                    .contains("请求级检查点-2"));
            assertFalse(model.request(1).messages().toString()
                    .contains("绝密原始源码"));
            assertFalse(model.request(2).messages().toString()
                    .contains("绝密原始源码"));
            assertTrue(memory.messages().toString()
                    .contains("绝密原始源码"),
                    "请求级检查点不得改写真实 ChatMemory");
        }
    }

    @Test
    void partial与complete并发确认同一重复块必须只启动一次恢复请求()
            throws Exception {
        for (int round = 0; round < 10; round++) {
            MutableChatMemory memory = memoryWithQuestion();
            RecordingRecoveryModel model = new RecordingRecoveryModel();
            List<ModelRequestGate.Request> gateRequests =
                    new CopyOnWriteArrayList<>();
            List<ToolProtocolRecoveryPolicy.Phase> phases =
                    new CopyOnWriteArrayList<>();
            List<String> partials = new CopyOnWriteArrayList<>();
            AtomicInteger completes = new AtomicInteger();
            AtomicInteger errors = new AtomicInteger();
            AtomicReference<ToolLoopTerminationProtocol.ControlledTermination>
                    terminal = new AtomicReference<>();
            try (ManagedModelRequestGate gate = allowingGate(gateRequests)) {
                recoveryService(model, memory).chat(7L, "本轮问题")
                        .modelRequestGate(gate, directContinuation())
                        .toolProtocolRecoveryPolicy(recoveryPolicy(phases))
                        .onPartialResponse(partials::add)
                        .onCompleteResponse(ignored ->
                                completes.incrementAndGet())
                        .onControlledTermination(terminal::set)
                        .onError(ignored -> errors.incrementAndGet())
                        .start();
                assertTrue(model.awaitCalls(1));

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch release = new CountDownLatch(1);
                StreamingChatResponseHandler handler = model.handler(0);
                try (var threads =
                             Executors.newVirtualThreadPerTaskExecutor()) {
                    Future<?> partial = threads.submit(() -> {
                        ready.countDown();
                        awaitLatch(release, "等待并发释放 partial 超时");
                        handler.onPartialResponse(DUPLICATE_PSEUDO_TOOL);
                    });
                    Future<?> complete = threads.submit(() -> {
                        ready.countDown();
                        awaitLatch(release, "等待并发释放 complete 超时");
                        handler.onCompleteResponse(response(
                                DUPLICATE_PSEUDO_TOOL,
                                new TokenUsage(2, 3, 5)));
                    });
                    try {
                        assertTrue(ready.await(2, TimeUnit.SECONDS),
                                "两个真实 SDK 回调必须同时到达释放点");
                        release.countDown();
                        partial.get(2, TimeUnit.SECONDS);
                        complete.get(2, TimeUnit.SECONDS);
                    } finally {
                        release.countDown();
                    }
                }

                assertTrue(model.awaitCalls(2));
                gate.awaitIdle();
                assertEquals(2, model.callCount(),
                        "并发重复确认不得启动第三次模型请求，round=" + round);
                assertEquals(2, gateRequests.size(),
                        "并发重复确认只能进入一次恢复门禁，round=" + round);
                assertEquals(List.of(
                        ToolProtocolRecoveryPolicy.Phase.STARTED), phases,
                        "同一来源代只能发布一次 STARTED，round=" + round);
                assertEquals(0, errors.get(),
                        "并发重复确认不得退化为普通错误，round=" + round);
                assertEquals(0, completes.get(),
                        "并发重复确认不得完成首代，round=" + round);
                assertNull(terminal.get(),
                        "并发重复确认不得退化为 PROTOCOL_ERROR，round=" + round);
                assertTrue(partials.isEmpty(),
                        "重复伪工具正文不得下发，round=" + round);
                assertFalse(containsAiText(
                        memory.messages(), DUPLICATE_PSEUDO_TOOL),
                        "重复伪工具正文不得进入 memory，round=" + round);
                assertFalse(memory.messages().contains(
                        SystemMessage.from(CORRECTION_INSTRUCTION)),
                        "临时纠正指令不得进入 memory，round=" + round);
            }
        }
    }

    @Test
    void 恢复门禁必须等待并发结构化工具批次写入配对结果后再读取memory()
            throws Exception {
        BlockingToolWriteMemory memory = new BlockingToolWriteMemory(7L);
        memory.add(UserMessage.from("本轮问题"));
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        CountDownLatch trustedPartialEntered = new CountDownLatch(1);
        CountDownLatch releaseTrustedPartial = new CountDownLatch(1);
        CountDownLatch recoveryGateEntered = new CountDownLatch(1);
        AtomicInteger gateCalls = new AtomicInteger();
        List<List<ChatMessage>> gateSnapshots =
                new CopyOnWriteArrayList<>();
        try (ManagedModelRequestGate gate = new ManagedModelRequestGate(
                request -> {
                    List<ChatMessage> snapshot =
                            request.latestMemory().get().messages();
                    gateSnapshots.add(snapshot);
                    if (gateCalls.incrementAndGet() == 2) {
                        recoveryGateEntered.countDown();
                    }
                    List<ChatMessage> prepared = new ArrayList<>(snapshot);
                    prepared.addAll(request.transientMessages());
                    return CompletableFuture.completedFuture(
                            new ModelRequestGate.Decision(
                                    ModelRequestGate.Status.ALLOWED,
                                    prepared,
                                    prepared.size(),
                                    ""));
                })) {
            recoveryService(model, memory).chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .toolProtocolRecoveryPolicy(recoveryPolicy(
                            new CopyOnWriteArrayList<>()))
                    .onPartialResponse(text -> {
                        assertEquals("可信前缀", text);
                        trustedPartialEntered.countDown();
                        awaitLatch(releaseTrustedPartial,
                                "等待释放可信 partial 超时");
                    })
                    .onError(error -> org.junit.jupiter.api.Assertions.fail(
                            "并发工具批次闭合不应报错", error))
                    .start();
            assertTrue(model.awaitCalls(1));
            gate.awaitIdle();

            StreamingChatResponseHandler handler = model.handler(0);
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .id("racing-tool")
                    .name("writeFile")
                    .arguments("{}")
                    .build();
            try (var threads =
                         Executors.newVirtualThreadPerTaskExecutor()) {
                Future<?> partial = threads.submit(() ->
                        handler.onPartialResponse(
                                "可信前缀" + DUPLICATE_PSEUDO_TOOL));
                Future<?> complete = null;
                try {
                    assertTrue(trustedPartialEntered.await(
                                    2, TimeUnit.SECONDS),
                            "重复已判定后必须先阻塞在可信 partial 回调");
                    complete = threads.submit(() ->
                            handler.onCompleteResponse(
                                    toolResponse(request)));
                    assertTrue(memory.toolWriteStarted.await(
                                    2, TimeUnit.SECONDS),
                            "结构化 tool_calls 必须先写入 memory");

                    releaseTrustedPartial.countDown();

                    assertFalse(recoveryGateEntered.await(
                                    250, TimeUnit.MILLISECONDS),
                            "工具批次尚未补齐结果时，恢复门禁不得读取 memory");
                    assertEquals(1, model.callCount(),
                            "孤立 tool_calls 存在时不得启动恢复模型");
                } finally {
                    releaseTrustedPartial.countDown();
                    memory.releaseToolWrite.countDown();
                }
                partial.get(2, TimeUnit.SECONDS);
                if (complete != null) {
                    complete.get(2, TimeUnit.SECONDS);
                }
            } finally {
                releaseTrustedPartial.countDown();
                memory.releaseToolWrite.countDown();
            }

            assertTrue(recoveryGateEntered.await(2, TimeUnit.SECONDS),
                    "工具批次闭合后必须继续恢复门禁");
            assertTrue(model.awaitCalls(2),
                    "工具批次闭合后必须继续启动恢复模型");
            gate.awaitIdle();
            assertEquals(2, gateSnapshots.size());
            assertFalse(hasUnpairedToolRequests(gateSnapshots.get(1)),
                    "恢复门禁读取的 memory 不得包含孤立 tool_calls");
            assertTrue(gateSnapshots.get(1).stream()
                    .filter(ToolExecutionResultMessage.class::isInstance)
                    .map(ToolExecutionResultMessage.class::cast)
                    .anyMatch(result -> result.id().equals("racing-tool")));
        }
    }

    @Test
    void 恢复等待并发工具批次时全局取消必须立即失败且不得进入门禁()
            throws Exception {
        BlockingToolWriteMemory memory = new BlockingToolWriteMemory(7L);
        memory.add(UserMessage.from("本轮问题"));
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        CountDownLatch trustedPartialEntered = new CountDownLatch(1);
        CountDownLatch releaseTrustedPartial = new CountDownLatch(1);
        CountDownLatch recoveryStarted = new CountDownLatch(1);
        CountDownLatch recoveryFailed = new CountDownLatch(1);
        AtomicInteger gateCalls = new AtomicInteger();
        List<ToolProtocolRecoveryPolicy.Phase> phases =
                new CopyOnWriteArrayList<>();
        try (ManagedModelRequestGate gate = new ManagedModelRequestGate(
                request -> {
                    gateCalls.incrementAndGet();
                    List<ChatMessage> prepared = new ArrayList<>(
                            request.latestMemory().get().messages());
                    prepared.addAll(request.transientMessages());
                    return CompletableFuture.completedFuture(
                            new ModelRequestGate.Decision(
                                    ModelRequestGate.Status.ALLOWED,
                                    prepared,
                                    prepared.size(),
                                    ""));
                })) {
            TokenStream stream = recoveryService(model, memory)
                    .chat(7L, "本轮问题");
            stream.modelRequestGate(gate, directContinuation())
                    .toolProtocolRecoveryPolicy(
                            new ToolProtocolRecoveryPolicy(
                                    Set.of("writeFile"), phase -> {
                                phases.add(phase);
                                if (phase == ToolProtocolRecoveryPolicy
                                        .Phase.STARTED) {
                                    recoveryStarted.countDown();
                                } else if (phase == ToolProtocolRecoveryPolicy
                                        .Phase.FAILED) {
                                    recoveryFailed.countDown();
                                }
                            }))
                    .onPartialResponse(text -> {
                        assertEquals("可信前缀", text);
                        trustedPartialEntered.countDown();
                        awaitLatch(releaseTrustedPartial,
                                "等待释放可信 partial 超时");
                    })
                    .onError(error -> org.junit.jupiter.api.Assertions.fail(
                            "全局取消不得冒充普通错误", error))
                    .start();
            assertTrue(model.awaitCalls(1));
            gate.awaitIdle();

            StreamingChatResponseHandler handler = model.handler(0);
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .id("cancel-racing-tool")
                    .name("writeFile")
                    .arguments("{}")
                    .build();
            try (var threads =
                         Executors.newVirtualThreadPerTaskExecutor()) {
                Future<?> partial = threads.submit(() ->
                        handler.onPartialResponse(
                                "可信前缀" + DUPLICATE_PSEUDO_TOOL));
                Future<?> complete = null;
                try {
                    assertTrue(trustedPartialEntered.await(
                                    2, TimeUnit.SECONDS));
                    complete = threads.submit(() ->
                            handler.onCompleteResponse(
                                    toolResponse(request)));
                    assertTrue(memory.toolWriteStarted.await(
                                    2, TimeUnit.SECONDS));
                    releaseTrustedPartial.countDown();
                    assertTrue(recoveryStarted.await(2, TimeUnit.SECONDS));
                    partial.get(2, TimeUnit.SECONDS);

                    Future<?> cancellation = threads.submit(stream::cancel);
                    cancellation.get(250, TimeUnit.MILLISECONDS);
                    assertTrue(recoveryFailed.await(
                                    250, TimeUnit.MILLISECONDS),
                            "全局取消必须立即唤醒恢复等待并闭合失败阶段");
                    assertEquals(List.of(
                            ToolProtocolRecoveryPolicy.Phase.STARTED,
                            ToolProtocolRecoveryPolicy.Phase.FAILED), phases);
                    assertEquals(1, gateCalls.get(),
                            "取消后不得进入第二次恢复门禁");
                    assertEquals(1, model.callCount(),
                            "取消后不得启动恢复模型");
                } finally {
                    releaseTrustedPartial.countDown();
                    memory.releaseToolWrite.countDown();
                }
                partial.get(2, TimeUnit.SECONDS);
                if (complete != null) {
                    complete.get(2, TimeUnit.SECONDS);
                }
            } finally {
                releaseTrustedPartial.countDown();
                memory.releaseToolWrite.countDown();
            }
        }
    }

    @Test
    void 纠正代直接最终正文必须先标记恢复并只累计真实usage()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        List<ToolProtocolRecoveryPolicy.Phase> phases = new CopyOnWriteArrayList<>();
        List<String> events = new CopyOnWriteArrayList<>();
        AtomicReference<ChatResponse> completed = new AtomicReference<>();
        try (ManagedModelRequestGate gate = allowingGate(new CopyOnWriteArrayList<>())) {
            recoveryService(model, memory).chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .toolProtocolRecoveryPolicy(new ToolProtocolRecoveryPolicy(
                            Set.of("writeFile"), phase -> {
                        phases.add(phase);
                        events.add("phase:" + phase);
                    }))
                    .onPartialResponse(text -> events.add("text:" + text))
                    .onCompleteResponse(response -> {
                        completed.set(response);
                        events.add("complete");
                    })
                    .onError(error -> org.junit.jupiter.api.Assertions.fail(
                            "直接正文恢复不应报错", error))
                    .start();
            assertTrue(model.awaitCalls(1));
            model.handler(0).onPartialResponse(DUPLICATE_PSEUDO_TOOL);
            assertTrue(model.awaitCalls(2));

            model.handler(1).onPartialResponse("正确完成");
            model.handler(1).onCompleteResponse(response("正确完成",
                    new TokenUsage(11, 7, 18)));
            gate.awaitIdle();

            assertEquals(List.of(
                    ToolProtocolRecoveryPolicy.Phase.STARTED,
                    ToolProtocolRecoveryPolicy.Phase.RECOVERED), phases);
            assertEquals(List.of("phase:STARTED", "phase:RECOVERED",
                    "text:正确完成", "complete"), events);
            assertEquals(new TokenUsage(11, 7, 18),
                    completed.get().metadata().tokenUsage());
            assertEquals(1, memory.messages().stream()
                    .filter(AiMessage.class::isInstance).count());
            assertFalse(memory.messages().contains(
                    SystemMessage.from(CORRECTION_INSTRUCTION)));
        }
    }

    @Test
    void completeOnly触发恢复必须累计首代与纠正代的真实usage()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        AtomicReference<ChatResponse> completed = new AtomicReference<>();
        try (ManagedModelRequestGate gate = allowingGate(
                new CopyOnWriteArrayList<>())) {
            recoveryService(model, memory).chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .toolProtocolRecoveryPolicy(recoveryPolicy(
                            new CopyOnWriteArrayList<>()))
                    .onPartialResponse(ignored -> { })
                    .onCompleteResponse(completed::set)
                    .onError(error -> org.junit.jupiter.api.Assertions.fail(
                            "complete-only 恢复不应报错", error))
                    .start();
            assertTrue(model.awaitCalls(1));

            model.handler(0).onCompleteResponse(response(
                    DUPLICATE_PSEUDO_TOOL, new TokenUsage(2, 3, 5)));
            assertTrue(model.awaitCalls(2));
            model.handler(1).onCompleteResponse(response(
                    "纠正完成", new TokenUsage(11, 7, 18)));
            gate.awaitIdle();

            assertEquals(new TokenUsage(13, 10, 23),
                    completed.get().metadata().tokenUsage());
        }
    }

    @Test
    void 纠正代发布RECOVERED后SDK错误不得追加FAILED且普通错误只收口一次()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        List<ToolProtocolRecoveryPolicy.Phase> phases =
                new CopyOnWriteArrayList<>();
        AtomicInteger errors = new AtomicInteger();
        AtomicReference<Throwable> observedError = new AtomicReference<>();
        try (ManagedModelRequestGate gate = allowingGate(
                new CopyOnWriteArrayList<>())) {
            recoveryService(model, memory).chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .toolProtocolRecoveryPolicy(recoveryPolicy(phases))
                    .onPartialResponse(ignored -> { })
                    .onError(error -> {
                        observedError.set(error);
                        errors.incrementAndGet();
                    })
                    .start();
            assertTrue(model.awaitCalls(1));
            model.handler(0).onPartialResponse(DUPLICATE_PSEUDO_TOOL);
            assertTrue(model.awaitCalls(2));

            ToolExecutionRequest structuredTool = ToolExecutionRequest.builder()
                    .id("recovered-tool")
                    .name("writeFile")
                    .arguments("{}")
                    .build();
            model.handler(1).onPartialToolExecutionRequest(
                    0, structuredTool);
            IllegalStateException sdkError =
                    new IllegalStateException("纠正代普通错误");
            model.handler(1).onError(sdkError);
            gate.awaitIdle();

            assertEquals(List.of(
                    ToolProtocolRecoveryPolicy.Phase.STARTED,
                    ToolProtocolRecoveryPolicy.Phase.RECOVERED), phases);
            assertEquals(1, errors.get());
            assertSame(sdkError, observedError.get());
        }
    }

    @Test
    void 首代和纠正代completeOnly重复伪工具块必须恢复后协议熔断()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        AtomicInteger gateCalls = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        List<String> partials = new CopyOnWriteArrayList<>();
        List<ToolProtocolRecoveryPolicy.Phase> phases =
                new CopyOnWriteArrayList<>();
        AtomicReference<ToolLoopTerminationProtocol.ControlledTermination> terminal =
                new AtomicReference<>();
        try (ManagedModelRequestGate gate = allowingGate(
                new CopyOnWriteArrayList<>(), gateCalls)) {
            recoveryService(model, memory).chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .toolProtocolRecoveryPolicy(recoveryPolicy(phases))
                    .onPartialResponse(partials::add)
                    .onControlledTermination(terminal::set)
                    .onError(ignored -> errors.incrementAndGet())
                    .start();
            assertTrue(model.awaitCalls(1));

            model.handler(0).onCompleteResponse(response(
                    DUPLICATE_PSEUDO_TOOL, new TokenUsage(2, 3, 5)));
            assertTrue(model.awaitCalls(2),
                    "complete-only 首代重复块必须发起纠正请求");
            model.handler(1).onCompleteResponse(response(
                    DUPLICATE_PSEUDO_TOOL, new TokenUsage(7, 11, 18)));
            gate.awaitIdle();

            assertEquals(2, model.callCount());
            assertEquals(2, gateCalls.get());
            assertEquals(List.of(
                    ToolProtocolRecoveryPolicy.Phase.STARTED,
                    ToolProtocolRecoveryPolicy.Phase.FAILED), phases);
            assertEquals(ToolLoopTerminationProtocol
                            .ControlledTerminationReason.PROTOCOL_ERROR,
                    terminal.get().reason());
            assertEquals(0, errors.get());
            assertTrue(partials.isEmpty(),
                    "complete-only 伪工具正文不得下发给用户");
            assertFalse(containsAiText(
                    memory.messages(), DUPLICATE_PSEUDO_TOOL));
        }
    }

    @Test
    void partial仅覆盖前缀时complete只下发未观察suffix且不得重复展示()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        List<String> partials = new CopyOnWriteArrayList<>();
        AtomicReference<ChatResponse> completed = new AtomicReference<>();
        try (ManagedModelRequestGate gate = allowingGate(
                new CopyOnWriteArrayList<>())) {
            recoveryService(model, memory).chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .toolProtocolRecoveryPolicy(recoveryPolicy(
                            new CopyOnWriteArrayList<>()))
                    .onPartialResponse(partials::add)
                    .onCompleteResponse(completed::set)
                    .onError(error -> org.junit.jupiter.api.Assertions.fail(
                            "正文 suffix 补送不应报错", error))
                    .start();
            assertTrue(model.awaitCalls(1));

            model.handler(0).onPartialResponse("可信前缀");
            model.handler(0).onCompleteResponse(response(
                    "可信前缀与完整后缀", new TokenUsage(3, 5, 8)));
            gate.awaitIdle();

            assertEquals(List.of("可信前缀", "与完整后缀"), partials);
            assertEquals("可信前缀与完整后缀",
                    completed.get().aiMessage().text());
            assertTrue(containsAiText(
                    memory.messages(), "可信前缀与完整后缀"));
        }
    }

    @Test
    void 普通响应已有partial但complete正文为空时必须报告一致性错误()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger completes = new AtomicInteger();
        try (ManagedModelRequestGate gate = allowingGate(
                new CopyOnWriteArrayList<>())) {
            recoveryService(model, memory).chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .toolProtocolRecoveryPolicy(recoveryPolicy(
                            new CopyOnWriteArrayList<>()))
                    .onPartialResponse(ignored -> { })
                    .onCompleteResponse(ignored -> completes.incrementAndGet())
                    .onError(failure::set)
                    .start();
            assertTrue(model.awaitCalls(1));

            model.handler(0).onPartialResponse("可信前缀");
            model.handler(0).onCompleteResponse(response(
                    "", new TokenUsage(3, 5, 8)));
            gate.awaitIdle();

            assertInstanceOf(StreamingResponseConsistencyException.class,
                    failure.get());
            assertEquals(0, completes.get());
            assertFalse(memory.messages().stream()
                    .filter(AiMessage.class::isInstance)
                    .map(AiMessage.class::cast)
                    .anyMatch(message -> message.text() != null));
        }
    }

    @Test
    void 工具响应省略complete正文时必须使用累计可信partial重建消息()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        AtomicInteger toolCalls = new AtomicInteger();
        List<String> partials = new CopyOnWriteArrayList<>();
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("omitted-tool-text")
                .name("writeFile")
                .arguments("{}")
                .build();
        try (ManagedModelRequestGate gate = allowingGate(
                new CopyOnWriteArrayList<>())) {
            RecoveryAiService service = AiServices.builder(
                            RecoveryAiService.class)
                    .streamingChatModel(model)
                    .chatMemoryProvider(ignored -> memory)
                    .tools(new RecoveryTools(toolCalls))
                    .build();
            service.chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .toolProtocolRecoveryPolicy(recoveryPolicy(
                            new CopyOnWriteArrayList<>()))
                    .onPartialResponse(partials::add)
                    .onError(error -> org.junit.jupiter.api.Assertions.fail(
                            "省略工具完整正文时应继续执行", error))
                    .start();
            assertTrue(model.awaitCalls(1));

            StreamingChatResponseHandler handler = model.handler(0);
            handler.onPartialResponse("可信前缀");
            handler.onCompleteResponse(toolResponse("", request));
            assertTrue(model.awaitCalls(2));
            model.handler(1).onCompleteResponse(response(
                    "完成", new TokenUsage(2, 2, 4)));
            gate.awaitIdle();

            AiMessage storedToolRequest = memory.messages().stream()
                    .filter(AiMessage.class::isInstance)
                    .map(AiMessage.class::cast)
                    .filter(AiMessage::hasToolExecutionRequests)
                    .findFirst()
                    .orElseThrow();
            assertEquals("可信前缀", storedToolRequest.text());
            assertEquals(List.of("可信前缀", "完成"), partials);
            assertEquals(1, toolCalls.get());
        }
    }

    @Test
    void 含伪工具文本的非前缀complete必须报告普通流一致性错误()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        List<String> partials = new CopyOnWriteArrayList<>();
        List<ToolProtocolRecoveryPolicy.Phase> phases =
                new CopyOnWriteArrayList<>();
        AtomicReference<ToolLoopTerminationProtocol.ControlledTermination> terminal =
                new AtomicReference<>();
        AtomicInteger completes = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (ManagedModelRequestGate gate = allowingGate(
                new CopyOnWriteArrayList<>())) {
            recoveryService(model, memory).chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .toolProtocolRecoveryPolicy(recoveryPolicy(phases))
                    .onPartialResponse(partials::add)
                    .onCompleteResponse(ignored -> completes.incrementAndGet())
                    .onControlledTermination(terminal::set)
                    .onError(error -> {
                        failure.set(error);
                        errors.incrementAndGet();
                    })
                    .start();
            assertTrue(model.awaitCalls(1));

            model.handler(0).onPartialResponse("已观察可信前缀");
            model.handler(0).onCompleteResponse(response(
                    DUPLICATE_PSEUDO_TOOL, new TokenUsage(3, 5, 8)));
            model.handler(0).onCompleteResponse(response(
                    "迟到完整正文", new TokenUsage(1, 1, 2)));
            model.handler(0).onError(new IllegalStateException("迟到错误"));
            gate.awaitIdle();

            assertEquals(List.of("已观察可信前缀"), partials);
            assertInstanceOf(StreamingResponseConsistencyException.class,
                    failure.get());
            assertEquals(1, errors.get());
            assertEquals(0, completes.get());
            assertNull(terminal.get());
            assertTrue(phases.isEmpty());
            assertEquals(1, model.callCount());
            assertFalse(containsAiText(
                    memory.messages(), DUPLICATE_PSEUDO_TOOL));
        }
    }

    @Test
    void 完全不含工具标记的非前缀complete也必须报告普通流一致性错误()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        List<ToolProtocolRecoveryPolicy.Phase> phases =
                new CopyOnWriteArrayList<>();
        AtomicReference<ToolLoopTerminationProtocol.ControlledTermination> terminal =
                new AtomicReference<>();
        AtomicInteger errors = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (ManagedModelRequestGate gate = allowingGate(
                new CopyOnWriteArrayList<>())) {
            recoveryService(model, memory).chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .toolProtocolRecoveryPolicy(recoveryPolicy(phases))
                    .onPartialResponse(ignored -> { })
                    .onControlledTermination(terminal::set)
                    .onError(error -> {
                        failure.set(error);
                        errors.incrementAndGet();
                    })
                    .start();
            assertTrue(model.awaitCalls(1));

            model.handler(0).onPartialResponse("天气晴朗");
            model.handler(0).onCompleteResponse(response(
                    "风和日丽", new TokenUsage(3, 5, 8)));
            gate.awaitIdle();

            assertInstanceOf(StreamingResponseConsistencyException.class,
                    failure.get());
            assertEquals(1, errors.get());
            assertNull(terminal.get());
            assertTrue(phases.isEmpty());
            assertFalse(containsAiText(memory.messages(), "风和日丽"));
        }
    }

    @Test
    void 纠正代发生非前缀complete必须保留STARTED并只报告普通一致性错误()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        List<ToolProtocolRecoveryPolicy.Phase> phases =
                new CopyOnWriteArrayList<>();
        List<String> partials = new CopyOnWriteArrayList<>();
        AtomicReference<ToolLoopTerminationProtocol.ControlledTermination>
                terminal = new AtomicReference<>();
        AtomicInteger completes = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (ManagedModelRequestGate gate = allowingGate(
                new CopyOnWriteArrayList<>())) {
            recoveryService(model, memory).chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .toolProtocolRecoveryPolicy(recoveryPolicy(phases))
                    .onPartialResponse(partials::add)
                    .onCompleteResponse(ignored -> completes.incrementAndGet())
                    .onControlledTermination(terminal::set)
                    .onError(error -> {
                        failure.set(error);
                        errors.incrementAndGet();
                    })
                    .start();
            assertTrue(model.awaitCalls(1));
            model.handler(0).onPartialResponse(DUPLICATE_PSEUDO_TOOL);
            assertTrue(model.awaitCalls(2));

            StreamingChatResponseHandler recoveryHandler = model.handler(1);
            recoveryHandler.onPartialResponse("[工具调");
            recoveryHandler.onCompleteResponse(response(
                    "完全不同的普通正文", new TokenUsage(3, 5, 8)));
            recoveryHandler.onCompleteResponse(response(
                    "迟到完整正文", new TokenUsage(1, 1, 2)));
            recoveryHandler.onError(new IllegalStateException("迟到错误"));
            gate.awaitIdle();

            assertEquals(List.of(
                    ToolProtocolRecoveryPolicy.Phase.STARTED), phases);
            assertInstanceOf(StreamingResponseConsistencyException.class,
                    failure.get());
            assertEquals(1, errors.get());
            assertEquals(0, completes.get());
            assertNull(terminal.get(),
                    "恢复代流一致性错误不得冒充 PROTOCOL_ERROR");
            assertTrue(partials.isEmpty(),
                    "缓冲片段与不可信完整正文都不得下发");
            assertEquals(2, model.callCount());
            assertFalse(containsAiText(
                    memory.messages(), "完全不同的普通正文"));
            assertFalse(containsAiText(
                    memory.messages(), "迟到完整正文"));
            assertFalse(containsAiText(
                    memory.messages(), DUPLICATE_PSEUDO_TOOL));
            assertFalse(memory.messages().contains(
                    SystemMessage.from(CORRECTION_INSTRUCTION)));
        }
    }

    @Test
    void 纠正代再次重复伪工具块必须二次熔断且严格只有两次请求和两次门禁()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        AtomicInteger gateCalls = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        List<ToolProtocolRecoveryPolicy.Phase> phases = new CopyOnWriteArrayList<>();
        AtomicReference<ToolLoopTerminationProtocol.ControlledTermination> terminal =
                new AtomicReference<>();
        try (ManagedModelRequestGate gate = allowingGate(
                new CopyOnWriteArrayList<>(), gateCalls)) {
            recoveryService(model, memory).chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .toolProtocolRecoveryPolicy(recoveryPolicy(phases))
                    .onPartialResponse(ignored -> { })
                    .onControlledTermination(terminal::set)
                    .onError(ignored -> errors.incrementAndGet())
                    .start();
            assertTrue(model.awaitCalls(1));
            model.handler(0).onPartialResponse(DUPLICATE_PSEUDO_TOOL);
            assertTrue(model.awaitCalls(2));

            model.handler(1).onPartialResponse(DUPLICATE_PSEUDO_TOOL);
            gate.awaitIdle();

            assertEquals(2, model.callCount());
            assertEquals(2, gateCalls.get());
            assertEquals(List.of(ToolProtocolRecoveryPolicy.Phase.STARTED,
                    ToolProtocolRecoveryPolicy.Phase.FAILED), phases);
            assertEquals(ToolLoopTerminationProtocol
                            .ControlledTerminationReason.PROTOCOL_ERROR,
                    terminal.get().reason());
            assertEquals(0, errors.get());
            assertFalse(containsAiText(memory.messages(), DUPLICATE_PSEUDO_TOOL));
            assertFalse(memory.messages().contains(
                    SystemMessage.from(CORRECTION_INSTRUCTION)));
        }
    }

    @Test
    void 纠正代真实工具调用恢复后可正常续调但后续退化不得重获纠正额度()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        AtomicInteger toolCalls = new AtomicInteger();
        List<ModelRequestGate.Request> gateRequests =
                new CopyOnWriteArrayList<>();
        List<ToolProtocolRecoveryPolicy.Phase> phases = new CopyOnWriteArrayList<>();
        AtomicReference<ToolLoopTerminationProtocol.ControlledTermination> terminal =
                new AtomicReference<>();
        try (ManagedModelRequestGate gate = allowingGate(gateRequests)) {
            RecoveryAiService service = AiServices.builder(RecoveryAiService.class)
                    .streamingChatModel(model)
                    .chatMemoryProvider(ignored -> memory)
                    .tools(new RecoveryTools(toolCalls))
                    .build();
            service.chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .toolProtocolRecoveryPolicy(recoveryPolicy(phases))
                    .onPartialResponse(ignored -> { })
                    .onControlledTermination(terminal::set)
                    .onError(error -> org.junit.jupiter.api.Assertions.fail(
                            "真实工具恢复不应报普通错误", error))
                    .start();
            assertTrue(model.awaitCalls(1));
            model.handler(0).onPartialResponse(DUPLICATE_PSEUDO_TOOL);
            assertTrue(model.awaitCalls(2));

            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .id("write-1").name("writeFile").arguments("{}").build();
            model.handler(1).onPartialToolExecutionRequest(0, request);
            model.handler(1).onCompleteResponse(toolResponse(request));
            assertTrue(model.awaitCalls(3));
            assertEquals(1, toolCalls.get());
            assertEquals(3, gateRequests.size());
            Object initialState = compressionAttemptState(
                    gateRequests.get(0));
            assertSame(initialState,
                    compressionAttemptState(gateRequests.get(1)),
                    "recovery 必须复用 initial 的共享状态");
            assertSame(initialState,
                    compressionAttemptState(gateRequests.get(2)),
                    "continuation 必须复用 initial 的共享状态");
            assertEquals(List.of(ToolProtocolRecoveryPolicy.Phase.STARTED,
                    ToolProtocolRecoveryPolicy.Phase.RECOVERED), phases);

            model.handler(2).onPartialResponse(DUPLICATE_PSEUDO_TOOL);
            gate.awaitIdle();

            assertEquals(3, model.callCount(),
                    "正常工具续调允许第三次，但再次退化不能发起第四次纠正");
            assertEquals(List.of(ToolProtocolRecoveryPolicy.Phase.STARTED,
                    ToolProtocolRecoveryPolicy.Phase.RECOVERED,
                    ToolProtocolRecoveryPolicy.Phase.FAILED), phases);
            assertEquals(ToolLoopTerminationProtocol
                            .ControlledTerminationReason.PROTOCOL_ERROR,
                    terminal.get().reason());
            assertTrue(memory.messages().stream()
                    .anyMatch(ToolExecutionResultMessage.class::isInstance));
        }
    }

    @Test
    void 恢复状态监听器异常不得反向破坏恢复与正常完成() throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        AtomicReference<ChatResponse> completed = new AtomicReference<>();
        try (ManagedModelRequestGate gate = allowingGate(new CopyOnWriteArrayList<>())) {
            recoveryService(model, memory).chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .toolProtocolRecoveryPolicy(new ToolProtocolRecoveryPolicy(
                            Set.of("writeFile"), ignored -> {
                        throw new IllegalStateException("监听器失败");
                    }))
                    .onPartialResponse(ignored -> { })
                    .onCompleteResponse(completed::set)
                    .onError(error -> org.junit.jupiter.api.Assertions.fail(
                            "监听器异常必须隔离", error))
                    .start();
            assertTrue(model.awaitCalls(1));
            model.handler(0).onPartialResponse(DUPLICATE_PSEUDO_TOOL);
            assertTrue(model.awaitCalls(2));
            model.handler(1).onCompleteResponse(response(
                    "完成", new TokenUsage(1, 2, 3)));
            gate.awaitIdle();

            assertEquals("完成", completed.get().aiMessage().text());
        }
    }

    @Test
    void 未安装恢复Policy时伪工具正文保持原有流式行为() {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        List<String> partials = new ArrayList<>();
        AtomicReference<ChatResponse> completed = new AtomicReference<>();
        recoveryService(model, memory).chat(7L, "本轮问题")
                .onPartialResponse(partials::add)
                .onCompleteResponse(completed::set)
                .onError(error -> org.junit.jupiter.api.Assertions.fail(
                        "未启用恢复不应报错", error))
                .start();

        model.handler(0).onPartialResponse(DUPLICATE_PSEUDO_TOOL);
        model.handler(0).onCompleteResponse(response(
                DUPLICATE_PSEUDO_TOOL, new TokenUsage(2, 3, 5)));

        assertEquals(List.of(DUPLICATE_PSEUDO_TOOL), partials);
        assertEquals(DUPLICATE_PSEUDO_TOOL,
                completed.get().aiMessage().text());
        assertEquals(1, model.callCount());
    }

    @Test
    void 旧代晚到的所有SDK入口必须按generation丢弃且不改变纠正代终态()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        AtomicInteger errors = new AtomicInteger();
        AtomicReference<ChatResponse> completed = new AtomicReference<>();
        List<String> partials = new CopyOnWriteArrayList<>();
        List<ToolProtocolRecoveryPolicy.Phase> phases = new CopyOnWriteArrayList<>();
        try (ManagedModelRequestGate gate = allowingGate(new CopyOnWriteArrayList<>())) {
            recoveryService(model, memory).chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .toolProtocolRecoveryPolicy(recoveryPolicy(phases))
                    .onPartialResponse(partials::add)
                    .onCompleteResponse(completed::set)
                    .onError(ignored -> errors.incrementAndGet())
                    .start();
            assertTrue(model.awaitCalls(1));
            StreamingChatResponseHandler oldHandler = model.handler(0);
            oldHandler.onPartialResponse(DUPLICATE_PSEUDO_TOOL);
            assertTrue(model.awaitCalls(2));

            oldHandler.onPartialResponse("旧代迟到正文");
            oldHandler.onCompleteResponse(response(
                    "旧代迟到完成", new TokenUsage(99, 99, 198)));
            oldHandler.onError(new IllegalStateException("旧代迟到错误"));

            model.handler(1).onCompleteResponse(response(
                    "纠正代完成", new TokenUsage(4, 5, 9)));
            gate.awaitIdle();

            assertEquals(0, errors.get());
            assertTrue(partials.stream().noneMatch(
                    text -> text.contains("旧代迟到")));
            assertEquals("纠正代完成", completed.get().aiMessage().text());
            assertEquals(new TokenUsage(4, 5, 9),
                    completed.get().metadata().tokenUsage());
            assertFalse(containsAiText(memory.messages(), "旧代迟到完成"));
            assertEquals(List.of(ToolProtocolRecoveryPolicy.Phase.STARTED,
                    ToolProtocolRecoveryPolicy.Phase.RECOVERED), phases);
        }
    }

    @Test
    void 恢复门禁硬拒绝必须标记失败且普通错误只收口一次()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        AtomicInteger gateCalls = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        List<ToolProtocolRecoveryPolicy.Phase> phases = new CopyOnWriteArrayList<>();
        try (ManagedModelRequestGate gate = new ManagedModelRequestGate(request -> {
            int call = gateCalls.incrementAndGet();
            if (call == 1) {
                return CompletableFuture.completedFuture(
                        new ModelRequestGate.Decision(
                                ModelRequestGate.Status.ALLOWED,
                                request.latestMemory().get().messages(),
                                1,
                                ""));
            }
            return CompletableFuture.completedFuture(
                    new ModelRequestGate.Decision(
                            ModelRequestGate.Status.HARD_LIMIT_REJECTED,
                            request.latestMemory().get().messages(),
                            32_768,
                            "纠正上下文过长"));
        })) {
            recoveryService(model, memory).chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .toolProtocolRecoveryPolicy(recoveryPolicy(phases))
                    .onPartialResponse(ignored -> { })
                    .onError(error -> {
                        assertInstanceOf(ModelRequestGateException.class, error);
                        errors.incrementAndGet();
                    })
                    .start();
            assertTrue(model.awaitCalls(1));

            model.handler(0).onPartialResponse(DUPLICATE_PSEUDO_TOOL);
            gate.awaitIdle();

            assertEquals(1, model.callCount());
            assertEquals(2, gateCalls.get());
            assertEquals(1, errors.get());
            assertEquals(List.of(ToolProtocolRecoveryPolicy.Phase.STARTED,
                    ToolProtocolRecoveryPolicy.Phase.FAILED), phases);
        }
    }

    @Test
    void 恢复门禁调度被拒绝必须标记失败且不能启动纠正模型() {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        AtomicInteger dispatchCalls = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        List<ToolProtocolRecoveryPolicy.Phase> phases = new CopyOnWriteArrayList<>();
        ModelRequestGate gate = new ModelRequestGate() {
            @Override
            public java.util.concurrent.CompletionStage<Decision> prepare(
                    Request request) {
                List<ChatMessage> prepared = new ArrayList<>(
                        request.latestMemory().get().messages());
                prepared.addAll(request.transientMessages());
                return CompletableFuture.completedFuture(
                        new Decision(Status.ALLOWED, prepared, 1, ""));
            }

            @Override
            public java.util.concurrent.CompletionStage<DispatchStatus> onPrepared(
                    java.util.concurrent.CompletionStage<Decision> preparation,
                    java.util.function.BiConsumer<Decision, Throwable> completion) {
                if (dispatchCalls.incrementAndGet() == 1) {
                    preparation.whenComplete(completion);
                    return CompletableFuture.completedFuture(
                            DispatchStatus.DISPATCHED);
                }
                return CompletableFuture.completedFuture(
                        DispatchStatus.REJECTED);
            }
        };
        recoveryService(model, memory).chat(7L, "本轮问题")
                .modelRequestGate(gate, directContinuation())
                .toolProtocolRecoveryPolicy(recoveryPolicy(phases))
                .onPartialResponse(ignored -> { })
                .onError(ignored -> errors.incrementAndGet())
                .start();
        assertEquals(1, model.callCount());

        model.handler(0).onPartialResponse(DUPLICATE_PSEUDO_TOOL);

        assertEquals(1, model.callCount());
        assertEquals(2, dispatchCalls.get());
        assertEquals(1, errors.get());
        assertEquals(List.of(ToolProtocolRecoveryPolicy.Phase.STARTED,
                ToolProtocolRecoveryPolicy.Phase.FAILED), phases);
    }

    @Test
    void 恢复模型启动失败必须标记失败且错误只收口一次()
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        model.failOnCall(2);
        AtomicInteger errors = new AtomicInteger();
        List<ToolProtocolRecoveryPolicy.Phase> phases = new CopyOnWriteArrayList<>();
        try (ManagedModelRequestGate gate = allowingGate(new CopyOnWriteArrayList<>())) {
            recoveryService(model, memory).chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .toolProtocolRecoveryPolicy(recoveryPolicy(phases))
                    .onPartialResponse(ignored -> { })
                    .onError(ignored -> errors.incrementAndGet())
                    .start();
            assertTrue(model.awaitCalls(1));

            model.handler(0).onPartialResponse(DUPLICATE_PSEUDO_TOOL);
            gate.awaitIdle();

            assertEquals(2, model.callCount());
            assertEquals(1, errors.get());
            assertEquals(List.of(ToolProtocolRecoveryPolicy.Phase.STARTED,
                    ToolProtocolRecoveryPolicy.Phase.FAILED), phases);
        }
    }

    @Test
    void 首次请求必须使用门禁完成后的活动记忆而不是构造时旧快照()
            throws Exception {
        MutableChatMemory memory = new MutableChatMemory(7L);
        memory.add(UserMessage.from("旧上下文".repeat(10_000)));
        memory.add(AiMessage.from("旧回复"));
        AtomicReference<ChatRequest> capturedRequest = new AtomicReference<>();
        AtomicInteger modelCalls = new AtomicInteger();
        StreamingChatModel model = new StreamingChatModel() {
            @Override
            public void doChat(
                    ChatRequest request,
                    dev.langchain4j.model.chat.response
                            .StreamingChatResponseHandler handler) {
                capturedRequest.set(request);
                modelCalls.incrementAndGet();
            }
        };
        TestAiService service = AiServices.builder(TestAiService.class)
                .streamingChatModel(model)
                .chatMemoryProvider(ignored -> memory)
                .build();
        List<ChatMessage> compressedMessages = List.of(
                UserMessage.from("压缩摘要"),
                AiMessage.from("已读取摘要"),
                UserMessage.from("本轮问题"));
        try (ManagedModelRequestGate gate = new ManagedModelRequestGate(request -> {
            assertEquals(7L, request.memoryId());
            assertSame(memory, request.latestMemory().get());
            memory.replaceWith(compressedMessages);
            return CompletableFuture.completedFuture(
                    new ModelRequestGate.Decision(
                            ModelRequestGate.Status.ALLOWED,
                            request.latestMemory().get().messages(),
                            12_000,
                            ""));
        })) {
            TokenStream stream = service.chat(7L, "本轮问题");

            stream.modelRequestGate(gate, action -> {
                        action.run();
                        return true;
                    })
                    .onPartialResponse(ignored -> { })
                    .ignoreErrors()
                    .start();
            gate.awaitIdle();

            assertEquals(1, modelCalls.get());
            assertEquals(compressedMessages,
                    capturedRequest.get().messages());
        }
    }

    @Test
    void 首次请求等待门禁期间取消后晚到结果不得启动模型()
            throws Exception {
        MutableChatMemory memory = new MutableChatMemory(7L);
        memory.add(UserMessage.from("旧上下文"));
        AtomicInteger modelCalls = new AtomicInteger();
        StreamingChatModel model = new StreamingChatModel() {
            @Override
            public void doChat(
                    ChatRequest request,
                    dev.langchain4j.model.chat.response
                            .StreamingChatResponseHandler handler) {
                modelCalls.incrementAndGet();
            }
        };
        TestAiService service = AiServices.builder(TestAiService.class)
                .streamingChatModel(model)
                .chatMemoryProvider(ignored -> memory)
                .build();
        CompletableFuture<ModelRequestGate.Decision> preparation =
                new CompletableFuture<>();
        try (ManagedModelRequestGate gate =
                     new ManagedModelRequestGate(request -> preparation)) {
            TokenStream stream = service.chat(7L, "本轮问题");
            stream.modelRequestGate(
                            gate,
                            action -> {
                                action.run();
                                return true;
                            })
                    .onPartialResponse(ignored -> { })
                    .ignoreErrors()
                    .start();

            stream.cancel();
            preparation.complete(new ModelRequestGate.Decision(
                    ModelRequestGate.Status.ALLOWED,
                    memory.messages(),
                    10,
                    ""));
            gate.awaitIdle();

            assertEquals(0, modelCalls.get());
        }
    }

    @Test
    void 首次门禁硬拒绝必须保留拒绝类型与阶段且不调用模型()
            throws Exception {
        MutableChatMemory memory = new MutableChatMemory(7L);
        memory.add(UserMessage.from("超长上下文"));
        AtomicInteger modelCalls = new AtomicInteger();
        StreamingChatModel model = new StreamingChatModel() {
            @Override
            public void doChat(
                    ChatRequest request,
                    dev.langchain4j.model.chat.response
                            .StreamingChatResponseHandler handler) {
                modelCalls.incrementAndGet();
            }
        };
        TestAiService service = AiServices.builder(TestAiService.class)
                .streamingChatModel(model)
                .chatMemoryProvider(ignored -> memory)
                .build();
        AtomicReference<Throwable> error = new AtomicReference<>();
        try (ManagedModelRequestGate gate = new ManagedModelRequestGate(
                request -> CompletableFuture.completedFuture(
                        new ModelRequestGate.Decision(
                                ModelRequestGate.Status.HARD_LIMIT_REJECTED,
                                request.latestMemory().get().messages(),
                                37_785,
                                "本轮上下文无法安全继续，生成已停止，请重试")))) {
            TokenStream stream = service.chat(7L, "本轮问题");

            stream.modelRequestGate(gate, action -> {
                        action.run();
                        return true;
                    })
                    .onPartialResponse(ignored -> { })
                    .onError(error::set)
                    .start();
            gate.awaitIdle();

            assertEquals(0, modelCalls.get());
            ModelRequestGateException rejection = assertInstanceOf(
                    ModelRequestGateException.class, error.get());
            assertEquals(ModelRequestGateException.Stage.INITIAL,
                    rejection.stage());
            assertEquals(ModelRequestGate.Status.HARD_LIMIT_REJECTED,
                    rejection.status());
            assertEquals("本轮上下文无法安全继续，生成已停止，请重试",
                    rejection.getMessage());
        }
    }

    @Test
    void 首次门禁完成回调调度被拒绝时不得调用模型且失败只收口一次() {
        MutableChatMemory memory = new MutableChatMemory(7L);
        memory.add(UserMessage.from("本轮问题"));
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        StreamingChatModel model = new StreamingChatModel() {
            @Override
            public void doChat(
                    ChatRequest request,
                    dev.langchain4j.model.chat.response
                            .StreamingChatResponseHandler handler) {
                modelCalls.incrementAndGet();
            }
        };
        TestAiService service = AiServices.builder(TestAiService.class)
                .streamingChatModel(model)
                .chatMemoryProvider(ignored -> memory)
                .build();
        ModelRequestGate gate = new ModelRequestGate() {
            @Override
            public java.util.concurrent.CompletionStage<Decision> prepare(
                    Request request) {
                return CompletableFuture.completedFuture(new Decision(
                        Status.ALLOWED,
                        request.latestMemory().get().messages(),
                        10,
                        ""));
            }

            @Override
            public java.util.concurrent.CompletionStage<DispatchStatus> onPrepared(
                    java.util.concurrent.CompletionStage<Decision> preparation,
                    java.util.function.BiConsumer<Decision, Throwable> completion) {
                return CompletableFuture.completedFuture(
                        DispatchStatus.REJECTED);
            }
        };
        TokenStream stream = service.chat(7L, "本轮问题");

        stream.modelRequestGate(gate, action -> {
                    action.run();
                    return true;
                })
                .onPartialResponse(ignored -> { })
                .onError(ignored -> errors.incrementAndGet())
                .start();

        assertEquals(0, modelCalls.get());
        assertEquals(1, errors.get());
    }

    @Test
    void cancelBeforeStartDoesNotPrepareGatePublishContentsOrStartModel() {
        AiServiceContext context = new AiServiceContext(Object.class);
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger gateCalls = new AtomicInteger();
        context.streamingChatModel = new StreamingChatModel() {
            @Override
            public void doChat(
                    dev.langchain4j.model.chat.request.ChatRequest request,
                    dev.langchain4j.model.chat.response.StreamingChatResponseHandler handler) {
                modelCalls.incrementAndGet();
            }
        };
        AiServiceTokenStream stream = new AiServiceTokenStream(
                AiServiceTokenStreamParameters.builder()
                        .messages(List.of(UserMessage.from("test")))
                        .toolSpecifications(List.of())
                        .toolExecutors(Map.of())
                        .retrievedContents(List.of())
                        .context(context)
                        .memoryId("memory")
                        .methodKey("method")
                        .build());
        AtomicInteger contentCalls = new AtomicInteger();
        stream.modelRequestGate(request -> {
                    gateCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            new ModelRequestGate.Decision(
                                    ModelRequestGate.Status.ALLOWED,
                                    request.latestMemory().get().messages(),
                                    1,
                                    ""));
                }, action -> {
                    action.run();
                    return true;
                })
                .onPartialResponse(partial -> { })
                .onRetrieved(contents -> contentCalls.incrementAndGet())
                .ignoreErrors();

        stream.cancel();
        stream.start();

        assertEquals(0, gateCalls.get());
        assertEquals(0, contentCalls.get());
        assertEquals(0, modelCalls.get());
    }

    @Test
    void 初始工具选择策略只影响首个模型请求() throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        RecoveryAiService service = recoveryService(model, memory);

        service.chat(7L, "本轮问题")
                .initialToolChoiceRequired(true)
                .onPartialResponse(ignored -> { })
                .onError(error -> org.junit.jupiter.api.Assertions.fail(
                        "首轮工具选择测试不应报错", error))
                .start();

        assertTrue(model.awaitCalls(1));
        assertEquals(ToolChoice.REQUIRED, model.request(0).toolChoice());
    }

    @Test
    void 只读回合的未完成工具链策略不会自动续行() {
        IncompleteToolChainRecoveryPolicy policy =
                new IncompleteToolChainRecoveryPolicy(
                        () -> false,
                        () -> IncompleteToolChainRecoveryPolicy.BuildState.GENERATING,
                        ignored -> { });

        assertFalse(policy.requiresContinuation());
    }

    private RecoveryAiService recoveryService(
            RecordingRecoveryModel model, ChatMemory memory) {
        return AiServices.builder(RecoveryAiService.class)
                .streamingChatModel(model)
                .chatMemoryProvider(ignored -> memory)
                .tools(new RecoveryTools(new AtomicInteger()))
                .build();
    }

    private StreamingRequestController requestController(
            TokenStream stream) throws ReflectiveOperationException {
        AiServiceTokenStream aiServiceTokenStream = assertInstanceOf(
                AiServiceTokenStream.class, stream);
        java.lang.reflect.Field field = AiServiceTokenStream.class
                .getDeclaredField("requestController");
        field.setAccessible(true);
        return (StreamingRequestController) field.get(aiServiceTokenStream);
    }

    private AiServiceTokenStream tokenStreamWithTools(
            List<ToolSpecification> specifications,
            Map<String, ToolExecutor> executors) {
        AiServiceContext context = new AiServiceContext(Object.class);
        context.streamingChatModel = new RecordingRecoveryModel();
        return new AiServiceTokenStream(
                AiServiceTokenStreamParameters.builder()
                        .messages(List.of(UserMessage.from("测试")))
                        .toolSpecifications(specifications)
                        .toolExecutors(executors)
                        .retrievedContents(List.of())
                        .context(context)
                        .memoryId("memory")
                        .methodKey("method")
                        .build());
    }

    private ToolSpecification toolSpecification(String name) {
        return ToolSpecification.builder().name(name).build();
    }

    private ToolExecutor successfulToolExecutor() {
        return (request, memoryId) -> "成功";
    }

    private InternalOutputRecoveryPolicy internalPolicy(
            InternalOutputRecoveryPolicy.Mode mode) {
        return new InternalOutputRecoveryPolicy(
                mode, INTERNAL_PREFIX, Set.of(INTERNAL_MARKER));
    }

    private ToolExecutionRequest toolRequest(
            String id, String name, String arguments) {
        return ToolExecutionRequest.builder()
                .id(id)
                .name(name)
                .arguments(arguments)
                .build();
    }

    private MutableChatMemory memoryWithQuestion() {
        MutableChatMemory memory = new MutableChatMemory(7L);
        memory.add(UserMessage.from("本轮问题"));
        return memory;
    }

    private ManagedModelRequestGate allowingGate(
            List<ModelRequestGate.Request> requests) {
        return allowingGate(requests, new AtomicInteger());
    }

    private ManagedModelRequestGate allowingGate(
            List<ModelRequestGate.Request> requests,
            AtomicInteger callCount) {
        return new ManagedModelRequestGate(request -> {
            requests.add(request);
            callCount.incrementAndGet();
            List<ChatMessage> prepared = new ArrayList<>(
                    request.latestMemory().get().messages());
            prepared.addAll(request.transientMessages());
            return CompletableFuture.completedFuture(
                    new ModelRequestGate.Decision(
                            ModelRequestGate.Status.ALLOWED,
                            prepared,
                            prepared.size(),
                            ""));
        });
    }

    private ModelRequestGate.ContinuationGate directContinuation() {
        return action -> {
            action.run();
            return true;
        };
    }

    private ToolProtocolRecoveryPolicy recoveryPolicy(
            List<ToolProtocolRecoveryPolicy.Phase> phases) {
        return new ToolProtocolRecoveryPolicy(
                Set.of("writeFile"), phases::add);
    }

    private List<GenerationStreamSignal.Recovery.Phase> recoveryPhases(
            List<GenerationStreamSignal> signals) {
        return signals.stream()
                .filter(GenerationStreamSignal.Recovery.class::isInstance)
                .map(GenerationStreamSignal.Recovery.class::cast)
                .map(GenerationStreamSignal.Recovery::phase)
                .toList();
    }

    private int signalIndex(
            List<GenerationStreamSignal> signals,
            java.util.function.Predicate<GenerationStreamSignal> predicate) {
        for (int index = 0; index < signals.size(); index++) {
            if (predicate.test(signals.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private void assertGenerationListenerFailure(
            Class<? extends GenerationStreamSignal> failingType,
            ListenerFailureTrigger trigger) throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        AtomicInteger terminations = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        try (ManagedModelRequestGate gate = allowingGate(
                new CopyOnWriteArrayList<>())) {
            RecoveryAiService service = AiServices.builder(
                            RecoveryAiService.class)
                    .streamingChatModel(model)
                    .chatMemoryProvider(ignored -> memory)
                    .tools(new RecoveryTools(toolCalls))
                    .build();
            TokenStream stream = service.chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .internalOutputRecoveryPolicy(internalPolicy(
                            InternalOutputRecoveryPolicy.Mode.RECOVER_ONCE))
                    .onGenerationStreamSignal(signal -> {
                        if (failingType.isInstance(signal)) {
                            throw new IllegalStateException(
                                    "generation 监听器失败："
                                            + failingType.getSimpleName());
                        }
                    })
                    .onCompleteResponse(ignored ->
                            completions.incrementAndGet())
                    .onControlledTermination(termination -> {
                        assertEquals(ToolLoopTerminationProtocol
                                        .ControlledTerminationReason
                                        .PROTOCOL_ERROR,
                                termination.reason());
                        terminations.incrementAndGet();
                    })
                    .onError(ignored -> errors.incrementAndGet());
            StreamingRequestController controller =
                    requestController(stream);
            stream.start();
            assertTrue(model.awaitCalls(1));

            assertDoesNotThrow(() -> trigger.trigger(
                    model.handler(0), model));
            gate.awaitIdle();

            assertEquals(1, terminations.get(),
                    failingType.getSimpleName() + " 必须唯一协议终止");
            assertEquals(0, errors.get());
            assertEquals(0, completions.get());
            assertFalse(controller.isOpen());
            assertEquals(ToolLoopTerminationProtocol
                            .ControlledTerminationReason.PROTOCOL_ERROR,
                    controller.controlledTermination().reason());
            int expectedToolCalls = failingType
                    == GenerationStreamSignal.ToolExecuted.class ? 1 : 0;
            assertEquals(expectedToolCalls, toolCalls.get(),
                    failingType.getSimpleName()
                            + " 失败后的工具副作用边界不正确");
            int expectedModelCalls = failingType
                    == GenerationStreamSignal.Recovery.class ? 2 : 1;
            assertEquals(expectedModelCalls, model.callCount(),
                    failingType.getSimpleName()
                            + " 失败后不得再启动新的模型请求");
            assertFalse(hasUnpairedToolRequests(memory.messages()),
                    failingType.getSimpleName()
                            + " 失败后 ChatMemory 不得遗留未配对工具请求");
            if (failingType
                    == GenerationStreamSignal.CompleteToolRequest.class) {
                List<ToolExecutionResultMessage> results = memory.messages()
                        .stream()
                        .filter(ToolExecutionResultMessage.class::isInstance)
                        .map(ToolExecutionResultMessage.class::cast)
                        .toList();
                assertEquals(1, results.size());
                assertTrue(results.getFirst().text().contains("受控跳过"),
                        "已提交工具批次必须写入明确跳过结果完成配对");
            }
        }
    }

    @FunctionalInterface
    private interface ListenerFailureTrigger {

        void trigger(
                StreamingChatResponseHandler handler,
                RecordingRecoveryModel model);
    }

    private static void awaitLatch(
            CountDownLatch latch, String timeoutMessage) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                org.junit.jupiter.api.Assertions.fail(timeoutMessage);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            org.junit.jupiter.api.Assertions.fail(timeoutMessage, exception);
        }
    }

    private boolean containsAiText(
            List<ChatMessage> messages, String expected) {
        return messages.stream()
                .filter(AiMessage.class::isInstance)
                .map(AiMessage.class::cast)
                .anyMatch(message -> expected.equals(message.text()));
    }

    private void assertStreamFinishedCandidateRecovers(String candidate)
            throws Exception {
        MutableChatMemory memory = memoryWithQuestion();
        RecordingRecoveryModel model = new RecordingRecoveryModel();
        List<ToolProtocolRecoveryPolicy.Phase> phases =
                new CopyOnWriteArrayList<>();
        List<String> partials = new CopyOnWriteArrayList<>();
        AtomicReference<ChatResponse> completed = new AtomicReference<>();
        try (ManagedModelRequestGate gate = allowingGate(
                new CopyOnWriteArrayList<>())) {
            recoveryService(model, memory).chat(7L, "本轮问题")
                    .modelRequestGate(gate, directContinuation())
                    .toolProtocolRecoveryPolicy(recoveryPolicy(phases))
                    .onPartialResponse(partials::add)
                    .onCompleteResponse(completed::set)
                    .onError(error -> org.junit.jupiter.api.Assertions.fail(
                            "流结束伪工具候选应自动纠正", error))
                    .start();
            assertTrue(model.awaitCalls(1));

            model.handler(0).onCompleteResponse(response(
                    candidate, new TokenUsage(2, 3, 5)));
            assertTrue(model.awaitCalls(2),
                    "流结束必须启动纠正 generation：" + candidate);
            model.handler(1).onCompleteResponse(response(
                    "纠正完成", new TokenUsage(4, 5, 9)));
            gate.awaitIdle();

            assertEquals(2, model.callCount());
            assertEquals(List.of(
                    ToolProtocolRecoveryPolicy.Phase.STARTED,
                    ToolProtocolRecoveryPolicy.Phase.RECOVERED), phases);
            assertEquals(List.of("纠正完成"), partials);
            assertEquals("纠正完成", completed.get().aiMessage().text());
            assertFalse(containsAiTextFragment(
                    memory.messages(), "[工具调用]"));
        }
    }

    private boolean containsAiTextFragment(
            List<ChatMessage> messages, String expected) {
        return messages.stream()
                .filter(AiMessage.class::isInstance)
                .map(AiMessage.class::cast)
                .map(AiMessage::text)
                .filter(java.util.Objects::nonNull)
                .anyMatch(text -> text.contains(expected));
    }

    private boolean hasUnpairedToolRequests(List<ChatMessage> messages) {
        Set<String> pendingIds = new java.util.HashSet<>();
        for (ChatMessage message : messages) {
            if (message instanceof AiMessage aiMessage
                    && aiMessage.hasToolExecutionRequests()) {
                aiMessage.toolExecutionRequests().stream()
                        .map(ToolExecutionRequest::id)
                        .forEach(pendingIds::add);
            } else if (message instanceof ToolExecutionResultMessage result) {
                pendingIds.remove(result.id());
            }
        }
        return !pendingIds.isEmpty();
    }

    private int indexOfRecoveryPhase(
            List<GenerationStreamSignal> signals,
            GenerationStreamSignal.Recovery.Phase expectedPhase) {
        for (int index = 0; index < signals.size(); index++) {
            GenerationStreamSignal signal = signals.get(index);
            if (signal instanceof GenerationStreamSignal.Recovery recovery
                    && recovery.phase() == expectedPhase) {
                return index;
            }
        }
        return -1;
    }

    private Object compressionAttemptState(ModelRequestGate.Request request) {
        return request.contextCompressionAttemptState();
    }

    private ChatResponse response(String text, TokenUsage usage) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(text))
                .metadata(ChatResponseMetadata.builder()
                        .tokenUsage(usage)
                        .build())
                .build();
    }

    private ChatResponse toolResponse(ToolExecutionRequest request) {
        return toolResponse(null, request);
    }

    private ChatResponse toolResponse(
            String text, ToolExecutionRequest request) {
        return ChatResponse.builder()
                .aiMessage(text == null
                        ? AiMessage.from(List.of(request))
                        : AiMessage.from(text, List.of(request)))
                .metadata(ChatResponseMetadata.builder()
                        .tokenUsage(new TokenUsage(3, 4, 7))
                        .build())
                .build();
    }

    interface TestAiService {

        @dev.langchain4j.service.UserMessage("{{message}}")
        TokenStream chat(
                @MemoryId long memoryId,
                @V("message") String message);
    }

    interface RecoveryAiService {

        @dev.langchain4j.service.UserMessage("{{message}}")
        TokenStream chat(
                @MemoryId long memoryId,
                @V("message") String message);
    }

    static final class RecoveryTools {

        private final AtomicInteger calls;

        private RecoveryTools(AtomicInteger calls) {
            this.calls = calls;
        }

        @dev.langchain4j.agent.tool.Tool("写文件")
        public String writeFile() {
            calls.incrementAndGet();
            return "写入成功";
        }
    }

    static final class TextRecoveryTools {

        private final AtomicInteger calls;

        private TextRecoveryTools(AtomicInteger calls) {
            this.calls = calls;
        }

        @dev.langchain4j.agent.tool.Tool("写文件")
        public String writeFile(
                @dev.langchain4j.agent.tool.P("正文") String text) {
            calls.incrementAndGet();
            return "写入成功";
        }
    }

    static final class TerminalRecoveryTools {

        private final AtomicReference<IncompleteToolChainRecoveryPolicy
                .BuildState> state;

        private TerminalRecoveryTools(
                AtomicReference<IncompleteToolChainRecoveryPolicy
                        .BuildState> state) {
            this.state = state;
        }

        @dev.langchain4j.agent.tool.Tool("构建项目")
        public String buildProject() {
            state.set(IncompleteToolChainRecoveryPolicy.BuildState.SUCCEEDED);
            return "构建成功";
        }
    }

    static final class SourceReturningTools {

        @dev.langchain4j.agent.tool.Tool("写入原始源码")
        public String writeFile() {
            return "绝密原始源码";
        }
    }

    private static final class RecordingRecoveryModel
            implements StreamingChatModel {

        private final List<ChatRequest> requests =
                new CopyOnWriteArrayList<>();
        private final List<StreamingChatResponseHandler> handlers =
                new CopyOnWriteArrayList<>();
        private final CountDownLatch firstCall = new CountDownLatch(1);
        private final CountDownLatch secondCall = new CountDownLatch(1);
        private final CountDownLatch thirdCall = new CountDownLatch(1);
        private volatile int failingCall = -1;

        @Override
        public void doChat(
                ChatRequest request,
                StreamingChatResponseHandler handler) {
            requests.add(request);
            handlers.add(handler);
            int call = requests.size();
            switch (call) {
                case 1 -> firstCall.countDown();
                case 2 -> secondCall.countDown();
                case 3 -> thirdCall.countDown();
                default -> { }
            }
            if (call == failingCall) {
                throw new IllegalStateException("模型启动失败");
            }
        }

        private boolean awaitCalls(int expected) throws InterruptedException {
            CountDownLatch latch = switch (expected) {
                case 1 -> firstCall;
                case 2 -> secondCall;
                case 3 -> thirdCall;
                default -> throw new IllegalArgumentException(
                        "测试只等待前三次模型请求");
            };
            return latch.await(2, TimeUnit.SECONDS);
        }

        private int callCount() {
            return requests.size();
        }

        private ChatRequest request(int index) {
            return requests.get(index);
        }

        private StreamingChatResponseHandler handler(int index) {
            return handlers.get(index);
        }

        private void failOnCall(int call) {
            this.failingCall = call;
        }
    }

    private static final class MutableChatMemory implements ChatMemory {

        private final Object id;
        private final List<ChatMessage> messages = new ArrayList<>();

        private MutableChatMemory(Object id) {
            this.id = id;
        }

        @Override
        public Object id() {
            return id;
        }

        @Override
        public synchronized void add(ChatMessage message) {
            messages.add(message);
        }

        @Override
        public synchronized List<ChatMessage> messages() {
            return List.copyOf(messages);
        }

        @Override
        public synchronized void clear() {
            messages.clear();
        }

        private synchronized void replaceWith(
                List<ChatMessage> replacement) {
            messages.clear();
            messages.addAll(replacement);
        }
    }

    private static final class BlockingToolWriteMemory
            implements ChatMemory {

        private final Object id;
        private final List<ChatMessage> messages =
                new CopyOnWriteArrayList<>();
        private final CountDownLatch toolWriteStarted =
                new CountDownLatch(1);
        private final CountDownLatch releaseToolWrite =
                new CountDownLatch(1);

        private BlockingToolWriteMemory(Object id) {
            this.id = id;
        }

        @Override
        public Object id() {
            return id;
        }

        @Override
        public void add(ChatMessage message) {
            messages.add(message);
            if (message instanceof AiMessage aiMessage
                    && aiMessage.hasToolExecutionRequests()) {
                toolWriteStarted.countDown();
                awaitLatch(releaseToolWrite,
                        "等待释放结构化工具请求写入超时");
            }
        }

        @Override
        public List<ChatMessage> messages() {
            return List.copyOf(messages);
        }

        @Override
        public void clear() {
            messages.clear();
        }
    }
}
