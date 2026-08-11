package com.lyw.appgeneration.rag.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.service.rag.model.KnowledgeChunk;
import com.lyw.appgeneration.service.rag.model.RagChunkKind;
import com.lyw.appgeneration.service.rag.model.RagDocumentKind;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VuePgVectorIngestionVerifierTest {

    private static final String CATALOG_VERSION = "catalog-v1";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void 完全一致的当前版本通过且历史版本只统计() {
        VueIngestionExpectedSnapshot snapshot = snapshot();

        VueIngestionVerification result = VuePgVectorIngestionVerifier.verifyRows(
                snapshot, validRows(snapshot), 7);

        assertTrue(result.passed());
        assertEquals(CATALOG_VERSION, result.catalogVersion());
        assertEquals(23, result.expectedCount());
        assertEquals(23, result.actualCount());
        assertEquals(7, result.historicalCount());
        assertEquals(Set.of(1024), result.dimensions());
        assertTrue(result.issues().isEmpty());
    }

    @Test
    void 缺行额外行错误标识元数据维度和文本均失败() {
        VueIngestionExpectedSnapshot snapshot = snapshot();
        List<VuePgVectorRow> validRows = validRows(snapshot);

        assertIssue(snapshot, validRows.subList(0, 22), "当前目录版本行数");
        assertIssue(snapshot, appendUnexpectedRow(validRows), "存在未声明块");
        assertIssue(snapshot, replaceFirst(validRows,
                row -> copy(UUID.randomUUID(), row.vectorDimension(), row.text(), row.metadata())),
                "稳定 UUID");
        assertIssue(snapshot, replaceFirst(validRows,
                row -> copy(row.embeddingId(), row.vectorDimension(), row.text(),
                        withoutMetadata(row.metadata(), "chunkKind"))),
                "metadata 键集合");
        assertIssue(snapshot, replaceFirst(validRows,
                row -> copy(row.embeddingId(), row.vectorDimension(), row.text(),
                        withMetadata(row.metadata(), "unexpectedKey", "unexpectedValue"))),
                "metadata 键集合");
        assertIssue(snapshot, replaceFirst(validRows,
                row -> copy(row.embeddingId(), row.vectorDimension(), row.text(),
                        withMetadata(row.metadata(), "documentId", "wrong"))),
                "documentId");
        assertIssue(snapshot, replaceFirst(validRows,
                row -> copy(row.embeddingId(), 768, row.text(), row.metadata())),
                "向量维度");
        assertIssue(snapshot, replaceFirst(validRows,
                row -> copy(row.embeddingId(), row.vectorDimension(), "错误文本", row.metadata())),
                "检索文本");

        VueIngestionVerification textResult = VuePgVectorIngestionVerifier.verifyRows(
                snapshot,
                replaceFirst(validRows, row -> copy(
                        row.embeddingId(), row.vectorDimension(), "错误文本", row.metadata())),
                0);
        assertTrue(textResult.issues().stream().noneMatch(issue ->
                issue.contains("错误文本") || issue.contains(validRows.getFirst().text())));
    }

    @Test
    void 五项元数据分别比对且问题不泄露检索文本() {
        VueIngestionExpectedSnapshot snapshot = snapshot();
        List<VuePgVectorRow> rows = validRows(snapshot);

        for (String key : Set.of(
                "documentId", "documentKind", "chunkKind", "catalogVersion")) {
            VueIngestionVerification result = VuePgVectorIngestionVerifier.verifyRows(
                    snapshot,
                    replaceFirst(rows, row -> copy(
                            row.embeddingId(), row.vectorDimension(), row.text(),
                            withMetadata(row.metadata(), key, "敏感差异内容"))),
                    0);

            assertFalse(result.passed());
            assertTrue(result.issues().stream().anyMatch(issue -> issue.contains(key)), key);
            assertTrue(result.issues().stream().noneMatch(issue ->
                    issue.contains("敏感差异内容") || issue.contains(rows.getFirst().text())));
        }

        VueIngestionVerification chunkIdResult = VuePgVectorIngestionVerifier.verifyRows(
                snapshot,
                replaceFirst(rows, row -> copy(
                        row.embeddingId(), row.vectorDimension(), row.text(),
                        withMetadata(row.metadata(), "chunkId", "敏感差异内容"))),
                0);
        assertFalse(chunkIdResult.passed());
        assertTrue(chunkIdResult.issues().stream().anyMatch(issue -> issue.contains("缺少知识块")));
        assertTrue(chunkIdResult.issues().stream().anyMatch(issue -> issue.contains("存在未声明块")));
        assertTrue(chunkIdResult.issues().stream().noneMatch(issue -> issue.contains(rows.getFirst().text())));
    }

    @Test
    void 重复块标识被明确拒绝() {
        VueIngestionExpectedSnapshot snapshot = snapshot();
        List<VuePgVectorRow> rows = new ArrayList<>(validRows(snapshot));
        VuePgVectorRow first = rows.getFirst();
        VuePgVectorRow second = rows.get(1);
        rows.set(1, copy(second.embeddingId(), second.vectorDimension(), second.text(),
                withMetadata(second.metadata(), "chunkId", first.metadata().get("chunkId"))));

        VueIngestionVerification result = VuePgVectorIngestionVerifier.verifyRows(snapshot, rows, 0);

        assertFalse(result.passed());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.contains("重复知识块")));
    }

    @Test
    void 未声明和重复块问题不泄露数据库中的实际块标识() {
        VueIngestionExpectedSnapshot snapshot = snapshot();
        List<VuePgVectorRow> rows = new ArrayList<>(validRows(snapshot));
        List<String> sensitiveChunkIds = List.of(
                "password=database-secret-marker",
                "<template>SOURCE_SECRET_MARKER</template>",
                "检索文本秘密标记-SEARCH_TEXT_SECRET_MARKER");
        for (String sensitiveChunkId : sensitiveChunkIds) {
            rows.add(unexpectedRow(sensitiveChunkId));
            rows.add(unexpectedRow(sensitiveChunkId));
        }

        VueIngestionVerification result = VuePgVectorIngestionVerifier.verifyRows(snapshot, rows, 0);

        assertFalse(result.passed());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.contains("存在未声明块")));
        assertTrue(result.issues().stream().anyMatch(issue -> issue.contains("重复知识块")));
        sensitiveChunkIds.forEach(sensitiveChunkId ->
                assertTrue(result.issues().stream().noneMatch(issue -> issue.contains(sensitiveChunkId)),
                        sensitiveChunkId));
    }

    @Test
    void 元数据空值转换为核验问题而非逃逸异常() {
        VueIngestionExpectedSnapshot snapshot = snapshot();
        List<VuePgVectorRow> rows = validRows(snapshot);
        Map<String, String> metadataWithNull = new LinkedHashMap<>(rows.getFirst().metadata());
        metadataWithNull.put("chunkKind", null);

        VueIngestionVerification result = VuePgVectorIngestionVerifier.verifyRows(
                snapshot,
                replaceFirst(rows, row -> copy(
                        row.embeddingId(), row.vectorDimension(), row.text(), metadataWithNull)),
                0);

        assertFalse(result.passed());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.contains("chunkKind")));
    }

    @Test
    void 数据库失败只暴露固定类别和异常类型() {
        VueIngestionExpectedSnapshot snapshot = snapshot();
        String password = "绝不能出现在问题列表中的密码";
        String rawMessage = password + " 原始 SQL 异常 检索文本-0";

        VueIngestionVerification result = new VuePgVectorIngestionVerifier(
                (jdbcUrl, user, ignoredPassword) -> {
                    throw new SQLException(rawMessage);
                }, OBJECT_MAPPER).verify(
                snapshot, new VuePgVectorTarget("127.0.0.1", 1, "unavailable", "admin"), password);

        assertFalse(result.passed());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.contains("数据库连接失败")));
        assertTrue(result.issues().stream().anyMatch(issue -> issue.contains("SQLException")));
        assertTrue(result.issues().stream().noneMatch(issue -> issue.contains(password)));
        assertEquals(1, result.issues().size());
        assertTrue(result.issues().getFirst().matches("数据库连接失败: [A-Za-z0-9_$]+"));
        assertFalse(result.issues().getFirst().contains(rawMessage));
    }

    @Test
    void jdbc成功读取二十三行统计历史并关闭所有资源() throws Exception {
        JdbcHarness jdbc = JdbcHarness.valid(snapshot());

        VueIngestionVerification result = jdbc.runVerification();

        assertTrue(result.passed());
        assertEquals(23, result.actualCount());
        assertEquals(7, result.historicalCount());
        verify(jdbc.protocolStatement).setString(1, "templates_vue");
        verify(jdbc.currentStatement).setString(1, CATALOG_VERSION);
        verify(jdbc.historicalStatement).setString(1, CATALOG_VERSION);
        jdbc.verifyAllResourcesClosed();
    }

    @Test
    void 缺表和错列协议均在读取物理行前失败() throws Exception {
        JdbcHarness missingTable = JdbcHarness.valid(snapshot());
        missingTable.protocolColumns = List.of();
        missingTable.stubProtocolRows();
        JdbcHarness wrongColumn = JdbcHarness.valid(snapshot());
        wrongColumn.protocolColumns = List.of(
                new Column("embedding_id", "uuid", "uuid"),
                new Column("embedding", "USER-DEFINED", "vector"),
                new Column("text", "character varying", "varchar"),
                new Column("metadata", "json", "json"));
        wrongColumn.stubProtocolRows();

        VueIngestionVerification missingResult = missingTable.runVerification();
        VueIngestionVerification wrongResult = wrongColumn.runVerification();

        assertIssueOnly(missingResult, "PGVector 表不存在");
        assertIssueOnly(wrongResult, "PGVector 列协议不一致: text");
        verify(missingTable.connection, never()).prepareStatement(
                org.mockito.ArgumentMatchers.contains("vector_dims"));
        verify(wrongColumn.connection, never()).prepareStatement(
                org.mockito.ArgumentMatchers.contains("vector_dims"));
        missingTable.verifyProtocolResourcesClosed();
        wrongColumn.verifyProtocolResourcesClosed();
    }

    @Test
    void 当前行和历史统计SQL异常均脱敏并关闭已创建资源() throws Exception {
        String rawMessage = "password 检索文本-0 SELECT 原始异常";
        JdbcHarness currentFailure = JdbcHarness.valid(snapshot());
        when(currentFailure.currentStatement.executeQuery()).thenThrow(new SQLException(rawMessage));
        JdbcHarness historicalFailure = JdbcHarness.valid(snapshot());
        when(historicalFailure.historicalStatement.executeQuery()).thenThrow(new SQLException(rawMessage));

        VueIngestionVerification currentResult = currentFailure.runVerification();
        VueIngestionVerification historicalResult = historicalFailure.runVerification();

        assertSanitizedIssue(currentResult, "数据库读取失败", rawMessage);
        assertSanitizedIssue(historicalResult, "数据库读取失败", rawMessage);
        verify(currentFailure.currentStatement).close();
        verify(currentFailure.connection).close();
        verify(historicalFailure.currentRows).close();
        verify(historicalFailure.currentStatement).close();
        verify(historicalFailure.historicalStatement).close();
        verify(historicalFailure.connection).close();
    }

    @Test
    void metadata数据库空值JSON空值非法JSON和含空字段均返回受控失败() throws Exception {
        assertMetadataIssue(null, "metadata 解析失败");
        assertMetadataIssue("null", "metadata 解析失败");
        assertMetadataIssue("{非法 JSON", "metadata 解析失败");

        Map<String, String> metadataWithNull = new LinkedHashMap<>(
                validRows(snapshot()).getFirst().metadata());
        metadataWithNull.put("chunkKind", null);
        assertMetadataIssue(OBJECT_MAPPER.writeValueAsString(metadataWithNull), "chunkKind");
    }

    @Test
    void 连接关闭异常使用独立类别且不泄露原始消息() throws Exception {
        JdbcHarness jdbc = JdbcHarness.valid(snapshot());
        String rawMessage = "password 检索文本-0 close raw message";
        org.mockito.Mockito.doThrow(new SQLException(rawMessage)).when(jdbc.connection).close();

        VueIngestionVerification result = jdbc.runVerification();

        assertSanitizedIssue(result, "数据库连接关闭失败", rawMessage);
        verify(jdbc.protocolRows).close();
        verify(jdbc.currentRows).close();
        verify(jdbc.historicalRows).close();
        verify(jdbc.protocolStatement).close();
        verify(jdbc.currentStatement).close();
        verify(jdbc.historicalStatement).close();
    }

    private static void assertIssue(
            VueIngestionExpectedSnapshot snapshot,
            List<VuePgVectorRow> rows,
            String expectedIssue) {
        VueIngestionVerification result = VuePgVectorIngestionVerifier.verifyRows(snapshot, rows, 0);

        assertFalse(result.passed(), expectedIssue);
        assertTrue(result.issues().stream().anyMatch(issue -> issue.contains(expectedIssue)),
                () -> expectedIssue + ": " + result.issues());
    }

    private static void assertIssueOnly(
            VueIngestionVerification result,
            String expectedIssue) {
        assertFalse(result.passed());
        assertEquals(List.of(expectedIssue), result.issues());
    }

    private static void assertSanitizedIssue(
            VueIngestionVerification result,
            String category,
            String rawMessage) {
        assertFalse(result.passed());
        assertEquals(1, result.issues().size());
        assertTrue(result.issues().getFirst().startsWith(category + ": "));
        assertFalse(result.issues().getFirst().contains(rawMessage));
        assertFalse(result.issues().getFirst().contains("password"));
        assertFalse(result.issues().getFirst().contains("检索文本"));
        assertFalse(result.issues().getFirst().contains("SELECT"));
    }

    private static void assertMetadataIssue(
            String metadataJson,
            String expectedIssue) throws Exception {
        JdbcHarness jdbc = JdbcHarness.valid(snapshot());
        jdbc.metadataOverrides.put(0, metadataJson);
        jdbc.stubCurrentRows();

        VueIngestionVerification result = jdbc.runVerification();

        assertFalse(result.passed());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.contains(expectedIssue)),
                () -> result.issues().toString());
        assertTrue(result.issues().stream().noneMatch(issue ->
                issue.contains("检索文本-0") || issue.contains("{非法 JSON")));
        verify(jdbc.connection).close();
        verify(jdbc.currentRows).close();
        verify(jdbc.currentStatement).close();
    }

    private static VueIngestionExpectedSnapshot snapshot() {
        List<KnowledgeChunk> chunks = IntStream.range(0, 23)
                .mapToObj(index -> new KnowledgeChunk(
                        "chunk-" + index,
                        "document-" + index,
                        index % 2 == 0
                                ? RagDocumentKind.PROJECT_SKELETON
                                : RagDocumentKind.FEATURE_SNIPPET,
                        index % 2 == 0 ? RagChunkKind.OVERVIEW : RagChunkKind.ENGINEERING,
                        "检索文本-" + index))
                .toList();
        return VueIngestionExpectedSnapshot.from(CATALOG_VERSION, chunks);
    }

    private static List<VuePgVectorRow> validRows(VueIngestionExpectedSnapshot snapshot) {
        return snapshot.rowsByChunkId().values().stream()
                .map(row -> new VuePgVectorRow(
                        row.embeddingId(), snapshot.embeddingDimension(), row.searchText(), Map.of(
                        "chunkId", row.chunkId(),
                        "documentId", row.documentId(),
                        "documentKind", row.documentKind().name(),
                        "chunkKind", row.chunkKind().name(),
                        "catalogVersion", snapshot.catalogVersion())))
                .toList();
    }

    private static List<VuePgVectorRow> appendUnexpectedRow(List<VuePgVectorRow> rows) {
        List<VuePgVectorRow> result = new ArrayList<>(rows);
        result.add(unexpectedRow("unexpected"));
        return result;
    }

    private static VuePgVectorRow unexpectedRow(String chunkId) {
        return new VuePgVectorRow(
                UUID.randomUUID(), 1024, "未声明文本", Map.of(
                "chunkId", chunkId,
                "documentId", "unexpected-document",
                "documentKind", "FEATURE_SNIPPET",
                "chunkKind", "OVERVIEW",
                "catalogVersion", CATALOG_VERSION));
    }

    private static List<VuePgVectorRow> replaceFirst(
            List<VuePgVectorRow> rows,
            UnaryOperator<VuePgVectorRow> replacement) {
        List<VuePgVectorRow> result = new ArrayList<>(rows);
        result.set(0, replacement.apply(result.getFirst()));
        return result;
    }

    private static VuePgVectorRow copy(
            UUID embeddingId,
            int vectorDimension,
            String text,
            Map<String, String> metadata) {
        return new VuePgVectorRow(embeddingId, vectorDimension, text, metadata);
    }

    private static Map<String, String> withoutMetadata(
            Map<String, String> metadata,
            String key) {
        Map<String, String> result = new LinkedHashMap<>(metadata);
        result.remove(key);
        return result;
    }

    private static Map<String, String> withMetadata(
            Map<String, String> metadata,
            String key,
            String value) {
        Map<String, String> result = new LinkedHashMap<>(metadata);
        result.put(key, value);
        return result;
    }

    private record Column(String name, String dataType, String udtName) {
    }

    private static final class JdbcHarness {

        private final VueIngestionExpectedSnapshot snapshot;
        private final Connection connection = mock(Connection.class);
        private final PreparedStatement protocolStatement = mock(PreparedStatement.class);
        private final PreparedStatement currentStatement = mock(PreparedStatement.class);
        private final PreparedStatement historicalStatement = mock(PreparedStatement.class);
        private final ResultSet protocolRows = mock(ResultSet.class);
        private final ResultSet currentRows = mock(ResultSet.class);
        private final ResultSet historicalRows = mock(ResultSet.class);
        private final Map<Integer, String> metadataOverrides = new LinkedHashMap<>();
        private List<Column> protocolColumns = List.of(
                new Column("embedding_id", "uuid", "uuid"),
                new Column("embedding", "USER-DEFINED", "vector"),
                new Column("text", "text", "text"),
                new Column("metadata", "json", "json"));

        private JdbcHarness(VueIngestionExpectedSnapshot snapshot) throws Exception {
            this.snapshot = snapshot;
            when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
                String sql = invocation.getArgument(0);
                if (sql.contains("information_schema.columns")) {
                    return protocolStatement;
                }
                if (sql.contains("vector_dims")) {
                    return currentStatement;
                }
                if (sql.contains("count(*)")) {
                    return historicalStatement;
                }
                throw new AssertionError("出现未声明 SQL 协议");
            });
            when(protocolStatement.executeQuery()).thenReturn(protocolRows);
            when(currentStatement.executeQuery()).thenReturn(currentRows);
            when(historicalStatement.executeQuery()).thenReturn(historicalRows);
            when(historicalRows.next()).thenReturn(true, false);
            when(historicalRows.getLong(1)).thenReturn(7L);
            stubProtocolRows();
            stubCurrentRows();
        }

        static JdbcHarness valid(VueIngestionExpectedSnapshot snapshot) throws Exception {
            return new JdbcHarness(snapshot);
        }

        VueIngestionVerification runVerification() {
            return new VuePgVectorIngestionVerifier(
                    (jdbcUrl, user, password) -> connection, OBJECT_MAPPER)
                    .verify(snapshot,
                            new VuePgVectorTarget("db.internal", 15432, "rag", "rag_user"),
                            "password");
        }

        void stubProtocolRows() throws Exception {
            int[] index = {-1};
            doAnswer(invocation -> ++index[0] < protocolColumns.size())
                    .when(protocolRows).next();
            doAnswer(invocation -> protocolColumns.get(index[0]).name())
                    .when(protocolRows).getString("column_name");
            doAnswer(invocation -> protocolColumns.get(index[0]).dataType())
                    .when(protocolRows).getString("data_type");
            doAnswer(invocation -> protocolColumns.get(index[0]).udtName())
                    .when(protocolRows).getString("udt_name");
        }

        void stubCurrentRows() throws Exception {
            List<VuePgVectorRow> expectedRows = validRows(snapshot);
            int[] index = {-1};
            doAnswer(invocation -> ++index[0] < expectedRows.size())
                    .when(currentRows).next();
            doAnswer(invocation -> expectedRows.get(index[0]).embeddingId())
                    .when(currentRows).getObject("embedding_id", UUID.class);
            doAnswer(invocation -> expectedRows.get(index[0]).vectorDimension())
                    .when(currentRows).getInt(2);
            doAnswer(invocation -> expectedRows.get(index[0]).text())
                    .when(currentRows).getString("text");
            doAnswer(invocation -> {
                int rowIndex = index[0];
                if (metadataOverrides.containsKey(rowIndex)) {
                    return metadataOverrides.get(rowIndex);
                }
                return OBJECT_MAPPER.writeValueAsString(expectedRows.get(rowIndex).metadata());
            }).when(currentRows).getString(4);
        }

        void verifyProtocolResourcesClosed() throws Exception {
            verify(protocolRows).close();
            verify(protocolStatement).close();
            verify(connection).close();
        }

        void verifyAllResourcesClosed() throws Exception {
            verify(protocolRows).close();
            verify(currentRows).close();
            verify(historicalRows).close();
            verify(protocolStatement).close();
            verify(currentStatement).close();
            verify(historicalStatement).close();
            verify(connection).close();
        }
    }
}
