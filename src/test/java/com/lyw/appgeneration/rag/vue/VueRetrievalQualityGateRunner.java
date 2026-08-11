package com.lyw.appgeneration.rag.vue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.rag.ingest.VueIngestionVerification;
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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 在真实检索评测前执行摄取物理核验，未通过时绝不创建模型或检索服务。
 */
public final class VueRetrievalQualityGateRunner {

    public VueRetrievalEvaluationReport evaluateWhenIngested(
            Supplier<VueIngestionVerification> verificationSupplier,
            Supplier<VueRetrievalEvaluationReport> evaluationSupplier) {
        VueIngestionVerification verification = Objects.requireNonNull(
                verificationSupplier.get(), "摄取前置核验结果不能为空");
        if (!verification.passed()) {
            List<String> reasons = new ArrayList<>();
            reasons.add("摄取前置条件不满足");
            reasons.addAll(verification.issues());
            return VueRetrievalEvaluationReport.notExecuted(reasons);
        }

        VueRetrievalEvaluationReport report = Objects.requireNonNull(
                evaluationSupplier.get(), "检索评测报告不能为空");
        if (!report.executed()) {
            throw new IllegalStateException("摄取核验通过后，检索评测报告必须为已执行状态");
        }
        return report;
    }

    public VueRetrievalEvaluationReport evaluateDataset(
            VuePgVectorTarget target,
            String pgVectorPassword,
            String dashScopeApiKey,
            TemplateCatalog catalog) {
        VueEvalDataset dataset;
        try {
            dataset = VueEvalDataset.load("rag/vue-hybrid-eval-set.json", new ObjectMapper());
        } catch (IOException exception) {
            throw new UncheckedIOException("加载 Vue 检索评测集失败", exception);
        }
        try (EvaluationServices services = createEvaluationServices(
                target, pgVectorPassword, dashScopeApiKey, catalog)) {
            return new VueRetrievalEvaluator(services.retrievalService()).evaluate(dataset.queries());
        }
    }

    private EvaluationServices createEvaluationServices(
            VuePgVectorTarget target,
            String pgVectorPassword,
            String dashScopeApiKey,
            TemplateCatalog catalog) {
        RagProperties properties = evaluationProperties(target, pgVectorPassword);
        EmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
                .baseUrl(properties.getEmbedding().getBaseUrl())
                .apiKey(dashScopeApiKey)
                .modelName(properties.getEmbedding().getModelName())
                .dimensions(properties.getEmbedding().getDimension())
                .timeout(Duration.ofMillis(properties.getEmbedding().getTimeoutMs()))
                .logRequests(false)
                .logResponses(false)
                .build();
        EmbeddingStore<TextSegment> store = createVueStore(properties);
        Map<CodeGenTypeEnum, EmbeddingStore<TextSegment>> stores = Map.of(
                CodeGenTypeEnum.VUE_PROJECT, store);
        VueRetrievalResourceProvider resourceProvider =
                VueRetrievalResourceProvider.forEvaluation(catalog);
        RagRerankService rerankService = new RagRerankService(
                properties, dashScopeApiKey);
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
            String pgVectorPassword) {
        RagProperties properties = new RagProperties();
        properties.setEnabled(true);
        properties.getHybrid().setEnabled(true);
        properties.setTemplatesDir(Path.of("embed_text").toAbsolutePath().toString());
        properties.getPgvector().setHost(target.host());
        properties.getPgvector().setPort(target.port());
        properties.getPgvector().setDatabase(target.database());
        properties.getPgvector().setUser(target.user());
        properties.getPgvector().setPassword(pgVectorPassword);
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
