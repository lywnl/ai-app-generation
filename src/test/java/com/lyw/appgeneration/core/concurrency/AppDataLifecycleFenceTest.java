package com.lyw.appgeneration.core.concurrency;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AppDataLifecycleFenceTest {

    private static final long APP_ID = 7L;

    @Test
    void deleteClosesGateAndWaitsForAllEnteredWriters() throws Exception {
        AppDataLifecycleFence fence = new AppDataLifecycleFence();
        AppDataLifecycleFence.WriterPermit first = fence.tryAcquireWriter(APP_ID);
        AppDataLifecycleFence.WriterPermit second = fence.tryAcquireWriter(APP_ID);
        assertNotNull(first);
        assertNotNull(second);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<AppDataLifecycleFence.DeletePermit> deletion = executor.submit(
                    () -> fence.beginDelete(APP_ID, Duration.ofSeconds(2)));
            try {
                awaitGateClosed(fence);

                assertThrows(TimeoutException.class,
                        () -> deletion.get(100, TimeUnit.MILLISECONDS));
                first.close();
                assertThrows(TimeoutException.class,
                        () -> deletion.get(100, TimeUnit.MILLISECONDS));
                second.close();

                AppDataLifecycleFence.DeletePermit deletePermit =
                        deletion.get(1, TimeUnit.SECONDS);
                assertNotNull(deletePermit);
                deletePermit.abortAndReopen();
            } finally {
                first.close();
                second.close();
                deletion.cancel(true);
            }
        }
    }

    @Test
    void timedOutDeleteReopensGateWithoutLeavingSideEffectsInFence() {
        AppDataLifecycleFence fence = new AppDataLifecycleFence();
        AppDataLifecycleFence.WriterPermit writer = fence.tryAcquireWriter(APP_ID);

        assertNull(fence.beginDelete(APP_ID, Duration.ZERO));
        writer.close();

        assertNotNull(fence.tryAcquireWriter(APP_ID));
    }

    @Test
    void committedTombstonePermanentlyRejectsWritersAndAnotherDelete() {
        AppDataLifecycleFence fence = new AppDataLifecycleFence();
        AppDataLifecycleFence.DeletePermit deletePermit =
                fence.beginDelete(APP_ID, Duration.ofSeconds(1));
        assertNotNull(deletePermit);

        deletePermit.commitTombstone();
        deletePermit.close();

        assertNull(fence.tryAcquireWriter(APP_ID));
        assertNull(fence.beginDelete(APP_ID, Duration.ofSeconds(1)));
    }

    @Test
    void abortedOrImplicitlyClosedDeleteReopensGate() {
        AppDataLifecycleFence fence = new AppDataLifecycleFence();

        AppDataLifecycleFence.DeletePermit aborted =
                fence.beginDelete(APP_ID, Duration.ofSeconds(1));
        assertNotNull(aborted);
        aborted.abortAndReopen();
        aborted.abortAndReopen();
        assertNotNull(fence.tryAcquireWriter(APP_ID));

        AppDataLifecycleFence.DeletePermit implicitlyAborted =
                fence.beginDelete(APP_ID + 1, Duration.ofSeconds(1));
        assertNotNull(implicitlyAborted);
        implicitlyAborted.close();
        assertNotNull(fence.tryAcquireWriter(APP_ID + 1));
    }

    @Test
    void writerCloseIsIdempotentAndDoesNotReleaseAnotherWriter() throws Exception {
        AppDataLifecycleFence fence = new AppDataLifecycleFence();
        AppDataLifecycleFence.WriterPermit first = fence.tryAcquireWriter(APP_ID);
        AppDataLifecycleFence.WriterPermit second = fence.tryAcquireWriter(APP_ID);
        assertNotNull(first);
        assertNotNull(second);
        first.close();
        first.close();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<AppDataLifecycleFence.DeletePermit> deletion = executor.submit(
                    () -> fence.beginDelete(APP_ID, Duration.ofSeconds(2)));
            try {
                awaitGateClosed(fence);
                assertThrows(TimeoutException.class,
                        () -> deletion.get(100, TimeUnit.MILLISECONDS));

                second.close();
                AppDataLifecycleFence.DeletePermit permit =
                        deletion.get(1, TimeUnit.SECONDS);
                assertNotNull(permit);
                permit.abortAndReopen();
            } finally {
                second.close();
                deletion.cancel(true);
            }
        }
    }

    @Test
    void applicationStatesAreIndependent() {
        AppDataLifecycleFence fence = new AppDataLifecycleFence();
        AppDataLifecycleFence.DeletePermit deletePermit =
                fence.beginDelete(APP_ID, Duration.ofSeconds(1));
        assertNotNull(deletePermit);

        assertNull(fence.tryAcquireWriter(APP_ID));
        assertNotNull(fence.tryAcquireWriter(APP_ID + 1));
        deletePermit.abortAndReopen();
    }

    @Test
    void interruptedDeleteReopensGateAndRestoresInterruptStatus() throws Exception {
        AppDataLifecycleFence fence = new AppDataLifecycleFence();
        AppDataLifecycleFence.WriterPermit writer = fence.tryAcquireWriter(APP_ID);
        assertNotNull(writer);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AtomicReference<Thread> deletionThread = new AtomicReference<>();
            Future<InterruptedDeleteResult> deletion = executor.submit(() -> {
                deletionThread.set(Thread.currentThread());
                try {
                    fence.beginDelete(APP_ID, Duration.ofSeconds(5));
                    return new InterruptedDeleteResult(null,
                            Thread.currentThread().isInterrupted());
                } catch (Throwable throwable) {
                    return new InterruptedDeleteResult(throwable,
                            Thread.currentThread().isInterrupted());
                }
            });
            awaitGateClosed(fence);
            deletionThread.get().interrupt();
            InterruptedDeleteResult result = deletion.get(1, TimeUnit.SECONDS);
            assertInstanceOf(IllegalStateException.class, result.failure());
            assertTrue(result.interrupted());
        }
        writer.close();

        assertNotNull(fence.tryAcquireWriter(APP_ID));
    }

    @Test
    void rejectsInvalidApplicationIdAndTimeout() {
        AppDataLifecycleFence fence = new AppDataLifecycleFence();

        assertThrows(IllegalArgumentException.class,
                () -> fence.tryAcquireWriter(0));
        assertThrows(IllegalArgumentException.class,
                () -> fence.beginDelete(APP_ID, Duration.ofMillis(-1)));
        assertThrows(NullPointerException.class,
                () -> fence.beginDelete(APP_ID, null));
    }

    private void awaitGateClosed(AppDataLifecycleFence fence) throws Exception {
        ExecutorService observer = Executors.newVirtualThreadPerTaskExecutor();
        Future<Boolean> observation = observer.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                AppDataLifecycleFence.WriterPermit permit =
                        fence.tryAcquireWriter(APP_ID);
                if (permit == null) {
                    return true;
                }
                permit.close();
                Thread.onSpinWait();
            }
            return false;
        });
        try {
            assertTrue(observation.get(1, TimeUnit.SECONDS));
        } finally {
            observation.cancel(true);
            observer.shutdownNow();
            assertTrue(observer.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    private record InterruptedDeleteResult(Throwable failure, boolean interrupted) {
    }
}
