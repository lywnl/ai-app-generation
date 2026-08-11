package com.lyw.appgeneration.rag.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.service.rag.catalog.TemplateCatalog;
import com.lyw.appgeneration.service.rag.ingest.VueKnowledgeIngestor;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 仅在显式授权且依赖完整时执行的 Vue 知识真实摄取门禁。
 */
class VueKnowledgeIngestionQualityGateTest {

    private static final Path DATASET_ROOT = Path.of("embed_text/vue-project");
    private static final Path REPORT = Path.of("target/rag-eval/vue-ingestion-report.md");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DASHSCOPE_COMPATIBLE_BASE_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String EMBEDDING_MODEL = "text-embedding-v4";
    private static final int EMBEDDING_DIMENSION = 1024;
    private static final Duration EMBEDDING_TIMEOUT = Duration.ofSeconds(10);

    @Test
    void 环境显式就绪时摄取并核验真实Vue知识() throws Exception {
        VueIngestionEnvironment environment = VueIngestionEnvironment.inspectSystemEnvironment();
        if (!environment.ready()) {
            writeReport(VueIngestionReport.notExecuted(
                    environment.target().displayName(), environment.reasons()));
            return;
        }

        Map<String, String> variables = System.getenv();
        TemplateCatalog catalog = new TemplateCatalog(DATASET_ROOT, OBJECT_MAPPER);
        VueIngestionExpectedSnapshot expected = VueIngestionExpectedSnapshot.from(catalog);
        try {
            EmbeddingModel model = createEmbeddingModel(variables.get("DASHSCOPE_API_KEY"));
            EmbeddingStore<TextSegment> store = createVueStore(
                    environment.target(), variables.get("SPRING_DATASOURCE_PASSWORD"));
            VueKnowledgeIngestor.IngestResult result = new VueKnowledgeIngestor(model, OBJECT_MAPPER)
                    .ingest(DATASET_ROOT, store);
            assertEquals(expected.catalogVersion(), result.catalogVersion());
            assertEquals(23, result.chunkCount());

            VueIngestionVerification verification = new VuePgVectorIngestionVerifier(OBJECT_MAPPER)
                    .verify(expected, environment.target(), variables.get("SPRING_DATASOURCE_PASSWORD"));
            VueIngestionReport report = VueIngestionReport.verified(
                    environment.target().displayName(), verification);
            writeReport(report);
            assertTrue(report.passed(), "Vue 真实摄取物理核验失败，详见 " + REPORT);
        } catch (Exception exception) {
            writeReport(VueIngestionReport.failed(
                    environment.target().displayName(), expected.catalogVersion(),
                    List.of("真实摄取依赖失败: " + exception.getClass().getSimpleName())));
            throw exception;
        }
    }

    private EmbeddingModel createEmbeddingModel(String apiKey) {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(DASHSCOPE_COMPATIBLE_BASE_URL)
                .apiKey(apiKey)
                .modelName(EMBEDDING_MODEL)
                .dimensions(EMBEDDING_DIMENSION)
                .timeout(EMBEDDING_TIMEOUT)
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    private EmbeddingStore<TextSegment> createVueStore(
            VuePgVectorTarget target,
            String password) {
        return PgVectorEmbeddingStore.builder()
                .host(target.host())
                .port(target.port())
                .database(target.database())
                .user(target.user())
                .password(password)
                .table("templates_vue")
                .dimension(EMBEDDING_DIMENSION)
                .createTable(true)
                .useIndex(false)
                .build();
    }

    private void writeReport(VueIngestionReport report) throws IOException {
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report.renderMarkdown(), StandardCharsets.UTF_8);
    }
}
