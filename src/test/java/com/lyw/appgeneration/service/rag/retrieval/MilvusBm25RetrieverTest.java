package com.lyw.appgeneration.service.rag.retrieval;

import com.google.gson.JsonObject;
import com.lyw.appgeneration.service.rag.model.RagChunkKind;
import com.lyw.appgeneration.service.rag.model.RagDocumentKind;
import com.lyw.appgeneration.service.rag.model.RankedCandidate;
import com.lyw.appgeneration.service.rag.store.MilvusBm25SearchClient;
import io.milvus.v2.service.vector.response.SearchResp;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MilvusBm25RetrieverTest {

    @Test
    void 同一父文档多个chunk只保留最高分并稳定排序() {
        MilvusBm25SearchClient searchClient = mock(MilvusBm25SearchClient.class);
        when(searchClient.search("pinia", "catalog-v5", RagDocumentKind.FEATURE_SNIPPET, 10))
                .thenReturn(response(
                        result(0.4F, "chunk-a1", "doc-a", RagDocumentKind.FEATURE_SNIPPET),
                        result(0.9F, "chunk-a2", "doc-a", RagDocumentKind.FEATURE_SNIPPET),
                        result(0.9F, "chunk-b1", "doc-b", RagDocumentKind.FEATURE_SNIPPET),
                        result(0.8F, "chunk-old", "doc-old", RagDocumentKind.FEATURE_SNIPPET)));
        MilvusBm25Retriever retriever = new MilvusBm25Retriever(searchClient);

        List<RankedCandidate> candidates = retriever.retrieve(
                "pinia", "catalog-v5", RagDocumentKind.FEATURE_SNIPPET, 10);

        assertEquals(List.of(
                new RankedCandidate("doc-a", RagDocumentKind.FEATURE_SNIPPET, 1, 0.9F),
                new RankedCandidate("doc-b", RagDocumentKind.FEATURE_SNIPPET, 2, 0.9F),
                new RankedCandidate("doc-old", RagDocumentKind.FEATURE_SNIPPET, 3, 0.8F)), candidates);
    }

    @Test
    void 空查询或非法参数返回空结果且不访问Milvus() {
        MilvusBm25SearchClient searchClient = mock(MilvusBm25SearchClient.class);
        MilvusBm25Retriever retriever = new MilvusBm25Retriever(searchClient);

        assertEquals(List.of(), retriever.retrieve(" ", "catalog-v5", RagDocumentKind.FEATURE_SNIPPET, 10));
        assertEquals(List.of(), retriever.retrieve("query", "", RagDocumentKind.FEATURE_SNIPPET, 10));
        assertEquals(List.of(), retriever.retrieve("query", "catalog-v5", null, 10));
        assertEquals(List.of(), retriever.retrieve("query", "catalog-v5", RagDocumentKind.FEATURE_SNIPPET, 0));
        verify(searchClient, never()).search(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void 返回metadata非法或文档类型版本不匹配时跳过() {
        MilvusBm25SearchClient searchClient = mock(MilvusBm25SearchClient.class);
        when(searchClient.search("query", "catalog-v5", RagDocumentKind.FEATURE_SNIPPET, 10))
                .thenReturn(response(
                        result(0.9F, "missing-kind", "doc-a", null),
                        result(0.8F, "wrong-version", "doc-b", RagDocumentKind.FEATURE_SNIPPET,
                                "catalog-old"),
                        result(0.7F, "valid", "doc-c", RagDocumentKind.FEATURE_SNIPPET)));
        MilvusBm25Retriever retriever = new MilvusBm25Retriever(searchClient);

        assertEquals(List.of(new RankedCandidate("doc-c", RagDocumentKind.FEATURE_SNIPPET, 1, 0.7F)),
                retriever.retrieve("query", "catalog-v5", RagDocumentKind.FEATURE_SNIPPET, 10));
    }

    @Test
    void JSON元数据中的数字布尔和对象值必须拒绝() {
        MilvusBm25SearchClient searchClient = mock(MilvusBm25SearchClient.class);
        JsonObject numericMetadata = validJsonMetadata();
        numericMetadata.addProperty("documentId", 42);
        JsonObject booleanMetadata = validJsonMetadata();
        booleanMetadata.addProperty("documentId", true);
        JsonObject objectMetadata = validJsonMetadata();
        JsonObject nested = new JsonObject();
        nested.addProperty("value", "doc-a");
        objectMetadata.add("documentId", nested);
        when(searchClient.search("query", "catalog-v5", RagDocumentKind.FEATURE_SNIPPET, 10))
                .thenReturn(response(
                        jsonResult(0.9F, numericMetadata),
                        jsonResult(0.8F, booleanMetadata),
                        jsonResult(0.7F, objectMetadata)));
        MilvusBm25Retriever retriever = new MilvusBm25Retriever(searchClient);

        assertEquals(List.of(), retriever.retrieve(
                "query", "catalog-v5", RagDocumentKind.FEATURE_SNIPPET, 10));
    }

    private SearchResp response(SearchResp.SearchResult... results) {
        return SearchResp.builder().searchResults(List.of(List.of(results))).build();
    }

    private SearchResp.SearchResult result(float score,
                                           String chunkId,
                                           String documentId,
                                           RagDocumentKind documentKind) {
        return result(score, chunkId, documentId, documentKind, "catalog-v5");
    }

    private SearchResp.SearchResult result(float score,
                                           String chunkId,
                                           String documentId,
                                           RagDocumentKind documentKind,
                                           String catalogVersion) {
        Map<String, Object> metadata = new java.util.HashMap<>();
        metadata.put("chunkId", chunkId);
        metadata.put("documentId", documentId);
        metadata.put("documentKind", documentKind == null ? "" : documentKind.name());
        metadata.put("chunkKind", RagChunkKind.OVERVIEW.name());
        metadata.put("catalogVersion", catalogVersion);
        return SearchResp.SearchResult.builder()
                .id(chunkId)
                .score(score)
                .entity(Map.of("metadata", metadata))
                .build();
    }

    private SearchResp.SearchResult jsonResult(float score, JsonObject metadata) {
        return SearchResp.SearchResult.builder()
                .id("chunk-json")
                .score(score)
                .entity(Map.of("metadata", metadata))
                .build();
    }

    private JsonObject validJsonMetadata() {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("chunkId", "chunk-json");
        metadata.addProperty("documentId", "doc-json");
        metadata.addProperty("documentKind", RagDocumentKind.FEATURE_SNIPPET.name());
        metadata.addProperty("chunkKind", RagChunkKind.OVERVIEW.name());
        metadata.addProperty("catalogVersion", "catalog-v5");
        return metadata;
    }
}
