package com.lyw.appgeneration.rag.vue;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VueRetrievalEvaluationReportTest {

    @Test
    void unexecutedReportNeverClaimsPassingOrRendersFakeMetrics() {
        String markdown = VueRetrievalEvaluationReport.notExecuted(
                List.of("缺少环境变量 DASHSCOPE_API_KEY")).renderMarkdown();

        assertTrue(markdown.contains("状态：未执行"));
        assertTrue(markdown.contains("DASHSCOPE_API_KEY"));
        assertFalse(markdown.contains("状态：通过"));
        assertFalse(markdown.contains("Skeleton Hit@1 |"));
    }

    @Test
    void executedReportRendersBothMetricsDeltasStylesAndActualRows() {
        VueRetrievalMetrics hybrid = new VueRetrievalMetrics(
                0.90, 0.85, 30,
                Map.of("精确技术词", new VueRetrievalMetrics.StyleSlice(1.0, 0.9, 6)));
        VueRetrievalMetrics dense = new VueRetrievalMetrics(
                0.90, 0.85, 30,
                Map.of("精确技术词", new VueRetrievalMetrics.StyleSlice(0.9, 0.8, 6)));
        VueRetrievalObservation observation = new VueRetrievalObservation(
                new VueEvalCase("q1", "需求", "精确技术词", List.of("s1"), List.of("f1")),
                "s1", List.of("f1"), null);

        String markdown = VueRetrievalEvaluationReport.executed(
                VueRetrievalComparison.compare(hybrid, dense),
                List.of(observation),
                List.of(observation)).renderMarkdown();

        assertTrue(markdown.contains("状态：通过"));
        assertTrue(markdown.contains("Skeleton Hit@1"));
        assertTrue(markdown.contains("Feature Recall@4"));
        assertTrue(markdown.contains("Hybrid - Dense"));
        assertTrue(markdown.contains("精确技术词"));
        assertTrue(markdown.contains("q1"));
        assertTrue(markdown.contains("s1"));
        assertTrue(markdown.contains("f1"));
    }

    @Test
    void sanitizesEveryFinalReportFieldIncludingReasonAndRows() {
        String notExecuted = VueRetrievalEvaluationReport.notExecuted(List.of(
                "检查 C:\\Users\\alice\\private 失败，Authorization: Bearer reason-secret"))
                .renderMarkdown();
        VueRetrievalMetrics metrics = new VueRetrievalMetrics(
                1.0, 1.0, 1,
                Map.of("token=style-secret", new VueRetrievalMetrics.StyleSlice(1.0, 1.0, 1)));
        VueRetrievalObservation observation = new VueRetrievalObservation(
                new VueEvalCase(
                        "secret=query-secret", "需求", "普通文案", List.of("s1"), List.of()),
                "password=skeleton-secret",
                List.of("api_key=feature-secret"),
                "Bearer error-secret");

        String executed = VueRetrievalEvaluationReport.executed(
                VueRetrievalComparison.compare(metrics, metrics),
                List.of(observation),
                List.of(observation)).renderMarkdown();

        assertFalse(notExecuted.contains("alice"));
        assertFalse(notExecuted.contains("reason-secret"));
        for (String secret : new String[]{
                "style-secret", "query-secret", "skeleton-secret", "feature-secret", "error-secret"}) {
            assertFalse(executed.contains(secret), secret + " 不得从任何报告字段泄漏");
        }
    }
}
