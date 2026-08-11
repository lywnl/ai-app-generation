package com.lyw.appgeneration.rag.ingest;

import com.lyw.appgeneration.service.rag.model.KnowledgeChunk;
import com.lyw.appgeneration.service.rag.model.RagChunkKind;
import com.lyw.appgeneration.service.rag.model.RagDocumentKind;
import org.junit.jupiter.api.Test;

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

class VuePgVectorIngestionVerifierTest {

    private static final String CATALOG_VERSION = "catalog-v1";

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
                row -> copy(row, UUID.randomUUID(), row.vectorDimension(), row.text(), row.metadata())),
                "稳定 UUID");
        assertIssue(snapshot, replaceFirst(validRows,
                row -> copy(row, row.embeddingId(), row.vectorDimension(), row.text(),
                        withoutMetadata(row.metadata(), "chunkKind"))),
                "metadata 键集合");
        assertIssue(snapshot, replaceFirst(validRows,
                row -> copy(row, row.embeddingId(), row.vectorDimension(), row.text(),
                        withMetadata(row.metadata(), "unexpectedKey", "unexpectedValue"))),
                "metadata 键集合");
        assertIssue(snapshot, replaceFirst(validRows,
                row -> copy(row, row.embeddingId(), row.vectorDimension(), row.text(),
                        withMetadata(row.metadata(), "documentId", "wrong"))),
                "documentId");
        assertIssue(snapshot, replaceFirst(validRows,
                row -> copy(row, row.embeddingId(), 768, row.text(), row.metadata())),
                "向量维度");
        assertIssue(snapshot, replaceFirst(validRows,
                row -> copy(row, row.embeddingId(), row.vectorDimension(), "错误文本", row.metadata())),
                "检索文本");

        VueIngestionVerification textResult = VuePgVectorIngestionVerifier.verifyRows(
                snapshot,
                replaceFirst(validRows, row -> copy(
                        row, row.embeddingId(), row.vectorDimension(), "错误文本", row.metadata())),
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
                            row, row.embeddingId(), row.vectorDimension(), row.text(),
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
                        row, row.embeddingId(), row.vectorDimension(), row.text(),
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
        rows.set(1, copy(second, second.embeddingId(), second.vectorDimension(), second.text(),
                withMetadata(second.metadata(), "chunkId", first.metadata().get("chunkId"))));

        VueIngestionVerification result = VuePgVectorIngestionVerifier.verifyRows(snapshot, rows, 0);

        assertFalse(result.passed());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.contains("重复知识块")));
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
                        row, row.embeddingId(), row.vectorDimension(), row.text(), metadataWithNull)),
                0);

        assertFalse(result.passed());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.contains("chunkKind")));
    }

    @Test
    void 数据库失败只暴露固定类别和异常类型() {
        VueIngestionExpectedSnapshot snapshot = snapshot();
        String password = "绝不能出现在问题列表中的密码";

        VueIngestionVerification result = VuePgVectorIngestionVerifier.verify(
                snapshot, new VuePgVectorTarget("127.0.0.1", 1, "unavailable", "admin"), password);

        assertFalse(result.passed());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.contains("数据库连接失败")));
        assertTrue(result.issues().stream().anyMatch(issue -> issue.contains("SQLException")));
        assertTrue(result.issues().stream().noneMatch(issue -> issue.contains(password)));
        assertEquals(1, result.issues().size());
        assertTrue(result.issues().getFirst().matches("数据库连接失败: [A-Za-z0-9_$]+"));
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
        result.add(new VuePgVectorRow(
                UUID.randomUUID(), 1024, "未声明文本", Map.of(
                "chunkId", "unexpected",
                "documentId", "unexpected-document",
                "documentKind", "FEATURE_SNIPPET",
                "chunkKind", "OVERVIEW",
                "catalogVersion", CATALOG_VERSION)));
        return result;
    }

    private static List<VuePgVectorRow> replaceFirst(
            List<VuePgVectorRow> rows,
            UnaryOperator<VuePgVectorRow> replacement) {
        List<VuePgVectorRow> result = new ArrayList<>(rows);
        result.set(0, replacement.apply(result.getFirst()));
        return result;
    }

    private static VuePgVectorRow copy(
            VuePgVectorRow row,
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
}
