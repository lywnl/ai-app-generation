package com.lyw.appgeneration.service.rag.monitor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VueRagMetricsCollectorTest {

    @Test
    void recordsRealStageCountsDurationAndContextLength() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        VueRagMetricsCollector collector = new VueRagMetricsCollector(registry);

        collector.recordRetrievalDuration(Duration.ofMillis(125));
        collector.recordBm25Candidates(7);
        collector.recordDenseCandidates(5);
        collector.recordRrfCandidates(9);
        collector.recordRerankCandidates(3);
        collector.recordFinalSelection(1, 4);
        collector.recordContextLength(8765);

        assertEquals(1, registry.get("vue_rag_retrieval_duration_seconds").timer().count());
        assertEquals(125, registry.get("vue_rag_retrieval_duration_seconds")
                .timer().totalTime(TimeUnit.MILLISECONDS), 0.001);
        assertSummary(registry, "vue_rag_bm25_candidates", 7);
        assertSummary(registry, "vue_rag_dense_candidates", 5);
        assertSummary(registry, "vue_rag_rrf_candidates", 9);
        assertSummary(registry, "vue_rag_rerank_candidates", 3);
        assertSummary(registry, "vue_rag_final_skeletons", 1);
        assertSummary(registry, "vue_rag_final_features", 4);
        assertSummary(registry, "vue_rag_context_length", 8765);
    }

    @Test
    void degradationReasonIsRestrictedToFiniteEnumValues() {
        assertEquals(Set.of(
                        "bm25_failed",
                        "dense_failed",
                        "rerank_failed",
                        "fallback_skeleton",
                        "catalog_unavailable"),
                Arrays.stream(VueRagDegradationReason.values())
                        .map(VueRagDegradationReason::tagValue)
                        .collect(Collectors.toSet()));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        VueRagMetricsCollector collector = new VueRagMetricsCollector(registry);
        collector.recordDegradation(VueRagDegradationReason.BM25_FAILED);
        collector.recordDegradation(VueRagDegradationReason.RERANK_FAILED);

        assertEquals(1, registry.get("vue_rag_degradations_total")
                .tag("reason", "bm25_failed").counter().count());
        assertEquals(1, registry.get("vue_rag_degradations_total")
                .tag("reason", "rerank_failed").counter().count());
        assertEquals(Set.of(
                        "bm25_failed",
                        "dense_failed",
                        "rerank_failed",
                        "fallback_skeleton",
                        "catalog_unavailable"),
                registry.find("vue_rag_degradations_total")
                .counters().stream()
                .map(counter -> counter.getId().getTag("reason"))
                .collect(Collectors.toSet()));
        assertEquals(0, registry.get("vue_rag_degradations_total")
                .tag("reason", "dense_failed").counter().count());
    }

    private void assertSummary(SimpleMeterRegistry registry, String name, double expectedTotal) {
        assertEquals(1, registry.get(name).summary().count());
        assertEquals(expectedTotal, registry.get(name).summary().totalAmount(), 0.001);
    }
}
