package com.lyw.appgeneration.rag.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonElement;
import com.lyw.appgeneration.service.rag.store.MilvusCollectionSchemaVerifier;
import com.lyw.appgeneration.constants.RagConstants;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.QueryResults;
import io.milvus.param.ConnectParam;
import io.milvus.param.R;
import io.milvus.param.dml.QueryParam;
import io.milvus.response.QueryResultsWrapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 通过 Milvus SDK 只读核验 Vue 当前目录版本的物理数据。
 */
public final class VueMilvusIngestionVerifier {

    private static final String COLLECTION_NAME = RagConstants.VUE_BM25_COLLECTION;
    private static final List<String> ROW_FIELDS = List.of("id", "text", "metadata", "vector");
    private static final long PAGE_SIZE = 1000L;
    private final MilvusClientProvider clientProvider;
    private final SchemaVerifier schemaVerifier;
    private final ObjectMapper objectMapper;

    public VueMilvusIngestionVerifier(ObjectMapper objectMapper) {
        this(VueMilvusIngestionVerifier::createClient,
                new MilvusCollectionSchemaVerifier()::verify, objectMapper);
    }

    VueMilvusIngestionVerifier(
            MilvusClientProvider clientProvider,
            SchemaVerifier schemaVerifier,
            ObjectMapper objectMapper) {
        this.clientProvider = Objects.requireNonNull(clientProvider);
        this.schemaVerifier = Objects.requireNonNull(schemaVerifier);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public VueIngestionVerification verify(
            VueIngestionExpectedSnapshot expected,
            VueMilvusTarget target,
            String password) {
        MilvusServiceClient client;
        try {
            client = clientProvider.create(target, password);
        } catch (RuntimeException exception) {
            return failure(expected, "Milvus 连接失败", exception);
        }
        try {
            schemaVerifier.verify(client, COLLECTION_NAME, expected.embeddingDimension());
            List<VueMilvusRow> rows = readCurrentRows(client, expected.catalogVersion());
            long historicalCount = readHistoricalCount(client, expected.catalogVersion());
            return verifyRows(expected, rows, historicalCount);
        } catch (RuntimeException | JsonProcessingException exception) {
            return failure(expected, "Milvus 读取失败", exception);
        } finally {
            close(client);
        }
    }

    static VueIngestionVerification verifyRows(
            VueIngestionExpectedSnapshot expected,
            List<VueMilvusRow> rows,
            long historicalCount) {
        List<String> issues = new ArrayList<>();
        if (rows.size() != expected.rowsByChunkId().size()) {
            issues.add("当前目录版本行数不一致: 期望=%d,实际=%d".formatted(
                    expected.rowsByChunkId().size(), rows.size()));
        }
        Map<String, VueMilvusRow> actualByChunkId = indexRows(rows, issues);
        expected.rowsByChunkId().forEach((chunkId, expectedRow) -> {
            VueMilvusRow actual = actualByChunkId.remove(chunkId);
            if (actual == null) {
                issues.add("缺少知识块: " + chunkId);
                return;
            }
            compareRow(expected, expectedRow, actual, issues);
        });
        if (!actualByChunkId.isEmpty()) {
            issues.add("存在未声明块: 数量=" + actualByChunkId.size());
        }
        Set<Integer> dimensions = rows.stream()
                .map(VueMilvusRow::vectorDimension)
                .collect(Collectors.toUnmodifiableSet());
        return new VueIngestionVerification(
                issues.isEmpty(), expected.catalogVersion(), expected.rowsByChunkId().size(),
                rows.size(), historicalCount, dimensions, issues);
    }

    static String catalogVersionExpression(String catalogVersion) {
        return "metadata[\"catalogVersion\"] == \"%s\"".formatted(escapeExpressionValue(catalogVersion));
    }

    static String historicalVersionExpression(String catalogVersion) {
        return "metadata[\"catalogVersion\"] != \"%s\"".formatted(escapeExpressionValue(catalogVersion));
    }

    static QueryParam historicalCountQuery(String catalogVersion) {
        return QueryParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .withConsistencyLevel(ConsistencyLevelEnum.STRONG)
                .withExpr(historicalVersionExpression(catalogVersion))
                .withOutFields(List.of("count(*)"))
                .build();
    }

    private List<VueMilvusRow> readCurrentRows(
            MilvusServiceClient client,
            String catalogVersion) throws JsonProcessingException {
        List<VueMilvusRow> rows = new ArrayList<>();
        long offset = 0;
        while (true) {
            QueryParam query = QueryParam.newBuilder()
                    .withCollectionName(COLLECTION_NAME)
                    .withConsistencyLevel(ConsistencyLevelEnum.STRONG)
                    .withExpr(catalogVersionExpression(catalogVersion))
                    .withOutFields(ROW_FIELDS)
                    .withOffset(offset)
                    .withLimit(PAGE_SIZE)
                    .build();
            List<QueryResultsWrapper.RowRecord> page = queryRows(client, query);
            for (QueryResultsWrapper.RowRecord record : page) {
                rows.add(toRow(record));
            }
            if (page.size() < PAGE_SIZE) {
                return rows;
            }
            offset += PAGE_SIZE;
        }
    }

    private long readHistoricalCount(MilvusServiceClient client, String catalogVersion) {
        List<QueryResultsWrapper.RowRecord> records = queryRows(client, historicalCountQuery(catalogVersion));
        if (records.isEmpty()) {
            return 0;
        }
        Object value = records.getFirst().get("count(*)");
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("Milvus count(*) 返回协议不匹配");
        }
        return number.longValue();
    }

    private List<QueryResultsWrapper.RowRecord> queryRows(
            MilvusServiceClient client,
            QueryParam query) {
        R<QueryResults> response = client.query(query);
        if (response == null || response.getStatus() == null
                || response.getStatus() != R.Status.Success.getCode() || response.getData() == null) {
            throw new IllegalStateException("Milvus 查询失败");
        }
        return new QueryResultsWrapper(response.getData()).getRowRecords();
    }

    private VueMilvusRow toRow(QueryResultsWrapper.RowRecord record) throws JsonProcessingException {
        Object id = record.get("id");
        Object text = record.get("text");
        Object vector = record.get("vector");
        if (!(id instanceof String stringId) || !(text instanceof String stringText)
                || !(vector instanceof List<?> vectorValues)) {
            throw new IllegalArgumentException("Milvus 行协议不匹配");
        }
        return new VueMilvusRow(stringId, vectorValues.size(), stringText,
                readMetadata(record.get("metadata")));
    }

    private Map<String, String> readMetadata(Object metadata) throws JsonProcessingException {
        if (metadata == null) {
            throw new IllegalArgumentException("metadata 为空");
        }
        JsonNode root = metadata instanceof JsonElement jsonElement
                ? objectMapper.readTree(jsonElement.toString())
                : objectMapper.valueToTree(metadata);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("metadata 不是字符串对象");
        }
        Map<String, String> values = new LinkedHashMap<>();
        root.fields().forEachRemaining(field -> {
            if (!field.getValue().isTextual()) {
                throw new IllegalArgumentException("metadata 不是字符串对象");
            }
            values.put(field.getKey(), field.getValue().textValue());
        });
        return values;
    }

    private static String escapeExpressionValue(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static Map<String, VueMilvusRow> indexRows(
            List<VueMilvusRow> rows,
            List<String> issues) {
        Map<String, VueMilvusRow> rowsByChunkId = new LinkedHashMap<>();
        int duplicateCount = 0;
        for (VueMilvusRow row : rows) {
            String chunkId = row.metadata().get("chunkId");
            if (chunkId == null) {
                issues.add("知识块缺少 chunkId");
                continue;
            }
            if (rowsByChunkId.putIfAbsent(chunkId, row) != null) {
                duplicateCount++;
            }
        }
        if (duplicateCount > 0) {
            issues.add("重复知识块: 数量=" + duplicateCount);
        }
        return rowsByChunkId;
    }

    private static void compareRow(
            VueIngestionExpectedSnapshot expected,
            VueIngestionExpectedSnapshot.ExpectedRow expectedRow,
            VueMilvusRow actual,
            List<String> issues) {
        String chunkId = expectedRow.chunkId();
        if (!expectedRow.embeddingId().toString().equals(actual.embeddingId())) {
            issues.add("稳定 UUID 不一致: " + chunkId);
        }
        if (!expected.metadataKeys().equals(actual.metadata().keySet())) {
            issues.add("metadata 键集合不一致: " + chunkId);
        }
        compareMetadata("chunkId", expectedRow.chunkId(), actual, chunkId, issues);
        compareMetadata("documentId", expectedRow.documentId(), actual, chunkId, issues);
        compareMetadata("documentKind", expectedRow.documentKind().name(), actual, chunkId, issues);
        compareMetadata("chunkKind", expectedRow.chunkKind().name(), actual, chunkId, issues);
        compareMetadata("catalogVersion", expected.catalogVersion(), actual, chunkId, issues);
        if (actual.vectorDimension() != expected.embeddingDimension()) {
            issues.add("向量维度不一致: " + chunkId);
        }
        if (!expectedRow.searchText().equals(actual.text())) {
            issues.add("检索文本不一致: " + chunkId);
        }
    }

    private static void compareMetadata(
            String key,
            String expectedValue,
            VueMilvusRow actual,
            String chunkId,
            List<String> issues) {
        if (!expectedValue.equals(actual.metadata().get(key))) {
            issues.add("metadata " + key + " 不一致: " + chunkId);
        }
    }

    private static void close(MilvusServiceClient client) {
        try {
            client.close(5L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ignored) {
            // 核验结论优先，关闭异常不能被误报为物理数据不一致。
        }
    }

    private static VueIngestionVerification failure(
            VueIngestionExpectedSnapshot expected,
            String category,
            Exception exception) {
        return new VueIngestionVerification(
                false, expected.catalogVersion(), expected.rowsByChunkId().size(),
                0, 0, Set.of(), List.of(category + ": " + exception.getClass().getSimpleName()));
    }

    private static MilvusServiceClient createClient(VueMilvusTarget target, String password) {
        return new MilvusServiceClient(ConnectParam.newBuilder()
                .withHost(target.host())
                .withPort(target.port())
                .withDatabaseName(target.database())
                .withAuthorization(target.username(), password)
                .build());
    }

    @FunctionalInterface
    interface MilvusClientProvider {
        MilvusServiceClient create(VueMilvusTarget target, String password);
    }

    @FunctionalInterface
    interface SchemaVerifier {
        void verify(MilvusServiceClient client, String collectionName, int dimension);
    }
}
