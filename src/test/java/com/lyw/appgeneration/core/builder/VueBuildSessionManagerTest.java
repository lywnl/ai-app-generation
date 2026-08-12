package com.lyw.appgeneration.core.builder;

import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager.AppOperationType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VueBuildSessionManagerTest {

    @Test
    void thirdFailureMustBecomeTerminalAndRejectFurtherBuilds() {
        AppOperationLeaseManager operationManager = new AppOperationLeaseManager();
        VueBuildSessionManager manager = new VueBuildSessionManager();
        try (var operationLease = operationManager.acquire(
                7L, AppOperationType.GENERATE, "turn-1");
             VueBuildSessionManager.VueBuildLease lease =
                     manager.open(operationLease, 9L, "turn-1")) {
            try (var attempt1 = lease.beginBuild()) {
                assertEquals(VueBuildPhase.REPAIRING,
                        lease.recordFailure(attempt1, failure(BuildStage.NPM_BUILD)).phase());
            }
            try (var attempt2 = lease.beginBuild()) {
                assertEquals(VueBuildPhase.FINAL_DIAGNOSIS,
                        lease.recordFailure(attempt2, failure(BuildStage.NPM_BUILD)).phase());
            }
            try (var attempt3 = lease.beginBuild()) {
                assertEquals(VueBuildPhase.FAILED,
                        lease.recordFailure(attempt3, failure(BuildStage.NPM_BUILD)).phase());
            }
            assertFalse(lease.canBuild());
            assertThrows(IllegalStateException.class, lease::beginBuild);
        }
    }

    @Test
    void sameAppMustNotAcquireTwoActiveLeases() {
        AppOperationLeaseManager manager = new AppOperationLeaseManager();
        try (var ignored = manager.acquire(7L, AppOperationType.GENERATE, "turn-1")) {
            assertThrows(AppOperationLeaseManager.ActiveAppOperationException.class,
                    () -> manager.acquire(7L, AppOperationType.DEPLOY, "deploy-1"));
        }
    }

    @Test
    void cancelledLeaseMustWaitForInFlightCallbackBeforeNextTurnCanAcquire() throws Exception {
        AppOperationLeaseManager operationManager = new AppOperationLeaseManager();
        VueBuildSessionManager manager = new VueBuildSessionManager();
        var operationLease = operationManager.acquire(7L, AppOperationType.GENERATE, "turn-1");
        VueBuildSessionManager.VueBuildLease lease = manager.open(operationLease, 9L, "turn-1");
        AutoCloseable callback = lease.enterCallback();

        lease.cancel();
        assertFalse(lease.awaitQuiescence(Duration.ofMillis(10)));
        assertThrows(AppOperationLeaseManager.ActiveAppOperationException.class,
                () -> operationManager.acquire(7L, AppOperationType.GENERATE, "turn-2"));

        callback.close();
        assertTrue(lease.awaitQuiescence(Duration.ofSeconds(1)));
        lease.close();
        operationLease.close();
        assertDoesNotThrow(() -> operationManager.acquire(
                7L, AppOperationType.GENERATE, "turn-2").close());
    }

    @Test
    void concurrentAcquisitionAllowsOnlyOneOperationPerApp() throws Exception {
        AppOperationLeaseManager manager = new AppOperationLeaseManager();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<AppOperationLeaseManager.AppOperationLease>> futures = List.of(
                    executor.submit(() -> acquireAfter(start, manager, AppOperationType.GENERATE)),
                    executor.submit(() -> acquireAfter(start, manager, AppOperationType.DEPLOY)));
            start.countDown();
            int successes = 0;
            AppOperationLeaseManager.AppOperationLease acquiredLease = null;
            for (Future<AppOperationLeaseManager.AppOperationLease> future : futures) {
                try {
                    acquiredLease = future.get(1, TimeUnit.SECONDS);
                    successes++;
                } catch (java.util.concurrent.ExecutionException exception) {
                    assertTrue(exception.getCause()
                            instanceof AppOperationLeaseManager.ActiveAppOperationException);
                }
            }
            assertEquals(1, successes);
            acquiredLease.close();
        }
    }

    @Test
    void differentAppsCanAcquireInParallelAndAllOperationPairsConflict() {
        AppOperationLeaseManager manager = new AppOperationLeaseManager();
        try (var first = manager.acquire(7L, AppOperationType.GENERATE, "turn-1");
             var second = manager.acquire(8L, AppOperationType.DEPLOY, "deploy-2")) {
            assertEquals(7L, first.appId());
            assertEquals(8L, second.appId());
        }
        try (var download = manager.acquire(7L, AppOperationType.DOWNLOAD, "download-1")) {
            assertThrows(AppOperationLeaseManager.ActiveAppOperationException.class,
                    () -> manager.acquire(7L, AppOperationType.DELETE, "delete-1"));
        }
    }

    @Test
    void buildAttemptReservationIsUniqueAndConcurrentBuildIsRejected() throws Exception {
        AppOperationLeaseManager operationManager = new AppOperationLeaseManager();
        VueBuildSessionManager manager = new VueBuildSessionManager();
        try (var operation = operationManager.acquire(7L, AppOperationType.GENERATE, "turn-1");
             var lease = manager.open(operation, 9L, "turn-1");
             var first = lease.beginBuild()) {
            assertEquals(1, first.attempt());
            assertThrows(VueBuildSessionManager.BuildInProgressException.class,
                    lease::beginBuild);
            lease.recordFailure(first, failure(BuildStage.NPM_BUILD));
            try (var second = lease.beginBuild()) {
                assertEquals(2, second.attempt());
            }
            assertEquals(2, lease.snapshot().buildAttempt());
            assertEquals(VueBuildFailureKind.INFRASTRUCTURE,
                    lease.snapshot().failureKind());
        }
    }

    @Test
    void concurrentBeginBuildReservesExactlyOneTicket() throws Exception {
        AppOperationLeaseManager operationManager = new AppOperationLeaseManager();
        VueBuildSessionManager manager = new VueBuildSessionManager();
        try (var operation = operationManager.acquire(7L, AppOperationType.GENERATE, "turn-1");
             var lease = manager.open(operation, 9L, "turn-1");
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<VueBuildSessionManager.BuildAttemptTicket>> futures = List.of(
                    executor.submit(() -> beginAfter(start, lease)),
                    executor.submit(() -> beginAfter(start, lease)));
            start.countDown();
            VueBuildSessionManager.BuildAttemptTicket acquiredTicket = null;
            int successes = 0;
            for (Future<VueBuildSessionManager.BuildAttemptTicket> future : futures) {
                try {
                    acquiredTicket = future.get(1, TimeUnit.SECONDS);
                    successes++;
                } catch (java.util.concurrent.ExecutionException exception) {
                    assertTrue(exception.getCause()
                            instanceof VueBuildSessionManager.BuildInProgressException);
                }
            }
            assertEquals(1, successes);
            assertEquals(1, acquiredTicket.attempt());
            acquiredTicket.close();
            assertEquals(1, lease.snapshot().buildAttempt());
        }
    }

    @Test
    void completedTicketUnregistersOldCancellationBeforeThreadReuse() {
        AppOperationLeaseManager operationManager = new AppOperationLeaseManager();
        VueBuildSessionManager manager = new VueBuildSessionManager();
        AtomicInteger oldCancellation = new AtomicInteger();
        AtomicInteger currentCancellation = new AtomicInteger();
        try (var operation = operationManager.acquire(7L, AppOperationType.GENERATE, "turn-1");
             var lease = manager.open(operation, 9L, "turn-1")) {
            try (var first = lease.beginBuild()) {
                first.registerCancellation(oldCancellation::incrementAndGet);
                lease.recordFailure(first, failure(BuildStage.NPM_BUILD));
            }
            try (var second = lease.beginBuild()) {
                second.registerCancellation(currentCancellation::incrementAndGet);
                lease.cancel();
                assertEquals(0, oldCancellation.get());
                assertEquals(1, currentCancellation.get());
            }
        }
    }

    @Test
    void ticketCompletionCancelsPendingActionAlreadyCopiedForCancellation() throws Exception {
        AppOperationLeaseManager operationManager = new AppOperationLeaseManager();
        VueBuildSessionManager manager = new VueBuildSessionManager();
        CountDownLatch firstActionStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstAction = new CountDownLatch(1);
        AtomicInteger oldTicketCancellation = new AtomicInteger();
        try (var operation = operationManager.acquire(7L, AppOperationType.GENERATE, "turn-1");
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            operation.registerCancellation(() -> {
                firstActionStarted.countDown();
                awaitUnchecked(releaseFirstAction);
            });
            try (var lease = manager.open(operation, 9L, "turn-1");
                 var ticket = lease.beginBuild()) {
                ticket.registerCancellation(oldTicketCancellation::incrementAndGet);
                Future<Boolean> cancellation = executor.submit(operation::requestCancellation);

                try {
                    assertTrue(firstActionStarted.await(1, TimeUnit.SECONDS));
                    lease.recordFailure(ticket, failure(BuildStage.NPM_BUILD));
                } finally {
                    releaseFirstAction.countDown();
                }

                assertTrue(cancellation.get(1, TimeUnit.SECONDS));
                assertEquals(0, oldTicketCancellation.get());
            }
        }
    }

    @Test
    void runningCancellationKeepsClosedOperationOccupiedUntilActionFinishes() throws Exception {
        AppOperationLeaseManager operationManager = new AppOperationLeaseManager();
        CountDownLatch actionStarted = new CountDownLatch(1);
        CountDownLatch releaseAction = new CountDownLatch(1);
        var operation = operationManager.acquire(7L, AppOperationType.GENERATE, "turn-1");
        operation.registerCancellation(() -> {
            actionStarted.countDown();
            awaitUnchecked(releaseAction);
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> cancellation = executor.submit(operation::requestCancellation);
            assertTrue(actionStarted.await(1, TimeUnit.SECONDS));

            try {
                operation.close();
                assertThrows(AppOperationLeaseManager.ActiveAppOperationException.class,
                        () -> operationManager.acquire(
                                7L, AppOperationType.GENERATE, "turn-2"));
            } finally {
                releaseAction.countDown();
            }
            assertTrue(cancellation.get(1, TimeUnit.SECONDS));
        }
        assertDoesNotThrow(() -> operationManager.acquire(
                7L, AppOperationType.GENERATE, "turn-2").close());
    }

    @Test
    void deleteTakeoverCancellationFailureDoesNotPermanentlyLockManager() {
        AppOperationLeaseManager operationManager = new AppOperationLeaseManager();
        VueBuildSessionManager manager = new VueBuildSessionManager();
        try (var operation = operationManager.acquire(7L, AppOperationType.GENERATE, "turn-1");
             var lease = manager.open(operation, 9L, "turn-1")) {
            lease.registerModelCancellation(() -> {
                throw new IllegalStateException("取消回调失败");
            });
            assertThrows(IllegalStateException.class,
                    () -> operationManager.cancelAndAcquireDelete(
                            7L, "delete-1", Duration.ofSeconds(1)));
            assertDoesNotThrow(() -> operationManager.cancelAndAcquireDelete(
                    7L, "delete-2", Duration.ofSeconds(1)).close());
        }
        assertDoesNotThrow(() -> operationManager.acquire(
                7L, AppOperationType.DELETE, "delete-3").close());
    }

    @Test
    void ownerCloseCancellationFailuresRunAllActionsAndReleaseOperation() {
        AppOperationLeaseManager operationManager = new AppOperationLeaseManager();
        AtomicInteger cancellations = new AtomicInteger();
        var operation = operationManager.acquire(7L, AppOperationType.GENERATE, "turn-1");
        operation.registerCancellation(() -> {
            cancellations.incrementAndGet();
            throw new IllegalStateException("first");
        });
        operation.registerCancellation(cancellations::incrementAndGet);

        assertThrows(IllegalStateException.class, operation::close);
        assertEquals(2, cancellations.get());
        assertDoesNotThrow(() -> operationManager.acquire(
                7L, AppOperationType.GENERATE, "turn-2").close());
    }

    @Test
    void requestCancellationFailureStillClosesGateAndRunsAllActionsOnce() {
        AppOperationLeaseManager operationManager = new AppOperationLeaseManager();
        AtomicInteger cancellations = new AtomicInteger();
        try (var operation = operationManager.acquire(
                7L, AppOperationType.GENERATE, "turn-1")) {
            operation.registerCancellation(() -> {
                cancellations.incrementAndGet();
                throw new IllegalStateException("first");
            });
            operation.registerCancellation(cancellations::incrementAndGet);

            assertThrows(IllegalStateException.class, operation::requestCancellation);
            assertEquals(2, cancellations.get());
            assertThrows(IllegalStateException.class, operation::enterCallback);
            assertFalse(operation.requestCancellation());
            assertEquals(2, cancellations.get());
        }
    }

    @Test
    void ticketsCannotBeReusedOrCrossLeaseBoundaries() {
        AppOperationLeaseManager operationManager = new AppOperationLeaseManager();
        VueBuildSessionManager manager = new VueBuildSessionManager();
        try (var operation1 = operationManager.acquire(7L, AppOperationType.GENERATE, "turn-1");
             var operation2 = operationManager.acquire(8L, AppOperationType.GENERATE, "turn-2");
             var lease1 = manager.open(operation1, 9L, "turn-1");
             var lease2 = manager.open(operation2, 9L, "turn-2");
             var ticket = lease1.beginBuild()) {
            assertThrows(IllegalArgumentException.class,
                    () -> lease2.recordFailure(ticket, failure(BuildStage.NPM_BUILD)));
            assertEquals(VueBuildPhase.SUCCEEDED,
                    lease1.recordSuccess(ticket, success()).phase());
            assertThrows(IllegalStateException.class,
                    () -> lease1.recordSuccess(ticket, success()));
            assertEquals(1, lease1.snapshot().buildAttempt());
        }
    }

    @Test
    void cancelledLeaseRunsEachCancellationOnceAndRejectsNewWork() {
        AppOperationLeaseManager operationManager = new AppOperationLeaseManager();
        VueBuildSessionManager manager = new VueBuildSessionManager();
        AtomicInteger modelCancellations = new AtomicInteger();
        AtomicInteger buildCancellations = new AtomicInteger();
        try (var operation = operationManager.acquire(7L, AppOperationType.GENERATE, "turn-1");
             var lease = manager.open(operation, 9L, "turn-1");
             var ticket = lease.beginBuild()) {
            lease.registerModelCancellation(modelCancellations::incrementAndGet);
            ticket.registerCancellation(buildCancellations::incrementAndGet);

            assertTrue(lease.cancel());
            assertFalse(lease.cancel());

            assertEquals(1, modelCancellations.get());
            assertEquals(1, buildCancellations.get());
            assertEquals(VueBuildPhase.CANCELLED, lease.snapshot().phase());
            assertThrows(IllegalStateException.class, lease::beginBuild);
            assertThrows(IllegalStateException.class, lease::enterCallback);
        }
    }

    @Test
    void cancellationRegisteredAfterCancelRunsImmediatelyOnlyOnce() {
        AppOperationLeaseManager operationManager = new AppOperationLeaseManager();
        VueBuildSessionManager manager = new VueBuildSessionManager();
        AtomicInteger cancellations = new AtomicInteger();
        try (var operation = operationManager.acquire(7L, AppOperationType.GENERATE, "turn-1");
             var lease = manager.open(operation, 9L, "turn-1");
             var ticket = lease.beginBuild()) {
            lease.cancel();
            ticket.registerCancellation(cancellations::incrementAndGet);
            assertEquals(1, cancellations.get());
            assertThrows(IllegalStateException.class,
                    () -> ticket.registerCancellation(cancellations::incrementAndGet));
        }
    }

    @Test
    void failureClassificationComesFromTrustedBuildResult() {
        AppOperationLeaseManager operationManager = new AppOperationLeaseManager();
        VueBuildSessionManager manager = new VueBuildSessionManager();
        try (var operation = operationManager.acquire(7L, AppOperationType.GENERATE, "turn-1");
             var lease = manager.open(operation, 9L, "turn-1")) {
            try (var first = lease.beginBuild()) {
                assertEquals(VueBuildFailureKind.DEPENDENCY,
                        lease.recordFailure(first, failure(BuildStage.NPM_INSTALL)).failureKind());
            }
            try (var second = lease.beginBuild()) {
                assertEquals(VueBuildFailureKind.INFRASTRUCTURE,
                        lease.recordFailure(second, timedOutFailure()).failureKind());
            }
        }
    }

    @Test
    void openRejectsWrongOperationTypeOwnerAndClosedLease() {
        AppOperationLeaseManager operationManager = new AppOperationLeaseManager();
        VueBuildSessionManager manager = new VueBuildSessionManager();
        try (var deploy = operationManager.acquire(7L, AppOperationType.DEPLOY, "deploy-1")) {
            assertThrows(IllegalArgumentException.class,
                    () -> manager.open(deploy, 9L, "deploy-1"));
        }
        var generate = operationManager.acquire(7L, AppOperationType.GENERATE, "turn-1");
        assertThrows(IllegalArgumentException.class,
                () -> manager.open(generate, 9L, "forged-turn"));
        generate.close();
        assertThrows(IllegalStateException.class,
                () -> manager.open(generate, 9L, "turn-1"));
    }

    @Test
    void closingOldLeaseCannotDeleteReplacementDeleteLease() throws Exception {
        AppOperationLeaseManager operationManager = new AppOperationLeaseManager();
        VueBuildSessionManager manager = new VueBuildSessionManager();
        var generate = operationManager.acquire(7L, AppOperationType.GENERATE, "turn-1");
        var vue = manager.open(generate, 9L, "turn-1");

        var delete = operationManager.cancelAndAcquireDelete(
                7L, "delete-1", Duration.ofSeconds(1));
        generate.close();

        assertEquals(AppOperationType.DELETE, delete.operationType());
        assertThrows(AppOperationLeaseManager.ActiveAppOperationException.class,
                () -> operationManager.acquire(7L, AppOperationType.GENERATE, "turn-2"));
        vue.close();
        delete.close();
        assertDoesNotThrow(() -> operationManager.acquire(
                7L, AppOperationType.GENERATE, "turn-2").close());
    }

    @Test
    void deleteTakeoverTimesOutWithoutReleasingSourceOperation() throws Exception {
        AppOperationLeaseManager operationManager = new AppOperationLeaseManager();
        VueBuildSessionManager manager = new VueBuildSessionManager();
        try (var generate = operationManager.acquire(7L, AppOperationType.GENERATE, "turn-1");
             var vue = manager.open(generate, 9L, "turn-1");
             var callback = vue.enterCallback()) {
            assertThrows(AppOperationLeaseManager.OperationQuiescenceTimeoutException.class,
                    () -> operationManager.cancelAndAcquireDelete(
                            7L, "delete-1", Duration.ofMillis(10)));
            assertEquals(VueBuildPhase.CANCELLED, vue.snapshot().phase());
            assertThrows(AppOperationLeaseManager.ActiveAppOperationException.class,
                    () -> operationManager.acquire(7L, AppOperationType.DELETE, "delete-2"));
        }
    }

    @Test
    void oldCallbackCannotEnterAgainOrAffectNewTurn() throws Exception {
        AppOperationLeaseManager operationManager = new AppOperationLeaseManager();
        VueBuildSessionManager manager = new VueBuildSessionManager();
        var operation = operationManager.acquire(7L, AppOperationType.GENERATE, "turn-1");
        var oldLease = manager.open(operation, 9L, "turn-1");
        AutoCloseable callback = oldLease.enterCallback();
        oldLease.cancel();
        callback.close();
        oldLease.close();
        operation.close();

        try (var nextOperation = operationManager.acquire(
                7L, AppOperationType.GENERATE, "turn-2");
             var nextLease = manager.open(nextOperation, 9L, "turn-2")) {
            assertThrows(IllegalStateException.class, oldLease::enterCallback);
            assertEquals(VueBuildPhase.GENERATING, nextLease.snapshot().phase());
        }
    }

    private AppOperationLeaseManager.AppOperationLease acquireAfter(
            CountDownLatch start,
            AppOperationLeaseManager manager,
            AppOperationType operationType) throws Exception {
        start.await();
        return manager.acquire(7L, operationType, operationType.name());
    }

    private VueBuildSessionManager.BuildAttemptTicket beginAfter(
            CountDownLatch start,
            VueBuildSessionManager.VueBuildLease lease) throws Exception {
        start.await();
        return lease.beginBuild();
    }

    private void awaitUnchecked(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private BuildResult success() {
        return new BuildResult(true, BuildStage.SUCCESS, 0, false, "ok", 1L);
    }

    private BuildResult failure(BuildStage stage) {
        return new BuildResult(false, stage, 1, false, "failed", 1L);
    }

    private BuildResult timedOutFailure() {
        return new BuildResult(false, BuildStage.NPM_BUILD, null, true, "timeout", 1L);
    }
}
