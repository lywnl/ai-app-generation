package com.lyw.appgeneration.rag.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.AiAppGenerationApplication;
import com.lyw.appgeneration.constants.AppConstant;
import com.lyw.appgeneration.core.AiCodeGeneratorFacade;
import com.lyw.appgeneration.core.builder.VueProjectBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void evaluationPropertiesReceivesDedicatedPgVectorPassword() {
        Map<String, String> environment = Map.of(
                "SPRING_DATASOURCE_PASSWORD", "mysql-secret",
                "RAG_PGVECTOR_PASSWORD", "pg-secret");

        Map<String, Object> properties = evaluationProperties(environment);

        assertEquals("pg-secret", properties.get("rag.pgvector.password"));
    }

    @Test
    void requiresTenOfTenRealFirstGenerationBuilds() throws Exception {
        VueGenerationBuildEnvironment environment =
                VueGenerationBuildEnvironment.inspectSystemEnvironment();
        if (!environment.ready()) {
            writeReport(VueGenerationBuildReport.notExecuted(environment.reasons()));
            return;
        }

        VueGenerationBuildDataset dataset = VueGenerationBuildDataset.load(
                "rag/vue-generation-build-cases.json", new ObjectMapper());
        try (ConfigurableApplicationContext application = startEvaluationApplication()) {
            VueGenerationBuildReport report = new VueGenerationBuildEvaluator(
                    application.getBean(AiCodeGeneratorFacade.class),
                    application.getBean(VueProjectBuilder.class),
                    Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR),
                    GENERATED_ROOT,
                    GENERATION_TIMEOUT)
                    .evaluate(dataset.cases());
            writeReport(report);
            if (!report.passed()) {
                fail("Vue 首次真实生成构建未达到 10/10，详见 " + REPORT);
            }
        }
    }

    private ConfigurableApplicationContext startEvaluationApplication() {
        Map<String, Object> properties = evaluationProperties(System.getenv());
        return new SpringApplicationBuilder(AiAppGenerationApplication.class)
                .web(WebApplicationType.NONE)
                .initializers(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("vueGenerationBuildEvaluation", properties)))
                .run();
    }

    private Map<String, Object> evaluationProperties(Map<String, String> environment) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.main.lazy-initialization", "true");
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

    private void writeReport(VueGenerationBuildReport report) throws IOException {
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report.renderMarkdown(), StandardCharsets.UTF_8);
    }
}
