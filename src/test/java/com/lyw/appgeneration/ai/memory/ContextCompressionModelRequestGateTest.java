package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.ai.model.message.ContextCompressionMessage;
import com.lyw.appgeneration.ai.tools.FileToolBudgetGuard;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import com.lyw.appgeneration.core.concurrency.VueTurnAdmissionController;
import com.lyw.appgeneration.core.handler.GenerationStreamEvent;
import com.lyw.appgeneration.core.handler.SimpleGenerationTurnContext;
import com.lyw.appgeneration.core.handler.VueTurnContext;
import com.lyw.appgeneration.monitor.VueBuildRepairMetricsCollector;
import com.lyw.appgeneration.monitor.MemoryCompressionMetricsCollector;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemoryCompressionResult;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.ModelRequestGate;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.V;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.UserMemoryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextCompressionModelRequestGateTest {

    private static final String VALID_SUMMARY = """
            # 应用目标与定位
            测试应用
            # 用户偏好与硬约束
            无
            # 已否决的方案
            无
            # 关键设计决策与理由
            无
            # 当前进度速览
            已完成早期回合
            """.strip();

    @Test
    void 阻塞压缩只通过真实回合门发布固定开始和完成进度()
            throws Exception {
        ContextCompressionCoordinator coordinator =
                mock(ContextCompressionCoordinator.class);
        CompressionAwareChatMemory memory = compressionMemory("阻塞压缩");
        RecordingProgressGate continuation = new RecordingProgressGate();
        when(coordinator.admit(eq(memory), eq(List.of()), any(), any()))
                .thenAnswer(invocation -> {
                    Consumer<ContextAdmissionResult> listener =
                            invocation.getArgument(2);
                    ContextContinuationGate actualGate =
                            invocation.getArgument(3);
                    assertSame(continuation, actualGate);
                    assertTrue(actualGate.tryRun(() -> listener.accept(result(
                            ContextCompressionMode.BLOCKING_STARTED,
                            ContextAdmissionResult.FailureReason.NONE,
                            31_000, 31_000))));
                    return result(ContextCompressionMode.BLOCKING_COMPLETED,
                            ContextAdmissionResult.FailureReason.NONE,
                            31_000, 12_000);
                });

        try (ExecutorService executor =
                     Executors.newVirtualThreadPerTaskExecutor()) {
            ContextCompressionModelRequestGate gate =
                    new ContextCompressionModelRequestGate(
                            coordinator, executor);

            ModelRequestGate.Decision decision = gate.prepare(
                            request(memory, continuation))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertEquals(ModelRequestGate.Status.ALLOWED, decision.status());
            assertEquals(List.of(
                            ContextCompressionMessage.Phase.STARTED,
                            ContextCompressionMessage.Phase.COMPLETED),
                    continuation.phases());
            assertEquals(2, continuation.attempts(),
                    "COMPLETED 必须重新取得同一个真实回合门");
        }
    }

    @ParameterizedTest
    @EnumSource(value = ContextCompressionMode.class,
            names = {"ASYNC_SCHEDULED", "BLOCKING_FAILED",
                    "HARD_LIMIT_REJECTED"})
    void 异步压缩和失败结果不得发布控制事件(
            ContextCompressionMode mode) throws Exception {
        ContextCompressionCoordinator coordinator =
                mock(ContextCompressionCoordinator.class);
        CompressionAwareChatMemory memory = compressionMemory("非展示结果");
        RecordingProgressGate continuation = new RecordingProgressGate();
        ContextAdmissionResult.FailureReason reason = switch (mode) {
            case ASYNC_SCHEDULED ->
                    ContextAdmissionResult.FailureReason.NONE;
            case BLOCKING_FAILED ->
                    ContextAdmissionResult.FailureReason.MODEL_FAILED;
            case HARD_LIMIT_REJECTED ->
                    ContextAdmissionResult.FailureReason.STILL_OVER_HARD_LIMIT;
            default -> throw new IllegalArgumentException("未覆盖模式");
        };
        when(coordinator.admit(eq(memory), eq(List.of()), any(), any()))
                .thenAnswer(invocation -> {
                    Consumer<ContextAdmissionResult> listener =
                            invocation.getArgument(2);
                    listener.accept(result(mode, reason, 31_000, 31_000));
                    return result(mode, reason, 31_000, 31_000);
                });

        try (ExecutorService executor =
                     Executors.newVirtualThreadPerTaskExecutor()) {
            ContextCompressionModelRequestGate gate =
                    new ContextCompressionModelRequestGate(
                            coordinator, executor);

            gate.prepare(request(memory, continuation))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertTrue(continuation.phases().isEmpty());
        }
    }

    @Test
    void 回合终态先关门时静默丢弃迟到完成进度() throws Exception {
        ContextCompressionCoordinator coordinator =
                mock(ContextCompressionCoordinator.class);
        CompressionAwareChatMemory memory = compressionMemory("终态竞争");
        RecordingProgressGate continuation = new RecordingProgressGate();
        when(coordinator.admit(eq(memory), eq(List.of()), any(), any()))
                .thenAnswer(invocation -> {
                    Consumer<ContextAdmissionResult> listener =
                            invocation.getArgument(2);
                    ContextContinuationGate actualGate =
                            invocation.getArgument(3);
                    assertTrue(actualGate.tryRun(() -> listener.accept(result(
                            ContextCompressionMode.BLOCKING_STARTED,
                            ContextAdmissionResult.FailureReason.NONE,
                            31_000, 31_000))));
                    continuation.close();
                    return result(ContextCompressionMode.BLOCKING_COMPLETED,
                            ContextAdmissionResult.FailureReason.NONE,
                            31_000, 12_000);
                });

        try (ExecutorService executor =
                     Executors.newVirtualThreadPerTaskExecutor()) {
            ContextCompressionModelRequestGate gate =
                    new ContextCompressionModelRequestGate(
                            coordinator, executor);

            gate.prepare(request(memory, continuation))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertEquals(List.of(ContextCompressionMessage.Phase.STARTED),
                    continuation.phases());
            assertEquals(2, continuation.attempts());
        }
    }

    @ParameterizedTest
    @EnumSource(value = ContextCompressionMode.class,
            names = {"NORMAL", "ASYNC_SCHEDULED", "BLOCKING_COMPLETED"})
    void 可继续压缩模式只使用协调器审核快照且不二次读取(
            ContextCompressionMode mode) throws Exception {
        ContextCompressionCoordinator coordinator =
                mock(ContextCompressionCoordinator.class);
        CompressionAwareChatMemory compressionMemory =
                compressionMemory("已审核上下文");
        ChatMemory refreshedMemory = memory("超".repeat(40_000));
        MemoryTokenProperties properties = new MemoryTokenProperties();
        ConservativeChatTokenEstimator estimator =
                new ConservativeChatTokenEstimator(properties);
        List<ChatMessage> auditedMessages = compressionMemory.messages();
        assertTrue(estimator.estimateRequest(
                        refreshedMemory.messages(), List.of())
                        >= properties.getHardInputLimit());
        AtomicInteger memoryReads = new AtomicInteger();
        when(coordinator.admit(eq(compressionMemory), eq(List.of()),
                any(), any())).thenReturn(result(mode,
                ContextAdmissionResult.FailureReason.NONE, 29_000, 29_000,
                auditedMessages));

        try (ExecutorService executor =
                     Executors.newVirtualThreadPerTaskExecutor()) {
            ContextCompressionModelRequestGate gate =
                    new ContextCompressionModelRequestGate(
                            coordinator, executor);

            ModelRequestGate.Decision decision = gate.prepare(
                            new ModelRequestGate.Request(
                                    7L,
                                    () -> memoryReads.getAndIncrement() == 0
                                            ? compressionMemory : refreshedMemory,
                                    List.of(),
                                    action -> {
                                        action.run();
                                        return true;
                                    }))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertEquals(ModelRequestGate.Status.ALLOWED, decision.status());
            assertEquals(auditedMessages, decision.messages());
            assertEquals(29_000, decision.estimatedInputTokens());
            assertEquals(1, memoryReads.get(),
                    "模型请求必须复用协调器审核的同一份快照");
        }
    }

    @Test
    void 回合终态映射为取消() throws Exception {
        assertStatus(
                ContextCompressionMode.ADMISSION_FAILED,
                ContextAdmissionResult.FailureReason.TURN_TERMINATED,
                ModelRequestGate.Status.CANCELLED);
    }

    @Test
    void 压缩失败映射为类型化失败() throws Exception {
        assertStatus(
                ContextCompressionMode.BLOCKING_FAILED,
                ContextAdmissionResult.FailureReason.MODEL_FAILED,
                ModelRequestGate.Status.COMPRESSION_FAILED);
    }

    @Test
    void 硬上限拒绝保持独立状态() throws Exception {
        assertStatus(
                ContextCompressionMode.HARD_LIMIT_REJECTED,
                ContextAdmissionResult.FailureReason.STILL_OVER_HARD_LIMIT,
                ModelRequestGate.Status.HARD_LIMIT_REJECTED);
    }

    @Test
    void 协调器必须在受管虚拟线程而不是调用线程执行() throws Exception {
        ContextCompressionCoordinator coordinator =
                mock(ContextCompressionCoordinator.class);
        CompressionAwareChatMemory memory =
                compressionMemory("协调前消息");
        AtomicReference<Thread> worker = new AtomicReference<>();
        when(coordinator.admit(eq(memory), eq(List.of()), any(), any()))
                .thenAnswer(invocation -> {
                    worker.set(Thread.currentThread());
                    return result(ContextCompressionMode.NORMAL,
                            ContextAdmissionResult.FailureReason.NONE, 10, 10);
                });
        Thread caller = Thread.currentThread();

        try (ExecutorService executor =
                     Executors.newVirtualThreadPerTaskExecutor()) {
            ContextCompressionModelRequestGate gate =
                    new ContextCompressionModelRequestGate(
                            coordinator, executor);

            gate.prepare(request(memory)).toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);

            assertNotEquals(caller, worker.get());
            assertTrue(worker.get().isVirtual());
        }
    }

    @Test
    void 已完成Future的完成回调仍由受管虚拟线程派发() throws Exception {
        ContextCompressionCoordinator coordinator =
                mock(ContextCompressionCoordinator.class);
        ChatMemory memory = memory("已完成门禁结果");
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        Thread caller = Thread.currentThread();

        try (ExecutorService executor =
                     Executors.newVirtualThreadPerTaskExecutor()) {
            ContextCompressionModelRequestGate gate =
                    new ContextCompressionModelRequestGate(
                            coordinator, executor);
            ModelRequestGate.Decision allowed = new ModelRequestGate.Decision(
                    ModelRequestGate.Status.ALLOWED,
                    memory.messages(),
                    10,
                    "");

            gate.onPrepared(CompletableFuture.completedFuture(allowed),
                    (decision, failure) -> {
                        callbackThread.set(Thread.currentThread());
                        callbackFailure.set(failure);
                        completed.countDown();
                    });

            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertNull(callbackFailure.get());
            assertNotEquals(caller, callbackThread.get());
            assertTrue(callbackThread.get().isVirtual());
        }
    }

    @Test
    void 完成回调执行器拒绝时不得同步执行续调用() throws Exception {
        ContextCompressionCoordinator coordinator =
                mock(ContextCompressionCoordinator.class);
        ChatMemory memory = memory("拒绝派发的门禁结果");
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        executor.shutdownNow();
        ContextCompressionModelRequestGate gate =
                new ContextCompressionModelRequestGate(coordinator, executor);
        AtomicInteger callbacks = new AtomicInteger();
        ModelRequestGate.Decision allowed = new ModelRequestGate.Decision(
                ModelRequestGate.Status.ALLOWED,
                memory.messages(),
                10,
                "");

        ModelRequestGate.DispatchStatus dispatch = gate.onPrepared(
                        CompletableFuture.completedFuture(allowed),
                        (decision, failure) -> callbacks.incrementAndGet())
                .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals(ModelRequestGate.DispatchStatus.REJECTED, dispatch);
        assertEquals(0, callbacks.get(),
                "执行器拒绝后不能在调用线程同步执行续调用");
        verify(coordinator, never()).admit(any(), any(), any(), any());
    }

    @Test
    void 执行器拒绝时不得回退调用线程执行协调器() throws Exception {
        ContextCompressionCoordinator coordinator =
                mock(ContextCompressionCoordinator.class);
        CompressionAwareChatMemory memory =
                compressionMemory("执行器拒绝消息");
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        executor.shutdownNow();
        ContextCompressionModelRequestGate gate =
                new ContextCompressionModelRequestGate(coordinator, executor);

        ModelRequestGate.Decision decision = gate.prepare(request(memory))
                .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals(ModelRequestGate.Status.COMPRESSION_FAILED,
                decision.status());
        assertTrue(decision.messages().isEmpty());
        assertFalse(decision.safeMessage().isBlank());
        verify(coordinator, never()).admit(any(), any(), any(), any());
    }

    @Test
    void 真实27K首次请求直接调用模型且不发布压缩控制事件()
            throws Exception {
        assertRealNonBlockingInitialRequest(15_600, false);
    }

    @Test
    void 冷启动跳过已摘要回合后模型请求仍必须携带严格L1()
            throws Exception {
        try (RealGateFixture fixture = new RealGateFixture(15_600, 12_000);
             SimpleGenerationTurnContext turnContext =
                     fixture.openTurn("cold-rebuild-strict-l1")) {
            fixture.rebuildL0AfterSummaryCursor();
            RecordingStreamingChatModel model =
                    new RecordingStreamingChatModel();
            RealGateAiService service = AiServices.builder(
                            RealGateAiService.class)
                    .streamingChatModel(model)
                    .chatMemoryProvider(ignored -> fixture.memory())
                    .build();
            AtomicReference<Throwable> error = new AtomicReference<>();
            TokenStream stream = service.chat(7L, "冷启动后的当前问题");
            try {
                stream.modelRequestGate(fixture.gate(), turnContext)
                        .onPartialResponse(ignored -> { })
                        .onError(error::set)
                        .start();

                assertTrue(model.awaitCalls(1));
                assertNull(error.get());
                ChatRequest request = model.request(0);
                assertTrue(containsTextFragment(
                                request.messages(), VALID_SUMMARY),
                        "冷启动游标跳过旧 L0 后，模型请求仍必须携带严格 L1");
                assertTrue(containsText(
                        request.messages(), fixture.recentUser()));
                assertTrue(containsText(
                        request.messages(), "冷启动后的当前问题"));
                assertFalse(containsText(
                        request.messages(), fixture.oldUser()));
                verify(fixture.summaryService()).getRequiredSummary(
                        RealGateFixture.APP_ID, 2L);
            } finally {
                stream.cancel();
            }
        }
    }

    @Test
    void 真实28K首次请求异步压旧回合且模型仍使用原审核快照()
            throws Exception {
        assertRealNonBlockingInitialRequest(17_000, true);
    }

    @Test
    void 首次请求真实压缩后仍达32K时不得调用模型且只返回安全拒绝()
            throws Exception {
        String oversizedCurrentUser = "超".repeat(21_000);
        try (RealGateFixture fixture = new RealGateFixture(19_000, 12_000);
             SimpleGenerationTurnContext turnContext =
                     fixture.openTurn("real-initial-hard-limit")) {
            RecordingStreamingChatModel model =
                    new RecordingStreamingChatModel();
            RealGateAiService service = AiServices.builder(
                            RealGateAiService.class)
                    .streamingChatModel(model)
                    .chatMemoryProvider(ignored -> fixture.memory())
                    .build();
            CompletableFuture<GenerationStreamEvent> businessResult =
                    new CompletableFuture<>();
            CompletableFuture<List<GenerationStreamEvent>> events =
                    turnContext.mergeProgress(
                                    Mono.fromFuture(businessResult).flux())
                            .collectList()
                            .toFuture();
            AtomicReference<Throwable> error = new AtomicReference<>();
            CountDownLatch errorDelivered = new CountDownLatch(1);
            TokenStream stream = service.chat(7L, oversizedCurrentUser);
            try {
                stream.modelRequestGate(fixture.gate(), turnContext)
                        .onPartialResponse(ignored -> { })
                        .onError(failure -> {
                            error.set(failure);
                            errorDelivered.countDown();
                        })
                        .start();

                assertTrue(fixture.awaitCompressionStarted(),
                        "首次超长请求必须先进入真实阻塞压缩");
                assertEquals(0, model.callCount(),
                        "压缩完成前不得调用模型");

                fixture.releaseCompression();

                assertTrue(errorDelivered.await(2, TimeUnit.SECONDS));
                assertEquals(0, model.callCount(),
                        "压缩后仍达到 32K 时不得调用模型");
                assertEquals("对话上下文过长，请开启新会话后重试",
                        error.get().getMessage());
                assertTrue(fixture.estimator().estimateRequest(
                                fixture.memory().messages(), List.of())
                                >= fixture.properties().getHardInputLimit());
                assertTrue(containsText(
                                fixture.memory().messages(), fixture.oldUser()),
                        "最终预演仍超 32K 时不得在拒绝前提交 L0 裁剪");
                assertTrue(containsText(
                        fixture.memory().messages(), fixture.recentUser()));
                assertTrue(containsText(
                        fixture.memory().messages(), oversizedCurrentUser));

                businessResult.complete(
                        GenerationStreamEvent.content("硬拒绝后占位正文"));
                assertEquals(List.of(
                                GenerationStreamEvent.contextCompression(
                                        ContextCompressionMessage.started()),
                                GenerationStreamEvent.content(
                                        "硬拒绝后占位正文")),
                        events.get(2, TimeUnit.SECONDS),
                        "硬拒绝不得伪造 COMPLETED 控制事件");
            } finally {
                businessResult.complete(
                        GenerationStreamEvent.content("硬拒绝后占位正文"));
                stream.cancel();
            }
        }
    }

    @Test
    void 真实30K压缩进度贯通普通回合且不串入Vue回合()
            throws Exception {
        try (RealGateFixture fixture = new RealGateFixture(19_000, 12_000);
             SimpleGenerationTurnContext turnContext =
                     fixture.openTurn("real-simple-progress-30k")) {
            VueTurnContext isolatedVue = openIndependentVueTurn(
                    "isolated-vue-progress");
            CompletableFuture<GenerationStreamEvent> vueBusinessResult =
                    new CompletableFuture<>();
            AtomicInteger vueSubscriptions = new AtomicInteger();
            CompletableFuture<List<GenerationStreamEvent>> vueEvents =
                    isolatedVue.mergeProgress(Flux.defer(() -> {
                                vueSubscriptions.incrementAndGet();
                                return Mono.fromFuture(vueBusinessResult).flux();
                            }))
                            .collectList()
                            .toFuture();
            AtomicInteger businessSubscriptions = new AtomicInteger();
            try {
                StepVerifier.create(turnContext.mergeProgress(realGateBusiness(
                                fixture, turnContext, "普通正文",
                                businessSubscriptions)))
                        .expectNext(GenerationStreamEvent.contextCompression(
                                ContextCompressionMessage.started()))
                        .then(() -> releaseStartedCompression(fixture))
                        .expectNext(GenerationStreamEvent.contextCompression(
                                ContextCompressionMessage.completed()))
                        .expectNext(GenerationStreamEvent.content("普通正文"))
                        .verifyComplete();

                assertEquals(1, businessSubscriptions.get());
                assertFalse(vueEvents.isDone(),
                        "普通回合完成不得关闭或写入 Vue 私有通道");
                vueBusinessResult.complete(
                        GenerationStreamEvent.content("Vue占位正文"));
                assertEquals(List.of(
                                GenerationStreamEvent.content("Vue占位正文")),
                        vueEvents.get(2, TimeUnit.SECONDS));
                assertEquals(1, vueSubscriptions.get());
            } finally {
                vueBusinessResult.complete(
                        GenerationStreamEvent.content("Vue占位正文"));
                isolatedVue.closeResources();
            }
        }
    }

    @Test
    void 真实30K压缩进度贯通Vue回合且不串入普通回合()
            throws Exception {
        try (RealGateFixture fixture = new RealGateFixture(19_000, 12_000);
             SimpleGenerationTurnContext isolatedSimple =
                     openIndependentSimpleTurn("isolated-simple-progress")) {
            VueTurnContext turnContext = fixture.openVueTurn(
                    "real-vue-progress-30k");
            CompletableFuture<GenerationStreamEvent> simpleBusinessResult =
                    new CompletableFuture<>();
            AtomicInteger simpleSubscriptions = new AtomicInteger();
            CompletableFuture<List<GenerationStreamEvent>> simpleEvents =
                            isolatedSimple.mergeProgress(Flux.defer(() -> {
                                simpleSubscriptions.incrementAndGet();
                                return Mono.fromFuture(simpleBusinessResult)
                                        .flux();
                            }))
                            .collectList()
                            .toFuture();
            AtomicInteger businessSubscriptions = new AtomicInteger();
            try {
                StepVerifier.create(turnContext.mergeProgress(realGateBusiness(
                                fixture, turnContext, "Vue正文",
                                businessSubscriptions)))
                        .expectNext(GenerationStreamEvent.contextCompression(
                                ContextCompressionMessage.started()))
                        .then(() -> releaseStartedCompression(fixture))
                        .expectNext(GenerationStreamEvent.contextCompression(
                                ContextCompressionMessage.completed()))
                        .expectNext(GenerationStreamEvent.content("Vue正文"))
                        .verifyComplete();

                assertEquals(1, businessSubscriptions.get());
                assertFalse(simpleEvents.isDone(),
                        "Vue 回合完成不得关闭或写入普通回合私有通道");
                simpleBusinessResult.complete(
                        GenerationStreamEvent.content("普通占位正文"));
                assertEquals(List.of(
                                GenerationStreamEvent.content("普通占位正文")),
                        simpleEvents.get(2, TimeUnit.SECONDS));
                assertEquals(1, simpleSubscriptions.get());
            } finally {
                simpleBusinessResult.complete(
                        GenerationStreamEvent.content("普通占位正文"));
                turnContext.closeResources();
            }
        }
    }

    @Test
    void 首次请求真实跨入30K时必须等待协调器裁剪后再调用模型()
            throws Exception {
        try (RealGateFixture fixture = new RealGateFixture(19_000, 12_000);
             SimpleGenerationTurnContext turnContext =
                     fixture.openTurn("real-initial-30k")) {
            RecordingStreamingChatModel model =
                    new RecordingStreamingChatModel();
            RealGateAiService service = AiServices.builder(
                            RealGateAiService.class)
                    .streamingChatModel(model)
                    .chatMemoryProvider(ignored -> fixture.memory())
                    .build();
            AtomicReference<Throwable> error = new AtomicReference<>();
            TokenStream stream = service.chat(7L, "本轮问题");

            stream.modelRequestGate(fixture.gate(), turnContext)
                    .onPartialResponse(ignored -> { })
                    .onError(error::set)
                    .start();

            assertTrue(fixture.awaitCompressionStarted(),
                    "31K 首次请求必须进入真实阻塞压缩");
            assertEquals(0, model.callCount(),
                    "压缩释放前不得调用模型");

            fixture.releaseCompression();

            assertTrue(model.awaitCalls(1));
            assertNull(error.get());
            ChatRequest request = model.request(0);
            assertTrue(containsTextFragment(request.messages(), VALID_SUMMARY),
                    "模型请求必须使用协调器审核过的严格 L1 快照");
            assertFalse(containsText(request.messages(), fixture.oldUser()),
                    "真实协调器必须裁剪已摘要的旧完整回合");
            assertTrue(containsText(request.messages(), fixture.recentUser()));
            assertTrue(containsText(request.messages(), "本轮问题"));
            verify(fixture.summaryService()).getRequiredSummary(7L, 2L);
            stream.cancel();
        }
    }

    @Test
    void 工具结果真实跨入30K时必须等待协调器压缩后才能续调模型()
            throws Exception {
        String largeToolResult = "工".repeat(4_000);
        try (RealGateFixture fixture = new RealGateFixture(15_000, 12_000);
             SimpleGenerationTurnContext turnContext =
                     fixture.openTurn("real-tool-30k")) {
            RecordingStreamingChatModel model =
                    new RecordingStreamingChatModel();
            RealGateAiService service = AiServices.builder(
                            RealGateAiService.class)
                    .streamingChatModel(model)
                    .chatMemoryProvider(ignored -> fixture.memory())
                    .tools(new LargeResultTool(largeToolResult))
                    .build();
            AtomicReference<Throwable> error = new AtomicReference<>();
            TokenStream stream = service.chat(7L, "本轮问题");

            stream.modelRequestGate(fixture.gate(), turnContext)
                    .onPartialResponse(ignored -> { })
                    .onError(error::set)
                    .start();

            assertTrue(model.awaitCalls(1));
            assertTrue(fixture.estimator().estimateRequest(
                            model.request(0).messages(), List.of())
                            < fixture.properties()
                            .getAsyncCompressionThreshold(),
                    "首次请求必须低于 28K");

            model.handler(0).onCompleteResponse(toolResponse());

            assertTrue(fixture.awaitCompressionStarted(),
                    "工具结果加入后必须真实跨入 30K 阻塞压缩");
            int expandedTokens = fixture.estimator().estimateRequest(
                    fixture.memory().messages(), List.of());
            assertTrue(expandedTokens >= fixture.properties()
                    .getBlockingCompressionThreshold());
            assertTrue(expandedTokens < fixture.properties()
                    .getHardInputLimit());
            assertEquals(1, model.callCount(),
                    "压缩释放前续调用次数必须保持为 1");

            fixture.releaseCompression();

            assertTrue(model.awaitCalls(2));
            assertNull(error.get());
            ChatRequest secondRequest = model.request(1);
            assertFalse(containsText(
                    secondRequest.messages(), fixture.oldUser()));
            assertTrue(secondRequest.messages().stream()
                    .filter(ToolExecutionResultMessage.class::isInstance)
                    .map(ToolExecutionResultMessage.class::cast)
                    .anyMatch(result -> largeToolResult.equals(result.text())));
            assertTrue(containsTextFragment(
                            secondRequest.messages(), VALID_SUMMARY),
                    "工具续调必须复用协调器审核过的严格 L1 快照");
            verify(fixture.summaryService()).getRequiredSummary(7L, 2L);
            stream.cancel();
        }
    }

    private void assertRealNonBlockingInitialRequest(
            int oldTurnTokens, boolean expectAsyncCompression)
            throws Exception {
        String owner = expectAsyncCompression
                ? "real-initial-28k" : "real-initial-27k";
        String content = expectAsyncCompression
                ? "28K占位正文" : "27K占位正文";
        try (RealGateFixture fixture = new RealGateFixture(
                oldTurnTokens, 12_000);
             SimpleGenerationTurnContext turnContext =
                     fixture.openTurn(owner)) {
            RecordingStreamingChatModel model =
                    new RecordingStreamingChatModel();
            RealGateAiService service = AiServices.builder(
                            RealGateAiService.class)
                    .streamingChatModel(model)
                    .chatMemoryProvider(ignored -> fixture.memory())
                    .build();
            CompletableFuture<GenerationStreamEvent> businessResult =
                    new CompletableFuture<>();
            CompletableFuture<List<GenerationStreamEvent>> events =
                    turnContext.mergeProgress(
                                    Mono.fromFuture(businessResult).flux())
                            .collectList()
                            .toFuture();
            AtomicReference<Throwable> error = new AtomicReference<>();
            TokenStream stream = service.chat(7L, "本轮问题");
            try {
                stream.modelRequestGate(fixture.gate(), turnContext)
                        .onPartialResponse(ignored -> { })
                        .onError(error::set)
                        .start();

                assertTrue(model.awaitCalls(1),
                        "非阻塞阈值必须无需释放同步压缩就调用模型");
                assertNull(error.get());
                ChatRequest request = model.request(0);
                int requestTokens = fixture.estimator().estimateRequest(
                        request.messages(), List.of());
                assertEquals(fixture.memory().messages(), request.messages());
                assertTrue(containsText(request.messages(), fixture.oldUser()));
                assertTrue(containsText(
                        request.messages(), fixture.recentUser()));
                assertTrue(containsText(request.messages(), "本轮问题"));
                if (expectAsyncCompression) {
                    assertTrue(requestTokens >= fixture.properties()
                            .getAsyncCompressionThreshold());
                    assertTrue(requestTokens < fixture.properties()
                            .getBlockingCompressionThreshold());
                    verify(fixture.summaryService())
                            .triggerSummarizationAsync(
                                    eq(7L), eq(2L),
                                    any(BooleanSupplier.class));
                } else {
                    assertTrue(requestTokens < fixture.properties()
                            .getAsyncCompressionThreshold());
                    verify(fixture.summaryService(), never())
                            .triggerSummarizationAsync(
                                    any(), anyLong(),
                                    any(BooleanSupplier.class));
                }
                verify(fixture.summaryService(), never()).compressNow(
                        any(), anyLong(), any(Duration.class));

                businessResult.complete(
                        GenerationStreamEvent.content(content));
                assertEquals(List.of(GenerationStreamEvent.content(content)),
                        events.get(2, TimeUnit.SECONDS),
                        "27K/28K 非阻塞路径不得发布压缩控制事件");
            } finally {
                businessResult.complete(
                        GenerationStreamEvent.content(content));
                stream.cancel();
            }
        }
    }

    private boolean containsText(
            List<ChatMessage> messages, String expected) {
        return messages.stream().anyMatch(message -> {
            if (message instanceof UserMessage userMessage
                    && userMessage.hasSingleText()) {
                return expected.equals(userMessage.singleText());
            }
            if (message instanceof AiMessage aiMessage) {
                return expected.equals(aiMessage.text());
            }
            return false;
        });
    }

    private boolean containsTextFragment(
            List<ChatMessage> messages, String expectedFragment) {
        return messages.stream().anyMatch(message -> {
            if (message instanceof UserMessage userMessage
                    && userMessage.hasSingleText()) {
                return userMessage.singleText().contains(expectedFragment);
            }
            if (message instanceof AiMessage aiMessage) {
                return aiMessage.text() != null
                        && aiMessage.text().contains(expectedFragment);
            }
            return false;
        });
    }

    private ChatResponse toolResponse() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("large-result-call")
                .name("largeResult")
                .arguments("{}")
                .build();
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(List.of(request)))
                .metadata(ChatResponseMetadata.builder()
                        .tokenUsage(new TokenUsage())
                        .build())
                .build();
    }

    interface RealGateAiService {

        @dev.langchain4j.service.UserMessage("{{message}}")
        TokenStream chat(
                @MemoryId long memoryId,
                @V("message") String message);
    }

    static final class LargeResultTool {

        private final String result;

        private LargeResultTool(String result) {
            this.result = result;
        }

        @dev.langchain4j.agent.tool.Tool("返回大结果")
        public String largeResult() {
            return result;
        }
    }

    private static final class RecordingStreamingChatModel
            implements StreamingChatModel {

        private final CopyOnWriteArrayList<ChatRequest> requests =
                new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<StreamingChatResponseHandler>
                handlers = new CopyOnWriteArrayList<>();
        private final CountDownLatch firstCall = new CountDownLatch(1);
        private final CountDownLatch secondCall = new CountDownLatch(1);

        @Override
        public void doChat(
                ChatRequest request,
                StreamingChatResponseHandler handler) {
            requests.add(request);
            handlers.add(handler);
            int count = requests.size();
            if (count >= 1) {
                firstCall.countDown();
            }
            if (count >= 2) {
                secondCall.countDown();
            }
        }

        private boolean awaitCalls(int expected) throws InterruptedException {
            CountDownLatch latch = expected == 1 ? firstCall : secondCall;
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
    }

    private static final class CharacterCountingTokenEstimator
            implements ChatTokenEstimator {

        @Override
        public int estimateText(String text) {
            return text == null ? 0 : text.codePointCount(0, text.length());
        }

        @Override
        public int estimateMessages(List<ChatMessage> messages) {
            long tokens = 0L;
            for (ChatMessage message : messages) {
                tokens += estimateMessage(message);
            }
            return tokens >= Integer.MAX_VALUE
                    ? Integer.MAX_VALUE : (int) tokens;
        }

        @Override
        public int estimateToolSpecifications(List<ToolSpecification> tools) {
            return 0;
        }

        @Override
        public int estimateRequest(
                List<ChatMessage> messages,
                List<ToolSpecification> tools) {
            return estimateMessages(messages);
        }

        private int estimateMessage(ChatMessage message) {
            if (message instanceof UserMessage userMessage
                    && userMessage.hasSingleText()) {
                return estimateText(userMessage.singleText());
            }
            if (message instanceof AiMessage aiMessage) {
                int tokens = estimateText(aiMessage.text());
                for (ToolExecutionRequest request
                        : aiMessage.toolExecutionRequests()) {
                    tokens += estimateText(request.id());
                    tokens += estimateText(request.name());
                    tokens += estimateText(request.arguments());
                }
                return tokens;
            }
            if (message instanceof ToolExecutionResultMessage result) {
                return estimateText(result.id())
                        + estimateText(result.toolName())
                        + estimateText(result.text());
            }
            return estimateText(message.toString());
        }
    }

    private static final class RealGateFixture implements AutoCloseable {

        private static final long APP_ID = 7L;

        private final String oldUser;
        private final String oldAi;
        private final String recentUser;
        private final String recentAi;
        private final CharacterCountingTokenEstimator estimator =
                new CharacterCountingTokenEstimator();
        private final MemoryTokenProperties properties =
                new MemoryTokenProperties();
        private final MemorySummaryService summaryService =
                mock(MemorySummaryService.class);
        private final UserMemoryService userMemoryService =
                mock(UserMemoryService.class);
        private final ChatHistoryService historyService =
                mock(ChatHistoryService.class);
        private final ExecutorService compressionExecutor =
                Executors.newSingleThreadExecutor();
        private final ExecutorService gateExecutor =
                Executors.newVirtualThreadPerTaskExecutor();
        private final SimpleMeterRegistry memoryMetricsRegistry =
                new SimpleMeterRegistry();
        private final CountDownLatch compressionStarted =
                new CountDownLatch(1);
        private final CountDownLatch releaseCompression =
                new CountDownLatch(1);
        private final CompressionAwareChatMemory memory;
        private final ContextCompressionModelRequestGate gate;
        private final AppOperationLeaseManager operationManager =
                new AppOperationLeaseManager();

        private RealGateFixture(
                int oldTurnTokens,
                int recentTurnTokens) {
            oldUser = "旧".repeat(oldTurnTokens / 2);
            oldAi = "答".repeat(oldTurnTokens - oldUser.length());
            recentUser = "新".repeat(recentTurnTokens / 2);
            recentAi = "应".repeat(
                    recentTurnTokens - recentUser.length());
            properties.setBlockingTimeout(Duration.ofSeconds(5));
            when(summaryService.getCurrentSummary(APP_ID)).thenReturn("");
            when(summaryService.getRequiredSummary(APP_ID, 2L))
                    .thenReturn(VALID_SUMMARY);
            when(summaryService.lastSummarizedId(APP_ID)).thenReturn(0L);
            when(userMemoryService.recallByApp(APP_ID)).thenReturn("");
            when(historyService.listRecentCompleteTurnBoundaries(APP_ID, 2))
                    .thenReturn(List.of(
                            new ChatHistoryService.StableTurnBoundary(
                                    1L, 2L, oldUser, oldAi),
                            new ChatHistoryService.StableTurnBoundary(
                                    3L, 4L, recentUser, recentAi)));
            when(summaryService.compressNow(
                    eq(APP_ID), eq(2L), any(Duration.class)))
                    .thenAnswer(invocation -> {
                        compressionStarted.countDown();
                        try {
                            if (!releaseCompression.await(
                                    5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException(
                                        "测试未及时释放真实压缩");
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(
                                    "真实压缩等待被中断", exception);
                        }
                        return new MemoryCompressionResult(
                                MemoryCompressionResult.Status.COMPRESSED,
                                2L, 800, "完成");
                    });
            MessageWindowChatMemory delegate =
                    MessageWindowChatMemory.builder()
                            .id(APP_ID)
                            .maxMessages(Integer.MAX_VALUE)
                            .build();
            memory = new CompressionAwareChatMemory(
                    new TokenAwareChatMemory(delegate),
                    summaryService,
                    userMemoryService);
            memory.add(UserMessage.from(oldUser));
            memory.add(AiMessage.from(oldAi));
            memory.add(UserMessage.from(recentUser));
            memory.add(AiMessage.from(recentAi));
            ContextCompressionCoordinator coordinator =
                    new ContextCompressionCoordinator(
                            estimator,
                            historyService,
                            summaryService,
                            properties,
                            compressionExecutor,
                            new AppDataLifecycleFence(),
                            new MemoryCompressionMetricsCollector(
                                    memoryMetricsRegistry));
            gate = new ContextCompressionModelRequestGate(
                    coordinator, gateExecutor);
        }

        private SimpleGenerationTurnContext openTurn(String ownerToken) {
            var operation = operationManager.acquire(
                    APP_ID,
                    AppOperationLeaseManager.AppOperationType.GENERATE,
                    ownerToken);
            return new SimpleGenerationTurnContext(operation);
        }

        private VueTurnContext openVueTurn(String ownerToken) {
            return ContextCompressionModelRequestGateTest.openVueTurn(
                    operationManager, ownerToken);
        }

        private boolean awaitCompressionStarted()
                throws InterruptedException {
            return compressionStarted.await(2, TimeUnit.SECONDS);
        }

        private void releaseCompression() {
            releaseCompression.countDown();
        }

        private CompressionAwareChatMemory memory() {
            return memory;
        }

        private void rebuildL0AfterSummaryCursor() {
            List<ChatMessage> oldTurn = memory.completeTurnSnapshot()
                    .completedTurns().getFirst().messages();
            assertTrue(memory.removeCompletedPrefixIfMatches(oldTurn));
            when(summaryService.lastSummarizedId(APP_ID)).thenReturn(2L);
            when(historyService.listRecentCompleteTurnBoundaries(APP_ID, 1))
                    .thenReturn(List.of(
                            new ChatHistoryService.StableTurnBoundary(
                                    3L, 4L, recentUser, recentAi)));
        }

        private ContextCompressionModelRequestGate gate() {
            return gate;
        }

        private CharacterCountingTokenEstimator estimator() {
            return estimator;
        }

        private MemoryTokenProperties properties() {
            return properties;
        }

        private MemorySummaryService summaryService() {
            return summaryService;
        }

        private String oldUser() {
            return oldUser;
        }

        private String recentUser() {
            return recentUser;
        }

        @Override
        public void close() {
            releaseCompression.countDown();
            gateExecutor.shutdownNow();
            compressionExecutor.shutdownNow();
            memoryMetricsRegistry.close();
        }
    }

    private void assertStatus(
            ContextCompressionMode mode,
            ContextAdmissionResult.FailureReason failureReason,
            ModelRequestGate.Status expected) throws Exception {
        ContextCompressionCoordinator coordinator =
                mock(ContextCompressionCoordinator.class);
        CompressionAwareChatMemory memory =
                compressionMemory("协调后的活动消息");
        List<ChatMessage> latestMessages = List.of(
                UserMessage.from("协调后的活动消息"));
        when(coordinator.admit(eq(memory), eq(List.of()), any(), any()))
                .thenReturn(result(mode, failureReason, 31_000, 29_000));

        try (ExecutorService executor =
                     Executors.newVirtualThreadPerTaskExecutor()) {
            ContextCompressionModelRequestGate gate =
                    new ContextCompressionModelRequestGate(
                            coordinator, executor);

            ModelRequestGate.Decision decision = gate.prepare(request(memory))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertEquals(expected, decision.status());
            assertEquals(latestMessages, decision.messages());
            assertEquals(29_000, decision.estimatedInputTokens());
            if (expected == ModelRequestGate.Status.ALLOWED) {
                assertTrue(decision.safeMessage().isBlank());
            } else {
                assertFalse(decision.safeMessage().isBlank());
            }
        }
    }

    private ModelRequestGate.Request request(ChatMemory memory) {
        return request(memory, action -> {
            action.run();
            return true;
        });
    }

    private Flux<GenerationStreamEvent> realGateBusiness(
            RealGateFixture fixture,
            ContextContinuationGate continuationGate,
            String content,
            AtomicInteger subscriptions) {
        return Flux.defer(() -> {
            subscriptions.incrementAndGet();
            return Mono.fromCompletionStage(fixture.gate().prepare(
                            request(fixture.memory(), continuationGate)))
                    .doOnNext(decision -> assertEquals(
                            ModelRequestGate.Status.ALLOWED,
                            decision.status()))
                    .thenReturn((GenerationStreamEvent)
                            GenerationStreamEvent.content(content))
                    .flux();
        });
    }

    private void releaseStartedCompression(RealGateFixture fixture) {
        try {
            assertTrue(fixture.awaitCompressionStarted(),
                    "30K 请求必须进入真实阻塞压缩");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待真实阻塞压缩被中断", exception);
        }
        fixture.releaseCompression();
    }

    private SimpleGenerationTurnContext openIndependentSimpleTurn(
            String ownerToken) {
        var operation = new AppOperationLeaseManager().acquire(
                7L,
                AppOperationLeaseManager.AppOperationType.GENERATE,
                ownerToken);
        return new SimpleGenerationTurnContext(operation);
    }

    private VueTurnContext openIndependentVueTurn(String ownerToken) {
        return openVueTurn(new AppOperationLeaseManager(), ownerToken);
    }

    private static VueTurnContext openVueTurn(
            AppOperationLeaseManager operationManager,
            String ownerToken) {
        var admission = new VueTurnAdmissionController(
                new VueBuildRepairMetricsCollector(new SimpleMeterRegistry()))
                .tryAcquire()
                .orElseThrow();
        AppOperationLeaseManager.AppOperationLease operation = null;
        VueBuildSessionManager.VueBuildLease vueLease = null;
        try {
            operation = operationManager.acquire(
                    7L,
                    AppOperationLeaseManager.AppOperationType.GENERATE,
                    ownerToken);
            vueLease = new VueBuildSessionManager().open(
                    operation, 9L, ownerToken);
            VueTurnContext context = new VueTurnContext(
                    7L, 9L, ownerToken, operation, vueLease, admission,
                    new FileToolBudgetGuard().newSession());
            admission = null;
            operation = null;
            vueLease = null;
            return context;
        } finally {
            VueTurnContext.closeAll(vueLease, operation, admission);
        }
    }

    private ModelRequestGate.Request request(
            ChatMemory memory,
            ModelRequestGate.ContinuationGate continuationGate) {
        return new ModelRequestGate.Request(
                7L, () -> memory, List.of(), continuationGate);
    }

    private ContextAdmissionResult result(
            ContextCompressionMode mode,
            ContextAdmissionResult.FailureReason failureReason,
            int initialTokens,
            int finalTokens) {
        return result(mode, failureReason, initialTokens, finalTokens,
                List.of(UserMessage.from("协调后的活动消息")));
    }

    private ContextAdmissionResult result(
            ContextCompressionMode mode,
            ContextAdmissionResult.FailureReason failureReason,
            int initialTokens,
            int finalTokens,
            List<ChatMessage> requestMessages) {
        return new ContextAdmissionResult(
                mode, initialTokens, finalTokens, requestMessages, 0L,
                failureReason, "测试结果");
    }

    private ChatMemory memory(String message) {
        MessageWindowChatMemory memory =
                MessageWindowChatMemory.withMaxMessages(10);
        memory.add(UserMessage.from(message));
        return memory;
    }

    private CompressionAwareChatMemory compressionMemory(String message) {
        MessageWindowChatMemory delegate = MessageWindowChatMemory.builder()
                .id(7L)
                .maxMessages(Integer.MAX_VALUE)
                .build();
        MemorySummaryService summaryService = mock(MemorySummaryService.class);
        UserMemoryService userMemoryService = mock(UserMemoryService.class);
        when(summaryService.getCurrentSummary(7L)).thenReturn("");
        when(userMemoryService.recallByApp(7L)).thenReturn("");
        CompressionAwareChatMemory memory = new CompressionAwareChatMemory(
                new TokenAwareChatMemory(delegate),
                summaryService,
                userMemoryService);
        memory.add(UserMessage.from(message));
        return memory;
    }

    private static final class RecordingProgressGate
            implements ContextContinuationGate {

        private final List<ContextCompressionMessage.Phase> phases =
                new CopyOnWriteArrayList<>();
        private final AtomicInteger attempts = new AtomicInteger();
        private volatile boolean open = true;

        @Override
        public boolean tryRun(Runnable action) {
            attempts.incrementAndGet();
            if (!open) {
                return false;
            }
            action.run();
            return true;
        }

        @Override
        public void publishContextCompression(
                ContextCompressionMessage message) {
            phases.add(message.phase());
        }

        private List<ContextCompressionMessage.Phase> phases() {
            return List.copyOf(phases);
        }

        private int attempts() {
            return attempts.get();
        }

        private void close() {
            open = false;
        }
    }
}
