package dev.langchain4j.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static dev.langchain4j.service.StreamingRequestController.GenerationCancellation.CANCELLED;
import static dev.langchain4j.service.StreamingRequestController.GenerationCancellation.REJECTED;
import static dev.langchain4j.service.ToolLoopTerminationProtocol.ControlledTerminationReason.RESOURCE_LIMIT_EXCEEDED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingRequestControllerRecoveryTest {

    @Test
    void 当前代物理取消异常不回滚恢复撤销语义() {
        StreamingRequestController controller = activeController();
        long oldGeneration = controller.latestModelRequestGeneration();
        AtomicInteger lateHandleCancellations = new AtomicInteger();
        AtomicInteger staleActions = new AtomicInteger();
        controller.registerRequestHandle(oldGeneration, () -> {
            throw new RuntimeException("取消底层流失败");
        });

        StreamingRequestController.GenerationCancellation cancellation =
                assertDoesNotThrow(() -> controller
                        .cancelGenerationForRecovery(oldGeneration));

        assertEquals(CANCELLED, cancellation);
        assertTrue(controller.isOpen(), "物理取消失败不能终止整个用户回合");
        assertNull(controller.enterCallback(oldGeneration));
        assertFalse(controller.runIfCurrentGeneration(
                oldGeneration, staleActions::incrementAndGet));
        controller.registerRequestHandle(
                oldGeneration, lateHandleCancellations::incrementAndGet);
        assertEquals(1, lateHandleCancellations.get(), "旧代迟到 handle 必须立即取消");
        assertEquals(0, staleActions.get());
        assertTrue(controller.beforeModelRequest(oldGeneration),
                "刚撤销的旧代仍应能推进一次恢复请求");
    }

    @Test
    void 撤销当前代后整轮保持活动且旧代所有入口失效() {
        StreamingRequestController controller = new StreamingRequestController();
        AtomicInteger activeHandleCancellations = new AtomicInteger();
        AtomicInteger lateHandleCancellations = new AtomicInteger();
        AtomicInteger staleActions = new AtomicInteger();

        assertTrue(controller.beforeModelRequest());
        long firstGeneration = controller.latestModelRequestGeneration();
        assertEquals(1L, firstGeneration);
        controller.registerRequestHandle(
                firstGeneration, activeHandleCancellations::incrementAndGet);

        assertEquals(CANCELLED,
                controller.cancelGenerationForRecovery(firstGeneration));

        assertTrue(controller.isOpen(), "generation 撤销不能终止整个用户回合");
        assertEquals(1, activeHandleCancellations.get());
        assertNull(controller.enterCallback(firstGeneration));
        assertFalse(controller.runIfCurrentGeneration(
                firstGeneration, staleActions::incrementAndGet));
        controller.registerRequestHandle(
                firstGeneration, lateHandleCancellations::incrementAndGet);
        assertEquals(1, lateHandleCancellations.get(), "旧代迟到 handle 必须立即取消");
        assertEquals(0, staleActions.get());
        assertEquals(REJECTED,
                controller.cancelGenerationForRecovery(firstGeneration));
        assertEquals(REJECTED,
                controller.cancelGenerationForRecovery(firstGeneration + 1));
    }

    @Test
    void 旧代未来代和撤销代均不能进入回调执行动作或保留句柄() {
        StreamingRequestController oldGenerationController = activeController();
        long oldGeneration = oldGenerationController
                .latestModelRequestGeneration();
        assertTrue(oldGenerationController.beforeModelRequest());
        assertRejectedAtEveryGenerationEntry(
                oldGenerationController, oldGeneration);

        StreamingRequestController futureGenerationController =
                activeController();
        long futureGeneration = futureGenerationController
                .latestModelRequestGeneration() + 1L;
        assertRejectedAtEveryGenerationEntry(
                futureGenerationController, futureGeneration);

        StreamingRequestController cancelledGenerationController =
                activeController();
        long cancelledGeneration = cancelledGenerationController
                .latestModelRequestGeneration();
        assertEquals(CANCELLED, cancelledGenerationController
                .cancelGenerationForRecovery(cancelledGeneration));
        assertRejectedAtEveryGenerationEntry(
                cancelledGenerationController, cancelledGeneration);
    }

    @Test
    void 恢复请求成功启动下一代并计入模型请求总数() {
        StreamingRequestController controller = activeController();
        long firstGeneration = controller.latestModelRequestGeneration();
        assertEquals(CANCELLED,
                controller.cancelGenerationForRecovery(firstGeneration));

        assertTrue(controller.beforeModelRequest(firstGeneration));
        long recoveryGeneration = controller.latestModelRequestGeneration();

        assertEquals(2L, recoveryGeneration);
        assertEquals(2, controller.modelRequestCount());
        assertFalse(controller.beforeModelRequest(firstGeneration),
                "同一旧代只能启动一次恢复请求，不能错误推进第三代");
        assertEquals(2, controller.modelRequestCount());
        assertTrue(controller.runIfCurrentGeneration(
                recoveryGeneration, () -> { }));
        try (StreamingRequestController.CallbackTicket ignored =
                     controller.enterCallback(recoveryGeneration)) {
            assertTrue(controller.isOpen());
        }
    }

    @Test
    void 终态先获胜时恢复撤销一律拒绝() {
        StreamingRequestController cancelled = activeController();
        long cancelledGeneration = cancelled.latestModelRequestGeneration();
        cancelled.cancel();
        assertEquals(REJECTED,
                cancelled.cancelGenerationForRecovery(cancelledGeneration));

        StreamingRequestController terminated = activeController();
        long terminatedGeneration = terminated.latestModelRequestGeneration();
        assertTrue(terminated.terminate(new ToolLoopTerminationProtocol
                .ControlledTermination(RESOURCE_LIMIT_EXCEEDED, null)));
        assertEquals(REJECTED,
                terminated.cancelGenerationForRecovery(terminatedGeneration));

        StreamingRequestController completed = activeController();
        long completedGeneration = completed.latestModelRequestGeneration();
        assertTrue(completed.completeNormally());
        assertEquals(REJECTED,
                completed.cancelGenerationForRecovery(completedGeneration));

        StreamingRequestController normalCompleting = activeController();
        long normalCompletingGeneration =
                normalCompleting.latestModelRequestGeneration();
        assertTrue(normalCompleting.claimNormalCompletion());
        assertEquals(REJECTED, normalCompleting.cancelGenerationForRecovery(
                normalCompletingGeneration));
    }

    @Test
    void 恢复撤销先获胜后旧代不能再发起第二次恢复() {
        StreamingRequestController controller = activeController();
        long generation = controller.latestModelRequestGeneration();

        assertEquals(CANCELLED,
                controller.cancelGenerationForRecovery(generation));
        assertEquals(REJECTED,
                controller.cancelGenerationForRecovery(generation));
        assertTrue(controller.completeNormally(), "回合保持 ACTIVE 后仍可正常完成");
    }

    @Test
    void 当前代回调打开时撤销可线性化且后续终态仍等待回调排空() {
        StreamingRequestController controller = activeController();
        long generation = controller.latestModelRequestGeneration();
        AtomicInteger notifications = new AtomicInteger();
        controller.onControlledTermination(
                ignored -> notifications.incrementAndGet());
        StreamingRequestController.CallbackTicket callback =
                controller.enterCallback(generation);

        assertEquals(CANCELLED,
                controller.cancelGenerationForRecovery(generation));
        assertTrue(controller.isOpen());
        controller.cancel();
        assertEquals(0, notifications.get(), "终态通知必须继续等待已登记回调退出");
        callback.close();
        assertEquals(1, notifications.get());
    }

    @Test
    void 两个恢复线程同时起跑时只有一个成功() throws Exception {
        for (int iteration = 0; iteration < 100; iteration++) {
            StreamingRequestController controller = activeController();
            long generation = controller.latestModelRequestGeneration();
            RaceResult<StreamingRequestController.GenerationCancellation,
                    StreamingRequestController.GenerationCancellation> race =
                    race(
                            () -> controller.cancelGenerationForRecovery(
                                    generation),
                            () -> controller.cancelGenerationForRecovery(
                                    generation));

            assertEquals(1, java.util.stream.Stream.of(
                            race.first(), race.second())
                    .filter(CANCELLED::equals)
                    .count());
            assertTrue(controller.isOpen());
        }
    }

    @Test
    void 普通完成与恢复通过当前代原子门竞争时只有一个成功() throws Exception {
        for (int iteration = 0; iteration < 100; iteration++) {
            StreamingRequestController controller = activeController();
            long generation = controller.latestModelRequestGeneration();
            java.util.concurrent.atomic.AtomicBoolean completionClaimed =
                    new java.util.concurrent.atomic.AtomicBoolean();
            RaceResult<Boolean,
                    StreamingRequestController.GenerationCancellation> race =
                    race(
                            () -> controller.runIfCurrentGeneration(
                                    generation,
                                    () -> completionClaimed.set(
                                            controller.completeNormally())),
                            () -> controller.cancelGenerationForRecovery(
                                    generation));

            assertEquals(1, (race.first() ? 1 : 0)
                    + (race.second() == CANCELLED ? 1 : 0));
            assertEquals(race.first(), completionClaimed.get());
        }
    }

    @Test
    void 受控终止与恢复通过当前代原子门竞争时只有一个成功() throws Exception {
        for (int iteration = 0; iteration < 100; iteration++) {
            StreamingRequestController controller = activeController();
            long generation = controller.latestModelRequestGeneration();
            java.util.concurrent.atomic.AtomicBoolean terminationClaimed =
                    new java.util.concurrent.atomic.AtomicBoolean();
            RaceResult<Boolean,
                    StreamingRequestController.GenerationCancellation> race =
                    race(
                            () -> controller.runIfCurrentGeneration(
                                    generation,
                                    () -> terminationClaimed.set(
                                            controller.claimControlledTermination(
                                                    controlledTermination()))),
                            () -> controller.cancelGenerationForRecovery(
                                    generation));

            if (race.first()) {
                controller.dispatchClaimedTermination();
            }
            assertEquals(1, (race.first() ? 1 : 0)
                    + (race.second() == CANCELLED ? 1 : 0));
            assertEquals(race.first(), terminationClaimed.get());
        }
    }

    @Test
    void 全局取消与恢复竞争后最终取消关闭唯一推进路径() throws Exception {
        for (int iteration = 0; iteration < 100; iteration++) {
            StreamingRequestController controller = activeController();
            long generation = controller.latestModelRequestGeneration();
            RaceResult<Boolean,
                    StreamingRequestController.GenerationCancellation> race =
                    race(
                            () -> {
                                controller.cancel();
                                return controller.isCancelled();
                            },
                            () -> controller.cancelGenerationForRecovery(
                                    generation));

            assertTrue(race.first());
            assertTrue(controller.isCancelled());
            assertFalse(controller.beforeModelRequest(generation));
            assertNull(controller.enterCallback(generation));
            assertFalse(controller.runIfCurrentGeneration(
                    generation, () -> { }));
        }
    }

    @Test
    void 恢复成功后下一代启动前全局取消可覆盖() {
        StreamingRequestController controller = activeController();
        long generation = controller.latestModelRequestGeneration();
        assertEquals(CANCELLED,
                controller.cancelGenerationForRecovery(generation));

        controller.cancel();

        assertTrue(controller.isCancelled());
        assertFalse(controller.beforeModelRequest(generation));
        assertNull(controller.enterCallback(generation));
        assertFalse(controller.runIfCurrentGeneration(
                generation, () -> { }));
    }

    private <F, S> RaceResult<F, S> race(
            java.util.concurrent.Callable<F> first,
            java.util.concurrent.Callable<S> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<F> firstFuture = executor.submit(() -> {
                start.await();
                return first.call();
            });
            Future<S> secondFuture = executor.submit(() -> {
                start.await();
                return second.call();
            });
            start.countDown();
            return new RaceResult<>(
                    firstFuture.get(2, TimeUnit.SECONDS),
                    secondFuture.get(2, TimeUnit.SECONDS));
        }
    }

    private StreamingRequestController activeController() {
        StreamingRequestController controller = new StreamingRequestController();
        assertTrue(controller.beforeModelRequest());
        return controller;
    }

    private void assertRejectedAtEveryGenerationEntry(
            StreamingRequestController controller, long generation) {
        AtomicInteger actions = new AtomicInteger();
        AtomicInteger cancellations = new AtomicInteger();

        assertNull(controller.enterCallback(generation));
        assertFalse(controller.runIfCurrentGeneration(
                generation, actions::incrementAndGet));
        controller.registerRequestHandle(
                generation, cancellations::incrementAndGet);

        assertEquals(0, actions.get());
        assertEquals(1, cancellations.get(), "非当前代 handle 必须立即取消");
    }

    private ToolLoopTerminationProtocol.ControlledTermination
            controlledTermination() {
        return new ToolLoopTerminationProtocol.ControlledTermination(
                RESOURCE_LIMIT_EXCEEDED, null);
    }

    private record RaceResult<F, S>(F first, S second) {
    }
}
