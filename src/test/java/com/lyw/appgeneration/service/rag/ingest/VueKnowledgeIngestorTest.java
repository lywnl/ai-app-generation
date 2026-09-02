package com.lyw.appgeneration.service.rag.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.ai.AiCodeGeneratorService;
import com.lyw.appgeneration.ai.image.ImageCollectionService;
import com.lyw.appgeneration.ai.tools.FileToolBudgetGuard;
import com.lyw.appgeneration.ai.tools.FileToolExecutionScopeManager;
import com.lyw.appgeneration.core.AiCodeGeneratorFacade;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import com.lyw.appgeneration.core.concurrency.VueTurnAdmissionController;
import com.lyw.appgeneration.core.handler.VueTurnContext;
import com.lyw.appgeneration.core.handler.VueTurnMode;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.monitor.VueBuildRepairMetricsCollector;
import com.lyw.appgeneration.service.rag.RagPromptAssembler;
import com.lyw.appgeneration.service.rag.RagRerankService;
import com.lyw.appgeneration.service.rag.RagRetrievalService;
import com.lyw.appgeneration.service.rag.VueHybridRetrievalService;
import com.lyw.appgeneration.service.rag.catalog.TemplateCatalog;
import com.lyw.appgeneration.service.rag.model.KnowledgeChunk;
import com.lyw.appgeneration.service.rag.model.RankedCandidate;
import com.lyw.appgeneration.service.rag.model.TemplateDoc;
import com.lyw.appgeneration.service.rag.model.VueRagContext;
import com.lyw.appgeneration.service.rag.monitor.VueRagMetricsCollector;
import com.lyw.appgeneration.service.rag.retrieval.DenseRetriever;
import com.lyw.appgeneration.service.rag.retrieval.MilvusBm25Retriever;
import com.lyw.appgeneration.service.rag.retrieval.RrfFusionService;
import com.lyw.appgeneration.service.rag.retrieval.VueRetrievalResourceProvider;
import com.lyw.appgeneration.service.rag.retrieval.VueRetrievalResources;
import com.lyw.appgeneration.service.rag.support.TemplateTestData;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.ModelRequestGate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VueKnowledgeIngestorTest {

    private static final Path DATASET_ROOT = Path.of("embed_text/vue-project");
    private static final Set<String> METADATA_KEYS = Set.of(
            "chunkId", "documentId", "documentKind", "chunkKind", "catalogVersion"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void splitsTwentyThreeChunksIntoDashScopeCompatibleEmbeddingBatches() {
        RecordingEmbeddingModel embeddingModel = new RecordingEmbeddingModel();
        RecordingEmbeddingStore store = new RecordingEmbeddingStore();
        TemplateCatalog catalog = new TemplateCatalog(DATASET_ROOT, objectMapper);

        VueKnowledgeIngestor.IngestResult result = ingestor(embeddingModel).ingest(DATASET_ROOT, store);

        assertEquals(3, embeddingModel.batchCalls);
        assertEquals(List.of(10, 10, 3), embeddingModel.batchSizes);
        assertEquals(1, store.batchCalls);
        assertEquals(23, result.chunkCount());
        assertEquals(catalog.getCatalogVersion(), result.catalogVersion());
        assertEquals(catalog.getChunks().stream().map(KnowledgeChunk::searchText).toList(),
                embeddingModel.inputs.stream().map(TextSegment::text).toList());
        assertEquals(embeddingModel.inputs, store.segments);
        assertEquals(23, store.embeddings.size());
        assertEquals(store.ids.size(), store.embeddings.size());
        assertEquals(store.segments.size(), store.embeddings.size());

        for (int index = 0; index < catalog.getChunks().size(); index++) {
            KnowledgeChunk chunk = catalog.getChunks().get(index);
            TextSegment segment = store.segments.get(index);
            String stableId = UUID.nameUUIDFromBytes(chunk.chunkId().getBytes(StandardCharsets.UTF_8)).toString();
            assertEquals(stableId, store.ids.get(index));
            assertEquals(chunk.searchText(), segment.text());
            assertEquals(METADATA_KEYS, segment.metadata().toMap().keySet());
            assertEquals(chunk.chunkId(), segment.metadata().getString("chunkId"));
            assertEquals(chunk.documentId(), segment.metadata().getString("documentId"));
            assertEquals(chunk.documentKind().name(), segment.metadata().getString("documentKind"));
            assertEquals(chunk.chunkKind().name(), segment.metadata().getString("chunkKind"));
            assertEquals(catalog.getCatalogVersion(), segment.metadata().getString("catalogVersion"));
            assertEquals((float) index, store.embeddings.get(index).vector()[0]);
        }
        assertSourceCodeAbsent(catalog, store.segments);
    }

    @Test
    void repeatedIngestionUsesSameIdsWithoutIncreasingVisibleRows() {
        RecordingEmbeddingStore store = new RecordingEmbeddingStore();
        VueKnowledgeIngestor ingestor = ingestor(new RecordingEmbeddingModel());

        ingestor.ingest(DATASET_ROOT, store);
        List<String> firstIds = List.copyOf(store.ids);
        ingestor.ingest(DATASET_ROOT, store);

        assertEquals(firstIds, store.ids);
        assertEquals(23, store.visibleSegments.size());
        assertEquals(2, store.batchCalls);
    }

    @Test
    void changedCatalogPublishesOnlyTheNewCatalogVersion(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("features/login.json");
        ObjectNode document = TemplateTestData.featureDocument("feature-login");
        TemplateTestData.write(file, document);
        RecordingEmbeddingStore store = new RecordingEmbeddingStore();
        VueKnowledgeIngestor ingestor = ingestor(new RecordingEmbeddingModel());

        VueKnowledgeIngestor.IngestResult first = ingestor.ingest(tempDir, store);
        document.put("embedText", "更新后的登录检索描述");
        TemplateTestData.write(file, document);
        VueKnowledgeIngestor.IngestResult second = ingestor.ingest(tempDir, store);

        assertNotEquals(first.catalogVersion(), second.catalogVersion());
        assertEquals(Set.of(second.catalogVersion()), store.segments.stream()
                .map(segment -> segment.metadata().getString("catalogVersion"))
                .collect(java.util.stream.Collectors.toSet()));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void rejectsAnyBatchEmbeddingCountMismatchBeforeWritingStore(int failedBatch) {
        RecordingEmbeddingModel embeddingModel = new RecordingEmbeddingModel();
        embeddingModel.mismatchBatch = failedBatch;
        RecordingEmbeddingStore store = new RecordingEmbeddingStore();

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> ingestor(embeddingModel).ingest(DATASET_ROOT, store));

        int expectedStart = (failedBatch - 1) * 10;
        int expectedEnd = Math.min(expectedStart + 10, 23);
        assertTrue(exception.getMessage().contains("批次=" + failedBatch));
        assertTrue(exception.getMessage().contains(
                "范围=[" + expectedStart + "," + expectedEnd + ")"));
        assertEquals(0, store.batchCalls);
        assertEquals(0, store.visibleSegments.size());
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void modelExceptionInLaterBatchPropagatesWithoutWritingStore(int failedBatch) {
        RecordingEmbeddingModel embeddingModel = new RecordingEmbeddingModel();
        IllegalStateException original = new IllegalStateException("第 " + failedBatch + " 批失败");
        embeddingModel.exceptionBatch = failedBatch;
        embeddingModel.exception = original;
        RecordingEmbeddingStore store = new RecordingEmbeddingStore();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> ingestor(embeddingModel).ingest(DATASET_ROOT, store));

        assertSame(original, thrown);
        assertEquals(failedBatch, embeddingModel.batchCalls);
        assertEquals(0, store.batchCalls);
        assertEquals(0, store.visibleSegments.size());
    }

    @Test
    void productionDenseOnlyIgnoresLegacyRowsAndAssemblesCurrentParentSource() {
        TemplateCatalog catalog = new TemplateCatalog(DATASET_ROOT, objectMapper);
        DeterministicEmbeddingModel embeddingModel = new DeterministicEmbeddingModel();
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        addLegacyRows(store);
        new VueKnowledgeIngestor(embeddingModel, objectMapper).ingest(DATASET_ROOT, store);
        RagProperties properties = new RagProperties();
        properties.setEnabled(true);
        properties.getRetrieval().setMinScore(0.0);
        DenseRetriever denseRetriever = new DenseRetriever(
                embeddingModel, Map.of(CodeGenTypeEnum.VUE_PROJECT, store), properties);

        List<RankedCandidate> denseCandidates = denseRetriever.retrieve(
                "Vue3 基础工程 登录表单", catalog.getCatalogVersion(),
                com.lyw.appgeneration.service.rag.model.RagDocumentKind.PROJECT_SKELETON, 10);
        ProductionRetrievalHarness retrievalHarness = productionRetrievalService(
                catalog, denseRetriever, embeddingModel, store, properties);
        String prompt = generateWithDisabledHybrid(retrievalHarness.service(), properties);
        VueRagContext context = retrievalHarness.service()
                .retrieveVueProjectDenseOnly("Vue3 基础工程 登录表单");

        assertFalse(denseCandidates.isEmpty());
        assertTrue(denseCandidates.stream().allMatch(candidate ->
                catalog.findDocumentById(candidate.documentId()).isPresent()));
        assertFalse(denseCandidates.stream().map(RankedCandidate::documentId).toList()
                .contains("legacy-schema-document"));
        assertFalse(denseCandidates.stream().map(RankedCandidate::documentId).toList()
                .contains("old-version-document"));
        assertEquals(catalog.getCatalogVersion(), context.catalogVersion());
        assertTrue(context.skeleton() != null);
        String parentSource = context.skeleton().getFiles().stream()
                .filter(file -> "src/App.vue".equals(file.getPath()))
                .findFirst()
                .orElseThrow()
                .getContent();
        assertTrue(prompt.contains("│ " + parentSource.lines().findFirst().orElseThrow()));
        assertFalse(prompt.contains("LEGACY_SCHEMA_SOURCE"));
        assertFalse(prompt.contains("OLD_CATALOG_SOURCE"));
        verify(retrievalHarness.fusion(), never()).fuse(
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyInt());
        verify(retrievalHarness.rerank(), never()).rerankVue(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    private String generateWithDisabledHybrid(
            RagRetrievalService retrievalService,
            RagProperties properties) {
        properties.getHybrid().setEnabled(false);
        AiCodeGeneratorService generator = mock(AiCodeGeneratorService.class);
        ImageCollectionService imageService = mock(ImageCollectionService.class);
        when(imageService.enhancePrompt("Vue3 基础工程 登录表单"))
                .thenReturn("Vue3 基础工程 登录表单\n图片增强信息");
        when(generator.generateVueProjectCodeStream(
                org.mockito.ArgumentMatchers.eq(9L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(mock(TokenStream.class));
        AiCodeGeneratorFacade facade = new AiCodeGeneratorFacade();
        ReflectionTestUtils.setField(facade, "imageCollectionService", imageService);
        ReflectionTestUtils.setField(facade, "ragRetrievalService", retrievalService);
        ReflectionTestUtils.setField(facade, "ragPromptAssembler",
                new RagPromptAssembler(properties, mock(VueRagMetricsCollector.class)));
        ReflectionTestUtils.setField(facade, "ragProperties", properties);
        FileToolBudgetGuard budgetGuard = new FileToolBudgetGuard();
        ReflectionTestUtils.setField(facade, "fileToolExecutionScopeManager",
                new FileToolExecutionScopeManager(budgetGuard));
        ReflectionTestUtils.setField(facade, "modelRequestGate",
                mock(ModelRequestGate.class));
        AppOperationLeaseManager operationManager =
                new AppOperationLeaseManager();
        VueBuildSessionManager sessionManager = new VueBuildSessionManager();
        var operation = operationManager.acquire(
                9L, AppOperationLeaseManager.AppOperationType.GENERATE,
                "disabled-hybrid-real-turn");
        var lease = sessionManager.open(
                operation, 11L, "disabled-hybrid-real-turn");
        var permit = new VueTurnAdmissionController(
                new VueBuildRepairMetricsCollector(new SimpleMeterRegistry()))
                .tryAcquire().orElseThrow();
        VueTurnContext turnContext = new VueTurnContext(
                9L, 11L, "disabled-hybrid-real-turn", operation, lease,
                permit, budgetGuard.newSession(), true);
        turnContext.initializeMode(VueTurnMode.MUTATION_REQUIRED);

        facade.generateVueProjectStream(
                "Vue3 基础工程 登录表单", 9L, true,
                turnContext, generator);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(generator).generateVueProjectCodeStream(
                org.mockito.ArgumentMatchers.eq(9L), promptCaptor.capture());
        verify(imageService).enhancePrompt("Vue3 基础工程 登录表单");
        turnContext.closeResources();
        return promptCaptor.getValue();
    }

    private ProductionRetrievalHarness productionRetrievalService(
            TemplateCatalog catalog,
            DenseRetriever denseRetriever,
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> store,
            RagProperties properties) {
        VueRetrievalResourceProvider provider = mock(VueRetrievalResourceProvider.class);
        when(provider.current()).thenReturn(java.util.Optional.of(
                new VueRetrievalResources(catalog)));
        RrfFusionService fusion = mock(RrfFusionService.class);
        RagRerankService rerank = mock(RagRerankService.class);
        VueHybridRetrievalService vueService = new VueHybridRetrievalService(
                provider, mock(MilvusBm25Retriever.class), denseRetriever, fusion, rerank,
                mock(VueRagMetricsCollector.class), properties);
        RagRetrievalService retrievalService = new RagRetrievalService(
                embeddingModel, Map.of(CodeGenTypeEnum.VUE_PROJECT, store),
                properties, rerank, vueService, mock(
                        com.lyw.appgeneration.service.rag.retrieval.NativeTemplateCatalogProvider.class));
        return new ProductionRetrievalHarness(retrievalService, fusion, rerank);
    }

    private void addLegacyRows(InMemoryEmbeddingStore<TextSegment> store) {
        store.add("legacy-schema-row", Embedding.from(new float[]{1.0f, 0.0f}), TextSegment.from(
                "旧 Vue 模板", Metadata.from(Map.of(
                        "id", "legacy-schema-document",
                        "title", "旧模板",
                        "category", "legacy",
                        "code", "LEGACY_SCHEMA_SOURCE"))));
        store.add("old-version-row", Embedding.from(new float[]{1.0f, 0.0f}), TextSegment.from(
                "旧目录 Vue 模板", Metadata.from(Map.of(
                        "chunkId", "old-version:overview",
                        "documentId", "old-version-document",
                        "documentKind", "PROJECT_SKELETON",
                        "chunkKind", "OVERVIEW",
                        "catalogVersion", "catalog-old",
                        "code", "OLD_CATALOG_SOURCE"))));
    }

    private VueKnowledgeIngestor ingestor(EmbeddingModel embeddingModel) {
        return new VueKnowledgeIngestor(embeddingModel, objectMapper);
    }

    private void assertSourceCodeAbsent(TemplateCatalog catalog, List<TextSegment> segments) {
        List<String> sourceContents = catalog.getDocuments().stream()
                .map(TemplateDoc::getFiles)
                .flatMap(List::stream)
                .map(TemplateDoc.TemplateFile::getContent)
                .filter(content -> content != null && !content.isBlank())
                .toList();
        for (TextSegment segment : segments) {
            String metadata = segment.metadata().toMap().toString();
            sourceContents.forEach(source -> {
                assertFalse(segment.text().contains(source));
                assertFalse(metadata.contains(source));
            });
        }
    }

    private static final class RecordingEmbeddingModel implements EmbeddingModel {

        private int batchCalls;
        private int mismatchBatch = -1;
        private int exceptionBatch = -1;
        private RuntimeException exception;
        private int nextEmbeddingIndex;
        private final List<Integer> batchSizes = new ArrayList<>();
        private final List<TextSegment> inputs = new ArrayList<>();

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            batchCalls++;
            batchSizes.add(segments.size());
            inputs.addAll(segments);
            if (batchCalls == exceptionBatch) {
                throw exception;
            }
            int resultSize = batchCalls == mismatchBatch
                    ? Math.max(0, segments.size() - 1)
                    : segments.size();
            List<Embedding> embeddings = java.util.stream.IntStream.range(0, resultSize)
                    .mapToObj(index -> {
                        int globalIndex = nextEmbeddingIndex++;
                        return Embedding.from(new float[]{globalIndex, globalIndex + 1});
                    })
                    .toList();
            return Response.from(embeddings);
        }
    }

    private static final class DeterministicEmbeddingModel implements EmbeddingModel {

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            boolean query = segments.size() == 1;
            return Response.from(segments.stream()
                    .map(segment -> query
                            ? Embedding.from(new float[]{1.0f, 0.0f})
                            : Embedding.from(new float[]{0.8f, 0.2f}))
                    .toList());
        }
    }

    private record ProductionRetrievalHarness(
            RagRetrievalService service,
            RrfFusionService fusion,
            RagRerankService rerank) {
    }

    private static final class RecordingEmbeddingStore implements EmbeddingStore<TextSegment> {

        private int batchCalls;
        private List<String> ids = List.of();
        private List<Embedding> embeddings = List.of();
        private List<TextSegment> segments = List.of();
        private final Map<String, TextSegment> visibleSegments = new LinkedHashMap<>();

        @Override
        public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> segments) {
            batchCalls++;
            this.ids = List.copyOf(ids);
            this.embeddings = List.copyOf(embeddings);
            this.segments = List.copyOf(segments);
            for (int index = 0; index < ids.size(); index++) {
                visibleSegments.put(ids.get(index), segments.get(index));
            }
        }

        @Override
        public String add(Embedding embedding) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void add(String id, Embedding embedding) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String add(Embedding embedding, TextSegment embedded) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> addAll(List<Embedding> embeddings) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
            return new EmbeddingSearchResult<>(new ArrayList<>());
        }
    }
}
