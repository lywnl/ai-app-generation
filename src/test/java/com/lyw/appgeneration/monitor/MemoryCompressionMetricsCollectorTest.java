package com.lyw.appgeneration.monitor;

import com.lyw.appgeneration.ai.memory.ContextAdmissionResult;
import com.lyw.appgeneration.ai.memory.ContextCompressionMode;
import com.lyw.appgeneration.service.MemoryCompressionResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryCompressionMetricsCollectorTest {

    @Test
    void exposesConstructorInjectionBoundaryForBypassMetrics() {
        assertDoesNotThrow(() -> Class.forName(
                        "com.lyw.appgeneration.monitor."
                                + "MemoryCompressionMetricsCollector")
                .getConstructor(MeterRegistry.class));
    }

    @Test
    void springUsesTheMeterRegistryConstructor() throws Exception {
        assertTrue(MemoryCompressionMetricsCollector.class
                .getConstructor(MeterRegistry.class)
                .isAnnotationPresent(Autowired.class));
    }

    @Test
    void exposesOnlyTypedLowCardinalityRecordingOperations() {
        assertDoesNotThrow(() -> {
            Class<?> collector = Class.forName(
                    "com.lyw.appgeneration.monitor."
                            + "MemoryCompressionMetricsCollector");
            Set<String> operations = Arrays.stream(collector.getMethods())
                    .map(Method::getName)
                    .filter(name -> name.startsWith("record")
                            || name.startsWith("start"))
                    .collect(Collectors.toSet());

            assertEquals(Set.of(
                    "recordContextGate",
                    "recordEstimatedTokens",
                    "startToolChainCheckpoint",
                    "startCompression",
                    "recordCompressionExecutorRejected",
                    "recordSummaryDraftSuccess",
                    "recordSummaryDraftFailure",
                    "recordL2Debounce",
                    "recordL2Candidate",
                    "recordL2RecallTokens"), operations);
            assertTrue(Arrays.stream(collector.getMethods())
                    .filter(method -> operations.contains(method.getName()))
                    .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                    .noneMatch(String.class::equals),
                    "指标公共 API 不得允许任意字符串标签");
            Method complete = MemoryCompressionMetricsCollector
                    .CheckpointObservation.class.getMethod(
                            "complete",
                            MemoryCompressionMetricsCollector
                                    .CheckpointOutcome.class,
                            int.class);
            assertEquals(boolean.class, complete.getReturnType());
            assertEquals(List.of(
                            MemoryCompressionMetricsCollector
                                    .CheckpointOutcome.class,
                            int.class),
                    List.of(complete.getParameterTypes()));
        });
    }

    @Test
    void prometheusContractUsesExactLowCardinalityNamesAndTags() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(
                PrometheusConfig.DEFAULT);
        MemoryCompressionMetricsCollector collector =
                new MemoryCompressionMetricsCollector(registry);

        collector.recordContextGate(
                ContextCompressionMode.BLOCKING_COMPLETED,
                ContextAdmissionResult.FailureReason.NONE);
        collector.recordEstimatedTokens(
                MemoryCompressionMetricsCollector.EstimationStage.BEFORE,
                30_720);
        collector.startToolChainCheckpoint(65_536).complete(
                MemoryCompressionMetricsCollector.CheckpointOutcome.SUCCESS,
                18_000);
        collector.startCompression(
                        MemoryCompressionMetricsCollector.CompressionMode.BLOCKING)
                .complete(MemoryCompressionResult.Status.COMPRESSED);
        collector.recordSummaryDraftSuccess(3_000, 2);
        collector.recordL2Debounce(
                MemoryCompressionMetricsCollector.DebounceOutcome.SUBMITTED);
        collector.recordL2Candidate(
                MemoryCompressionMetricsCollector.CandidateStatus.ACTIVE);
        collector.recordL2RecallTokens(800);

        assertMetricTags(registry, "memory_context_gate_total",
                Set.of("mode", "outcome"));
        assertMetricTags(registry, "memory_context_estimated_tokens",
                Set.of("stage"));
        assertMetricTags(registry, "memory_tool_chain_checkpoint_total",
                Set.of("outcome"));
        assertMetricTags(registry, "memory_tool_chain_checkpoint_tokens",
                Set.of("stage"));
        assertMetricTags(registry,
                "memory_tool_chain_checkpoint_duration_seconds",
                Set.of("outcome"));
        assertMetricTags(registry, "memory_compression_total",
                Set.of("mode", "outcome"));
        assertMetricTags(registry, "memory_compression_duration_seconds",
                Set.of("mode", "outcome"));
        assertMetricTags(registry, "memory_summary_tokens", Set.of());
        assertMetricTags(registry, "memory_summary_reduce_rounds", Set.of());
        assertMetricTags(registry, "memory_l2_debounce_total",
                Set.of("outcome"));
        assertMetricTags(registry, "memory_l2_candidate_total",
                Set.of("status"));
        assertMetricTags(registry, "memory_l2_recall_tokens", Set.of());
        assertEquals(3_000D, registry.get("memory_summary_tokens")
                .summary().totalAmount());
        assertEquals(2D, registry.get("memory_summary_reduce_rounds")
                .summary().totalAmount());

        String scrape = registry.scrape();
        assertScrapeSample(scrape, "memory_context_gate_total", Map.of(
                "mode", "blocking_completed", "outcome", "none"));
        assertScrapeSample(scrape,
                "memory_context_estimated_tokens_count",
                Map.of("stage", "before"));
        assertScrapeSample(scrape,
                "memory_tool_chain_checkpoint_total",
                Map.of("outcome", "success"));
        assertScrapeSample(scrape,
                "memory_tool_chain_checkpoint_tokens_count",
                Map.of("stage", "before"));
        assertScrapeSample(scrape,
                "memory_tool_chain_checkpoint_tokens_count",
                Map.of("stage", "after"));
        assertScrapeSample(scrape,
                "memory_tool_chain_checkpoint_duration_seconds_count",
                Map.of("outcome", "success"));
        assertScrapeSample(scrape, "memory_compression_total", Map.of(
                "mode", "blocking", "outcome", "compressed"));
        assertScrapeSample(scrape,
                "memory_compression_duration_seconds_count",
                Map.of("mode", "blocking", "outcome", "compressed"));
        assertScrapeSample(scrape, "memory_summary_tokens_count", Map.of());
        assertScrapeSample(scrape,
                "memory_summary_reduce_rounds_count", Map.of());
        assertScrapeSample(scrape, "memory_l2_debounce_total",
                Map.of("outcome", "submitted"));
        assertScrapeSample(scrape, "memory_l2_candidate_total",
                Map.of("status", "active"));
        assertScrapeSample(scrape, "memory_l2_recall_tokens_count", Map.of());
        for (String forbidden : Set.of(
                "appId", "app_id", "userId", "user_id",
                "turnId", "turn_id", "model_name", "error_message",
                "敏感用户正文", "敏感摘要正文", "敏感工具正文",
                "/src/secret.vue", "<template>源码</template>",
                "{\"path\":\"secret.vue\"}")) {
            assertFalse(scrape.contains(forbidden), forbidden);
        }
    }

    @Test
    void executorRejectionRecordsCounterWithoutInventingDuration() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MemoryCompressionMetricsCollector collector =
                new MemoryCompressionMetricsCollector(registry);

        collector.recordCompressionExecutorRejected(
                MemoryCompressionMetricsCollector.CompressionMode.ASYNC);

        Counter rejected = registry.find("memory_compression_total")
                .tags("mode", "async", "outcome", "executor_rejected")
                .counter();
        assertNotNull(rejected);
        assertEquals(1D, rejected.count());
        assertTrue(registry.find("memory_compression_duration_seconds")
                .timers().isEmpty());
    }

    @Test
    void compressionObservationCompletesOnlyOnce() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MemoryCompressionMetricsCollector collector =
                new MemoryCompressionMetricsCollector(registry);
        MemoryCompressionMetricsCollector.CompressionObservation observation =
                collector.startCompression(
                        MemoryCompressionMetricsCollector.CompressionMode.BLOCKING);

        assertTrue(observation.complete(
                MemoryCompressionResult.Status.COMPRESSED));
        assertFalse(observation.complete(
                MemoryCompressionResult.Status.MODEL_FAILED));
        assertEquals(1D, registry.get("memory_compression_total")
                .tags("mode", "blocking", "outcome", "compressed")
                .counter().count());
        assertEquals(1L, registry.get("memory_compression_duration_seconds")
                .tags("mode", "blocking", "outcome", "compressed")
                .timer().count());
    }

    @Test
    void toolChainCheckpointObservationCompletesOnlyOnceWithFinalTokens() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MemoryCompressionMetricsCollector collector =
                new MemoryCompressionMetricsCollector(registry);
        MemoryCompressionMetricsCollector.CheckpointObservation observation =
                collector.startToolChainCheckpoint(65_536);

        assertTrue(observation.complete(
                MemoryCompressionMetricsCollector.CheckpointOutcome.FAILED,
                65_536));
        assertFalse(observation.complete(
                MemoryCompressionMetricsCollector.CheckpointOutcome.SUCCESS,
                18_000));
        assertEquals(1D, registry.get("memory_tool_chain_checkpoint_total")
                .tags("outcome", "failed").counter().count());
        assertEquals(1L, registry.get("memory_tool_chain_checkpoint_tokens")
                .tags("stage", "before").summary().count());
        assertEquals(65_536D,
                registry.get("memory_tool_chain_checkpoint_tokens")
                        .tags("stage", "before").summary().totalAmount());
        assertEquals(1L, registry.get("memory_tool_chain_checkpoint_tokens")
                .tags("stage", "after").summary().count());
        assertEquals(65_536D,
                registry.get("memory_tool_chain_checkpoint_tokens")
                        .tags("stage", "after").summary().totalAmount());
        assertEquals(1L,
                registry.get("memory_tool_chain_checkpoint_duration_seconds")
                        .tags("outcome", "failed").timer().count());
        assertTrue(registry.find("memory_tool_chain_checkpoint_total")
                .tags("outcome", "success").counter() == null);
    }

    @Test
    void toolChainCheckpointClockFailureIsFullyBypassed() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MemoryCompressionMetricsCollector collector =
                new MemoryCompressionMetricsCollector(registry, () -> {
                    throw new IllegalStateException("clock down");
                });

        MemoryCompressionMetricsCollector.CheckpointObservation observation =
                assertDoesNotThrow(
                        () -> collector.startToolChainCheckpoint(65_536));
        assertTrue(assertDoesNotThrow(() -> observation.complete(
                MemoryCompressionMetricsCollector.CheckpointOutcome.SUCCESS,
                18_000)));
        assertEquals(1D, registry.get("memory_tool_chain_checkpoint_total")
                .tags("outcome", "success").counter().count());
        assertEquals(List.of(65_536D, 18_000D), List.of(
                registry.get("memory_tool_chain_checkpoint_tokens")
                        .tags("stage", "before").summary().totalAmount(),
                registry.get("memory_tool_chain_checkpoint_tokens")
                        .tags("stage", "after").summary().totalAmount()));
        assertTrue(registry.find(
                        "memory_tool_chain_checkpoint_duration_seconds")
                .timers().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(ThrowingMeterRegistry.FailurePoint.class)
    void meterFailureNeverEscapesIntoMemoryBusiness(
            ThrowingMeterRegistry.FailurePoint failurePoint) {
        ThrowingMeterRegistry registry = new ThrowingMeterRegistry(failurePoint);
        MemoryCompressionMetricsCollector collector =
                new MemoryCompressionMetricsCollector(registry);

        assertDoesNotThrow(() -> {
            collector.startToolChainCheckpoint(65_536).complete(
                    MemoryCompressionMetricsCollector.CheckpointOutcome.FAILED,
                    65_536);
            collector.recordContextGate(
                    ContextCompressionMode.NORMAL,
                    ContextAdmissionResult.FailureReason.NONE);
            collector.recordEstimatedTokens(
                    MemoryCompressionMetricsCollector.EstimationStage.BEFORE,
                    100);
            collector.startCompression(
                            MemoryCompressionMetricsCollector.CompressionMode.BLOCKING)
                    .complete(MemoryCompressionResult.Status.COMPRESSED);
            collector.recordSummaryDraftSuccess(80, 0);
            collector.recordSummaryDraftFailure(1);
        });
        assertTrue(registry.failureTriggered());
    }

    private static void assertMetricTags(
            MeterRegistry registry, String name, Set<String> expectedTags) {
        java.util.List<io.micrometer.core.instrument.Meter> matches =
                registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals(name))
                .toList();
        assertFalse(matches.isEmpty(), "缺少指标: " + name);
        Set<String> actualTags = matches.getFirst()
                .getId().getTags().stream()
                .map(tag -> tag.getKey())
                .collect(Collectors.toSet());
        assertEquals(expectedTags, actualTags, name);
    }

    private static void assertScrapeSample(
            String scrape, String sampleName, Map<String, String> tags) {
        java.util.List<String> lines = scrape.lines()
                .filter(candidate -> candidate.startsWith(
                        sampleName + (tags.isEmpty() ? " " : "{")))
                .toList();
        assertFalse(lines.isEmpty(), () ->
                "scrape 缺少精确样本：" + sampleName + "\n" + scrape);
        if (tags.isEmpty()) {
            assertTrue(lines.stream().noneMatch(line -> line.contains("{")),
                    sampleName);
            return;
        }
        for (String line : lines) {
            String tagBlock = line.substring(
                    line.indexOf('{') + 1, line.indexOf('}'));
            Set<String> actualTagKeys = Arrays.stream(tagBlock.split(","))
                    .map(tag -> tag.substring(0, tag.indexOf('=')))
                    .collect(Collectors.toSet());
            assertEquals(tags.keySet(), actualTagKeys, sampleName);
        }
        assertTrue(lines.stream().anyMatch(candidate ->
                        tags.entrySet().stream().allMatch(entry ->
                                candidate.contains(entry.getKey() + "=\""
                                        + entry.getValue() + "\""))),
                () -> "scrape 缺少指定标签值：" + sampleName
                        + " tags=" + tags + "\n" + scrape);
    }
}
