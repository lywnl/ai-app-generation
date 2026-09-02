package com.lyw.appgeneration.service.rag.store;

import com.lyw.appgeneration.constants.RagConstants;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.FieldSchema;
import io.milvus.grpc.FunctionSchema;
import io.milvus.grpc.IndexDescription;
import io.milvus.param.R;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.index.DescribeIndexParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 验证既有 Collection 是否符合应用固定的 Milvus 存储协议。
 */
@Component
public final class MilvusCollectionSchemaVerifier {

    private static final Set<String> DENSE_FIELD_NAMES = Set.of("id", "text", "metadata", "vector");
    private static final Set<String> BM25_FIELD_NAMES = Set.of(
            "id", "text", "metadata", "vector", "bm25_sparse_vector");

    public void verify(MilvusServiceClient client, String collectionName, int expectedDimension) {
        Objects.requireNonNull(client, "Milvus 客户端不能为空");
        Objects.requireNonNull(collectionName, "Collection 名称不能为空");
        R<?> collectionResponse = client.describeCollection(DescribeCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .build());
        requireSuccess(collectionResponse, collectionName, "Collection");
        var schema = ((io.milvus.grpc.DescribeCollectionResponse) collectionResponse.getData()).getSchema();
        if (schema.getEnableDynamicField()) {
            throw invalid(collectionName, "动态字段");
        }
        boolean bm25Collection = RagConstants.VUE_BM25_COLLECTION.equals(collectionName);
        Set<String> expectedFieldNames = bm25Collection ? BM25_FIELD_NAMES : DENSE_FIELD_NAMES;
        List<FieldSchema> fieldSchemas = schema.getFieldsList();
        Set<String> fieldNames = fieldSchemas.stream()
                .map(FieldSchema::getName)
                .collect(Collectors.toSet());
        expectedFieldNames.stream()
                .filter(expectedField -> !fieldNames.contains(expectedField))
                .findFirst()
                .ifPresent(missingField -> {
                    throw invalid(collectionName, missingField);
                });
        if (fieldSchemas.size() != expectedFieldNames.size()) {
            throw invalid(collectionName, "字段集合");
        }
        Map<String, FieldSchema> fields = fieldSchemas
                .stream()
                .collect(Collectors.toMap(FieldSchema::getName, field -> field));
        requireField(fields, collectionName, "id", field -> field.getDataType() == DataType.VarChar
                && field.getIsPrimaryKey() && !field.getAutoID() && hasParameter(field.getTypeParamsList(), "max_length", "36"));
        requireField(fields, collectionName, "text", field -> field.getDataType() == DataType.VarChar
                && hasParameter(field.getTypeParamsList(), "max_length", "65535"));
        if (bm25Collection && !hasParameter(fields.get("text").getTypeParamsList(),
                "enable_analyzer", "true")) {
            throw invalid(collectionName, "text.enable_analyzer");
        }
        requireField(fields, collectionName, "metadata", field -> field.getDataType() == DataType.JSON);
        requireVectorField(fields.get("vector"), collectionName, expectedDimension);
        if (bm25Collection) {
            requireField(fields, collectionName, "bm25_sparse_vector",
                    field -> field.getDataType() == DataType.SparseFloatVector);
            requireBm25Function(schema.getFunctionsList(), collectionName);
            verifyIndex(client, collectionName, "vector", "FLAT", "COSINE", null);
            verifyIndex(client, collectionName, "bm25_sparse_vector",
                    "SPARSE_INVERTED_INDEX", "BM25", "0.2");
            return;
        }
        verifyIndex(client, collectionName, "vector", "FLAT", "COSINE", null);
    }

    private void requireField(
            Map<String, FieldSchema> fields,
            String collectionName,
            String fieldName,
            Predicate<FieldSchema> matches) {
        FieldSchema field = fields.get(fieldName);
        if (field == null || !matches.test(field)) {
            throw invalid(collectionName, fieldName);
        }
    }

    private void requireVectorField(FieldSchema field, String collectionName, int expectedDimension) {
        if (field == null || field.getDataType() != DataType.FloatVector
                || !hasParameter(field.getTypeParamsList(), "dim", Integer.toString(expectedDimension))) {
            throw invalid(collectionName, "vector，期望维度=" + expectedDimension);
        }
    }

    private void requireBm25Function(List<FunctionSchema> functions, String collectionName) {
        boolean matched = functions.stream().anyMatch(function ->
                function.getType() == io.milvus.grpc.FunctionType.BM25
                        && function.getInputFieldNamesList().equals(List.of("text"))
                        && function.getOutputFieldNamesList().equals(List.of("bm25_sparse_vector")));
        if (!matched) {
            throw invalid(collectionName, "BM25 Function");
        }
    }

    private void verifyIndex(MilvusServiceClient client,
                             String collectionName,
                             String fieldName,
                             String indexType,
                             String metricType,
                             String dropRatioBuild) {
        R<?> indexResponse = client.describeIndex(DescribeIndexParam.newBuilder()
                .withCollectionName(collectionName)
                .withFieldName(fieldName)
                .build());
        requireSuccess(indexResponse, collectionName, fieldName + " index");
        List<IndexDescription> indexes = ((io.milvus.grpc.DescribeIndexResponse) indexResponse.getData())
                .getIndexDescriptionsList();
        IndexDescription index = indexes.stream()
                .filter(item -> fieldName.equals(item.getFieldName()))
                .findFirst()
                .orElseThrow(() -> invalid(collectionName, fieldName + " index"));
        if (!hasParameter(index.getParamsList(), "index_type", indexType)) {
            throw invalid(collectionName, indexType);
        }
        if (!hasParameter(index.getParamsList(), "metric_type", metricType)) {
            throw invalid(collectionName, metricType);
        }
        if (dropRatioBuild != null
                && !hasParameter(index.getParamsList(), "drop_ratio_build", dropRatioBuild)) {
            throw invalid(collectionName, "drop_ratio_build=" + dropRatioBuild);
        }
    }

    private boolean hasParameter(List<io.milvus.grpc.KeyValuePair> parameters, String key, String expectedValue) {
        return parameters.stream().anyMatch(parameter -> key.equals(parameter.getKey())
                && expectedValue.equals(parameter.getValue()));
    }

    private void requireSuccess(R<?> response, String collectionName, String resource) {
        if (response == null || response.getStatus() == null || response.getStatus() != R.Status.Success.getCode()
                || response.getData() == null) {
            throw invalid(collectionName, resource);
        }
    }

    private IllegalStateException invalid(String collectionName, String fieldName) {
        return new IllegalStateException("Milvus Collection=" + collectionName + " 的 " + fieldName + " 协议不匹配");
    }
}
