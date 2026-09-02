package com.lyw.appgeneration.service.rag.store;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.R;
import io.milvus.param.collection.FlushParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.UpsertParam;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.utility.request.FlushReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.response.UpsertResp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 仅为显式标识写入提供 Milvus 原子覆盖语义，其余行为由官方存储实现负责。
 */
public final class MilvusEmbeddingStoreAdapter implements EmbeddingStore<TextSegment> {

    private static final String ID_FIELD = "id";
    private static final String TEXT_FIELD = "text";
    private static final String METADATA_FIELD = "metadata";
    private static final String VECTOR_FIELD = "vector";
    private static final Gson GSON = new Gson();

    private final MilvusServiceClient client;
    private final String collectionName;
    private final MilvusEmbeddingStore delegate;
    private final MilvusClientV2 v2Client;

    public MilvusEmbeddingStoreAdapter(
            MilvusServiceClient client,
            String collectionName,
            MilvusEmbeddingStore delegate) {
        this(client, collectionName, delegate, null);
    }

    /**
     * V2 客户端仅用于带 Milvus Function 的 Collection；传空时保留普通 Collection 的旧写入协议。
     */
    MilvusEmbeddingStoreAdapter(
            MilvusServiceClient client,
            String collectionName,
            MilvusEmbeddingStore delegate,
            MilvusClientV2 v2Client) {
        this.client = Objects.requireNonNull(client, "Milvus 客户端不能为空");
        this.collectionName = Objects.requireNonNull(collectionName, "Collection 名称不能为空");
        this.delegate = Objects.requireNonNull(delegate, "Milvus 存储不能为空");
        this.v2Client = v2Client;
    }

    @Override
    public String add(Embedding embedding) {
        return delegate.add(embedding);
    }

    @Override
    public void add(String id, Embedding embedding) {
        upsert(List.of(id), List.of(embedding), null);
    }

    @Override
    public String add(Embedding embedding, TextSegment embedded) {
        return delegate.add(embedding, embedded);
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        return delegate.addAll(embeddings);
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> embedded) {
        return delegate.addAll(embeddings, embedded);
    }

    @Override
    public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> embedded) {
        upsert(ids, embeddings, embedded);
    }

    @Override
    public void remove(String id) {
        delegate.remove(id);
    }

    @Override
    public void removeAll(Collection<String> ids) {
        delegate.removeAll(ids);
    }

    @Override
    public void removeAll(Filter filter) {
        delegate.removeAll(filter);
    }

    @Override
    public void removeAll() {
        delegate.removeAll();
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        return delegate.search(request);
    }

    private void upsert(List<String> ids, List<Embedding> embeddings, List<TextSegment> segments) {
        Objects.requireNonNull(ids, "标识列表不能为空");
        Objects.requireNonNull(embeddings, "向量列表不能为空");
        validateInput(ids, embeddings, segments);
        if (ids.isEmpty()) {
            return;
        }
        if (v2Client != null) {
            upsertV2(ids, embeddings, segments);
            return;
        }
        UpsertParam upsertParam = UpsertParam.newBuilder()
                .withCollectionName(collectionName)
                .withFields(buildFields(ids, embeddings, segments))
                .build();
        requireSuccess(invokeUpsert(upsertParam), "upsert");
        FlushParam flushParam = FlushParam.newBuilder()
                .withCollectionNames(List.of(collectionName))
                .build();
        requireSuccess(invokeFlush(flushParam), "flush");
    }

    private void upsertV2(List<String> ids, List<Embedding> embeddings, List<TextSegment> segments) {
        UpsertReq request = UpsertReq.builder()
                .collectionName(collectionName)
                .data(buildRows(ids, embeddings, segments))
                .build();
        UpsertResp response;
        try {
            response = v2Client.upsert(request);
        } catch (RuntimeException exception) {
            throw dependencyFailure("upsert");
        }
        if (response == null || response.getUpsertCnt() != ids.size()) {
            throw dependencyFailure("upsert");
        }
        try {
            v2Client.flush(FlushReq.builder()
                    .collectionNames(List.of(collectionName))
                    .build());
        } catch (RuntimeException exception) {
            throw dependencyFailure("flush");
        }
    }

    private List<JsonObject> buildRows(
            List<String> ids,
            List<Embedding> embeddings,
            List<TextSegment> segments) {
        List<JsonObject> rows = new ArrayList<>(ids.size());
        for (int index = 0; index < ids.size(); index++) {
            TextSegment segment = segments == null ? null : segments.get(index);
            JsonObject row = new JsonObject();
            row.addProperty(ID_FIELD, ids.get(index));
            row.addProperty(TEXT_FIELD, segment == null ? "" : segment.text());
            row.add(METADATA_FIELD, toMetadata(segment));
            row.add(VECTOR_FIELD, GSON.toJsonTree(embeddings.get(index).vectorAsList()));
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    private void validateInput(List<String> ids, List<Embedding> embeddings, List<TextSegment> segments) {
        if (ids.size() != embeddings.size() || (segments != null && ids.size() != segments.size())) {
            throw new IllegalArgumentException("显式标识、向量和文本片段数量必须一致");
        }
        if (ids.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("显式标识列表不能包含空元素");
        }
        if (embeddings.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("向量列表不能包含空元素");
        }
        if (segments != null && segments.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("文本片段列表不能包含空元素");
        }
    }

    private List<InsertParam.Field> buildFields(
            List<String> ids,
            List<Embedding> embeddings,
            List<TextSegment> segments) {
        List<String> texts = new ArrayList<>(ids.size());
        List<JsonObject> metadata = new ArrayList<>(ids.size());
        for (int index = 0; index < ids.size(); index++) {
            TextSegment segment = segments == null ? null : segments.get(index);
            texts.add(segment == null ? "" : segment.text());
            metadata.add(toMetadata(segment));
        }
        return List.of(
                new InsertParam.Field(ID_FIELD, ids),
                new InsertParam.Field(TEXT_FIELD, texts),
                new InsertParam.Field(METADATA_FIELD, metadata),
                new InsertParam.Field(VECTOR_FIELD, embeddings.stream().map(Embedding::vectorAsList).toList())
        );
    }

    private JsonObject toMetadata(TextSegment segment) {
        if (segment == null) {
            return new JsonObject();
        }
        return JsonParser.parseString(GSON.toJson(segment.metadata().toMap()))
                .getAsJsonObject();
    }

    private R<?> invokeUpsert(UpsertParam parameter) {
        try {
            return client.upsert(parameter);
        } catch (RuntimeException exception) {
            throw dependencyFailure("upsert");
        }
    }

    private R<?> invokeFlush(FlushParam parameter) {
        try {
            return client.flush(parameter);
        } catch (RuntimeException exception) {
            throw dependencyFailure("flush");
        }
    }

    private void requireSuccess(R<?> response, String operation) {
        if (response == null || response.getStatus() == null
                || response.getStatus() != R.Status.Success.getCode()) {
            throw dependencyFailure(operation);
        }
    }

    private IllegalStateException dependencyFailure(String operation) {
        return new IllegalStateException("Milvus " + operation + " 失败，Collection=" + collectionName);
    }
}
