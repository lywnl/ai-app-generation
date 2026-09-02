package com.lyw.appgeneration.rag.vue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.constants.RagConstants;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.rag.ingest.VueIngestionVerification;
import com.lyw.appgeneration.rag.ingest.VueMilvusTarget;
import com.lyw.appgeneration.service.rag.RagRerankService;
import com.lyw.appgeneration.service.rag.RagRetrievalService;
import com.lyw.appgeneration.service.rag.VueHybridRetrievalService;
import com.lyw.appgeneration.service.rag.catalog.TemplateCatalog;
import com.lyw.appgeneration.service.rag.monitor.VueRagMetricsCollector;
import com.lyw.appgeneration.service.rag.retrieval.DenseRetriever;
import com.lyw.appgeneration.service.rag.retrieval.MilvusBm25Retriever;
import com.lyw.appgeneration.service.rag.retrieval.RrfFusionService;
import com.lyw.appgeneration.service.rag.retrieval.VueRetrievalResourceProvider;
import com.lyw.appgeneration.service.rag.store.MilvusCollectionSchemaVerifier;
import com.lyw.appgeneration.service.rag.store.MilvusEmbeddingStoreFactory;
import com.lyw.appgeneration.service.rag.store.MilvusV2ClientProvider;
import com.lyw.appgeneration.service.rag.store.MilvusVueBm25CollectionProvisioner;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
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
            VueMilvusTarget target,
            String password,
            String apiKey,
            TemplateCatalog catalog) {
        VueEvalDataset dataset = loadDataset();
        try (EvaluationServices services = createEvaluationServices(
                target, password, apiKey, catalog)) {
            return new VueRetrievalEvaluator(services.retrievalService()).evaluate(dataset.queries());
        }
    }

    private VueEvalDataset loadDataset() {
        try {
            return VueEvalDataset.load("rag/vue-hybrid-eval-set.json", new ObjectMapper());
        } catch (IOException exception) {
            throw new UncheckedIOException("加载 Vue 检索评测集失败", exception);
        }
    }

    private EvaluationServices createEvaluationServices(
            VueMilvusTarget target,
            String password,
            String apiKey,
            TemplateCatalog catalog) {
        RagProperties properties = evaluationProperties(target, password);
        EmbeddingModel embeddingModel = createEmbeddingModel(properties, apiKey);
        MilvusV2ClientProvider v2ClientProvider = new MilvusV2ClientProvider(properties);
        MilvusEmbeddingStoreFactory factory = new MilvusEmbeddingStoreFactory(
                properties, new MilvusCollectionSchemaVerifier(), v2ClientProvider,
                new MilvusVueBm25CollectionProvisioner());
        VueRetrievalResourceProvider resourceProvider = null;
        try {
            EmbeddingStore<TextSegment> store = factory.create(RagConstants.VUE_BM25_COLLECTION);
            resourceProvider = VueRetrievalResourceProvider.forEvaluation(catalog);
            RagRerankService rerankService = new RagRerankService(properties, apiKey);
            RagRetrievalService retrievalService = createRetrievalService(
                    embeddingModel, store, resourceProvider, rerankService, properties,
                    new MilvusBm25Retriever(new com.lyw.appgeneration.service.rag.store.MilvusBm25SearchClient(
                            v2ClientProvider.getClient())));
            return new EvaluationServices(retrievalService, resourceProvider, factory);
        } catch (RuntimeException | Error exception) {
            closeOnCreationFailure(resourceProvider, factory, exception);
            throw exception;
        }
    }

    private EmbeddingModel createEmbeddingModel(RagProperties properties, String apiKey) {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(properties.getEmbedding().getBaseUrl())
                .apiKey(apiKey)
                .modelName(properties.getEmbedding().getModelName())
                .dimensions(properties.getEmbedding().getDimension())
                .timeout(Duration.ofMillis(properties.getEmbedding().getTimeoutMs()))
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    private RagRetrievalService createRetrievalService(
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> store,
            VueRetrievalResourceProvider resourceProvider,
            RagRerankService rerankService,
            RagProperties properties,
            MilvusBm25Retriever bm25Retriever) {
        Map<CodeGenTypeEnum, EmbeddingStore<TextSegment>> stores = Map.of(
                CodeGenTypeEnum.VUE_PROJECT, store);
            VueHybridRetrievalService hybridService = new VueHybridRetrievalService(
                    resourceProvider,
                    bm25Retriever,
                    new DenseRetriever(embeddingModel, stores, properties),
                new RrfFusionService(),
                rerankService,
                new VueRagMetricsCollector(new SimpleMeterRegistry()),
                properties);
        return new RagRetrievalService(
                embeddingModel, stores, properties, rerankService, hybridService,
                org.mockito.Mockito.mock(
                        com.lyw.appgeneration.service.rag.retrieval.NativeTemplateCatalogProvider.class));
    }

    private void closeOnCreationFailure(
            VueRetrievalResourceProvider resourceProvider,
            MilvusEmbeddingStoreFactory factory,
            Throwable originalFailure) {
        if (resourceProvider != null) {
            try {
                resourceProvider.close();
            } catch (RuntimeException closeFailure) {
                originalFailure.addSuppressed(closeFailure);
            }
        }
        try {
            factory.close();
        } catch (RuntimeException closeFailure) {
            originalFailure.addSuppressed(closeFailure);
        }
    }

    static RagProperties evaluationProperties(VueMilvusTarget target, String password) {
        RagProperties properties = new RagProperties();
        properties.setEnabled(true);
        properties.getHybrid().setEnabled(true);
        properties.setTemplatesDir(Path.of("embed_text").toAbsolutePath().toString());
        properties.getMilvus().setHost(target.host());
        properties.getMilvus().setPort(target.port());
        properties.getMilvus().setDatabase(target.database());
        properties.getMilvus().setUsername(target.username());
        properties.getMilvus().setPassword(password);
        return properties;
    }

    private record EvaluationServices(
            RagRetrievalService retrievalService,
            VueRetrievalResourceProvider resourceProvider,
            MilvusEmbeddingStoreFactory factory
    ) implements AutoCloseable {

        @Override
        public void close() {
            try {
                resourceProvider.close();
            } finally {
                factory.close();
            }
        }
    }
}
