package com.lyw.appgeneration.monitor;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiModelMetricsCollectorTest {

    @Test
    void exposesConstructorInjectionBoundaryForLowCardinalityMetrics() {
        assertDoesNotThrow(() -> AiModelMetricsCollector.class
                .getConstructor(MeterRegistry.class));
    }

    @Test
    void replacesArbitraryStringMetricApiWithTypedOperations() {
        Set<String> metricOperations = Arrays.stream(
                        AiModelMetricsCollector.class.getMethods())
                .filter(method -> method.getName().startsWith("record"))
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "recordRequest",
                "recordError",
                "recordTokenUsage",
                "recordResponseTime",
                "recordTokenEstimationRatio"), metricOperations);
        assertDoesNotThrow(() -> Class.forName(
                AiModelMetricsCollector.class.getName() + "$ModelFamily"));
        assertDoesNotThrow(() -> Class.forName(
                AiModelMetricsCollector.class.getName() + "$RequestStatus"));
        assertDoesNotThrow(() -> Class.forName(
                AiModelMetricsCollector.class.getName() + "$ErrorType"));
        assertDoesNotThrow(() -> Class.forName(
                AiModelMetricsCollector.class.getName() + "$TokenType"));
        assertDoesNotThrow(() -> Class.forName(
                AiModelMetricsCollector.class.getName() + "$ResponseOutcome"));
        assertTrue(Arrays.stream(AiModelMetricsCollector.class.getMethods())
                .filter(method -> method.getName().startsWith("record"))
                .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                .noneMatch(String.class::equals));
        assertTrue(Arrays.stream(
                        AiModelMetricsCollector.class.getDeclaredFields())
                .map(field -> field.getType())
                .noneMatch(ConcurrentMap.class::isAssignableFrom));
    }

    @Test
    void prometheusContractRemovesBusinessIdsRawModelAndRawError() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(
                PrometheusConfig.DEFAULT);
        AiModelMetricsCollector collector =
                new AiModelMetricsCollector(registry);
        AiModelMetricsCollector.ModelFamily family =
                AiModelMetricsCollector.ModelFamily.UNKNOWN;

        collector.recordRequest(
                family, AiModelMetricsCollector.RequestStatus.STARTED);
        collector.recordError(
                family, AiModelMetricsCollector.ErrorType.UNKNOWN);
        collector.recordTokenUsage(
                family, AiModelMetricsCollector.TokenType.INPUT, 120);
        collector.recordResponseTime(
                family,
                AiModelMetricsCollector.ResponseOutcome.ERROR,
                Duration.ofMillis(25));
        collector.recordTokenEstimationRatio(family, 1.25D);

        assertMetricTags(registry, "ai_model_requests_total",
                Set.of("model_family", "status"));
        assertMetricTags(registry, "ai_model_errors_total",
                Set.of("model_family", "error_type"));
        assertMetricTags(registry, "ai_model_tokens_total",
                Set.of("model_family", "token_type"));
        assertMetricTags(registry, "ai_model_response_duration_seconds",
                Set.of("model_family", "outcome"));
        assertMetricTags(registry, "memory_token_estimation_ratio",
                Set.of("model_family"));
        assertEquals(1.25D, registry.get("memory_token_estimation_ratio")
                .summary().totalAmount());

        String scrape = registry.scrape();
        assertScrapeSample(scrape, "ai_model_requests_total", Map.of(
                "model_family", "unknown", "status", "started"));
        assertScrapeSample(scrape, "ai_model_errors_total", Map.of(
                "model_family", "unknown", "error_type", "unknown"));
        assertScrapeSample(scrape, "ai_model_tokens_total", Map.of(
                "model_family", "unknown", "token_type", "input"));
        assertScrapeSample(scrape,
                "ai_model_response_duration_seconds_count",
                Map.of("model_family", "unknown", "outcome", "error"));
        assertScrapeSample(scrape,
                "memory_token_estimation_ratio_count",
                Map.of("model_family", "unknown"));
        for (String forbidden : Set.of(
                "user_id", "app_id", "model_name", "error_message",
                "user-7788", "app-9911", "private-model-20260816",
                "secret-exception-message")) {
            assertFalse(scrape.contains(forbidden), forbidden);
        }
    }

    @Test
    void modelAndErrorMappingsAreControlled() {
        assertDoesNotThrow(() -> {
            Method modelMapping = AiModelMetricsCollector.ModelFamily.class
                    .getMethod("fromModelName", String.class);
            assertEquals(AiModelMetricsCollector.ModelFamily.DEEPSEEK,
                    modelMapping.invoke(null, "deepseek-chat"));
            assertEquals(AiModelMetricsCollector.ModelFamily.QWEN,
                    modelMapping.invoke(null, "QWEN-max"));
            assertEquals(AiModelMetricsCollector.ModelFamily.OPENAI,
                    modelMapping.invoke(null, "gpt-5"));
            assertEquals(AiModelMetricsCollector.ModelFamily.UNKNOWN,
                    modelMapping.invoke(null, "private-model-20260816"));
            Method errorMapping = AiModelMetricsCollector.ErrorType.class
                    .getMethod("fromThrowable", Throwable.class);
            assertEquals(AiModelMetricsCollector.ErrorType.TIMEOUT,
                    errorMapping.invoke(null, new TimeoutException(
                            "secret-exception-message")));
            assertEquals(AiModelMetricsCollector.ErrorType.NETWORK,
                    errorMapping.invoke(null, new IOException(
                            "secret-exception-message")));
            assertEquals(AiModelMetricsCollector.ErrorType.UNKNOWN,
                    errorMapping.invoke(null, new IllegalStateException(
                            "secret-exception-message")));
        });
    }

    @ParameterizedTest
    @EnumSource(ThrowingMeterRegistry.FailurePoint.class)
    void meterFailureNeverEscapesIntoModelBusiness(
            ThrowingMeterRegistry.FailurePoint failurePoint) {
        ThrowingMeterRegistry registry = new ThrowingMeterRegistry(failurePoint);
        AiModelMetricsCollector collector =
                new AiModelMetricsCollector(registry);

        assertDoesNotThrow(() -> {
            collector.recordRequest(
                    AiModelMetricsCollector.ModelFamily.OPENAI,
                    AiModelMetricsCollector.RequestStatus.STARTED);
            collector.recordResponseTime(
                    AiModelMetricsCollector.ModelFamily.OPENAI,
                    AiModelMetricsCollector.ResponseOutcome.SUCCESS,
                    Duration.ofMillis(5));
            collector.recordTokenEstimationRatio(
                    AiModelMetricsCollector.ModelFamily.OPENAI, 1D);
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
