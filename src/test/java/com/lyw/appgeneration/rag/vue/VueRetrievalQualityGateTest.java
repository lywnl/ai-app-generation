package com.lyw.appgeneration.rag.vue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.rag.ingest.VueIngestionExpectedSnapshot;
import com.lyw.appgeneration.rag.ingest.VuePgVectorIngestionVerifier;
import com.lyw.appgeneration.rag.ingest.VuePgVectorTarget;
import com.lyw.appgeneration.service.rag.RagRerankService;
import com.lyw.appgeneration.service.rag.RagRetrievalService;
import com.lyw.appgeneration.service.rag.VueHybridRetrievalService;
import com.lyw.appgeneration.service.rag.catalog.TemplateCatalog;
import com.lyw.appgeneration.service.rag.monitor.VueRagMetricsCollector;
import com.lyw.appgeneration.service.rag.retrieval.DenseRetriever;
import com.lyw.appgeneration.service.rag.retrieval.RrfFusionService;
import com.lyw.appgeneration.service.rag.retrieval.VueRetrievalResourceProvider;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Vue Hybrid 与 Dense-only 的高成本真实检索质量门禁。
 */
class VueRetrievalQualityGateTest {

    private static final Path REPORT = Path.of(
            "target/rag-eval/vue-hybrid-retrieval-report.md");

    @Test
    void evaluationPropertiesReceivesDedicatedPgVectorPassword() {
        Map<String, String> environment = Map.of(
                "SPRING_DATASOURCE_PASSWORD", "mysql-secret",
                "RAG_PGVECTOR_PASSWORD", "pg-secret");
        VuePgVectorTarget target = VuePgVectorTarget.from(environment);

        RagProperties properties = evaluationProperties(target, environment);

        assertEquals("pg-secret", properties.getPgvector().getPassword());
    }

    @Test
    void evaluatesRealRetrievalWhenEnvironmentIsExplicitlyReady() throws Exception {
        VueEvaluationEnvironment environment = VueEvaluationEnvironment.inspectSystemEnvironment();
        if (!environment.ready()) {
            writeReport(VueRetrievalEvaluationReport.notExecuted(environment.reasons()));
            return;
        }

        Map<String, String> variables = System.getenv();
        VuePgVectorTarget target = VuePgVectorTarget.from(variables);
        VueRetrievalEvaluationReport report = new VueRetrievalQualityGateRunner().evaluateWhenIngested(
                () -> verifyIngestion(target, variables),
                () -> evaluateDataset(target, variables));
        writeReport(report);
        if (!report.executed()) {
            fail("Vue 真实检索的摄取前置条件不满足，详见 " + REPORT);
        }
        if (!report.passed()) {
            fail("Vue 真实检索未达到质量门槛，详见 " + REPORT);
        }
    }

    private com.lyw.appgeneration.rag.ingest.VueIngestionVerification verifyIngestion(
            VuePgVectorTarget target,
            Map<String, String> variables) {
        TemplateCatalog catalog = new TemplateCatalog(
                Path.of("embed_text/vue-project"), new ObjectMapper());
        VueIngestionExpectedSnapshot expected = VueIngestionExpectedSnapshot.from(catalog);
        return new VuePgVectorIngestionVerifier(new ObjectMapper()).verify(
                expected, target, variables.get("RAG_PGVECTOR_PASSWORD"));
    }

    private VueRetrievalEvaluationReport evaluateDataset(
            VuePgVectorTarget target,
            Map<String, String> variables) {
        VueEvalDataset dataset;
        try {
            dataset = VueEvalDataset.load("rag/vue-hybrid-eval-set.json", new ObjectMapper());
        } catch (IOException exception) {
            throw new UncheckedIOException("加载 Vue 检索评测集失败", exception);
        }
        try (EvaluationServices services = createEvaluationServices(target, variables)) {
            return new VueRetrievalEvaluator(services.retrievalService()).evaluate(dataset.queries());
        }
    }

    private EvaluationServices createEvaluationServices(
            VuePgVectorTarget target,
            Map<String, String> variables) {
        RagProperties properties = evaluationProperties(
                target, variables);
        String apiKey = variables.get("DASHSCOPE_API_KEY");
        EmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
                .baseUrl(properties.getEmbedding().getBaseUrl())
                .apiKey(apiKey)
                .modelName(properties.getEmbedding().getModelName())
                .dimensions(properties.getEmbedding().getDimension())
                .timeout(Duration.ofMillis(properties.getEmbedding().getTimeoutMs()))
                .logRequests(false)
                .logResponses(false)
                .build();
        EmbeddingStore<TextSegment> store = createVueStore(properties);
        Map<CodeGenTypeEnum, EmbeddingStore<TextSegment>> stores = Map.of(
                CodeGenTypeEnum.VUE_PROJECT, store);
        VueRetrievalResourceProvider resourceProvider = new VueRetrievalResourceProvider(
                properties, new ObjectMapper());
        RagRerankService rerankService = new RagRerankService(properties, apiKey);
        VueHybridRetrievalService hybridService = new VueHybridRetrievalService(
                resourceProvider,
                new DenseRetriever(embeddingModel, stores, properties),
                new RrfFusionService(),
                rerankService,
                new VueRagMetricsCollector(new SimpleMeterRegistry()),
                properties);
        RagRetrievalService retrievalService = new RagRetrievalService(
                embeddingModel, stores, properties, rerankService, hybridService);
        return new EvaluationServices(retrievalService, resourceProvider);
    }

    static RagProperties evaluationProperties(
            VuePgVectorTarget target,
            Map<String, String> environment) {
        RagProperties properties = new RagProperties();
        properties.setEnabled(true);
        properties.getHybrid().setEnabled(true);
        properties.setTemplatesDir(Path.of("embed_text").toAbsolutePath().toString());
        properties.getPgvector().setHost(target.host());
        properties.getPgvector().setPort(target.port());
        properties.getPgvector().setDatabase(target.database());
        properties.getPgvector().setUser(target.user());
        properties.getPgvector().setPassword(environment.get("RAG_PGVECTOR_PASSWORD"));
        return properties;
    }

    private EmbeddingStore<TextSegment> createVueStore(RagProperties properties) {
        RagProperties.PgVector pg = properties.getPgvector();
        return PgVectorEmbeddingStore.builder()
                .host(pg.getHost())
                .port(pg.getPort())
                .database(pg.getDatabase())
                .user(pg.getUser())
                .password(pg.getPassword())
                .table("templates_vue")
                .dimension(properties.getEmbedding().getDimension())
                .createTable(false)
                .useIndex(false)
                .build();
    }

    private void writeReport(VueRetrievalEvaluationReport report) throws IOException {
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report.renderMarkdown(), StandardCharsets.UTF_8);
    }

    private record EvaluationServices(
            RagRetrievalService retrievalService,
            VueRetrievalResourceProvider resourceProvider
    ) implements AutoCloseable {

        @Override
        public void close() {
            resourceProvider.close();
        }
    }
}
