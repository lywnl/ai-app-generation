package com.lyw.appgeneration.rag.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.service.rag.catalog.TemplateCatalog;
import com.lyw.appgeneration.service.rag.model.KnowledgeChunk;
import com.lyw.appgeneration.service.rag.model.RagChunkKind;
import com.lyw.appgeneration.service.rag.model.RagDocumentKind;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VueIngestionExpectedSnapshotTest {

    @Test
    void 不存在公共构造器且类型不可被record规范绕过() {
        assertEquals(0, VueIngestionExpectedSnapshot.class.getConstructors().length);
        assertFalse(VueIngestionExpectedSnapshot.class.isRecord());
        assertTrue(Modifier.isFinal(VueIngestionExpectedSnapshot.class.getModifiers()));
    }

    @Test
    void 当前目录生成二十三条稳定期望数据() {
        TemplateCatalog catalog = new TemplateCatalog(
                Path.of("embed_text/vue-project"), new ObjectMapper());

        VueIngestionExpectedSnapshot snapshot = VueIngestionExpectedSnapshot.from(catalog);

        assertEquals(catalog.getCatalogVersion(), snapshot.catalogVersion());
        assertEquals(23, snapshot.rowsByChunkId().size());
        assertEquals(1024, snapshot.embeddingDimension());
        assertEquals(Set.of("chunkId", "documentId", "documentKind", "chunkKind", "catalogVersion"),
                snapshot.metadataKeys());
        snapshot.rowsByChunkId().forEach((chunkId, row) ->
                assertEquals(UUID.nameUUIDFromBytes(chunkId.getBytes(StandardCharsets.UTF_8)),
                        row.embeddingId()));
        catalog.getChunks().forEach(chunk -> {
            VueIngestionExpectedSnapshot.ExpectedRow row = snapshot.rowsByChunkId().get(chunk.chunkId());
            assertNotNull(row, chunk.chunkId());
            assertEquals(chunk.chunkId(), row.chunkId());
            assertEquals(chunk.documentId(), row.documentId());
            assertEquals(chunk.documentKind(), row.documentKind());
            assertEquals(chunk.chunkKind(), row.chunkKind());
            assertEquals(chunk.searchText(), row.searchText());
        });
    }

    @Test
    void 拒绝非二十三条目录和重复块标识() {
        KnowledgeChunk firstChunk = new KnowledgeChunk(
                "first", "doc", RagDocumentKind.FEATURE_SNIPPET,
                RagChunkKind.OVERVIEW, "检索文本");
        KnowledgeChunk secondChunk = new KnowledgeChunk(
                "second", "doc", RagDocumentKind.FEATURE_SNIPPET,
                RagChunkKind.OVERVIEW, "另一段检索文本");

        assertThrows(IllegalArgumentException.class, () ->
                VueIngestionExpectedSnapshot.from("version", List.of(firstChunk, secondChunk)));
        assertThrows(IllegalArgumentException.class, () ->
                VueIngestionExpectedSnapshot.from("version", duplicateChunkIds()));
    }

    private static List<KnowledgeChunk> duplicateChunkIds() {
        return IntStream.range(0, 23)
                .mapToObj(index -> new KnowledgeChunk(
                        index == 22 ? "chunk-0" : "chunk-" + index,
                        "doc-" + index,
                        RagDocumentKind.FEATURE_SNIPPET,
                        RagChunkKind.OVERVIEW,
                        "检索文本-" + index))
                .toList();
    }
}
