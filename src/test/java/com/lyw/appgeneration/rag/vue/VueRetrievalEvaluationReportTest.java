package com.lyw.appgeneration.rag.vue;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VueRetrievalEvaluationReportTest {

    @Test
    void failedReportRendersCurrentRunAndSanitizesEveryField() {
        String markdown = VueRetrievalEvaluationReport.failed(
                "run-token=run-secret",
                List.of("检查 /Users/alice/private 失败，Bearer reason-secret"))
                .renderMarkdown();

        assertTrue(markdown.contains("状态：未通过"));
        assertTrue(markdown.contains("运行标识："));
        assertFalse(markdown.contains("run-secret"));
        assertFalse(markdown.contains("alice"));
        assertFalse(markdown.contains("reason-secret"));
        assertFalse(markdown.contains("Skeleton Hit@1 |"));
    }

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
        List<VueRetrievalObservation> rows = java.util.stream.IntStream.range(0, 30)
                .mapToObj(index -> new VueRetrievalObservation(
                        new VueEvalCase(
                                "q" + index, "需求", "精确技术词",
                                List.of("s1"), List.of("f1")),
                        "s1", List.of("f1"), null))
                .toList();

        String markdown = VueRetrievalEvaluationReport.executed(rows, rows)
                .renderMarkdown();

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
    void 少于三十条的完美指标也不得通过() {
        VueRetrievalEvaluationReport report =
                VueRetrievalEvaluationReport.executed(List.of(), List.of());

        assertFalse(report.passed());
    }

    @Test
    void 完美指标遇到退化错误重复或双链用例不一致也不得通过() {
        List<VueRetrievalObservation> healthy = observations("q", 30, false, null);
        List<VueRetrievalObservation> hybridDegraded = observations("q", 30, true, null);
        List<VueRetrievalObservation> denseFailed = observations("q", 30, false, "Dense 检索异常");
        List<VueRetrievalObservation> duplicates = java.util.Collections.nCopies(
                30, healthy.getFirst());
        List<VueRetrievalObservation> differentCases = observations("other", 30, false, null);
        assertFalse(VueRetrievalEvaluationReport.executed(
                hybridDegraded, healthy).passed());
        assertFalse(VueRetrievalEvaluationReport.executed(
                healthy, denseFailed).passed());
        assertFalse(VueRetrievalEvaluationReport.executed(
                duplicates, duplicates).passed());
        assertFalse(VueRetrievalEvaluationReport.executed(
                healthy, differentCases).passed());
    }

    @Test
    void 外部完美汇总不得掩盖三十条全错明细() {
        List<VueRetrievalObservation> wrong = java.util.stream.IntStream.range(0, 30)
                .mapToObj(index -> new VueRetrievalObservation(
                        new VueEvalCase(
                                "q" + index, "需求", "精确技术词",
                                List.of("expected-skeleton"), List.of("expected-feature")),
                        "wrong-skeleton", List.of("wrong-feature"), null))
                .toList();
        VueRetrievalEvaluationReport report =
                VueRetrievalEvaluationReport.executed(wrong, wrong);

        assertFalse(report.passed());
    }

    @Test
    void 双链QueryId相同但完整用例不同不得通过() {
        List<VueRetrievalObservation> hybrid = observations("q", 30, false, null);
        List<VueRetrievalObservation> dense = java.util.stream.IntStream.range(0, 30)
                .mapToObj(index -> new VueRetrievalObservation(
                        new VueEvalCase(
                                "q" + index, "不同需求", "不同风格",
                                List.of("other-skeleton"), List.of("other-feature")),
                        "other-skeleton", List.of("other-feature"), null))
                .toList();
        VueRetrievalEvaluationReport report =
                VueRetrievalEvaluationReport.executed(hybrid, dense);

        assertFalse(report.passed());
    }

    private List<VueRetrievalObservation> observations(
            String queryPrefix,
            int count,
            boolean degraded,
            String error) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> new VueRetrievalObservation(
                        new VueEvalCase(
                                queryPrefix + index, "需求", "精确技术词",
                                List.of("s1"), List.of()),
                        "s1", List.<String>of(), error, degraded))
                .toList();
    }

    @Test
    void sanitizesEveryFinalReportFieldIncludingReasonAndRows() {
        String notExecuted = VueRetrievalEvaluationReport.notExecuted(List.of(
                "检查 C:\\Users\\alice\\private 失败，Authorization: Bearer reason-secret"))
                .renderMarkdown();
        VueRetrievalObservation observation = new VueRetrievalObservation(
                new VueEvalCase(
                        "secret=query-secret", "需求", "token=style-secret",
                        List.of("s1"), List.of()),
                "password=skeleton-secret",
                List.of("api_key=feature-secret"),
                "Bearer error-secret");

        String executed = VueRetrievalEvaluationReport.executed(
                List.of(observation), List.of(observation)).renderMarkdown();

        assertFalse(notExecuted.contains("alice"));
        assertFalse(notExecuted.contains("reason-secret"));
        for (String secret : new String[]{
                "style-secret", "query-secret", "skeleton-secret", "feature-secret", "error-secret"}) {
            assertFalse(executed.contains(secret), secret + " 不得从任何报告字段泄漏");
        }
    }
}
