package com.lyw.appgeneration.service.rag.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lyw.appgeneration.service.rag.catalog.TemplateCatalog;
import com.lyw.appgeneration.service.rag.model.KnowledgeChunk;
import com.lyw.appgeneration.service.rag.model.TemplateDoc;
import com.lyw.appgeneration.service.rag.support.TemplateTestData;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import static org.junit.jupiter.api.Assertions.assertThrows;

class VueKnowledgeIngestorTest {

    private static final Path DATASET_ROOT = Path.of("embed_text/vue-project");
    private static final Set<String> METADATA_KEYS = Set.of(
            "chunkId", "documentId", "documentKind", "chunkKind", "catalogVersion"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void batchesTwentyThreeChunksWithStableIdsAndMinimalMetadata() {
        RecordingEmbeddingModel embeddingModel = new RecordingEmbeddingModel();
        RecordingEmbeddingStore store = new RecordingEmbeddingStore();
        TemplateCatalog catalog = new TemplateCatalog(DATASET_ROOT, objectMapper);

        VueKnowledgeIngestor.IngestResult result = ingestor(embeddingModel).ingest(DATASET_ROOT, store);

        assertEquals(1, embeddingModel.batchCalls);
        assertEquals(1, store.batchCalls);
        assertEquals(23, result.chunkCount());
        assertEquals(catalog.getCatalogVersion(), result.catalogVersion());
        assertEquals(catalog.getChunks().stream().map(KnowledgeChunk::searchText).toList(),
                embeddingModel.inputs.stream().map(TextSegment::text).toList());
        assertEquals(embeddingModel.inputs, store.segments);

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

    @Test
    void rejectsEmbeddingCountMismatchBeforeWritingStore() {
        RecordingEmbeddingModel embeddingModel = new RecordingEmbeddingModel();
        embeddingModel.dropLastEmbedding = true;
        RecordingEmbeddingStore store = new RecordingEmbeddingStore();

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> ingestor(embeddingModel).ingest(DATASET_ROOT, store));

        assertFalse(exception.getMessage().isBlank());
        assertEquals(0, store.batchCalls);
        assertEquals(0, store.visibleSegments.size());
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
        private boolean dropLastEmbedding;
        private List<TextSegment> inputs = List.of();

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            batchCalls++;
            inputs = List.copyOf(segments);
            int resultSize = dropLastEmbedding ? Math.max(0, segments.size() - 1) : segments.size();
            List<Embedding> embeddings = java.util.stream.IntStream.range(0, resultSize)
                    .mapToObj(index -> Embedding.from(new float[]{index, index + 1}))
                    .toList();
            return Response.from(embeddings);
        }
    }

    private static final class RecordingEmbeddingStore implements EmbeddingStore<TextSegment> {

        private int batchCalls;
        private List<String> ids = List.of();
        private List<TextSegment> segments = List.of();
        private final Map<String, TextSegment> visibleSegments = new LinkedHashMap<>();

        @Override
        public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> segments) {
            batchCalls++;
            this.ids = List.copyOf(ids);
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
