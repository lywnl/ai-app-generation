package com.lyw.appgeneration.service.rag;

import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.constants.RagConstants;
import com.lyw.appgeneration.service.rag.catalog.TemplateCatalog;
import com.lyw.appgeneration.service.rag.exception.RerankException;
import com.lyw.appgeneration.service.rag.model.RagDocumentKind;
import com.lyw.appgeneration.service.rag.model.RankedCandidate;
import com.lyw.appgeneration.service.rag.model.TemplateDoc;
import com.lyw.appgeneration.service.rag.model.VueRagContext;
import com.lyw.appgeneration.service.rag.model.VueFeatureContext;
import com.lyw.appgeneration.service.rag.model.CurrentVueProject;
import com.lyw.appgeneration.service.rag.model.VueTurnRagResult;
import com.lyw.appgeneration.core.resource.ResourcePreparationScope;
import java.util.function.Consumer;
import com.lyw.appgeneration.service.rag.monitor.VueRagDegradationReason;
import com.lyw.appgeneration.service.rag.monitor.VueRagLogSanitizer;
import com.lyw.appgeneration.service.rag.monitor.VueRagMetricsCollector;
import com.lyw.appgeneration.service.rag.monitor.VueRagStage;
import com.lyw.appgeneration.service.rag.retrieval.DenseRetriever;
import com.lyw.appgeneration.service.rag.retrieval.MilvusBm25Retriever;
import com.lyw.appgeneration.service.rag.retrieval.RrfFusionService;
import com.lyw.appgeneration.service.rag.retrieval.VueRagExecutor;
import com.lyw.appgeneration.service.rag.retrieval.VueRetrievalResourceProvider;
import com.lyw.appgeneration.service.rag.retrieval.VueRetrievalResources;
import dev.langchain4j.data.embedding.Embedding;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Vue 工程骨架与功能片段双链混合检索编排。
 */
@Service
@Slf4j
public class VueHybridRetrievalService {

    static final String BASIC_SKELETON_ID = "vue-skeleton-basic-001";

    private final VueRetrievalResourceProvider resourceProvider;
    private final MilvusBm25Retriever bm25Retriever;
    private final DenseRetriever denseRetriever;
    private final RrfFusionService fusionService;
    private final RagRerankService rerankService;
    private final VueRagMetricsCollector metricsCollector;
    private final RagProperties properties;
    private final VueRagExecutor execution;

    public VueHybridRetrievalService(VueRetrievalResourceProvider resourceProvider,
                                     MilvusBm25Retriever bm25Retriever,
                                     DenseRetriever denseRetriever,
                                     RrfFusionService fusionService,
                                     RagRerankService rerankService,
                                     VueRagMetricsCollector metricsCollector,
                                     RagProperties properties,
                                     VueRagExecutor execution) {
        this.resourceProvider = resourceProvider;
        this.bm25Retriever = bm25Retriever;
        this.denseRetriever = denseRetriever;
        this.fusionService = fusionService;
        this.rerankService = rerankService;
        this.metricsCollector = metricsCollector;
        this.properties = properties;
        this.execution = execution;
    }

    /**
     * 使用原始需求执行两条完全分池的 Vue 混合检索链。
     */
    public VueRagContext retrieve(String rawQuery) {
        long startNanos = System.nanoTime();
        String queryHash = VueRagLogSanitizer.queryHash(rawQuery);
        try {
            VueRetrievalResources resources = resourceProvider.current().orElse(null);
            if (resources == null) {
                log.error("[Vue RAG] 目录不可用,返回无 RAG,queryHash={},candidateCount=0",
                        queryHash);
                metricsCollector.recordDegradation(VueRagDegradationReason.CATALOG_UNAVAILABLE);
                metricsCollector.recordFinalSelection(0, 0);
                return VueRagContext.unavailable();
            }

            TemplateCatalog catalog = resources.catalog();
            ChainPair chains = retrieveChains(rawQuery, resources);
            ChainResult skeletonChain = chains.skeleton();
            ChainResult featureChain = chains.feature();
            boolean degraded = skeletonChain.degraded() || featureChain.degraded();

            TemplateDoc skeleton = firstOfKind(
                    skeletonChain.documents(), RagDocumentKind.PROJECT_SKELETON);
            if (skeleton == null) {
                skeleton = catalog.findDocumentById(BASIC_SKELETON_ID)
                        .filter(document -> document.getDocumentKind() == RagDocumentKind.PROJECT_SKELETON)
                        .orElse(null);
                degraded = true;
                metricsCollector.recordDegradation(VueRagDegradationReason.FALLBACK_SKELETON);
                if (skeleton == null) {
                    log.error("[Vue RAG] 固定基础骨架不存在,queryHash={},catalogVersion={},"
                                    + "skeletonId={},skeletonCount=0",
                            queryHash, catalog.getCatalogVersion(), BASIC_SKELETON_ID);
                    metricsCollector.recordFinalSelection(0, 0);
                    return new VueRagContext(null, List.of(), catalog.getCatalogVersion(), true);
                }
            }

            List<TemplateDoc> selectedFeatures = selectFeatures(
                    featureChain.documents(), RagConstants.VUE_INITIAL_FEATURE_TOP_K);
            metricsCollector.recordFinalSelection(1, selectedFeatures.size());
            log.info("[Vue RAG] 检索完成,queryHash={},catalogVersion={},skeletonId={},featureIds={},"
                            + "skeletonCount=1,featureCount={},elapsedMs={}",
                    queryHash, catalog.getCatalogVersion(), skeleton.getId(),
                    selectedFeatures.stream().map(TemplateDoc::getId).toList(),
                    selectedFeatures.size(), elapsedMillis(startNanos));
            return new VueRagContext(
                    skeleton, selectedFeatures, catalog.getCatalogVersion(), degraded);
        } finally {
            metricsCollector.recordRetrievalDuration(
                    Duration.ofNanos(System.nanoTime() - startNanos));
        }
    }

    /**
     * 使用同一目录、Embedding 与数量上限执行 Dense-only 离线评测基线。
     *
     * <p>该方法不会被生产生成链调用，也不会做基础骨架兜底；Dense 失败必须体现为 miss，
     * 避免基线被固定兜底结果抬高。
     */
    public VueRagContext retrieveDenseOnlyForEvaluation(String rawQuery) {
        VueRetrievalResources resources = resourceProvider.current().orElse(null);
        if (resources == null) {
            return VueRagContext.unavailable();
        }
        TemplateCatalog catalog = resources.catalog();
        if (invalidQuery(rawQuery, catalog)) {
            return new VueRagContext(null, List.of(), catalog.getCatalogVersion(), true);
        }
        try (var request = execution.openRequest()) {
            Embedding embedding = request.await(queryEmbedding(request, rawQuery));
            List<TemplateDoc> skeletonCandidates = resolveParents(
                    request.await(request.submit(VueRagStage.DENSE, RagDocumentKind.PROJECT_SKELETON,
                            () -> denseRetriever.retrieveWithEmbedding(embedding, catalog.getCatalogVersion(),
                                    RagDocumentKind.PROJECT_SKELETON, RagConstants.VUE_CHANNEL_TOP_K))),
                    RagDocumentKind.PROJECT_SKELETON,
                    catalog);
            TemplateDoc skeleton = firstOfKind(
                    skeletonCandidates, RagDocumentKind.PROJECT_SKELETON);
            if (skeleton == null) {
                return new VueRagContext(null, List.of(), catalog.getCatalogVersion(), true);
            }
            List<TemplateDoc> featureCandidates = resolveParents(
                    request.await(request.submit(VueRagStage.DENSE, RagDocumentKind.FEATURE_SNIPPET,
                            () -> denseRetriever.retrieveWithEmbedding(embedding, catalog.getCatalogVersion(),
                                    RagDocumentKind.FEATURE_SNIPPET, RagConstants.VUE_CHANNEL_TOP_K))),
                    RagDocumentKind.FEATURE_SNIPPET,
                    catalog);
            List<TemplateDoc> selectedFeatures = selectFeatures(
                    featureCandidates, RagConstants.VUE_INITIAL_FEATURE_TOP_K);
            return new VueRagContext(
                    skeleton, selectedFeatures, catalog.getCatalogVersion(), false);
        } catch (Exception exception) {
            log.warn("[Vue RAG][Dense Baseline] 检索失败,queryHash={},catalogVersion={},candidateCount=0",
                    VueRagLogSanitizer.queryHash(rawQuery), catalog.getCatalogVersion());
            return new VueRagContext(null, List.of(), catalog.getCatalogVersion(), true);
        }
    }

    /**
     * 使用当前目录和新版短块 metadata 执行生产 Dense-only 检索。
     *
     * <p>该入口只关闭 BM25、RRF 与 Rerank，仍按目录版本、文档类型回查完整父文档，
     * 并在 Dense 无结果或失败时使用固定基础骨架保证生产生成可用。
     */
    public VueRagContext retrieveDenseOnly(String rawQuery) {
        long startNanos = System.nanoTime();
        String queryHash = VueRagLogSanitizer.queryHash(rawQuery);
        try {
            VueRetrievalResources resources = resourceProvider.current().orElse(null);
            if (resources == null) {
                log.error("[Vue RAG][Dense] 目录不可用,返回无 RAG,queryHash={},candidateCount=0",
                        queryHash);
                metricsCollector.recordDegradation(VueRagDegradationReason.CATALOG_UNAVAILABLE);
                metricsCollector.recordFinalSelection(0, 0);
                return VueRagContext.unavailable();
            }
            TemplateCatalog catalog = resources.catalog();
            DensePair results = retrieveDensePools(rawQuery, catalog);
            DenseResult skeletonResult = results.skeleton();
            DenseResult featureResult = results.feature();
            boolean degraded = skeletonResult.failed() || skeletonResult.documents().isEmpty()
                    || featureResult.failed() || featureResult.documents().isEmpty();

            TemplateDoc skeleton = firstOfKind(
                    skeletonResult.documents(), RagDocumentKind.PROJECT_SKELETON);
            if (skeleton == null) {
                skeleton = basicSkeleton(catalog);
                degraded = true;
                metricsCollector.recordDegradation(VueRagDegradationReason.FALLBACK_SKELETON);
            }
            if (skeleton == null) {
                metricsCollector.recordFinalSelection(0, 0);
                return new VueRagContext(null, List.of(), catalog.getCatalogVersion(), true);
            }

            List<TemplateDoc> selectedFeatures = selectFeatures(
                    featureResult.documents(), RagConstants.VUE_INITIAL_FEATURE_TOP_K);
            metricsCollector.recordFinalSelection(1, selectedFeatures.size());
            return new VueRagContext(
                    skeleton, selectedFeatures, catalog.getCatalogVersion(), degraded);
        } finally {
            metricsCollector.recordRetrievalDuration(
                    Duration.ofNanos(System.nanoTime() - startNanos));
        }
    }

    private ChainPair retrieveChains(String rawQuery, VueRetrievalResources resources) {
        TemplateCatalog catalog = resources.catalog();
        if (invalidQuery(rawQuery, catalog)) {
            return new ChainPair(new ChainResult(List.of(), true), new ChainResult(List.of(), true));
        }
        Map<String, Double> qualityScores = qualityScores(catalog);
        try (var request = execution.openRequest()) {
            CompletableFuture<Embedding> embedding = queryEmbedding(request, rawQuery);
            CompletableFuture<ChannelResult> skeletonBm25 = bm25Channel(
                    request, rawQuery, catalog, RagDocumentKind.PROJECT_SKELETON);
            CompletableFuture<ChannelResult> featureBm25 = bm25Channel(
                    request, rawQuery, catalog, RagDocumentKind.FEATURE_SNIPPET);
            CompletableFuture<ChainResult> skeleton = retrieveChain(request, embedding, skeletonBm25,
                    rawQuery, RagDocumentKind.PROJECT_SKELETON, RagConstants.VUE_SKELETON_TOP_K, resources, qualityScores);
            CompletableFuture<ChainResult> feature = retrieveChain(request, embedding, featureBm25,
                    rawQuery, RagDocumentKind.FEATURE_SNIPPET, RagConstants.VUE_INITIAL_FEATURE_TOP_K, resources, qualityScores);
            return request.await(skeleton.thenCombine(feature, ChainPair::new));
        }
    }

    /** 后续仅访问功能池，确认工程目录存在后按相关性选取，不重新检索骨架。 */
    public VueFeatureContext retrieveFeatures(String query, CurrentVueProject project, boolean hybrid) {
        if (project == null || !project.available()) return VueFeatureContext.unavailable();
        VueRetrievalResources resources = resourceProvider.current().orElse(null);
        if (resources == null || invalidQuery(query, resources.catalog())) return VueFeatureContext.unavailable();
        try (var request = execution.openRequest()) {
            var embedding = queryEmbedding(request, query);
            ChainResult result = hybrid ? request.await(retrieveChain(request, embedding,
                    bm25Channel(request, query, resources.catalog(), RagDocumentKind.FEATURE_SNIPPET),
                    query, RagDocumentKind.FEATURE_SNIPPET, RagConstants.VUE_FOLLOWUP_FEATURE_TOP_K, resources,
                    qualityScores(resources.catalog())))
                    : request.await(denseChannel(request, embedding, resources.catalog(), RagDocumentKind.FEATURE_SNIPPET)
                    .thenApply(channel -> new ChainResult(resolveParents(channel.candidates(),
                            RagDocumentKind.FEATURE_SNIPPET, resources.catalog()), channel.failed())));
            List<TemplateDoc> features = selectFeatures(result.documents(), RagConstants.VUE_FOLLOWUP_FEATURE_TOP_K);
            metricsCollector.recordFinalSelection(0, features.size());
            return new VueFeatureContext(features, resources.catalog().getCatalogVersion(), result.degraded());
        }
    }

    /** 在线共用截止；每个渠道完成即发布可用部分，慢重排不能抹掉已经完成的召回。 */
    public VueTurnRagResult prepareOnline(String query, CurrentVueProject project, boolean initial,
            boolean hybrid, ResourcePreparationScope scope, Consumer<VueTurnRagResult> publish) {
        scope.requireActive();
        VueRetrievalResources resources = resourceProvider.current().orElse(null);
        scope.requireActive();
        VueTurnRagResult unavailable = initial ? VueTurnRagResult.initial(VueRagContext.unavailable())
                : VueTurnRagResult.followUp(VueFeatureContext.unavailable());
        if (resources == null || invalidQuery(query, resources.catalog())
                || project.existing() && !project.available() || !initial && !project.available()) {
            publish.accept(unavailable);
            return unavailable;
        }
        OnlineSelection selection = new OnlineSelection(resources.catalog(), project, initial, hybrid, scope, publish);
        try (var request = execution.openRequest(scope)) {
            selection.publish();
            var embedding = queryEmbedding(request, query);
            Map<String, Double> quality = qualityScores(resources.catalog());
            List<CompletableFuture<Void>> chains = new ArrayList<>();
            if (initial) chains.add(onlineChain(request, embedding, query, RagDocumentKind.PROJECT_SKELETON,
                    RagConstants.VUE_SKELETON_TOP_K, resources, quality, hybrid, selection));
            chains.add(onlineChain(request, embedding, query, RagDocumentKind.FEATURE_SNIPPET,
                    selection.featureTopK, resources, quality, hybrid, selection));
            request.await(CompletableFuture.allOf(chains.toArray(CompletableFuture[]::new)));
            return selection.last;
        }
    }

    private CompletableFuture<Void> onlineChain(VueRagExecutor.RequestScope request,
            CompletableFuture<Embedding> embedding, String query, RagDocumentKind kind, int rerankTopK,
            VueRetrievalResources resources, Map<String, Double> quality, boolean hybrid, OnlineSelection selection) {
        var bm25 = hybrid ? bm25Channel(request, query, resources.catalog(), kind)
                : CompletableFuture.completedFuture(new ChannelResult(List.of(), false));
        var dense = denseChannel(request, embedding, resources.catalog(), kind);
        // 登记的回调也属于链：最终重排发布后不会被稍晚执行的早期渠道回调覆盖。
        var observedBm25 = bm25.thenApply(value -> { selection.channel(kind, true, value); return value; });
        var observedDense = dense.thenApply(value -> { selection.channel(kind, false, value); return value; });
        return observedBm25.thenCombine(observedDense, RecallPair::new).thenCompose(pair -> hybrid
                ? finishChain(request, pair.bm25(), pair.dense(), query, kind, rerankTopK, resources, quality)
                : CompletableFuture.completedFuture(new ChainResult(resolveParents(pair.dense().candidates(), kind,
                        resources.catalog()), pair.dense().failed())))
                .handle((value, failure) -> { selection.finished(kind, value, failure); return null; });
    }

    private final class OnlineSelection {
        private final TemplateCatalog catalog;
        private final CurrentVueProject project;
        private final boolean initial;
        private final int featureTopK;
        private final boolean hybrid;
        private final ResourcePreparationScope scope;
        private final Consumer<VueTurnRagResult> consumer;
        private final Map<RagDocumentKind, PoolSelection> pools = new java.util.EnumMap<>(RagDocumentKind.class);
        private VueTurnRagResult last;

        private OnlineSelection(TemplateCatalog catalog, CurrentVueProject project, boolean initial, boolean hybrid,
                                ResourcePreparationScope scope, Consumer<VueTurnRagResult> consumer) {
            this.catalog = catalog; this.project = project; this.initial = initial; this.hybrid = hybrid;
            this.scope = scope; this.consumer = consumer;
            this.featureTopK = initial ? RagConstants.VUE_INITIAL_FEATURE_TOP_K : RagConstants.VUE_FOLLOWUP_FEATURE_TOP_K;
            if (initial) pools.put(RagDocumentKind.PROJECT_SKELETON, new PoolSelection());
            pools.put(RagDocumentKind.FEATURE_SNIPPET, new PoolSelection());
        }

        private synchronized void channel(RagDocumentKind kind, boolean sparse, ChannelResult value) {
            if (!scope.canStart()) return;
            var pool = pools.get(kind);
            if (pool.finished) return;
            if (sparse) pool.bm25 = value; else pool.dense = value;
            publish();
        }

        private synchronized void finished(RagDocumentKind kind, ChainResult value, Throwable failure) {
            if (!scope.canStart()) return;
            var pool = pools.get(kind);
            pool.finished = true;
            if (failure == null) pool.finalResult = value;
            publish();
        }

        private List<TemplateDoc> documents(RagDocumentKind kind) {
            PoolSelection pool = pools.get(kind);
            if (pool == null) return List.of();
            if (pool.finalResult != null) return pool.finalResult.documents();
            if (!hybrid) return resolveParents(pool.dense.candidates(), kind, catalog);
            return resolveParents(fusionService.fuse(pool.bm25.candidates(), pool.dense.candidates(),
                    qualityScores(catalog), RagConstants.VUE_FUSION_TOP_K), kind, catalog);
        }

        private synchronized void publish() {
            if (!scope.canStart()) return;
            boolean degraded = pools.values().stream().anyMatch(pool -> pool.finalResult == null || pool.finalResult.degraded());
            List<TemplateDoc> candidates = documents(RagDocumentKind.FEATURE_SNIPPET);
            if (initial) {
                TemplateDoc skeleton = firstOfKind(
                        documents(RagDocumentKind.PROJECT_SKELETON), RagDocumentKind.PROJECT_SKELETON);
                if (skeleton == null && !project.existing()) { skeleton = basicSkeleton(catalog); degraded = true; }
                List<TemplateDoc> features = skeleton == null && !project.existing()
                        ? List.of() : selectFeatures(candidates, featureTopK);
                last = VueTurnRagResult.initial(new VueRagContext(skeleton, features, catalog.getCatalogVersion(), degraded));
            } else {
                last = VueTurnRagResult.followUp(new VueFeatureContext(selectFeatures(candidates, featureTopK), catalog.getCatalogVersion(), degraded));
            }
            consumer.accept(last);
        }
    }

    private static final class PoolSelection {
        private ChannelResult bm25 = new ChannelResult(List.of(), true);
        private ChannelResult dense = new ChannelResult(List.of(), true);
        private ChainResult finalResult;
        private boolean finished;
    }

    private DensePair retrieveDensePools(String rawQuery, TemplateCatalog catalog) {
        if (invalidQuery(rawQuery, catalog)) {
            return new DensePair(new DenseResult(List.of(), false), new DenseResult(List.of(), false));
        }
        try (var request = execution.openRequest()) {
            CompletableFuture<Embedding> embedding = queryEmbedding(request, rawQuery);
            CompletableFuture<DenseResult> skeleton = denseChannel(
                    request, embedding, catalog, RagDocumentKind.PROJECT_SKELETON)
                    .thenApply(result -> new DenseResult(resolveParents(result.candidates(),
                            RagDocumentKind.PROJECT_SKELETON, catalog), result.failed()));
            CompletableFuture<DenseResult> feature = denseChannel(
                    request, embedding, catalog, RagDocumentKind.FEATURE_SNIPPET)
                    .thenApply(result -> new DenseResult(resolveParents(result.candidates(),
                            RagDocumentKind.FEATURE_SNIPPET, catalog), result.failed()));
            return request.await(skeleton.thenCombine(feature, DensePair::new));
        }
    }

    private boolean invalidQuery(String rawQuery, TemplateCatalog catalog) {
        return rawQuery == null || rawQuery.isBlank()
                || catalog.getCatalogVersion() == null || catalog.getCatalogVersion().isBlank();
    }

    private CompletableFuture<Embedding> queryEmbedding(VueRagExecutor.RequestScope request, String rawQuery) {
        return request.submit(VueRagStage.EMBEDDING, null, () -> denseRetriever.embedQuery(rawQuery));
    }

    private CompletableFuture<ChannelResult> bm25Channel(VueRagExecutor.RequestScope request,
                                                         String rawQuery, TemplateCatalog catalog,
                                                         RagDocumentKind kind) {
        return recall(VueRagDegradationReason.BM25_FAILED, request.submit(VueRagStage.BM25, kind,
                () -> bm25Retriever.retrieve(rawQuery, catalog.getCatalogVersion(), kind, RagConstants.VUE_CHANNEL_TOP_K)));
    }

    private CompletableFuture<ChannelResult> denseChannel(VueRagExecutor.RequestScope request,
                                                          CompletableFuture<Embedding> embedding,
                                                          TemplateCatalog catalog, RagDocumentKind kind) {
        // 共享计算失败时，两池都走原来的 Dense 失败分支，不额外重算向量。
        return recall(VueRagDegradationReason.DENSE_FAILED, embedding.thenCompose(value ->
                request.submit(VueRagStage.DENSE, kind, () -> denseRetriever.retrieveWithEmbedding(
                        value, catalog.getCatalogVersion(), kind, RagConstants.VUE_CHANNEL_TOP_K))));
    }

    private TemplateDoc basicSkeleton(TemplateCatalog catalog) {
        return catalog.findDocumentById(BASIC_SKELETON_ID)
                .filter(document -> document.getDocumentKind() == RagDocumentKind.PROJECT_SKELETON)
                .orElse(null);
    }

    private CompletableFuture<ChainResult> retrieveChain(VueRagExecutor.RequestScope request,
                                                         CompletableFuture<Embedding> embedding,
                                                         CompletableFuture<ChannelResult> bm25,
                                                         String rawQuery, RagDocumentKind documentKind,
                                                         int rerankTopK, VueRetrievalResources resources,
                                                         Map<String, Double> qualityScores) {
        return bm25.thenCombine(denseChannel(request, embedding, resources.catalog(), documentKind),
                        RecallPair::new)
                .thenCompose(pair -> finishChain(request, pair.bm25(), pair.dense(), rawQuery,
                        documentKind, rerankTopK, resources, qualityScores));
    }

    private CompletableFuture<ChainResult> finishChain(VueRagExecutor.RequestScope request,
                                                       ChannelResult bm25, ChannelResult dense,
                                                       String rawQuery, RagDocumentKind documentKind,
                                                       int rerankTopK, VueRetrievalResources resources,
                                                       Map<String, Double> qualityScores) {
        boolean degraded = bm25.failed() || bm25.candidates().isEmpty()
                || dense.failed() || dense.candidates().isEmpty();

        List<RankedCandidate> fused = fusionService.fuse(
                bm25.candidates(), dense.candidates(), qualityScores, RagConstants.VUE_FUSION_TOP_K);
        metricsCollector.recordRrfCandidates(fused.size());
        log.debug("[Vue RAG] RRF融合完成,queryHash={},catalogVersion={},candidateIds={},candidateCount={}",
                VueRagLogSanitizer.queryHash(rawQuery), resources.catalog().getCatalogVersion(),
                VueRagLogSanitizer.candidateIds(fused), fused.size());
        if (fused.isEmpty()) {
            return CompletableFuture.completedFuture(new ChainResult(List.of(), true));
        }
        List<TemplateDoc> parentDocuments = resolveParents(
                fused, documentKind, resources.catalog());
        if (parentDocuments.isEmpty()) {
            return CompletableFuture.completedFuture(new ChainResult(List.of(), true));
        }
        if (!properties.getRerank().isEnabled()) {
            return CompletableFuture.completedFuture(
                    new ChainResult(parentDocuments.stream().limit(rerankTopK).toList(), degraded));
        }
        return request.submit(VueRagStage.RERANK, documentKind,
                () -> rerankService.rerankVue(rawQuery, parentDocuments, rerankTopK)).handle((reranked, failure) -> {
            if (failure == null) {
                metricsCollector.recordRerankCandidates(reranked.size());
                return new ChainResult(reranked, degraded);
            }
            if (!(unwrap(failure) instanceof RerankException)) {
                throw new CompletionException(failure);
            }
            log.warn("[Vue RAG][Rerank] 失败,按 RRF 顺序降级,queryHash={},catalogVersion={},"
                            + "candidateIds={},candidateCount={}",
                    VueRagLogSanitizer.queryHash(rawQuery),
                    resources.catalog().getCatalogVersion(),
                    parentDocuments.stream().map(TemplateDoc::getId).toList(), parentDocuments.size());
            metricsCollector.recordRerankCandidates(0);
            metricsCollector.recordDegradation(VueRagDegradationReason.RERANK_FAILED);
            return new ChainResult(
                    parentDocuments.stream().limit(rerankTopK).toList(), true);
        });
    }

    private CompletableFuture<ChannelResult> recall(VueRagDegradationReason failureReason,
                                                     CompletableFuture<List<RankedCandidate>> retrieval) {
        return retrieval.handle((candidates, failure) -> {
            if (failure == null) {
                List<RankedCandidate> safeCandidates = candidates == null ? List.of() : candidates;
                recordChannelCandidates(failureReason, safeCandidates.size());
                return new ChannelResult(safeCandidates, false);
            }
            if (unwrap(failure) instanceof Error error) {
                throw error;
            }
            recordChannelCandidates(failureReason, 0);
            metricsCollector.recordDegradation(failureReason);
            return new ChannelResult(List.of(), true);
        });
    }

    private Throwable unwrap(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }

    private void recordChannelCandidates(VueRagDegradationReason channel, int count) {
        if (channel == VueRagDegradationReason.BM25_FAILED) {
            metricsCollector.recordBm25Candidates(count);
        } else {
            metricsCollector.recordDenseCandidates(count);
        }
    }

    private long elapsedMillis(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    private List<TemplateDoc> resolveParents(List<RankedCandidate> candidates,
                                             RagDocumentKind expectedKind,
                                             TemplateCatalog catalog) {
        List<TemplateDoc> parents = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (RankedCandidate candidate : candidates) {
            if (candidate == null || candidate.documentKind() != expectedKind || !seen.add(candidate.documentId())) continue;
            catalog.findDocumentById(candidate.documentId())
                    .filter(document -> document.getDocumentKind() == expectedKind)
                    .ifPresent(parents::add);
        }
        return List.copyOf(parents);
    }

    private TemplateDoc firstOfKind(List<TemplateDoc> documents, RagDocumentKind kind) {
        return documents.stream()
                .filter(document -> document != null && document.getId() != null && !document.getId().isBlank()
                        && document.getDocumentKind() == kind)
                .findFirst()
                .orElse(null);
    }

    private List<TemplateDoc> selectFeatures(List<TemplateDoc> candidates, int topK) {
        List<TemplateDoc> selected = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (TemplateDoc candidate : candidates) {
            if (candidate != null && candidate.getId() != null && !candidate.getId().isBlank()
                    && candidate.getDocumentKind() == RagDocumentKind.FEATURE_SNIPPET
                    && seen.add(candidate.getId())) {
                selected.add(candidate);
                if (selected.size() == topK) {
                    break;
                }
            }
        }
        return List.copyOf(selected);
    }

    private Map<String, Double> qualityScores(TemplateCatalog catalog) {
        Map<String, Double> scores = new HashMap<>();
        for (TemplateDoc document : catalog.getDocuments()) {
            Double score = document.getQualityScore();
            scores.put(document.getId(), score != null && Double.isFinite(score) ? score : 0.0);
        }
        return Map.copyOf(scores);
    }

    private record ChannelResult(List<RankedCandidate> candidates, boolean failed) {
    }

    private record DenseResult(List<TemplateDoc> documents, boolean failed) {
    }

    private record ChainResult(List<TemplateDoc> documents, boolean degraded) {
    }

    private record ChainPair(ChainResult skeleton, ChainResult feature) {
    }

    private record DensePair(DenseResult skeleton, DenseResult feature) {
    }

    private record RecallPair(ChannelResult bm25, ChannelResult dense) {
    }

}
