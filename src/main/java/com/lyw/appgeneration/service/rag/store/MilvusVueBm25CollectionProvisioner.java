package com.lyw.appgeneration.service.rag.store;

import com.lyw.appgeneration.constants.RagConstants;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 创建并复用 Vue 的 Milvus 原生 BM25 Collection。
 */
@Component
public class MilvusVueBm25CollectionProvisioner {

    public void ensureCollection(MilvusClientV2 client, int dimension) {
        Objects.requireNonNull(client, "Milvus V2 客户端不能为空");
        if (dimension <= 0) {
            throw new IllegalArgumentException("Embedding 维度必须为正数");
        }
        HasCollectionReq hasCollectionReq = HasCollectionReq.builder()
                .collectionName(RagConstants.VUE_BM25_COLLECTION)
                .build();
        if (Boolean.TRUE.equals(client.hasCollection(hasCollectionReq))) {
            return;
        }
        try {
            client.createCollection(createRequest(dimension));
        } catch (RuntimeException exception) {
            if (!Boolean.TRUE.equals(client.hasCollection(hasCollectionReq))) {
                throw new IllegalStateException("Milvus Collection 创建失败: "
                        + RagConstants.VUE_BM25_COLLECTION, null);
            }
        }
    }

    CreateCollectionReq createRequest(int dimension) {
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .fieldSchemaList(List.of(
                        idField(),
                        textField(),
                        metadataField(),
                        vectorField(dimension),
                        sparseVectorField()))
                .enableDynamicField(false)
                .functionList(List.of(bm25Function()))
                .build();
        return CreateCollectionReq.builder()
                .collectionName(RagConstants.VUE_BM25_COLLECTION)
                .description("Vue 模板稠密向量与 Milvus 原生 BM25 检索集合")
                .collectionSchema(schema)
                .indexParams(List.of(denseIndex(), bm25Index()))
                .consistencyLevel(ConsistencyLevel.STRONG)
                .build();
    }

    private CreateCollectionReq.FieldSchema idField() {
        return CreateCollectionReq.FieldSchema.builder()
                .name("id")
                .dataType(DataType.VarChar)
                .maxLength(36)
                .isPrimaryKey(true)
                .autoID(false)
                .build();
    }

    private CreateCollectionReq.FieldSchema textField() {
        return CreateCollectionReq.FieldSchema.builder()
                .name("text")
                .dataType(DataType.VarChar)
                .maxLength(65535)
                .enableAnalyzer(true)
                .build();
    }

    private CreateCollectionReq.FieldSchema metadataField() {
        return CreateCollectionReq.FieldSchema.builder()
                .name("metadata")
                .dataType(DataType.JSON)
                .build();
    }

    private CreateCollectionReq.FieldSchema vectorField(int dimension) {
        return CreateCollectionReq.FieldSchema.builder()
                .name("vector")
                .dataType(DataType.FloatVector)
                .dimension(dimension)
                .build();
    }

    private CreateCollectionReq.FieldSchema sparseVectorField() {
        return CreateCollectionReq.FieldSchema.builder()
                .name("bm25_sparse_vector")
                .dataType(DataType.SparseFloatVector)
                .build();
    }

    private CreateCollectionReq.Function bm25Function() {
        return CreateCollectionReq.Function.builder()
                .name("text_bm25_emb")
                .functionType(FunctionType.BM25)
                .inputFieldNames(List.of("text"))
                .outputFieldNames(List.of("bm25_sparse_vector"))
                .build();
    }

    private IndexParam denseIndex() {
        return IndexParam.builder()
                .fieldName("vector")
                .indexName("vector_idx")
                .indexType(IndexParam.IndexType.FLAT)
                .metricType(IndexParam.MetricType.COSINE)
                .build();
    }

    private IndexParam bm25Index() {
        return IndexParam.builder()
                .fieldName("bm25_sparse_vector")
                .indexName("bm25_sparse_vector_idx")
                .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                .metricType(IndexParam.MetricType.BM25)
                .extraParams(Map.of("drop_ratio_build", 0.2))
                .build();
    }
}
