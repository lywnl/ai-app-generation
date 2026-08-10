package com.lyw.appgeneration.service.rag;

import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.service.rag.model.RetrievedSnippet;
import com.lyw.appgeneration.service.rag.model.TemplateDoc;
import com.lyw.appgeneration.service.rag.model.VueRagContext;
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

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagRetrievalServiceTest {

    @Test
    void preservesHtmlAndMultiFileDenseRetrievalBehavior() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(any(String.class)))
                .thenReturn(Response.from(Embedding.from(new float[]{1.0f, 2.0f})));
        EmbeddingStore<TextSegment> htmlStore = storeWith("html-1", "HTML 标题", "<main>HTML</main>");
        EmbeddingStore<TextSegment> multiStore = storeWith("multi-1", "多文件标题", "多文件源码");
        Map<CodeGenTypeEnum, EmbeddingStore<TextSegment>> stores =
                new EnumMap<>(CodeGenTypeEnum.class);
        stores.put(CodeGenTypeEnum.HTML, htmlStore);
        stores.put(CodeGenTypeEnum.MULTI_FILE, multiStore);
        RagProperties properties = new RagProperties();
        properties.setEnabled(true);
        properties.getRetrieval().setTopK(3);
        properties.getRerank().setEnabled(true);
        RagRerankService rerankService = mock(RagRerankService.class);
        VueHybridRetrievalService vueService = mock(VueHybridRetrievalService.class);
        RagRetrievalService service = new RagRetrievalService(
                embeddingModel, stores, properties, rerankService, vueService);

        List<RetrievedSnippet> html = service.retrieve("官网", CodeGenTypeEnum.HTML);
        List<RetrievedSnippet> multi = service.retrieve("计时器", CodeGenTypeEnum.MULTI_FILE);

        assertEquals(List.of("html-1"), html.stream().map(RetrievedSnippet::getId).toList());
        assertEquals(List.of("multi-1"), multi.stream().map(RetrievedSnippet::getId).toList());
        assertEquals("<main>HTML</main>", html.getFirst().getCode());
        verify(rerankService, never()).rerank(any(), any(), any(Integer.class));
        verify(vueService, never()).retrieve(any());
    }

    @Test
    void delegatesRawQueryToVueHybridRetrieval() {
        VueHybridRetrievalService vueService = mock(VueHybridRetrievalService.class);
        TemplateDoc skeleton = new TemplateDoc();
        skeleton.setId("vue-skeleton");
        VueRagContext expected = new VueRagContext(skeleton, List.of(), "catalog", false);
        when(vueService.retrieve("原始 Vue 需求")).thenReturn(expected);
        RagRetrievalService service = new RagRetrievalService(
                mock(EmbeddingModel.class), Map.of(), new RagProperties(),
                mock(RagRerankService.class), vueService);

        VueRagContext actual = service.retrieveVueProject("原始 Vue 需求");

        assertEquals(expected, actual);
        verify(vueService).retrieve("原始 Vue 需求");
    }

    @SuppressWarnings("unchecked")
    private EmbeddingStore<TextSegment> storeWith(String id, String title, String code) {
        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        Metadata metadata = Metadata.from(Map.of(
                "id", id,
                "title", title,
                "category", "test",
                "code", code
        ));
        EmbeddingMatch<TextSegment> match = new EmbeddingMatch<>(
                0.9, id, Embedding.from(new float[]{1.0f}), TextSegment.from("检索文本", metadata));
        when(store.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(List.of(match)));
        return store;
    }
}
