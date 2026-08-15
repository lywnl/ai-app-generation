package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.ai.memory.ContextContinuationGate;
import com.lyw.appgeneration.ai.tools.FileToolBudgetGuard;
import com.lyw.appgeneration.core.builder.VueBuildPhase;
import com.lyw.appgeneration.core.concurrency.VueTurnAdmissionController;
import com.lyw.appgeneration.monitor.VueBuildRepairMetricsCollector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class VueTurnContextTest {

    @Test
    void Vue回合直接实现统一上下文继续门() {
        VueTurnContext context = context("turn-context-gate");
        ContextContinuationGate gate = assertInstanceOf(
                ContextContinuationGate.class, context);
        AtomicBoolean invoked = new AtomicBoolean();

        assertTrue(gate.tryRun(() -> invoked.set(true)));
        context.revokeCallbacks();

        assertTrue(invoked.get());
        assertFalse(gate.tryRun(() -> fail("关门后不得执行晚到动作")));
    }

    @Test
    void 生产构造器必须显式接收不可伪造的准入许可() {
        assertTrue(Arrays.stream(VueTurnContext.class.getConstructors())
                .allMatch(constructor -> Arrays.asList(
                                constructor.getParameterTypes())
                        .contains(VueTurnAdmissionController
                                .AdmissionPermit.class)),
                "公开生产构造器不得绕过全局 Vue 回合准入许可");
    }

    @Test
    void 资源关闭失败仍必须继续释放后续资源和全局许可() {
        List<String> closed = new ArrayList<>();
        IllegalStateException takeoverFailure =
                new IllegalStateException("删除参与者关闭失败");
        IllegalStateException vueFailure =
                new IllegalStateException("Vue 租约关闭失败");
        IllegalStateException operationFailure =
                new IllegalStateException("应用租约关闭失败");

        RuntimeException thrown = assertThrows(
                RuntimeException.class, () -> VueTurnContext.closeAll(
                        () -> {
                            closed.add("takeover");
                            throw takeoverFailure;
                        },
                        () -> {
                            closed.add("vue");
                            throw vueFailure;
                        },
                        () -> {
                            closed.add("operation");
                            throw operationFailure;
                        },
                        () -> closed.add("admission")));

        assertSame(takeoverFailure, thrown);
        assertEquals(List.of("takeover", "vue", "operation", "admission"),
                closed);
        assertEquals(2, thrown.getSuppressed().length);
        assertSame(vueFailure, thrown.getSuppressed()[0]);
        assertSame(operationFailure, thrown.getSuppressed()[1]);
    }

    @Test
    void 真实上下文关闭必须幂等释放全局准入许可() {
        var manager = new com.lyw.appgeneration.core.concurrency
                .AppOperationLeaseManager();
        var operation = manager.acquire(7L,
                com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager
                        .AppOperationType.GENERATE,
                "turn-admission-release");
        var vueLease = new com.lyw.appgeneration.core.builder
                .VueBuildSessionManager().open(
                operation, 9L, "turn-admission-release");
        var admission = new VueTurnAdmissionController(
                new VueBuildRepairMetricsCollector(new SimpleMeterRegistry()));
        var permit = admission.tryAcquire().orElseThrow();
        List<VueTurnAdmissionController.AdmissionPermit> otherPermits =
                new ArrayList<>();
        for (int index = 1;
                index < VueTurnAdmissionController.MAX_ACTIVE_TURNS;
                index++) {
            otherPermits.add(admission.tryAcquire().orElseThrow());
        }
        VueTurnContext context = new VueTurnContext(
                7L, 9L, "turn-admission-release", operation, vueLease, permit,
                new FileToolBudgetGuard().newSession());

        context.closeResources();
        context.closeResources();

        VueTurnAdmissionController.AdmissionPermit replacement = admission
                .tryAcquire().orElseThrow();
        assertTrue(admission.tryAcquire().isEmpty(),
                "重复关闭上下文不得多释放准入容量");
        replacement.close();
        otherPermits.forEach(VueTurnAdmissionController.AdmissionPermit::close);
    }

    @Test
    void 用户提交与预提交终止必须共享同一状态门协议() throws Exception {
        Class<?> commitState = Arrays.stream(VueTurnContext.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("UserCommitState"))
                .findFirst()
                .orElseThrow();
        Class<?> commitResult = Arrays.stream(VueTurnContext.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("UserCommitResult"))
                .findFirst()
                .orElseThrow();
        Class<?> terminationDecision = Arrays.stream(
                        VueTurnContext.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName()
                        .equals("PreCommitTerminationDecision"))
                .findFirst()
                .orElseThrow();

        assertNotNull(VueTurnContext.class.getMethod(
                "commitUser", BooleanSupplier.class));
        assertNotNull(VueTurnContext.class.getMethod(
                "claimPreCommitTermination"));
        assertNotNull(VueTurnContext.class.getMethod("userCommitState"));
        assertEquals(3, commitState.getEnumConstants().length);
        assertEquals(3, commitResult.getEnumConstants().length);
        assertEquals(3, terminationDecision.getEnumConstants().length);
    }

    @Test
    void 预提交终止先行后不得再执行用户持久化() {
        VueTurnContext context = context("turn-pre-commit-wins");
        AtomicBoolean persisted = new AtomicBoolean();

        assertEquals(VueTurnContext.PreCommitTerminationDecision.PRE_COMMIT_WON,
                context.claimPreCommitTermination());
        assertEquals(VueTurnContext.UserCommitResult.TERMINATED_BEFORE_COMMIT,
                context.commitUser(() -> persisted.compareAndSet(false, true)));
        assertFalse(persisted.get());
        assertEquals(VueTurnContext.UserCommitState.PRE_COMMIT_TERMINATED,
                context.userCommitState());
        assertEquals(VueTurnContext.PreCommitTerminationDecision.ALREADY_TERMINATED,
                context.claimPreCommitTermination());
    }

    @Test
    void 用户提交先行时终止必须等待状态发布并转为提交后收尾()
            throws Exception {
        VueTurnContext context = context("turn-user-commit-wins");
        CountDownLatch persistEntered = new CountDownLatch(1);
        CountDownLatch allowPersist = new CountDownLatch(1);
        CountDownLatch terminationStarted = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var commit = executor.submit(() -> context.commitUser(() -> {
                persistEntered.countDown();
                await(allowPersist);
                return true;
            }));
            assertTrue(persistEntered.await(1, TimeUnit.SECONDS));
            var termination = executor.submit(() -> {
                terminationStarted.countDown();
                return context.claimPreCommitTermination();
            });
            assertTrue(terminationStarted.await(1, TimeUnit.SECONDS));
            allowPersist.countDown();

            assertEquals(VueTurnContext.UserCommitResult.COMMITTED,
                    commit.get(1, TimeUnit.SECONDS));
            assertEquals(VueTurnContext.PreCommitTerminationDecision.POST_COMMIT_REQUIRED,
                    termination.get(1, TimeUnit.SECONDS));
        }
        assertEquals(VueTurnContext.UserCommitState.COMMITTED,
                context.userCommitState());
    }

    @Test
    void 用户持久化失败不得发布已提交状态() {
        VueTurnContext context = context("turn-store-failed");

        assertEquals(VueTurnContext.UserCommitResult.STORE_FAILED,
                context.commitUser(() -> false));
        assertEquals(VueTurnContext.UserCommitState.PREPARING,
                context.userCommitState());
        assertEquals(VueTurnContext.PreCommitTerminationDecision.PRE_COMMIT_WON,
                context.claimPreCommitTermination());
    }

    @Test
    void 预提交终止后不得启动新的准备步骤() {
        VueTurnContext context = context("turn-preparation-rejected");
        AtomicBoolean executed = new AtomicBoolean();

        assertEquals(VueTurnContext.PreCommitTerminationDecision.PRE_COMMIT_WON,
                context.claimPreCommitTermination());
        assertThrows(CancellationException.class,
                () -> context.callPreparation(() -> {
                    executed.set(true);
                    return "不应执行";
                }));
        assertFalse(executed.get());
    }

    @Test
    void 活跃准备回合必须允许准备步骤执行() {
        VueTurnContext context = context("turn-preparation-active");

        assertEquals("已执行", context.callPreparation(() -> "已执行"));
    }

    @Test
    void 已获准准备步骤必须允许返回空值() {
        VueTurnContext context = context("turn-preparation-null");
        AtomicBoolean executed = new AtomicBoolean();

        assertNull(context.callPreparation(() -> {
            executed.set(true);
            return null;
        }));
        assertTrue(executed.get());
    }

    @Test
    void 预提交终止不得插入准备准入与动作执行之间() throws Exception {
        VueTurnContext context = context("turn-preparation-race");
        CountDownLatch actionEntered = new CountDownLatch(1);
        CountDownLatch releaseAction = new CountDownLatch(1);
        AtomicBoolean lateStepRan = new AtomicBoolean();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> admitted = executor.submit(() ->
                    context.callPreparation(() -> {
                        actionEntered.countDown();
                        await(releaseAction);
                        return "完成";
                    }));
            assertTrue(actionEntered.await(1, TimeUnit.SECONDS));

            assertEquals(VueTurnContext.PreCommitTerminationDecision.PRE_COMMIT_WON,
                    context.claimPreCommitTermination());
            assertThrows(CancellationException.class,
                    () -> context.callPreparation(() -> {
                        lateStepRan.set(true);
                        return "迟到";
                    }));
            releaseAction.countDown();

            assertEquals("完成", admitted.get(1, TimeUnit.SECONDS));
            assertFalse(lateStepRan.get());
        }
    }

    @Test
    void 统一回合状态只能由一个终态触发者推进并完成共享结果() {
        VueTurnContext context = context("turn-shared-finalization");
        assertEquals(VueTurnContext.UserCommitResult.COMMITTED,
                context.commitUser(() -> true));
        AtomicReference<VueTurnFinalizer.FinalizationResult> observed =
                new AtomicReference<>();
        AtomicInteger failures = new AtomicInteger();
        AutoCloseable registration = context.onFinalized(
                observed::set, ignored -> failures.incrementAndGet());

        assertEquals(VueTurnContext.TurnStage.ACTIVE,
                context.turnState().stage());
        assertTrue(context.tryStartFinalization(
                VueTurnContext.TerminalTrigger.COMPLETED));
        assertFalse(context.tryStartFinalization(
                VueTurnContext.TerminalTrigger.FAILED));
        assertEquals(VueTurnContext.TurnStage.FINALIZING,
                context.turnState().stage());
        assertEquals(VueTurnContext.TerminalTrigger.COMPLETED,
                context.turnState().trigger());

        VueTurnFinalizer.FinalizationResult result = finalizationResult();
        context.completeFinalization(result);

        assertSame(result, context.awaitFinalization());
        assertSame(result, observed.get());
        assertEquals(0, failures.get());
        assertEquals(VueTurnContext.TurnStage.FINALIZED,
                context.turnState().stage());
        assertEquals(VueTurnContext.TerminalTrigger.COMPLETED,
                context.turnState().trigger());
        close(registration);
    }

    @Test
    void 晚到取消遇到已经认领并释放的终态必须幂等返回false() {
        VueTurnContext context = realContext("turn-late-cancellation");
        assertEquals(VueTurnContext.UserCommitResult.COMMITTED,
                context.commitUser(() -> true));
        assertTrue(context.tryStartFinalization(
                VueTurnContext.TerminalTrigger.COMPLETED));
        context.closeResources();

        assertFalse(context.tryStartCancellation(
                VueTurnContext.TerminalTrigger.CANCELLED));
    }

    @Test
    void 活跃回合租约异常失效时取消必须继续暴露生命周期错误() {
        VueTurnContext context = realContext("turn-active-lease-lost");
        assertEquals(VueTurnContext.UserCommitResult.COMMITTED,
                context.commitUser(() -> true));
        context.closeResources();

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> context.tryStartCancellation(
                        VueTurnContext.TerminalTrigger.CANCELLED));

        assertEquals("应用操作租约已经失效", thrown.getMessage());
        assertEquals(VueTurnContext.TurnStage.ACTIVE,
                context.turnState().stage());
    }

    @Test
    void 取消动作异常不能被误判为晚到取消() {
        String turnId = "turn-cancellation-action-failed";
        var operation = new com.lyw.appgeneration.core.concurrency
                .AppOperationLeaseManager().acquire(
                7L,
                com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager
                        .AppOperationType.GENERATE,
                turnId);
        var vueLease = new com.lyw.appgeneration.core.builder
                .VueBuildSessionManager().open(operation, 9L, turnId);
        VueTurnContext context = new VueTurnContext(
                7L, 9L, turnId, operation, vueLease,
                new FileToolBudgetGuard().newSession());
        IllegalStateException cancellationFailure =
                new IllegalStateException("取消动作失败");
        operation.registerCancellation(() -> {
            throw cancellationFailure;
        });

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> context.tryStartCancellation(
                        VueTurnContext.TerminalTrigger.CANCELLED));

        assertSame(cancellationFailure, thrown);
        context.closeResources();
    }

    @Test
    void 取消动作抛出租约失效异常时也不能被误判为晚到取消() {
        String turnId = "turn-inactive-lease-action-failed";
        var manager = new com.lyw.appgeneration.core.concurrency
                .AppOperationLeaseManager();
        var operation = manager.acquire(
                7L,
                com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager
                        .AppOperationType.GENERATE,
                turnId);
        var vueLease = new com.lyw.appgeneration.core.builder
                .VueBuildSessionManager().open(operation, 9L, turnId);
        VueTurnContext context = new VueTurnContext(
                7L, 9L, turnId, operation, vueLease,
                new FileToolBudgetGuard().newSession());
        var staleOperation = manager.acquire(
                8L,
                com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager
                        .AppOperationType.GENERATE,
                "stale-operation");
        staleOperation.close();
        operation.registerCancellation(staleOperation::requestCancellation);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> context.tryStartCancellation(
                        VueTurnContext.TerminalTrigger.CANCELLED));

        assertEquals("应用操作租约已经失效", thrown.getMessage());
        assertEquals(VueTurnContext.TurnStage.FINALIZING,
                context.turnState().stage());
        context.closeResources();
    }

    @Test
    void 共享收尾异常必须唤醒观察者且已注销观察者不得回调() {
        VueTurnContext context = context("turn-finalization-failed");
        assertEquals(VueTurnContext.UserCommitResult.COMMITTED,
                context.commitUser(() -> true));
        AtomicInteger removedObserverCalls = new AtomicInteger();
        AutoCloseable removed = context.onFinalized(
                ignored -> removedObserverCalls.incrementAndGet(),
                ignored -> removedObserverCalls.incrementAndGet());
        close(removed);
        AtomicReference<Throwable> observedFailure = new AtomicReference<>();
        context.onFinalized(ignored -> { }, observedFailure::set);
        IllegalStateException failure = new IllegalStateException("收尾失败");

        assertTrue(context.tryStartFinalization(
                VueTurnContext.TerminalTrigger.FAILED));
        context.failFinalization(failure);

        CompletionException thrown = assertThrows(
                CompletionException.class, context::awaitFinalization);
        assertSame(failure, thrown.getCause());
        assertSame(failure, observedFailure.get());
        assertEquals(0, removedObserverCalls.get());
        assertEquals(VueTurnContext.TurnStage.FINALIZED,
                context.turnState().stage());
    }

    @Test
    void 非致命观察者错误不得阻断后续成功或失败通知() {
        VueTurnContext successContext = context("turn-observer-success-error");
        assertEquals(VueTurnContext.UserCommitResult.COMMITTED,
                successContext.commitUser(() -> true));
        VueTurnFinalizer.FinalizationResult expectedResult =
                finalizationResult();
        AtomicReference<VueTurnFinalizer.FinalizationResult> observedResult =
                new AtomicReference<>();
        successContext.onFinalized(
                ignored -> {
                    throw new AssertionError("success-observer-error");
                }, ignored -> { });
        successContext.onFinalized(observedResult::set, ignored -> { });
        assertTrue(successContext.tryStartFinalization(
                VueTurnContext.TerminalTrigger.COMPLETED));

        successContext.completeFinalization(expectedResult);

        assertSame(expectedResult, observedResult.get());
        assertSame(expectedResult, successContext.awaitFinalization());

        VueTurnContext failedContext = context("turn-observer-failure-error");
        assertEquals(VueTurnContext.UserCommitResult.COMMITTED,
                failedContext.commitUser(() -> true));
        AssertionError expectedFailure = new AssertionError("root-failure");
        AtomicReference<Throwable> observedFailure = new AtomicReference<>();
        failedContext.onFinalized(
                ignored -> { }, failure -> {
                    throw new AssertionError("failure-observer-error");
                });
        failedContext.onFinalized(ignored -> { }, observedFailure::set);
        assertTrue(failedContext.tryStartFinalization(
                VueTurnContext.TerminalTrigger.FAILED));

        failedContext.failFinalization(expectedFailure);

        CompletionException sharedFailure = assertThrows(
                CompletionException.class, failedContext::awaitFinalization);
        assertSame(expectedFailure, sharedFailure.getCause());
        assertSame(expectedFailure, observedFailure.get());
    }

    @Test
    @SuppressWarnings("removal")
    void 线程终止错误不得被观察者边界吞掉() {
        VueTurnContext context = context("turn-observer-thread-death");
        assertEquals(VueTurnContext.UserCommitResult.COMMITTED,
                context.commitUser(() -> true));
        ThreadDeath fatal = new ThreadDeath();
        context.onFinalized(ignored -> {
            throw fatal;
        }, ignored -> { });
        assertTrue(context.tryStartFinalization(
                VueTurnContext.TerminalTrigger.COMPLETED));
        VueTurnFinalizer.FinalizationResult result = finalizationResult();

        ThreadDeath thrown = assertThrows(
                ThreadDeath.class,
                () -> context.completeFinalization(result));

        assertSame(fatal, thrown);
        assertSame(result, context.awaitFinalization(),
                "致命观察者错误不得改写已经完成的共享终态");
    }

    @Test
    void 删除接管必须通过回合信号等待共享收尾后才能替换租约()
            throws Exception {
        var manager = new com.lyw.appgeneration.core.concurrency
                .AppOperationLeaseManager();
        var operation = manager.acquire(7L,
                com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager
                        .AppOperationType.GENERATE,
                "turn-delete-signal");
        var vueLease = new com.lyw.appgeneration.core.builder
                .VueBuildSessionManager().open(
                operation, 9L, "turn-delete-signal");
        VueTurnContext context = new VueTurnContext(
                7L, 9L, "turn-delete-signal", operation, vueLease,
                new com.lyw.appgeneration.ai.tools.FileToolBudgetGuard()
                        .newSession());
        assertEquals(VueTurnContext.UserCommitResult.COMMITTED,
                context.commitUser(() -> true));
        context.registerDeleteTakeoverParticipant();
        AtomicReference<VueTurnContext.DeleteTakeoverRequest> request =
                new AtomicReference<>();
        context.deleteTakeoverSignal().subscribe(request::set);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<com.lyw.appgeneration.core.concurrency
                    .AppOperationLeaseManager.AppOperationLease> deletion =
                    executor.submit(() -> manager.cancelAndAcquireDelete(
                            7L, "delete", java.time.Duration.ofSeconds(1)));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (request.get() == null && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertNotNull(request.get());
            assertFalse(deletion.isDone());
            assertTrue(context.tryStartDeleteTakeoverFinalization());
            context.completeFinalization(finalizationResult());

            try (var deleteLease = deletion.get(1, TimeUnit.SECONDS)) {
                assertEquals(com.lyw.appgeneration.core.concurrency
                                .AppOperationLeaseManager.AppOperationType.DELETE,
                        deleteLease.operationType());
            }
        } finally {
            context.closeResources();
        }
    }

    private VueTurnFinalizer.FinalizationResult finalizationResult() {
        VueTurnOutcome outcome = new VueTurnOutcome(
                com.lyw.appgeneration.core.builder.VueBuildPhase.SUCCEEDED,
                VueTurnOutcome.TurnOutcomeType.SUCCEEDED,
                "项目已生成并构建成功。", true, "项目已生成并构建成功。");
        return new VueTurnFinalizer.FinalizationResult(outcome, true);
    }

    private VueTurnContext context(String turnId) {
        return VueTurnContext.testing(
                7L, 9L, turnId,
                com.lyw.appgeneration.core.builder.VueBuildPhase.GENERATING);
    }

    private VueTurnContext realContext(String turnId) {
        var operation = new com.lyw.appgeneration.core.concurrency
                .AppOperationLeaseManager().acquire(
                7L,
                com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager
                        .AppOperationType.GENERATE,
                turnId);
        var vueLease = new com.lyw.appgeneration.core.builder
                .VueBuildSessionManager().open(operation, 9L, turnId);
        return new VueTurnContext(
                7L, 9L, turnId, operation, vueLease,
                new FileToolBudgetGuard().newSession());
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("等待测试屏障超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待测试屏障被中断", exception);
        }
    }

    private void close(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception exception) {
            throw new AssertionError("关闭观察注册失败", exception);
        }
    }
}
