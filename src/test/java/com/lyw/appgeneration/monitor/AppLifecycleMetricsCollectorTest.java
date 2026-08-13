package com.lyw.appgeneration.monitor;

import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager.AppOperationType;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AppLifecycleMetricsCollectorTest {

    @Test
    void protocolAndPublisherObservationsAreIndependentAndFirstResultWins() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AppLifecycleMetricsCollector collector =
                new AppLifecycleMetricsCollector(registry);
        var protocol = collector.startSseProtocolObservation();
        var publisher = collector.startSsePublisherObservation();

        assertTrue(protocol.complete(
                AppLifecycleMetricsCollector.SseProtocolResult.PROTOCOL_ERROR));
        assertFalse(protocol.complete(
                AppLifecycleMetricsCollector.SseProtocolResult.DONE));
        assertTrue(publisher.complete(
                AppLifecycleMetricsCollector.SsePublisherResult.SUBSCRIBER_CANCELLED));
        assertFalse(publisher.complete(
                AppLifecycleMetricsCollector.SsePublisherResult.COMPLETED));

        assertEquals(1.0, registry.get("generation_sse_protocol_results_total")
                .tag("result", "protocol_error").counter().count());
        assertEquals(1.0, registry.get("generation_sse_publisher_terminations_total")
                .tag("result", "subscriber_cancelled").counter().count());
    }

    @Test
    void concurrentPublisherCompletionStillRecordsExactlyOneTermination() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AppLifecycleMetricsCollector collector =
                new AppLifecycleMetricsCollector(registry);
        var observation = collector.startSsePublisherObservation();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> completeAfterStart(
                    observation, ready, start,
                    AppLifecycleMetricsCollector.SsePublisherResult.COMPLETED));
            var second = executor.submit(() -> completeAfterStart(
                    observation, ready, start,
                    AppLifecycleMetricsCollector.SsePublisherResult.PUBLISHER_ERROR));
            ready.await();
            start.countDown();
            assertTrue(first.get() ^ second.get());
        }
        double completions = counterCount(registry,
                "generation_sse_publisher_terminations_total", "completed");
        double errors = counterCount(registry,
                "generation_sse_publisher_terminations_total", "publisher_error");
        assertEquals(1.0, completions + errors);
    }

    @Test
    void prometheusScrapeUsesOnlyContractedGenericMetricNamesAndTags() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(
                PrometheusConfig.DEFAULT);
        AppLifecycleMetricsCollector collector =
                new AppLifecycleMetricsCollector(registry);

        collector.recordOperation(AppOperationType.GENERATE,
                AppLifecycleMetricsCollector.OperationResult.REJECTED,
                AppOperationType.DELETE);
        collector.recordOperationCancellation(
                AppLifecycleMetricsCollector.OperationCancellationTrigger.DELETE_TAKEOVER,
                AppLifecycleMetricsCollector.OperationCancellationResult.TIMED_OUT);
        collector.startSseProtocolObservation().complete(
                AppLifecycleMetricsCollector.SseProtocolResult.SYSTEM_ERROR);
        collector.startSsePublisherObservation().complete(
                AppLifecycleMetricsCollector.SsePublisherResult.PUBLISHER_ERROR);

        assertMetricTags(registry, "app_operations_total",
                Set.of("operation", "result", "conflict_with"));
        assertMetricTags(registry, "app_operation_cancellations_total",
                Set.of("trigger", "result"));
        assertMetricTags(registry, "generation_sse_protocol_results_total",
                Set.of("result"));
        assertMetricTags(registry, "generation_sse_publisher_terminations_total",
                Set.of("result"));
        String scrape = registry.scrape();
        assertTrue(scrape.contains("generation_sse_protocol_results_total"));
        assertTrue(scrape.contains("generation_sse_publisher_terminations_total"));
        assertFalse(scrape.contains("vue_sse_sessions_total"));
        assertFalse(scrape.contains("client_cancel"));
        assertFalse(scrape.contains("write_error"));
    }

    @Test
    void registryFailureDoesNotChangeOperationObservationCallers() {
        AppLifecycleMetricsCollector collector =
                new AppLifecycleMetricsCollector(mock(MeterRegistry.class));

        collector.recordOperation(AppOperationType.DOWNLOAD,
                AppLifecycleMetricsCollector.OperationResult.ACQUIRED, null);
        assertTrue(collector.startSseProtocolObservation().complete(
                AppLifecycleMetricsCollector.SseProtocolResult.DONE));
    }

    private static boolean completeAfterStart(
            AppLifecycleMetricsCollector.SsePublisherObservation observation,
            CountDownLatch ready, CountDownLatch start,
            AppLifecycleMetricsCollector.SsePublisherResult result)
            throws InterruptedException {
        ready.countDown();
        start.await();
        return observation.complete(result);
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

    private static double counterCount(
            MeterRegistry registry, String name, String result) {
        return registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals(name))
                .filter(meter -> result.equals(meter.getId().getTag("result")))
                .mapToDouble(meter -> meter.measure().iterator().next().getValue())
                .sum();
    }
}
