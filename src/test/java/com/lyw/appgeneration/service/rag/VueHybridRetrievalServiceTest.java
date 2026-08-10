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
import com.lyw.appgeneration.service.rag.retrieval.Bm25Retriever;
import com.lyw.appgeneration.service.rag.retrieval.DenseRetriever;
import com.lyw.appgeneration.service.rag.retrieval.RrfFusionService;
import com.lyw.appgeneration.service.rag.retrieval.VueRetrievalResourceProvider;
import com.lyw.appgeneration.service.rag.retrieval.VueRetrievalResources;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import static org.mockito.Mockito.when;

class VueHybridRetrievalServiceTest {

    private static final String QUERY = "需要 Vue 管理后台、登录、表格、上传和看板";
    private static final String CATALOG_VERSION = "catalog-v5";
    private static final String BASIC_SKELETON_ID = "vue-skeleton-basic-001";

    @Test
    void runsSeparatedSkeletonAndFeatureChainsWithFixedParameters() {
        TemplateDoc skeleton = compatibleSkeleton("skeleton-primary");
        List<TemplateDoc> features = List.of(
                compatibleFeature("feature-1"), compatibleFeature("feature-2"),
                compatibleFeature("feature-3"), compatibleFeature("feature-4"),
                compatibleFeature("feature-5"), compatibleFeature("feature-6"));
        Harness harness = harness(join(List.of(skeleton), features));
        when(harness.bm25.retrieve(eq(QUERY), eq(RagDocumentKind.PROJECT_SKELETON), eq(10)))
                .thenReturn(candidates(List.of(skeleton), RagDocumentKind.PROJECT_SKELETON));
        when(harness.dense.retrieve(eq(QUERY), eq(CATALOG_VERSION),
                eq(RagDocumentKind.PROJECT_SKELETON), eq(10)))
                .thenReturn(candidates(List.of(skeleton), RagDocumentKind.PROJECT_SKELETON));
        when(harness.bm25.retrieve(eq(QUERY), eq(RagDocumentKind.FEATURE_SNIPPET), eq(10)))
                .thenReturn(candidates(features, RagDocumentKind.FEATURE_SNIPPET));
        when(harness.dense.retrieve(eq(QUERY), eq(CATALOG_VERSION),
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
        when(harness.bm25.retrieve(any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("BM25 unavailable"));
        when(harness.dense.retrieve(eq(QUERY), eq(CATALOG_VERSION), any(), eq(10)))
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
        when(harness.bm25.retrieve(eq(QUERY), any(), eq(10)))
                .thenAnswer(invocation -> candidatesForKind(List.of(skeleton), invocation.getArgument(1)));
        when(harness.dense.retrieve(any(), any(), any(), anyInt()))
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
        when(harness.bm25.retrieve(eq(QUERY), eq(RagDocumentKind.PROJECT_SKELETON), eq(10)))
                .thenReturn(List.of());
        when(harness.dense.retrieve(eq(QUERY), eq(CATALOG_VERSION),
                eq(RagDocumentKind.PROJECT_SKELETON), eq(10)))
                .thenReturn(List.of());
        when(harness.bm25.retrieve(eq(QUERY), eq(RagDocumentKind.FEATURE_SNIPPET), eq(10)))
                .thenReturn(List.of(new RankedCandidate(
                        "missing-feature", RagDocumentKind.FEATURE_SNIPPET, 1, 1.0)));
        when(harness.dense.retrieve(eq(QUERY), eq(CATALOG_VERSION),
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
        when(harness.bm25.retrieve(any(), any(), anyInt())).thenReturn(List.of());
        when(harness.dense.retrieve(any(), any(), any(), anyInt())).thenReturn(List.of());

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
        when(harness.bm25.retrieve(eq(QUERY), eq(RagDocumentKind.PROJECT_SKELETON), eq(10)))
                .thenReturn(List.of());
        when(harness.dense.retrieve(eq(QUERY), eq(CATALOG_VERSION),
                eq(RagDocumentKind.PROJECT_SKELETON), eq(10)))
                .thenReturn(List.of());
        when(harness.bm25.retrieve(eq(QUERY), eq(RagDocumentKind.FEATURE_SNIPPET), eq(10)))
                .thenReturn(candidates(List.of(feature), RagDocumentKind.FEATURE_SNIPPET));
        when(harness.dense.retrieve(eq(QUERY), eq(CATALOG_VERSION),
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
        when(harness.bm25.retrieve(eq(QUERY), eq(RagDocumentKind.PROJECT_SKELETON), eq(10)))
                .thenReturn(candidates(List.of(skeleton), RagDocumentKind.PROJECT_SKELETON));
        when(harness.dense.retrieve(eq(QUERY), eq(CATALOG_VERSION),
                eq(RagDocumentKind.PROJECT_SKELETON), eq(10)))
                .thenReturn(List.of());
        when(harness.bm25.retrieve(eq(QUERY), eq(RagDocumentKind.FEATURE_SNIPPET), eq(10)))
                .thenReturn(List.of());
        when(harness.dense.retrieve(eq(QUERY), eq(CATALOG_VERSION),
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
                provider, mock(DenseRetriever.class), mock(RrfFusionService.class),
                mock(RagRerankService.class), metrics, new RagProperties());

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
        when(harness.bm25.retrieve(eq(QUERY), eq(RagDocumentKind.PROJECT_SKELETON), eq(10)))
                .thenReturn(candidates(List.of(skeleton), RagDocumentKind.PROJECT_SKELETON));
        when(harness.dense.retrieve(eq(QUERY), eq(CATALOG_VERSION),
                eq(RagDocumentKind.PROJECT_SKELETON), eq(10)))
                .thenReturn(List.of());
        when(harness.bm25.retrieve(eq(QUERY), eq(RagDocumentKind.FEATURE_SNIPPET), eq(10)))
                .thenThrow(new IllegalStateException("feature BM25 failed"));
        when(harness.dense.retrieve(eq(QUERY), eq(CATALOG_VERSION),
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

    private void stubSuccessfulRecall(Harness harness,
                                      List<TemplateDoc> skeletons,
                                      List<TemplateDoc> features) {
        when(harness.bm25.retrieve(eq(QUERY), eq(RagDocumentKind.PROJECT_SKELETON), eq(10)))
                .thenReturn(candidates(skeletons, RagDocumentKind.PROJECT_SKELETON));
        when(harness.dense.retrieve(eq(QUERY), eq(CATALOG_VERSION),
                eq(RagDocumentKind.PROJECT_SKELETON), eq(10)))
                .thenReturn(List.of());
        when(harness.bm25.retrieve(eq(QUERY), eq(RagDocumentKind.FEATURE_SNIPPET), eq(10)))
                .thenReturn(candidates(features, RagDocumentKind.FEATURE_SNIPPET));
        when(harness.dense.retrieve(eq(QUERY), eq(CATALOG_VERSION),
                eq(RagDocumentKind.FEATURE_SNIPPET), eq(10)))
                .thenReturn(List.of());
    }

    private Harness harness(List<TemplateDoc> documents) {
        Map<String, TemplateDoc> byId = new LinkedHashMap<>();
        documents.forEach(document -> byId.put(document.getId(), document));
        TemplateCatalog catalog = mock(TemplateCatalog.class);
        when(catalog.getCatalogVersion()).thenReturn(CATALOG_VERSION);
        when(catalog.getDocuments()).thenReturn(List.copyOf(byId.values()));
        when(catalog.findDocumentById(any())).thenAnswer(invocation ->
                Optional.ofNullable(byId.get(invocation.getArgument(0))));

        Bm25Retriever bm25 = mock(Bm25Retriever.class);
        DenseRetriever dense = mock(DenseRetriever.class);
        RrfFusionService fusion = spy(new RrfFusionService());
        RagRerankService rerank = mock(RagRerankService.class);
        VueRagMetricsCollector metrics = mock(VueRagMetricsCollector.class);
        RagProperties properties = new RagProperties();
        when(rerank.rerankVue(any(), anyList(), anyInt())).thenAnswer(invocation -> {
            List<TemplateDoc> candidates = invocation.getArgument(1);
            int topK = invocation.getArgument(2);
            return candidates.stream().limit(topK).toList();
        });
        VueRetrievalResourceProvider provider = mock(VueRetrievalResourceProvider.class);
        when(provider.current()).thenReturn(Optional.of(new VueRetrievalResources(catalog, bm25)));
        VueHybridRetrievalService service = new VueHybridRetrievalService(
                provider, dense, fusion, rerank, metrics, properties);
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
            Bm25Retriever bm25,
            DenseRetriever dense,
            RrfFusionService fusion,
            RagRerankService rerank,
            VueRagMetricsCollector metrics,
            RagProperties properties
    ) {
    }
}
