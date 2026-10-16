package com.lyw.appgeneration.rag.vue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.constants.RagConstants;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.rag.ingest.VueIngestionExpectedSnapshot;
import com.lyw.appgeneration.rag.ingest.VueMilvusIngestionVerifier;
import com.lyw.appgeneration.rag.ingest.VueMilvusTarget;
import com.lyw.appgeneration.service.rag.RagRerankService;
import com.lyw.appgeneration.service.rag.VueHybridRetrievalService;
import com.lyw.appgeneration.service.rag.catalog.TemplateCatalog;
import com.lyw.appgeneration.service.rag.model.RagDocumentKind;
import com.lyw.appgeneration.service.rag.model.TemplateDoc;
import com.lyw.appgeneration.service.rag.monitor.VueRagMetricsCollector;
import com.lyw.appgeneration.service.rag.monitor.VueRagStage;
import com.lyw.appgeneration.service.rag.retrieval.DenseRetriever;
import com.lyw.appgeneration.service.rag.retrieval.MilvusBm25Retriever;
import com.lyw.appgeneration.service.rag.retrieval.RrfFusionService;
import com.lyw.appgeneration.service.rag.retrieval.VueRagExecutor;
import com.lyw.appgeneration.service.rag.retrieval.VueRetrievalResourceProvider;
import com.lyw.appgeneration.service.rag.store.MilvusBm25SearchClient;
import com.lyw.appgeneration.service.rag.store.MilvusCollectionSchemaVerifier;
import com.lyw.appgeneration.service.rag.store.MilvusEmbeddingStoreFactory;
import com.lyw.appgeneration.service.rag.store.MilvusV2ClientProvider;
import com.lyw.appgeneration.service.rag.store.MilvusVueBm25CollectionProvisioner;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.milvus.v2.service.vector.response.SearchResp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.MDC;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 显式启用的只读真实服务基准；报告只包含查询 ID、固定标签、耗时和模板 ID。 */
@EnabledIfEnvironmentVariable(named = "RAG_LATENCY_BENCHMARK", matches = "true")
class VueRagLatencyBenchmarkTest {

    private static final String TRACE_KEY = "ragBenchmarkTrace";
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Trace> traces = new ConcurrentHashMap<>();

    @Test
    void measuresTheSameDatasetBeforeAndAfterRefactoring() throws Exception {
        String phase = System.getProperty("rag.benchmark.phase", "before");
        assertTrue(List.of("before", "after").contains(phase));
        Path directory = Path.of(".codex/reports/vue-rag-latency", phase);
        Files.createDirectories(directory);
        Path reportFile = directory.resolve("samples.json");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("runId", UUID.randomUUID().toString());
        report.put("phase", phase);
        report.put("startedAt", Instant.now().toString());
        report.put("status", "前置核验中");
        write(reportFile, report);
        List<Sample> samples = new ArrayList<>();
        List<Sample> warmup = new ArrayList<>();

        try {
            Map<String, String> environment = System.getenv();
            VueEvaluationEnvironment readiness = VueEvaluationEnvironment.inspect(environment);
            report.put("environmentReady", readiness.ready());
            report.put("environmentIssues", readiness.reasons());
            assertTrue(readiness.ready(), "真实服务评测环境未就绪，详见本轮报告");
            VueMilvusTarget target = VueMilvusTarget.from(environment);
            assertEquals("127.0.0.1", target.host(), "本基准只允许使用本地 Milvus");
            TemplateCatalog catalog = new TemplateCatalog(Path.of("embed_text/vue-project"), mapper);
            var verification = new VueMilvusIngestionVerifier(mapper).verify(
                    VueIngestionExpectedSnapshot.from(catalog), target,
                    environment.get("RAG_MILVUS_PASSWORD"));
            report.put("catalogVersion", catalog.getCatalogVersion());
            report.put("verificationPassed", verification.passed());
            report.put("verificationIssues", verification.issues());
            write(reportFile, report);
            assertTrue(verification.passed(), "只读摄取核验失败，停止基准，不自动修改集合");

            VueEvalDataset dataset = VueEvalDataset.load("rag/vue-hybrid-eval-set.json", mapper);
            assertEquals(30, dataset.queries().size());
            report.put("datasetVersion", dataset.version());
            RagProperties properties = VueRetrievalQualityGateRunner.evaluationProperties(
                    target, environment.get("RAG_MILVUS_PASSWORD"));
            report.put("embeddingModel", properties.getEmbedding().getModelName());
            report.put("embeddingDimension", properties.getEmbedding().getDimension());
            report.put("embeddingTimeoutMs", properties.getEmbedding().getTimeoutMs());
            report.put("rerankModel", properties.getRerank().getModelName());
            report.put("rerankTimeoutMs", properties.getRerank().getTimeoutMs());
            report.put("workerThreads", properties.getExecution().getWorkerThreads());
            report.put("maxConcurrentRequests", properties.getExecution().getMaxConcurrentRequests());
            report.put("queueCapacity", properties.getExecution().getQueueCapacity());
            report.put("status", "测量中");

            try (Harness harness = createHarness(properties, environment.get("DASHSCOPE_API_KEY"), catalog)) {
                for (VueEvalCase query : dataset.queries().subList(0, 3)) {
                    Sample sample = sample(harness, query, "warmup", 0);
                    warmup.add(sample);
                    assertTrue(sample.error() == null && sample.stages().stream()
                            .noneMatch(stage -> stage.error() != null), "预热时服务调用失败，停止真实测量");
                }
                report.put("warmup", warmup);
                for (int round = 1; round <= 3; round++) {
                    for (VueEvalCase query : dataset.queries()) {
                        samples.add(sample(harness, query, "single", round));
                    }
                    report.put("samples", List.copyOf(samples));
                    write(reportFile, report);
                    System.out.printf("真实 RAG 基准：%s，单请求第 %d 轮完成。%n", phase, round);
                }
                try (var callers = Executors.newFixedThreadPool(4)) {
                    List<Callable<Sample>> tasks = dataset.queries().stream()
                            .<Callable<Sample>>map(query -> () -> sample(harness, query, "concurrent-4", 1))
                            .toList();
                    for (var future : callers.invokeAll(tasks)) {
                        samples.add(future.get());
                    }
                }
            }
            report.put("samples", List.copyOf(samples));
            report.put("measuredSamples", samples.size());
            assertEquals(120, samples.size());
            if ("after".equals(phase)) {
                for (Sample sample : samples) {
                    if (sample.error() == null && sample.stages().stream().noneMatch(stage -> stage.error() != null)) {
                        assertEquals(1, stageCount(sample, "embedding"), sample.queryId());
                        assertEquals(1, stageCount(sample, "logical_embedding_call"), sample.queryId());
                        assertEquals(2, stageCount(sample, "bm25"), sample.queryId());
                        assertEquals(2, stageCount(sample, "dense"), sample.queryId());
                        assertTrue(stageCount(sample, "rerank") <= 2, sample.queryId());
                        assertEquals(1, stageCount(sample, "admission_wait"), sample.queryId());
                        assertEquals(5 + stageCount(sample, "rerank"), sample.stages().stream()
                                .filter(stage -> stage.stage().startsWith("queue_")).count(), sample.queryId());
                    }
                }
                report.put("callAssertionsPassed", true);
            }
            report.put("status", "测量完成");
        } catch (Exception | AssertionError failure) {
            report.put("status", "未完成");
            report.put("failureType", failure.getClass().getSimpleName());
            report.put("warmup", List.copyOf(warmup));
            report.put("samples", List.copyOf(samples));
            throw failure;
        } finally {
            report.put("finishedAt", Instant.now().toString());
            write(reportFile, report);
        }
    }

    private Harness createHarness(RagProperties properties, String apiKey, TemplateCatalog catalog) {
        EmbeddingModel rawModel = OpenAiEmbeddingModel.builder()
                .baseUrl(properties.getEmbedding().getBaseUrl()).apiKey(apiKey)
                .modelName(properties.getEmbedding().getModelName())
                .dimensions(properties.getEmbedding().getDimension())
                .timeout(Duration.ofMillis(properties.getEmbedding().getTimeoutMs()))
                .logRequests(false).logResponses(false).build();
        EmbeddingModel embedding = measuredProxy(EmbeddingModel.class, rawModel, "embedding");
        MilvusV2ClientProvider clients = new MilvusV2ClientProvider(properties);
        MilvusEmbeddingStoreFactory stores = new MilvusEmbeddingStoreFactory(properties,
                new MilvusCollectionSchemaVerifier(), clients, new MilvusVueBm25CollectionProvisioner());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        VueRagExecutor execution = null;
        try {
            @SuppressWarnings("unchecked")
            EmbeddingStore<TextSegment> store = measuredProxy(EmbeddingStore.class,
                    stores.create(RagConstants.VUE_BM25_COLLECTION), "dense");
            MilvusBm25Retriever bm25 = new MilvusBm25Retriever(new MilvusBm25SearchClient(clients.getClient()) {
                @Override
                public SearchResp search(String query, String version, RagDocumentKind kind, int topK) {
                    return measured("bm25", kind.name(), () -> super.search(query, version, kind, topK));
                }
            });
            RagRerankService rerank = new RagRerankService(properties, apiKey) {
                @Override
                public List<TemplateDoc> rerankVue(String query, List<TemplateDoc> candidates, int topK) {
                    String kind = candidates.isEmpty() ? "shared" : candidates.getFirst().getDocumentKind().name();
                    return measured("rerank", kind, () -> super.rerankVue(query, candidates, topK));
                }
            };
            VueRetrievalResourceProvider provider = VueRetrievalResourceProvider.forEvaluation(catalog);
            VueRagMetricsCollector metrics = new VueRagMetricsCollector(registry) {
                @Override public void recordAdmissionWait(Duration duration) {
                    super.recordAdmissionWait(duration);
                    recordTrace("admission_wait", "shared", duration.toNanos(), null);
                }
                @Override public void recordEmbeddingCall() {
                    super.recordEmbeddingCall();
                    recordTrace("logical_embedding_call", "shared", 0L, null);
                }
                @Override public void recordExecutorQueueDuration(VueRagStage stage,
                                                                 RagDocumentKind kind, Duration duration) {
                    super.recordExecutorQueueDuration(stage, kind, duration);
                    recordTrace("queue_" + stage.tagValue(), kind == null ? "shared" : kind.name(),
                            duration.toNanos(), null);
                }
                @Override public void recordStageDuration(VueRagStage stage,
                                                         RagDocumentKind kind, Duration duration) {
                    super.recordStageDuration(stage, kind, duration);
                    recordTrace("execution_" + stage.tagValue(), kind == null ? "shared" : kind.name(),
                            duration.toNanos(), null);
                }
            };
            execution = new VueRagExecutor(properties, metrics);
            VueHybridRetrievalService service = new VueHybridRetrievalService(provider, bm25,
                    new DenseRetriever(embedding, Map.of(CodeGenTypeEnum.VUE_PROJECT, store), properties),
                    new RrfFusionService(), rerank, metrics, properties, execution);
            return new Harness(service, stores, provider, registry, execution);
        } catch (RuntimeException | Error failure) {
            try { if (execution != null) execution.close(); } finally {
                try { stores.close(); } finally { registry.close(); }
            }
            throw failure;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T measuredProxy(Class<T> api, T delegate, String stage) {
        return (T) Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, (proxy, method, args) -> {
            boolean measured = "embedding".equals(stage)
                    ? method.getName().startsWith("embed") : method.getName().equals("search");
            java.util.function.Supplier<Object> invocation = () -> {
                try {
                    return method.invoke(delegate, args);
                } catch (InvocationTargetException failure) {
                    if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
                    if (failure.getCause() instanceof Error error) throw error;
                    throw new IllegalStateException("评测依赖调用失败", failure.getCause());
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("评测代理调用失败", failure);
                }
            };
            return measured ? measured(stage, "shared", invocation) : invocation.get();
        });
    }

    private <T> T measured(String stage, String kind, java.util.function.Supplier<T> operation) {
        long start = System.nanoTime();
        String error = null;
        try {
            return operation.get();
        } catch (RuntimeException | Error failure) {
            error = failure.getClass().getSimpleName();
            throw failure;
        } finally {
            recordTrace(stage, kind, System.nanoTime() - start, error);
        }
    }

    private void recordTrace(String stage, String kind, long durationNanos, String error) {
        String id = MDC.get(TRACE_KEY);
        Trace trace = id == null ? null : traces.get(id);
        if (trace != null) trace.add(new Stage(stage, kind, durationNanos, error));
    }

    private long stageCount(Sample sample, String stage) {
        return sample.stages().stream().filter(item -> stage.equals(item.stage())).count();
    }

    private Sample sample(Harness harness, VueEvalCase query, String mode, int round) {
        String id = mode + "-" + round + "-" + query.queryId();
        Trace trace = new Trace();
        traces.put(id, trace);
        Map<String, String> previous = MDC.getCopyOfContextMap();
        MDC.put(TRACE_KEY, id);
        long start = System.nanoTime();
        try {
            var context = harness.service().retrieve(query.query());
            return new Sample(query.queryId(), mode, round, System.nanoTime() - start,
                    context.skeleton() == null ? null : context.skeleton().getId(),
                    context.features().stream().map(TemplateDoc::getId).toList(),
                    context.degraded(), null, trace.snapshot());
        } catch (RuntimeException failure) {
            return new Sample(query.queryId(), mode, round, System.nanoTime() - start,
                    null, List.of(), true, failure.getClass().getSimpleName(), trace.snapshot());
        } finally {
            traces.remove(id);
            if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
        }
    }

    private void write(Path path, Object value) throws Exception {
        mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }

    private record Harness(VueHybridRetrievalService service, MilvusEmbeddingStoreFactory stores,
                           VueRetrievalResourceProvider provider, SimpleMeterRegistry registry,
                           VueRagExecutor execution) implements AutoCloseable {
        @Override public void close() {
            try { execution.close(); } finally {
                try { provider.close(); } finally {
                    try { stores.close(); } finally { registry.close(); }
                }
            }
        }
    }

    private static final class Trace {
        private final List<Stage> stages = Collections.synchronizedList(new ArrayList<>());
        void add(Stage stage) { stages.add(stage); }
        List<Stage> snapshot() { synchronized (stages) { return List.copyOf(stages); } }
    }

    record Stage(String stage, String kind, long durationNanos, String error) {}
    record Sample(String queryId, String mode, int round, long durationNanos, String skeletonId,
                  List<String> featureIds, boolean degraded, String error, List<Stage> stages) {}
}
