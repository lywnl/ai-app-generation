package com.lyw.appgeneration.rag.vue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.rag.eval.EvaluationReportLifecycle;
import com.lyw.appgeneration.rag.ingest.VueIngestionExpectedSnapshot;
import com.lyw.appgeneration.rag.ingest.VuePgVectorIngestionVerifier;
import com.lyw.appgeneration.rag.ingest.VuePgVectorTarget;
import com.lyw.appgeneration.service.rag.catalog.TemplateCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vue Hybrid 与 Dense-only 的高成本真实检索质量门禁。
 */
class VueRetrievalQualityGateTest {

    private static final Path REPORT = Path.of(
            "target/rag-eval/vue-hybrid-retrieval-report.md");

    @Test
    void 入口异常时旧通过报告被本轮失败状态覆盖(@TempDir Path directory) throws Exception {
        Path report = directory.resolve("retrieval.md");
        Files.writeString(report, "状态：通过\n旧指标：Skeleton Hit@1\n", StandardCharsets.UTF_8);
        IllegalStateException original = new IllegalStateException("password=不得写入");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> EvaluationReportLifecycle.execute(
                        report,
                        VueRetrievalEvaluationReport.failed(
                                "run-retrieval-new", List.of("本轮检索正在执行"))
                                .renderMarkdown(),
                        () -> { throw original; },
                        VueRetrievalEvaluationReport::renderMarkdown,
                        VueRetrievalEvaluationReport.failed(
                                "run-retrieval-new", List.of("本轮检索执行异常"))
                                .renderMarkdown()));

        String markdown = Files.readString(report, StandardCharsets.UTF_8);
        assertSame(original, thrown);
        assertTrue(markdown.contains("状态：未通过"));
        assertTrue(markdown.contains("run-retrieval-new"));
        assertFalse(markdown.contains("旧指标"));
        assertFalse(markdown.contains("不得写入"));
    }

    @Test
    void evaluationPropertiesReceivesDedicatedPgVectorPassword() {
        Map<String, String> environment = Map.of(
                "SPRING_DATASOURCE_PASSWORD", "mysql-secret",
                "RAG_PGVECTOR_PASSWORD", "pg-secret");
        VuePgVectorTarget target = VuePgVectorTarget.from(environment);

        RagProperties properties = VueRetrievalQualityGateRunner.evaluationProperties(
                target, environment.get("RAG_PGVECTOR_PASSWORD"));

        assertEquals("pg-secret", properties.getPgvector().getPassword());
    }

    @Test
    void evaluatesRealRetrievalWhenEnvironmentIsExplicitlyReady() throws Exception {
        String runId = UUID.randomUUID().toString();
        VueEvaluationEnvironment[] inspectedEnvironment = new VueEvaluationEnvironment[1];
        VueRetrievalEvaluationReport report = EvaluationReportLifecycle.execute(
                REPORT,
                VueRetrievalEvaluationReport.failed(
                        runId, List.of("本轮真实检索运行中，旧结果已失效"))
                        .renderMarkdown(),
                () -> evaluateCurrentRun(runId, inspectedEnvironment),
                VueRetrievalEvaluationReport::renderMarkdown,
                VueRetrievalEvaluationReport.failed(
                        runId, List.of("本轮真实检索发生异常"))
                        .renderMarkdown());
        if (!inspectedEnvironment[0].ready()) {
            return;
        }
        if (!report.executed()) {
            fail("Vue 真实检索的摄取前置条件不满足，详见 " + REPORT);
        }
        if (!report.passed()) {
            fail("Vue 真实检索未达到质量门槛，详见 " + REPORT);
        }
    }

    private VueRetrievalEvaluationReport evaluateCurrentRun(
            String runId,
            VueEvaluationEnvironment[] inspectedEnvironment) {
        VueEvaluationEnvironment environment = VueEvaluationEnvironment.inspectSystemEnvironment();
        inspectedEnvironment[0] = environment;
        if (!environment.ready()) {
            return VueRetrievalEvaluationReport.notExecuted(runId, environment.reasons());
        }

        Map<String, String> variables = System.getenv();
        VuePgVectorTarget target = VuePgVectorTarget.from(variables);
        String pgVectorPassword = variables.get("RAG_PGVECTOR_PASSWORD");
        TemplateCatalog catalog = new TemplateCatalog(
                Path.of("embed_text/vue-project"), new ObjectMapper());
        return new VueRetrievalQualityGateRunner().evaluateWhenIngested(
                () -> verifyIngestion(target, variables, catalog),
                () -> new VueRetrievalQualityGateRunner()
                        .evaluateDataset(
                                target,
                                pgVectorPassword,
                                variables.get("DASHSCOPE_API_KEY"),
                                catalog))
                .withRunId(runId);
    }

    private com.lyw.appgeneration.rag.ingest.VueIngestionVerification verifyIngestion(
            VuePgVectorTarget target,
            Map<String, String> variables,
            TemplateCatalog catalog) {
        VueIngestionExpectedSnapshot expected = VueIngestionExpectedSnapshot.from(catalog);
        return new VuePgVectorIngestionVerifier(new ObjectMapper()).verify(
                expected, target, variables.get("RAG_PGVECTOR_PASSWORD"));
    }

}
