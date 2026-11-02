package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.AiGeneratorServiceFactory;
import com.lyw.appgeneration.ai.AiCodeGeneratorService;
import com.lyw.appgeneration.ai.VueTurnModeRoutingService;
import com.lyw.appgeneration.ai.VueTurnModeRoutingServiceFactory;
import com.lyw.appgeneration.ai.image.ImageCollectionService;
import com.lyw.appgeneration.ai.memory.TurnRequestBoundary;
import com.lyw.appgeneration.ai.memory.ToolMessageCollapser;
import com.lyw.appgeneration.ai.model.message.ContextCompressionMessage;
import com.lyw.appgeneration.ai.tools.FileToolBudgetGuard;
import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.core.AiCodeGeneratorFacade;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import com.lyw.appgeneration.core.concurrency.VueTurnAdmissionController;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.core.handler.StreamHandlerExecutor;
import com.lyw.appgeneration.core.handler.VueTurnContext;
import com.lyw.appgeneration.core.handler.VueTurnMode;
import com.lyw.appgeneration.core.handler.GenerationStreamEvent;
import com.lyw.appgeneration.core.handler.GenerationCancellationRegistry;
import com.lyw.appgeneration.core.handler.VueTurnCancellationCoordinator;
import com.lyw.appgeneration.core.handler.VueTurnFinalizer;
import com.lyw.appgeneration.core.handler.VueTurnMemoryProjection;
import com.lyw.appgeneration.core.handler.VueTurnOutcome;
import com.lyw.appgeneration.exception.GenerationPreflightException;
import com.lyw.appgeneration.exception.ModelServiceErrorMessages;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;
import com.lyw.appgeneration.monitor.AppLifecycleMetricsCollector;
import com.lyw.appgeneration.monitor.ThrowingMeterRegistry;
import com.lyw.appgeneration.monitor.VueBuildRepairMetricsCollector;
import com.lyw.appgeneration.manger.ToolManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.UserMemoryService;
import com.lyw.appgeneration.service.VueTurnModeRouter;
import com.lyw.appgeneration.service.rag.RagPromptAssembler;
import com.lyw.appgeneration.service.rag.RagRetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.Disposable;
import reactor.core.publisher.Hooks;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

class AppServiceImplVueTurnTest {

    @Test
    void 主动取消准备中的回合不得写用户消息或伪造AI终态() throws Exception {
        CountDownLatch enteredHistory = new CountDownLatch(1);
        CountDownLatch releaseHistory = new CountDownLatch(1);
        when(history.getLastMessage(APP_ID)).thenAnswer(invocation -> {
            enteredHistory.countDown();
            assertTrue(releaseHistory.await(2, TimeUnit.SECONDS));
            return null;
        });
        try (var cancellationExecutor = Executors.newVirtualThreadPerTaskExecutor();
             var coordinator = installRealVuePipeline(mock(ToolMessageCollapser.class), cancellationExecutor)) {
            StepVerifier.create(service.chatToGenCode(APP_ID, "需求", "cancel-before-user-commit",
                            User.builder().id(USER_ID).build()))
                    .then(() -> {
                        try {
                            assertTrue(enteredHistory.await(2, TimeUnit.SECONDS));
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(failure);
                        }
                        assertTrue(service.cancelGeneration(APP_ID, "cancel-before-user-commit",
                                User.builder().id(USER_ID).build()));
                        releaseHistory.countDown();
                    })
                    .expectError(java.util.concurrent.CancellationException.class)
                    .verify(Duration.ofSeconds(3));
        } finally {
            releaseHistory.countDown();
        }
        verify(history, never()).addChatMessageAndReturn(anyLong(), anyString(), anyString(), anyLong());
        verify(history, never()).addAiMessageAndReturn(anyLong(), anyString(), anyString(), any(), anyLong());
        verify(facade, never()).prepareVueGenerator(anyLong());
        operationManager.acquire(APP_ID, AppOperationLeaseManager.AppOperationType.GENERATE,
                "after-precommit-cancellation").close();
    }

    @Test
    void 主动取消收尾本身失败必须结束原流并传播错误() {
        prepareExplicitCancellation();
        when(facade.generateVueProjectStream(
                eq("需求"), eq(APP_ID), eq(true), any(), eq(generator)))
                .thenReturn(Flux.concat(Flux.just("已开始"), Flux.never()));
        when(executor.doExecuteVue(any(), any())).thenAnswer(invocation ->
                invocation.<Flux<String>>getArgument(0).map(GenerationStreamEvent::content));
        IllegalStateException failure = new IllegalStateException("取消收尾失败");
        when(finalizer.finalizeOnce(any(), any())).thenAnswer(invocation -> {
            VueTurnContext context = invocation.getArgument(0);
            context.closeResources();
            ReflectionTestUtils.invokeMethod(context, "failFinalization", failure);
            throw failure;
        });
        try (var coordinator = new VueTurnCancellationCoordinator(finalizer, Runnable::run,
                new VueBuildRepairMetricsCollector(metricsRegistry))) {
            ReflectionTestUtils.setField(service, "vueTurnCancellationCoordinator", coordinator);
            StepVerifier.create(service.chatToGenCode(APP_ID, "需求", "cancel-finalization-failure",
                            User.builder().id(USER_ID).build()))
                    .expectNext(GenerationStreamEvent.content("已开始"))
                    .then(() -> assertTrue(service.cancelGeneration(APP_ID, "cancel-finalization-failure",
                            User.builder().id(USER_ID).build())))
                    .expectErrorMatches(error -> error == failure)
                    .verify(Duration.ofSeconds(3));
        }
        operationManager.acquire(APP_ID, AppOperationLeaseManager.AppOperationType.GENERATE,
                "after-finalization-failure").close();
    }

    @Test
    void 已发正文后主动取消必须仍发送唯一终态并结束流() {
        ToolMessageCollapser collapser = prepareExplicitCancellation();
        when(facade.generateVueProjectStream(
                eq("需求"), eq(APP_ID), eq(true), any(), eq(generator)))
                .thenReturn(Flux.concat(Flux.just(startedText()), Flux.never()));

        try (var coordinator = installRealVuePipeline(collapser)) {
            StepVerifier.create(service.chatToGenCode(APP_ID, "需求", "explicit-after-text",
                            User.builder().id(USER_ID).build()))
                    .expectNextMatches(event -> event instanceof GenerationStreamEvent.AiText)
                    .then(() -> assertTrue(service.cancelGeneration(APP_ID, "explicit-after-text",
                            User.builder().id(USER_ID).build())))
                    .expectNextMatches(event -> event instanceof GenerationStreamEvent.TurnOutcome terminal
                            && terminal.message().getOutcome() == VueTurnOutcome.TurnOutcomeType.CANCELLED)
                    .expectComplete()
                    .verify(Duration.ofSeconds(3));
        }
        verify(history, times(1)).addAiMessageAndReturn(eq(APP_ID), anyString(), anyString(),
                eq(ChatMemoryOutcome.CANCELLED), eq(USER_ID));
        operationManager.acquire(APP_ID, AppOperationLeaseManager.AppOperationType.GENERATE,
                "after-explicit-cancellation").close();
    }

    @Test
    void 取消使业务流先结束时必须等持久收尾完成再发送终态() {
        ToolMessageCollapser collapser = prepareExplicitCancellation();
        var pending = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
        var model = reactor.core.publisher.Sinks.many().unicast().<String>onBackpressureBuffer();
        when(facade.generateVueProjectStream(
                eq("需求"), eq(APP_ID), eq(true), any(), eq(generator)))
                .thenAnswer(invocation -> {
                    VueTurnContext context = invocation.getArgument(3);
                    context.registerModelCancellation(model::tryEmitComplete);
                    model.tryEmitNext(startedText());
                    return model.asFlux();
                });

        try (var coordinator = installRealVuePipeline(collapser, pending::add)) {
            StepVerifier.create(service.chatToGenCode(APP_ID, "需求", "explicit-delayed-finalization",
                            User.builder().id(USER_ID).build()))
                    .expectNextMatches(event -> event instanceof GenerationStreamEvent.AiText)
                    .then(() -> assertTrue(service.cancelGeneration(APP_ID, "explicit-delayed-finalization",
                            User.builder().id(USER_ID).build())))
                    .expectNoEvent(Duration.ofMillis(50))
                    .then(() -> pending.remove().run())
                    .expectNextMatches(event -> event instanceof GenerationStreamEvent.TurnOutcome terminal
                            && terminal.message().getOutcome() == VueTurnOutcome.TurnOutcomeType.CANCELLED)
                    .expectComplete()
                    .verify(Duration.ofSeconds(3));
        }
        verify(history, times(1)).addAiMessageAndReturn(eq(APP_ID), anyString(), anyString(),
                eq(ChatMemoryOutcome.CANCELLED), eq(USER_ID));
    }

    private ToolMessageCollapser prepareExplicitCancellation() {
        when(history.getLastMessage(APP_ID)).thenReturn(null);
        when(history.addChatMessageAndReturn(APP_ID, "需求", "user", USER_ID))
                .thenReturn(savedUser(101L, "需求"));
        when(history.addAiMessageAndReturn(eq(APP_ID), anyString(), anyString(),
                eq(ChatMemoryOutcome.CANCELLED), eq(USER_ID)))
                .thenAnswer(invocation -> savedAiMessage(invocation.getArgument(1)));
        ToolMessageCollapser collapser = mock(ToolMessageCollapser.class);
        when(collapser.finishLastTurn(any(), any(), any())).thenReturn(collapsed());
        return collapser;
    }

    private String startedText() {
        return cn.hutool.json.JSONUtil.toJsonStr(
                new com.lyw.appgeneration.ai.model.message.AiResponseMessage(1L, "已经开始检查。"));
    }

    @Test
    void 相同原文的两次提交向生成入口传递各自持久记录而不猜最后记录() {
        when(history.getLastMessage(APP_ID)).thenReturn(null);
        when(history.addChatMessageAndReturn(APP_ID, "需求", "user", USER_ID))
                .thenReturn(savedUser(301L, "需求"), savedUser(302L, "需求"));
        List<Long> identities = new ArrayList<>();
        when(facade.generateVueProjectStream(eq("需求"), eq(APP_ID), anyBoolean(), any(), eq(generator)))
                .thenAnswer(invocation -> {
                    VueTurnContext context = invocation.getArgument(3);
                    identities.add(context.userHistoryId());
                    return Flux.just("正文");
                });
        when(executor.doExecuteVue(any(), any())).thenAnswer(invocation -> {
            VueTurnContext context = invocation.getArgument(1);
            return invocation.<Flux<String>>getArgument(0).map(GenerationStreamEvent::content)
                    .doFinally(ignored -> context.closeResources());
        });
        for (int index = 0; index < 2; index++) {
            service.chatToGenCode(APP_ID, "需求", User.builder().id(USER_ID).build()).blockLast();
        }
        assertEquals(List.of(301L, 302L), identities);
        verify(history, never()).addChatMessage(anyLong(), anyString(), anyString(), anyLong());
        verify(history, times(2)).getLastMessage(APP_ID);
    }

    @Test
    void Vue原始需求超过请求边界时不得写入用户消息或启动模型() {
        String oversizedMessage = "a".repeat(
                TurnRequestBoundary.MAX_RAW_USER_TEXT_LENGTH + 1);

        assertThrows(GenerationPreflightException.class, () ->
                service.chatToGenCode(
                                APP_ID, oversizedMessage,
                                User.builder().id(USER_ID).build())
                        .blockLast());

        verifyNoInteractions(history, facade, executor);
    }

    @Test
    void Vue服务真实回合在模型正文前合并压缩进度且只订阅业务一次() {
        AtomicInteger businessSubscriptions = new AtomicInteger();
        when(history.getLastMessage(APP_ID)).thenReturn(null);
        when(history.addChatMessageAndReturn(APP_ID, "需求", "user", USER_ID))
                .thenReturn(savedUser(101L, "需求"));
        when(facade.generateVueProjectStream(
                eq("需求"), eq(APP_ID), eq(true), any(), eq(generator)))
                .thenAnswer(invocation -> {
                    VueTurnContext context = invocation.getArgument(3);
                    return Flux.defer(() -> {
                        businessSubscriptions.incrementAndGet();
                        assertTrue(context.tryRun(() ->
                                context.publishContextCompression(
                                        ContextCompressionMessage.started())));
                        assertTrue(context.tryRun(() ->
                                context.publishContextCompression(
                                        ContextCompressionMessage.completed())));
                        return Flux.just("正文");
                    });
                });
        when(executor.doExecuteVue(any(), any())).thenAnswer(invocation -> {
            VueTurnContext context = invocation.getArgument(1);
            return invocation.<Flux<String>>getArgument(0)
                    .map(GenerationStreamEvent::content)
                    .doFinally(ignored -> context.closeResources());
        });

        StepVerifier.create(service.chatToGenCode(
                        APP_ID, "需求", User.builder().id(USER_ID).build()))
                .assertNext(event -> assertEquals(
                        ContextCompressionMessage.Phase.STARTED,
                        ((GenerationStreamEvent.ContextCompression) event)
                                .message().phase()))
                .assertNext(event -> assertEquals(
                        ContextCompressionMessage.Phase.COMPLETED,
                        ((GenerationStreamEvent.ContextCompression) event)
                                .message().phase()))
                .expectNext(GenerationStreamEvent.content("正文"))
                .verifyComplete();

        assertEquals(1, businessSubscriptions.get());
    }

    private ChatHistory savedUser(long id, String message) {
        return ChatHistory.builder().id(id).appId(APP_ID).userId(USER_ID)
                .messageType("user").message(message).build();
    }

    private static final long APP_ID = 7L;
    private static final long USER_ID = 9L;

    private final AiCodeGeneratorFacade facade = mock(AiCodeGeneratorFacade.class);
    private final AiGeneratorServiceFactory factory = mock(AiGeneratorServiceFactory.class);
    private final ChatHistoryService history = mock(ChatHistoryService.class);
    private final StreamHandlerExecutor executor = mock(StreamHandlerExecutor.class);
    private final AiCodeGeneratorService generator = mock(AiCodeGeneratorService.class);
    private final VueTurnCancellationCoordinator cancellationCoordinator =
            mock(VueTurnCancellationCoordinator.class);
    private final VueTurnFinalizer finalizer = mock(VueTurnFinalizer.class);
    private final VueTurnModeRoutingServiceFactory vueTurnModeRoutingServiceFactory =
            mock(VueTurnModeRoutingServiceFactory.class);
    private final VueTurnModeRoutingService vueTurnModeRoutingService =
            mock(VueTurnModeRoutingService.class);
    private final VueTurnModeRouter vueTurnModeRouter = new VueTurnModeRouter(
            vueTurnModeRoutingServiceFactory);
    private AppOperationLeaseManager operationManager;
    private AppServiceImpl service;
    private final com.lyw.appgeneration.service.AppNameService naming =
            mock(com.lyw.appgeneration.service.AppNameService.class);

    @org.junit.jupiter.api.AfterEach
    void 生成回合不能触发创建命名() {
        org.mockito.Mockito.verifyNoInteractions(naming);
    }
    private SimpleMeterRegistry metricsRegistry;
    private VueTurnAdmissionController admissionController;

    @BeforeEach
    void setUp() {
        operationManager = new AppOperationLeaseManager();
        metricsRegistry = new SimpleMeterRegistry();
        admissionController = new VueTurnAdmissionController(
                new VueBuildRepairMetricsCollector(metricsRegistry));
        service = new AppServiceImpl();
        ReflectionTestUtils.setField(service, "appNameService", naming);
        ReflectionTestUtils.setField(service, "aiCodeGeneratorFacade", facade);
        ReflectionTestUtils.setField(service, "aiGeneratorServiceFactory", factory);
        ReflectionTestUtils.setField(service, "chatHistoryService", history);
        ReflectionTestUtils.setField(service, "streamHandlerExecutor", executor);
        ReflectionTestUtils.setField(service, "appOperationLeaseManager", operationManager);
        ReflectionTestUtils.setField(service, "appLifecycleMetricsCollector",
                new AppLifecycleMetricsCollector(metricsRegistry));
        ReflectionTestUtils.setField(service, "vueBuildSessionManager",
                new VueBuildSessionManager());
        ReflectionTestUtils.setField(service, "vueTurnAdmissionController",
                admissionController);
        ReflectionTestUtils.setField(service, "vueTurnCancellationCoordinator",
                cancellationCoordinator);
        ReflectionTestUtils.setField(service, "vueTurnFinalizer", finalizer);
        ReflectionTestUtils.setField(service, "fileToolBudgetGuard",
                new com.lyw.appgeneration.ai.tools.FileToolBudgetGuard());
        ReflectionTestUtils.setField(service, "vueTurnModeRouter",
                vueTurnModeRouter);
        ReflectionTestUtils.setField(service, "generationCancellationRegistry",
                new GenerationCancellationRegistry());
        when(vueTurnModeRoutingServiceFactory.create())
                .thenReturn(vueTurnModeRoutingService);
        when(vueTurnModeRoutingService.route(anyString()))
                .thenReturn(VueTurnMode.MUTATION_REQUIRED);
        App app = App.builder().id(APP_ID).userId(USER_ID)
                .codeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue()).build();
        service = org.mockito.Mockito.spy(service);
        org.mockito.Mockito.doReturn(app).when(service).getById(APP_ID);
        when(factory.getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.VUE_PROJECT))
                .thenReturn(generator);
        when(facade.prepareVueGenerator(APP_ID)).thenReturn(generator);
        when(cancellationCoordinator.requestPreCommitCleanup(any()))
                .thenAnswer(invocation -> {
                    VueTurnContext context = invocation.getArgument(0);
                    context.cancelGeneration();
                    context.closeResources();
                    return CompletableFuture.completedFuture(null);
                });
    }

    @Test
    void 全局准入满额必须在领取应用租约和写用户消息前拒绝() {
        List<VueTurnAdmissionController.AdmissionPermit> permits =
                new ArrayList<>();
        try {
            for (int index = 0;
                    index < VueTurnAdmissionController.MAX_ACTIVE_TURNS;
                    index++) {
                permits.add(admissionController.tryAcquire().orElseThrow());
            }

            GenerationPreflightException exception = assertThrows(
                    GenerationPreflightException.class,
                    () -> service.chatToGenCode(
                            APP_ID, "需求", User.builder().id(USER_ID).build())
                            .blockLast());

            assertEquals(GenerationPreflightException.Kind.BUSINESS,
                    exception.kind());
            assertEquals(com.lyw.appgeneration.exception.ErrorCode
                    .TOO_MANY_REQUEST.getCode(), exception.code());
            assertEquals("当前生成任务较多，请稍后再试", exception.safeMessage());
            assertTrue(metricsRegistry.find("app_operations_total")
                    .meters().isEmpty(), "全局拒绝不得伪造 app 租约领取指标");
            verifyNoInteractions(history, facade, executor, finalizer);
            operationManager.acquire(APP_ID,
                    AppOperationLeaseManager.AppOperationType.GENERATE,
                    "after-admission-rejected").close();
        } finally {
            permits.forEach(VueTurnAdmissionController.AdmissionPermit::close);
        }
    }

    @Test
    void 用户已提交但Handler尚未接管时取消仍必须完成稳定收尾()
            throws Exception {
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch cancellationTaskStarted = new CountDownLatch(1);
        CompletableFuture<Void> releaseHandler = new CompletableFuture<>();
        AtomicInteger modelStarts = new AtomicInteger();
        AtomicReference<VueTurnContext> capturedContext = new AtomicReference<>();
        AtomicReference<Throwable> droppedError = new AtomicReference<>();
        ToolMessageCollapser collapser = mock(ToolMessageCollapser.class);
        MemorySummaryService summary = com.lyw.appgeneration.service.MemorySummaryTestSupport.terminalPersistenceStub();
        UserMemoryService preference = mock(UserMemoryService.class);
        var repairMetrics = new VueBuildRepairMetricsCollector(metricsRegistry);
        VueTurnFinalizer realFinalizer = new VueTurnFinalizer(
                history, collapser, summary, preference, factory,
                new AppDataLifecycleFence(), repairMetrics,
                new FileToolBudgetGuard());
        when(history.getLastMessage(APP_ID)).thenReturn(null);
        when(history.addChatMessageAndReturn(APP_ID, "需求", "user", USER_ID))
                .thenReturn(savedUser(101L, "需求"));
        when(history.addAiMessageAndReturn(
                eq(APP_ID), eq(VueTurnFinalizer.CANCELLED_MESSAGE),
                eq(cancelledMemoryProjection()),
                eq(ChatMemoryOutcome.CANCELLED), eq(USER_ID)))
                .thenReturn(savedAiMessage(
                        VueTurnFinalizer.CANCELLED_MESSAGE));
        when(collapser.finishLastTurn(any(), any(), any()))
                .thenReturn(new ToolMessageCollapser.CollapseResult(
                        ToolMessageCollapser.CollapseStatus.COLLAPSED,
                        List.of()));
        when(facade.generateVueProjectStream(
                eq("需求"), eq(APP_ID), eq(true), any(), eq(generator)))
                .thenReturn(Flux.defer(() -> {
                    modelStarts.incrementAndGet();
                    return Flux.never();
                }));
        when(executor.doExecuteVue(any(), any())).thenAnswer(invocation -> {
            capturedContext.set(invocation.getArgument(1));
            handlerEntered.countDown();
            releaseHandler.join();
            return invocation.getArgument(0);
        });
        Executor controlledCancellationExecutor = task -> {
            cancellationTaskStarted.countDown();
            task.run();
        };

        ExecutorService cancellationCaller =
                Executors.newVirtualThreadPerTaskExecutor();
        try (var realCoordinator = new VueTurnCancellationCoordinator(
                realFinalizer, controlledCancellationExecutor,
                repairMetrics)) {
            Hooks.onErrorDropped(droppedError::set);
            ReflectionTestUtils.setField(service, "vueTurnFinalizer",
                    realFinalizer);
            ReflectionTestUtils.setField(service,
                    "vueTurnCancellationCoordinator", realCoordinator);
            Disposable subscription = service.chatToGenCode(
                    APP_ID, "需求", User.builder().id(USER_ID).build())
                    .subscribe();
            assertTrue(handlerEntered.await(2, TimeUnit.SECONDS));
            assertEquals(VueTurnContext.UserCommitState.COMMITTED,
                    capturedContext.get().userCommitState());

            CompletableFuture<Void> cancellationCall =
                    CompletableFuture.runAsync(
                            subscription::dispose, cancellationCaller);
            assertTrue(cancellationTaskStarted.await(2, TimeUnit.SECONDS),
                    "取消收尾任务必须真正开始");
            assertEquals(VueTurnContext.TurnStage.FINALIZING,
                    capturedContext.get().turnState().stage());
            assertFalse(capturedContext.get()
                            .awaitQuiescence(Duration.ZERO),
                    "同步 Handler 装配必须计入回合静默边界");
            assertFalse(cancellationCall.isDone(),
                    "Handler 未释放时取消调用不能完成");
            assertThrows(AppOperationLeaseManager.ActiveAppOperationException.class,
                    () -> operationManager.acquire(
                            APP_ID,
                            AppOperationLeaseManager.AppOperationType.GENERATE,
                            "before-cancel-finalized"));
            verify(history, never()).addAiMessageAndReturn(
                    eq(APP_ID), eq(VueTurnFinalizer.CANCELLED_MESSAGE),
                    eq(cancelledMemoryProjection()),
                    eq(ChatMemoryOutcome.CANCELLED), eq(USER_ID));
            releaseHandler.complete(null);

            cancellationCall.get(2, TimeUnit.SECONDS);
            verify(history, times(1)).addAiMessageAndReturn(
                    eq(APP_ID), eq(VueTurnFinalizer.CANCELLED_MESSAGE),
                    eq(cancelledMemoryProjection()),
                    eq(ChatMemoryOutcome.CANCELLED), eq(USER_ID));
            verify(facade, never()).generateVueProjectStream(
                    anyString(), anyLong(), anyBoolean(), any(), any());
            assertEquals(0, modelStarts.get());
            assertEquals(null, droppedError.get(),
                    "正常取消后的迟到 Handler 不得产生丢弃异常");
            assertEquals(VueTurnContext.TurnStage.FINALIZED,
                    capturedContext.get().turnState().stage());
            operationManager.acquire(
                    APP_ID, AppOperationLeaseManager.AppOperationType.GENERATE,
                    "after-cancel-finalized").close();
        } finally {
            releaseHandler.complete(null);
            cancellationCaller.close();
            Hooks.resetOnErrorDropped();
        }
    }

    @Test
    void serviceCreationAndUserSavePrecedeModelSubscription() {
        AtomicInteger starts = new AtomicInteger();
        when(history.getLastMessage(APP_ID)).thenReturn(null);
        when(facade.generateVueProjectStream(
                eq("需求"), eq(APP_ID), eq(true), any(), eq(generator)))
                .thenReturn(Flux.defer(() -> {
                    starts.incrementAndGet();
                    return Flux.just("raw");
                }));
        when(history.addChatMessageAndReturn(APP_ID, "需求", "user", USER_ID))
                .thenReturn(savedUser(101L, "需求"));
        when(executor.doExecuteVue(any(), any())).thenAnswer(invocation ->
                invocation.<Flux<String>>getArgument(0)
                        .map(GenerationStreamEvent::content));

        Flux<GenerationStreamEvent> result = service.chatToGenCode(
                APP_ID, "需求", User.builder().id(USER_ID).build());

        assertEquals(0, starts.get(), "返回冷 Flux 时模型尚未启动");
        assertEquals("raw", ((GenerationStreamEvent.SimpleText)
                result.blockFirst()).text());
        assertEquals(1, starts.get());
        assertEquals(1.0, metricsRegistry.get("app_operations_total")
                .tags("operation", "generate", "result", "acquired",
                        "conflict_with", "none").counter().count());
        InOrder order = inOrder(history, facade, executor);
        order.verify(facade).prepareVueGenerator(APP_ID);
        order.verify(history).addChatMessageAndReturn(APP_ID, "需求", "user", USER_ID);
        order.verify(executor).doExecuteVue(any(), any());
        order.verify(facade).generateVueProjectStream(
                eq("需求"), eq(APP_ID), eq(true), any(), eq(generator));
    }

    @Test
    void 成功终态后的协议内部取消不得重新请求用户取消() {
        AtomicReference<VueTurnContext> capturedContext = new AtomicReference<>();
        when(history.getLastMessage(APP_ID)).thenReturn(null);
        when(history.addChatMessageAndReturn(APP_ID, "需求", "user", USER_ID))
                .thenReturn(savedUser(101L, "需求"));
        when(facade.generateVueProjectStream(
                eq("需求"), eq(APP_ID), eq(true), any(), eq(generator)))
                .thenReturn(Flux.just("raw"));
        when(executor.doExecuteVue(any(), any())).thenAnswer(invocation -> {
            VueTurnContext context = invocation.getArgument(1);
            capturedContext.set(context);
            VueTurnOutcome outcome = new VueTurnOutcome(
                    com.lyw.appgeneration.core.builder.VueBuildPhase.SUCCEEDED,
                    VueTurnOutcome.TurnOutcomeType.SUCCEEDED,
                    "项目已生成并构建成功。", "可信记忆投影", true,
                    "项目已生成并构建成功。");
            return Flux.defer(() -> {
                assertTrue(context.tryStartFinalization(
                        VueTurnContext.TerminalTrigger.COMPLETED));
                context.closeResources();
                return Flux.just(GenerationStreamEvent.turnOutcome(outcome));
            });
        });

        List<GenerationStreamEvent> events = service.chatToGenCode(
                        APP_ID, "需求", User.builder().id(USER_ID).build())
                .take(1)
                .collectList()
                .block();

        assertEquals(1, events.size());
        assertEquals(VueTurnContext.TerminalTrigger.COMPLETED,
                capturedContext.get().terminalWinner().orElseThrow());
        verify(cancellationCoordinator, never()).requestCancellation(
                eq(capturedContext.get()), any());
        operationManager.acquire(
                APP_ID, AppOperationLeaseManager.AppOperationType.GENERATE,
                "after-success-terminal").close();
    }

    @Test
    void counterIncrementFailureDoesNotChangeUserCommitModelStartOrLeaseRelease() {
        ThrowingMeterRegistry registry = new ThrowingMeterRegistry(
                ThrowingMeterRegistry.FailurePoint.COUNTER_INCREMENT);
        ReflectionTestUtils.setField(service, "appLifecycleMetricsCollector",
                new AppLifecycleMetricsCollector(registry));
        AtomicInteger modelStarts = new AtomicInteger();
        when(history.getLastMessage(APP_ID)).thenReturn(null);
        when(history.addChatMessageAndReturn(APP_ID, "需求", "user", USER_ID))
                .thenReturn(savedUser(101L, "需求"));
        when(facade.generateVueProjectStream(
                eq("需求"), eq(APP_ID), eq(true), any(), eq(generator)))
                .thenReturn(Flux.defer(() -> {
                    modelStarts.incrementAndGet();
                    return Flux.just("raw");
                }));
        when(executor.doExecuteVue(any(), any())).thenAnswer(invocation -> {
            VueTurnContext context = invocation.getArgument(1);
            return invocation.<Flux<String>>getArgument(0)
                    .map(GenerationStreamEvent::content)
                    .doFinally(ignored -> context.closeResources());
        });

        List<GenerationStreamEvent> events = service.chatToGenCode(
                APP_ID, "需求", User.builder().id(USER_ID).build())
                .collectList().block();

        assertEquals(1, events.size());
        assertEquals("raw", ((GenerationStreamEvent.SimpleText)
                events.getFirst()).text());
        assertEquals(1, modelStarts.get());
        verify(history, times(1)).addChatMessageAndReturn(
                APP_ID, "需求", "user", USER_ID);
        verify(facade, times(1)).generateVueProjectStream(
                eq("需求"), eq(APP_ID), eq(true), any(), eq(generator));
        operationManager.acquire(
                APP_ID, AppOperationLeaseManager.AppOperationType.GENERATE,
                "after-metrics-failure").close();
        assertTrue(registry.failureTriggered());
    }

    @Test
    void activeDeployRejectsVueGenerationAndRecordsConflict() {
        Flux<GenerationStreamEvent> result = service.chatToGenCode(
                APP_ID, "需求", User.builder().id(USER_ID).build());
        try (var ignored = operationManager.acquire(
                APP_ID, AppOperationLeaseManager.AppOperationType.DEPLOY,
                "部署中")) {
            StepVerifier.create(result)
                    .expectErrorMessage("应用正在执行其他操作，请稍后再生成")
                    .verify();
        }

        assertEquals(1.0, metricsRegistry.get("app_operations_total")
                .tags("operation", "generate", "result", "rejected",
                        "conflict_with", "deploy").counter().count());
        verifyNoInteractions(history, facade, executor);
    }

    @Test
    void DeepSeek分类余额不足在用户提交前明确提示并释放租约() {
        when(history.getLastMessage(APP_ID)).thenReturn(null);
        when(vueTurnModeRoutingService.route("页面都有什么"))
                .thenThrow(new dev.langchain4j.exception.InvalidRequestException(
                        new dev.langchain4j.exception.HttpException(402, "Insufficient Balance PRIVATE")));

        GenerationPreflightException failure = assertThrows(GenerationPreflightException.class,
                () -> service.chatToGenCode(APP_ID, "页面都有什么", User.builder().id(USER_ID).build()).blockLast());

        assertEquals("模型服务余额不足，请充值后重试。", failure.safeMessage());
        assertEquals(GenerationPreflightException.Kind.SYSTEM, failure.kind());
        verify(history, never()).addChatMessageAndReturn(eq(APP_ID), anyString(), anyString(), eq(USER_ID));
        verify(facade, never()).generateVueProjectStream(anyString(), anyLong(), anyBoolean(), any(), any());
        operationManager.acquire(APP_ID, AppOperationLeaseManager.AppOperationType.GENERATE,
                "after-routing-balance-error").close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 生成同步或流式余额异常在真实收尾链路只保存一次失败(boolean synchronous) {
        String message = ModelServiceErrorMessages.INSUFFICIENT_BALANCE_MESSAGE;
        String projection = VueTurnMemoryProjection.systemError(List.of(),
                VueTurnMemoryProjection.SystemErrorReason.MODEL_INSUFFICIENT_BALANCE);
        var failure = new dev.langchain4j.exception.HttpException(402, "PRIVATE api-key request-id");
        ToolMessageCollapser collapser = mock(ToolMessageCollapser.class);
        when(history.getLastMessage(APP_ID)).thenReturn(null);
        when(history.addChatMessageAndReturn(APP_ID, "需求", "user", USER_ID))
                .thenReturn(savedUser(101L, "需求"));
        when(history.addAiMessageAndReturn(APP_ID, message, projection, ChatMemoryOutcome.SYSTEM_ERROR, USER_ID))
                .thenReturn(savedAiMessage(message));
        when(collapser.finishLastTurn(any(), any(), any())).thenReturn(collapsed());
        if (synchronous) {
            when(facade.generateVueProjectStream(eq("需求"), eq(APP_ID), eq(true), any(), eq(generator)))
                    .thenThrow(failure);
        } else {
            when(facade.generateVueProjectStream(eq("需求"), eq(APP_ID), eq(true), any(), eq(generator)))
                    .thenReturn(Flux.error(new java.util.concurrent.CompletionException(failure)));
        }

        try (var coordinator = installRealVuePipeline(collapser)) {
            List<GenerationStreamEvent> events = service.chatToGenCode(APP_ID, "需求", User.builder().id(USER_ID).build())
                    .collectList().block();
            assertEquals(1, events.size());
            var outcome = ((GenerationStreamEvent.TurnOutcome) events.getFirst()).message();
            assertEquals(VueTurnOutcome.TurnOutcomeType.SYSTEM_ERROR, outcome.getOutcome());
            assertEquals(message, outcome.getMessage());
            assertFalse(outcome.isShouldRefreshPreview());
            operationManager.acquire(APP_ID, AppOperationLeaseManager.AppOperationType.GENERATE,
                    "after-stream-balance-error").close();
        }
        verify(history, times(1)).addAiMessageAndReturn(APP_ID, message, projection,
                ChatMemoryOutcome.SYSTEM_ERROR, USER_ID);
        verify(collapser, times(1)).finishLastTurn(any(), any(), any());
    }

    @Test
    void 提交后启动失败分支保留余额提示并真实持久化() throws Exception {
        var fixture = createCommittedTurnFixture("balance-startup");
        String message = ModelServiceErrorMessages.INSUFFICIENT_BALANCE_MESSAGE;
        String projection = VueTurnMemoryProjection.systemError(List.of(),
                VueTurnMemoryProjection.SystemErrorReason.MODEL_INSUFFICIENT_BALANCE);
        ToolMessageCollapser collapser = mock(ToolMessageCollapser.class);
        when(history.addAiMessageAndReturn(APP_ID, message, projection, ChatMemoryOutcome.SYSTEM_ERROR, USER_ID))
                .thenReturn(savedAiMessage(message));
        when(collapser.finishLastTurn(any(), any(), any())).thenReturn(collapsed());

        try (var coordinator = installRealVuePipeline(collapser)) {
            Flux<GenerationStreamEvent> flow = ReflectionTestUtils.invokeMethod(service,
                    "finalizeCommittedVueFailure", fixture.context(),
                    new dev.langchain4j.exception.HttpException(402, "PRIVATE"));
            var events = flow.collectList().block();
            assertEquals(1, events.size());
            var outcome = ((GenerationStreamEvent.TurnOutcome) events.getFirst()).message();
            assertEquals(message, outcome.getMessage());
            assertFalse(outcome.isShouldRefreshPreview());
            operationManager.acquire(APP_ID, AppOperationLeaseManager.AppOperationType.GENERATE,
                    "after-startup-balance-error").close();
        } finally {
            fixture.context().closeResources();
        }
        verify(history, times(1)).addAiMessageAndReturn(APP_ID, message, projection,
                ChatMemoryOutcome.SYSTEM_ERROR, USER_ID);
    }

    @Test
    void 分类模型异常在用户消息提交前返回系统内部错误() {
        when(history.getLastMessage(APP_ID)).thenReturn(null);
        when(vueTurnModeRoutingService.route("页面都有什么"))
                .thenThrow(new IllegalStateException("分类模型不可用"));
        when(history.addChatMessageAndReturn(APP_ID, "页面都有什么", "user", USER_ID))
                .thenReturn(savedUser(101L, "页面都有什么"));

        GenerationPreflightException exception = assertThrows(
                GenerationPreflightException.class,
                () -> service.chatToGenCode(
                        APP_ID, "页面都有什么",
                        User.builder().id(USER_ID).build()).blockLast());

        assertEquals(GenerationPreflightException.Kind.SYSTEM,
                exception.kind());
        assertEquals("系统内部错误，请稍后尝试。", exception.safeMessage());
        verify(history, never()).addChatMessageAndReturn(
                APP_ID, "页面都有什么", "user", USER_ID);
        verify(facade, never()).generateVueProjectStream(
                anyString(), anyLong(), anyBoolean(), any(), any());
    }

    @Test
    void userSaveFalseNeverStartsModelAndReleasesLease() {
        ImageCollectionService imageCollection = mock(ImageCollectionService.class);
        RagRetrievalService retrieval = mock(RagRetrievalService.class);
        RagPromptAssembler assembler = mock(RagPromptAssembler.class);
        RagProperties ragProperties = new RagProperties();
        ragProperties.setEnabled(true);
        AiCodeGeneratorFacade realFacade = new AiCodeGeneratorFacade();
        ReflectionTestUtils.setField(realFacade, "aiGeneratorServiceFactory", factory);
        ReflectionTestUtils.setField(realFacade, "imageCollectionService", imageCollection);
        ReflectionTestUtils.setField(realFacade, "ragRetrievalService", retrieval);
        ReflectionTestUtils.setField(realFacade, "ragPromptAssembler", assembler);
        ReflectionTestUtils.setField(realFacade, "ragProperties", ragProperties);
        ReflectionTestUtils.setField(service, "aiCodeGeneratorFacade", realFacade);
        when(history.getLastMessage(APP_ID)).thenReturn(null);
        when(history.addChatMessageAndReturn(APP_ID, "需求", "user", USER_ID))
                .thenReturn(null);
        when(executor.doExecuteVue(any(), any())).thenAnswer(invocation -> {
            VueTurnContext context = invocation.getArgument(1);
            return invocation.<Flux<String>>getArgument(0)
                    .doOnError(ignored -> context.closeResources())
                    .map(GenerationStreamEvent::content);
        });

        GenerationPreflightException exception = assertThrows(
                GenerationPreflightException.class, () -> service.chatToGenCode(
                        APP_ID, "需求", User.builder().id(USER_ID).build())
                        .blockLast());
        assertEquals(GenerationPreflightException.Kind.SYSTEM,
                exception.kind());
        verify(executor, never()).doExecuteVue(any(), any());
        verifyNoInteractions(imageCollection, retrieval, assembler, generator);
        operationManager.acquire(APP_ID,
                AppOperationLeaseManager.AppOperationType.GENERATE,
                "next-turn").close();
    }

    @Test
    void orphanUserForcesColdRebuildBeforeServiceCreation() {
        when(history.getLastMessage(APP_ID)).thenReturn(ChatHistory.builder()
                .id(5L).appId(APP_ID).userId(USER_ID).message("旧需求")
                .messageType("user").build());
        when(facade.generateVueProjectStream(anyString(), anyLong(),
                anyBoolean(), any(), any())).thenReturn(Flux.empty());
        when(history.addChatMessageAndReturn(APP_ID, "新需求", "user", USER_ID))
                .thenReturn(savedUser(101L, "新需求"));
        when(history.repairOrphanUserTurn(APP_ID, USER_ID,
                "生成过程中遇到系统异常，请稍后重试。"))
                .thenReturn(true);
        when(executor.doExecuteVue(any(), any())).thenAnswer(invocation ->
                invocation.<Flux<String>>getArgument(0)
                        .map(GenerationStreamEvent::content));

        service.chatToGenCode(APP_ID, "新需求",
                User.builder().id(USER_ID).build()).blockLast();

        InOrder order = inOrder(factory, facade, history);
        order.verify(history).repairOrphanUserTurn(APP_ID, USER_ID,
                "生成过程中遇到系统异常，请稍后重试。");
        order.verify(factory).prepareVueColdRebuild(APP_ID);
        order.verify(history).addChatMessageAndReturn(APP_ID, "新需求", "user", USER_ID);
        order.verify(facade).generateVueProjectStream(
                eq("新需求"), eq(APP_ID), eq(false), any(), eq(generator));
    }

    @Test
    void orphanRepairFalseDoesNotClearL0SaveCurrentUserOrStartModel() {
        when(history.getLastMessage(APP_ID)).thenReturn(ChatHistory.builder()
                .id(5L).appId(APP_ID).userId(USER_ID).message("旧需求")
                .messageType("user").build());
        when(history.repairOrphanUserTurn(APP_ID, USER_ID,
                "生成过程中遇到系统异常，请稍后重试。"))
                .thenReturn(false);

        assertThrows(RuntimeException.class, () -> service.chatToGenCode(
                APP_ID, "新需求", User.builder().id(USER_ID).build()).blockLast());

        verify(factory, never()).prepareVueColdRebuild(APP_ID);
        verify(facade, never()).generateVueProjectStream(
                anyString(), anyLong(), anyBoolean(), any(), any());
        verify(history, never()).addChatMessageAndReturn(
                APP_ID, "新需求", "user", USER_ID);
        verify(history, never()).addAiMessageAndReturn(
                eq(APP_ID), anyString(), anyString(), any(), eq(USER_ID));
        verify(finalizer, never()).finalizeOnce(any(), any());
        operationManager.acquire(APP_ID,
                AppOperationLeaseManager.AppOperationType.GENERATE,
                "next-after-repair-failure").close();
    }

    @Test
    void cancellationDuringHistoryReadPreventsLaterPreparationSideEffects()
            throws Exception {
        CountDownLatch historyEntered = new CountDownLatch(1);
        CountDownLatch releaseHistory = new CountDownLatch(1);
        when(history.getLastMessage(APP_ID)).thenAnswer(invocation -> {
            historyEntered.countDown();
            assertTrue(releaseHistory.await(1, TimeUnit.SECONDS));
            return null;
        });
        var operation = operationManager.acquire(
                APP_ID, AppOperationLeaseManager.AppOperationType.GENERATE,
                "turn-preparation-cancel");
        var vueLease = new VueBuildSessionManager().open(
                operation, USER_ID, "turn-preparation-cancel");
        VueTurnContext context = new VueTurnContext(
                APP_ID, USER_ID, "turn-preparation-cancel",
                operation, vueLease,
                admissionController.tryAcquire().orElseThrow(),
                new com.lyw.appgeneration.ai.tools.FileToolBudgetGuard()
                        .newSession());

        try (var preparationExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            var preparation = preparationExecutor.submit(() ->
                    ReflectionTestUtils.invokeMethod(
                            service, "prepareVueTurn",
                            APP_ID, "需求", User.builder().id(USER_ID).build(),
                            context));
            assertTrue(historyEntered.await(1, TimeUnit.SECONDS));

            assertEquals(VueTurnContext.PreCommitTerminationDecision.PRE_COMMIT_WON,
                    context.claimPreCommitTermination());
            releaseHistory.countDown();
            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> preparation.get(1, TimeUnit.SECONDS));
            assertTrue(failure.getCause()
                    instanceof java.util.concurrent.CancellationException);

            verify(history, never()).repairOrphanUserTurn(
                    anyLong(), anyLong(), anyString());
            verify(factory, never()).prepareVueColdRebuild(APP_ID);
            verify(facade, never()).prepareVueGenerator(APP_ID);
            verify(history, never()).addChatMessageAndReturn(
                    eq(APP_ID), anyString(), anyString(), eq(USER_ID));
            verify(finalizer, never()).finalizeOnce(any(), any());
            verifyNoInteractions(executor);
        } finally {
            releaseHistory.countDown();
            context.closeResources();
        }
    }

    @Test
    void 模型流同步异常必须生成唯一系统终态并稳定写入记忆() {
        IllegalStateException preparationFailure =
                new IllegalStateException("图片增强失败");
        ToolMessageCollapser collapser = mock(ToolMessageCollapser.class);
        when(history.getLastMessage(APP_ID)).thenReturn(null);
        when(history.addChatMessageAndReturn(APP_ID, "需求", "user", USER_ID))
                .thenReturn(savedUser(101L, "需求"));
        when(history.addAiMessageAndReturn(
                eq(APP_ID), eq(VueTurnFinalizer.SYSTEM_ERROR_MESSAGE),
                eq(systemErrorMemoryProjection()),
                eq(ChatMemoryOutcome.SYSTEM_ERROR), eq(USER_ID)))
                .thenReturn(savedAiMessage(VueTurnFinalizer.SYSTEM_ERROR_MESSAGE));
        when(collapser.finishLastTurn(any(), any(), any()))
                .thenReturn(collapsed());
        when(facade.generateVueProjectStream(
                eq("需求"), eq(APP_ID), eq(true), any(), eq(generator)))
                .thenThrow(preparationFailure);

        try (var coordinator = installRealVuePipeline(collapser)) {
            List<GenerationStreamEvent> events = service.chatToGenCode(
                    APP_ID, "需求", User.builder().id(USER_ID).build())
                    .collectList().block();

            assertEquals(1, events.size());
            var outcome = (GenerationStreamEvent.TurnOutcome) events.getFirst();
            assertEquals(VueTurnOutcome.TurnOutcomeType.SYSTEM_ERROR,
                    outcome.message().getOutcome());
        }

        verify(history, times(1)).addAiMessageAndReturn(
                eq(APP_ID), eq(VueTurnFinalizer.SYSTEM_ERROR_MESSAGE),
                eq(systemErrorMemoryProjection()),
                eq(ChatMemoryOutcome.SYSTEM_ERROR), eq(USER_ID));
        verify(collapser, times(1)).finishLastTurn(any(), any(), any());
        operationManager.acquire(APP_ID,
                AppOperationLeaseManager.AppOperationType.GENERATE,
                "after-system-error-finalization").close();
    }

    @Test
    void 删除参与者注册边界关闭后不得留下孤立用户消息() throws Exception {
        ToolMessageCollapser collapser = mock(ToolMessageCollapser.class);
        when(history.addAiMessageAndReturn(
                eq(APP_ID), eq(VueTurnFinalizer.SYSTEM_ERROR_MESSAGE),
                eq(systemErrorMemoryProjection()),
                eq(ChatMemoryOutcome.SYSTEM_ERROR), eq(USER_ID)))
                .thenReturn(savedAiMessage(VueTurnFinalizer.SYSTEM_ERROR_MESSAGE));
        when(collapser.finishLastTurn(any(), any(), any()))
                .thenReturn(collapsed());
        String turnId = "turn-registration-closed";
        var operation = operationManager.acquire(
                APP_ID, AppOperationLeaseManager.AppOperationType.GENERATE,
                turnId);
        var vueLease = new VueBuildSessionManager().open(
                operation, USER_ID, turnId);
        VueTurnContext context = new VueTurnContext(
                APP_ID, USER_ID, turnId, operation, vueLease,
                admissionController.tryAcquire().orElseThrow(),
                new FileToolBudgetGuard().newSession());
        assertEquals(VueTurnContext.UserCommitResult.COMMITTED,
                context.commitUser(() -> true));
        operation.requestCancellation();
        Object committedTurn = createCommittedTurn(context);

        try (var coordinator = installRealVuePipeline(collapser)) {
            Flux<GenerationStreamEvent> flow = ReflectionTestUtils.invokeMethod(
                    service, "runCommittedVueTurn", committedTurn);
            List<GenerationStreamEvent> events = flow
                    .collectList().block();

            assertEquals(1, events.size());
            var outcome = (GenerationStreamEvent.TurnOutcome) events.getFirst();
            assertEquals(VueTurnOutcome.TurnOutcomeType.SYSTEM_ERROR,
                    outcome.message().getOutcome());
        }

        verify(facade, never()).generateVueProjectStream(
                anyString(), anyLong(), anyBoolean(), any(), any());
        verify(history, times(1)).addAiMessageAndReturn(
                eq(APP_ID), eq(VueTurnFinalizer.SYSTEM_ERROR_MESSAGE),
                eq(systemErrorMemoryProjection()),
                eq(ChatMemoryOutcome.SYSTEM_ERROR), eq(USER_ID));
        verify(collapser, times(1)).finishLastTurn(any(), any(), any());
        operationManager.acquire(APP_ID,
                AppOperationLeaseManager.AppOperationType.GENERATE,
                "after-registration-failure").close();
    }

    @Test
    void 删除在参与者注册后接管不得跳过Handler装配()
            throws Exception {
        ToolMessageCollapser collapser = stableCancellationCollapser();
        AtomicInteger modelSubscriptions = stubNeverEndingVueModel();

        String turnId = "turn-delete-after-registration";
        CommittedTurnFixture fixture = createCommittedTurnFixture(turnId);
        var operation = fixture.operation();
        VueTurnContext context = fixture.context();
        CountDownLatch deleteClosedGate = new CountDownLatch(1);
        CompletableFuture<Void> handlerEntered = new CompletableFuture<>();
        CompletableFuture<Void> releaseHandler = new CompletableFuture<>();
        var cancellationRegistration = operation.registerCancellation(
                deleteClosedGate::countDown);
        Object committedTurn = fixture.committedTurn();
        Future<List<GenerationStreamEvent>> generation = null;
        Future<AppOperationLeaseManager.AppOperationLease> deletion = null;

        try (var coordinator = installBlockingRealVuePipeline(
                    collapser, handlerEntered, releaseHandler);
                var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            Flux<GenerationStreamEvent> flow = ReflectionTestUtils.invokeMethod(
                    service, "runCommittedVueTurn", committedTurn);
            generation = callers.submit(() -> flow.collectList().block());
            handlerEntered.get(1, TimeUnit.SECONDS);
            boolean turnQuiescentWhenParticipantVisible =
                    context.awaitQuiescence(Duration.ZERO);
            boolean appQuiescentWhenParticipantVisible =
                    operation.awaitQuiescence(Duration.ZERO);

            deletion = callers.submit(() ->
                    operationManager.cancelAndAcquireDelete(
                            APP_ID, "delete-after-registration",
                            Duration.ofSeconds(2)));
            Future<AppOperationLeaseManager.AppOperationLease> deletionCall =
                    deletion;
            Future<List<GenerationStreamEvent>> generationCall = generation;
            assertTrue(deleteClosedGate.await(1, TimeUnit.SECONDS),
                    "删除接管必须已经关闭 app 回调门");
            assertFalse(deletionCall.isDone(),
                    "参与者可见后删除必须等待 Handler 装配完成");
            releaseHandler.complete(null);

            try (var deleteLease = assertDoesNotThrow(
                    () -> deletionCall.get(3, TimeUnit.SECONDS),
                    "参与者一旦可见，Handler 装配必须在已持有的回调票据内完成")) {
                List<GenerationStreamEvent> events = generationCall.get(
                        1, TimeUnit.SECONDS);

                assertFalse(turnQuiescentWhenParticipantVisible,
                        "参与者可见时必须已经持有回合内层票据");
                assertFalse(appQuiescentWhenParticipantVisible,
                        "参与者可见时必须已经持有 app 外层票据");
                assertEquals(1, events.size());
                var outcome = (GenerationStreamEvent.TurnOutcome)
                        events.getFirst();
                assertEquals(VueTurnOutcome.TurnOutcomeType.CANCELLED,
                        outcome.message().getOutcome());
                verify(history, times(1)).addAiMessageAndReturn(
                        eq(APP_ID), eq(VueTurnFinalizer.CANCELLED_MESSAGE),
                        eq(cancelledMemoryProjection()),
                        eq(ChatMemoryOutcome.CANCELLED), eq(USER_ID));
                verify(facade, never()).generateVueProjectStream(
                        anyString(), anyLong(), anyBoolean(), any(), any());
                assertEquals(0, modelSubscriptions.get());
                assertEquals(VueTurnContext.TurnStage.FINALIZED,
                        context.turnState().stage());
                assertEquals(1.0, metricsRegistry
                        .get("vue_turn_admissions_total")
                        .tag("result", "released").counter().count());
                assertEquals(AppOperationLeaseManager.AppOperationType.DELETE,
                        deleteLease.operationType());
            }
            operationManager.acquire(
                    APP_ID, AppOperationLeaseManager.AppOperationType.GENERATE,
                    "after-delete-finalized").close();
        } finally {
            releaseHandler.complete(null);
            if (generation != null && !generation.isDone()) {
                generation.cancel(true);
            }
            if (deletion != null && !deletion.isDone()) {
                deletion.cancel(true);
            }
            cancellationRegistration.close();
            context.closeResources();
        }
    }

    @Test
    void 删除不得观察到Handler回调已进入但参与者尚未登记()
            throws Exception {
        ToolMessageCollapser collapser = stableCancellationCollapser();
        stubNeverEndingVueModel();

        CompletableFuture<Void> callbackPrepared = new CompletableFuture<>();
        CompletableFuture<Void> releaseAtomicCommit = new CompletableFuture<>();
        AtomicBoolean boundaryArmed = new AtomicBoolean();
        Runnable atomicCommitBoundary = () -> {
            if (boundaryArmed.get() && callbackPrepared.complete(null)) {
                releaseAtomicCommit.join();
            }
        };
        operationManager = operationManagerWithRegistrationHook(
                atomicCommitBoundary);
        ReflectionTestUtils.setField(
                service, "appOperationLeaseManager", operationManager);

        String turnId = "turn-delete-before-registration";
        CommittedTurnFixture fixture = createCommittedTurnFixture(turnId);
        var operation = fixture.operation();
        VueTurnContext context = fixture.context();
        // 修复前由此独立入口触发边界；修复后同一钩子在 OperationState 原子区触发。
        org.mockito.Mockito.doAnswer(invocation -> {
            atomicCommitBoundary.run();
            return invocation.callRealMethod();
        }).when(context).registerDeleteTakeoverParticipant();

        boundaryArmed.set(true);
        Object committedTurn = fixture.committedTurn();
        Future<List<GenerationStreamEvent>> generation = null;
        Future<AppOperationLeaseManager.AppOperationLease> deletion = null;

        try (var coordinator = installRealVuePipeline(collapser);
                var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            Flux<GenerationStreamEvent> flow = ReflectionTestUtils.invokeMethod(
                    service, "runCommittedVueTurn", committedTurn);
            generation = callers.submit(() -> flow.collectList().block());
            callbackPrepared.get(1, TimeUnit.SECONDS);
            assertFalse(context.awaitQuiescence(Duration.ZERO),
                    "原子登记边界必须已经持有回合内层票据");

            AtomicReference<Thread> deletionThread = new AtomicReference<>();
            deletion = callers.submit(() -> {
                deletionThread.set(Thread.currentThread());
                return operationManager.cancelAndAcquireDelete(
                        APP_ID, "delete-before-registration",
                        Duration.ofSeconds(2));
            });
            Future<AppOperationLeaseManager.AppOperationLease> deletionCall =
                    deletion;
            Future<List<GenerationStreamEvent>> generationCall = generation;
            awaitBlockedOrCompleted(deletionThread, deletionCall);
            assertFalse(deletionCall.isDone(),
                    "删除不得观察到 callback 已进入但参与者为空并快速失败");

            releaseAtomicCommit.complete(null);

            try (var deleteLease = assertDoesNotThrow(
                    () -> deletionCall.get(3, TimeUnit.SECONDS),
                    "删除接管必须取得删除租约，不能抛出裸 IllegalStateException")) {
                generationCall.get(1, TimeUnit.SECONDS);
                assertEquals(AppOperationLeaseManager.AppOperationType.DELETE,
                        deleteLease.operationType());
            }
        } finally {
            releaseAtomicCommit.complete(null);
            if (generation != null && !generation.isDone()) {
                generation.cancel(true);
            }
            if (deletion != null && !deletion.isDone()) {
                deletion.cancel(true);
            }
            context.closeResources();
        }
    }

    @Test
    void vueHandlerIsInstalledAfterUserCommitAndBeforeModelSubscription() {
        when(history.getLastMessage(APP_ID)).thenReturn(null);
        when(history.addChatMessageAndReturn(APP_ID, "需求", "user", USER_ID))
                .thenReturn(savedUser(101L, "需求"));
        when(facade.generateVueProjectStream(
                eq("需求"), eq(APP_ID), eq(true), any(), eq(generator)))
                .thenReturn(Flux.empty());
        when(executor.doExecuteVue(any(), any())).thenAnswer(invocation ->
                invocation.<Flux<String>>getArgument(0)
                        .map(GenerationStreamEvent::content));

        service.chatToGenCode(APP_ID, "需求",
                User.builder().id(USER_ID).build()).blockLast();

        InOrder order = inOrder(history, facade, executor);
        order.verify(history).getLastMessage(APP_ID);
        order.verify(facade).prepareVueGenerator(APP_ID);
        order.verify(history).addChatMessageAndReturn(APP_ID, "需求", "user", USER_ID);
        order.verify(executor).doExecuteVue(any(), any());
        order.verify(facade).generateVueProjectStream(
                eq("需求"), eq(APP_ID), eq(true), any(), eq(generator));
    }

    private VueTurnCancellationCoordinator installRealVuePipeline(
            ToolMessageCollapser collapser) {
        return installRealVuePipeline(collapser, Runnable::run);
    }

    private VueTurnCancellationCoordinator installRealVuePipeline(
            ToolMessageCollapser collapser, Executor cancellationExecutor) {
        var repairMetrics = new VueBuildRepairMetricsCollector(metricsRegistry);
        VueTurnFinalizer realFinalizer = new VueTurnFinalizer(
                history, collapser, com.lyw.appgeneration.service.MemorySummaryTestSupport.terminalPersistenceStub(),
                mock(UserMemoryService.class), factory,
                new AppDataLifecycleFence(), repairMetrics,
                new FileToolBudgetGuard());
        VueTurnCancellationCoordinator coordinator =
                new VueTurnCancellationCoordinator(
                        realFinalizer, cancellationExecutor, repairMetrics);
        var jsonHandler = new com.lyw.appgeneration.core.handler
                .JsonMessageStreamHandler(
                mock(ToolManager.class), realFinalizer, coordinator);
        var realExecutor = new StreamHandlerExecutor();
        ReflectionTestUtils.setField(
                realExecutor, "jsonMessageStreamHandler", jsonHandler);
        ReflectionTestUtils.setField(service, "vueTurnFinalizer", realFinalizer);
        ReflectionTestUtils.setField(
                service, "vueTurnCancellationCoordinator", coordinator);
        ReflectionTestUtils.setField(service, "streamHandlerExecutor", realExecutor);
        return coordinator;
    }

    private VueTurnCancellationCoordinator installBlockingRealVuePipeline(
            ToolMessageCollapser collapser,
            CompletableFuture<Void> handlerEntered,
            CompletableFuture<Void> releaseHandler) {
        VueTurnCancellationCoordinator coordinator =
                installRealVuePipeline(collapser);
        StreamHandlerExecutor realExecutor =
                (StreamHandlerExecutor) ReflectionTestUtils.getField(
                        service, "streamHandlerExecutor");
        StreamHandlerExecutor blockingExecutor =
                mock(StreamHandlerExecutor.class);
        when(blockingExecutor.doExecuteVue(any(), any()))
                .thenAnswer(invocation -> {
                    handlerEntered.complete(null);
                    releaseHandler.join();
                    return realExecutor.doExecuteVue(
                            invocation.<Flux<String>>getArgument(0),
                            invocation.<VueTurnContext>getArgument(1));
                });
        ReflectionTestUtils.setField(
                service, "streamHandlerExecutor", blockingExecutor);
        return coordinator;
    }

    private ToolMessageCollapser.CollapseResult collapsed() {
        return new ToolMessageCollapser.CollapseResult(
                ToolMessageCollapser.CollapseStatus.COLLAPSED, List.of());
    }

    private ChatHistory savedAiMessage(String message) {
        return ChatHistory.builder()
                .id(11L)
                .appId(APP_ID)
                .userId(USER_ID)
                .messageType("ai")
                .message(message)
                .build();
    }

    private Object createCommittedTurn(VueTurnContext context) throws Exception {
        Class<?> committedType = java.util.Arrays.stream(
                        AppServiceImpl.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("CommittedVueTurn"))
                .findFirst()
                .orElseThrow();
        var constructor = committedType.getDeclaredConstructor(
                VueTurnContext.class, AiCodeGeneratorService.class,
                String.class, boolean.class, Throwable.class);
        constructor.setAccessible(true);
        return constructor.newInstance(context, generator, "需求", true, null);
    }

    private CommittedTurnFixture createCommittedTurnFixture(String turnId)
            throws Exception {
        var operation = operationManager.acquire(
                APP_ID, AppOperationLeaseManager.AppOperationType.GENERATE,
                turnId);
        var vueLease = new VueBuildSessionManager().open(
                operation, USER_ID, turnId);
        VueTurnContext realContext = new VueTurnContext(
                APP_ID, USER_ID, turnId, operation, vueLease,
                admissionController.tryAcquire().orElseThrow(),
                new FileToolBudgetGuard().newSession());
        VueTurnContext context = org.mockito.Mockito.mock(
                VueTurnContext.class,
                org.mockito.Mockito.withSettings()
                        .spiedInstance(realContext)
                        .defaultAnswer(org.mockito.Mockito.CALLS_REAL_METHODS)
                        .mockMaker(org.mockito.MockMakers.INLINE));
        assertEquals(VueTurnContext.UserCommitResult.COMMITTED,
                context.commitUser(() -> true));
        return new CommittedTurnFixture(
                operation, context, createCommittedTurn(context));
    }

    private ToolMessageCollapser stableCancellationCollapser() {
        ToolMessageCollapser collapser = mock(ToolMessageCollapser.class);
        when(history.addAiMessageAndReturn(
                eq(APP_ID), eq(VueTurnFinalizer.CANCELLED_MESSAGE),
                eq(cancelledMemoryProjection()),
                eq(ChatMemoryOutcome.CANCELLED), eq(USER_ID)))
                .thenReturn(savedAiMessage(VueTurnFinalizer.CANCELLED_MESSAGE));
        when(collapser.finishLastTurn(any(), any(), any()))
                .thenReturn(collapsed());
        return collapser;
    }

    private String cancelledMemoryProjection() {
        return VueTurnMemoryProjection.project(
                List.of(), VueTurnOutcome.TurnOutcomeType.CANCELLED);
    }

    private String systemErrorMemoryProjection() {
        return VueTurnMemoryProjection.project(
                List.of(), VueTurnOutcome.TurnOutcomeType.SYSTEM_ERROR);
    }

    private String protocolErrorMemoryProjection() {
        return VueTurnMemoryProjection.project(
                List.of(), VueTurnOutcome.TurnOutcomeType.PROTOCOL_ERROR);
    }

    private AtomicInteger stubNeverEndingVueModel() {
        AtomicInteger modelSubscriptions = new AtomicInteger();
        when(facade.generateVueProjectStream(
                eq("需求"), eq(APP_ID), eq(true), any(), eq(generator)))
                .thenReturn(Flux.defer(() -> {
                    modelSubscriptions.incrementAndGet();
                    return Flux.never();
                }));
        return modelSubscriptions;
    }

    private AppOperationLeaseManager operationManagerWithRegistrationHook(
            Runnable hook) throws Exception {
        var constructor = AppOperationLeaseManager.class
                .getDeclaredConstructor(Runnable.class);
        constructor.setAccessible(true);
        return constructor.newInstance(hook);
    }

    private void awaitBlockedOrCompleted(
            AtomicReference<Thread> threadReference, Future<?> future) {
        long deadlineNanos = System.nanoTime()
                + Duration.ofSeconds(1).toNanos();
        Thread thread;
        while (!future.isDone()
                && ((thread = threadReference.get()) == null
                || thread.getState() != Thread.State.BLOCKED)
                && System.nanoTime() < deadlineNanos) {
            Thread.onSpinWait();
        }
        thread = threadReference.get();
        assertTrue(future.isDone()
                        || thread != null
                        && thread.getState() == Thread.State.BLOCKED,
                "删除线程未进入原子提交竞争边界");
    }

    private record CommittedTurnFixture(
            AppOperationLeaseManager.AppOperationLease operation,
            VueTurnContext context,
            Object committedTurn) {
    }

}
