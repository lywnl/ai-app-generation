package com.lyw.appgeneration.rag.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.service.rag.catalog.TemplateCatalog;
import com.lyw.appgeneration.service.rag.model.KnowledgeChunk;
import com.lyw.appgeneration.service.rag.model.RagChunkKind;
import com.lyw.appgeneration.service.rag.model.RagDocumentKind;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VueIngestionExpectedSnapshotTest {

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
    }

    @Test
    void 拒绝非二十三条目录和重复块标识() {
        KnowledgeChunk chunk = new KnowledgeChunk(
                "duplicate", "doc", RagDocumentKind.FEATURE_SNIPPET,
                RagChunkKind.OVERVIEW, "检索文本");

        assertThrows(IllegalArgumentException.class, () ->
                VueIngestionExpectedSnapshot.from("version", List.of(chunk), 23));
        assertThrows(IllegalArgumentException.class, () ->
                VueIngestionExpectedSnapshot.from("version", List.of(chunk, chunk), 2));
    }
}
