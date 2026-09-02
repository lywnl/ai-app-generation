package com.lyw.appgeneration.service.rag.store;

import com.lyw.appgeneration.service.rag.model.RagDocumentKind;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.response.SearchResp;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MilvusBm25SearchClientTest {

    @Test
    void 使用原始查询构造MilvusBM25请求并带上服务端过滤() {
        MilvusClientV2 client = mock(MilvusClientV2.class);
        SearchResp response = SearchResp.builder().searchResults(List.of(List.of())).build();
        when(client.search(any(SearchReq.class))).thenReturn(response);
        MilvusBm25SearchClient searchClient = new MilvusBm25SearchClient(client);

        assertEquals(response, searchClient.search(
                "Vue + Pinia", "catalog-v5", RagDocumentKind.FEATURE_SNIPPET, 10));

        ArgumentCaptor<SearchReq> captor = ArgumentCaptor.forClass(SearchReq.class);
        verify(client).search(captor.capture());
        SearchReq request = captor.getValue();
        assertEquals("templates_vue_bm25", request.getCollectionName());
        assertEquals("bm25_sparse_vector", request.getAnnsField());
        assertEquals(IndexParam.MetricType.BM25, request.getMetricType());
        assertEquals(10, request.getTopK());
        assertEquals(List.of("id", "metadata"), request.getOutputFields());
        assertEquals("metadata[\"catalogVersion\"] == \"catalog-v5\" && "
                        + "metadata[\"documentKind\"] == \"FEATURE_SNIPPET\"",
                request.getFilter());
        EmbeddedText query = assertInstanceOf(EmbeddedText.class, request.getData().getFirst());
        assertEquals("Vue + Pinia", query.getData());
    }
}
