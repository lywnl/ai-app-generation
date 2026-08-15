package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.monitor.VueBuildRepairMetricsCollector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.Optional;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/** 在客户端断开后仍受应用生命周期管理地完成 Vue 回合稳定收尾。 */
@Slf4j
@Component
public class VueTurnCancellationCoordinator implements AutoCloseable {

    static final Duration QUIESCENCE_TIMEOUT = Duration.ofSeconds(10);

    private final VueTurnFinalizer finalizer;
    private final Executor executor;
    private final Duration quiescenceTimeout;
    private final VueBuildRepairMetricsCollector metricsCollector;
    private final ConcurrentMap<String, PendingCancellation> pending =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PendingPreCommitCleanup>
            pendingPreCommitCleanups = new ConcurrentHashMap<>();
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    @Autowired
    public VueTurnCancellationCoordinator(
            VueTurnFinalizer finalizer,
            @Qualifier("vueTurnCancellationExecutor") Executor executor,
            VueBuildRepairMetricsCollector metricsCollector) {
        this(finalizer, executor, QUIESCENCE_TIMEOUT, metricsCollector);
    }

    VueTurnCancellationCoordinator(
            VueTurnFinalizer finalizer, Executor executor,
            Duration quiescenceTimeout) {
        this(finalizer, executor, quiescenceTimeout, null);
    }

    VueTurnCancellationCoordinator(
            VueTurnFinalizer finalizer, Executor executor,
            Duration quiescenceTimeout,
            VueBuildRepairMetricsCollector metricsCollector) {
        this.finalizer = Objects.requireNonNull(finalizer);
        this.executor = Objects.requireNonNull(executor);
        this.quiescenceTimeout = Objects.requireNonNull(quiescenceTimeout);
        this.metricsCollector = metricsCollector;
        if (quiescenceTimeout.isZero() || quiescenceTimeout.isNegative()) {
            throw new IllegalArgumentException("静默等待时间必须大于 0");
        }
    }

    public boolean requestCancellation(
            VueTurnContext context, Supplier<String> canonicalPrefix) {
        return request(context, canonicalPrefix,
                VueTurnOutcome.TurnOutcomeType.CANCELLED, null);
    }

    /** 绝对截止时间专用入口；只有赢得终态 CAS 才返回可等待的最终结果。 */
    public Optional<Mono<VueTurnFinalizer.FinalizationResult>> requestTimeout(
            VueTurnContext context, Supplier<String> canonicalPrefix) {
        Sinks.One<VueTurnFinalizer.FinalizationResult> result = Sinks.one();
        boolean claimed = request(context, canonicalPrefix,
                VueTurnOutcome.TurnOutcomeType.TIMED_OUT, result);
        return claimed ? Optional.of(result.asMono()) : Optional.empty();
    }

    /** 预提交终止只等待准备步骤静默并释放资源，不产生稳定 AI 终态。 */
    public CompletionStage<Void> requestPreCommitCleanup(VueTurnContext context) {
        Objects.requireNonNull(context, "context 不能为空");
        if (context.userCommitState()
                != VueTurnContext.UserCommitState.PRE_COMMIT_TERMINATED) {
            throw new IllegalStateException("预提交清理只接受已经终止的准备回合");
        }
        PendingPreCommitCleanup existing =
                pendingPreCommitCleanups.get(context.turnId());
        if (existing != null) {
            return existing.completion();
        }
        PendingPreCommitCleanup cleanup =
                new PendingPreCommitCleanup(context);
        existing = pendingPreCommitCleanups.putIfAbsent(
                context.turnId(), cleanup);
        if (existing != null) {
            return existing.completion();
        }
        try {
            context.cancelGeneration();
        } catch (RuntimeException | Error failure) {
            cleanup.completion().completeExceptionally(failure);
            schedulePreCommitDrain(cleanup, failure);
            log.error("Vue 预提交取消动作失败,等待资源排空,appId={},turnId={}",
                    context.appId(), context.turnId(), failure);
            return cleanup.completion();
        }
        try {
            executor.execute(() -> completePreCommitCleanup(cleanup));
        } catch (RejectedExecutionException rejection) {
            handleRejectedPreCommitCleanup(cleanup, rejection);
        } catch (RuntimeException | Error failure) {
            cleanup.completion().completeExceptionally(failure);
            schedulePreCommitDrain(cleanup, failure);
            log.error("Vue 预提交清理启动失败,等待资源排空,appId={},turnId={}",
                    context.appId(), context.turnId(), failure);
        }
        return cleanup.completion();
    }

    private void completePreCommitCleanup(PendingPreCommitCleanup cleanup) {
        VueTurnContext context = cleanup.context();
        try {
            while (!context.awaitQuiescence(quiescenceTimeout)) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("Vue 预提交清理在静默前被中断");
                }
                log.warn("Vue 预提交清理等待回调静默超时,继续跟踪,appId={},turnId={}",
                        context.appId(), context.turnId());
            }
            context.closeResources();
            pendingPreCommitCleanups.remove(context.turnId(), cleanup);
            cleanup.completion().complete(null);
        } catch (RuntimeException | Error failure) {
            cleanup.completion().completeExceptionally(failure);
            schedulePreCommitDrain(cleanup, failure);
            log.error("Vue 预提交清理失败,保留租约,appId={},turnId={}",
                    context.appId(), context.turnId(), failure);
        }
    }

    private void handleRejectedPreCommitCleanup(
            PendingPreCommitCleanup cleanup,
            RejectedExecutionException rejection) {
        VueTurnContext context = cleanup.context();
        if (!Schedulers.isInNonBlockingThread()
                && context.awaitQuiescence(Duration.ZERO)) {
            try {
                context.closeResources();
                pendingPreCommitCleanups.remove(context.turnId(), cleanup);
                cleanup.completion().complete(null);
                return;
            } catch (RuntimeException | Error closeFailure) {
                addSuppressed(rejection, closeFailure);
            }
        }
        cleanup.completion().completeExceptionally(rejection);
        schedulePreCommitDrain(cleanup, rejection);
        log.error("Vue 预提交清理任务被拒绝且不能安全同步释放,尝试资源排空,appId={},turnId={}",
                context.appId(), context.turnId(), rejection);
    }

    private void schedulePreCommitDrain(
            PendingPreCommitCleanup cleanup, Throwable originalFailure) {
        if (!cleanup.claimDrain()) {
            return;
        }
        try {
            executor.execute(() -> drainPreCommitResources(
                    cleanup, originalFailure));
        } catch (RuntimeException | Error schedulingFailure) {
            addSuppressed(originalFailure, schedulingFailure);
            drainPreCommitSynchronouslyIfQuiescent(cleanup, originalFailure);
        }
    }

    private void drainPreCommitResources(
            PendingPreCommitCleanup cleanup, Throwable originalFailure) {
        VueTurnContext context = cleanup.context();
        try {
            while (!context.awaitQuiescence(quiescenceTimeout)) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException(
                            "Vue 预提交资源排空在静默前被中断");
                }
                log.warn("Vue 预提交资源排空等待回调静默超时,继续跟踪,appId={},turnId={}",
                        context.appId(), context.turnId());
            }
            closePreCommitDrainedResources(cleanup);
        } catch (RuntimeException | Error drainFailure) {
            addSuppressed(originalFailure, drainFailure);
            log.error("Vue 预提交资源排空失败,继续保留安全占用,appId={},turnId={}",
                    context.appId(), context.turnId(), drainFailure);
        }
    }

    private void drainPreCommitSynchronouslyIfQuiescent(
            PendingPreCommitCleanup cleanup, Throwable originalFailure) {
        VueTurnContext context = cleanup.context();
        if (!context.awaitQuiescence(Duration.ZERO)) {
            log.error("Vue 预提交资源排空任务被拒绝且回调未静默,保留租约,appId={},turnId={}",
                    context.appId(), context.turnId(), originalFailure);
            return;
        }
        try {
            closePreCommitDrainedResources(cleanup);
        } catch (RuntimeException | Error closeFailure) {
            addSuppressed(originalFailure, closeFailure);
            log.error("Vue 预提交资源同步排空失败,继续保留安全占用,appId={},turnId={}",
                    context.appId(), context.turnId(), closeFailure);
        }
    }

    private void closePreCommitDrainedResources(
            PendingPreCommitCleanup cleanup) {
        VueTurnContext context = cleanup.context();
        context.closeResources();
        pendingPreCommitCleanups.remove(context.turnId(), cleanup);
    }

    /** 删除 manager 已关闭外层门，仅由回合信号分支认领稳定取消终态。 */
    public Optional<Mono<VueTurnFinalizer.FinalizationResult>>
            requestDeleteTakeover(
                    VueTurnContext context,
                    VueTurnContext.DeleteTakeoverRequest request,
                    Supplier<String> canonicalPrefix) {
        Objects.requireNonNull(context, "context 不能为空");
        Objects.requireNonNull(request, "删除接管请求不能为空");
        Objects.requireNonNull(canonicalPrefix, "canonicalPrefix 不能为空");
        if (!context.isUserCommitted()) {
            throw new IllegalStateException("删除接管只接受已提交 Vue 回合");
        }
        if (!context.tryStartDeleteTakeoverFinalization()) {
            return Optional.empty();
        }
        VueBuildRepairMetricsCollector.CancellationTrigger trigger =
                VueBuildRepairMetricsCollector.CancellationTrigger.DELETE_TAKEOVER;
        recordCancellation(trigger,
                VueBuildRepairMetricsCollector.CancellationResult.REQUESTED);
        Sinks.One<VueTurnFinalizer.FinalizationResult> result = Sinks.one();
        PendingCancellation cancellation = new PendingCancellation(
                context, canonicalPrefix,
                VueTurnOutcome.TurnOutcomeType.CANCELLED,
                trigger, result);
        pending.put(context.turnId(), cancellation);
        try {
            executor.execute(() -> finalizeDeleteTakeover(
                    cancellation, request));
        } catch (RejectedExecutionException rejection) {
            if (!Schedulers.isInNonBlockingThread()
                    && context.awaitQuiescence(Duration.ZERO)) {
                finalizeDeleteTakeover(cancellation, request);
            } else {
                failCancellation(cancellation, rejection);
            }
        } catch (RuntimeException | Error failure) {
            failCancellation(cancellation, failure);
            throw failure;
        }
        return Optional.of(result.asMono());
    }

    private void finalizeDeleteTakeover(
            PendingCancellation cancellation,
            VueTurnContext.DeleteTakeoverRequest request) {
        VueTurnContext context = cancellation.context();
        try {
            if (!request.takeoverContext().awaitQuiescence()) {
                throw new IllegalStateException("删除接管未在截止时间内达到静默");
            }
            VueTurnFinalizer.FinalizationResult result = finalizeOutcome(
                    context, cancellation.canonicalPrefix(),
                    VueTurnOutcome.TurnOutcomeType.CANCELLED,
                    VueTurnFinalizer.CANCELLED_MESSAGE);
            pending.remove(context.turnId(), cancellation);
            cancellation.result().tryEmitValue(result);
            recordCancellation(cancellation.metricTrigger(),
                    VueBuildRepairMetricsCollector.CancellationResult.COMPLETED);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failCancellation(cancellation, exception);
        } catch (RuntimeException | Error failure) {
            failCancellation(cancellation, failure);
        }
    }

    private boolean request(
            VueTurnContext context, Supplier<String> canonicalPrefix,
            VueTurnOutcome.TurnOutcomeType outcomeType,
            Sinks.One<VueTurnFinalizer.FinalizationResult> result) {
        Objects.requireNonNull(context, "context 不能为空");
        Objects.requireNonNull(canonicalPrefix, "canonicalPrefix 不能为空");
        if (!context.isUserCommitted()) {
            throw new IllegalStateException("取消协调器只接受已提交 Vue 回合");
        }
        if (!accepting.get()) {
            throw new RejectedExecutionException("Vue 取消协调器正在关闭");
        }
        VueTurnContext.TerminalTrigger trigger = outcomeType
                == VueTurnOutcome.TurnOutcomeType.TIMED_OUT
                ? VueTurnContext.TerminalTrigger.TIMED_OUT
                : VueTurnContext.TerminalTrigger.CANCELLED;
        boolean claimed;
        try {
            claimed = context.tryStartCancellation(trigger);
        } catch (RuntimeException | Error failure) {
            if (!isClaimedCancellation(context, trigger)) {
                throw failure;
            }
            PendingCancellation cancellation = registerCancellation(
                    context, canonicalPrefix, outcomeType, result);
            failCancellation(cancellation, failure);
            throw failure;
        }
        if (!claimed) {
            return false;
        }
        PendingCancellation cancellation = registerCancellation(
                context, canonicalPrefix, outcomeType, result);
        try {
            executor.execute(() -> finalizeCancellation(cancellation));
        } catch (RejectedExecutionException exception) {
            if (!Schedulers.isInNonBlockingThread()
                    && context.awaitQuiescence(Duration.ZERO)) {
                try {
                    finalizeQuiescentCancellation(cancellation);
                } catch (RuntimeException | Error finalizationFailure) {
                    failCancellation(cancellation, finalizationFailure);
                    throw finalizationFailure;
                }
                return true;
            }
            recordCancellation(cancellation.metricTrigger(),
                    VueBuildRepairMetricsCollector.CancellationResult.FAILED);
            context.failFinalization(exception);
            scheduleResourceDrain(cancellation, exception);
            log.error("Vue 取消后台任务被拒绝且回调未静默,保留终态门与租约,appId={},turnId={}",
                    context.appId(), context.turnId(), exception);
            throw exception;
        } catch (RuntimeException | Error failure) {
            failCancellation(cancellation, failure);
            throw failure;
        }
        return true;
    }

    private boolean isClaimedCancellation(
            VueTurnContext context, VueTurnContext.TerminalTrigger trigger) {
        VueTurnContext.TurnState state = context.turnState();
        return state.stage() == VueTurnContext.TurnStage.FINALIZING
                && state.trigger() == trigger;
    }

    private PendingCancellation registerCancellation(
            VueTurnContext context,
            Supplier<String> canonicalPrefix,
            VueTurnOutcome.TurnOutcomeType outcomeType,
            Sinks.One<VueTurnFinalizer.FinalizationResult> result) {
        VueBuildRepairMetricsCollector.CancellationTrigger metricTrigger =
                cancellationTrigger(outcomeType);
        recordCancellation(metricTrigger,
                VueBuildRepairMetricsCollector.CancellationResult.REQUESTED);
        PendingCancellation cancellation =
                new PendingCancellation(
                        context, canonicalPrefix, outcomeType,
                        metricTrigger, result);
        pending.put(context.turnId(), cancellation);
        return cancellation;
    }

    private void finalizeCancellation(PendingCancellation cancellation) {
        VueTurnContext context = cancellation.context();
        try {
            while (!context.awaitQuiescence(quiescenceTimeout)) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException(
                            "Vue 取消后台任务在静默前被中断");
                }
                recordCancellation(cancellation.metricTrigger(),
                        VueBuildRepairMetricsCollector.CancellationResult.TIMED_OUT);
                log.warn("Vue 取消等待回调静默超时,继续跟踪,appId={},turnId={}",
                        context.appId(), context.turnId());
            }
            finalizeQuiescentCancellation(cancellation);
        } catch (RuntimeException | Error failure) {
            failCancellation(cancellation, failure);
        }
    }

    private void failCancellation(
            PendingCancellation cancellation, Throwable failure) {
        VueTurnContext context = cancellation.context();
        recordCancellation(cancellation.metricTrigger(),
                VueBuildRepairMetricsCollector.CancellationResult.FAILED);
        boolean finalized = context.turnState().stage()
                == VueTurnContext.TurnStage.FINALIZED;
        boolean quiescent = finalized
                || context.awaitQuiescence(Duration.ZERO);
        if (quiescent && !finalized) {
            try {
                context.closeResources();
            } catch (RuntimeException | Error closeFailure) {
                if (closeFailure != failure) {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        if (!finalized && context.turnState().stage()
                == VueTurnContext.TurnStage.FINALIZING) {
            context.failFinalization(failure);
        }
        if (quiescent) {
            pending.remove(context.turnId(), cancellation);
        } else {
            scheduleResourceDrain(cancellation, failure);
        }
        if (cancellation.result() != null) {
            cancellation.result().tryEmitError(failure);
        }
        log.error(quiescent
                        ? "Vue 取消后台收尾异常,已关闭静默资源,appId={},turnId={}"
                        : "Vue 取消后台收尾异常且回调未静默,保留租约,appId={},turnId={}",
                context.appId(), context.turnId(), failure);
    }

    private void scheduleResourceDrain(
            PendingCancellation cancellation, Throwable originalFailure) {
        if (!cancellation.claimDrain()) {
            return;
        }
        try {
            executor.execute(() -> drainResources(
                    cancellation, originalFailure));
        } catch (RuntimeException | Error schedulingFailure) {
            addSuppressed(originalFailure, schedulingFailure);
            drainSynchronouslyIfQuiescent(cancellation, originalFailure);
        }
    }

    private void drainResources(
            PendingCancellation cancellation, Throwable originalFailure) {
        VueTurnContext context = cancellation.context();
        try {
            while (!context.awaitQuiescence(quiescenceTimeout)) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException(
                            "Vue 取消资源排空在静默前被中断");
                }
                log.warn("Vue 取消资源排空等待回调静默超时,继续跟踪,appId={},turnId={}",
                        context.appId(), context.turnId());
            }
            closeDrainedResources(cancellation);
        } catch (RuntimeException | Error drainFailure) {
            addSuppressed(originalFailure, drainFailure);
            log.error("Vue 取消资源排空失败,继续保留安全占用,appId={},turnId={}",
                    context.appId(), context.turnId(), drainFailure);
        }
    }

    private void drainSynchronouslyIfQuiescent(
            PendingCancellation cancellation, Throwable originalFailure) {
        VueTurnContext context = cancellation.context();
        if (!context.awaitQuiescence(Duration.ZERO)) {
            log.error("Vue 取消资源排空任务被拒绝且回调未静默,保留租约,appId={},turnId={}",
                    context.appId(), context.turnId(), originalFailure);
            return;
        }
        try {
            closeDrainedResources(cancellation);
        } catch (RuntimeException | Error closeFailure) {
            addSuppressed(originalFailure, closeFailure);
            log.error("Vue 取消资源同步排空失败,继续保留安全占用,appId={},turnId={}",
                    context.appId(), context.turnId(), closeFailure);
        }
    }

    private void closeDrainedResources(PendingCancellation cancellation) {
        VueTurnContext context = cancellation.context();
        context.closeResources();
        pending.remove(context.turnId(), cancellation);
    }

    private void addSuppressed(Throwable original, Throwable secondary) {
        if (original != secondary) {
            original.addSuppressed(secondary);
        }
    }

    private void finalizeQuiescentCancellation(PendingCancellation cancellation) {
        VueTurnContext context = cancellation.context();
        if (!context.isUserCommitted()) {
            context.closeResources();
            pending.remove(context.turnId(), cancellation);
            if (cancellation.result() != null) {
                cancellation.result().tryEmitError(new IllegalStateException(
                        "Vue 回合在用户消息提交前结束"));
            }
            recordCancellation(cancellation.metricTrigger(),
                    VueBuildRepairMetricsCollector.CancellationResult.COMPLETED);
            return;
        }
        String message = cancellation.outcomeType()
                == VueTurnOutcome.TurnOutcomeType.TIMED_OUT
                ? JsonMessageStreamHandler.TIMEOUT_MESSAGE
                : VueTurnFinalizer.CANCELLED_MESSAGE;
        VueTurnFinalizer.FinalizationResult finalized = finalizeOutcome(
                context, cancellation.canonicalPrefix(),
                cancellation.outcomeType(), message);
        pending.remove(context.turnId(), cancellation);
        if (cancellation.result() != null) {
            cancellation.result().tryEmitValue(finalized);
        }
        recordCancellation(cancellation.metricTrigger(),
                VueBuildRepairMetricsCollector.CancellationResult.COMPLETED);
    }

    private VueTurnFinalizer.FinalizationResult finalizeOutcome(
            VueTurnContext context,
            Supplier<String> canonicalPrefix,
            VueTurnOutcome.TurnOutcomeType outcomeType,
            String message) {
        String canonical = JsonMessageStreamHandler.appendTerminalText(
                canonicalPrefix.get(), message);
        return finalizer.finalizeOnce(context, new VueTurnOutcome(
                context.phase(), outcomeType, canonical, false, message));
    }

    private VueBuildRepairMetricsCollector.CancellationTrigger cancellationTrigger(
            VueTurnOutcome.TurnOutcomeType outcomeType) {
        return outcomeType == VueTurnOutcome.TurnOutcomeType.TIMED_OUT
                ? VueBuildRepairMetricsCollector.CancellationTrigger.ABSOLUTE_DEADLINE
                : VueBuildRepairMetricsCollector.CancellationTrigger.SUBSCRIBER_CANCELLED;
    }

    private void recordCancellation(
            VueBuildRepairMetricsCollector.CancellationTrigger trigger,
            VueBuildRepairMetricsCollector.CancellationResult result) {
        if (metricsCollector != null) {
            metricsCollector.recordCancellation(trigger, result);
        }
    }

    @Override
    public void close() {
        accepting.set(false);
        int remaining = pending.size() + pendingPreCommitCleanups.size();
        if (remaining > 0) {
            log.warn("Vue 取消协调器关闭时仍有未静默回合,数量={},租约保持占用",
                    remaining);
        }
    }

    int pendingCount() {
        return pending.size() + pendingPreCommitCleanups.size();
    }

    private record PendingCancellation(
            VueTurnContext context, Supplier<String> canonicalPrefix,
            VueTurnOutcome.TurnOutcomeType outcomeType,
            VueBuildRepairMetricsCollector.CancellationTrigger metricTrigger,
            Sinks.One<VueTurnFinalizer.FinalizationResult> result,
            AtomicBoolean drainScheduled) {

        private PendingCancellation(
                VueTurnContext context, Supplier<String> canonicalPrefix,
                VueTurnOutcome.TurnOutcomeType outcomeType,
                VueBuildRepairMetricsCollector.CancellationTrigger metricTrigger,
                Sinks.One<VueTurnFinalizer.FinalizationResult> result) {
            this(context, canonicalPrefix, outcomeType, metricTrigger, result,
                    new AtomicBoolean());
        }

        private boolean claimDrain() {
            return drainScheduled.compareAndSet(false, true);
        }
    }

    private record PendingPreCommitCleanup(
            VueTurnContext context,
            CompletableFuture<Void> completion,
            AtomicBoolean drainScheduled) {

        private PendingPreCommitCleanup(VueTurnContext context) {
            this(context, new CompletableFuture<>(), new AtomicBoolean());
        }

        private boolean claimDrain() {
            return drainScheduled.compareAndSet(false, true);
        }
    }
}
