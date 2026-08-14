package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.ai.tools.FileToolBudgetGuard;

import com.lyw.appgeneration.core.builder.VueBuildPhase;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import com.lyw.appgeneration.monitor.VueBuildRepairMetricsCollector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Duration;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        context.markUserCommitted();
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
        context.markUserCommitted();
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
        AppOperationLeaseManager manager = new AppOperationLeaseManager();
        var operation = manager.acquire(7L,
                AppOperationLeaseManager.AppOperationType.GENERATE, "turn-rejected");
        var lease = new VueBuildSessionManager().open(operation, 9L, "turn-rejected");
        VueTurnContext context = new VueTurnContext(
                7L, 9L, "turn-rejected", operation, lease, budgetSession());
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
        java.util.concurrent.Executor rejecting = task -> {
            throw new RejectedExecutionException("executor closed");
        };

        try (var coordinator = new VueTurnCancellationCoordinator(
                finalizer, rejecting, Duration.ofMillis(20))) {
            assertThrows(RejectedExecutionException.class, () ->
                    coordinator.requestCancellation(context, () -> "部分"));
            assertEquals(1, coordinator.pendingCount());
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
    }

    @Test
    void rejectedExecutionFinalizesSynchronouslyOnlyWhenAlreadyQuiescent() {
        AppOperationLeaseManager manager = new AppOperationLeaseManager();
        var operation = manager.acquire(7L,
                AppOperationLeaseManager.AppOperationType.GENERATE, "turn-quiet");
        var lease = new VueBuildSessionManager().open(operation, 9L, "turn-quiet");
        VueTurnContext context = new VueTurnContext(
                7L, 9L, "turn-quiet", operation, lease, budgetSession());
        context.markUserCommitted();
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
    void timeoutWaitsForRealQuiescenceBeforePublishingTimedOutOutcome()
            throws Exception {
        AppOperationLeaseManager manager = new AppOperationLeaseManager();
        var operation = manager.acquire(7L,
                AppOperationLeaseManager.AppOperationType.GENERATE, "turn-timeout");
        var lease = new VueBuildSessionManager().open(
                operation, 9L, "turn-timeout");
        VueTurnContext context = new VueTurnContext(
                7L, 9L, "turn-timeout", operation, lease, budgetSession());
        context.markUserCommitted();
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
    void cancellationBeforeUserCommitReleasesQuietTurnWithoutPersistingAi() {
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
            assertTrue(coordinator.requestCancellation(context, () -> ""));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (coordinator.pendingCount() != 0
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(0, coordinator.pendingCount());
        }

        verify(finalizer, org.mockito.Mockito.never()).finalizeOnce(any(), any());
        manager.acquire(7L, AppOperationLeaseManager.AppOperationType.GENERATE,
                "after-pre-user-cancel").close();
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
    private FileToolBudgetGuard.Session budgetSession() {
        return new FileToolBudgetGuard().newSession();
    }
}
