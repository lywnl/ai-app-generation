package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.ai.AiGeneratorServiceFactory;
import com.lyw.appgeneration.ai.memory.ToolMessageCollapser;
import com.lyw.appgeneration.ai.tools.FileToolBudgetGuard;

import com.lyw.appgeneration.core.builder.VueBuildPhase;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import com.lyw.appgeneration.core.concurrency.VueTurnAdmissionController;
import com.lyw.appgeneration.monitor.VueBuildRepairMetricsCollector;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.UserMemoryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Future;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class VueTurnCancellationCoordinatorTest {

    @Test
    void cancellationClosesGateCancelsModelWaitsAndFinalizesOnce() throws Exception {
        SimpleMeterRegistry metricsRegistry = new SimpleMeterRegistry();
        VueBuildRepairMetricsCollector metrics =
                new VueBuildRepairMetricsCollector(metricsRegistry);
        AppOperationLeaseManager manager = new AppOperationLeaseManager();
        var operation = manager.acquire(7L,
                AppOperationLeaseManager.AppOperationType.GENERATE, "turn-cancel");
        var lease = new VueBuildSessionManager().open(
                operation, 9L, "turn-cancel");
        VueTurnContext context = new VueTurnContext(
                7L, 9L, "turn-cancel", operation, lease, budgetSession());
        context.commitUser(() -> true);
        AtomicInteger modelCancellations = new AtomicInteger();
        context.registerModelCancellation(modelCancellations::incrementAndGet);
        VueTurnFinalizer finalizer = mock(VueTurnFinalizer.class);
        CountDownLatch finalized = new CountDownLatch(1);
        when(finalizer.finalizeOnce(eq(context), any())).thenAnswer(invocation -> {
            VueTurnOutcome outcome = invocation.getArgument(1);
            assertEquals(VueTurnOutcome.TurnOutcomeType.CANCELLED, outcome.outcome());
            assertEquals("已生成部分\n\n本次生成已取消。", outcome.canonicalAiText());
            finalized.countDown();
            context.closeResources();
            return new VueTurnFinalizer.FinalizationResult(outcome, true);
        });

        try (var executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().factory());
             var coordinator = new VueTurnCancellationCoordinator(
                     finalizer, executor,
                     VueTurnCancellationCoordinator.QUIESCENCE_TIMEOUT, metrics)) {
            assertTrue(coordinator.requestCancellation(context, () -> "已生成部分"));
            assertFalse(coordinator.requestCancellation(context, () -> "重复"));
            assertTrue(finalized.await(2, TimeUnit.SECONDS));
        }

        assertEquals(1, modelCancellations.get());
        assertFalse(context.tryRunCallback(() -> { }));
        verify(finalizer).finalizeOnce(eq(context), any());
        assertEquals(1.0, cancellationCount(metricsRegistry,
                "subscriber_cancelled", "requested"));
        assertEquals(1.0, cancellationCount(metricsRegistry,
                "subscriber_cancelled", "completed"));
        manager.acquire(7L, AppOperationLeaseManager.AppOperationType.GENERATE,
                "turn-next").close();
    }

    @Test
    void blockedCallbackKeepsLeaseUntilItReallyBecomesQuiescent() throws Exception {
        AppOperationLeaseManager manager = new AppOperationLeaseManager();
        var operation = manager.acquire(7L,
                AppOperationLeaseManager.AppOperationType.GENERATE, "turn-blocked");
        var lease = new VueBuildSessionManager().open(operation, 9L, "turn-blocked");
        VueTurnContext context = new VueTurnContext(
                7L, 9L, "turn-blocked", operation, lease, budgetSession());
        context.commitUser(() -> true);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        Thread callback = Thread.startVirtualThread(() -> context.tryRunCallback(() -> {
            callbackEntered.countDown();
            try {
                releaseCallback.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }));
        assertTrue(callbackEntered.await(1, TimeUnit.SECONDS));
        VueTurnFinalizer finalizer = mock(VueTurnFinalizer.class);
        CountDownLatch finalized = new CountDownLatch(1);
        when(finalizer.finalizeOnce(eq(context), any())).thenAnswer(invocation -> {
            VueTurnOutcome outcome = invocation.getArgument(1);
            context.closeResources();
            finalized.countDown();
            return new VueTurnFinalizer.FinalizationResult(outcome, true);
        });

        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var coordinator = new VueTurnCancellationCoordinator(
                     finalizer, executor, Duration.ofMillis(20))) {
            assertTrue(coordinator.requestCancellation(context, () -> "部分"));
            assertFalse(finalized.await(80, TimeUnit.MILLISECONDS));
            assertThrows(RuntimeException.class, () -> manager.acquire(
                    7L, AppOperationLeaseManager.AppOperationType.GENERATE, "too-early"));
            releaseCallback.countDown();
            assertTrue(finalized.await(2, TimeUnit.SECONDS));
        }
        callback.join();

        verify(finalizer).finalizeOnce(eq(context), any());
        manager.acquire(7L, AppOperationLeaseManager.AppOperationType.GENERATE,
                "after-quiescence").close();
    }

    @Test
    void rejectedExecutionKeepsClaimedTurnAndLeasePending() {
        SimpleMeterRegistry metricsRegistry = new SimpleMeterRegistry();
        VueBuildRepairMetricsCollector metrics =
                new VueBuildRepairMetricsCollector(metricsRegistry);
        AppOperationLeaseManager manager = new AppOperationLeaseManager();
        var operation = manager.acquire(7L,
                AppOperationLeaseManager.AppOperationType.GENERATE, "turn-rejected");
        var lease = new VueBuildSessionManager().open(operation, 9L, "turn-rejected");
        var admission = new VueTurnAdmissionController(metrics);
        var admissionPermit = admission.tryAcquire().orElseThrow();
        VueTurnContext context = new VueTurnContext(
                7L, 9L, "turn-rejected", operation, lease,
                admissionPermit, budgetSession());
        context.commitUser(() -> true);
        VueTurnFinalizer finalizer = mock(VueTurnFinalizer.class);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        Thread callback = Thread.startVirtualThread(() -> context.tryRunCallback(() -> {
            callbackEntered.countDown();
            try {
                releaseCallback.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }));
        try {
            assertTrue(callbackEntered.await(1, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            throw new AssertionError(exception);
        }
        RejectedExecutionException initialRejection =
                new RejectedExecutionException("initial schedule rejected");
        RejectedExecutionException drainRejection =
                new RejectedExecutionException("drain schedule rejected");
        AtomicInteger submissions = new AtomicInteger();
        java.util.concurrent.Executor rejecting = task -> {
            int submission = submissions.incrementAndGet();
            if (submission == 1) {
                throw initialRejection;
            }
            if (submission == 2) {
                throw drainRejection;
            }
            throw new AssertionError("拒绝后不得递归提交更多资源排空任务");
        };

        try (var coordinator = new VueTurnCancellationCoordinator(
                finalizer, rejecting, Duration.ofMillis(20), metrics)) {
            assertEquals(initialRejection, assertThrows(
                    RejectedExecutionException.class, () ->
                            coordinator.requestCancellation(context, () -> "部分")));
            assertEquals(1, coordinator.pendingCount());
            CompletionException finalizationFailure = assertThrows(
                    CompletionException.class, context::awaitFinalization);
            assertEquals(initialRejection, finalizationFailure.getCause());
            assertEquals(2, submissions.get(),
                    "首次取消和一次资源排空都被拒绝后必须停止重试");
            assertEquals(1, initialRejection.getSuppressed().length);
            assertEquals(drainRejection, initialRejection.getSuppressed()[0]);
            assertEquals(1.0, cancellationCount(metricsRegistry,
                    "subscriber_cancelled", "failed"));
        }

        verify(finalizer, org.mockito.Mockito.never()).finalizeOnce(any(), any());
        assertThrows(RuntimeException.class, () -> manager.acquire(
                7L, AppOperationLeaseManager.AppOperationType.GENERATE, "must-stay-blocked"));
        releaseCallback.countDown();
        try {
            callback.join();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        context.closeResources();
        assertEquals(1.0, metricsRegistry
                .get("vue_turn_admissions_total")
                .tag("result", "released").counter().count());
        context.closeResources();
        assertEquals(1.0, metricsRegistry
                .get("vue_turn_admissions_total")
                .tag("result", "released").counter().count());
    }

    @Test
    void rejectedCancellationTaskFailsResultThenDrainsResourcesWhenExecutorRecovers()
            throws Exception {
        SimpleMeterRegistry metricsRegistry = new SimpleMeterRegistry();
        VueBuildRepairMetricsCollector metrics =
                new VueBuildRepairMetricsCollector(metricsRegistry);
        AppOperationLeaseManager manager = new AppOperationLeaseManager();
        var operation = manager.acquire(
                7L, AppOperationLeaseManager.AppOperationType.GENERATE,
                "turn-first-schedule-rejected");
        var lease = new VueBuildSessionManager().open(
                operation, 9L, "turn-first-schedule-rejected");
        var admission = new VueTurnAdmissionController(metrics);
        var admissionPermit = admission.tryAcquire().orElseThrow();
        VueTurnContext context = new VueTurnContext(
                7L, 9L, "turn-first-schedule-rejected",
                operation, lease, admissionPermit, budgetSession());
        context.commitUser(() -> true);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        Thread callback = Thread.startVirtualThread(() ->
                context.tryRunCallback(() -> {
                    callbackEntered.countDown();
                    await(releaseCallback);
                }));
        assertTrue(callbackEntered.await(1, TimeUnit.SECONDS));
        RejectedExecutionException initialRejection =
                new RejectedExecutionException("initial schedule rejected");
        LinkedBlockingQueue<Runnable> recoveryTasks =
                new LinkedBlockingQueue<>();
        AtomicInteger submissions = new AtomicInteger();
        java.util.concurrent.Executor recoveringExecutor = task -> {
            if (submissions.incrementAndGet() == 1) {
                throw initialRejection;
            }
            recoveryTasks.add(task);
        };
        VueTurnFinalizer finalizer = mock(VueTurnFinalizer.class);

        try (var coordinator = new VueTurnCancellationCoordinator(
                finalizer, recoveringExecutor, Duration.ofSeconds(10),
                metrics)) {
            assertEquals(initialRejection, assertThrows(
                    RejectedExecutionException.class,
                    () -> coordinator.requestCancellation(context, () -> "部分")));
            CompletionException finalizationFailure = assertThrows(
                    CompletionException.class, context::awaitFinalization);
            assertEquals(initialRejection, finalizationFailure.getCause());
            assertEquals(1, coordinator.pendingCount());

            Runnable drainTask = recoveryTasks.poll(1, TimeUnit.SECONDS);
            assertNotNull(drainTask,
                    "首次取消任务被拒绝后必须尝试一次受管资源排空");
            Thread drainThread = Thread.ofVirtual()
                    .name("test-vue-rejected-cancel-drain")
                    .start(drainTask);
            releaseCallback.countDown();
            callback.join();
            drainThread.join(Duration.ofSeconds(1));

            assertFalse(drainThread.isAlive());
            assertEquals(0, coordinator.pendingCount());
            manager.acquire(
                    7L, AppOperationLeaseManager.AppOperationType.GENERATE,
                    "after-rejected-cancel-drain").close();
            assertEquals(1.0, cancellationCount(metricsRegistry,
                    "subscriber_cancelled", "failed"));
            assertEquals(1.0, metricsRegistry
                    .get("vue_turn_admissions_total")
                    .tag("result", "released").counter().count());
        } finally {
            releaseCallback.countDown();
            callback.join();
            context.closeResources();
        }

        verify(finalizer, org.mockito.Mockito.never())
                .finalizeOnce(any(), any());
    }

    @Test
    void rejectedExecutionFinalizesSynchronouslyOnlyWhenAlreadyQuiescent() {
        AppOperationLeaseManager manager = new AppOperationLeaseManager();
        var operation = manager.acquire(7L,
                AppOperationLeaseManager.AppOperationType.GENERATE, "turn-quiet");
        var lease = new VueBuildSessionManager().open(operation, 9L, "turn-quiet");
        VueTurnContext context = new VueTurnContext(
                7L, 9L, "turn-quiet", operation, lease, budgetSession());
        context.commitUser(() -> true);
        VueTurnFinalizer finalizer = mock(VueTurnFinalizer.class);
        when(finalizer.finalizeOnce(eq(context), any())).thenAnswer(invocation -> {
            VueTurnOutcome outcome = invocation.getArgument(1);
            context.closeResources();
            return new VueTurnFinalizer.FinalizationResult(outcome, true);
        });
        java.util.concurrent.Executor rejecting = task -> {
            throw new RejectedExecutionException("executor closed");
        };

        try (var coordinator = new VueTurnCancellationCoordinator(
                finalizer, rejecting, Duration.ofMillis(20))) {
            assertTrue(coordinator.requestCancellation(context, () -> "部分"));
            assertEquals(0, coordinator.pendingCount());
        }

        verify(finalizer).finalizeOnce(eq(context), any());
        manager.acquire(7L, AppOperationLeaseManager.AppOperationType.GENERATE,
                "after-safe-fallback").close();
    }

    @Test
    void interruptedCancellationFailsResultThenDrainsResourcesAfterQuiescence()
            throws Exception {
        SimpleMeterRegistry metricsRegistry = new SimpleMeterRegistry();
        VueBuildRepairMetricsCollector metrics =
                new VueBuildRepairMetricsCollector(metricsRegistry);
        AppOperationLeaseManager manager = new AppOperationLeaseManager();
        var operation = manager.acquire(
                7L, AppOperationLeaseManager.AppOperationType.GENERATE,
                "turn-interrupted");
        var lease = new VueBuildSessionManager().open(
                operation, 9L, "turn-interrupted");
        var admission = new VueTurnAdmissionController(metrics);
        var admissionPermit = admission.tryAcquire().orElseThrow();
        VueTurnContext context = new VueTurnContext(
                7L, 9L, "turn-interrupted", operation, lease,
                admissionPermit, budgetSession());
        context.commitUser(() -> true);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        Thread callback = Thread.startVirtualThread(() ->
                context.tryRunCallback(() -> {
                    callbackEntered.countDown();
                    try {
                        if (!releaseCallback.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("等待中断测试清理屏障超时");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("中断测试清理屏障被中断", exception);
                    }
                }));
        assertTrue(callbackEntered.await(1, TimeUnit.SECONDS));
        LinkedBlockingQueue<Runnable> backgroundTasks =
                new LinkedBlockingQueue<>();
        java.util.concurrent.Executor controlledExecutor =
                backgroundTasks::add;
        VueTurnFinalizer finalizer = mock(VueTurnFinalizer.class);
        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<Throwable> observedFailure = new AtomicReference<>();

        try (AutoCloseable observer = context.onFinalized(
                ignored -> { }, failure -> {
                    observedFailure.set(failure);
                    failed.countDown();
                });
             var coordinator = new VueTurnCancellationCoordinator(
                     finalizer, controlledExecutor, Duration.ofSeconds(10),
                     metrics)) {
            assertTrue(coordinator.requestCancellation(context, () -> "部分"));
            Runnable cancellationTask = backgroundTasks.poll(
                    1, TimeUnit.SECONDS);
            assertNotNull(cancellationTask);
            Thread cancellationThread = Thread.ofVirtual()
                    .name("test-vue-cancel-interrupted")
                    .start(cancellationTask);
            cancellationThread.interrupt();

            assertTrue(failed.await(1, TimeUnit.SECONDS),
                    "后台线程中断后必须失败共享终态，不能永久等待");
            assertEquals("Vue 取消后台任务在静默前被中断",
                    observedFailure.get().getMessage());
            assertEquals(VueTurnContext.TurnStage.FINALIZED,
                    context.turnState().stage());
            assertEquals(1, coordinator.pendingCount(),
                    "未静默回合必须继续保留 pending 与租约");
            assertThrows(AppOperationLeaseManager.ActiveAppOperationException.class,
                    () -> manager.acquire(
                            7L,
                            AppOperationLeaseManager.AppOperationType.GENERATE,
                            "must-remain-blocked-after-interrupt"));
            assertEquals(1.0, cancellationCount(metricsRegistry,
                    "subscriber_cancelled", "requested"));
            assertEquals(1.0, cancellationCount(metricsRegistry,
                    "subscriber_cancelled", "failed"));
            Runnable drainTask = backgroundTasks.poll(1, TimeUnit.SECONDS);
            assertNotNull(drainTask,
                    "结果失败后必须调度独立资源排空任务");
            Thread drainThread = Thread.ofVirtual()
                    .name("test-vue-cancel-drain")
                    .start(drainTask);

            releaseCallback.countDown();
            callback.join();
            drainThread.join(Duration.ofSeconds(1));

            assertFalse(drainThread.isAlive(), "资源排空任务必须在静默后结束");
            assertEquals(0, coordinator.pendingCount());
            manager.acquire(
                    7L, AppOperationLeaseManager.AppOperationType.GENERATE,
                    "after-interrupted-drain").close();
            assertEquals(1.0, metricsRegistry
                    .get("vue_turn_admissions_total")
                    .tag("result", "released").counter().count());
            context.closeResources();
            assertEquals(1.0, metricsRegistry
                    .get("vue_turn_admissions_total")
                    .tag("result", "released").counter().count(),
                    "排空后的重复关闭不得重复释放准入许可");
        } finally {
            releaseCallback.countDown();
            callback.join();
            context.closeResources();
        }

        verify(finalizer, org.mockito.Mockito.never())
                .finalizeOnce(any(), any());
    }

    @Test
    void canonicalSupplierFailureFailsSharedFinalizationAndReleasesQuietLease()
            throws Exception {
        AppOperationLeaseManager manager = new AppOperationLeaseManager();
        var operation = manager.acquire(
                7L, AppOperationLeaseManager.AppOperationType.GENERATE,
                "turn-prefix-failure");
        var lease = new VueBuildSessionManager().open(
                operation, 9L, "turn-prefix-failure");
        VueTurnContext context = new VueTurnContext(
                7L, 9L, "turn-prefix-failure", operation, lease,
                budgetSession());
        context.commitUser(() -> true);
        VueTurnFinalizer finalizer = mock(VueTurnFinalizer.class);
        IllegalStateException prefixFailure =
                new IllegalStateException("canonical supplier failed");
        AtomicReference<Throwable> observedFailure = new AtomicReference<>();

        try (AutoCloseable observer = context.onFinalized(
                ignored -> { }, observedFailure::set);
             var coordinator = new VueTurnCancellationCoordinator(
                     finalizer, Runnable::run, Duration.ofMillis(20))) {
            Mono<VueTurnFinalizer.FinalizationResult> result = coordinator
                    .requestTimeout(context, () -> {
                        throw prefixFailure;
                    })
                    .orElseThrow();

            RuntimeException emitted = assertThrows(
                    RuntimeException.class,
                    () -> result.block(Duration.ofSeconds(1)));
            assertTrue(emitted == prefixFailure
                            || emitted.getCause() == prefixFailure,
                    "超时结果必须传播原始 supplier 异常");
            assertEquals(prefixFailure, observedFailure.get(),
                    "共享 finalization 观察者必须收到同一个异常");
            assertEquals(VueTurnContext.TurnStage.FINALIZED,
                    context.turnState().stage());
            assertEquals(0, coordinator.pendingCount());
            manager.acquire(
                    7L, AppOperationLeaseManager.AppOperationType.GENERATE,
                    "after-prefix-failure").close();
        } finally {
            context.closeResources();
        }

        verify(finalizer, org.mockito.Mockito.never())
                .finalizeOnce(any(), any());
    }

    @Test
    void realFinalizerFailureIsPropagatedOnceWithoutSecondaryFailure()
            throws Exception {
        AppOperationLeaseManager manager = new AppOperationLeaseManager();
        var operation = manager.acquire(
                7L, AppOperationLeaseManager.AppOperationType.GENERATE,
                "turn-real-finalizer-failure");
        var lease = new VueBuildSessionManager().open(
                operation, 9L, "turn-real-finalizer-failure");
        VueTurnContext context = new VueTurnContext(
                7L, 9L, "turn-real-finalizer-failure", operation, lease,
                budgetSession());
        context.commitUser(() -> true);
        ChatHistoryService history = mock(ChatHistoryService.class);
        ToolMessageCollapser collapser = mock(ToolMessageCollapser.class);
        AssertionError finalizerFailure =
                new AssertionError("chat history fatal failure");
        doThrow(finalizerFailure).when(history)
                .addChatMessage(anyLong(), anyString(), eq("ai"), anyLong());
        when(collapser.collapseLastTurn(anyLong(), anyString()))
                .thenReturn(new ToolMessageCollapser.CollapseResult(
                        ToolMessageCollapser.CollapseStatus.COLLAPSED,
                        java.util.List.of()));
        VueBuildRepairMetricsCollector metrics =
                new VueBuildRepairMetricsCollector(new SimpleMeterRegistry());
        VueTurnFinalizer realFinalizer = new VueTurnFinalizer(
                history, collapser, mock(MemorySummaryService.class),
                mock(UserMemoryService.class),
                mock(AiGeneratorServiceFactory.class),
                new AppDataLifecycleFence(), metrics,
                new FileToolBudgetGuard());
        AtomicReference<Throwable> observedFailure = new AtomicReference<>();

        try (AutoCloseable observer = context.onFinalized(
                ignored -> { }, observedFailure::set);
             var coordinator = new VueTurnCancellationCoordinator(
                     realFinalizer, Runnable::run, Duration.ofMillis(20))) {
            Mono<VueTurnFinalizer.FinalizationResult> result = coordinator
                    .requestTimeout(context, () -> "部分")
                    .orElseThrow();

            Throwable emitted = assertThrows(
                    Throwable.class,
                    () -> result.block(Duration.ofSeconds(1)));
            assertTrue(emitted == finalizerFailure
                            || emitted.getCause() == finalizerFailure,
                    "结果 sink 必须保留 finalizer 的原始失败");
            assertEquals(finalizerFailure, observedFailure.get());
            assertEquals(VueTurnContext.TurnStage.FINALIZED,
                    context.turnState().stage());
            assertEquals(0, coordinator.pendingCount());
            manager.acquire(
                    7L, AppOperationLeaseManager.AppOperationType.GENERATE,
                    "after-real-finalizer-failure").close();
        } finally {
            context.closeResources();
        }
    }

    @Test
    void timeoutWaitsForRealQuiescenceBeforePublishingTimedOutOutcome()
            throws Exception {
        AppOperationLeaseManager manager = new AppOperationLeaseManager();
        var operation = manager.acquire(7L,
                AppOperationLeaseManager.AppOperationType.GENERATE, "turn-timeout");
        var lease = new VueBuildSessionManager().open(
                operation, 9L, "turn-timeout");
        VueTurnContext context = new VueTurnContext(
                7L, 9L, "turn-timeout", operation, lease, budgetSession());
        context.commitUser(() -> true);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        Thread callback = Thread.startVirtualThread(() -> context.tryRunCallback(() -> {
            callbackEntered.countDown();
            try {
                releaseCallback.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }));
        assertTrue(callbackEntered.await(1, TimeUnit.SECONDS));
        VueTurnFinalizer finalizer = mock(VueTurnFinalizer.class);
        when(finalizer.finalizeOnce(eq(context), any())).thenAnswer(invocation -> {
            VueTurnOutcome requested = invocation.getArgument(1);
            assertEquals(VueTurnOutcome.TurnOutcomeType.TIMED_OUT,
                    requested.outcome());
            context.closeResources();
            return new VueTurnFinalizer.FinalizationResult(requested, true);
        });

        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var coordinator = new VueTurnCancellationCoordinator(
                     finalizer, executor, Duration.ofMillis(20))) {
            Mono<VueTurnFinalizer.FinalizationResult> result = coordinator
                    .requestTimeout(context, () -> "部分")
                    .orElseThrow();
            assertThrows(IllegalStateException.class,
                    () -> result.block(Duration.ofMillis(80)));
            assertThrows(RuntimeException.class, () -> manager.acquire(
                    7L, AppOperationLeaseManager.AppOperationType.GENERATE,
                    "too-early-after-timeout"));
            assertFalse(coordinator.requestCancellation(context, () -> "断开"));
            assertEquals(VueTurnContext.TerminalTrigger.TIMED_OUT,
                    context.terminalWinner().orElseThrow());
            releaseCallback.countDown();
            var completed = result.block(Duration.ofSeconds(2));
            assertEquals(VueTurnOutcome.TurnOutcomeType.TIMED_OUT,
                    completed.outcome().outcome());
        }
        callback.join();
        manager.acquire(7L, AppOperationLeaseManager.AppOperationType.GENERATE,
                "after-timeout-quiescence").close();
    }

    @Test
    void timeoutClaimClosesOuterToolGateBeforeBackgroundTaskStarts() {
        AppOperationLeaseManager manager = new AppOperationLeaseManager();
        var operation = manager.acquire(7L,
                AppOperationLeaseManager.AppOperationType.GENERATE,
                "turn-timeout-linearized");
        var lease = new VueBuildSessionManager().open(
                operation, 9L, "turn-timeout-linearized");
        VueTurnContext context = new VueTurnContext(
                7L, 9L, "turn-timeout-linearized", operation, lease,
                budgetSession());
        context.commitUser(() -> true);
        AtomicReference<Runnable> background = new AtomicReference<>();
        VueTurnFinalizer finalizer = mock(VueTurnFinalizer.class);

        try (var coordinator = new VueTurnCancellationCoordinator(
                finalizer, background::set, Duration.ofMillis(20))) {
            assertTrue(coordinator.requestTimeout(context, () -> "")
                    .isPresent());
            assertThrows(IllegalStateException.class, lease::enterCallback,
                    "requestTimeout 返回前必须同步关闭文件工具使用的外层门");
            assertTrue(background.get() != null);
        } finally {
            context.closeResources();
        }
    }

    @Test
    void cancellationAndTimeoutRejectTurnBeforeUserCommit() {
        AppOperationLeaseManager manager = new AppOperationLeaseManager();
        var operation = manager.acquire(7L,
                AppOperationLeaseManager.AppOperationType.GENERATE,
                "turn-pre-user-cancel");
        var lease = new VueBuildSessionManager().open(
                operation, 9L, "turn-pre-user-cancel");
        VueTurnContext context = new VueTurnContext(
                7L, 9L, "turn-pre-user-cancel", operation, lease,
                budgetSession());
        VueTurnFinalizer finalizer = mock(VueTurnFinalizer.class);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var coordinator = new VueTurnCancellationCoordinator(
                     finalizer, executor, Duration.ofMillis(20))) {
            assertThrows(IllegalStateException.class,
                    () -> coordinator.requestCancellation(context, () -> ""));
            assertThrows(IllegalStateException.class,
                    () -> coordinator.requestTimeout(context, () -> ""));
            assertEquals(0, coordinator.pendingCount());
        }

        verify(finalizer, org.mockito.Mockito.never()).finalizeOnce(any(), any());
        assertTrue(operation.isActive(), "预提交资源必须由 Service 准备阶段清理");
        context.closeResources();
    }

    @Test
    void preCommitCleanupWaitsForAdmittedStepBeforeClosingResources()
            throws Exception {
        AppOperationLeaseManager manager = new AppOperationLeaseManager();
        var operation = manager.acquire(7L,
                AppOperationLeaseManager.AppOperationType.GENERATE,
                "turn-pre-commit-cleanup");
        var lease = new VueBuildSessionManager().open(
                operation, 9L, "turn-pre-commit-cleanup");
        VueTurnContext context = new VueTurnContext(
                7L, 9L, "turn-pre-commit-cleanup",
                operation, lease, budgetSession());
        CountDownLatch actionEntered = new CountDownLatch(1);
        CountDownLatch releaseAction = new CountDownLatch(1);
        Thread action = Thread.startVirtualThread(() ->
                context.callPreparation(() -> {
                    actionEntered.countDown();
                    await(releaseAction);
                    return null;
                }));
        assertTrue(actionEntered.await(1, TimeUnit.SECONDS));
        assertEquals(VueTurnContext.PreCommitTerminationDecision.PRE_COMMIT_WON,
                context.claimPreCommitTermination());
        VueTurnFinalizer finalizer = mock(VueTurnFinalizer.class);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var coordinator = new VueTurnCancellationCoordinator(
                     finalizer, executor, Duration.ofMillis(20))) {
            CompletionStage<Void> cleanup =
                    coordinator.requestPreCommitCleanup(context);

            assertFalse(cleanup.toCompletableFuture().isDone());
            assertThrows(AppOperationLeaseManager.ActiveAppOperationException.class,
                    () -> manager.acquire(
                            7L,
                            AppOperationLeaseManager.AppOperationType.GENERATE,
                            "too-early"));
            verify(finalizer, org.mockito.Mockito.never())
                    .finalizeOnce(any(), any());

            releaseAction.countDown();
            action.join();
            cleanup.toCompletableFuture().get(1, TimeUnit.SECONDS);

            assertEquals(0, coordinator.pendingCount());
            manager.acquire(7L,
                    AppOperationLeaseManager.AppOperationType.GENERATE,
                    "after-cleanup").close();
            verify(finalizer, org.mockito.Mockito.never())
                    .finalizeOnce(any(), any());
        } finally {
            releaseAction.countDown();
            action.join();
            context.closeResources();
        }
    }

    @Test
    void interruptedPreCommitCleanupFailsResultThenDrainsResourcesAfterQuiescence()
            throws Exception {
        SimpleMeterRegistry metricsRegistry = new SimpleMeterRegistry();
        VueBuildRepairMetricsCollector metrics =
                new VueBuildRepairMetricsCollector(metricsRegistry);
        AppOperationLeaseManager manager = new AppOperationLeaseManager();
        var operation = manager.acquire(
                7L, AppOperationLeaseManager.AppOperationType.GENERATE,
                "turn-pre-commit-interrupted");
        var lease = new VueBuildSessionManager().open(
                operation, 9L, "turn-pre-commit-interrupted");
        var admission = new VueTurnAdmissionController(metrics);
        var admissionPermit = admission.tryAcquire().orElseThrow();
        VueTurnContext context = new VueTurnContext(
                7L, 9L, "turn-pre-commit-interrupted",
                operation, lease, admissionPermit, budgetSession());
        CountDownLatch actionEntered = new CountDownLatch(1);
        CountDownLatch releaseAction = new CountDownLatch(1);
        Thread action = Thread.startVirtualThread(() ->
                context.callPreparation(() -> {
                    actionEntered.countDown();
                    await(releaseAction);
                    return null;
                }));
        assertTrue(actionEntered.await(1, TimeUnit.SECONDS));
        assertEquals(VueTurnContext.PreCommitTerminationDecision.PRE_COMMIT_WON,
                context.claimPreCommitTermination());
        LinkedBlockingQueue<Runnable> backgroundTasks =
                new LinkedBlockingQueue<>();
        VueTurnFinalizer finalizer = mock(VueTurnFinalizer.class);

        try (var coordinator = new VueTurnCancellationCoordinator(
                finalizer, backgroundTasks::add, Duration.ofSeconds(10),
                metrics)) {
            CompletionStage<Void> cleanup =
                    coordinator.requestPreCommitCleanup(context);
            Runnable cleanupTask = backgroundTasks.poll(1, TimeUnit.SECONDS);
            assertNotNull(cleanupTask);
            Thread cleanupThread = Thread.ofVirtual()
                    .name("test-vue-pre-commit-interrupted")
                    .start(cleanupTask);
            cleanupThread.interrupt();
            cleanupThread.join(Duration.ofSeconds(1));

            assertFalse(cleanupThread.isAlive());
            CompletionException failure = assertThrows(
                    CompletionException.class,
                    () -> cleanup.toCompletableFuture().join());
            assertEquals("Vue 预提交清理在静默前被中断",
                    failure.getCause().getMessage());
            assertEquals(1, coordinator.pendingCount(),
                    "原 completion 失败时仍须保留未静默资源");
            assertThrows(AppOperationLeaseManager.ActiveAppOperationException.class,
                    () -> manager.acquire(
                            7L,
                            AppOperationLeaseManager.AppOperationType.GENERATE,
                            "must-remain-blocked-after-pre-commit-interrupt"));

            Runnable drainTask = backgroundTasks.poll(1, TimeUnit.SECONDS);
            assertNotNull(drainTask,
                    "预提交结果失败后必须调度独立资源排空任务");
            Thread drainThread = Thread.ofVirtual()
                    .name("test-vue-pre-commit-drain")
                    .start(drainTask);
            releaseAction.countDown();
            action.join();
            drainThread.join(Duration.ofSeconds(1));

            assertFalse(drainThread.isAlive());
            assertEquals(0, coordinator.pendingCount());
            manager.acquire(
                    7L, AppOperationLeaseManager.AppOperationType.GENERATE,
                    "after-pre-commit-drain").close();
            assertEquals(1.0, metricsRegistry
                    .get("vue_turn_admissions_total")
                    .tag("result", "released").counter().count());
            context.closeResources();
            assertEquals(1.0, metricsRegistry
                    .get("vue_turn_admissions_total")
                    .tag("result", "released").counter().count(),
                    "预提交排空后的重复关闭不得重复释放准入许可");
        } finally {
            releaseAction.countDown();
            action.join();
            context.closeResources();
        }

        verify(finalizer, org.mockito.Mockito.never())
                .finalizeOnce(any(), any());
    }

    @Test
    void preCommitCleanupRejectsPreparingAndCommittedTurns() {
        AppOperationLeaseManager manager = new AppOperationLeaseManager();
        VueTurnFinalizer finalizer = mock(VueTurnFinalizer.class);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var coordinator = new VueTurnCancellationCoordinator(
                     finalizer, executor, Duration.ofMillis(20))) {
            var preparingOperation = manager.acquire(
                    7L, AppOperationLeaseManager.AppOperationType.GENERATE,
                    "turn-preparing-rejected");
            var preparingLease = new VueBuildSessionManager().open(
                    preparingOperation, 9L, "turn-preparing-rejected");
            VueTurnContext preparing = new VueTurnContext(
                    7L, 9L, "turn-preparing-rejected",
                    preparingOperation, preparingLease, budgetSession());
            try {
                assertThrows(IllegalStateException.class,
                        () -> coordinator.requestPreCommitCleanup(preparing));
                assertTrue(preparingOperation.isActive());
            } finally {
                preparing.closeResources();
            }

            var committedOperation = manager.acquire(
                    8L, AppOperationLeaseManager.AppOperationType.GENERATE,
                    "turn-committed-rejected");
            var committedLease = new VueBuildSessionManager().open(
                    committedOperation, 9L, "turn-committed-rejected");
            VueTurnContext committed = new VueTurnContext(
                    8L, 9L, "turn-committed-rejected",
                    committedOperation, committedLease, budgetSession());
            committed.commitUser(() -> true);
            try {
                assertThrows(IllegalStateException.class,
                        () -> coordinator.requestPreCommitCleanup(committed));
                assertTrue(committedOperation.isActive());
            } finally {
                committed.closeResources();
            }

            assertEquals(0, coordinator.pendingCount());
        }
        verify(finalizer, org.mockito.Mockito.never()).finalizeOnce(any(), any());
    }

    @Test
    void rejectedExecutorClosesQuiescentPreCommitTurnSynchronously()
            throws Exception {
        AppOperationLeaseManager manager = new AppOperationLeaseManager();
        var operation = manager.acquire(
                7L, AppOperationLeaseManager.AppOperationType.GENERATE,
                "turn-pre-commit-rejected-quiet");
        var lease = new VueBuildSessionManager().open(
                operation, 9L, "turn-pre-commit-rejected-quiet");
        VueTurnContext context = new VueTurnContext(
                7L, 9L, "turn-pre-commit-rejected-quiet",
                operation, lease, budgetSession());
        assertEquals(VueTurnContext.PreCommitTerminationDecision.PRE_COMMIT_WON,
                context.claimPreCommitTermination());
        VueTurnFinalizer finalizer = mock(VueTurnFinalizer.class);
        java.util.concurrent.Executor rejecting = task -> {
            throw new RejectedExecutionException("executor closed");
        };

        try (var coordinator = new VueTurnCancellationCoordinator(
                finalizer, rejecting, Duration.ofMillis(20))) {
            coordinator.requestPreCommitCleanup(context)
                    .toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertEquals(0, coordinator.pendingCount());
        }

        manager.acquire(7L,
                AppOperationLeaseManager.AppOperationType.GENERATE,
                "after-pre-commit-rejected-quiet").close();
        verify(finalizer, org.mockito.Mockito.never()).finalizeOnce(any(), any());
    }

    @Test
    void rejectedExecutorKeepsNonQuiescentPreCommitTurnPending()
            throws Exception {
        AppOperationLeaseManager manager = new AppOperationLeaseManager();
        var operation = manager.acquire(
                7L, AppOperationLeaseManager.AppOperationType.GENERATE,
                "turn-pre-commit-rejected-busy");
        var lease = new VueBuildSessionManager().open(
                operation, 9L, "turn-pre-commit-rejected-busy");
        VueTurnContext context = new VueTurnContext(
                7L, 9L, "turn-pre-commit-rejected-busy",
                operation, lease, budgetSession());
        CountDownLatch actionEntered = new CountDownLatch(1);
        CountDownLatch releaseAction = new CountDownLatch(1);
        Thread action = Thread.startVirtualThread(() ->
                context.callPreparation(() -> {
                    actionEntered.countDown();
                    await(releaseAction);
                    return null;
                }));
        assertTrue(actionEntered.await(1, TimeUnit.SECONDS));
        assertEquals(VueTurnContext.PreCommitTerminationDecision.PRE_COMMIT_WON,
                context.claimPreCommitTermination());
        VueTurnFinalizer finalizer = mock(VueTurnFinalizer.class);
        java.util.concurrent.Executor rejecting = task -> {
            throw new RejectedExecutionException("executor closed");
        };

        try (var coordinator = new VueTurnCancellationCoordinator(
                finalizer, rejecting, Duration.ofMillis(20))) {
            CompletionStage<Void> cleanup =
                    coordinator.requestPreCommitCleanup(context);
            CompletionException failure = assertThrows(
                    CompletionException.class,
                    () -> cleanup.toCompletableFuture().join());
            assertTrue(failure.getCause()
                    instanceof RejectedExecutionException);
            assertEquals(1, coordinator.pendingCount());
            assertThrows(AppOperationLeaseManager.ActiveAppOperationException.class,
                    () -> manager.acquire(
                            7L,
                            AppOperationLeaseManager.AppOperationType.GENERATE,
                            "must-remain-blocked"));
        } finally {
            releaseAction.countDown();
            action.join();
            context.closeResources();
        }
        verify(finalizer, org.mockito.Mockito.never()).finalizeOnce(any(), any());
    }

    @Test
    void deleteTakeoverUsesDedicatedTerminalClaimAndSharedFinalization()
            throws Exception {
        AppOperationLeaseManager manager = new AppOperationLeaseManager();
        var operation = manager.acquire(7L,
                AppOperationLeaseManager.AppOperationType.GENERATE,
                "turn-delete");
        var lease = new VueBuildSessionManager().open(
                operation, 9L, "turn-delete");
        VueTurnContext context = new VueTurnContext(
                7L, 9L, "turn-delete", operation, lease, budgetSession());
        context.commitUser(() -> true);
        context.registerDeleteTakeoverParticipant();
        AtomicReference<VueTurnContext.DeleteTakeoverRequest> request =
                new AtomicReference<>();
        context.deleteTakeoverSignal().subscribe(request::set);
        VueTurnFinalizer finalizer = mock(VueTurnFinalizer.class);
        when(finalizer.finalizeOnce(eq(context), any())).thenAnswer(invocation -> {
            VueTurnOutcome outcome = invocation.getArgument(1);
            var result = new VueTurnFinalizer.FinalizationResult(outcome, true);
            context.completeFinalization(result);
            return result;
        });

        try (var background = Executors.newVirtualThreadPerTaskExecutor();
             var coordinator = new VueTurnCancellationCoordinator(
                     finalizer, background, Duration.ofSeconds(1))) {
            Future<AppOperationLeaseManager.AppOperationLease> deletion =
                    background.submit(() -> manager.cancelAndAcquireDelete(
                            7L, "delete", Duration.ofSeconds(1)));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (request.get() == null && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(request.get() != null);

            var result = coordinator.requestDeleteTakeover(
                            context, request.get(), () -> "已生成部分")
                    .orElseThrow()
                    .block(Duration.ofSeconds(1));
            assertEquals(VueTurnOutcome.TurnOutcomeType.CANCELLED,
                    result.outcome().outcome());
            assertEquals(VueTurnContext.TerminalTrigger.DELETE_TAKEOVER,
                    context.terminalWinner().orElseThrow());
            try (var deleteLease = deletion.get(1, TimeUnit.SECONDS)) {
                assertEquals(AppOperationLeaseManager.AppOperationType.DELETE,
                        deleteLease.operationType());
            }
        } finally {
            context.closeResources();
        }
    }

    @Test
    void deleteTakeoverSchedulesBlockingFinalizationOffNonBlockingThread()
            throws Exception {
        AppOperationLeaseManager manager = new AppOperationLeaseManager();
        var operation = manager.acquire(
                7L, AppOperationLeaseManager.AppOperationType.GENERATE,
                "turn-delete-scheduled");
        var lease = new VueBuildSessionManager().open(
                operation, 9L, "turn-delete-scheduled");
        VueTurnContext context = new VueTurnContext(
                7L, 9L, "turn-delete-scheduled",
                operation, lease, budgetSession());
        context.commitUser(() -> true);
        context.registerDeleteTakeoverParticipant();
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        Thread callback = Thread.startVirtualThread(() ->
                context.tryRunCallback(() -> {
                    callbackEntered.countDown();
                    await(releaseCallback);
                }));
        assertTrue(callbackEntered.await(1, TimeUnit.SECONDS));
        AtomicReference<VueTurnContext.DeleteTakeoverRequest> request =
                new AtomicReference<>();
        CountDownLatch requestObserved = new CountDownLatch(1);
        context.deleteTakeoverSignal().subscribe(observed -> {
            request.set(observed);
            requestObserved.countDown();
        });
        LinkedBlockingQueue<Runnable> managedTasks =
                new LinkedBlockingQueue<>();
        VueTurnFinalizer finalizer = mock(VueTurnFinalizer.class);
        AtomicReference<String> finalizerThread = new AtomicReference<>();
        when(finalizer.finalizeOnce(eq(context), any())).thenAnswer(invocation -> {
            finalizerThread.set(Thread.currentThread().getName());
            VueTurnOutcome outcome = invocation.getArgument(1);
            var result = new VueTurnFinalizer.FinalizationResult(outcome, true);
            context.closeResources();
            context.completeFinalization(result);
            return result;
        });
        reactor.core.scheduler.Scheduler triggerScheduler =
                reactor.core.scheduler.Schedulers.newParallel(
                        "test-delete-trigger", 1);

        try (var deletionExecutor = Executors.newVirtualThreadPerTaskExecutor();
             var coordinator = new VueTurnCancellationCoordinator(
                     finalizer, managedTasks::add, Duration.ofSeconds(2))) {
            Future<AppOperationLeaseManager.AppOperationLease> deletion =
                    deletionExecutor.submit(() -> manager.cancelAndAcquireDelete(
                            7L, "delete-scheduled", Duration.ofSeconds(2)));
            assertTrue(requestObserved.await(1, TimeUnit.SECONDS));
            CountDownLatch subscriptionReturned = new CountDownLatch(1);
            AtomicBoolean triggerWasNonBlocking = new AtomicBoolean();
            AtomicReference<reactor.core.Disposable> downstream =
                    new AtomicReference<>();
            AtomicReference<Throwable> downstreamFailure = new AtomicReference<>();

            triggerScheduler.schedule(() -> {
                triggerWasNonBlocking.set(
                        reactor.core.scheduler.Schedulers
                                .isInNonBlockingThread());
                downstream.set(coordinator.requestDeleteTakeover(
                                context, request.get(), () -> "已生成部分")
                        .orElseThrow()
                        .subscribe(ignored -> { }, downstreamFailure::set));
                subscriptionReturned.countDown();
            });

            assertTrue(subscriptionReturned.await(300, TimeUnit.MILLISECONDS),
                    "Reactor non-blocking 线程只能提交任务，不能等待删除静默");
            assertTrue(triggerWasNonBlocking.get());
            assertNull(downstreamFailure.get());
            downstream.get().dispose();

            Runnable finalizationTask = managedTasks.poll(1, TimeUnit.SECONDS);
            assertNotNull(finalizationTask,
                    "删除接管收尾必须提交到受管取消执行器");
            Thread finalizationThread = Thread.ofVirtual()
                    .name("test-managed-delete-finalization")
                    .start(finalizationTask);
            releaseCallback.countDown();
            callback.join();
            finalizationThread.join(Duration.ofSeconds(1));

            assertFalse(finalizationThread.isAlive());
            assertEquals("test-managed-delete-finalization",
                    finalizerThread.get());
            assertEquals(VueTurnOutcome.TurnOutcomeType.CANCELLED,
                    context.awaitFinalization().outcome().outcome());
            try (var deleteLease = deletion.get(1, TimeUnit.SECONDS)) {
                assertEquals(AppOperationLeaseManager.AppOperationType.DELETE,
                        deleteLease.operationType());
            }
            verify(finalizer).finalizeOnce(eq(context), any());
        } finally {
            releaseCallback.countDown();
            callback.join();
            triggerScheduler.dispose();
            context.closeResources();
        }
    }

    private static double cancellationCount(
            SimpleMeterRegistry registry, String trigger, String result) {
        return registry.getMeters().stream()
                .filter(meter -> meter.getId().getName()
                        .equals("vue_turn_cancellations_total"))
                .filter(meter -> trigger.equals(meter.getId().getTag("trigger")))
                .filter(meter -> result.equals(meter.getId().getTag("result")))
                .mapToDouble(meter -> meter.measure().iterator().next().getValue())
                .sum();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("等待测试屏障超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待测试屏障被中断", exception);
        }
    }

    private FileToolBudgetGuard.Session budgetSession() {
        return new FileToolBudgetGuard().newSession();
    }
}
