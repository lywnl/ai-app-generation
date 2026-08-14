package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.AiGeneratorServiceFactory;
import com.lyw.appgeneration.ai.AiCodeGeneratorService;
import com.lyw.appgeneration.ai.image.ImageCollectionService;
import com.lyw.appgeneration.ai.memory.ToolMessageCollapser;
import com.lyw.appgeneration.ai.tools.FileToolBudgetGuard;
import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.core.AiCodeGeneratorFacade;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import com.lyw.appgeneration.core.concurrency.VueTurnAdmissionController;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.core.handler.StreamHandlerExecutor;
import com.lyw.appgeneration.core.handler.VueTurnContext;
import com.lyw.appgeneration.core.handler.GenerationStreamEvent;
import com.lyw.appgeneration.core.handler.VueTurnCancellationCoordinator;
import com.lyw.appgeneration.core.handler.VueTurnFinalizer;
import com.lyw.appgeneration.core.handler.VueTurnOutcome;
import com.lyw.appgeneration.exception.GenerationPreflightException;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.monitor.AppLifecycleMetricsCollector;
import com.lyw.appgeneration.monitor.ThrowingMeterRegistry;
import com.lyw.appgeneration.monitor.VueBuildRepairMetricsCollector;
import com.lyw.appgeneration.manger.ToolManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.UserMemoryService;
import com.lyw.appgeneration.service.rag.RagPromptAssembler;
import com.lyw.appgeneration.service.rag.RagRetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.Disposable;
import reactor.core.publisher.Hooks;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;

class AppServiceImplVueTurnTest {

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
    private AppOperationLeaseManager operationManager;
    private AppServiceImpl service;
    private SimpleMeterRegistry metricsRegistry;
    private VueTurnAdmissionController admissionController;

    @BeforeEach
    void setUp() {
        operationManager = new AppOperationLeaseManager();
        metricsRegistry = new SimpleMeterRegistry();
        admissionController = new VueTurnAdmissionController(
                new VueBuildRepairMetricsCollector(metricsRegistry));
        service = new AppServiceImpl();
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
        CompletableFuture<Void> releaseHandler = new CompletableFuture<>();
        AtomicInteger modelStarts = new AtomicInteger();
        AtomicReference<VueTurnContext> capturedContext = new AtomicReference<>();
        AtomicReference<Throwable> droppedError = new AtomicReference<>();
        ToolMessageCollapser collapser = mock(ToolMessageCollapser.class);
        MemorySummaryService summary = mock(MemorySummaryService.class);
        UserMemoryService preference = mock(UserMemoryService.class);
        var repairMetrics = new VueBuildRepairMetricsCollector(metricsRegistry);
        VueTurnFinalizer realFinalizer = new VueTurnFinalizer(
                history, collapser, summary, preference, factory,
                new AppDataLifecycleFence(), repairMetrics,
                new FileToolBudgetGuard());
        when(history.getLastMessage(APP_ID)).thenReturn(null);
        when(history.addChatMessage(APP_ID, "需求", "user", USER_ID))
                .thenReturn(true);
        when(history.addChatMessage(
                APP_ID, VueTurnFinalizer.CANCELLED_MESSAGE, "ai", USER_ID))
                .thenReturn(true);
        when(collapser.collapseLastTurn(
                APP_ID, VueTurnFinalizer.CANCELLED_MESSAGE))
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

        try (ExecutorService cancellationExecutor =
                Executors.newVirtualThreadPerTaskExecutor();
             var realCoordinator = new VueTurnCancellationCoordinator(
                     realFinalizer, cancellationExecutor, repairMetrics)) {
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

            subscription.dispose();
            assertThrows(AppOperationLeaseManager.ActiveAppOperationException.class,
                    () -> operationManager.acquire(
                            APP_ID,
                            AppOperationLeaseManager.AppOperationType.GENERATE,
                            "before-cancel-finalized"));
            releaseHandler.complete(null);

            verify(history, timeout(2_000).times(1)).addChatMessage(
                    APP_ID, VueTurnFinalizer.CANCELLED_MESSAGE, "ai", USER_ID);
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
            Hooks.resetOnErrorDropped();
            releaseHandler.complete(null);
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
        when(history.addChatMessage(APP_ID, "需求", "user", USER_ID))
                .thenReturn(true);
        when(executor.doExecuteVue(any(), any())).thenAnswer(invocation ->
                invocation.<Flux<String>>getArgument(0)
                        .map(GenerationStreamEvent::content));

        Flux<GenerationStreamEvent> result = service.chatToGenCode(
                APP_ID, "需求", User.builder().id(USER_ID).build());

        assertEquals(0, starts.get(), "返回冷 Flux 时模型尚未启动");
        assertEquals("raw", ((GenerationStreamEvent.Content)
                result.blockFirst()).text());
        assertEquals(1, starts.get());
        assertEquals(1.0, metricsRegistry.get("app_operations_total")
                .tags("operation", "generate", "result", "acquired",
                        "conflict_with", "none").counter().count());
        InOrder order = inOrder(history, facade, executor);
        order.verify(facade).prepareVueGenerator(APP_ID);
        order.verify(history).addChatMessage(APP_ID, "需求", "user", USER_ID);
        order.verify(executor).doExecuteVue(any(), any());
        order.verify(facade).generateVueProjectStream(
                eq("需求"), eq(APP_ID), eq(true), any(), eq(generator));
    }

    @Test
    void counterIncrementFailureDoesNotChangeUserCommitModelStartOrLeaseRelease() {
        ThrowingMeterRegistry registry = new ThrowingMeterRegistry(
                ThrowingMeterRegistry.FailurePoint.COUNTER_INCREMENT);
        ReflectionTestUtils.setField(service, "appLifecycleMetricsCollector",
                new AppLifecycleMetricsCollector(registry));
        AtomicInteger modelStarts = new AtomicInteger();
        when(history.getLastMessage(APP_ID)).thenReturn(null);
        when(history.addChatMessage(APP_ID, "需求", "user", USER_ID))
                .thenReturn(true);
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
        assertEquals("raw", ((GenerationStreamEvent.Content)
                events.getFirst()).text());
        assertEquals(1, modelStarts.get());
        verify(history, times(1)).addChatMessage(
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
        when(history.addChatMessage(APP_ID, "需求", "user", USER_ID))
                .thenReturn(false);
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
        when(history.addChatMessage(APP_ID, "新需求", "user", USER_ID))
                .thenReturn(true);
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
        order.verify(history).addChatMessage(APP_ID, "新需求", "user", USER_ID);
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
        verify(history, never()).addChatMessage(
                APP_ID, "新需求", "user", USER_ID);
        verify(history, never()).addChatMessage(
                eq(APP_ID), anyString(), eq("ai"), eq(USER_ID));
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
            verify(history, never()).addChatMessage(
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
        when(history.addChatMessage(APP_ID, "需求", "user", USER_ID))
                .thenReturn(true);
        when(history.addChatMessage(
                APP_ID, VueTurnFinalizer.SYSTEM_ERROR_MESSAGE, "ai", USER_ID))
                .thenReturn(true);
        when(collapser.collapseLastTurn(
                APP_ID, VueTurnFinalizer.SYSTEM_ERROR_MESSAGE))
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

        verify(history, times(1)).addChatMessage(
                APP_ID, VueTurnFinalizer.SYSTEM_ERROR_MESSAGE, "ai", USER_ID);
        verify(collapser, times(1)).collapseLastTurn(
                APP_ID, VueTurnFinalizer.SYSTEM_ERROR_MESSAGE);
        operationManager.acquire(APP_ID,
                AppOperationLeaseManager.AppOperationType.GENERATE,
                "after-system-error-finalization").close();
    }

    @Test
    void 删除参与者注册边界关闭后不得留下孤立用户消息() throws Exception {
        ToolMessageCollapser collapser = mock(ToolMessageCollapser.class);
        when(history.addChatMessage(
                APP_ID, VueTurnFinalizer.SYSTEM_ERROR_MESSAGE, "ai", USER_ID))
                .thenReturn(true);
        when(collapser.collapseLastTurn(
                APP_ID, VueTurnFinalizer.SYSTEM_ERROR_MESSAGE))
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
        verify(history, times(1)).addChatMessage(
                APP_ID, VueTurnFinalizer.SYSTEM_ERROR_MESSAGE, "ai", USER_ID);
        verify(collapser, times(1)).collapseLastTurn(
                APP_ID, VueTurnFinalizer.SYSTEM_ERROR_MESSAGE);
        operationManager.acquire(APP_ID,
                AppOperationLeaseManager.AppOperationType.GENERATE,
                "after-registration-failure").close();
    }

    @Test
    void vueHandlerIsInstalledAfterUserCommitAndBeforeModelSubscription() {
        when(history.getLastMessage(APP_ID)).thenReturn(null);
        when(history.addChatMessage(APP_ID, "需求", "user", USER_ID))
                .thenReturn(true);
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
        order.verify(history).addChatMessage(APP_ID, "需求", "user", USER_ID);
        order.verify(executor).doExecuteVue(any(), any());
        order.verify(facade).generateVueProjectStream(
                eq("需求"), eq(APP_ID), eq(true), any(), eq(generator));
    }

    private VueTurnCancellationCoordinator installRealVuePipeline(
            ToolMessageCollapser collapser) {
        var repairMetrics = new VueBuildRepairMetricsCollector(metricsRegistry);
        VueTurnFinalizer realFinalizer = new VueTurnFinalizer(
                history, collapser, mock(MemorySummaryService.class),
                mock(UserMemoryService.class), factory,
                new AppDataLifecycleFence(), repairMetrics,
                new FileToolBudgetGuard());
        VueTurnCancellationCoordinator coordinator =
                new VueTurnCancellationCoordinator(
                        realFinalizer, Runnable::run, repairMetrics);
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

    private ToolMessageCollapser.CollapseResult collapsed() {
        return new ToolMessageCollapser.CollapseResult(
                ToolMessageCollapser.CollapseStatus.COLLAPSED, List.of());
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

}
