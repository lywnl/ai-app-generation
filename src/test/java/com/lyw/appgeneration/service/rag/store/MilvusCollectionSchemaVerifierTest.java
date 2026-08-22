package com.lyw.appgeneration.service.rag.store;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.grpc.DescribeIndexResponse;
import io.milvus.grpc.FieldSchema;
import io.milvus.grpc.IndexDescription;
import io.milvus.grpc.KeyValuePair;
import io.milvus.grpc.CollectionSchema;
import io.milvus.param.R;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MilvusCollectionSchemaVerifierTest {

    private MilvusServiceClient client;
    private MilvusCollectionSchemaVerifier verifier;

    @BeforeEach
    void setUp() {
        client = mock(MilvusServiceClient.class);
        verifier = new MilvusCollectionSchemaVerifier();
        when(client.describeCollection(any())).thenReturn(R.success(validCollection()));
        when(client.describeIndex(any())).thenReturn(R.success(validIndex()));
    }

    @Test
    void 四字段维度索引和度量全部匹配时通过() {
        assertDoesNotThrow(() -> verifier.verify(client, "templates_vue", 1024));
    }

    @Test
    void 描述Collection失败时拒绝启动() {
        when(client.describeCollection(any())).thenReturn(R.failed(
                R.Status.UnexpectedError, "敏感下游错误"));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> verifier.verify(client, "templates_vue", 1024));

        assertTrue(exception.getMessage().contains("templates_vue"));
        assertTrue(exception.getMessage().contains("Collection"));
        assertTrue(!exception.getMessage().contains("敏感下游错误"));
    }

    @Test
    void 描述Collection返回空时拒绝启动() {
        when(client.describeCollection(any())).thenReturn(null);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> verifier.verify(client, "templates_vue", 1024));

        assertTrue(exception.getMessage().contains("templates_vue"));
        assertTrue(exception.getMessage().contains("Collection"));
    }

    @Test
    void 缺少字段时指出字段名() {
        when(client.describeCollection(any())).thenReturn(R.success(collection(List.of(
                idField(), textField(), vectorField(1024)))));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> verifier.verify(client, "templates_vue", 1024));

        assertTrue(exception.getMessage().contains("metadata"));
    }

    @Test
    void 向量维度错误时拒绝复用Collection() {
        when(client.describeCollection(any())).thenReturn(R.success(collection(List.of(
                idField(), textField(), metadataField(), vectorField(768)))));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> verifier.verify(client, "templates_vue", 1024));

        assertTrue(exception.getMessage().contains("vector"));
        assertTrue(exception.getMessage().contains("1024"));
    }

    @Test
    void 主键协议错误时拒绝复用Collection() {
        FieldSchema invalidId = idField().toBuilder().setAutoID(true).build();
        when(client.describeCollection(any())).thenReturn(R.success(collection(List.of(
                invalidId, textField(), metadataField(), vectorField(1024)))));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> verifier.verify(client, "templates_vue", 1024));

        assertTrue(exception.getMessage().contains("id"));
    }

    @Test
    void 文本长度协议错误时拒绝复用Collection() {
        FieldSchema invalidText = textField().toBuilder().clearTypeParams()
                .addTypeParams(pair("max_length", "1024"))
                .build();
        when(client.describeCollection(any())).thenReturn(R.success(collection(List.of(
                idField(), invalidText, metadataField(), vectorField(1024)))));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> verifier.verify(client, "templates_vue", 1024));

        assertTrue(exception.getMessage().contains("text"));
    }

    @Test
    void 元数据类型错误时拒绝复用Collection() {
        FieldSchema invalidMetadata = metadataField().toBuilder().setDataType(DataType.VarChar).build();
        when(client.describeCollection(any())).thenReturn(R.success(collection(List.of(
                idField(), textField(), invalidMetadata, vectorField(1024)))));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> verifier.verify(client, "templates_vue", 1024));

        assertTrue(exception.getMessage().contains("metadata"));
    }

    @Test
    void 额外字段或动态字段开启时拒绝复用Collection() {
        FieldSchema extraField = FieldSchema.newBuilder()
                .setName("legacy")
                .setDataType(DataType.VarChar)
                .addTypeParams(pair("max_length", "32"))
                .build();
        when(client.describeCollection(any())).thenReturn(R.success(collection(List.of(
                idField(), textField(), metadataField(), vectorField(1024), extraField))));

        IllegalStateException extraFieldException = assertThrows(IllegalStateException.class,
                () -> verifier.verify(client, "templates_vue", 1024));

        assertTrue(extraFieldException.getMessage().contains("字段集合"));

        when(client.describeCollection(any())).thenReturn(R.success(
                validCollection().toBuilder().setSchema(
                        validCollection().getSchema().toBuilder().setEnableDynamicField(true)).build()));

        IllegalStateException dynamicFieldException = assertThrows(IllegalStateException.class,
                () -> verifier.verify(client, "templates_vue", 1024));

        assertTrue(dynamicFieldException.getMessage().contains("动态字段"));
    }

    @Test
    void 索引类型错误时拒绝复用Collection() {
        when(client.describeIndex(any())).thenReturn(R.success(index("HNSW", "COSINE")));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> verifier.verify(client, "templates_vue", 1024));

        assertTrue(exception.getMessage().contains("FLAT"));
    }

    @Test
    void 度量类型错误时拒绝复用Collection() {
        when(client.describeIndex(any())).thenReturn(R.success(index("FLAT", "L2")));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> verifier.verify(client, "templates_vue", 1024));

        assertTrue(exception.getMessage().contains("COSINE"));
    }

    @Test
    void 描述索引返回空或失败时拒绝启动() {
        when(client.describeIndex(any())).thenReturn(null);

        IllegalStateException nullResponseException = assertThrows(IllegalStateException.class,
                () -> verifier.verify(client, "templates_vue", 1024));

        assertTrue(nullResponseException.getMessage().contains("index"));

        when(client.describeIndex(any())).thenReturn(R.failed(new IllegalStateException("索引查询失败")));

        IllegalStateException failedResponseException = assertThrows(IllegalStateException.class,
                () -> verifier.verify(client, "templates_vue", 1024));

        assertTrue(failedResponseException.getMessage().contains("index"));
    }

    private DescribeCollectionResponse validCollection() {
        return collection(List.of(idField(), textField(), metadataField(), vectorField(1024)));
    }

    private DescribeCollectionResponse collection(List<FieldSchema> fields) {
        return DescribeCollectionResponse.newBuilder()
                .setSchema(CollectionSchema.newBuilder()
                        .setName("templates_vue")
                        .addAllFields(fields))
                .build();
    }

    private FieldSchema idField() {
        return FieldSchema.newBuilder()
                .setName("id")
                .setDataType(DataType.VarChar)
                .setIsPrimaryKey(true)
                .setAutoID(false)
                .addTypeParams(pair("max_length", "36"))
                .build();
    }

    private FieldSchema textField() {
        return FieldSchema.newBuilder()
                .setName("text")
                .setDataType(DataType.VarChar)
                .addTypeParams(pair("max_length", "65535"))
                .build();
    }

    private FieldSchema metadataField() {
        return FieldSchema.newBuilder()
                .setName("metadata")
                .setDataType(DataType.JSON)
                .build();
    }

    private FieldSchema vectorField(int dimension) {
        return FieldSchema.newBuilder()
                .setName("vector")
                .setDataType(DataType.FloatVector)
                .addTypeParams(pair("dim", Integer.toString(dimension)))
                .build();
    }

    private DescribeIndexResponse validIndex() {
        return index("FLAT", "COSINE");
    }

    private DescribeIndexResponse index(String indexType, String metricType) {
        return DescribeIndexResponse.newBuilder()
                .addIndexDescriptions(IndexDescription.newBuilder()
                        .setFieldName("vector")
                        .setIndexName("vector_idx")
                        .addParams(pair("index_type", indexType))
                        .addParams(pair("metric_type", metricType)))
                .build();
    }

    private KeyValuePair pair(String key, String value) {
        return KeyValuePair.newBuilder().setKey(key).setValue(value).build();
    }
}
