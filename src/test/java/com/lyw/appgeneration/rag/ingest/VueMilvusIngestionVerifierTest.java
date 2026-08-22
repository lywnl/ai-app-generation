package com.lyw.appgeneration.rag.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lyw.appgeneration.service.rag.catalog.TemplateCatalog;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.param.dml.QueryParam;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VueMilvusIngestionVerifierTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void 完全一致的当前版本通过且历史版本只统计() {
        VueIngestionExpectedSnapshot snapshot = snapshot();

        VueIngestionVerification result = VueMilvusIngestionVerifier.verifyRows(
                snapshot, validRows(snapshot), 7);

        assertTrue(result.passed());
        assertEquals(23, result.expectedCount());
        assertEquals(23, result.actualCount());
        assertEquals(7, result.historicalCount());
        assertEquals(Set.of(1024), result.dimensions());
    }

    @Test
    void 缺行额外行标识元数据维度和文本均失败且不泄漏内容() {
        VueIngestionExpectedSnapshot snapshot = snapshot();
        List<VueMilvusRow> rows = validRows(snapshot);

        assertIssue(snapshot, rows.subList(0, 22), "当前目录版本行数");
        assertIssue(snapshot, appendUnexpectedRow(rows), "存在未声明块");
        assertIssue(snapshot, replaceFirst(rows, new VueMilvusRow(
                UUID.randomUUID().toString(), 1024, rows.getFirst().text(), rows.getFirst().metadata())), "稳定 UUID");
        assertIssue(snapshot, replaceFirst(rows, new VueMilvusRow(
                rows.getFirst().embeddingId(), 768, rows.getFirst().text(), rows.getFirst().metadata())), "向量维度");
        VueIngestionVerification textResult = VueMilvusIngestionVerifier.verifyRows(snapshot,
                replaceFirst(rows, new VueMilvusRow(rows.getFirst().embeddingId(), 1024,
                        "敏感检索文本", rows.getFirst().metadata())), 0);
        assertFalse(textResult.passed());
        assertTrue(textResult.issues().stream().anyMatch(issue -> issue.contains("检索文本不一致")));
        assertTrue(textResult.issues().stream().noneMatch(issue -> issue.contains("敏感检索文本")));
    }

    @Test
    void expression转义反斜杠和双引号() {
        String value = "目录\\版本\"一";

        assertEquals("metadata[\"catalogVersion\"] == \"目录\\\\版本\\\"一\"",
                VueMilvusIngestionVerifier.catalogVersionExpression(value));
        assertEquals("metadata[\"catalogVersion\"] != \"目录\\\\版本\\\"一\"",
                VueMilvusIngestionVerifier.historicalVersionExpression(value));
    }

    @Test
    void 历史版本聚合查询不得设置分页参数() {
        QueryParam query = VueMilvusIngestionVerifier.historicalCountQuery("catalog-v1");

        assertEquals("templates_vue", query.getCollectionName());
        assertEquals(ConsistencyLevelEnum.STRONG, query.getConsistencyLevel());
        assertEquals(List.of("count(*)"), query.getOutFields());
        assertEquals("metadata[\"catalogVersion\"] != \"catalog-v1\"", query.getExpr());
        assertEquals(0L, query.getOffset());
        assertEquals(0L, query.getLimit());
    }

    @Test
    void SDK返回的Gson元数据按字符串对象解析() throws Exception {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("chunkId", "vue-chunk-001");
        metadata.addProperty("catalogVersion", "catalog-v1");

        Method readMetadata = VueMilvusIngestionVerifier.class
                .getDeclaredMethod("readMetadata", Object.class);
        readMetadata.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> parsed = (Map<String, String>) readMetadata.invoke(
                new VueMilvusIngestionVerifier(OBJECT_MAPPER), metadata);

        assertEquals(Map.of(
                "chunkId", "vue-chunk-001",
                "catalogVersion", "catalog-v1"), parsed);
    }

    @Test
    void SDK返回数字元数据值时拒绝协议漂移() throws Exception {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("chunkId", 1);

        assertMetadataRejected(metadata);
        assertMetadataRejected(Map.of("chunkId", 1));
    }

    @Test
    void SDK返回布尔元数据值时拒绝协议漂移() throws Exception {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("chunkId", true);

        assertMetadataRejected(metadata);
        assertMetadataRejected(Map.of("chunkId", true));
    }

    @Test
    void SDK返回数组元数据值时拒绝协议漂移() throws Exception {
        JsonObject metadata = new JsonObject();
        JsonArray values = new JsonArray();
        values.add("vue-chunk-001");
        metadata.add("chunkId", values);

        assertMetadataRejected(metadata);
        assertMetadataRejected(Map.of("chunkId", List.of("vue-chunk-001")));
    }

    @Test
    void SDK返回对象元数据值时拒绝协议漂移() throws Exception {
        JsonObject metadata = new JsonObject();
        JsonObject nested = new JsonObject();
        nested.addProperty("value", "vue-chunk-001");
        metadata.add("chunkId", nested);

        assertMetadataRejected(metadata);
        assertMetadataRejected(Map.of("chunkId", Map.of("value", "vue-chunk-001")));
    }

    @Test
    void 目标使用Milvus默认值和环境覆盖() {
        VueMilvusTarget defaults = VueMilvusTarget.from(Map.of());
        VueMilvusTarget configured = VueMilvusTarget.from(Map.of(
                "RAG_MILVUS_HOST", "milvus.internal",
                "RAG_MILVUS_PORT", "19531",
                "RAG_MILVUS_DATABASE", "rag_test",
                "RAG_MILVUS_USERNAME", "reader"));

        assertEquals("127.0.0.1:19530/default", defaults.displayName());
        assertEquals("root", defaults.username());
        assertEquals("milvus.internal:19531/rag_test", configured.displayName());
        assertEquals("reader", configured.username());
    }

    private static void assertIssue(
            VueIngestionExpectedSnapshot snapshot,
            List<VueMilvusRow> rows,
            String expectedIssue) {
        VueIngestionVerification result = VueMilvusIngestionVerifier.verifyRows(snapshot, rows, 0);
        assertFalse(result.passed());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.contains(expectedIssue)),
                () -> expectedIssue + ": " + result.issues());
    }

    private static void assertMetadataRejected(Object metadata) throws Exception {
        Method readMetadata = VueMilvusIngestionVerifier.class
                .getDeclaredMethod("readMetadata", Object.class);
        readMetadata.setAccessible(true);

        InvocationTargetException exception = assertThrows(InvocationTargetException.class,
                () -> readMetadata.invoke(new VueMilvusIngestionVerifier(OBJECT_MAPPER), metadata));

        assertInstanceOf(IllegalArgumentException.class, exception.getCause());
    }

    private static List<VueMilvusRow> appendUnexpectedRow(List<VueMilvusRow> rows) {
        List<VueMilvusRow> result = new ArrayList<>(rows);
        Map<String, String> metadata = new LinkedHashMap<>(rows.getFirst().metadata());
        metadata.put("chunkId", "unexpected-chunk");
        result.add(new VueMilvusRow(UUID.randomUUID().toString(), 1024, "未声明文本", metadata));
        return result;
    }

    private static List<VueMilvusRow> replaceFirst(List<VueMilvusRow> rows, VueMilvusRow replacement) {
        List<VueMilvusRow> result = new ArrayList<>(rows);
        result.set(0, replacement);
        return result;
    }

    private static VueIngestionExpectedSnapshot snapshot() {
        return VueIngestionExpectedSnapshot.from(new TemplateCatalog(
                Path.of("embed_text/vue-project"), OBJECT_MAPPER));
    }

    private static List<VueMilvusRow> validRows(VueIngestionExpectedSnapshot snapshot) {
        return snapshot.rowsByChunkId().values().stream()
                .map(row -> new VueMilvusRow(row.embeddingId().toString(), 1024,
                        row.searchText(), Map.of(
                                "chunkId", row.chunkId(),
                                "documentId", row.documentId(),
                                "documentKind", row.documentKind().name(),
                                "chunkKind", row.chunkKind().name(),
                                "catalogVersion", snapshot.catalogVersion())))
                .toList();
    }
}
