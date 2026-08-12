package com.lyw.appgeneration.core.handler;

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
    private final ConcurrentMap<String, PendingCancellation> pending =
            new ConcurrentHashMap<>();
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    @Autowired
    public VueTurnCancellationCoordinator(
            VueTurnFinalizer finalizer,
            @Qualifier("vueTurnCancellationExecutor") Executor executor) {
        this(finalizer, executor, QUIESCENCE_TIMEOUT);
    }

    VueTurnCancellationCoordinator(
            VueTurnFinalizer finalizer, Executor executor,
            Duration quiescenceTimeout) {
        this.finalizer = Objects.requireNonNull(finalizer);
        this.executor = Objects.requireNonNull(executor);
        this.quiescenceTimeout = Objects.requireNonNull(quiescenceTimeout);
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
        PendingCancellation cancellation =
                new PendingCancellation(
                        context, canonicalPrefix, outcomeType, result);
        pending.put(context.turnId(), cancellation);
        try {
            executor.execute(() -> finalizeCancellation(cancellation));
        } catch (RejectedExecutionException exception) {
            if (context.awaitQuiescence(Duration.ZERO)) {
                finalizeQuiescentCancellation(cancellation);
                return true;
            }
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
                    log.warn("Vue 取消后台任务在静默前中断,保留租约,appId={},turnId={}",
                            context.appId(), context.turnId());
                    return;
                }
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
            return;
        }
        String message = cancellation.outcomeType()
                == VueTurnOutcome.TurnOutcomeType.TIMED_OUT
                ? JsonMessageStreamHandler.TIMEOUT_MESSAGE
                : "本次生成已取消。";
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
            Sinks.One<VueTurnFinalizer.FinalizationResult> result) {
    }
}
