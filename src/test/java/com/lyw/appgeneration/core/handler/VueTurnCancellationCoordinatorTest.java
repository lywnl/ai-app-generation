package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.core.builder.VueBuildPhase;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;

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
        AppOperationLeaseManager manager = new AppOperationLeaseManager();
        var operation = manager.acquire(7L,
                AppOperationLeaseManager.AppOperationType.GENERATE, "turn-cancel");
        var lease = new VueBuildSessionManager().open(
                operation, 9L, "turn-cancel");
        VueTurnContext context = new VueTurnContext(
                7L, 9L, "turn-cancel", operation, lease);
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
             var coordinator = new VueTurnCancellationCoordinator(finalizer, executor)) {
            assertTrue(coordinator.requestCancellation(context, () -> "已生成部分"));
            assertFalse(coordinator.requestCancellation(context, () -> "重复"));
            assertTrue(finalized.await(2, TimeUnit.SECONDS));
        }

        assertEquals(1, modelCancellations.get());
        assertFalse(context.tryRunCallback(() -> { }));
        verify(finalizer).finalizeOnce(eq(context), any());
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
                7L, 9L, "turn-blocked", operation, lease);
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
                7L, 9L, "turn-rejected", operation, lease);
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
                7L, 9L, "turn-quiet", operation, lease);
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
}
