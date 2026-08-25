package com.lyw.appgeneration.service.rag.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.service.rag.catalog.NativeTemplateCatalog;
import com.lyw.appgeneration.service.rag.model.TemplateDoc;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * 将受校验的 HTML 或原生三文件模板目录幂等摄取到 Milvus。
 */
@Component
public class NativeTemplateIngestor {

    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;

    public NativeTemplateIngestor(EmbeddingModel embeddingModel, ObjectMapper objectMapper) {
        this.embeddingModel = embeddingModel;
        this.objectMapper = objectMapper;
    }

    public IngestResult ingest(
            Path templateRoot,
            CodeGenTypeEnum type,
            EmbeddingStore<TextSegment> store) {
        return ingest(new NativeTemplateCatalog(templateRoot, type, objectMapper), store);
    }

    public IngestResult ingest(
            NativeTemplateCatalog catalog,
            EmbeddingStore<TextSegment> store) {
        List<TemplateDoc> documents = catalog.getDocuments();
        List<TextSegment> segments = documents.stream()
                .map(document -> createSegment(document, catalog.getCatalogVersion()))
                .toList();
        Response<List<Embedding>> response = embeddingModel.embedAll(segments);
        List<Embedding> embeddings = response == null ? null : response.content();
        if (embeddings == null || embeddings.size() != documents.size()) {
            int actualCount = embeddings == null ? -1 : embeddings.size();
            throw new IllegalStateException("原生模板与 embedding 数量不一致: templates=%d, embeddings=%d"
                    .formatted(documents.size(), actualCount));
        }

        List<String> stableIds = documents.stream()
                .map(TemplateDoc::getId)
                .map(NativeTemplateIngestor::stableId)
                .toList();
        store.addAll(stableIds, embeddings, segments);
        store.removeAll(metadataKey("catalogVersion")
                .isNotEqualTo(catalog.getCatalogVersion()));
        return new IngestResult(catalog.getCatalogVersion(), documents.size());
    }

    private TextSegment createSegment(TemplateDoc document, String catalogVersion) {
        Metadata metadata = Metadata.from(Map.of(
                "documentId", document.getId(),
                "documentKind", document.getDocumentKind().name(),
                "catalogVersion", catalogVersion,
                "title", document.getTitle(),
                "category", document.getCategory()
        ));
        return TextSegment.from(document.getEmbedText(), metadata);
    }

    static String stableId(String documentId) {
        return UUID.nameUUIDFromBytes(documentId.getBytes(StandardCharsets.UTF_8)).toString();
    }

    public record IngestResult(String catalogVersion, int documentCount) {
    }
}
