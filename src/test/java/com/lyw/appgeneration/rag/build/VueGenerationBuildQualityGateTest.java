package com.lyw.appgeneration.rag.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.AiAppGenerationApplication;
import com.lyw.appgeneration.constants.AppConstant;
import com.lyw.appgeneration.core.AiCodeGeneratorFacade;
import com.lyw.appgeneration.core.builder.VueProjectBuilder;
import com.lyw.appgeneration.rag.eval.EvaluationReportLifecycle;
import com.lyw.appgeneration.rag.ingest.VueIngestionExpectedSnapshot;
import com.lyw.appgeneration.rag.ingest.VueIngestionVerification;
import com.lyw.appgeneration.rag.ingest.VuePgVectorIngestionVerifier;
import com.lyw.appgeneration.rag.ingest.VuePgVectorTarget;
import com.lyw.appgeneration.rag.vue.VueRetrievalQualityGateRunner;
import com.lyw.appgeneration.service.rag.catalog.TemplateCatalog;
import com.lyw.appgeneration.service.rag.retrieval.VueRetrievalResourceProvider;
import com.lyw.appgeneration.service.rag.retrieval.Bm25Retriever;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 十条 Vue 首次真实生成与 npm 构建的高成本质量门禁。
 */
class VueGenerationBuildQualityGateTest {

    private static final Path REPORT = Path.of(
            "target/rag-eval/vue-generation-build-report.md");
    private static final Path GENERATED_ROOT = Path.of(
            "target/rag-eval/generated");
    private static final Duration GENERATION_TIMEOUT = Duration.ofMinutes(10);

    @Test
    void 入口异常时旧十条通过报告被本轮失败状态覆盖(@TempDir Path directory) throws Exception {
        Path report = directory.resolve("generation.md");
        Files.writeString(report, "状态：通过\n构建成功数：10/10\n", StandardCharsets.UTF_8);
        IllegalArgumentException original = new IllegalArgumentException("Bearer 不得写入");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> EvaluationReportLifecycle.execute(
                        report,
                        VueGenerationBuildReport.failed(
                                "run-generation-new", java.util.List.of("本轮生成正在执行"))
                                .renderMarkdown(),
                        () -> { throw original; },
                        VueGenerationBuildReport::renderMarkdown,
                        VueGenerationBuildReport.failed(
                                "run-generation-new", java.util.List.of("本轮生成执行异常"))
                                .renderMarkdown()));

        String markdown = Files.readString(report, StandardCharsets.UTF_8);
        assertSame(original, thrown);
        assertTrue(markdown.contains("状态：未通过"));
        assertTrue(markdown.contains("run-generation-new"));
        assertFalse(markdown.contains("10/10"));
        assertFalse(markdown.contains("不得写入"));
    }

    @Test
    void evaluationPropertiesReceivesDedicatedPgVectorPassword() {
        Map<String, String> environment = Map.of(
                "SPRING_DATASOURCE_PASSWORD", "mysql-secret",
                "RAG_PGVECTOR_PASSWORD", "pg-secret");

        Map<String, Object> properties = evaluationProperties(environment);

        assertEquals("pg-secret", properties.get("rag.pgvector.password"));
    }

    @Test
    void 真实生成Spring复用同一目录快照并在关闭时释放资源() {
        TemplateCatalog catalog = new TemplateCatalog(
                Path.of("embed_text/vue-project"), new ObjectMapper());
        Bm25Retriever bm25;

        try (ConfigurableApplicationContext context = startEvaluationApplication(catalog)) {
            VueRetrievalResourceProvider provider =
                    context.getBean(VueRetrievalResourceProvider.class);
            assertSame(catalog, provider.current().orElseThrow().catalog());
            bm25 = provider.current().orElseThrow().bm25Retriever().orElseThrow();
        }

        assertThrows(RuntimeException.class, () -> bm25.retrieve(
                "Vue 基础站点",
                com.lyw.appgeneration.service.rag.model.RagDocumentKind.PROJECT_SKELETON,
                1));
    }

    @Test
    void requiresTenOfTenRealFirstGenerationBuilds() throws Exception {
        String runId = UUID.randomUUID().toString();
        VueGenerationBuildEnvironment[] inspectedEnvironment =
                new VueGenerationBuildEnvironment[1];
        VueGenerationBuildReport report = EvaluationReportLifecycle.execute(
                REPORT,
                VueGenerationBuildReport.failed(
                        runId, java.util.List.of("本轮真实生成运行中，旧结果已失效"))
                        .renderMarkdown(),
                () -> evaluateCurrentRun(runId, inspectedEnvironment),
                VueGenerationBuildReport::renderMarkdown,
                VueGenerationBuildReport.failed(
                        runId, java.util.List.of("本轮真实生成发生异常"))
                        .renderMarkdown());
        if (!inspectedEnvironment[0].ready()) {
            return;
        }
        if (!report.passed()) {
            fail("Vue 首次真实生成构建未达到 10/10，详见 " + REPORT);
        }
    }

    private VueGenerationBuildReport evaluateCurrentRun(
            String runId,
            VueGenerationBuildEnvironment[] inspectedEnvironment) {
        VueGenerationBuildEnvironment environment =
                VueGenerationBuildEnvironment.inspectSystemEnvironment();
        inspectedEnvironment[0] = environment;
        if (!environment.ready()) {
            return VueGenerationBuildReport.notExecuted(runId, environment.reasons());
        }

        Map<String, String> variables = System.getenv();
        VuePgVectorTarget target = VuePgVectorTarget.from(variables);
        TemplateCatalog catalog = new TemplateCatalog(
                Path.of("embed_text/vue-project"), new ObjectMapper());
        VueGenerationBuildReport report = new VueGenerationBuildQualityGateRunner().evaluate(
                () -> verifyIngestion(target, variables, catalog),
                () -> new VueRetrievalQualityGateRunner()
                        .evaluateDataset(target, variables, catalog),
                () -> evaluateGeneration(catalog));
        return report.withRunId(runId);
    }

    private VueIngestionVerification verifyIngestion(
            VuePgVectorTarget target,
            Map<String, String> variables,
            TemplateCatalog catalog) {
        VueIngestionExpectedSnapshot expected = VueIngestionExpectedSnapshot.from(catalog);
        return new VuePgVectorIngestionVerifier(new ObjectMapper()).verify(
                expected, target, variables.get("RAG_PGVECTOR_PASSWORD"));
    }

    private VueGenerationBuildReport evaluateGeneration(TemplateCatalog catalog) {
        VueGenerationBuildDataset dataset;
        try {
            dataset = VueGenerationBuildDataset.load(
                    "rag/vue-generation-build-cases.json", new ObjectMapper());
        } catch (IOException exception) {
            throw new UncheckedIOException("加载 Vue 生成构建评测集失败", exception);
        }
        try (ConfigurableApplicationContext application = startEvaluationApplication(catalog)) {
            return new VueGenerationBuildEvaluator(
                    application.getBean(AiCodeGeneratorFacade.class),
                    application.getBean(VueProjectBuilder.class),
                    Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR),
                    GENERATED_ROOT,
                    GENERATION_TIMEOUT)
                    .evaluate(dataset.cases());
        }
    }

    private ConfigurableApplicationContext startEvaluationApplication(TemplateCatalog catalog) {
        Map<String, Object> properties = evaluationProperties(System.getenv());
        return new SpringApplicationBuilder(AiAppGenerationApplication.class)
                .web(WebApplicationType.NONE)
                .properties(properties)
                .initializers(context -> context.addBeanFactoryPostProcessor(
                        new VueEvaluationCatalogSnapshotConfigurer(catalog)))
                .run();
    }

    private Map<String, Object> evaluationProperties(Map<String, String> environment) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.main.lazy-initialization", "true");
        properties.put("DEEPSEEK_API_KEY", "unused-by-context-lifecycle-test");
        properties.put("DASHSCOPE_API_KEY", "unused-by-context-lifecycle-test");
        properties.put("PEXELS_API_KEY", "unused-by-context-lifecycle-test");
        properties.put("rag.enabled", "true");
        properties.put("rag.hybrid.enabled", "true");
        properties.put("rag.ingest.enabled", "false");
        properties.put("rag.templates-dir", Path.of("embed_text").toAbsolutePath().toString());
        properties.put("rag.pgvector.host", valueOrDefault(
                environment, "RAG_PGVECTOR_HOST", "127.0.0.1"));
        properties.put("rag.pgvector.port", valueOrDefault(
                environment, "RAG_PGVECTOR_PORT", "5432"));
        properties.put("rag.pgvector.database", valueOrDefault(
                environment, "RAG_PGVECTOR_DATABASE", "ai_codegen_rag"));
        properties.put("rag.pgvector.user", valueOrDefault(
                environment, "RAG_PGVECTOR_USER", "admin"));
        properties.put("rag.pgvector.password", environment.get("RAG_PGVECTOR_PASSWORD"));
        properties.put("pexels.api-key", "unused-by-vue-generation-build-evaluation");
        return properties;
    }

    private String valueOrDefault(
            Map<String, String> environment,
            String name,
            String fallback) {
        String value = environment.get(name);
        return value == null || value.isBlank() ? fallback : value;
    }

}
