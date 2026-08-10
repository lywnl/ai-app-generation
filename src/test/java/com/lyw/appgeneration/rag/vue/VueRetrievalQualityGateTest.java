package com.lyw.appgeneration.rag.vue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.service.rag.RagRerankService;
import com.lyw.appgeneration.service.rag.RagRetrievalService;
import com.lyw.appgeneration.service.rag.VueHybridRetrievalService;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Vue Hybrid 与 Dense-only 的高成本真实检索质量门禁。
 */
class VueRetrievalQualityGateTest {

    private static final Path REPORT = Path.of(
            "target/rag-eval/vue-hybrid-retrieval-report.md");

    @Test
    void evaluatesRealRetrievalWhenEnvironmentIsExplicitlyReady() throws Exception {
        VueEvaluationEnvironment environment = VueEvaluationEnvironment.inspectSystemEnvironment();
        if (!environment.ready()) {
            writeReport(VueRetrievalEvaluationReport.notExecuted(environment.reasons()));
            return;
        }

        VueEvalDataset dataset = VueEvalDataset.load(
                "rag/vue-hybrid-eval-set.json", new ObjectMapper());
        try (EvaluationServices services = createEvaluationServices()) {
            VueRetrievalEvaluationReport report = new VueRetrievalEvaluator(services.retrievalService())
                    .evaluate(dataset.queries());
            writeReport(report);
            if (!report.passed()) {
                fail("Vue 真实检索未达到质量门槛，详见 " + REPORT);
            }
        }
    }

    private EvaluationServices createEvaluationServices() {
        Map<String, String> environment = System.getenv();
        RagProperties properties = evaluationProperties(environment);
        String apiKey = environment.get("DASHSCOPE_API_KEY");
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

    private RagProperties evaluationProperties(Map<String, String> environment) {
        RagProperties properties = new RagProperties();
        properties.setEnabled(true);
        properties.getHybrid().setEnabled(true);
        properties.setTemplatesDir(Path.of("embed_text").toAbsolutePath().toString());
        properties.getPgvector().setHost(environment.getOrDefault(
                "RAG_PGVECTOR_HOST", "127.0.0.1"));
        properties.getPgvector().setPort(integerEnvironment(
                environment, "RAG_PGVECTOR_PORT", 5432));
        properties.getPgvector().setDatabase(environment.getOrDefault(
                "RAG_PGVECTOR_DATABASE", "ai_codegen_rag"));
        properties.getPgvector().setUser(environment.getOrDefault(
                "RAG_PGVECTOR_USER", "admin"));
        properties.getPgvector().setPassword(environment.get("SPRING_DATASOURCE_PASSWORD"));
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

    private int integerEnvironment(
            Map<String, String> environment,
            String name,
            int fallback) {
        try {
            return Integer.parseInt(environment.getOrDefault(name, Integer.toString(fallback)));
        } catch (NumberFormatException exception) {
            return fallback;
        }
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
