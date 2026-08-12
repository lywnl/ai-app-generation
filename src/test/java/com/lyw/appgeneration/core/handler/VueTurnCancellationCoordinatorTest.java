package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.core.builder.VueBuildPhase;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
}
