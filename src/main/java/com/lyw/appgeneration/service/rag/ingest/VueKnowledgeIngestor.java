package com.lyw.appgeneration.service.rag.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.service.rag.catalog.TemplateCatalog;
import com.lyw.appgeneration.service.rag.model.KnowledgeChunk;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 将经过目录校验的 Vue 知识块批量摄取到稠密向量存储。
 */
@Slf4j
@Component
public class VueKnowledgeIngestor {

    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;

    public VueKnowledgeIngestor(EmbeddingModel embeddingModel, ObjectMapper objectMapper) {
        this.embeddingModel = embeddingModel;
        this.objectMapper = objectMapper;
    }

    /**
     * 使用稳定块 ID 批量写入当前目录版本。
     *
     * @param vueRoot Vue 知识目录
     * @param store Vue 稠密向量存储
     * @return 当前目录版本和摄取块数
     */
    public IngestResult ingest(Path vueRoot, EmbeddingStore<TextSegment> store) {
        long start = System.currentTimeMillis();
        TemplateCatalog catalog = new TemplateCatalog(vueRoot, objectMapper);
        List<KnowledgeChunk> chunks = catalog.getChunks();
        List<TextSegment> segments = chunks.stream()
                .map(chunk -> createSegment(chunk, catalog.getCatalogVersion()))
                .toList();
        List<String> ids = chunks.stream()
                .map(KnowledgeChunk::chunkId)
                .map(VueKnowledgeIngestor::stableId)
                .toList();

        Response<List<Embedding>> response = embeddingModel.embedAll(segments);
        List<Embedding> embeddings = response == null ? null : response.content();
        if (embeddings == null || embeddings.size() != chunks.size()) {
            int actualSize = embeddings == null ? -1 : embeddings.size();
            throw new IllegalStateException("Vue 知识块与 embedding 数量不一致: chunks="
                    + chunks.size() + ", embeddings=" + actualSize);
        }
        store.addAll(ids, embeddings, segments);

        long elapsed = System.currentTimeMillis() - start;
        log.info("[RAG Ingest] Vue 目录版本={},块数={},耗时={}ms",
                catalog.getCatalogVersion(), chunks.size(), elapsed);
        return new IngestResult(catalog.getCatalogVersion(), chunks.size());
    }

    private TextSegment createSegment(KnowledgeChunk chunk, String catalogVersion) {
        Metadata metadata = Metadata.from(Map.of(
                "chunkId", chunk.chunkId(),
                "documentId", chunk.documentId(),
                "documentKind", chunk.documentKind().name(),
                "chunkKind", chunk.chunkKind().name(),
                "catalogVersion", catalogVersion
        ));
        return TextSegment.from(chunk.searchText(), metadata);
    }

    private static String stableId(String chunkId) {
        return UUID.nameUUIDFromBytes(chunkId.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * 单次 Vue 目录摄取结果。
     */
    public record IngestResult(String catalogVersion, int chunkCount) {
    }
}
