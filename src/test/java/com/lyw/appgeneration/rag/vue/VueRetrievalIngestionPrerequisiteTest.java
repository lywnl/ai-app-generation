package com.lyw.appgeneration.rag.vue;

import com.lyw.appgeneration.rag.ingest.VueIngestionVerification;
import com.lyw.appgeneration.rag.ingest.VuePgVectorTarget;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        VueRetrievalEvaluationReport expected =
                VueRetrievalEvaluationReport.executed(List.of(), List.of());

        VueRetrievalEvaluationReport actual = new VueRetrievalQualityGateRunner()
                .evaluateWhenIngested(() -> passed, () -> {
                    evaluations.incrementAndGet();
                    return expected;
                });

        assertSame(expected, actual);
        assertEquals(1, evaluations.get());
    }

    @Test
    void 摄取核验结果为空时原样失败且不创建模型或检索服务() {
        AtomicInteger serviceCreations = new AtomicInteger();

        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> new VueRetrievalQualityGateRunner().evaluateWhenIngested(
                        () -> null,
                        () -> {
                            serviceCreations.incrementAndGet();
                            return null;
                        }));

        assertTrue(exception.getMessage().contains("摄取前置核验结果"));
        assertEquals(0, serviceCreations.get());
    }

    @Test
    void 摄取核验异常原样传播且失败详情不会泄漏到报告() {
        IllegalStateException original = new IllegalStateException("password=verification-secret");
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new VueRetrievalQualityGateRunner().evaluateWhenIngested(
                        () -> {
                            throw original;
                        },
                        () -> {
                            throw new AssertionError("不得创建模型服务");
                        }));
        VueIngestionVerification failed = new VueIngestionVerification(
                false, "catalog", 23, 0, 0, Set.of(),
                List.of("读取 /Users/alice/private，token=issue-secret"));
        VueRetrievalEvaluationReport failedReport = new VueRetrievalQualityGateRunner()
                .evaluateWhenIngested(() -> failed, () -> null);

        String failedMarkdown = failedReport.renderMarkdown();
        assertSame(original, thrown);
        assertFalse(failedMarkdown.contains("alice"));
        assertFalse(failedMarkdown.contains("issue-secret"));
    }

    @Test
    void 评测返回空报告或抛出异常时原样失败() {
        VueIngestionVerification passed = new VueIngestionVerification(
                true, "catalog", 23, 23, 0, Set.of(1024), List.of());
        NullPointerException nullException = assertThrows(NullPointerException.class,
                () -> new VueRetrievalQualityGateRunner().evaluateWhenIngested(
                        () -> passed, () -> null));
        IllegalStateException original = new IllegalStateException("Bearer evaluation-secret");
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new VueRetrievalQualityGateRunner().evaluateWhenIngested(
                        () -> passed,
                        () -> {
                            throw original;
                        }));

        assertTrue(nullException.getMessage().contains("检索评测报告"));
        assertSame(original, thrown);
    }

    @Test
    void 评测返回未执行报告时拒绝错误归类为摄取前置失败() {
        VueIngestionVerification passed = new VueIngestionVerification(
                true, "catalog", 23, 23, 0, Set.of(1024), List.of());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new VueRetrievalQualityGateRunner().evaluateWhenIngested(
                        () -> passed,
                        () -> VueRetrievalEvaluationReport.notExecuted(List.of("评测内部未执行"))));

        assertTrue(exception.getMessage().contains("已执行"));
    }

    @Test
    void 规范化目标同时用于核验和评测配置() {
        assertTargetMapping(Map.of(
                "RAG_PGVECTOR_HOST", "",
                "RAG_PGVECTOR_DATABASE", "",
                "RAG_PGVECTOR_USER", "",
                "RAG_PGVECTOR_PORT", "0"));
        assertTargetMapping(Map.of("RAG_PGVECTOR_PORT", "70000"));
        assertTargetMapping(Map.of("RAG_PGVECTOR_PORT", "非法端口"));
    }

    private void assertTargetMapping(Map<String, String> variables) {
        VuePgVectorTarget target = VuePgVectorTarget.from(variables);
        var properties = VueRetrievalQualityGateRunner.evaluationProperties(
                target, "password");

        assertEquals(target.host(), properties.getPgvector().getHost());
        assertEquals(target.port(), properties.getPgvector().getPort());
        assertEquals(target.database(), properties.getPgvector().getDatabase());
        assertEquals(target.user(), properties.getPgvector().getUser());
        assertEquals("password", properties.getPgvector().getPassword());
    }
}
