package com.lyw.appgeneration.rag.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonElement;
import com.lyw.appgeneration.constants.RagConstants;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.service.rag.catalog.NativeTemplateCatalog;
import com.lyw.appgeneration.service.rag.catalog.TemplateCatalog;
import com.lyw.appgeneration.service.rag.model.KnowledgeChunk;
import com.lyw.appgeneration.service.rag.ingest.NativeTemplateIngestor;
import com.lyw.appgeneration.service.rag.model.TemplateDoc;
import com.lyw.appgeneration.service.rag.store.MilvusCollectionSchemaVerifier;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.QueryResults;
import io.milvus.param.ConnectParam;
import io.milvus.param.R;
import io.milvus.param.dml.QueryParam;
import io.milvus.response.QueryResultsWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 只在显式验收时连接本地 Milvus，核对原生模板摄取后的物理数据。
 */
@EnabledIfEnvironmentVariable(named = "NATIVE_RAG_INGEST_VERIFY", matches = "true")
class NativeMilvusIngestionVerificationTest {

    private static final int EMBEDDING_DIMENSION = 1024;
    private static final Set<String> METADATA_KEYS = Set.of(
            "documentId", "documentKind", "catalogVersion", "title", "category");
    private static final Set<String> VUE_METADATA_KEYS = Set.of(
            "chunkId", "documentId", "documentKind", "chunkKind", "catalogVersion");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void verifiesNativeCollectionsAndKeepsVueCollectionUnchanged() throws Exception {
        NativeTemplateCatalog htmlCatalog = new NativeTemplateCatalog(
                Path.of("embed_text", "html"), CodeGenTypeEnum.HTML, OBJECT_MAPPER);
        NativeTemplateCatalog multiCatalog = new NativeTemplateCatalog(
                Path.of("embed_text", "multi-file"), CodeGenTypeEnum.MULTI_FILE, OBJECT_MAPPER);
        TemplateCatalog vueCatalog = new TemplateCatalog(
                Path.of("embed_text", "vue-project"), OBJECT_MAPPER);
        MilvusServiceClient client = createClient();
        try {
            verifyCollection(client, "templates_html", htmlCatalog);
            verifyCollection(client, "templates_multi", multiCatalog);
            verifyVueBm25Collection(client, vueCatalog);
            new MilvusCollectionSchemaVerifier().verify(
                    client, "templates_vue", EMBEDDING_DIMENSION);
            assertEquals(23L, count(client, "templates_vue", "id != \"\""),
                    "原生模板摄取不得改变 Vue Collection 行数");
        } finally {
            close(client);
        }
    }

    private void verifyVueBm25Collection(
            MilvusServiceClient client,
            TemplateCatalog catalog) throws Exception {
        new MilvusCollectionSchemaVerifier().verify(
                client, RagConstants.VUE_BM25_COLLECTION, EMBEDDING_DIMENSION);
        List<QueryResultsWrapper.RowRecord> rows = query(client, QueryParam.newBuilder()
                .withCollectionName(RagConstants.VUE_BM25_COLLECTION)
                .withConsistencyLevel(ConsistencyLevelEnum.STRONG)
                .withExpr(catalogVersionExpression(catalog.getCatalogVersion(), true))
                .withOutFields(List.of("id", "text", "metadata", "vector"))
                .withLimit(1000L)
                .build());
        assertEquals(catalog.getChunks().size(), rows.size(),
                "Vue BM25 当前目录版本行数不一致");
        assertEquals(catalog.getChunks().size(), count(
                client, RagConstants.VUE_BM25_COLLECTION, "id != \"\""),
                "Vue BM25 总行数不一致");
        assertEquals(0L, count(client, RagConstants.VUE_BM25_COLLECTION,
                        catalogVersionExpression(catalog.getCatalogVersion(), false)),
                "Vue BM25 仍存在历史目录版本");

        Map<String, KnowledgeChunk> expectedByChunkId = catalog.getChunks().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        KnowledgeChunk::chunkId, chunk -> chunk));
        Set<String> actualChunkIds = new HashSet<>();
        Set<Integer> vectorDimensions = new HashSet<>();
        for (QueryResultsWrapper.RowRecord row : rows) {
            Map<String, String> metadata = readMetadata(row.get("metadata"));
            assertEquals(VUE_METADATA_KEYS, metadata.keySet(), "Vue BM25 metadata 键不一致");
            String chunkId = metadata.get("chunkId");
            assertTrue(actualChunkIds.add(chunkId), "Vue BM25 存在重复 chunkId: " + chunkId);
            KnowledgeChunk expected = expectedByChunkId.get(chunkId);
            assertNotNull(expected, "Vue BM25 存在未声明 chunkId: " + chunkId);
            assertEquals(expected.documentId(), metadata.get("documentId"));
            assertEquals(expected.documentKind().name(), metadata.get("documentKind"));
            assertEquals(expected.chunkKind().name(), metadata.get("chunkKind"));
            assertEquals(catalog.getCatalogVersion(), metadata.get("catalogVersion"));
            assertEquals(expected.searchText(), row.get("text"));
            assertEquals(stableId(chunkId), row.get("id"));
            assertTrue(row.get("vector") instanceof List<?>, "Vue BM25 向量字段协议错误");
            vectorDimensions.add(((List<?>) row.get("vector")).size());
        }
        assertEquals(expectedByChunkId.keySet(), actualChunkIds, "Vue BM25 知识块集合不一致");
        assertEquals(Set.of(EMBEDDING_DIMENSION), vectorDimensions, "Vue BM25 向量维度不一致");
    }

    private void verifyCollection(
            MilvusServiceClient client,
            String collection,
            NativeTemplateCatalog catalog) throws Exception {
        new MilvusCollectionSchemaVerifier().verify(client, collection, EMBEDDING_DIMENSION);
        List<QueryResultsWrapper.RowRecord> rows = query(client, QueryParam.newBuilder()
                .withCollectionName(collection)
                .withConsistencyLevel(ConsistencyLevelEnum.STRONG)
                .withExpr(catalogVersionExpression(catalog.getCatalogVersion(), true))
                .withOutFields(List.of("id", "text", "metadata", "vector"))
                .withLimit(100L)
                .build());

        assertEquals(catalog.getDocuments().size(), rows.size(),
                collection + " 当前目录版本行数不一致");
        assertEquals(catalog.getDocuments().size(), count(client, collection, "id != \"\""),
                collection + " 总行数不一致，可能残留旧协议或历史版本数据");
        assertEquals(0L, count(client, collection,
                        catalogVersionExpression(catalog.getCatalogVersion(), false)),
                collection + " 仍存在历史目录版本");

        Map<String, TemplateDoc> expectedById = catalog.getDocuments().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        TemplateDoc::getId, document -> document));
        Set<String> actualDocumentIds = new HashSet<>();
        Set<Integer> vectorDimensions = new HashSet<>();
        for (QueryResultsWrapper.RowRecord row : rows) {
            Map<String, String> metadata = readMetadata(row.get("metadata"));
            assertEquals(METADATA_KEYS, metadata.keySet(), collection + " metadata 键不一致");
            String documentId = metadata.get("documentId");
            assertTrue(actualDocumentIds.add(documentId),
                    collection + " 存在重复 documentId: " + documentId);
            TemplateDoc expected = expectedById.get(documentId);
            assertNotNull(expected, collection + " 存在未声明 documentId: " + documentId);
            assertEquals(expected.getDocumentKind().name(), metadata.get("documentKind"));
            assertEquals(catalog.getCatalogVersion(), metadata.get("catalogVersion"));
            assertEquals(expected.getTitle(), metadata.get("title"));
            assertEquals(expected.getCategory(), metadata.get("category"));
            assertEquals(expected.getEmbedText(), row.get("text"));
            assertEquals(stableId(documentId), row.get("id"));
            assertTrue(row.get("vector") instanceof List<?>, collection + " 向量字段协议错误");
            vectorDimensions.add(((List<?>) row.get("vector")).size());
        }
        assertEquals(expectedById.keySet(), actualDocumentIds, collection + " 文档集合不一致");
        assertEquals(Set.of(EMBEDDING_DIMENSION), vectorDimensions,
                collection + " 向量维度不一致");
    }

    private List<QueryResultsWrapper.RowRecord> query(
            MilvusServiceClient client,
            QueryParam parameter) {
        R<QueryResults> response = client.query(parameter);
        if (response == null || response.getStatus() == null
                || response.getStatus() != R.Status.Success.getCode() || response.getData() == null) {
            throw new IllegalStateException("Milvus 查询失败: " + parameter.getCollectionName());
        }
        return new ArrayList<>(new QueryResultsWrapper(response.getData()).getRowRecords());
    }

    private long count(MilvusServiceClient client, String collection, String expression) {
        List<QueryResultsWrapper.RowRecord> rows = query(client, QueryParam.newBuilder()
                .withCollectionName(collection)
                .withConsistencyLevel(ConsistencyLevelEnum.STRONG)
                .withExpr(expression)
                .withOutFields(List.of("count(*)"))
                .build());
        Object count = rows.isEmpty() ? 0L : rows.getFirst().get("count(*)");
        if (!(count instanceof Number number)) {
            throw new IllegalStateException("Milvus count(*) 返回协议错误: " + collection);
        }
        return number.longValue();
    }

    private Map<String, String> readMetadata(Object rawMetadata) throws Exception {
        JsonNode root = rawMetadata instanceof JsonElement jsonElement
                ? OBJECT_MAPPER.readTree(jsonElement.toString())
                : OBJECT_MAPPER.valueToTree(rawMetadata);
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("Milvus metadata 不是 JSON 对象");
        }
        Map<String, String> values = new java.util.LinkedHashMap<>();
        root.fields().forEachRemaining(field -> {
            if (!field.getValue().isTextual()) {
                throw new IllegalStateException("Milvus metadata 必须全部是字符串字段");
            }
            values.put(field.getKey(), field.getValue().textValue());
        });
        return Map.copyOf(values);
    }

    private String catalogVersionExpression(String catalogVersion, boolean equal) {
        String operator = equal ? "==" : "!=";
        return "metadata[\"catalogVersion\"] %s \"%s\"".formatted(
                operator, catalogVersion.replace("\\", "\\\\").replace("\"", "\\\""));
    }

    private String stableId(String documentId) {
        return UUID.nameUUIDFromBytes(documentId.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private void close(MilvusServiceClient client) {
        try {
            client.close(5L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private MilvusServiceClient createClient() {
        Map<String, String> environment = System.getenv();
        String password = firstNonBlank(
                environment.get("RAG_MILVUS_PASSWORD"),
                environment.get("INFRA_SHARED_PASSWORD"));
        if (password == null) {
            throw new IllegalStateException("缺少 RAG_MILVUS_PASSWORD 或 INFRA_SHARED_PASSWORD");
        }
        return new MilvusServiceClient(ConnectParam.newBuilder()
                .withHost(firstNonBlank(environment.get("RAG_MILVUS_HOST"), "localhost"))
                .withPort(parsePort(environment.get("RAG_MILVUS_PORT")))
                .withDatabaseName(firstNonBlank(environment.get("RAG_MILVUS_DATABASE"), "default"))
                .withAuthorization(
                        firstNonBlank(environment.get("RAG_MILVUS_USERNAME"), "root"),
                        password)
                .build());
    }

    private int parsePort(String value) {
        return value == null || value.isBlank() ? 19530 : Integer.parseInt(value);
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }
}
