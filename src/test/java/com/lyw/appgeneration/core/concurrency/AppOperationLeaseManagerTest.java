package com.lyw.appgeneration.core.concurrency;

import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager.AppOperationType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppOperationLeaseManagerTest {

    @Test
    void ownerCloseSealsRegistrationThatPassedOuterCheck() throws Exception {
        CountDownLatch outerCheckPassed = new CountDownLatch(1);
        CountDownLatch resumeRegistration = new CountDownLatch(1);
        AtomicInteger lateCancellation = new AtomicInteger();
        AppOperationLeaseManager manager = new AppOperationLeaseManager(() -> {
            outerCheckPassed.countDown();
            awaitUnchecked(resumeRegistration);
        });
        var oldLease = manager.acquire(7L, AppOperationType.GENERATE, "turn-1");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> registration = executor.submit(
                    () -> oldLease.registerCancellation(lateCancellation::incrementAndGet));
            assertTrue(outerCheckPassed.await(1, TimeUnit.SECONDS));

            oldLease.close();
            try (var newLease = manager.acquire(
                    7L, AppOperationType.GENERATE, "turn-2")) {
                resumeRegistration.countDown();
                assertRegistrationRejected(registration);
                assertEquals(0, lateCancellation.get());
                assertEquals("turn-2", newLease.ownerToken());
            }
        } finally {
            resumeRegistration.countDown();
            oldLease.close();
        }
    }

    @Test
    void deleteReplacementSealsRegistrationThatPassedOuterCheck() throws Exception {
        CountDownLatch outerCheckPassed = new CountDownLatch(1);
        CountDownLatch resumeRegistration = new CountDownLatch(1);
        AtomicInteger lateCancellation = new AtomicInteger();
        AppOperationLeaseManager manager = new AppOperationLeaseManager(() -> {
            outerCheckPassed.countDown();
            awaitUnchecked(resumeRegistration);
        });
        var generateLease = manager.acquire(7L, AppOperationType.GENERATE, "turn-1");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> registration = executor.submit(
                    () -> generateLease.registerCancellation(lateCancellation::incrementAndGet));
            assertTrue(outerCheckPassed.await(1, TimeUnit.SECONDS));

            try (var deleteLease = manager.cancelAndAcquireDelete(
                    7L, "delete-1", Duration.ofSeconds(1))) {
                resumeRegistration.countDown();
                assertRegistrationRejected(registration);
                assertEquals(0, lateCancellation.get());
                assertEquals(AppOperationType.DELETE, deleteLease.operationType());
            }
        } finally {
            resumeRegistration.countDown();
            generateLease.close();
        }
    }

    @Test
    void registrationWinningBeforeDeleteSealIsIncludedInSameQuiescenceWait() throws Exception {
        CountDownLatch outerCheckPassed = new CountDownLatch(1);
        CountDownLatch resumeRegistration = new CountDownLatch(1);
        CountDownLatch lateActionStarted = new CountDownLatch(1);
        CountDownLatch releaseLateAction = new CountDownLatch(1);
        AppOperationLeaseManager manager = new AppOperationLeaseManager(() -> {
            outerCheckPassed.countDown();
            awaitUnchecked(resumeRegistration);
        });
        var generateLease = manager.acquire(7L, AppOperationType.GENERATE, "turn-1");
        var callback = generateLease.enterCallback();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> registration = executor.submit(() -> generateLease.registerCancellation(() -> {
                lateActionStarted.countDown();
                awaitUnchecked(releaseLateAction);
            }));
            assertTrue(outerCheckPassed.await(1, TimeUnit.SECONDS));
            Future<AppOperationLeaseManager.AppOperationLease> delete = executor.submit(
                    () -> manager.cancelAndAcquireDelete(
                            7L, "delete-1", Duration.ofSeconds(1)));
            awaitCancellationGateClosed(generateLease);

            resumeRegistration.countDown();
            assertTrue(lateActionStarted.await(1, TimeUnit.SECONDS));
            callback.close();
            assertFalse(delete.isDone());
            releaseLateAction.countDown();

            registration.get(1, TimeUnit.SECONDS);
            try (var deleteLease = delete.get(1, TimeUnit.SECONDS)) {
                assertEquals(AppOperationType.DELETE, deleteLease.operationType());
            }
        } finally {
            resumeRegistration.countDown();
            releaseLateAction.countDown();
            callback.close();
            generateLease.close();
        }
    }

    @Test
    void deleteTimeoutIncludesBlockingCancellationDispatchTime() throws Exception {
        AppOperationLeaseManager manager = new AppOperationLeaseManager();
        CountDownLatch actionStarted = new CountDownLatch(1);
        CountDownLatch releaseAction = new CountDownLatch(1);
        var generateLease = manager.acquire(7L, AppOperationType.GENERATE, "turn-1");
        generateLease.registerCancellation(() -> {
            actionStarted.countDown();
            awaitUnchecked(releaseAction);
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> delete = executor.submit(() -> manager.cancelAndAcquireDelete(
                    7L, "delete-1", Duration.ofMillis(30)));
            assertTrue(actionStarted.await(1, TimeUnit.SECONDS));
            try {
                ExecutionException exception = assertThrows(
                        ExecutionException.class,
                        () -> delete.get(500, TimeUnit.MILLISECONDS));
                assertInstanceOf(
                        AppOperationLeaseManager.OperationQuiescenceTimeoutException.class,
                        exception.getCause());
            } finally {
                releaseAction.countDown();
            }
        } finally {
            releaseAction.countDown();
            generateLease.close();
        }
    }

    private void assertRegistrationRejected(Future<?> registration) throws Exception {
        ExecutionException exception = assertThrows(
                ExecutionException.class,
                () -> registration.get(1, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, exception.getCause());
    }

    private void awaitCancellationGateClosed(
            AppOperationLeaseManager.AppOperationLease lease) throws Exception {
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (lease.isActive() && System.nanoTime() < deadlineNanos) {
            Thread.onSpinWait();
        }
        assertFalse(lease.isActive());
    }

    private void awaitUnchecked(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
