package com.lyw.appgeneration.service.rag;

import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.service.rag.catalog.TemplateCatalog;
import com.lyw.appgeneration.service.rag.exception.RerankException;
import com.lyw.appgeneration.service.rag.model.RagDocumentKind;
import com.lyw.appgeneration.service.rag.model.RankedCandidate;
import com.lyw.appgeneration.service.rag.model.TemplateDoc;
import com.lyw.appgeneration.service.rag.model.VueRagContext;
import com.lyw.appgeneration.service.rag.monitor.VueRagDegradationReason;
import com.lyw.appgeneration.service.rag.monitor.VueRagMetricsCollector;
import com.lyw.appgeneration.service.rag.retrieval.DenseRetriever;
import com.lyw.appgeneration.service.rag.retrieval.MilvusBm25Retriever;
import com.lyw.appgeneration.service.rag.retrieval.RrfFusionService;
import com.lyw.appgeneration.service.rag.retrieval.VueRagExecutor;
import com.lyw.appgeneration.service.rag.retrieval.VueRetrievalResourceProvider;
import com.lyw.appgeneration.service.rag.retrieval.VueRetrievalResources;
import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VueHybridRetrievalServiceTest {

    private final List<VueRagExecutor> executors = new ArrayList<>();

    @AfterEach
    void closeExecutors() {
        executors.forEach(VueRagExecutor::close);
    }

    private VueRagExecutor execution(RagProperties properties, VueRagMetricsCollector metrics) {
        VueRagExecutor execution = new VueRagExecutor(properties, metrics);
        executors.add(execution);
        return execution;
    }

    private static final String QUERY = "需要 Vue 管理后台、登录、表格、上传和看板";
    private static final String CATALOG_VERSION = "catalog-v5";
    private static final String BASIC_SKELETON_ID = "vue-skeleton-basic-001";

    @Test
    void 同次请求两池使用同一向量而不同请求重新计算() {
        TemplateDoc skeleton = compatibleSkeleton("skeleton");
        TemplateDoc feature = compatibleFeature("feature");
        Harness harness = harness(List.of(skeleton, feature));
        Embedding first = Embedding.from(new float[]{1, 2});
        Embedding second = Embedding.from(new float[]{3, 4});
        when(harness.dense.embedQuery(QUERY)).thenReturn(first, second);
        when(harness.dense.retrieveWithEmbedding(any(), any(), any(), anyInt()))
                .thenAnswer(invocation -> candidatesForKind(List.of(skeleton, feature), invocation.getArgument(2)));

        VueRagContext one = harness.service.retrieve(QUERY);
        VueRagContext two = harness.service.retrieve(QUERY);

        assertEquals(one, two);
        verify(harness.dense, times(2)).embedQuery(QUERY);
        verify(harness.metrics, times(2)).recordEmbeddingCall();
        ArgumentCaptor<Embedding> vectors = ArgumentCaptor.forClass(Embedding.class);
        verify(harness.dense, times(4)).retrieveWithEmbedding(vectors.capture(), eq(CATALOG_VERSION), any(), eq(10));
        assertSame(first, vectors.getAllValues().get(0));
        assertSame(first, vectors.getAllValues().get(1));
        assertSame(second, vectors.getAllValues().get(2));
        assertSame(second, vectors.getAllValues().get(3));
        verify(harness.dense, never()).retrieve(any(), any(), any(), anyInt());
    }

    @Test
    void 闩锁证明三个初始调用两个Dense及两个Rerank分别可以重叠() throws Exception {
        TemplateDoc skeleton = compatibleSkeleton("skeleton");
        TemplateDoc feature = compatibleFeature("feature");
        List<TemplateDoc> documents = List.of(skeleton, feature);
        Harness harness = harness(documents);
        CountDownLatch initialStarted = new CountDownLatch(3);
        CountDownLatch releaseInitial = new CountDownLatch(1);
        CountDownLatch denseStarted = new CountDownLatch(2);
        CountDownLatch releaseDense = new CountDownLatch(1);
        CountDownLatch rerankStarted = new CountDownLatch(2);
        CountDownLatch releaseRerank = new CountDownLatch(1);
        when(harness.dense.embedQuery(QUERY)).thenAnswer(invocation -> {
            initialStarted.countDown();
            await(releaseInitial);
            return Embedding.from(new float[]{1, 2});
        });
        when(harness.bm25.retrieve(any(), any(), any(), anyInt())).thenAnswer(invocation -> {
            initialStarted.countDown();
            await(releaseInitial);
            return candidatesForKind(documents, invocation.getArgument(2));
        });
        when(harness.dense.retrieveWithEmbedding(any(), any(), any(), anyInt())).thenAnswer(invocation -> {
            denseStarted.countDown();
            await(releaseDense);
            return candidatesForKind(documents, invocation.getArgument(2));
        });
        when(harness.rerank.rerankVue(any(), anyList(), anyInt())).thenAnswer(invocation -> {
            rerankStarted.countDown();
            await(releaseRerank);
            return invocation.getArgument(1);
        });

        try (var callers = Executors.newSingleThreadExecutor()) {
            var result = callers.submit(() -> harness.service.retrieve(QUERY));
            try {
                await(initialStarted);
                releaseInitial.countDown();
                await(denseStarted);
                releaseDense.countDown();
                await(rerankStarted);
                releaseRerank.countDown();
                VueRagContext context = result.get(5, TimeUnit.SECONDS);
                assertSame(skeleton, context.skeleton());
                assertEquals(List.of(feature), context.features());
                assertFalse(context.degraded());
            } finally {
                releaseInitial.countDown();
                releaseDense.countDown();
                releaseRerank.countDown();
            }
        }
    }

    @Test
    void 骨架池无需等待功能BM25就能完成重排() throws Exception {
        TemplateDoc skeleton = compatibleSkeleton("skeleton");
        TemplateDoc feature = compatibleFeature("feature");
        List<TemplateDoc> documents = List.of(skeleton, feature);
        Harness harness = harness(documents);
        CountDownLatch featureBm25Started = new CountDownLatch(1);
        CountDownLatch releaseFeature = new CountDownLatch(1);
        CountDownLatch skeletonReranked = new CountDownLatch(1);
        when(harness.bm25.retrieve(any(), any(), any(), anyInt())).thenAnswer(invocation -> {
            RagDocumentKind kind = invocation.getArgument(2);
            if (kind == RagDocumentKind.FEATURE_SNIPPET) {
                featureBm25Started.countDown();
                await(releaseFeature);
            }
            return candidatesForKind(documents, kind);
        });
        when(harness.dense.retrieveWithEmbedding(any(), any(), any(), anyInt()))
                .thenAnswer(invocation -> candidatesForKind(documents, invocation.getArgument(2)));
        when(harness.rerank.rerankVue(any(), anyList(), eq(3))).thenAnswer(invocation -> {
            skeletonReranked.countDown();
            return invocation.getArgument(1);
        });
        try (var callers = Executors.newSingleThreadExecutor()) {
            var result = callers.submit(() -> harness.service.retrieve(QUERY));
            try {
                await(featureBm25Started);
                await(skeletonReranked);
                assertFalse(result.isDone());
                releaseFeature.countDown();
                assertEquals(List.of(feature), result.get(5, TimeUnit.SECONDS).features());
            } finally {
                releaseFeature.countDown();
            }
        }
    }

    @Test
    void 共享Embedding失败时保留BM25且不会重算或搜索Dense() {
        TemplateDoc skeleton = compatibleSkeleton("bm25-skeleton");
        TemplateDoc feature = compatibleFeature("bm25-feature");
        Harness harness = harness(List.of(skeleton, feature));
        stubSuccessfulRecall(harness, List.of(skeleton), List.of(feature));
        when(harness.dense.embedQuery(QUERY)).thenThrow(new IllegalStateException("模型不可用"));

        VueRagContext context = harness.service.retrieve(QUERY);

        assertSame(skeleton, context.skeleton());
        assertEquals(List.of(feature), context.features());
        assertTrue(context.degraded());
        verify(harness.dense).embedQuery(QUERY);
        verify(harness.dense, never()).retrieveWithEmbedding(any(), any(), any(), anyInt());
        verify(harness.metrics, times(2)).recordDegradation(VueRagDegradationReason.DENSE_FAILED);
    }

    @Test
    void 空白查询不会提交模型或检索调用且保留原来的生产兜底() {
        TemplateDoc basic = compatibleSkeleton(BASIC_SKELETON_ID);
        Harness harness = harness(List.of(basic));
        assertSame(basic, harness.service.retrieve("  ").skeleton());
        assertSame(basic, harness.service.retrieveDenseOnly(null).skeleton());
        assertNull(harness.service.retrieveDenseOnlyForEvaluation("").skeleton());
        verifyNoInteractions(harness.bm25, harness.dense, harness.fusion, harness.rerank);
        verify(harness.metrics, never()).recordEmbeddingCall();
    }

    @Test
    void 单个工作线程也能完成全部依赖而不在池内等待子任务() throws Exception {
        TemplateDoc skeleton = compatibleSkeleton("skeleton");
        TemplateDoc feature = compatibleFeature("feature");
        RagProperties properties = new RagProperties();
        properties.getExecution().setWorkerThreads(1);
        Harness harness = harness(List.of(skeleton, feature), properties);
        stubSuccessfulRecall(harness, List.of(skeleton), List.of(feature));
        try (var callers = Executors.newSingleThreadExecutor()) {
            VueRagContext context = callers.submit(() -> harness.service.retrieve(QUERY)).get(5, TimeUnit.SECONDS);
            assertSame(skeleton, context.skeleton());
            assertEquals(List.of(feature), context.features());
        }
    }

    @Test
    void 生产DenseOnly共享向量并行搜索而离线基线保留骨架短路() throws Exception {
        TemplateDoc basic = compatibleSkeleton(BASIC_SKELETON_ID);
        Harness harness = harness(List.of(basic));
        CountDownLatch searchesStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(harness.dense.retrieveWithEmbedding(any(), any(), any(), anyInt())).thenAnswer(invocation -> {
            searchesStarted.countDown();
            await(release);
            return List.of();
        });
        try (var callers = Executors.newSingleThreadExecutor()) {
            var result = callers.submit(() -> harness.service.retrieveDenseOnly(QUERY));
            try {
                await(searchesStarted);
                release.countDown();
                assertSame(basic, result.get(5, TimeUnit.SECONDS).skeleton());
            } finally {
                release.countDown();
            }
        }
        assertNull(harness.service.retrieveDenseOnlyForEvaluation(QUERY).skeleton());
        verify(harness.dense, times(2)).embedQuery(QUERY);
        verify(harness.dense, times(2)).retrieveWithEmbedding(any(), any(), eq(RagDocumentKind.PROJECT_SKELETON), eq(10));
        verify(harness.dense, times(1)).retrieveWithEmbedding(any(), any(), eq(RagDocumentKind.FEATURE_SNIPPET), eq(10));
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "受控任务没有按预期推进");
    }

    @Test
    void runsSeparatedSkeletonAndFeatureChainsWithFixedParameters() {
        TemplateDoc skeleton = compatibleSkeleton("skeleton-primary");
        List<TemplateDoc> features = List.of(
                compatibleFeature("feature-1"), compatibleFeature("feature-2"),
                compatibleFeature("feature-3"), compatibleFeature("feature-4"),
                compatibleFeature("feature-5"), compatibleFeature("feature-6"));
        Harness harness = harness(join(List.of(skeleton), features));
        when(harness.bm25.retrieve(eq(QUERY), eq(CATALOG_VERSION), eq(RagDocumentKind.PROJECT_SKELETON), eq(10)))
                .thenReturn(candidates(List.of(skeleton), RagDocumentKind.PROJECT_SKELETON));
        when(harness.dense.retrieveWithEmbedding(any(Embedding.class), eq(CATALOG_VERSION),
                eq(RagDocumentKind.PROJECT_SKELETON), eq(10)))
                .thenReturn(candidates(List.of(skeleton), RagDocumentKind.PROJECT_SKELETON));
        when(harness.bm25.retrieve(eq(QUERY), eq(CATALOG_VERSION), eq(RagDocumentKind.FEATURE_SNIPPET), eq(10)))
                .thenReturn(candidates(features, RagDocumentKind.FEATURE_SNIPPET));
        when(harness.dense.retrieveWithEmbedding(any(Embedding.class), eq(CATALOG_VERSION),
                eq(RagDocumentKind.FEATURE_SNIPPET), eq(10)))
                .thenReturn(candidates(features.reversed(), RagDocumentKind.FEATURE_SNIPPET));

        VueRagContext context = harness.service.retrieve(QUERY);

        assertEquals("skeleton-primary", context.skeleton().getId());
        assertEquals(List.of("feature-1", "feature-6", "feature-2", "feature-5"),
                context.features().stream().map(TemplateDoc::getId).toList());
        assertEquals(4, context.features().size());
        assertEquals(CATALOG_VERSION, context.catalogVersion());
        assertFalse(context.degraded());
        verify(harness.fusion, org.mockito.Mockito.times(2))
                .fuse(anyList(), anyList(), anyMap(), eq(15));
        verify(harness.rerank).rerankVue(eq(QUERY), anyList(), eq(3));
        verify(harness.rerank).rerankVue(eq(QUERY), anyList(), eq(8));
        verify(harness.metrics).recordBm25Candidates(1);
        verify(harness.metrics).recordBm25Candidates(6);
        verify(harness.metrics).recordDenseCandidates(1);
        verify(harness.metrics).recordDenseCandidates(6);
        verify(harness.metrics).recordRrfCandidates(1);
        verify(harness.metrics).recordRrfCandidates(6);
        verify(harness.metrics).recordRerankCandidates(1);
        verify(harness.metrics).recordRerankCandidates(6);
        verify(harness.metrics).recordFinalSelection(1, 4);
        verify(harness.metrics).recordRetrievalDuration(any());
    }

    @Test
    void echartsRequirementDoesNotFilterHybridCandidatesBeforeRerank() {
        String query = "生成商城，并使用 ECharts 展示销售趋势";
        TemplateDoc shopSkeleton = compatibleSkeleton("skeleton-shop");
        TemplateDoc dashboardSkeleton = compatibleSkeleton("skeleton-dashboard");
        dashboardSkeleton.getDependencies().put("echarts", "5.5.1");
        Harness harness = harness(List.of(shopSkeleton, dashboardSkeleton));
        when(harness.bm25.retrieve(eq(query), eq(CATALOG_VERSION), eq(RagDocumentKind.PROJECT_SKELETON), eq(10)))
                .thenReturn(candidates(
                        List.of(shopSkeleton, dashboardSkeleton),
                        RagDocumentKind.PROJECT_SKELETON));
        when(harness.dense.retrieveWithEmbedding(any(Embedding.class), eq(CATALOG_VERSION),
                eq(RagDocumentKind.PROJECT_SKELETON), eq(10)))
                .thenReturn(List.of());
        when(harness.bm25.retrieve(eq(query), eq(CATALOG_VERSION), eq(RagDocumentKind.FEATURE_SNIPPET), eq(10)))
                .thenReturn(List.of());
        when(harness.dense.retrieveWithEmbedding(any(Embedding.class), eq(CATALOG_VERSION),
                eq(RagDocumentKind.FEATURE_SNIPPET), eq(10)))
                .thenReturn(List.of());

        VueRagContext context = harness.service.retrieve(query);

        assertEquals("skeleton-shop", context.skeleton().getId());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TemplateDoc>> rerankCandidates = ArgumentCaptor.forClass(List.class);
        verify(harness.rerank).rerankVue(eq(query), rerankCandidates.capture(), eq(3));
        assertEquals(List.of("skeleton-shop", "skeleton-dashboard"),
                rerankCandidates.getValue().stream().map(TemplateDoc::getId).toList());
    }

    @Test
    void echartsRejectionDoesNotOverrideDenseOnlyRanking() {
        String query = "生成数据看板，但不要使用 ECharts";
        TemplateDoc dashboardSkeleton = compatibleSkeleton("skeleton-dashboard");
        dashboardSkeleton.getDependencies().put("echarts", "5.5.1");
        TemplateDoc adminSkeleton = compatibleSkeleton("skeleton-admin");
        Harness harness = harness(List.of(dashboardSkeleton, adminSkeleton));
        when(harness.dense.retrieveWithEmbedding(any(Embedding.class), eq(CATALOG_VERSION),
                eq(RagDocumentKind.PROJECT_SKELETON), eq(10)))
                .thenReturn(candidates(
                        List.of(dashboardSkeleton, adminSkeleton),
                        RagDocumentKind.PROJECT_SKELETON));
        when(harness.dense.retrieveWithEmbedding(any(Embedding.class), eq(CATALOG_VERSION),
                eq(RagDocumentKind.FEATURE_SNIPPET), eq(10)))
                .thenReturn(List.of());

        VueRagContext production = harness.service.retrieveDenseOnly(query);
        VueRagContext evaluation = harness.service.retrieveDenseOnlyForEvaluation(query);

        assertEquals("skeleton-dashboard", production.skeleton().getId());
        assertEquals("skeleton-dashboard", evaluation.skeleton().getId());
    }

    @Test
    void rerankTextContainsParentMetadataAndNeverContainsSourceContent() {
        RagProperties properties = new RagProperties();
        properties.getRerank().setDocCharLimit(10_000);
        RagRerankService service = new RagRerankService(properties, "test-key");
        TemplateDoc document = compatibleFeature("feature-rerank");
        document.setTitle("登录标题");
        document.setDescription("登录描述");
        document.setEmbedText("登录意图文本");
        document.setTech(List.of("Vue 3", "Element Plus"));
        document.getDependencies().put("element-plus", "2.8.8");
        document.getFiles().getFirst().setPath("src/views/LoginView.vue");
        document.getFiles().getFirst().setContent("SOURCE_MARKER_SHOULD_NOT_ENTER_RERANK");

        String text = service.buildVueDocumentText(document);

        assertTrue(text.contains("登录标题"));
        assertTrue(text.contains("登录描述"));
        assertTrue(text.contains("登录意图文本"));
        assertTrue(text.contains("Vue 3"));
        assertTrue(text.contains("vue@3.3.4"));
        assertTrue(text.contains("javascript"));
        assertTrue(text.contains("vite@4.4.5"));
        assertTrue(text.contains("element-plus=2.8.8"));
        assertTrue(text.contains("src/views/LoginView.vue"));
        assertFalse(text.contains("SOURCE_MARKER_SHOULD_NOT_ENTER_RERANK"));
        assertFalse(text.contains("源码开头"));
    }

    @Test
    void skipsIncompatibleFeaturesAndContinuesToLaterCandidates() {
        TemplateDoc skeleton = compatibleSkeleton("skeleton-primary");
        TemplateDoc wrongVue = compatibleFeature("wrong-vue");
        wrongVue.setFramework("vue@2.7.16");
        TemplateDoc wrongLanguage = compatibleFeature("wrong-language");
        wrongLanguage.setLanguage("typescript");
        TemplateDoc wrongBuild = compatibleFeature("wrong-build");
        wrongBuild.setBuildTool("webpack@5.94.0");
        TemplateDoc wrongDependency = compatibleFeature("wrong-dependency");
        wrongDependency.getDependencies().put("vue-router", "3.6.5");
        List<TemplateDoc> goodFeatures = List.of(
                compatibleFeature("good-1"), compatibleFeature("good-2"),
                compatibleFeature("good-3"), compatibleFeature("good-4"));
        List<TemplateDoc> orderedFeatures = join(
                List.of(wrongVue, wrongLanguage, wrongBuild, wrongDependency), goodFeatures);
        Harness harness = harness(join(List.of(skeleton), orderedFeatures));
        stubSuccessfulRecall(harness, List.of(skeleton), orderedFeatures);

        VueRagContext context = harness.service.retrieve(QUERY);

        assertEquals(List.of("good-1", "good-2", "good-3", "good-4"), context.features().stream()
                .map(TemplateDoc::getId).toList());
    }

    @Test
    void acceptsSameBuildToolNameWhenBuildToolHasNoVersion() {
        TemplateDoc skeleton = compatibleSkeleton("skeleton-vite");
        skeleton.setBuildTool("Vite");
        TemplateDoc feature = compatibleFeature("feature-vite");
        feature.setBuildTool("vite");
        Harness harness = harness(List.of(skeleton, feature));
        stubSuccessfulRecall(harness, List.of(skeleton), List.of(feature));

        VueRagContext context = harness.service.retrieve(QUERY);

        assertEquals(List.of("feature-vite"), context.features().stream()
                .map(TemplateDoc::getId).toList());
    }

    @Test
    void acceptsCaretAndTildeDependencyRangesWithSameMajor() {
        TemplateDoc skeleton = compatibleSkeleton("skeleton-ranges");
        skeleton.getDependencies().put("shared-package", "4.2.4");
        TemplateDoc caret = compatibleFeature("caret-range");
        caret.getDependencies().put("shared-package", "^4.x");
        TemplateDoc tilde = compatibleFeature("tilde-range");
        tilde.getDependencies().put("shared-package", "~4.1.0");
        Harness harness = harness(List.of(skeleton, caret, tilde));
        stubSuccessfulRecall(harness, List.of(skeleton), List.of(caret, tilde));

        VueRagContext context = harness.service.retrieve(QUERY);

        assertEquals(List.of("caret-range", "tilde-range"), context.features().stream()
                .map(TemplateDoc::getId).toList());
    }

    @Test
    void acceptsFixedDependencyAboveMinimumMajor() {
        TemplateDoc skeleton = compatibleSkeleton("skeleton-minimum");
        skeleton.getDependencies().put("shared-package", "4.2.4");
        TemplateDoc feature = compatibleFeature("minimum-range");
        feature.getDependencies().put("shared-package", ">=3.0.0");
        Harness harness = harness(List.of(skeleton, feature));
        stubSuccessfulRecall(harness, List.of(skeleton), List.of(feature));

        VueRagContext context = harness.service.retrieve(QUERY);

        assertEquals(List.of("minimum-range"), context.features().stream()
                .map(TemplateDoc::getId).toList());
    }

    @Test
    void treatsUnboundedDependencyRangesAsCompatible() {
        TemplateDoc skeleton = compatibleSkeleton("skeleton-unbounded");
        skeleton.getDependencies().put("shared-package", "4.2.4");
        TemplateDoc wildcard = compatibleFeature("wildcard-range");
        wildcard.getDependencies().put("shared-package", "*");
        TemplateDoc latest = compatibleFeature("latest-range");
        latest.getDependencies().put("shared-package", "latest");
        Harness harness = harness(List.of(skeleton, wildcard, latest));
        stubSuccessfulRecall(harness, List.of(skeleton), List.of(wildcard, latest));

        VueRagContext context = harness.service.retrieve(QUERY);

        assertEquals(List.of("wildcard-range", "latest-range"), context.features().stream()
                .map(TemplateDoc::getId).toList());
    }

    @Test
    void unboundedDependencyDoesNotConflictWithUnsupportedOtherDeclaration() {
        TemplateDoc skeleton = compatibleSkeleton("skeleton-complex-range");
        skeleton.getDependencies().put("shared-package", ">=3 <5");
        TemplateDoc feature = compatibleFeature("unbounded-feature");
        feature.getDependencies().put("shared-package", "*");
        Harness harness = harness(List.of(skeleton, feature));
        stubSuccessfulRecall(harness, List.of(skeleton), List.of(feature));

        VueRagContext context = harness.service.retrieve(QUERY);

        assertEquals(List.of("unbounded-feature"), context.features().stream()
                .map(TemplateDoc::getId).toList());
    }

    @Test
    void conservativelyRejectsUnsupportedComplexDependencyRanges() {
        TemplateDoc skeleton = compatibleSkeleton("skeleton-complex");
        skeleton.getDependencies().put("shared-package", "4.2.4");
        TemplateDoc comparatorSet = compatibleFeature("comparator-set");
        comparatorSet.getDependencies().put("shared-package", ">=3 <5");
        TemplateDoc union = compatibleFeature("range-union");
        union.getDependencies().put("shared-package", "^3 || ^4");
        TemplateDoc compatible = compatibleFeature("fixed-compatible");
        compatible.getDependencies().put("shared-package", "4.9.0");
        List<TemplateDoc> features = List.of(comparatorSet, union, compatible);
        Harness harness = harness(join(List.of(skeleton), features));
        stubSuccessfulRecall(harness, List.of(skeleton), features);

        VueRagContext context = harness.service.retrieve(QUERY);

        assertEquals(List.of("fixed-compatible"), context.features().stream()
                .map(TemplateDoc::getId).toList());
    }

    @Test
    void marksDegradedWhenDenseReturnsEmptyButBm25KeepsBothChainsAvailable() {
        TemplateDoc skeleton = compatibleSkeleton("bm25-only-skeleton");
        TemplateDoc feature = compatibleFeature("bm25-only-feature");
        Harness harness = harness(List.of(skeleton, feature));
        stubSuccessfulRecall(harness, List.of(skeleton), List.of(feature));

        VueRagContext context = harness.service.retrieve(QUERY);

        assertEquals("bm25-only-skeleton", context.skeleton().getId());
        assertEquals(List.of("bm25-only-feature"), context.features().stream()
                .map(TemplateDoc::getId).toList());
        assertTrue(context.degraded());
    }

    @Test
    void degradesToDenseWhenBm25Fails() {
        TemplateDoc skeleton = compatibleSkeleton("dense-skeleton");
        Harness harness = harness(List.of(skeleton));
        when(harness.bm25.retrieve(any(), any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("BM25 unavailable"));
        when(harness.dense.retrieveWithEmbedding(any(Embedding.class), eq(CATALOG_VERSION), any(), eq(10)))
                .thenAnswer(invocation -> candidatesForKind(List.of(skeleton), invocation.getArgument(2)));

        VueRagContext context = harness.service.retrieve(QUERY);

        assertEquals("dense-skeleton", context.skeleton().getId());
        assertTrue(context.degraded());
        verify(harness.metrics, times(2))
                .recordDegradation(VueRagDegradationReason.BM25_FAILED);
    }

    @Test
    void degradesToBm25WhenDenseFails() {
        TemplateDoc skeleton = compatibleSkeleton("bm25-skeleton");
        Harness harness = harness(List.of(skeleton));
        when(harness.bm25.retrieve(eq(QUERY), eq(CATALOG_VERSION), any(), eq(10)))
                .thenAnswer(invocation -> candidatesForKind(List.of(skeleton), invocation.getArgument(2)));
        when(harness.dense.retrieveWithEmbedding(any(Embedding.class), any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("Dense unavailable"));

        VueRagContext context = harness.service.retrieve(QUERY);

        assertEquals("bm25-skeleton", context.skeleton().getId());
        assertTrue(context.degraded());
        verify(harness.metrics, times(2))
                .recordDegradation(VueRagDegradationReason.DENSE_FAILED);
    }

    @Test
    void degradesToRrfOrderWhenRerankFails() {
        TemplateDoc skeleton = compatibleSkeleton("rrf-skeleton");
        TemplateDoc feature = compatibleFeature("rrf-feature");
        Harness harness = harness(List.of(skeleton, feature));
        stubSuccessfulRecall(harness, List.of(skeleton), List.of(feature));
        when(harness.rerank.rerankVue(any(), anyList(), anyInt()))
                .thenThrow(new RerankException("rerank unavailable"));

        VueRagContext context = harness.service.retrieve(QUERY);

        assertEquals("rrf-skeleton", context.skeleton().getId());
        assertEquals(List.of("rrf-feature"), context.features().stream().map(TemplateDoc::getId).toList());
        assertTrue(context.degraded());
        verify(harness.metrics, times(2))
                .recordDegradation(VueRagDegradationReason.RERANK_FAILED);
        verify(harness.metrics, times(2)).recordRerankCandidates(0);
    }

    @Test
    void skipsRerankAndMetricsWhenRerankIsDisabled() {
        TemplateDoc skeleton = compatibleSkeleton("rrf-skeleton");
        List<TemplateDoc> features = new ArrayList<>();
        for (int index = 1; index <= 7; index++) {
            TemplateDoc incompatible = compatibleFeature("incompatible-" + index);
            incompatible.setFramework("vue@2.7.16");
            features.add(incompatible);
        }
        features.add(compatibleFeature("feature-within-top-n"));
        features.add(compatibleFeature("feature-after-top-n"));
        Harness harness = harness(join(List.of(skeleton), features));
        harness.properties.getRerank().setEnabled(false);
        stubSuccessfulRecall(harness, List.of(skeleton), features);

        VueRagContext context = harness.service.retrieve(QUERY);

        assertEquals("rrf-skeleton", context.skeleton().getId());
        assertEquals(List.of("feature-within-top-n"), context.features().stream()
                .map(TemplateDoc::getId).toList());
        verify(harness.rerank, never()).rerankVue(any(), anyList(), anyInt());
        verify(harness.metrics, never()).recordRerankCandidates(anyInt());
        verify(harness.metrics, never())
                .recordDegradation(VueRagDegradationReason.RERANK_FAILED);
    }

    @Test
    void doesNotRecordRerankCandidatesWhenRrfOrParentDocumentsAreEmpty() {
        TemplateDoc basic = compatibleSkeleton(BASIC_SKELETON_ID);
        Harness harness = harness(List.of(basic));
        when(harness.bm25.retrieve(eq(QUERY), eq(CATALOG_VERSION), eq(RagDocumentKind.PROJECT_SKELETON), eq(10)))
                .thenReturn(List.of());
        when(harness.dense.retrieveWithEmbedding(any(Embedding.class), eq(CATALOG_VERSION),
                eq(RagDocumentKind.PROJECT_SKELETON), eq(10)))
                .thenReturn(List.of());
        when(harness.bm25.retrieve(eq(QUERY), eq(CATALOG_VERSION), eq(RagDocumentKind.FEATURE_SNIPPET), eq(10)))
                .thenReturn(List.of(new RankedCandidate(
                        "missing-feature", RagDocumentKind.FEATURE_SNIPPET, 1, 1.0)));
        when(harness.dense.retrieveWithEmbedding(any(Embedding.class), eq(CATALOG_VERSION),
                eq(RagDocumentKind.FEATURE_SNIPPET), eq(10)))
                .thenReturn(List.of());

        harness.service.retrieve(QUERY);

        verify(harness.rerank, never()).rerankVue(any(), anyList(), anyInt());
        verify(harness.metrics, never()).recordRerankCandidates(anyInt());
        verify(harness.metrics, never())
                .recordDegradation(VueRagDegradationReason.RERANK_FAILED);
    }

    @Test
    void fallsBackToBasicSkeletonWhenBothChannelsHaveNoResults() {
        TemplateDoc basic = compatibleSkeleton(BASIC_SKELETON_ID);
        Harness harness = harness(List.of(basic));
        when(harness.bm25.retrieve(any(), any(), any(), anyInt())).thenReturn(List.of());
        when(harness.dense.retrieveWithEmbedding(any(Embedding.class), any(), any(), anyInt())).thenReturn(List.of());

        VueRagContext context = harness.service.retrieve(QUERY);

        assertEquals(BASIC_SKELETON_ID, context.skeleton().getId());
        assertTrue(context.features().isEmpty());
        assertTrue(context.degraded());
        verify(harness.rerank, never()).rerankVue(any(), anyList(), anyInt());
        verify(harness.metrics)
                .recordDegradation(VueRagDegradationReason.FALLBACK_SKELETON);
        verify(harness.metrics).recordFinalSelection(1, 0);
    }

    @Test
    void keepsSuccessfulFeaturesWhenSkeletonUsesBasicFallback() {
        TemplateDoc basic = compatibleSkeleton(BASIC_SKELETON_ID);
        TemplateDoc feature = compatibleFeature("surviving-feature");
        Harness harness = harness(List.of(basic, feature));
        when(harness.bm25.retrieve(eq(QUERY), eq(CATALOG_VERSION), eq(RagDocumentKind.PROJECT_SKELETON), eq(10)))
                .thenReturn(List.of());
        when(harness.dense.retrieveWithEmbedding(any(Embedding.class), eq(CATALOG_VERSION),
                eq(RagDocumentKind.PROJECT_SKELETON), eq(10)))
                .thenReturn(List.of());
        when(harness.bm25.retrieve(eq(QUERY), eq(CATALOG_VERSION), eq(RagDocumentKind.FEATURE_SNIPPET), eq(10)))
                .thenReturn(candidates(List.of(feature), RagDocumentKind.FEATURE_SNIPPET));
        when(harness.dense.retrieveWithEmbedding(any(Embedding.class), eq(CATALOG_VERSION),
                eq(RagDocumentKind.FEATURE_SNIPPET), eq(10)))
                .thenReturn(List.of());

        VueRagContext context = harness.service.retrieve(QUERY);

        assertEquals(BASIC_SKELETON_ID, context.skeleton().getId());
        assertEquals(List.of("surviving-feature"), context.features().stream()
                .map(TemplateDoc::getId).toList());
        assertTrue(context.degraded());
    }

    @Test
    void marksDegradedWhenFeatureChannelsBothReturnNoResults() {
        TemplateDoc skeleton = compatibleSkeleton("skeleton-only");
        Harness harness = harness(List.of(skeleton));
        when(harness.bm25.retrieve(eq(QUERY), eq(CATALOG_VERSION), eq(RagDocumentKind.PROJECT_SKELETON), eq(10)))
                .thenReturn(candidates(List.of(skeleton), RagDocumentKind.PROJECT_SKELETON));
        when(harness.dense.retrieveWithEmbedding(any(Embedding.class), eq(CATALOG_VERSION),
                eq(RagDocumentKind.PROJECT_SKELETON), eq(10)))
                .thenReturn(List.of());
        when(harness.bm25.retrieve(eq(QUERY), eq(CATALOG_VERSION), eq(RagDocumentKind.FEATURE_SNIPPET), eq(10)))
                .thenReturn(List.of());
        when(harness.dense.retrieveWithEmbedding(any(Embedding.class), eq(CATALOG_VERSION),
                eq(RagDocumentKind.FEATURE_SNIPPET), eq(10)))
                .thenReturn(List.of());

        VueRagContext context = harness.service.retrieve(QUERY);

        assertEquals("skeleton-only", context.skeleton().getId());
        assertTrue(context.features().isEmpty());
        assertTrue(context.degraded());
    }

    @Test
    void returnsNoRagWhenCatalogIsUnavailable() {
        VueRetrievalResourceProvider provider = mock(VueRetrievalResourceProvider.class);
        when(provider.current()).thenReturn(Optional.empty());
        VueRagMetricsCollector metrics = mock(VueRagMetricsCollector.class);
        VueHybridRetrievalService service = new VueHybridRetrievalService(
                provider, mock(MilvusBm25Retriever.class), mock(DenseRetriever.class), mock(RrfFusionService.class),
                mock(RagRerankService.class), metrics, new RagProperties(), execution(new RagProperties(), metrics));

        VueRagContext context = service.retrieve(QUERY);

        assertNull(context.skeleton());
        assertTrue(context.features().isEmpty());
        assertNull(context.catalogVersion());
        assertTrue(context.degraded());
        verify(metrics).recordDegradation(VueRagDegradationReason.CATALOG_UNAVAILABLE);
        verify(metrics).recordFinalSelection(0, 0);
        verify(metrics).recordRetrievalDuration(any());
    }

    @Test
    void featureChainFailureDoesNotBlockSkeleton() {
        TemplateDoc skeleton = compatibleSkeleton("surviving-skeleton");
        Harness harness = harness(List.of(skeleton));
        when(harness.bm25.retrieve(eq(QUERY), eq(CATALOG_VERSION), eq(RagDocumentKind.PROJECT_SKELETON), eq(10)))
                .thenReturn(candidates(List.of(skeleton), RagDocumentKind.PROJECT_SKELETON));
        when(harness.dense.retrieveWithEmbedding(any(Embedding.class), eq(CATALOG_VERSION),
                eq(RagDocumentKind.PROJECT_SKELETON), eq(10)))
                .thenReturn(List.of());
        when(harness.bm25.retrieve(eq(QUERY), eq(CATALOG_VERSION), eq(RagDocumentKind.FEATURE_SNIPPET), eq(10)))
                .thenThrow(new IllegalStateException("feature BM25 failed"));
        when(harness.dense.retrieveWithEmbedding(any(Embedding.class), eq(CATALOG_VERSION),
                eq(RagDocumentKind.FEATURE_SNIPPET), eq(10)))
                .thenThrow(new IllegalStateException("feature Dense failed"));

        VueRagContext context = harness.service.retrieve(QUERY);

        assertEquals("surviving-skeleton", context.skeleton().getId());
        assertTrue(context.features().isEmpty());
        assertTrue(context.degraded());
    }

    @Test
    void contextDefensivelyCopiesAndCapsFeatureList() {
        List<TemplateDoc> source = new ArrayList<>(List.of(
                compatibleFeature("f1"), compatibleFeature("f2"),
                compatibleFeature("f3"), compatibleFeature("f4"),
                compatibleFeature("f5")));

        VueRagContext context = new VueRagContext(
                compatibleSkeleton("skeleton"), source, CATALOG_VERSION, false);
        source.clear();

        assertEquals(4, context.features().size());
        assertThrowsUnsupported(() -> context.features().add(compatibleFeature("f6")));
    }

    @Test
    void denseOnlyEvaluationUsesOnlyDenseCandidatesAndPreservesCompatibilityFiltering() {
        TemplateDoc skeleton = compatibleSkeleton("dense-skeleton");
        TemplateDoc incompatible = compatibleFeature("dense-incompatible");
        incompatible.setFramework("vue@2.7.16");
        TemplateDoc compatible = compatibleFeature("dense-compatible");
        Harness harness = harness(List.of(skeleton, incompatible, compatible));
        when(harness.dense.retrieveWithEmbedding(any(Embedding.class), eq(CATALOG_VERSION),
                eq(RagDocumentKind.PROJECT_SKELETON), eq(10)))
                .thenReturn(candidates(List.of(skeleton), RagDocumentKind.PROJECT_SKELETON));
        when(harness.dense.retrieveWithEmbedding(any(Embedding.class), eq(CATALOG_VERSION),
                eq(RagDocumentKind.FEATURE_SNIPPET), eq(10)))
                .thenReturn(candidates(
                        List.of(incompatible, compatible), RagDocumentKind.FEATURE_SNIPPET));

        VueRagContext context = harness.service.retrieveDenseOnlyForEvaluation(QUERY);

        assertEquals("dense-skeleton", context.skeleton().getId());
        assertEquals(List.of("dense-compatible"), context.features().stream()
                .map(TemplateDoc::getId).toList());
        assertFalse(context.degraded());
        verify(harness.bm25, never()).retrieve(any(), any(), any(), anyInt());
        verify(harness.fusion, never()).fuse(anyList(), anyList(), anyMap(), anyInt());
        verify(harness.rerank, never()).rerankVue(any(), anyList(), anyInt());
    }

    @Test
    void productionDenseOnlyUsesCurrentCatalogParentsAndSkipsHybridStages() {
        TemplateDoc skeleton = compatibleSkeleton("dense-production-skeleton");
        TemplateDoc incompatible = compatibleFeature("dense-production-incompatible");
        incompatible.setFramework("vue@2.7.16");
        TemplateDoc compatible = compatibleFeature("dense-production-compatible");
        Harness harness = harness(List.of(skeleton, incompatible, compatible));
        when(harness.dense.retrieveWithEmbedding(any(Embedding.class), eq(CATALOG_VERSION),
                eq(RagDocumentKind.PROJECT_SKELETON), eq(10)))
                .thenReturn(candidates(List.of(skeleton), RagDocumentKind.PROJECT_SKELETON));
        when(harness.dense.retrieveWithEmbedding(any(Embedding.class), eq(CATALOG_VERSION),
                eq(RagDocumentKind.FEATURE_SNIPPET), eq(10)))
                .thenReturn(candidates(
                        List.of(incompatible, compatible), RagDocumentKind.FEATURE_SNIPPET));

        VueRagContext context = harness.service.retrieveDenseOnly(QUERY);

        assertSame(skeleton, context.skeleton());
        assertEquals(List.of(compatible), context.features());
        assertEquals(CATALOG_VERSION, context.catalogVersion());
        assertFalse(context.degraded());
        verify(harness.bm25, never()).retrieve(any(), any(), any(), anyInt());
        verify(harness.fusion, never()).fuse(anyList(), anyList(), anyMap(), anyInt());
        verify(harness.rerank, never()).rerankVue(any(), anyList(), anyInt());
    }

    @Test
    void productionDenseOnlyFallsBackToBasicSkeletonButEvaluationDoesNot() {
        TemplateDoc basic = compatibleSkeleton(BASIC_SKELETON_ID);
        Harness harness = harness(List.of(basic));
        when(harness.dense.retrieveWithEmbedding(any(Embedding.class), eq(CATALOG_VERSION), any(), anyInt()))
                .thenReturn(List.of());

        VueRagContext production = harness.service.retrieveDenseOnly(QUERY);
        VueRagContext evaluation = harness.service.retrieveDenseOnlyForEvaluation(QUERY);

        assertSame(basic, production.skeleton());
        assertTrue(production.features().isEmpty());
        assertEquals(CATALOG_VERSION, production.catalogVersion());
        assertTrue(production.degraded());
        assertNull(evaluation.skeleton());
        assertTrue(evaluation.features().isEmpty());
        verify(harness.bm25, never()).retrieve(any(), any(), any(), anyInt());
        verify(harness.fusion, never()).fuse(anyList(), anyList(), anyMap(), anyInt());
        verify(harness.rerank, never()).rerankVue(any(), anyList(), anyInt());
    }

    @Test
    void productionDenseOnlyFallsBackToBasicSkeletonWhenDenseFails() {
        TemplateDoc basic = compatibleSkeleton(BASIC_SKELETON_ID);
        Harness harness = harness(List.of(basic));
        when(harness.dense.retrieveWithEmbedding(any(Embedding.class), any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("dense unavailable"));

        VueRagContext context = harness.service.retrieveDenseOnly(QUERY);

        assertSame(basic, context.skeleton());
        assertTrue(context.features().isEmpty());
        assertEquals(CATALOG_VERSION, context.catalogVersion());
        assertTrue(context.degraded());
        verify(harness.bm25, never()).retrieve(any(), any(), any(), anyInt());
        verify(harness.fusion, never()).fuse(anyList(), anyList(), anyMap(), anyInt());
        verify(harness.rerank, never()).rerankVue(any(), anyList(), anyInt());
    }

    @Test
    void productionDenseOnlyMarksDegradedWhenFeatureCandidatesAreEmpty() {
        TemplateDoc skeleton = compatibleSkeleton("dense-skeleton-only");
        Harness harness = harness(List.of(skeleton));
        when(harness.dense.retrieveWithEmbedding(any(Embedding.class), eq(CATALOG_VERSION),
                eq(RagDocumentKind.PROJECT_SKELETON), eq(10)))
                .thenReturn(candidates(List.of(skeleton), RagDocumentKind.PROJECT_SKELETON));
        when(harness.dense.retrieveWithEmbedding(any(Embedding.class), eq(CATALOG_VERSION),
                eq(RagDocumentKind.FEATURE_SNIPPET), eq(10)))
                .thenReturn(List.of());

        VueRagContext context = harness.service.retrieveDenseOnly(QUERY);

        assertEquals("dense-skeleton-only", context.skeleton().getId());
        assertTrue(context.features().isEmpty());
        assertTrue(context.degraded());
    }

    @Test
    void denseOnlyEvaluationReturnsUnavailableInsteadOfBasicFallbackWhenDenseFails() {
        TemplateDoc basic = compatibleSkeleton(BASIC_SKELETON_ID);
        Harness harness = harness(List.of(basic));
        when(harness.dense.retrieveWithEmbedding(any(Embedding.class), any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("dense unavailable"));

        VueRagContext context = harness.service.retrieveDenseOnlyForEvaluation(QUERY);

        assertNull(context.skeleton());
        assertTrue(context.features().isEmpty());
        assertEquals(CATALOG_VERSION, context.catalogVersion());
        assertTrue(context.degraded());
        verify(harness.bm25, never()).retrieve(any(), any(), any(), anyInt());
    }

    private void stubSuccessfulRecall(Harness harness,
                                      List<TemplateDoc> skeletons,
                                      List<TemplateDoc> features) {
        when(harness.bm25.retrieve(eq(QUERY), eq(CATALOG_VERSION), eq(RagDocumentKind.PROJECT_SKELETON), eq(10)))
                .thenReturn(candidates(skeletons, RagDocumentKind.PROJECT_SKELETON));
        when(harness.dense.retrieveWithEmbedding(any(Embedding.class), eq(CATALOG_VERSION),
                eq(RagDocumentKind.PROJECT_SKELETON), eq(10)))
                .thenReturn(List.of());
        when(harness.bm25.retrieve(eq(QUERY), eq(CATALOG_VERSION), eq(RagDocumentKind.FEATURE_SNIPPET), eq(10)))
                .thenReturn(candidates(features, RagDocumentKind.FEATURE_SNIPPET));
        when(harness.dense.retrieveWithEmbedding(any(Embedding.class), eq(CATALOG_VERSION),
                eq(RagDocumentKind.FEATURE_SNIPPET), eq(10)))
                .thenReturn(List.of());
    }

    private Harness harness(List<TemplateDoc> documents) {
        return harness(documents, new RagProperties());
    }

    private Harness harness(List<TemplateDoc> documents, RagProperties properties) {
        Map<String, TemplateDoc> byId = new LinkedHashMap<>();
        documents.forEach(document -> byId.put(document.getId(), document));
        TemplateCatalog catalog = mock(TemplateCatalog.class);
        when(catalog.getCatalogVersion()).thenReturn(CATALOG_VERSION);
        when(catalog.getDocuments()).thenReturn(List.copyOf(byId.values()));
        when(catalog.findDocumentById(any())).thenAnswer(invocation ->
                Optional.ofNullable(byId.get(invocation.getArgument(0))));

        MilvusBm25Retriever bm25 = mock(MilvusBm25Retriever.class);
        DenseRetriever dense = mock(DenseRetriever.class);
        when(dense.embedQuery(any())).thenAnswer(invocation -> Embedding.from(new float[]{1.0f, 2.0f}));
        RrfFusionService fusion = spy(new RrfFusionService());
        RagRerankService rerank = mock(RagRerankService.class);
        VueRagMetricsCollector metrics = mock(VueRagMetricsCollector.class);
        when(rerank.rerankVue(any(), anyList(), anyInt())).thenAnswer(invocation -> {
            List<TemplateDoc> candidates = invocation.getArgument(1);
            int topK = invocation.getArgument(2);
            return candidates.stream().limit(topK).toList();
        });
        VueRetrievalResourceProvider provider = mock(VueRetrievalResourceProvider.class);
        when(provider.current()).thenReturn(Optional.of(new VueRetrievalResources(catalog)));
        VueHybridRetrievalService service = new VueHybridRetrievalService(
                provider, bm25, dense, fusion, rerank, metrics, properties, execution(properties, metrics));
        return new Harness(service, bm25, dense, fusion, rerank, metrics, properties);
    }

    private List<RankedCandidate> candidates(List<TemplateDoc> documents, RagDocumentKind kind) {
        List<RankedCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < documents.size(); index++) {
            TemplateDoc document = documents.get(index);
            if (document.getDocumentKind() == kind) {
                candidates.add(new RankedCandidate(document.getId(), kind, index + 1, 100.0 - index));
            }
        }
        return List.copyOf(candidates);
    }

    private List<RankedCandidate> candidatesForKind(List<TemplateDoc> documents, RagDocumentKind kind) {
        return candidates(documents, kind);
    }

    private TemplateDoc compatibleSkeleton(String id) {
        TemplateDoc document = compatibleDocument(id, RagDocumentKind.PROJECT_SKELETON);
        document.getFiles().add(file("package.json", "{\"private\":true}"));
        document.getFiles().add(file("index.html", "<div id=\"app\"></div>"));
        document.getFiles().add(file("vite.config.js", "export default {}"));
        document.getFiles().add(file("src/main.js", "createApp(App)"));
        document.getFiles().add(file("src/App.vue", "<template />"));
        return document;
    }

    private TemplateDoc compatibleFeature(String id) {
        TemplateDoc document = compatibleDocument(id, RagDocumentKind.FEATURE_SNIPPET);
        document.getFiles().add(file("src/views/" + id + ".vue", "SOURCE_" + id));
        return document;
    }

    private TemplateDoc compatibleDocument(String id, RagDocumentKind kind) {
        TemplateDoc document = new TemplateDoc();
        document.setId(id);
        document.setDocumentKind(kind);
        document.setFramework("vue@3.3.4");
        document.setLanguage("javascript");
        document.setBuildTool("vite@4.4.5");
        document.setTitle("标题-" + id);
        document.setDescription("描述-" + id);
        document.setEmbedText("意图-" + id);
        document.setTech(List.of("vue3", "vite"));
        document.setDependencies(new LinkedHashMap<>(Map.of(
                "vue", "3.3.4", "vue-router", "4.2.4")));
        document.setDevDependencies(new LinkedHashMap<>(Map.of(
                "@vitejs/plugin-vue", "4.2.3", "vite", "4.4.5")));
        document.setFiles(new ArrayList<>());
        document.setQualityScore(0.9);
        return document;
    }

    private TemplateDoc.TemplateFile file(String path, String content) {
        TemplateDoc.TemplateFile file = new TemplateDoc.TemplateFile();
        file.setPath(path);
        file.setContent(content);
        return file;
    }

    private <T> List<T> join(List<T> first, List<T> second) {
        List<T> joined = new ArrayList<>(first);
        joined.addAll(second);
        return List.copyOf(joined);
    }

    private void assertThrowsUnsupported(Runnable action) {
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, action::run);
    }

    private record Harness(
            VueHybridRetrievalService service,
            MilvusBm25Retriever bm25,
            DenseRetriever dense,
            RrfFusionService fusion,
            RagRerankService rerank,
            VueRagMetricsCollector metrics,
            RagProperties properties
    ) {
    }
}
