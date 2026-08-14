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
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.Optional;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

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

    private boolean request(
            VueTurnContext context, Supplier<String> canonicalPrefix,
            VueTurnOutcome.TurnOutcomeType outcomeType,
            Sinks.One<VueTurnFinalizer.FinalizationResult> result) {
        Objects.requireNonNull(context, "context 不能为空");
        Objects.requireNonNull(canonicalPrefix, "canonicalPrefix 不能为空");
        if (!accepting.get()) {
            throw new RejectedExecutionException("Vue 取消协调器正在关闭");
        }
        VueTurnContext.TerminalTrigger trigger = outcomeType
                == VueTurnOutcome.TurnOutcomeType.TIMED_OUT
                ? VueTurnContext.TerminalTrigger.TIMED_OUT
                : VueTurnContext.TerminalTrigger.CANCELLED;
        if (!context.tryClaimTerminalAndCancel(trigger)) {
            return false;
        }
        VueBuildRepairMetricsCollector.CancellationTrigger metricTrigger =
                cancellationTrigger(outcomeType);
        recordCancellation(metricTrigger,
                VueBuildRepairMetricsCollector.CancellationResult.REQUESTED);
        PendingCancellation cancellation =
                new PendingCancellation(
                        context, canonicalPrefix, outcomeType,
                        metricTrigger, result);
        pending.put(context.turnId(), cancellation);
        try {
            executor.execute(() -> finalizeCancellation(cancellation));
        } catch (RejectedExecutionException exception) {
            if (context.awaitQuiescence(Duration.ZERO)) {
                try {
                    finalizeQuiescentCancellation(cancellation);
                } catch (RuntimeException finalizationFailure) {
                    recordCancellation(metricTrigger,
                            VueBuildRepairMetricsCollector.CancellationResult.FAILED);
                    throw finalizationFailure;
                }
                return true;
            }
            recordCancellation(metricTrigger,
                    VueBuildRepairMetricsCollector.CancellationResult.FAILED);
            log.error("Vue 取消后台任务被拒绝且回调未静默,保留终态门与租约,appId={},turnId={}",
                    context.appId(), context.turnId(), exception);
            throw exception;
        }
        return true;
    }

    private void finalizeCancellation(PendingCancellation cancellation) {
        VueTurnContext context = cancellation.context();
        try {
            while (!context.awaitQuiescence(quiescenceTimeout)) {
                if (Thread.currentThread().isInterrupted()) {
                    recordCancellation(cancellation.metricTrigger(),
                            VueBuildRepairMetricsCollector.CancellationResult.FAILED);
                    log.warn("Vue 取消后台任务在静默前中断,保留租约,appId={},turnId={}",
                            context.appId(), context.turnId());
                    return;
                }
                recordCancellation(cancellation.metricTrigger(),
                        VueBuildRepairMetricsCollector.CancellationResult.TIMED_OUT);
                log.warn("Vue 取消等待回调静默超时,继续跟踪,appId={},turnId={}",
                        context.appId(), context.turnId());
            }
            finalizeQuiescentCancellation(cancellation);
        } catch (RuntimeException exception) {
            if (cancellation.result() != null) {
                cancellation.result().tryEmitError(exception);
            }
            log.error("Vue 取消后台收尾异常,保留租约,appId={},turnId={}",
                    context.appId(), context.turnId(), exception);
            recordCancellation(cancellation.metricTrigger(),
                    VueBuildRepairMetricsCollector.CancellationResult.FAILED);
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
        String canonical = JsonMessageStreamHandler.appendTerminalText(
                cancellation.canonicalPrefix().get(), message);
        VueTurnFinalizer.FinalizationResult finalized =
                finalizer.finalizeOnce(context, new VueTurnOutcome(
                context.phase(), cancellation.outcomeType(),
                canonical, false, message));
        pending.remove(context.turnId(), cancellation);
        if (cancellation.result() != null) {
            cancellation.result().tryEmitValue(finalized);
        }
        recordCancellation(cancellation.metricTrigger(),
                VueBuildRepairMetricsCollector.CancellationResult.COMPLETED);
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
        int remaining = pending.size();
        if (remaining > 0) {
            log.warn("Vue 取消协调器关闭时仍有未静默回合,数量={},租约保持占用",
                    remaining);
        }
    }

    int pendingCount() {
        return pending.size();
    }

    private record PendingCancellation(
            VueTurnContext context, Supplier<String> canonicalPrefix,
            VueTurnOutcome.TurnOutcomeType outcomeType,
            VueBuildRepairMetricsCollector.CancellationTrigger metricTrigger,
            Sinks.One<VueTurnFinalizer.FinalizationResult> result) {
    }
}
