package com.lyw.appgeneration.rag.vue;

import com.lyw.appgeneration.rag.ingest.VueIngestionVerification;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VueRetrievalIngestionPrerequisiteTest {

    @Test
    void 摄取核验失败时不创建模型或检索服务() {
        AtomicInteger serviceCreations = new AtomicInteger();
        VueIngestionVerification failed = new VueIngestionVerification(
                false, "catalog", 23, 0, 0, Set.of(), List.of("缺少表 templates_vue"));

        VueRetrievalEvaluationReport report = new VueRetrievalQualityGateRunner().evaluateWhenIngested(
                () -> failed,
                () -> {
                    serviceCreations.incrementAndGet();
                    throw new AssertionError("不得创建模型服务");
                });

        assertFalse(report.executed());
        assertEquals(0, serviceCreations.get());
        assertTrue(report.renderMarkdown().contains("摄取前置条件不满足"));
    }

    @Test
    void 摄取核验通过后只执行一次检索评测() {
        AtomicInteger evaluations = new AtomicInteger();
        VueIngestionVerification passed = new VueIngestionVerification(
                true, "catalog", 23, 23, 2, Set.of(1024), List.of());
        VueRetrievalMetrics metrics = new VueRetrievalMetrics(
                1.0, 1.0, 1,
                Map.of("精确技术词", new VueRetrievalMetrics.StyleSlice(1.0, 1.0, 1)));
        VueRetrievalEvaluationReport expected = VueRetrievalEvaluationReport.executed(
                VueRetrievalComparison.compare(metrics, metrics), List.of(), List.of());

        VueRetrievalEvaluationReport actual = new VueRetrievalQualityGateRunner()
                .evaluateWhenIngested(() -> passed, () -> {
                    evaluations.incrementAndGet();
                    return expected;
                });

        assertSame(expected, actual);
        assertEquals(1, evaluations.get());
    }

    @Test
    void 摄取核验结果为空时不创建模型或检索服务() {
        AtomicInteger serviceCreations = new AtomicInteger();

        VueRetrievalEvaluationReport report = new VueRetrievalQualityGateRunner().evaluateWhenIngested(
                () -> null,
                () -> {
                    serviceCreations.incrementAndGet();
                    return null;
                });

        assertFalse(report.executed());
        assertEquals(0, serviceCreations.get());
        assertTrue(report.renderMarkdown().contains("摄取前置核验结果为空"));
    }

    @Test
    void 摄取核验异常或失败详情不会泄漏到报告() {
        VueRetrievalEvaluationReport exceptionReport = new VueRetrievalQualityGateRunner()
                .evaluateWhenIngested(
                        () -> {
                            throw new IllegalStateException("password=verification-secret");
                        },
                        () -> {
                            throw new AssertionError("不得创建模型服务");
                        });
        VueIngestionVerification failed = new VueIngestionVerification(
                false, "catalog", 23, 0, 0, Set.of(),
                List.of("读取 /Users/alice/private，token=issue-secret"));
        VueRetrievalEvaluationReport failedReport = new VueRetrievalQualityGateRunner()
                .evaluateWhenIngested(() -> failed, () -> null);

        String exceptionMarkdown = exceptionReport.renderMarkdown();
        String failedMarkdown = failedReport.renderMarkdown();
        assertFalse(exceptionMarkdown.contains("verification-secret"));
        assertTrue(exceptionMarkdown.contains("摄取前置核验失败"));
        assertFalse(failedMarkdown.contains("alice"));
        assertFalse(failedMarkdown.contains("issue-secret"));
    }

    @Test
    void 评测没有返回报告或抛出异常时标记为未执行() {
        VueIngestionVerification passed = new VueIngestionVerification(
                true, "catalog", 23, 23, 0, Set.of(1024), List.of());
        VueRetrievalEvaluationReport nullReport = new VueRetrievalQualityGateRunner()
                .evaluateWhenIngested(() -> passed, () -> null);
        VueRetrievalEvaluationReport exceptionReport = new VueRetrievalQualityGateRunner()
                .evaluateWhenIngested(
                        () -> passed,
                        () -> {
                            throw new IllegalStateException("Bearer evaluation-secret");
                        });

        assertFalse(nullReport.executed());
        assertTrue(nullReport.renderMarkdown().contains("检索评测未返回报告"));
        assertFalse(exceptionReport.executed());
        assertTrue(exceptionReport.renderMarkdown().contains("检索评测执行失败"));
        assertFalse(exceptionReport.renderMarkdown().contains("evaluation-secret"));
    }
}
