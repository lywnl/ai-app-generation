package com.lyw.appgeneration.monitor;

import com.lyw.appgeneration.core.builder.BuildResult;
import com.lyw.appgeneration.core.builder.BuildStage;
import com.lyw.appgeneration.core.builder.VueBuildFailureKind;
import com.lyw.appgeneration.core.builder.VueBuildPhase;
import com.lyw.appgeneration.core.handler.VueTurnOutcome;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class VueBuildRepairMetricsCollectorTest {

    @Test
    void buildAttemptOnlyCompletesOnceAndRejectsAttemptsOutsideOneToThree() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        VueBuildRepairMetricsCollector collector =
                new VueBuildRepairMetricsCollector(registry);

        VueBuildRepairMetricsCollector.BuildAttemptObservation observation =
                collector.startBuildAttempt(1);

        assertTrue(observation.complete(success()));
        assertFalse(observation.complete(success()));
        assertEquals(1.0, registry.get("vue_build_attempts_total")
                .tags("attempt", "1", "result", "succeeded",
                        "stage", "success", "failure_kind", "none")
                .counter().count());
        assertThrows(IllegalArgumentException.class,
                () -> collector.startBuildAttempt(0));
        assertThrows(IllegalArgumentException.class,
                () -> collector.startBuildAttempt(4));
    }

    @Test
    void concurrentBuildCompletionStillRecordsExactlyOneResult() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        VueBuildRepairMetricsCollector collector =
                new VueBuildRepairMetricsCollector(registry);
        VueBuildRepairMetricsCollector.BuildAttemptObservation observation =
                collector.startBuildAttempt(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> completeAfterStart(
                    observation, ready, start));
            var second = executor.submit(() -> completeAfterStart(
                    observation, ready, start));
            ready.await();
            start.countDown();

            assertTrue(first.get() ^ second.get());
        }
        assertEquals(1.0, registry.get("vue_build_attempts_total")
                .tags("attempt", "2", "result", "failed",
                        "stage", "npm_build", "failure_kind", "code")
                .counter().count());
    }

    @Test
    void turnOutcomeUsesOutcomePhaseInsteadOfInferringFromBuildAttempt() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        VueBuildRepairMetricsCollector collector =
                new VueBuildRepairMetricsCollector(registry);
        VueTurnOutcome outcome = new VueTurnOutcome(
                VueBuildPhase.REPAIRING,
                VueTurnOutcome.TurnOutcomeType.CANCELLED,
                "已取消", false, "已取消");

        collector.recordTurnOutcome(outcome);

        assertEquals(1.0, registry.get("vue_turn_outcomes_total")
                .tags("outcome", "cancelled", "phase", "repairing")
                .counter().count());
    }

    @Test
    void prometheusScrapeHasExactLowCardinalityMetricNamesTagsAndHistogram() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(
                PrometheusConfig.DEFAULT);
        VueBuildRepairMetricsCollector collector =
                new VueBuildRepairMetricsCollector(registry);

        collector.startBuildAttempt(3).complete(failed());
        collector.recordTurnOutcome(new VueTurnOutcome(
                VueBuildPhase.FINAL_DIAGNOSIS,
                VueTurnOutcome.TurnOutcomeType.PROTOCOL_ERROR,
                "协议异常", false, "协议异常"));
        collector.recordCancellation(
                VueBuildRepairMetricsCollector.CancellationTrigger.ABSOLUTE_DEADLINE,
                VueBuildRepairMetricsCollector.CancellationResult.TIMED_OUT);
        collector.recordMemoryL0Sync(
                VueBuildRepairMetricsCollector.MemoryAction.REBUILD,
                VueBuildRepairMetricsCollector.MemoryResult.EMPTY);

        assertMetricTags(registry, "vue_build_attempts_total",
                Set.of("attempt", "result", "stage", "failure_kind"));
        assertMetricTags(registry, "vue_build_attempt_duration_seconds",
                Set.of("attempt", "result", "stage"));
        assertMetricTags(registry, "vue_turn_outcomes_total",
                Set.of("outcome", "phase"));
        assertMetricTags(registry, "vue_turn_cancellations_total",
                Set.of("trigger", "result"));
        assertMetricTags(registry, "vue_memory_l0_sync_total",
                Set.of("action", "result"));
        String scrape = registry.scrape();
        assertTrue(scrape.contains("vue_build_attempts_total"));
        assertTrue(scrape.contains("vue_build_attempt_duration_seconds_bucket"));
        assertFalse(scrape.contains("vue_process_cleanup_total"));
        for (String forbidden : Set.of("appId", "app_id", "userId", "user_id",
                "turnId", "turn_id", "ownerToken", "owner_token", "path",
                "error", "exception")) {
            assertFalse(scrape.contains(forbidden + "="), forbidden);
        }
    }

    @Test
    void registryFailureIsBypassedWithoutChangingObservationResult() {
        MeterRegistry registry = mock(MeterRegistry.class);
        VueBuildRepairMetricsCollector collector =
                new VueBuildRepairMetricsCollector(registry);

        assertTrue(collector.startBuildAttempt(1).complete(success()));
        collector.recordCancellation(
                VueBuildRepairMetricsCollector.CancellationTrigger.BUILD_TIMEOUT,
                VueBuildRepairMetricsCollector.CancellationResult.COMPLETED);
        collector.recordTurnAdmission(
                VueBuildRepairMetricsCollector.AdmissionResult.RELEASED);
    }

    @Test
    void 回合准入指标只能记录三种固定结果标签() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        VueBuildRepairMetricsCollector collector =
                new VueBuildRepairMetricsCollector(registry);

        for (VueBuildRepairMetricsCollector.AdmissionResult result
                : VueBuildRepairMetricsCollector.AdmissionResult.values()) {
            collector.recordTurnAdmission(result);
        }

        assertEquals(1.0, registry.get("vue_turn_admissions_total")
                .tag("result", "acquired").counter().count());
        assertEquals(1.0, registry.get("vue_turn_admissions_total")
                .tag("result", "rejected").counter().count());
        assertEquals(1.0, registry.get("vue_turn_admissions_total")
                .tag("result", "released").counter().count());
        assertMetricTags(registry, "vue_turn_admissions_total",
                Set.of("result"));
    }

    private static boolean completeAfterStart(
            VueBuildRepairMetricsCollector.BuildAttemptObservation observation,
            CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return observation.complete(failed());
    }

    private static void assertMetricTags(
            MeterRegistry registry, String name, Set<String> expectedTags) {
        Set<String> actualTags = registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals(name))
                .findFirst()
                .orElseThrow()
                .getId().getTags().stream()
                .map(tag -> tag.getKey())
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(expectedTags, actualTags, name);
    }

    private static BuildResult success() {
        return new BuildResult(true, BuildStage.SUCCESS, 0,
                false, false, null, "", 12L);
    }

    private static BuildResult failed() {
        return new BuildResult(false, BuildStage.NPM_BUILD, 1,
                false, false, VueBuildFailureKind.CODE, "", 12L);
    }
}
