package com.lyw.appgeneration.service.rag.retrieval;

import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.service.rag.model.RagDocumentKind;
import com.lyw.appgeneration.service.rag.model.RankedCandidate;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DenseRetrieverTest {

    private static final String CATALOG_VERSION = "catalog-current";

    @Test
    void searchesVueStoreWithCurrentVersionAndDocumentKindFilters() {
        RecordingStore vueStore = new RecordingStore(List.of());
        RecordingStore htmlStore = new RecordingStore(List.of());
        RecordingEmbeddingModel embeddingModel = new RecordingEmbeddingModel();
        RagProperties properties = properties(0.37);
        DenseRetriever retriever = retriever(embeddingModel, vueStore, htmlStore, properties);

        retriever.retrieve("需要 Vue 管理后台", CATALOG_VERSION,
                RagDocumentKind.PROJECT_SKELETON, 7);

        assertEquals(List.of("需要 Vue 管理后台"), embeddingModel.queries);
        assertEquals(1, vueStore.searchCount);
        assertEquals(0, htmlStore.searchCount);
        assertEquals(7, vueStore.request.maxResults());
        assertEquals(0.37, vueStore.request.minScore());
        assertEquals(Embedding.from(new float[]{1.0f, 2.0f}), vueStore.request.queryEmbedding());
        assertTrue(vueStore.request.filter().test(metadata(
                "chunk-1", "skeleton-1", "PROJECT_SKELETON", "OVERVIEW", CATALOG_VERSION)));
        assertFalse(vueStore.request.filter().test(metadata(
                "chunk-1", "skeleton-1", "PROJECT_SKELETON", "OVERVIEW", "catalog-old")));
        assertFalse(vueStore.request.filter().test(metadata(
                "chunk-1", "feature-1", "FEATURE_SNIPPET", "OVERVIEW", CATALOG_VERSION)));
    }

    @Test
    void defaultsToTopTenAndAggregatesParentByHighestDenseScore() {
        RecordingStore vueStore = new RecordingStore(List.of(
                match(0.72, metadata("chunk-b-1", "document-b", "FEATURE_SNIPPET", "OVERVIEW", CATALOG_VERSION)),
                match(0.91, metadata("chunk-a-1", "document-a", "FEATURE_SNIPPET", "OVERVIEW", CATALOG_VERSION)),
                match(0.83, metadata("chunk-b-2", "document-b", "FEATURE_SNIPPET", "ENGINEERING", CATALOG_VERSION)),
                match(0.83, metadata("chunk-c-1", "document-c", "FEATURE_SNIPPET", "OVERVIEW", CATALOG_VERSION))
        ));
        DenseRetriever retriever = retriever(new RecordingEmbeddingModel(), vueStore,
                new RecordingStore(List.of()), properties(0.3));

        List<RankedCandidate> result = retriever.retrieve(
                "登录与上传", CATALOG_VERSION, RagDocumentKind.FEATURE_SNIPPET);

        assertEquals(10, vueStore.request.maxResults());
        assertEquals(List.of("document-a", "document-b", "document-c"), result.stream()
                .map(RankedCandidate::documentId).toList());
        assertEquals(List.of(1, 2, 3), result.stream().map(RankedCandidate::rank).toList());
        assertEquals(List.of(0.91, 0.83, 0.83), result.stream().map(RankedCandidate::score).toList());
    }

    @Test
    void skipsMissingAndIllegalMetadataWithoutBreakingChannel() {
        RecordingStore vueStore = new RecordingStore(List.of(
                match(0.99, Metadata.from(Map.of("documentId", "missing-fields"))),
                match(0.98, metadata("chunk-bad-kind", "bad-kind", "UNKNOWN", "OVERVIEW", CATALOG_VERSION)),
                match(0.97, metadata("chunk-bad-version", "bad-version", "FEATURE_SNIPPET", "OVERVIEW", "catalog-old")),
                new EmbeddingMatch<>(0.96, "null-segment", Embedding.from(new float[]{1.0f}), null),
                match(0.75, metadata("chunk-good", "document-good", "FEATURE_SNIPPET", "OVERVIEW", CATALOG_VERSION))
        ));
        DenseRetriever retriever = retriever(new RecordingEmbeddingModel(), vueStore,
                new RecordingStore(List.of()), properties(0.3));

        List<RankedCandidate> result = retriever.retrieve(
                "登录", CATALOG_VERSION, RagDocumentKind.FEATURE_SNIPPET);

        assertEquals(List.of("document-good"), result.stream().map(RankedCandidate::documentId).toList());
    }

    @Test
    void rejectsInvalidArgumentsWithoutCallingDependencies() {
        RecordingStore vueStore = new RecordingStore(List.of());
        RecordingEmbeddingModel embeddingModel = new RecordingEmbeddingModel();
        DenseRetriever retriever = retriever(embeddingModel, vueStore,
                new RecordingStore(List.of()), properties(0.3));

        assertTrue(retriever.retrieve("", CATALOG_VERSION, RagDocumentKind.FEATURE_SNIPPET).isEmpty());
        assertTrue(retriever.retrieve("登录", "", RagDocumentKind.FEATURE_SNIPPET).isEmpty());
        assertTrue(retriever.retrieve("登录", CATALOG_VERSION, null).isEmpty());
        assertTrue(retriever.retrieve("登录", CATALOG_VERSION, RagDocumentKind.FEATURE_SNIPPET, 0).isEmpty());
        assertEquals(0, vueStore.searchCount);
        assertTrue(embeddingModel.queries.isEmpty());
    }

    private DenseRetriever retriever(EmbeddingModel embeddingModel,
                                     EmbeddingStore<TextSegment> vueStore,
                                     EmbeddingStore<TextSegment> htmlStore,
                                     RagProperties properties) {
        Map<CodeGenTypeEnum, EmbeddingStore<TextSegment>> stores =
                new EnumMap<>(CodeGenTypeEnum.class);
        stores.put(CodeGenTypeEnum.VUE_PROJECT, vueStore);
        stores.put(CodeGenTypeEnum.HTML, htmlStore);
        return new DenseRetriever(embeddingModel, stores, properties);
    }

    private RagProperties properties(double minScore) {
        RagProperties properties = new RagProperties();
        properties.getRetrieval().setMinScore(minScore);
        return properties;
    }

    private static EmbeddingMatch<TextSegment> match(double score, Metadata metadata) {
        return new EmbeddingMatch<>(score, "embedding-id", Embedding.from(new float[]{1.0f}),
                TextSegment.from("不含源码的检索文本", metadata));
    }

    private static Metadata metadata(String chunkId,
                                     String documentId,
                                     String documentKind,
                                     String chunkKind,
                                     String catalogVersion) {
        return Metadata.from(Map.of(
                "chunkId", chunkId,
                "documentId", documentId,
                "documentKind", documentKind,
                "chunkKind", chunkKind,
                "catalogVersion", catalogVersion
        ));
    }

    private static final class RecordingEmbeddingModel implements EmbeddingModel {

        private final List<String> queries = new ArrayList<>();

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            queries.addAll(segments.stream().map(TextSegment::text).toList());
            return Response.from(segments.stream()
                    .map(segment -> Embedding.from(new float[]{1.0f, 2.0f}))
                    .toList());
        }
    }

    private static final class RecordingStore implements EmbeddingStore<TextSegment> {

        private final List<EmbeddingMatch<TextSegment>> matches;
        private EmbeddingSearchRequest request;
        private int searchCount;

        private RecordingStore(List<EmbeddingMatch<TextSegment>> matches) {
            this.matches = matches;
        }

        @Override
        public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
            this.request = request;
            searchCount++;
            return new EmbeddingSearchResult<>(matches);
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
    }
}
