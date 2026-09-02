package com.lyw.appgeneration.service.rag.store;

import com.google.gson.JsonObject;
import com.lyw.appgeneration.constants.RagConstants;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.FlushResponse;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.collection.FlushParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.UpsertParam;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.utility.request.FlushReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.response.UpsertResp;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MilvusEmbeddingStoreAdapterTest {

    private static final String COLLECTION_NAME = RagConstants.VUE_BM25_COLLECTION;

    @Test
    void 非显式标识入口全部委托且不调用原生写入或刷新() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        MilvusEmbeddingStore delegate = mock(MilvusEmbeddingStore.class);
        MilvusEmbeddingStoreAdapter adapter = new MilvusEmbeddingStoreAdapter(client, COLLECTION_NAME, delegate);
        Embedding embedding = Embedding.from(new float[]{0.1F, 0.2F});
        TextSegment segment = TextSegment.from("片段", Metadata.from("类型", "功能"));
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder().queryEmbedding(embedding).build();
        Filter filter = mock(Filter.class);
        EmbeddingSearchResult<TextSegment> searchResult = new EmbeddingSearchResult<>(List.of());

        when(delegate.add(embedding)).thenReturn("随机标识");
        when(delegate.add(embedding, segment)).thenReturn("带文本随机标识");
        when(delegate.addAll(List.of(embedding))).thenReturn(List.of("批量随机标识"));
        when(delegate.addAll(List.of(embedding), List.of(segment))).thenReturn(List.of("带文本批量随机标识"));
        when(delegate.search(request)).thenReturn(searchResult);

        assertEquals("随机标识", adapter.add(embedding));
        assertEquals("带文本随机标识", adapter.add(embedding, segment));
        assertEquals(List.of("批量随机标识"), adapter.addAll(List.of(embedding)));
        assertEquals(List.of("带文本批量随机标识"), adapter.addAll(List.of(embedding), List.of(segment)));
        assertEquals(searchResult, adapter.search(request));
        adapter.remove("待删除标识");
        adapter.removeAll(List.of("批量删除标识"));
        adapter.removeAll(filter);
        adapter.removeAll();

        verify(delegate).add(embedding);
        verify(delegate).add(embedding, segment);
        verify(delegate).addAll(List.of(embedding));
        verify(delegate).addAll(List.of(embedding), List.of(segment));
        verify(delegate).search(request);
        verify(delegate).remove("待删除标识");
        verify(delegate).removeAll(List.of("批量删除标识"));
        verify(delegate).removeAll(filter);
        verify(delegate).removeAll();
        verify(client, never()).upsert(any(UpsertParam.class));
        verify(client, never()).flush(any(FlushParam.class));
    }

    @Test
    void 单个显式标识写入空文本和空元数据后刷新() {
        MilvusServiceClient client = mockSuccessfulClient();
        MilvusEmbeddingStoreAdapter adapter = new MilvusEmbeddingStoreAdapter(client, COLLECTION_NAME,
                mock(MilvusEmbeddingStore.class));
        Embedding embedding = Embedding.from(new float[]{0.1F, 0.2F});

        adapter.add("固定标识", embedding);

        ArgumentCaptor<UpsertParam> upsertCaptor = ArgumentCaptor.forClass(UpsertParam.class);
        ArgumentCaptor<FlushParam> flushCaptor = ArgumentCaptor.forClass(FlushParam.class);
        InOrder inOrder = inOrder(client);
        inOrder.verify(client).upsert(upsertCaptor.capture());
        inOrder.verify(client).flush(flushCaptor.capture());
        UpsertParam upsert = upsertCaptor.getValue();
        assertEquals(COLLECTION_NAME, upsert.getCollectionName());
        assertEquals(List.of("id", "text", "metadata", "vector"), fieldNames(upsert));
        assertEquals(List.of("固定标识"), upsert.getFields().get(0).getValues());
        assertEquals(List.of(""), upsert.getFields().get(1).getValues());
        assertEquals("{}", upsert.getFields().get(2).getValues().getFirst().toString());
        assertEquals(List.of(0.1F, 0.2F), upsert.getFields().get(3).getValues().getFirst());
        assertEquals(List.of(COLLECTION_NAME), flushCaptor.getValue().getCollectionNames());
    }

    @Test
    void BM25显式标识批量写入使用V2行协议且不提交稀疏输出字段() {
        MilvusServiceClient legacyClient = mock(MilvusServiceClient.class);
        MilvusClientV2 v2Client = mock(MilvusClientV2.class);
        when(v2Client.upsert(any(UpsertReq.class))).thenReturn(UpsertResp.builder().upsertCnt(2).build());
        MilvusEmbeddingStoreAdapter adapter = new MilvusEmbeddingStoreAdapter(
                legacyClient, COLLECTION_NAME, mock(MilvusEmbeddingStore.class), v2Client);
        List<TextSegment> segments = List.of(
                TextSegment.from("第一段", Metadata.from("类型", "页面")),
                TextSegment.from("第二段", Metadata.from("类型", "功能")));

        adapter.addAll(List.of("标识一", "标识二"), List.of(
                Embedding.from(new float[]{0.1F}), Embedding.from(new float[]{0.2F})), segments);

        ArgumentCaptor<UpsertReq> upsertCaptor = ArgumentCaptor.forClass(UpsertReq.class);
        verify(v2Client).upsert(upsertCaptor.capture());
        assertEquals(COLLECTION_NAME, upsertCaptor.getValue().getCollectionName());
        assertEquals(List.of("id", "text", "metadata", "vector"),
                upsertCaptor.getValue().getData().getFirst().keySet().stream().toList());
        assertEquals("第一段", upsertCaptor.getValue().getData().getFirst().get("text").getAsString());
        assertEquals("标识二", upsertCaptor.getValue().getData().get(1).get("id").getAsString());
        assertTrue(upsertCaptor.getValue().getData().stream()
                .noneMatch(row -> row.has("bm25_sparse_vector")));
        verify(v2Client).flush(any(FlushReq.class));
        verifyNoInteractions(legacyClient);
    }

    @Test
    void 显式标识批量写入保持输入顺序() {
        MilvusServiceClient client = mockSuccessfulClient();
        MilvusEmbeddingStoreAdapter adapter = new MilvusEmbeddingStoreAdapter(client, COLLECTION_NAME,
                mock(MilvusEmbeddingStore.class));
        List<String> ids = List.of("标识一", "标识二");
        List<Embedding> embeddings = List.of(
                Embedding.from(new float[]{0.1F, 0.2F}),
                Embedding.from(new float[]{0.3F, 0.4F})
        );
        List<TextSegment> segments = List.of(
                TextSegment.from("第一段", Metadata.from(Map.of("序号", 1, "类型", "页面"))),
                TextSegment.from("第二段", Metadata.from("类型", "功能"))
        );

        adapter.addAll(ids, embeddings, segments);

        ArgumentCaptor<UpsertParam> captor = ArgumentCaptor.forClass(UpsertParam.class);
        verify(client).upsert(captor.capture());
        UpsertParam upsert = captor.getValue();
        assertEquals(List.of("id", "text", "metadata", "vector"), fieldNames(upsert));
        assertEquals(ids, upsert.getFields().get(0).getValues());
        assertEquals(List.of("第一段", "第二段"), upsert.getFields().get(1).getValues());
        List<JsonObject> metadata = castJsonObjects(upsert.getFields().get(2).getValues());
        assertEquals(1, metadata.get(0).get("序号").getAsInt());
        assertEquals("页面", metadata.get(0).get("类型").getAsString());
        assertEquals("功能", metadata.get(1).get("类型").getAsString());
        assertEquals(List.of(0.1F, 0.2F), upsert.getFields().get(3).getValues().get(0));
        assertEquals(List.of(0.3F, 0.4F), upsert.getFields().get(3).getValues().get(1));
        verify(client).flush(any(FlushParam.class));
    }

    @Test
    void 空显式标识批次不调用客户端() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        MilvusEmbeddingStoreAdapter adapter = new MilvusEmbeddingStoreAdapter(client, COLLECTION_NAME,
                mock(MilvusEmbeddingStore.class));

        adapter.addAll(List.of(), List.of(), List.of());

        verifyNoInteractions(client);
    }

    @Test
    void 显式标识批次数量不一致时拒绝调用客户端() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        MilvusEmbeddingStoreAdapter adapter = new MilvusEmbeddingStoreAdapter(client, COLLECTION_NAME,
                mock(MilvusEmbeddingStore.class));

        assertThrows(IllegalArgumentException.class, () -> adapter.addAll(
                List.of("标识一"),
                List.of(Embedding.from(new float[]{0.1F}), Embedding.from(new float[]{0.2F})),
                List.of(TextSegment.from("片段"))));

        verifyNoInteractions(client);
    }

    @Test
    void 空标识列表但其他列表非空时拒绝调用客户端() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        MilvusEmbeddingStoreAdapter adapter = new MilvusEmbeddingStoreAdapter(client, COLLECTION_NAME,
                mock(MilvusEmbeddingStore.class));

        assertThrows(IllegalArgumentException.class, () -> adapter.addAll(
                List.of(),
                List.of(Embedding.from(new float[]{0.1F})),
                List.of(TextSegment.from("片段"))));

        verifyNoInteractions(client);
    }

    @Test
    void 显式标识列表包含空元素时拒绝调用客户端() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        MilvusEmbeddingStoreAdapter adapter = new MilvusEmbeddingStoreAdapter(client, COLLECTION_NAME,
                mock(MilvusEmbeddingStore.class));

        assertThrows(IllegalArgumentException.class, () -> adapter.addAll(
                java.util.Collections.singletonList(null),
                List.of(Embedding.from(new float[]{0.1F})),
                List.of(TextSegment.from("片段"))));

        verifyNoInteractions(client);
    }

    @Test
    void 向量列表包含空元素时拒绝调用客户端() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        MilvusEmbeddingStoreAdapter adapter = new MilvusEmbeddingStoreAdapter(client, COLLECTION_NAME,
                mock(MilvusEmbeddingStore.class));

        assertThrows(IllegalArgumentException.class, () -> adapter.addAll(
                List.of("标识一"),
                java.util.Collections.singletonList(null),
                List.of(TextSegment.from("片段"))));

        verifyNoInteractions(client);
    }

    @Test
    void 文本片段为空时写入空文本和空元数据() {
        MilvusServiceClient client = mockSuccessfulClient();
        MilvusEmbeddingStoreAdapter adapter = new MilvusEmbeddingStoreAdapter(client, COLLECTION_NAME,
                mock(MilvusEmbeddingStore.class));

        adapter.addAll(List.of("标识一", "标识二"), List.of(
                Embedding.from(new float[]{0.1F}), Embedding.from(new float[]{0.2F})), null);

        ArgumentCaptor<UpsertParam> captor = ArgumentCaptor.forClass(UpsertParam.class);
        verify(client).upsert(captor.capture());
        assertEquals(List.of("", ""), captor.getValue().getFields().get(1).getValues());
        assertEquals(List.of("{}", "{}"), captor.getValue().getFields().get(2).getValues().stream()
                .map(Object::toString)
                .toList());
    }

    @Test
    void 文本片段列表包含空元素时拒绝调用客户端() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        MilvusEmbeddingStoreAdapter adapter = new MilvusEmbeddingStoreAdapter(client, COLLECTION_NAME,
                mock(MilvusEmbeddingStore.class));

        assertThrows(IllegalArgumentException.class, () -> adapter.addAll(
                List.of("标识一"),
                List.of(Embedding.from(new float[]{0.1F})),
                java.util.Collections.singletonList(null)));

        verifyNoInteractions(client);
    }

    @Test
    void Upsert失败或异常时不刷新() {
        assertUpsertFailurePreventsFlush(null);
        assertUpsertFailurePreventsFlush(R.failed(new IllegalStateException("写入失败")));
        MilvusServiceClient exceptionalClient = mock(MilvusServiceClient.class);
        when(exceptionalClient.upsert(any(UpsertParam.class))).thenThrow(new IllegalStateException("写入异常"));
        MilvusEmbeddingStoreAdapter adapter = new MilvusEmbeddingStoreAdapter(exceptionalClient, COLLECTION_NAME,
                mock(MilvusEmbeddingStore.class));

        assertThrows(IllegalStateException.class, () -> adapter.add("固定标识", Embedding.from(new float[]{0.1F})));

        verify(exceptionalClient, never()).flush(any(FlushParam.class));
    }

    @Test
    void 刷新返回空失败或异常时抛出依赖异常() {
        assertFlushFailure(null);
        assertFlushFailure(R.failed(new IllegalStateException("刷新失败")));
        MilvusServiceClient exceptionalClient = mock(MilvusServiceClient.class);
        when(exceptionalClient.upsert(any(UpsertParam.class))).thenReturn(successfulUpsert());
        when(exceptionalClient.flush(any(FlushParam.class))).thenThrow(new IllegalStateException("刷新异常"));
        MilvusEmbeddingStoreAdapter adapter = new MilvusEmbeddingStoreAdapter(exceptionalClient, COLLECTION_NAME,
                mock(MilvusEmbeddingStore.class));

        assertThrows(IllegalStateException.class, () -> adapter.add("固定标识", Embedding.from(new float[]{0.1F})));
    }

    @Test
    void 依赖异常不得携带下游敏感Cause() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.upsert(any(UpsertParam.class)))
                .thenThrow(new IllegalStateException("敏感密码=不得出现在日志"));
        MilvusEmbeddingStoreAdapter adapter = new MilvusEmbeddingStoreAdapter(client, COLLECTION_NAME,
                mock(MilvusEmbeddingStore.class));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> adapter.add("固定标识", Embedding.from(new float[]{0.1F})));

        assertEquals("Milvus upsert 失败，Collection=templates_vue_bm25", exception.getMessage());
        assertNull(exception.getCause());
        assertTrue(!exception.toString().contains("敏感密码"));
    }

    private void assertUpsertFailurePreventsFlush(R<MutationResult> response) {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.upsert(any(UpsertParam.class))).thenReturn(response);
        MilvusEmbeddingStoreAdapter adapter = new MilvusEmbeddingStoreAdapter(client, COLLECTION_NAME,
                mock(MilvusEmbeddingStore.class));

        assertThrows(IllegalStateException.class, () -> adapter.add("固定标识", Embedding.from(new float[]{0.1F})));

        verify(client, never()).flush(any(FlushParam.class));
    }

    private void assertFlushFailure(R<FlushResponse> response) {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.upsert(any(UpsertParam.class))).thenReturn(successfulUpsert());
        when(client.flush(any(FlushParam.class))).thenReturn(response);
        MilvusEmbeddingStoreAdapter adapter = new MilvusEmbeddingStoreAdapter(client, COLLECTION_NAME,
                mock(MilvusEmbeddingStore.class));

        assertThrows(IllegalStateException.class, () -> adapter.add("固定标识", Embedding.from(new float[]{0.1F})));
    }

    private R<MutationResult> successfulUpsert() {
        return R.success(MutationResult.getDefaultInstance());
    }

    private MilvusServiceClient mockSuccessfulClient() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.upsert(any(UpsertParam.class))).thenReturn(successfulUpsert());
        when(client.flush(any(FlushParam.class))).thenReturn(R.success());
        return client;
    }

    private List<String> fieldNames(UpsertParam upsert) {
        return upsert.getFields().stream().map(InsertParam.Field::getName).toList();
    }

    @SuppressWarnings("unchecked")
    private List<JsonObject> castJsonObjects(List<?> values) {
        return (List<JsonObject>) values;
    }
}
