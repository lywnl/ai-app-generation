package com.lyw.appgeneration.rag.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 通过 JDBC 只读核验 Vue 当前目录版本的 PGVector 物理数据。
 */
final class VuePgVectorIngestionVerifier {

    private static final String TABLE_NAME = "templates_vue";
    private static final String COLUMN_PROTOCOL_SQL = """
            SELECT column_name, data_type, udt_name
            FROM information_schema.columns
            WHERE table_schema = current_schema() AND table_name = ?
            """;
    private static final String CURRENT_ROWS_SQL = """
            SELECT embedding_id, vector_dims(embedding), text, metadata::text
            FROM %s
            WHERE metadata->>'catalogVersion' = ?
            ORDER BY embedding_id
            """.formatted(TABLE_NAME);
    private static final String HISTORICAL_COUNT_SQL = """
            SELECT count(*)
            FROM %s
            WHERE COALESCE(metadata->>'catalogVersion', '') <> ?
            """.formatted(TABLE_NAME);
    private static final Map<String, ColumnProtocol> EXPECTED_COLUMNS = Map.of(
            "embedding_id", new ColumnProtocol("uuid", "uuid"),
            "embedding", new ColumnProtocol("USER-DEFINED", "vector"),
            "text", new ColumnProtocol("text", "text"),
            "metadata", new ColumnProtocol("json", "json"));
    private static final TypeReference<Map<String, String>> METADATA_TYPE =
            new TypeReference<>() {
            };
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private VuePgVectorIngestionVerifier() {
    }

    static VueIngestionVerification verify(
            VueIngestionExpectedSnapshot expected,
            VuePgVectorTarget target,
            String password) {
        Connection connection;
        try {
            connection = DriverManager.getConnection(target.jdbcUrl(), target.user(), password);
        } catch (SQLException exception) {
            return failure(expected, "数据库连接失败", exception);
        }
        try (connection) {
            return verifyConnection(expected, connection);
        } catch (SQLException exception) {
            return failure(expected, "数据库连接关闭失败", exception);
        }
    }

    static VueIngestionVerification verifyRows(
            VueIngestionExpectedSnapshot expected,
            List<VuePgVectorRow> rows,
            long historicalCount) {
        List<String> issues = new ArrayList<>();
        if (rows.size() != expected.rowsByChunkId().size()) {
            issues.add("当前目录版本行数不一致: 期望=%d,实际=%d".formatted(
                    expected.rowsByChunkId().size(), rows.size()));
        }

        Map<String, VuePgVectorRow> actualByChunkId = indexRows(rows, issues);
        expected.rowsByChunkId().forEach((chunkId, expectedRow) -> {
            VuePgVectorRow actual = actualByChunkId.remove(chunkId);
            if (actual == null) {
                issues.add("缺少知识块: " + chunkId);
                return;
            }
            compareRow(expected, expectedRow, actual, issues);
        });
        actualByChunkId.keySet().forEach(chunkId ->
                issues.add("存在未声明块: " + chunkId));
        Set<Integer> dimensions = rows.stream()
                .map(VuePgVectorRow::vectorDimension)
                .collect(Collectors.toUnmodifiableSet());
        return new VueIngestionVerification(
                issues.isEmpty(), expected.catalogVersion(),
                expected.rowsByChunkId().size(), rows.size(), historicalCount,
                dimensions, issues);
    }

    private static VueIngestionVerification verifyConnection(
            VueIngestionExpectedSnapshot expected,
            Connection connection) {
        try {
            List<String> protocolIssues = verifyColumnProtocol(connection);
            if (!protocolIssues.isEmpty()) {
                return failure(expected, protocolIssues);
            }
            List<VuePgVectorRow> rows = readCurrentRows(
                    connection, expected.catalogVersion());
            long historicalCount = readHistoricalCount(
                    connection, expected.catalogVersion());
            return verifyRows(expected, rows, historicalCount);
        } catch (SQLException exception) {
            return failure(expected, "数据库读取失败", exception);
        } catch (JsonProcessingException exception) {
            return failure(expected, "metadata 解析失败", exception);
        } catch (IllegalArgumentException exception) {
            return failure(expected, "metadata 解析失败", exception);
        }
    }

    private static List<String> verifyColumnProtocol(Connection connection) throws SQLException {
        Map<String, ColumnProtocol> actualColumns = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(COLUMN_PROTOCOL_SQL)) {
            statement.setString(1, TABLE_NAME);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    actualColumns.put(
                            resultSet.getString("column_name"),
                            new ColumnProtocol(
                                    resultSet.getString("data_type"),
                                    resultSet.getString("udt_name")));
                }
            }
        }
        if (actualColumns.isEmpty()) {
            return List.of("PGVector 表不存在");
        }

        List<String> issues = new ArrayList<>();
        EXPECTED_COLUMNS.forEach((column, protocol) -> {
            if (!protocol.equals(actualColumns.get(column))) {
                issues.add("PGVector 列协议不一致: " + column);
            }
        });
        return issues;
    }

    private static List<VuePgVectorRow> readCurrentRows(
            Connection connection,
            String catalogVersion) throws SQLException, JsonProcessingException {
        List<VuePgVectorRow> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(CURRENT_ROWS_SQL)) {
            statement.setString(1, catalogVersion);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add(new VuePgVectorRow(
                            resultSet.getObject("embedding_id", java.util.UUID.class),
                            resultSet.getInt(2),
                            resultSet.getString("text"),
                            readMetadata(resultSet.getString(4))));
                }
            }
        }
        return rows;
    }

    private static Map<String, String> readMetadata(String metadataJson)
            throws JsonProcessingException {
        if (metadataJson == null) {
            throw new IllegalArgumentException("metadata 为空");
        }
        Map<String, String> metadata = OBJECT_MAPPER.readValue(metadataJson, METADATA_TYPE);
        if (metadata == null) {
            throw new IllegalArgumentException("metadata 不是对象");
        }
        return metadata;
    }

    private static long readHistoricalCount(
            Connection connection,
            String catalogVersion) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(HISTORICAL_COUNT_SQL)) {
            statement.setString(1, catalogVersion);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0;
            }
        }
    }

    private static Map<String, VuePgVectorRow> indexRows(
            List<VuePgVectorRow> rows,
            List<String> issues) {
        Map<String, VuePgVectorRow> rowsByChunkId = new LinkedHashMap<>();
        for (VuePgVectorRow row : rows) {
            String chunkId = row.metadata().get("chunkId");
            if (chunkId == null) {
                issues.add("知识块缺少 chunkId");
                continue;
            }
            if (rowsByChunkId.putIfAbsent(chunkId, row) != null) {
                issues.add("重复知识块: " + chunkId);
            }
        }
        return rowsByChunkId;
    }

    private static void compareRow(
            VueIngestionExpectedSnapshot expected,
            VueIngestionExpectedSnapshot.ExpectedRow expectedRow,
            VuePgVectorRow actual,
            List<String> issues) {
        String chunkId = expectedRow.chunkId();
        if (!expectedRow.embeddingId().equals(actual.embeddingId())) {
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
            VuePgVectorRow actual,
            String chunkId,
            List<String> issues) {
        if (!expectedValue.equals(actual.metadata().get(key))) {
            issues.add("metadata " + key + " 不一致: " + chunkId);
        }
    }

    private static VueIngestionVerification failure(
            VueIngestionExpectedSnapshot expected,
            String category,
            Exception exception) {
        return failure(expected, List.of(
                category + ": " + exception.getClass().getSimpleName()));
    }

    private static VueIngestionVerification failure(
            VueIngestionExpectedSnapshot expected,
            List<String> issues) {
        return new VueIngestionVerification(
                false, expected.catalogVersion(), expected.rowsByChunkId().size(),
                0, 0, Set.of(), issues);
    }

    private record ColumnProtocol(String dataType, String udtName) {
    }
}
