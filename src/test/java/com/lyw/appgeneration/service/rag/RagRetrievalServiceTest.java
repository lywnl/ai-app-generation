package com.lyw.appgeneration.service.rag;

import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.service.rag.catalog.NativeTemplateCatalog;
import com.lyw.appgeneration.service.rag.model.RetrievedSnippet;
import com.lyw.appgeneration.service.rag.model.RagDocumentKind;
import com.lyw.appgeneration.service.rag.model.TemplateDoc;
import com.lyw.appgeneration.service.rag.model.VueRagContext;
import com.lyw.appgeneration.service.rag.retrieval.NativeTemplateCatalogProvider;
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
import org.mockito.ArgumentCaptor;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RagRetrievalServiceTest {

    @Test
    void filtersCurrentVersionAndResolvesFullDocumentsFromLocalCatalog() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(any(String.class)))
                .thenReturn(Response.from(Embedding.from(new float[]{1.0f, 2.0f})));
        EmbeddingStore<TextSegment> htmlStore = storeWith(
                "html-1", "catalog-html", RagDocumentKind.PAGE_SECTION);
        Map<CodeGenTypeEnum, EmbeddingStore<TextSegment>> stores =
                new EnumMap<>(CodeGenTypeEnum.class);
        stores.put(CodeGenTypeEnum.HTML, htmlStore);
        RagProperties properties = new RagProperties();
        properties.setEnabled(true);
        properties.getRetrieval().setTopK(3);
        properties.getRerank().setEnabled(true);
        RagRerankService rerankService = mock(RagRerankService.class);
        VueHybridRetrievalService vueService = mock(VueHybridRetrievalService.class);
        TemplateDoc document = document("html-1", RagDocumentKind.PAGE_SECTION, "<main>本地源码</main>");
        NativeTemplateCatalog htmlCatalog = mock(NativeTemplateCatalog.class);
        when(htmlCatalog.getCatalogVersion()).thenReturn("catalog-html");
        when(htmlCatalog.findDocumentById("html-1")).thenReturn(java.util.Optional.of(document));
        NativeTemplateCatalogProvider catalogProvider = mock(NativeTemplateCatalogProvider.class);
        when(catalogProvider.current(CodeGenTypeEnum.HTML))
                .thenReturn(java.util.Optional.of(htmlCatalog));
        RagRetrievalService service = new RagRetrievalService(
                embeddingModel, stores, properties, rerankService, vueService, catalogProvider);

        List<RetrievedSnippet> html = service.retrieve("官网", CodeGenTypeEnum.HTML);

        assertEquals(List.of("html-1"), html.stream().map(RetrievedSnippet::getId).toList());
        assertEquals(document, html.getFirst().getDocument());
        ArgumentCaptor<EmbeddingSearchRequest> request =
                ArgumentCaptor.forClass(EmbeddingSearchRequest.class);
        verify(htmlStore).search(request.capture());
        assertTrue(request.getValue().filter() != null, "原生模板检索必须携带目录版本过滤器");
        verify(rerankService, never()).rerank(any(), any(), any(Integer.class));
        verify(vueService, never()).retrieve(any());
    }

    @Test
    void skipsCandidatesWithInvalidMetadataOrMissingLocalDocument() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(any(String.class)))
                .thenReturn(Response.from(Embedding.from(new float[]{1.0f})));
        EmbeddingStore<TextSegment> store = store();
        TextSegment wrongVersion = segment("html-old", "old-version", "PAGE_SECTION");
        TextSegment missingDocument = segment("html-missing", "catalog-html", "PAGE_SECTION");
        when(store.search(any(EmbeddingSearchRequest.class))).thenReturn(
                new EmbeddingSearchResult<>(List.of(
                        match("old", wrongVersion),
                        match("missing", missingDocument))));
        NativeTemplateCatalog catalog = mock(NativeTemplateCatalog.class);
        when(catalog.getCatalogVersion()).thenReturn("catalog-html");
        NativeTemplateCatalogProvider provider = mock(NativeTemplateCatalogProvider.class);
        when(provider.current(CodeGenTypeEnum.HTML)).thenReturn(java.util.Optional.of(catalog));
        RagRetrievalService service = new RagRetrievalService(
                embeddingModel, Map.of(CodeGenTypeEnum.HTML, store), new RagProperties(),
                mock(RagRerankService.class), mock(VueHybridRetrievalService.class), provider);

        assertTrue(service.retrieve("官网", CodeGenTypeEnum.HTML).isEmpty());
    }

    @Test
    void unavailableCatalogDegradesOnlyThatNativeType() {
        NativeTemplateCatalogProvider provider = mock(NativeTemplateCatalogProvider.class);
        when(provider.current(CodeGenTypeEnum.HTML)).thenReturn(java.util.Optional.empty());
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        RagRetrievalService service = new RagRetrievalService(
                embeddingModel, Map.of(CodeGenTypeEnum.HTML, store()), new RagProperties(),
                mock(RagRerankService.class), mock(VueHybridRetrievalService.class), provider);

        assertTrue(service.retrieve("官网", CodeGenTypeEnum.HTML).isEmpty());
        verifyNoInteractions(embeddingModel);
    }

    @Test
    void delegatesRawQueryToVueHybridRetrieval() {
        VueHybridRetrievalService vueService = mock(VueHybridRetrievalService.class);
        TemplateDoc skeleton = new TemplateDoc();
        skeleton.setId("vue-skeleton");
        VueRagContext expected = new VueRagContext(skeleton, List.of(), "catalog", false);
        when(vueService.retrieve("原始 Vue 需求")).thenReturn(expected);
        RagProperties properties = new RagProperties();
        properties.setEnabled(true);
        RagRetrievalService service = new RagRetrievalService(
                mock(EmbeddingModel.class), Map.of(), properties,
                mock(RagRerankService.class), vueService, mock(NativeTemplateCatalogProvider.class));

        VueRagContext actual = service.retrieveVueProject("原始 Vue 需求");

        assertEquals(expected, actual);
        verify(vueService).retrieve("原始 Vue 需求");
    }

    @Test
    void disabledRagBlocksHybridAndProductionDenseOnlyDelegation() {
        VueHybridRetrievalService vueService = mock(VueHybridRetrievalService.class);
        RagProperties properties = new RagProperties();
        properties.setEnabled(false);
        RagRetrievalService service = new RagRetrievalService(
                mock(EmbeddingModel.class), Map.of(), properties,
                mock(RagRerankService.class), vueService, mock(NativeTemplateCatalogProvider.class));

        VueRagContext hybrid = service.retrieveVueProject("Hybrid 需求");
        VueRagContext denseOnly = service.retrieveVueProjectDenseOnly("Dense 需求");

        assertEquals(VueRagContext.unavailable(), hybrid);
        assertEquals(VueRagContext.unavailable(), denseOnly);
        verifyNoInteractions(vueService);
    }

    @Test
    void delegatesRawQueryToVueProductionDenseOnlyWhenRagIsEnabled() {
        VueHybridRetrievalService vueService = mock(VueHybridRetrievalService.class);
        when(vueService.retrieveDenseOnly("生产 Dense 需求"))
                .thenReturn(VueRagContext.unavailable());
        RagProperties properties = new RagProperties();
        properties.setEnabled(true);
        RagRetrievalService service = new RagRetrievalService(
                mock(EmbeddingModel.class), Map.of(), properties,
                mock(RagRerankService.class), vueService, mock(NativeTemplateCatalogProvider.class));

        VueRagContext actual = service.retrieveVueProjectDenseOnly("生产 Dense 需求");

        assertEquals(VueRagContext.unavailable(), actual);
        verify(vueService).retrieveDenseOnly("生产 Dense 需求");
        verify(vueService, never()).retrieve(any());
        verify(vueService, never()).retrieveDenseOnlyForEvaluation(any());
    }

    @Test
    void delegatesDenseOnlyEvaluationWithoutChangingProductionEntry() {
        VueHybridRetrievalService vueService = mock(VueHybridRetrievalService.class);
        VueRagContext expected = VueRagContext.unavailable();
        when(vueService.retrieveDenseOnlyForEvaluation("基线需求")).thenReturn(expected);
        RagRetrievalService service = new RagRetrievalService(
                mock(EmbeddingModel.class), Map.of(), new RagProperties(),
                mock(RagRerankService.class), vueService, mock(NativeTemplateCatalogProvider.class));

        VueRagContext actual = service.retrieveVueProjectDenseOnlyForEvaluation("基线需求");

        assertEquals(expected, actual);
        verify(vueService).retrieveDenseOnlyForEvaluation("基线需求");
        verify(vueService, never()).retrieve(any());
    }

    @SuppressWarnings("unchecked")
    private EmbeddingStore<TextSegment> storeWith(
            String id, String catalogVersion, RagDocumentKind documentKind) {
        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        TextSegment segment = segment(id, catalogVersion, documentKind.name());
        EmbeddingMatch<TextSegment> match = new EmbeddingMatch<>(
                0.9, id, Embedding.from(new float[]{1.0f}), segment);
        when(store.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(List.of(match)));
        return store;
    }

    private TextSegment segment(String id, String catalogVersion, String documentKind) {
        return TextSegment.from("检索文本", Metadata.from(Map.of(
                "documentId", id,
                "documentKind", documentKind,
                "catalogVersion", catalogVersion,
                "title", "标题",
                "category", "test")));
    }

    private EmbeddingMatch<TextSegment> match(String id, TextSegment segment) {
        return new EmbeddingMatch<>(0.9, id,
                Embedding.from(new float[]{1.0f}), segment);
    }

    private TemplateDoc document(String id, RagDocumentKind kind, String content) {
        TemplateDoc document = new TemplateDoc();
        document.setId(id);
        document.setDocumentKind(kind);
        document.setTitle("标题");
        TemplateDoc.TemplateFile file = new TemplateDoc.TemplateFile();
        file.setPath("index.html");
        file.setContent(content);
        document.setFiles(List.of(file));
        return document;
    }

    @SuppressWarnings("unchecked")
    private EmbeddingStore<TextSegment> store() {
        return mock(EmbeddingStore.class);
    }
}
