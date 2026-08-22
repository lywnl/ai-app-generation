package com.lyw.appgeneration.service.rag.store;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.FieldSchema;
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

    private static final Set<String> EXPECTED_FIELD_NAMES = Set.of("id", "text", "metadata", "vector");

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
        List<FieldSchema> fieldSchemas = schema.getFieldsList();
        Set<String> fieldNames = fieldSchemas.stream()
                .map(FieldSchema::getName)
                .collect(Collectors.toSet());
        EXPECTED_FIELD_NAMES.stream()
                .filter(expectedField -> !fieldNames.contains(expectedField))
                .findFirst()
                .ifPresent(missingField -> {
                    throw invalid(collectionName, missingField);
                });
        if (fieldSchemas.size() != EXPECTED_FIELD_NAMES.size()) {
            throw invalid(collectionName, "字段集合");
        }
        Map<String, FieldSchema> fields = fieldSchemas
                .stream()
                .collect(Collectors.toMap(FieldSchema::getName, field -> field));
        requireField(fields, collectionName, "id", field -> field.getDataType() == DataType.VarChar
                && field.getIsPrimaryKey() && !field.getAutoID() && hasParameter(field.getTypeParamsList(), "max_length", "36"));
        requireField(fields, collectionName, "text", field -> field.getDataType() == DataType.VarChar
                && hasParameter(field.getTypeParamsList(), "max_length", "65535"));
        requireField(fields, collectionName, "metadata", field -> field.getDataType() == DataType.JSON);
        requireVectorField(fields.get("vector"), collectionName, expectedDimension);
        R<?> indexResponse = client.describeIndex(DescribeIndexParam.newBuilder()
                .withCollectionName(collectionName)
                .withFieldName("vector")
                .build());
        requireSuccess(indexResponse, collectionName, "index");
        List<IndexDescription> indexes = ((io.milvus.grpc.DescribeIndexResponse) indexResponse.getData())
                .getIndexDescriptionsList();
        IndexDescription vectorIndex = indexes.stream()
                .filter(index -> "vector".equals(index.getFieldName()))
                .findFirst()
                .orElseThrow(() -> invalid(collectionName, "vector index"));
        if (!hasParameter(vectorIndex.getParamsList(), "index_type", "FLAT")) {
            throw invalid(collectionName, "FLAT");
        }
        if (!hasParameter(vectorIndex.getParamsList(), "metric_type", "COSINE")) {
            throw invalid(collectionName, "COSINE");
        }
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
