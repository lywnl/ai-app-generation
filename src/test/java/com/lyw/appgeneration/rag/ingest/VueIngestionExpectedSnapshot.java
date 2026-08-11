package com.lyw.appgeneration.rag.ingest;

import com.lyw.appgeneration.service.rag.catalog.TemplateCatalog;
import com.lyw.appgeneration.service.rag.model.KnowledgeChunk;
import com.lyw.appgeneration.service.rag.model.RagChunkKind;
import com.lyw.appgeneration.service.rag.model.RagDocumentKind;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 由 Vue 模板目录直接计算的摄取核验期望，不能由摄取结果反推。
 */
public final class VueIngestionExpectedSnapshot {

    private static final int CURRENT_CHUNK_COUNT = 23;
    private static final int EMBEDDING_DIMENSION = 1024;
    private static final Set<String> METADATA_KEYS = Set.of(
            "chunkId", "documentId", "documentKind", "chunkKind", "catalogVersion");
    private final String catalogVersion;
    private final int embeddingDimension;
    private final Set<String> metadataKeys;
    private final Map<String, ExpectedRow> rowsByChunkId;

    private VueIngestionExpectedSnapshot(
            String catalogVersion,
            int embeddingDimension,
            Set<String> metadataKeys,
            Map<String, ExpectedRow> rowsByChunkId) {
        this.catalogVersion = catalogVersion;
        this.embeddingDimension = embeddingDimension;
        this.metadataKeys = Set.copyOf(metadataKeys);
        this.rowsByChunkId = Map.copyOf(rowsByChunkId);
    }

    public static VueIngestionExpectedSnapshot from(TemplateCatalog catalog) {
        return from(catalog.getCatalogVersion(), catalog.getChunks());
    }

    static VueIngestionExpectedSnapshot from(
            String catalogVersion,
            List<KnowledgeChunk> chunks) {
        if (catalogVersion == null || catalogVersion.isBlank()) {
            throw new IllegalArgumentException("Vue 目录版本为空");
        }
        if (chunks == null || chunks.size() != CURRENT_CHUNK_COUNT) {
            throw new IllegalArgumentException(
                    "Vue 知识块数量必须为 %d，实际为 %d".formatted(
                            CURRENT_CHUNK_COUNT, chunks == null ? 0 : chunks.size()));
        }

        Map<String, ExpectedRow> rows = new LinkedHashMap<>();
        for (KnowledgeChunk chunk : chunks) {
            ExpectedRow row = ExpectedRow.from(chunk);
            if (rows.putIfAbsent(chunk.chunkId(), row) != null) {
                throw new IllegalArgumentException("Vue 知识块 ID 重复: " + chunk.chunkId());
            }
        }
        if (rows.values().stream().map(ExpectedRow::embeddingId).distinct().count() != rows.size()) {
            throw new IllegalArgumentException("Vue 知识块稳定 UUID 重复");
        }
        return new VueIngestionExpectedSnapshot(
                catalogVersion, EMBEDDING_DIMENSION, METADATA_KEYS, rows);
    }

    public String catalogVersion() {
        return catalogVersion;
    }

    public int embeddingDimension() {
        return embeddingDimension;
    }

    public Set<String> metadataKeys() {
        return metadataKeys;
    }

    public Map<String, ExpectedRow> rowsByChunkId() {
        return rowsByChunkId;
    }

    public record ExpectedRow(
            UUID embeddingId,
            String chunkId,
            String documentId,
            RagDocumentKind documentKind,
            RagChunkKind chunkKind,
            String searchText) {

        static ExpectedRow from(KnowledgeChunk chunk) {
            String chunkId = chunk.chunkId();
            return new ExpectedRow(
                    UUID.nameUUIDFromBytes(chunkId.getBytes(StandardCharsets.UTF_8)),
                    chunkId,
                    chunk.documentId(),
                    chunk.documentKind(),
                    chunk.chunkKind(),
                    chunk.searchText());
        }
    }
}
