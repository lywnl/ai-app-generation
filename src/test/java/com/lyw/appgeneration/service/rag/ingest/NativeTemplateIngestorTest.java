package com.lyw.appgeneration.service.rag.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.service.rag.catalog.NativeTemplateCatalog;
import com.lyw.appgeneration.service.rag.model.RagDocumentKind;
import com.lyw.appgeneration.service.rag.model.TemplateDoc;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsNotEqualTo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NativeTemplateIngestorTest {

    @Test
    void batchUpsertsStableIdsAndRemovesHistoricalVersionsAfterWrite() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        Embedding firstEmbedding = Embedding.from(new float[]{1.0f, 2.0f});
        Embedding secondEmbedding = Embedding.from(new float[]{3.0f, 4.0f});
        when(embeddingModel.embedAll(any())).thenReturn(
                Response.from(List.of(firstEmbedding, secondEmbedding)));
        NativeTemplateCatalog catalog = catalog("catalog-v2",
                document("html-first", "第一模板"),
                document("html-second", "第二模板"));
        EmbeddingStore<TextSegment> store = store();

        NativeTemplateIngestor.IngestResult result =
                new NativeTemplateIngestor(embeddingModel, new ObjectMapper()).ingest(catalog, store);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> ids = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Embedding>> embeddings = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TextSegment>> segments = ArgumentCaptor.forClass(List.class);
        var order = inOrder(store);
        order.verify(store).addAll(ids.capture(), embeddings.capture(), segments.capture());
        ArgumentCaptor<Filter> cleanupFilters = ArgumentCaptor.forClass(Filter.class);
        order.verify(store).removeAll(cleanupFilters.capture());
        assertEquals(List.of(stableId("html-first"), stableId("html-second")), ids.getValue());
        assertEquals(List.of(firstEmbedding, secondEmbedding), embeddings.getValue());
        assertEquals("第一模板检索", segments.getValue().getFirst().text());
        assertEquals(Map.of(
                        "documentId", "html-first",
                        "documentKind", "PAGE_SECTION",
                        "catalogVersion", "catalog-v2",
                        "title", "第一模板",
                        "category", "landing"),
                segments.getValue().getFirst().metadata().toMap());
        IsNotEqualTo versionCleanup = (IsNotEqualTo) cleanupFilters.getValue();
        assertEquals("catalogVersion", versionCleanup.key());
        assertEquals(new NativeTemplateIngestor.IngestResult("catalog-v2", 2), result);
    }

    @Test
    void refusesWriteWhenEmbeddingCountDoesNotMatchDocuments() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embedAll(any())).thenReturn(
                Response.from(List.of(Embedding.from(new float[]{1.0f}))));
        NativeTemplateCatalog catalog = catalog("catalog-v1",
                document("html-first", "第一模板"),
                document("html-second", "第二模板"));
        EmbeddingStore<TextSegment> store = store();

        assertThrows(IllegalStateException.class,
                () -> new NativeTemplateIngestor(
                        embeddingModel, new ObjectMapper()).ingest(catalog, store));

        verify(store, never()).addAll(any(), any(), any());
        verify(store, never()).removeAll(any(Filter.class));
    }

    private NativeTemplateCatalog catalog(String version, TemplateDoc... documents) {
        NativeTemplateCatalog catalog = mock(NativeTemplateCatalog.class);
        when(catalog.getCodeGenType()).thenReturn(CodeGenTypeEnum.HTML);
        when(catalog.getCatalogVersion()).thenReturn(version);
        when(catalog.getDocuments()).thenReturn(List.of(documents));
        return catalog;
    }

    private TemplateDoc document(String id, String title) {
        TemplateDoc document = new TemplateDoc();
        document.setId(id);
        document.setDocumentKind(RagDocumentKind.PAGE_SECTION);
        document.setTitle(title);
        document.setCategory("landing");
        document.setEmbedText(title + "检索");
        return document;
    }

    private String stableId(String documentId) {
        return UUID.nameUUIDFromBytes(documentId.getBytes(StandardCharsets.UTF_8)).toString();
    }

    @SuppressWarnings("unchecked")
    private EmbeddingStore<TextSegment> store() {
        return mock(EmbeddingStore.class);
    }
}
