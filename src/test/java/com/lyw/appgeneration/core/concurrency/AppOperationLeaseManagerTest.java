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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void conditionalCancellationClaimsTerminalAndClosesCallbackGateAtomically()
            throws Exception {
        AppOperationLeaseManager manager = new AppOperationLeaseManager();
        var lease = manager.acquire(7L, AppOperationType.GENERATE, "turn-atomic");
        CountDownLatch claimEntered = new CountDownLatch(1);
        CountDownLatch releaseClaim = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> cancellation = executor.submit(() ->
                    lease.requestCancellationIf(() -> {
                        claimEntered.countDown();
                        awaitUnchecked(releaseClaim);
                        return true;
                    }));
            assertTrue(claimEntered.await(1, TimeUnit.SECONDS));

            Future<?> lateCallback = executor.submit(lease::enterCallback);
            assertFalse(lateCallback.isDone(),
                    "终态认领尚未提交时，晚到回调只能等待同一原子提交点");

            releaseClaim.countDown();
            assertTrue(cancellation.get(1, TimeUnit.SECONDS));
            ExecutionException rejected = assertThrows(
                    ExecutionException.class,
                    () -> lateCallback.get(1, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, rejected.getCause());
        } finally {
            releaseClaim.countDown();
            lease.close();
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

    @Test
    void runtimeFailureStartingDispatchDoesNotRunBlockingActionOnDeleteThread()
            throws Exception {
        AtomicInteger actions = new AtomicInteger();
        CountDownLatch actionStarted = new CountDownLatch(1);
        CountDownLatch releaseAction = new CountDownLatch(1);
        IllegalStateException startFailure = new IllegalStateException("启动失败");
        AppOperationLeaseManager manager = new AppOperationLeaseManager(
                null, task -> { throw startFailure; });
        var generateLease = manager.acquire(7L, AppOperationType.GENERATE, "turn-1");
        generateLease.registerCancellation(() -> {
            actions.incrementAndGet();
            actionStarted.countDown();
            awaitUnchecked(releaseAction);
        });

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> delete = executor.submit(() -> manager.cancelAndAcquireDelete(
                    7L, "delete-1", Duration.ofMillis(30)));
            try {
                ExecutionException exception = assertThrows(
                        ExecutionException.class,
                        () -> delete.get(300, TimeUnit.MILLISECONDS));
                assertSame(startFailure, exception.getCause());
                assertEquals(1, actionStarted.getCount());
                assertEquals(0, actions.get());

                releaseAction.countDown();
                generateLease.close();
                assertEquals(1, actions.get());
                assertCanAcquireNewTurn(manager);
            } finally {
                releaseAction.countDown();
                generateLease.close();
            }
        }
    }

    @Test
    void errorStartingDispatchDoesNotLeakOperationState() {
        AtomicInteger actions = new AtomicInteger();
        AssertionError startFailure = new AssertionError("启动错误");
        AppOperationLeaseManager manager = new AppOperationLeaseManager(
                null, task -> { throw startFailure; });
        var generateLease = manager.acquire(7L, AppOperationType.GENERATE, "turn-1");
        generateLease.registerCancellation(actions::incrementAndGet);

        AssertionError thrown = assertThrows(
                AssertionError.class,
                () -> manager.cancelAndAcquireDelete(
                        7L, "delete-1", Duration.ofSeconds(1)));

        assertSame(startFailure, thrown);
        assertEquals(0, actions.get());
        generateLease.close();
        assertEquals(1, actions.get());
        assertCanAcquireNewTurn(manager);
    }

    @Test
    void cancellationErrorStillRunsRemainingActionsAndPreventsDeleteReplacement() {
        AtomicInteger laterActions = new AtomicInteger();
        AssertionError actionFailure = new AssertionError("动作错误");
        AppOperationLeaseManager manager = new AppOperationLeaseManager();
        var generateLease = manager.acquire(7L, AppOperationType.GENERATE, "turn-1");
        generateLease.registerCancellation(() -> { throw actionFailure; });
        generateLease.registerCancellation(laterActions::incrementAndGet);

        AssertionError thrown = assertThrows(
                AssertionError.class,
                () -> manager.cancelAndAcquireDelete(
                        7L, "delete-1", Duration.ofSeconds(1)));

        assertSame(actionFailure, thrown);
        assertEquals(1, laterActions.get());
        assertThrows(AppOperationLeaseManager.ActiveAppOperationException.class,
                () -> manager.acquire(7L, AppOperationType.DELETE, "delete-2"));
        generateLease.close();
        assertCanAcquireNewTurn(manager);
    }

    @Test
    void repeatedSameCancellationErrorStillRunsRemainingActions() {
        AtomicInteger laterActions = new AtomicInteger();
        AssertionError actionFailure = new AssertionError("重复动作错误");
        AppOperationLeaseManager manager = new AppOperationLeaseManager();
        var generateLease = manager.acquire(7L, AppOperationType.GENERATE, "turn-1");
        generateLease.registerCancellation(() -> { throw actionFailure; });
        generateLease.registerCancellation(() -> { throw actionFailure; });
        generateLease.registerCancellation(laterActions::incrementAndGet);

        AssertionError thrown = assertThrows(
                AssertionError.class,
                () -> manager.cancelAndAcquireDelete(
                        7L, "delete-1", Duration.ofSeconds(1)));

        assertSame(actionFailure, thrown);
        assertEquals(1, laterActions.get());
        generateLease.close();
        assertCanAcquireNewTurn(manager);
    }

    @Test
    void startFailureDefersActionFailureUntilOwnerClose() {
        IllegalStateException startFailure = new IllegalStateException("启动失败");
        AssertionError actionFailure = new AssertionError("动作失败");
        AppOperationLeaseManager manager = new AppOperationLeaseManager(
                null, task -> { throw startFailure; });
        var generateLease = manager.acquire(7L, AppOperationType.GENERATE, "turn-1");
        generateLease.registerCancellation(() -> { throw actionFailure; });

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> manager.cancelAndAcquireDelete(
                        7L, "delete-1", Duration.ofSeconds(1)));

        assertSame(startFailure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        AssertionError closeFailure = assertThrows(
                AssertionError.class, generateLease::close);
        assertSame(actionFailure, closeFailure);
        assertCanAcquireNewTurn(manager);
    }

    @Test
    void failedDispatchStartDoesNotRestoreCancelledRegistration() {
        AtomicInteger actions = new AtomicInteger();
        AtomicInteger dispatchStarts = new AtomicInteger();
        AtomicReference<AppOperationLeaseManager.CancellationRegistration> registration =
                new AtomicReference<>();
        IllegalStateException startFailure = new IllegalStateException("启动失败");
        AppOperationLeaseManager manager = new AppOperationLeaseManager(null, task -> {
            if (dispatchStarts.incrementAndGet() == 1) {
                registration.get().close();
                throw startFailure;
            }
            task.run();
        });
        var generateLease = manager.acquire(7L, AppOperationType.GENERATE, "turn-1");
        registration.set(generateLease.registerCancellation(actions::incrementAndGet));

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> manager.cancelAndAcquireDelete(
                        7L, "delete-1", Duration.ofSeconds(1)));

        assertSame(startFailure, thrown);
        try (var deleteLease = manager.cancelAndAcquireDelete(
                7L, "delete-2", Duration.ofSeconds(1))) {
            assertEquals(AppOperationType.DELETE, deleteLease.operationType());
        }
        assertEquals(1, dispatchStarts.get());
        generateLease.close();
        assertEquals(0, actions.get());
        assertCanAcquireNewTurn(manager);
    }

    @Test
    void ownerCloseWaitsForFailedDispatchStartAndDrainsRestoredAction()
            throws Exception {
        CountDownLatch starterEntered = new CountDownLatch(1);
        CountDownLatch releaseStarter = new CountDownLatch(1);
        AtomicInteger actions = new AtomicInteger();
        AtomicReference<Thread> ownerCloseThread = new AtomicReference<>();
        IllegalStateException startFailure = new IllegalStateException("启动失败");
        AppOperationLeaseManager manager = new AppOperationLeaseManager(null, task -> {
            starterEntered.countDown();
            awaitUnchecked(releaseStarter);
            throw startFailure;
        });
        var generateLease = manager.acquire(7L, AppOperationType.GENERATE, "turn-1");
        generateLease.registerCancellation(actions::incrementAndGet);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> delete = executor.submit(() -> manager.cancelAndAcquireDelete(
                    7L, "delete-1", Duration.ofSeconds(1)));
            assertTrue(starterEntered.await(1, TimeUnit.SECONDS));
            Future<?> ownerClose = executor.submit(() -> {
                ownerCloseThread.set(Thread.currentThread());
                generateLease.close();
            });
            awaitThreadWaiting(ownerCloseThread);
            assertFalse(ownerClose.isDone());

            releaseStarter.countDown();
            ExecutionException exception = assertThrows(
                    ExecutionException.class,
                    () -> delete.get(300, TimeUnit.MILLISECONDS));
            assertSame(startFailure, exception.getCause());
            ownerClose.get(1, TimeUnit.SECONDS);
            assertEquals(1, actions.get());
            assertCanAcquireNewTurn(manager);
        } finally {
            releaseStarter.countDown();
            generateLease.close();
        }
    }

    @Test
    void interruptedOwnerClosePreservesInterruptWhileDrainingRestoredAction()
            throws Exception {
        CountDownLatch starterEntered = new CountDownLatch(1);
        CountDownLatch releaseStarter = new CountDownLatch(1);
        AtomicInteger actions = new AtomicInteger();
        AtomicReference<Thread> ownerCloseThread = new AtomicReference<>();
        IllegalStateException startFailure = new IllegalStateException("启动失败");
        AppOperationLeaseManager manager = new AppOperationLeaseManager(null, task -> {
            starterEntered.countDown();
            awaitUnchecked(releaseStarter);
            throw startFailure;
        });
        var generateLease = manager.acquire(7L, AppOperationType.GENERATE, "turn-1");
        generateLease.registerCancellation(actions::incrementAndGet);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> delete = executor.submit(() -> manager.cancelAndAcquireDelete(
                    7L, "delete-1", Duration.ofSeconds(1)));
            assertTrue(starterEntered.await(1, TimeUnit.SECONDS));
            Future<Boolean> ownerClose = executor.submit(() -> {
                ownerCloseThread.set(Thread.currentThread());
                Thread.currentThread().interrupt();
                generateLease.close();
                return Thread.currentThread().isInterrupted();
            });
            awaitThreadWaiting(ownerCloseThread);
            assertFalse(ownerClose.isDone());

            releaseStarter.countDown();
            ExecutionException exception = assertThrows(
                    ExecutionException.class,
                    () -> delete.get(300, TimeUnit.MILLISECONDS));
            assertSame(startFailure, exception.getCause());
            assertTrue(ownerClose.get(1, TimeUnit.SECONDS));
            assertEquals(1, actions.get());
            assertCanAcquireNewTurn(manager);
        } finally {
            releaseStarter.countDown();
            generateLease.close();
        }
    }

    @Test
    void lateRegistrationFailurePreventsDeleteReplacement() throws Exception {
        AssertionError actionFailure = new AssertionError("晚注册动作失败");
        AppOperationLeaseManager manager = new AppOperationLeaseManager();
        var generateLease = manager.acquire(7L, AppOperationType.GENERATE, "turn-1");
        var callback = generateLease.enterCallback();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> delete = executor.submit(() -> manager.cancelAndAcquireDelete(
                    7L, "delete-1", Duration.ofSeconds(1)));
            awaitCancellationGateClosed(generateLease);
            Future<?> registration = executor.submit(() ->
                    generateLease.registerCancellation(() -> { throw actionFailure; }));

            ExecutionException registrationException = assertThrows(
                    ExecutionException.class,
                    () -> registration.get(1, TimeUnit.SECONDS));
            assertSame(actionFailure, registrationException.getCause());
            callback.close();

            ExecutionException deleteException = assertThrows(
                    ExecutionException.class,
                    () -> delete.get(1, TimeUnit.SECONDS));
            assertSame(actionFailure, deleteException.getCause());
            assertThrows(AppOperationLeaseManager.ActiveAppOperationException.class,
                    () -> manager.acquire(7L, AppOperationType.DELETE, "delete-2"));
            generateLease.close();
            assertCanAcquireNewTurn(manager);
        } finally {
            callback.close();
            generateLease.close();
        }
    }

    @Test
    void sameEarlyAndLateCancellationFailureDoesNotSelfSuppress() throws Exception {
        AssertionError actionFailure = new AssertionError("重复取消失败");
        CountDownLatch earlyActionFinished = new CountDownLatch(1);
        AppOperationLeaseManager manager = new AppOperationLeaseManager();
        var generateLease = manager.acquire(7L, AppOperationType.GENERATE, "turn-1");
        var callback = generateLease.enterCallback();
        generateLease.registerCancellation(() -> {
            earlyActionFinished.countDown();
            throw actionFailure;
        });

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> delete = executor.submit(() -> manager.cancelAndAcquireDelete(
                    7L, "delete-1", Duration.ofSeconds(1)));
            assertTrue(earlyActionFinished.await(1, TimeUnit.SECONDS));
            Future<?> registration = executor.submit(() ->
                    generateLease.registerCancellation(() -> { throw actionFailure; }));
            ExecutionException registrationException = assertThrows(
                    ExecutionException.class,
                    () -> registration.get(1, TimeUnit.SECONDS));
            assertSame(actionFailure, registrationException.getCause());
            callback.close();

            ExecutionException deleteException = assertThrows(
                    ExecutionException.class,
                    () -> delete.get(1, TimeUnit.SECONDS));
            assertSame(actionFailure, deleteException.getCause());
            assertEquals(0, actionFailure.getSuppressed().length);
            generateLease.close();
            assertCanAcquireNewTurn(manager);
        } finally {
            callback.close();
            generateLease.close();
        }
    }

    private void assertRegistrationRejected(Future<?> registration) throws Exception {
        ExecutionException exception = assertThrows(
                ExecutionException.class,
                () -> registration.get(1, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, exception.getCause());
    }

    private void assertCanAcquireNewTurn(AppOperationLeaseManager manager) {
        try (var ignored = manager.acquire(
                7L, AppOperationType.GENERATE, "turn-next")) {
            // 能领取即证明旧操作状态已经释放。
        }
    }

    private void awaitThreadWaiting(AtomicReference<Thread> threadReference) {
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        Thread thread;
        while (((thread = threadReference.get()) == null
                || thread.getState() != Thread.State.WAITING)
                && System.nanoTime() < deadlineNanos) {
            Thread.onSpinWait();
        }
        assertTrue(thread != null && thread.getState() == Thread.State.WAITING);
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
