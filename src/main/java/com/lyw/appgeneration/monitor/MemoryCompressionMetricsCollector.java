package com.lyw.appgeneration.monitor;

import com.lyw.appgeneration.ai.memory.ContextAdmissionResult;
import com.lyw.appgeneration.ai.memory.ContextCompressionMode;
import com.lyw.appgeneration.service.MemoryCompressionResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** 分层记忆链路的低基数旁路指标收集器。 */
@Component
public final class MemoryCompressionMetricsCollector {

    private static final long UNAVAILABLE_NANOS = Long.MIN_VALUE;

    private final MeterRegistry registry;
    private final LongSupplier nanoTime;

    @Autowired
    public MemoryCompressionMetricsCollector(MeterRegistry registry) {
        this(registry, System::nanoTime);
    }

    MemoryCompressionMetricsCollector(
            MeterRegistry registry, LongSupplier nanoTime) {
        this.registry = Objects.requireNonNull(
                registry, "MeterRegistry 不能为空");
        this.nanoTime = Objects.requireNonNull(
                nanoTime, "单调时钟不能为空");
    }

    public void recordContextGate(
            ContextCompressionMode mode,
            ContextAdmissionResult.FailureReason outcome) {
        safely(() -> counter("memory_context_gate_total",
                "mode", tag(mode), "outcome", tag(outcome)).increment());
    }

    public void recordEstimatedTokens(EstimationStage stage, int tokens) {
        if (tokens < 0) {
            return;
        }
        safely(() -> summary("memory_context_estimated_tokens",
                "stage", tag(stage)).record(tokens));
    }

    public CompressionObservation startCompression(CompressionMode mode) {
        return new CompressionObservation(
                this, mode, currentNanosOrUnavailable());
    }

    public CheckpointObservation startToolChainCheckpoint(int beforeTokens) {
        return new CheckpointObservation(
                this, beforeTokens, currentNanosOrUnavailable());
    }

    public void recordCompressionExecutorRejected(CompressionMode mode) {
        safely(() -> counter("memory_compression_total",
                "mode", tag(mode),
                "outcome", "executor_rejected").increment());
    }

    public void recordSummaryDraftSuccess(
            int summaryTokens, int reducerRounds) {
        if (summaryTokens >= 0) {
            safely(() -> summary("memory_summary_tokens")
                    .record(summaryTokens));
        }
        recordSummaryReducerRounds(reducerRounds);
    }

    public void recordSummaryDraftFailure(int reducerRounds) {
        recordSummaryReducerRounds(reducerRounds);
    }

    private void recordSummaryReducerRounds(int reducerRounds) {
        if (reducerRounds >= 0) {
            safely(() -> summary("memory_summary_reduce_rounds")
                    .record(reducerRounds));
        }
    }

    public void recordL2Debounce(DebounceOutcome outcome) {
        safely(() -> counter("memory_l2_debounce_total",
                "outcome", tag(outcome)).increment());
    }

    public void recordL2Candidate(CandidateStatus status) {
        safely(() -> counter("memory_l2_candidate_total",
                "status", tag(status)).increment());
    }

    public void recordL2RecallTokens(int tokens) {
        if (tokens < 0) {
            return;
        }
        safely(() -> summary("memory_l2_recall_tokens").record(tokens));
    }

    private void completeCompression(
            CompressionMode mode,
            MemoryCompressionResult.Status outcome,
            long startedAtNanos) {
        String modeTag = tag(mode);
        String outcomeTag = tag(outcome);
        safely(() -> counter("memory_compression_total",
                "mode", modeTag,
                "outcome", outcomeTag).increment());
        long completedAtNanos = currentNanosOrUnavailable();
        if (startedAtNanos == UNAVAILABLE_NANOS
                || completedAtNanos == UNAVAILABLE_NANOS) {
            return;
        }
        long elapsedNanos = Math.max(0L,
                completedAtNanos - startedAtNanos);
        safely(() -> Timer.builder("memory_compression_duration_seconds")
                .description("分层记忆实际压缩耗时")
                .publishPercentileHistogram()
                .tags("mode", modeTag, "outcome", outcomeTag)
                .register(registry)
                .record(Duration.ofNanos(elapsedNanos)));
    }

    private void completeToolChainCheckpoint(
            int beforeTokens,
            CheckpointOutcome outcome,
            int afterTokens,
            long startedAtNanos) {
        String outcomeTag = tag(outcome);
        safely(() -> counter("memory_tool_chain_checkpoint_total",
                "outcome", outcomeTag).increment());
        safely(() -> summary("memory_tool_chain_checkpoint_tokens",
                "stage", tag(EstimationStage.BEFORE))
                .record(beforeTokens));
        safely(() -> summary("memory_tool_chain_checkpoint_tokens",
                "stage", tag(EstimationStage.AFTER))
                .record(afterTokens));
        long completedAtNanos = currentNanosOrUnavailable();
        if (startedAtNanos == UNAVAILABLE_NANOS
                || completedAtNanos == UNAVAILABLE_NANOS) {
            return;
        }
        long elapsedNanos = Math.max(0L,
                completedAtNanos - startedAtNanos);
        safely(() -> Timer.builder(
                        "memory_tool_chain_checkpoint_duration_seconds")
                .description("未完成工具链检查点判定耗时")
                .publishPercentileHistogram()
                .tags("outcome", outcomeTag)
                .register(registry)
                .record(Duration.ofNanos(elapsedNanos)));
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }

    private DistributionSummary summary(String name, String... tags) {
        return DistributionSummary.builder(name)
                .tags(tags)
                .register(registry);
    }

    private long currentNanosOrUnavailable() {
        try {
            return nanoTime.getAsLong();
        } catch (RuntimeException ignored) {
            return UNAVAILABLE_NANOS;
        }
    }

    private void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // 观测故障必须完全旁路，不能改变门禁、压缩、摘要或 L2 语义。
        }
    }

    private static String tag(Enum<?> value) {
        return Objects.requireNonNull(value, "指标枚举不能为空")
                .name().toLowerCase(Locale.ROOT);
    }

    /** 单次真实压缩 owner 的 CAS 终态句柄。 */
    public static final class CompressionObservation {

        private final MemoryCompressionMetricsCollector collector;
        private final CompressionMode mode;
        private final long startedAtNanos;
        private final AtomicBoolean completed = new AtomicBoolean();

        private CompressionObservation(
                MemoryCompressionMetricsCollector collector,
                CompressionMode mode,
                long startedAtNanos) {
            this.collector = collector;
            this.mode = mode;
            this.startedAtNanos = startedAtNanos;
        }

        public boolean complete(MemoryCompressionResult.Status outcome) {
            Objects.requireNonNull(outcome, "压缩结果不能为空");
            if (!completed.compareAndSet(false, true)) {
                return false;
            }
            collector.completeCompression(mode, outcome, startedAtNanos);
            return true;
        }
    }

    /** 单次真实工具链检查点判定的 CAS 终态句柄。 */
    public static final class CheckpointObservation {

        private final MemoryCompressionMetricsCollector collector;
        private final int beforeTokens;
        private final long startedAtNanos;
        private final AtomicBoolean completed = new AtomicBoolean();

        private CheckpointObservation(
                MemoryCompressionMetricsCollector collector,
                int beforeTokens,
                long startedAtNanos) {
            this.collector = collector;
            this.beforeTokens = beforeTokens;
            this.startedAtNanos = startedAtNanos;
        }

        public boolean complete(
                CheckpointOutcome outcome, int afterTokens) {
            Objects.requireNonNull(outcome, "检查点结果不能为空");
            if (!completed.compareAndSet(false, true)) {
                return false;
            }
            collector.completeToolChainCheckpoint(
                    beforeTokens, outcome, afterTokens, startedAtNanos);
            return true;
        }
    }

    public enum EstimationStage {
        BEFORE, AFTER
    }

    public enum CompressionMode {
        ASYNC, BLOCKING
    }

    public enum CheckpointOutcome {
        SUCCESS,
        FAILED,
        ALREADY_ATTEMPTED,
        NO_UNFINISHED_TAIL
    }

    public enum DebounceOutcome {
        REGISTERED,
        RESCHEDULED,
        SUBMITTED,
        COMPLETED,
        REJECTED,
        DATABASE_BACKOFF_DEFERRED
    }

    public enum CandidateStatus {
        CANDIDATE, ACTIVE, UNCHANGED
    }
}
