package com.lyw.appgeneration.monitor;

import com.lyw.appgeneration.core.builder.BuildResult;
import com.lyw.appgeneration.core.handler.VueTurnOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Vue 在线构建修复的低基数旁路指标收集器。 */
@Component
public final class VueBuildRepairMetricsCollector {

    private final MeterRegistry registry;

    public VueBuildRepairMetricsCollector(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "MeterRegistry 不能为空");
    }

    public BuildAttemptObservation startBuildAttempt(int attempt) {
        validateAttempt(attempt);
        return new BuildAttemptObservation(this, attempt);
    }

    public void recordTurnOutcome(VueTurnOutcome outcome) {
        Objects.requireNonNull(outcome, "Vue 回合终态不能为空");
        safely(() -> counter("vue_turn_outcomes_total",
                "outcome", tag(outcome.outcome()),
                "phase", tag(outcome.phase())).increment());
    }

    public void recordTurnAdmission(AdmissionResult result) {
        Objects.requireNonNull(result, "Vue 回合准入结果不能为空");
        safely(() -> counter("vue_turn_admissions_total",
                "result", tag(result)).increment());
    }

    public void recordCancellation(
            CancellationTrigger trigger, CancellationResult result) {
        safely(() -> counter("vue_turn_cancellations_total",
                "trigger", tag(trigger), "result", tag(result)).increment());
    }

    public void recordMemoryL0Sync(MemoryAction action, MemoryResult result) {
        safely(() -> counter("vue_memory_l0_sync_total",
                "action", tag(action), "result", tag(result)).increment());
    }

    private void completeBuildAttempt(int attempt, BuildResult result) {
        Objects.requireNonNull(result, "构建结果不能为空");
        String resultTag = buildResultTag(result);
        String stageTag = tag(result.stage());
        String failureKindTag = result.failureKind() == null
                ? "none" : tag(result.failureKind());
        safely(() -> {
            counter("vue_build_attempts_total",
                    "attempt", Integer.toString(attempt),
                    "result", resultTag,
                    "stage", stageTag,
                    "failure_kind", failureKindTag).increment();
            Timer.builder("vue_build_attempt_duration_seconds")
                    .description("Vue 在线真实构建耗时")
                    .publishPercentileHistogram()
                    .tags("attempt", Integer.toString(attempt),
                            "result", resultTag, "stage", stageTag)
                    .register(registry)
                    .record(Duration.ofMillis(result.durationMillis()));
        });
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }

    private void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // 观测故障必须完全旁路，不能改变在线生成和构建语义。
        }
    }

    private static String buildResultTag(BuildResult result) {
        if (result.success()) {
            return "succeeded";
        }
        if (result.cancelled()) {
            return "cancelled";
        }
        if (result.timedOut()) {
            return "timed_out";
        }
        return "failed";
    }

    private static void validateAttempt(int attempt) {
        if (attempt < 1 || attempt > 3) {
            throw new IllegalArgumentException("构建次数必须在 1 到 3 之间");
        }
    }

    private static String tag(Enum<?> value) {
        return Objects.requireNonNull(value, "指标枚举不能为空")
                .name().toLowerCase(Locale.ROOT);
    }

    /** 单次构建的 CAS 终态句柄，重复完成不重复计数。 */
    public static final class BuildAttemptObservation {

        private final VueBuildRepairMetricsCollector collector;
        private final int attempt;
        private final AtomicBoolean completed = new AtomicBoolean();

        private BuildAttemptObservation(
                VueBuildRepairMetricsCollector collector, int attempt) {
            this.collector = collector;
            this.attempt = attempt;
        }

        public boolean complete(BuildResult result) {
            Objects.requireNonNull(result, "构建结果不能为空");
            if (!completed.compareAndSet(false, true)) {
                return false;
            }
            collector.completeBuildAttempt(attempt, result);
            return true;
        }
    }

    public enum CancellationTrigger {
        SUBSCRIBER_CANCELLED, ABSOLUTE_DEADLINE, BUILD_TIMEOUT, DELETE_TAKEOVER
    }

    public enum AdmissionResult {
        ACQUIRED, REJECTED, RELEASED
    }

    public enum CancellationResult {
        REQUESTED, COMPLETED, TIMED_OUT, FAILED
    }

    public enum MemoryAction {
        COLLAPSE, REBUILD, INVALIDATE
    }

    public enum MemoryResult {
        SUCCEEDED, FAILED, EMPTY
    }
}
