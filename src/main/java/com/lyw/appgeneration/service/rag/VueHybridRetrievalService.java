package com.lyw.appgeneration.service.rag;

import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.service.rag.catalog.TemplateCatalog;
import com.lyw.appgeneration.service.rag.exception.RerankException;
import com.lyw.appgeneration.service.rag.model.RagDocumentKind;
import com.lyw.appgeneration.service.rag.model.RankedCandidate;
import com.lyw.appgeneration.service.rag.model.TemplateDoc;
import com.lyw.appgeneration.service.rag.model.VueRagContext;
import com.lyw.appgeneration.service.rag.monitor.VueRagDegradationReason;
import com.lyw.appgeneration.service.rag.monitor.VueRagLogSanitizer;
import com.lyw.appgeneration.service.rag.monitor.VueRagMetricsCollector;
import com.lyw.appgeneration.service.rag.retrieval.DenseRetriever;
import com.lyw.appgeneration.service.rag.retrieval.MilvusBm25Retriever;
import com.lyw.appgeneration.service.rag.retrieval.RrfFusionService;
import com.lyw.appgeneration.service.rag.retrieval.VueRetrievalResourceProvider;
import com.lyw.appgeneration.service.rag.retrieval.VueRetrievalResources;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vue 工程骨架与功能片段双链混合检索编排。
 */
@Service
@Slf4j
public class VueHybridRetrievalService {

    static final int CHANNEL_TOP_K = 10;
    static final int FUSION_TOP_K = 15;
    static final int SKELETON_RERANK_TOP_K = 3;
    static final int FEATURE_RERANK_TOP_K = 8;
    static final int FINAL_FEATURE_TOP_K = 4;
    static final String BASIC_SKELETON_ID = "vue-skeleton-basic-001";

    private static final Pattern VERSION_NUMBER = Pattern.compile("(?<!\\d)(\\d+)(?:\\.\\d+)*");
    private static final Pattern FIXED_DEPENDENCY_VERSION =
            Pattern.compile("^(\\d+)(?:\\.\\d+){0,2}$");
    private static final Pattern SINGLE_MAJOR_DEPENDENCY_RANGE =
            Pattern.compile("^[\\^~]\\s*(\\d+)(?:\\.(?:\\d+|[xX*])){0,2}$");
    private static final Pattern MINIMUM_DEPENDENCY_VERSION =
            Pattern.compile("^>=\\s*(\\d+)(?:\\.\\d+){0,2}$");

    private final VueRetrievalResourceProvider resourceProvider;
    private final MilvusBm25Retriever bm25Retriever;
    private final DenseRetriever denseRetriever;
    private final RrfFusionService fusionService;
    private final RagRerankService rerankService;
    private final VueRagMetricsCollector metricsCollector;
    private final RagProperties properties;

    public VueHybridRetrievalService(VueRetrievalResourceProvider resourceProvider,
                                     MilvusBm25Retriever bm25Retriever,
                                     DenseRetriever denseRetriever,
                                     RrfFusionService fusionService,
                                     RagRerankService rerankService,
                                     VueRagMetricsCollector metricsCollector,
                                     RagProperties properties) {
        this.resourceProvider = resourceProvider;
        this.bm25Retriever = bm25Retriever;
        this.denseRetriever = denseRetriever;
        this.fusionService = fusionService;
        this.rerankService = rerankService;
        this.metricsCollector = metricsCollector;
        this.properties = properties;
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
            Map<String, Double> qualityScores = qualityScores(catalog);
            ChainResult skeletonChain = retrieveChain(
                    rawQuery, RagDocumentKind.PROJECT_SKELETON, SKELETON_RERANK_TOP_K,
                    resources, qualityScores);
            ChainResult featureChain = retrieveChain(
                    rawQuery, RagDocumentKind.FEATURE_SNIPPET, FEATURE_RERANK_TOP_K,
                    resources, qualityScores);
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

            List<TemplateDoc> compatibleFeatures = selectCompatibleFeatures(
                    skeleton, featureChain.documents(), FINAL_FEATURE_TOP_K);
            metricsCollector.recordFinalSelection(1, compatibleFeatures.size());
            log.info("[Vue RAG] 检索完成,queryHash={},catalogVersion={},skeletonId={},featureIds={},"
                            + "skeletonCount=1,featureCount={},elapsedMs={}",
                    queryHash, catalog.getCatalogVersion(), skeleton.getId(),
                    compatibleFeatures.stream().map(TemplateDoc::getId).toList(),
                    compatibleFeatures.size(), elapsedMillis(startNanos));
            return new VueRagContext(
                    skeleton, compatibleFeatures, catalog.getCatalogVersion(), degraded);
        } finally {
            metricsCollector.recordRetrievalDuration(
                    Duration.ofNanos(System.nanoTime() - startNanos));
        }
    }

    /**
     * 使用同一目录、Embedding 与兼容性规则执行 Dense-only 离线评测基线。
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
        try {
            List<TemplateDoc> skeletonCandidates = resolveParents(
                    denseRetriever.retrieve(rawQuery, catalog.getCatalogVersion(),
                            RagDocumentKind.PROJECT_SKELETON, CHANNEL_TOP_K),
                    RagDocumentKind.PROJECT_SKELETON,
                    catalog);
            TemplateDoc skeleton = firstOfKind(
                    skeletonCandidates, RagDocumentKind.PROJECT_SKELETON);
            if (skeleton == null) {
                return new VueRagContext(null, List.of(), catalog.getCatalogVersion(), true);
            }
            List<TemplateDoc> featureCandidates = resolveParents(
                    denseRetriever.retrieve(rawQuery, catalog.getCatalogVersion(),
                            RagDocumentKind.FEATURE_SNIPPET, CHANNEL_TOP_K),
                    RagDocumentKind.FEATURE_SNIPPET,
                    catalog);
            List<TemplateDoc> compatibleFeatures = selectCompatibleFeatures(
                    skeleton, featureCandidates, FINAL_FEATURE_TOP_K);
            return new VueRagContext(
                    skeleton, compatibleFeatures, catalog.getCatalogVersion(), false);
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
            DenseResult skeletonResult = retrieveDenseParents(
                    rawQuery, RagDocumentKind.PROJECT_SKELETON, catalog);
            DenseResult featureResult = retrieveDenseParents(
                    rawQuery, RagDocumentKind.FEATURE_SNIPPET, catalog);
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

            List<TemplateDoc> compatibleFeatures = selectCompatibleFeatures(
                    skeleton, featureResult.documents(), FINAL_FEATURE_TOP_K);
            metricsCollector.recordFinalSelection(1, compatibleFeatures.size());
            return new VueRagContext(
                    skeleton, compatibleFeatures, catalog.getCatalogVersion(), degraded);
        } finally {
            metricsCollector.recordRetrievalDuration(
                    Duration.ofNanos(System.nanoTime() - startNanos));
        }
    }

    private DenseResult retrieveDenseParents(String rawQuery,
                                             RagDocumentKind documentKind,
                                             TemplateCatalog catalog) {
        try {
            List<RankedCandidate> candidates = denseRetriever.retrieve(
                    rawQuery, catalog.getCatalogVersion(), documentKind, CHANNEL_TOP_K);
            List<RankedCandidate> safeCandidates = candidates == null ? List.of() : candidates;
            metricsCollector.recordDenseCandidates(safeCandidates.size());
            return new DenseResult(resolveParents(safeCandidates, documentKind, catalog), false);
        } catch (Exception exception) {
            log.warn("[Vue RAG][Dense] 检索失败,queryHash={},catalogVersion={},documentKind={},"
                            + "candidateCount=0",
                    VueRagLogSanitizer.queryHash(rawQuery), catalog.getCatalogVersion(), documentKind);
            metricsCollector.recordDenseCandidates(0);
            metricsCollector.recordDegradation(VueRagDegradationReason.DENSE_FAILED);
            return new DenseResult(List.of(), true);
        }
    }

    private TemplateDoc basicSkeleton(TemplateCatalog catalog) {
        return catalog.findDocumentById(BASIC_SKELETON_ID)
                .filter(document -> document.getDocumentKind() == RagDocumentKind.PROJECT_SKELETON)
                .orElse(null);
    }

    private ChainResult retrieveChain(String rawQuery,
                                      RagDocumentKind documentKind,
                                      int rerankTopK,
                                      VueRetrievalResources resources,
                                      Map<String, Double> qualityScores) {
        ChannelResult bm25 = recall(VueRagDegradationReason.BM25_FAILED,
                () -> bm25Retriever.retrieve(rawQuery, resources.catalog().getCatalogVersion(),
                        documentKind, CHANNEL_TOP_K));
        ChannelResult dense = recall(VueRagDegradationReason.DENSE_FAILED,
                () -> denseRetriever.retrieve(rawQuery, resources.catalog().getCatalogVersion(),
                        documentKind, CHANNEL_TOP_K));
        boolean degraded = bm25.failed() || bm25.candidates().isEmpty()
                || dense.failed() || dense.candidates().isEmpty();

        List<RankedCandidate> fused = fusionService.fuse(
                bm25.candidates(), dense.candidates(), qualityScores, FUSION_TOP_K);
        metricsCollector.recordRrfCandidates(fused.size());
        log.debug("[Vue RAG] RRF融合完成,queryHash={},catalogVersion={},candidateIds={},candidateCount={}",
                VueRagLogSanitizer.queryHash(rawQuery), resources.catalog().getCatalogVersion(),
                VueRagLogSanitizer.candidateIds(fused), fused.size());
        if (fused.isEmpty()) {
            return new ChainResult(List.of(), true);
        }
        List<TemplateDoc> parentDocuments = resolveParents(
                fused, documentKind, resources.catalog());
        if (parentDocuments.isEmpty()) {
            return new ChainResult(List.of(), true);
        }
        if (!properties.getRerank().isEnabled()) {
            return new ChainResult(parentDocuments.stream().limit(rerankTopK).toList(), degraded);
        }
        try {
            List<TemplateDoc> reranked = rerankService.rerankVue(
                    rawQuery, parentDocuments, rerankTopK);
            metricsCollector.recordRerankCandidates(reranked.size());
            return new ChainResult(reranked, degraded);
        } catch (RerankException exception) {
            log.warn("[Vue RAG][Rerank] 失败,按 RRF 顺序降级,queryHash={},catalogVersion={},"
                            + "candidateIds={},candidateCount={}",
                    VueRagLogSanitizer.queryHash(rawQuery),
                    resources.catalog().getCatalogVersion(),
                    parentDocuments.stream().map(TemplateDoc::getId).toList(), parentDocuments.size());
            metricsCollector.recordRerankCandidates(0);
            metricsCollector.recordDegradation(VueRagDegradationReason.RERANK_FAILED);
            return new ChainResult(
                    parentDocuments.stream().limit(rerankTopK).toList(), true);
        }
    }

    private ChannelResult recall(VueRagDegradationReason failureReason,
                                 Supplier<List<RankedCandidate>> retrieval) {
        try {
            List<RankedCandidate> candidates = retrieval.get();
            List<RankedCandidate> safeCandidates = candidates == null ? List.of() : candidates;
            recordChannelCandidates(failureReason, safeCandidates.size());
            return new ChannelResult(safeCandidates, false);
        } catch (Exception exception) {
            recordChannelCandidates(failureReason, 0);
            metricsCollector.recordDegradation(failureReason);
            return new ChannelResult(List.of(), true);
        }
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
        for (RankedCandidate candidate : candidates) {
            catalog.findDocumentById(candidate.documentId())
                    .filter(document -> document.getDocumentKind() == expectedKind)
                    .ifPresent(parents::add);
        }
        return List.copyOf(parents);
    }

    private TemplateDoc firstOfKind(List<TemplateDoc> documents, RagDocumentKind kind) {
        return documents.stream()
                .filter(document -> document.getDocumentKind() == kind)
                .findFirst()
                .orElse(null);
    }

    private List<TemplateDoc> selectCompatibleFeatures(TemplateDoc skeleton,
                                                        List<TemplateDoc> candidates,
                                                        int topK) {
        List<TemplateDoc> selected = new ArrayList<>();
        for (TemplateDoc candidate : candidates) {
            if (candidate.getDocumentKind() == RagDocumentKind.FEATURE_SNIPPET
                    && isCompatible(skeleton, candidate)) {
                selected.add(candidate);
                if (selected.size() == topK) {
                    break;
                }
            }
        }
        return List.copyOf(selected);
    }

    private boolean isCompatible(TemplateDoc skeleton, TemplateDoc feature) {
        return hasSameMajorVersion(skeleton.getFramework(), feature.getFramework())
                && normalize(skeleton.getLanguage()).equals(normalize(feature.getLanguage()))
                && hasSameBuildTool(skeleton.getBuildTool(), feature.getBuildTool())
                && sharedDependenciesCompatible(skeleton, feature);
    }

    private boolean hasSameBuildTool(String left, String right) {
        String leftTool = toolName(left);
        return !leftTool.isBlank() && leftTool.equals(toolName(right));
    }

    private boolean hasSameMajorVersion(String left, String right) {
        Integer leftMajor = majorVersion(left);
        Integer rightMajor = majorVersion(right);
        return leftMajor != null && leftMajor.equals(rightMajor);
    }

    private boolean sharedDependenciesCompatible(TemplateDoc skeleton, TemplateDoc feature) {
        Map<String, String> skeletonDependencies = allDependencies(skeleton);
        Map<String, String> featureDependencies = allDependencies(feature);
        Set<String> sharedNames = new java.util.HashSet<>(skeletonDependencies.keySet());
        sharedNames.retainAll(featureDependencies.keySet());
        for (String dependency : sharedNames) {
            if (!dependencyVersionsCompatible(
                    skeletonDependencies.get(dependency), featureDependencies.get(dependency))) {
                return false;
            }
        }
        return true;
    }

    private boolean dependencyVersionsCompatible(String left, String right) {
        DependencyVersionRange leftRange = dependencyVersionRange(left);
        DependencyVersionRange rightRange = dependencyVersionRange(right);
        if (leftRange.kind() == DependencyRangeKind.UNBOUNDED
                || rightRange.kind() == DependencyRangeKind.UNBOUNDED) {
            return true;
        }
        if (leftRange.kind() == DependencyRangeKind.UNSUPPORTED
                || rightRange.kind() == DependencyRangeKind.UNSUPPORTED) {
            return false;
        }
        if (leftRange.kind() == DependencyRangeKind.MINIMUM
                && rightRange.kind() == DependencyRangeKind.MINIMUM) {
            return true;
        }
        if (leftRange.kind() == DependencyRangeKind.MINIMUM) {
            return rightRange.major() >= leftRange.major();
        }
        if (rightRange.kind() == DependencyRangeKind.MINIMUM) {
            return leftRange.major() >= rightRange.major();
        }
        return leftRange.major() == rightRange.major();
    }

    private DependencyVersionRange dependencyVersionRange(String value) {
        String normalized = normalize(value);
        if (normalized.equals("*") || normalized.equals("latest")) {
            return new DependencyVersionRange(DependencyRangeKind.UNBOUNDED, -1);
        }
        Matcher minimumMatcher = MINIMUM_DEPENDENCY_VERSION.matcher(normalized);
        if (minimumMatcher.matches()) {
            return new DependencyVersionRange(
                    DependencyRangeKind.MINIMUM, Integer.parseInt(minimumMatcher.group(1)));
        }
        Matcher fixedMatcher = FIXED_DEPENDENCY_VERSION.matcher(normalized);
        if (fixedMatcher.matches()) {
            return new DependencyVersionRange(
                    DependencyRangeKind.SINGLE_MAJOR, Integer.parseInt(fixedMatcher.group(1)));
        }
        Matcher rangeMatcher = SINGLE_MAJOR_DEPENDENCY_RANGE.matcher(normalized);
        if (rangeMatcher.matches()) {
            return new DependencyVersionRange(
                    DependencyRangeKind.SINGLE_MAJOR, Integer.parseInt(rangeMatcher.group(1)));
        }
        // 这里只实现任务所需的 npm 版本子集，复杂比较符集合和并集必须保守拒绝。
        return new DependencyVersionRange(DependencyRangeKind.UNSUPPORTED, -1);
    }

    private Map<String, String> allDependencies(TemplateDoc document) {
        Map<String, String> dependencies = new HashMap<>();
        addDependencies(dependencies, document.getDependencies());
        addDependencies(dependencies, document.getDevDependencies());
        return dependencies;
    }

    private void addDependencies(Map<String, String> target, Map<String, String> source) {
        if (source == null) {
            return;
        }
        source.forEach((name, version) -> {
            if (name != null && !name.isBlank()) {
                target.put(normalize(name), version);
            }
        });
    }

    private String toolName(String value) {
        String normalized = normalize(value);
        int at = normalized.indexOf('@');
        if (at > 0) {
            return normalized.substring(0, at);
        }
        Matcher matcher = VERSION_NUMBER.matcher(normalized);
        return matcher.find() ? normalized.substring(0, matcher.start()).strip() : normalized;
    }

    private Integer majorVersion(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = VERSION_NUMBER.matcher(value);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    private String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
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

    private record DependencyVersionRange(DependencyRangeKind kind, int major) {
    }

    private enum DependencyRangeKind {
        SINGLE_MAJOR,
        MINIMUM,
        UNBOUNDED,
        UNSUPPORTED
    }
}
