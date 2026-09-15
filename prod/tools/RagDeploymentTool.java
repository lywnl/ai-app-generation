import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonElement;
import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.constants.RagConstants;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.service.rag.catalog.NativeTemplateCatalog;
import com.lyw.appgeneration.service.rag.catalog.TemplateCatalog;
import com.lyw.appgeneration.service.rag.ingest.NativeTemplateIngestor;
import com.lyw.appgeneration.service.rag.ingest.VueKnowledgeIngestor;
import com.lyw.appgeneration.service.rag.model.RagDocumentKind;
import com.lyw.appgeneration.service.rag.store.MilvusBm25SearchClient;
import com.lyw.appgeneration.service.rag.store.MilvusCollectionSchemaVerifier;
import com.lyw.appgeneration.service.rag.store.MilvusEmbeddingStoreFactory;
import com.lyw.appgeneration.service.rag.store.MilvusV2ClientProvider;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.R;
import io.milvus.param.dml.QueryParam;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.response.QueryResultsWrapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/** 独立部署工具：复用生产摄取协议，不启动 Web 服务和后台记忆任务。 */
public final class RagDeploymentTool {
    private static final ObjectMapper JSON = new ObjectMapper();
    private record Expected(String text, Map<String, String> metadata) {}

    public static void main(String[] args) throws Exception {
        String mode = args.length == 1 ? args[0] : "verify";
        if (!Set.of("catalog", "ingest", "verify").contains(mode)) {
            throw new IllegalArgumentException("只支持 catalog、ingest、verify");
        }
        Path root = Path.of(System.getenv().getOrDefault("RAG_TEMPLATES_DIR", "/app/embed_text"));
        NativeTemplateCatalog html = new NativeTemplateCatalog(root.resolve("html"), CodeGenTypeEnum.HTML, JSON);
        NativeTemplateCatalog multi = new NativeTemplateCatalog(root.resolve("multi-file"), CodeGenTypeEnum.MULTI_FILE, JSON);
        TemplateCatalog vue = new TemplateCatalog(root.resolve("vue-project"), JSON);
        Map<String, Map<String, Expected>> expected = new LinkedHashMap<>();
        expected.put("templates_html", nativeExpected(html));
        expected.put("templates_multi", nativeExpected(multi));
        Map<String, Expected> vueRows = new LinkedHashMap<>();
        for (var chunk : vue.getChunks()) {
            vueRows.put(stableId(chunk.chunkId()), new Expected(chunk.searchText(), Map.of(
                    "chunkId", chunk.chunkId(), "documentId", chunk.documentId(),
                    "documentKind", chunk.documentKind().name(), "chunkKind", chunk.chunkKind().name(),
                    "catalogVersion", vue.getCatalogVersion())));
        }
        expected.put(RagConstants.VUE_BM25_COLLECTION, vueRows);
        expected.forEach((name, rows) -> System.out.println("EXPECTED " + name + "=" + rows.size()));
        if (mode.equals("catalog")) return;

        RagProperties properties = properties();
        var factory = new MilvusEmbeddingStoreFactory(properties, new MilvusCollectionSchemaVerifier());
        try {
            if (mode.equals("ingest")) {
                var model = OpenAiEmbeddingModel.builder()
                        .baseUrl(properties.getEmbedding().getBaseUrl())
                        .modelName(properties.getEmbedding().getModelName()).dimensions(1024)
                        .apiKey(required("DASHSCOPE_API_KEY")).timeout(Duration.ofSeconds(30))
                        .logRequests(false).logResponses(false).build();
                var nativeIngestor = new NativeTemplateIngestor(model, JSON);
                nativeIngestor.ingest(html, factory.create("templates_html"));
                nativeIngestor.ingest(multi, factory.create("templates_multi"));
                new VueKnowledgeIngestor(model, JSON).ingest(root.resolve("vue-project"), factory.create(RagConstants.VUE_BM25_COLLECTION));
            }
            verify(properties, factory, expected, vue.getCatalogVersion());
            System.out.println("RAG_DEPLOYMENT_VERIFIED");
        } finally {
            factory.close();
        }
    }

    private static Map<String, Expected> nativeExpected(NativeTemplateCatalog catalog) {
        Map<String, Expected> rows = new LinkedHashMap<>();
        for (var doc : catalog.getDocuments()) {
            rows.put(stableId(doc.getId()), new Expected(doc.getEmbedText(), Map.of(
                    "documentId", doc.getId(), "documentKind", doc.getDocumentKind().name(),
                    "catalogVersion", catalog.getCatalogVersion(), "title", doc.getTitle(), "category", doc.getCategory())));
        }
        return rows;
    }

    private static void verify(RagProperties properties, MilvusEmbeddingStoreFactory factory,
                               Map<String, Map<String, Expected>> expected, String vueVersion) throws Exception {
        var milvus = properties.getMilvus();
        var client = new MilvusServiceClient(ConnectParam.newBuilder().withHost(milvus.getHost())
                .withPort(milvus.getPort()).withDatabaseName(milvus.getDatabase())
                .withAuthorization(milvus.getUsername(), milvus.getPassword()).build());
        try {
            for (var entry : expected.entrySet()) {
                var store = factory.create(entry.getKey());
                var response = client.query(QueryParam.newBuilder().withCollectionName(entry.getKey())
                        .withConsistencyLevel(ConsistencyLevelEnum.STRONG).withExpr("id != \"\"")
                        .withOutFields(List.of("id", "text", "metadata", "vector")).withLimit(1000L).build());
                require(response.getStatus() == R.Status.Success.getCode(), "Milvus 查询失败");
                var rows = new QueryResultsWrapper(response.getData()).getRowRecords();
                require(rows.size() == entry.getValue().size(), "记录数量不一致: " + entry.getKey());
                Set<String> ids = new HashSet<>();
                for (var row : rows) {
                    String id = (String) row.get("id");
                    require(ids.add(id) && entry.getValue().containsKey(id), "未知或重复记录 ID");
                    Expected item = entry.getValue().get(id);
                    Object raw = row.get("metadata");
                    JsonNode metadata = raw instanceof JsonElement json ? JSON.readTree(json.toString()) : JSON.valueToTree(raw);
                    require(JSON.valueToTree(item.metadata()).equals(metadata), "metadata 不一致");
                    require(item.text().equals(row.get("text")), "索引文本不一致");
                    require(row.get("vector") instanceof List<?> v && v.size() == 1024, "向量维度错误");
                }
                verifyDense(store, rows.getFirst());
                System.out.println("VERIFIED " + entry.getKey() + "=" + rows.size() + " dimension=1024 dense=OK");
            }
            try (var provider = new MilvusV2ClientProvider(properties)) {
                var bm25 = new MilvusBm25SearchClient(provider);
                for (var kind : List.of(RagDocumentKind.PROJECT_SKELETON, RagDocumentKind.FEATURE_SNIPPET)) {
                    String query = kind == RagDocumentKind.PROJECT_SKELETON
                            ? "Vue3 Element Plus 商城 电商 购物车" : "Vue3 登录表单 邮箱密码校验 el-form";
                    String documentId = kind == RagDocumentKind.PROJECT_SKELETON
                            ? "vue-skeleton-shop-001" : "vue-login-form-001";
                    var result = bm25.search(query, vueVersion, kind, 10);
                    require(result.getSearchResults() != null && !result.getSearchResults().isEmpty()
                            && !result.getSearchResults().getFirst().isEmpty(), "BM25 未召回: " + kind);
                    require(result.getSearchResults().getFirst().stream().anyMatch(hit -> {
                        Expected item = expected.get(RagConstants.VUE_BM25_COLLECTION).get(String.valueOf(hit.getId()));
                        return item != null && documentId.equals(item.metadata().get("documentId"));
                    }), "BM25 未召回预期模板: " + documentId);
                }
                System.out.println("BM25 skeleton=OK feature=OK");
            }
        } finally {
            client.close(5L);
        }
    }

    private static void verifyDense(EmbeddingStore<TextSegment> store, QueryResultsWrapper.RowRecord row) {
        List<?> values = (List<?>) row.get("vector");
        float[] vector = new float[values.size()];
        for (int i = 0; i < vector.length; i++) vector[i] = ((Number) values.get(i)).floatValue();
        var result = store.search(EmbeddingSearchRequest.builder().queryEmbedding(Embedding.from(vector))
                .maxResults(1).minScore(0.99).build());
        require(!result.matches().isEmpty(), "稠密向量检索失败");
    }

    private static RagProperties properties() {
        var p = new RagProperties();
        p.getMilvus().setHost(required("RAG_MILVUS_HOST"));
        p.getMilvus().setPort(Integer.parseInt(System.getenv().getOrDefault("RAG_MILVUS_PORT", "19530")));
        p.getMilvus().setDatabase(System.getenv().getOrDefault("RAG_MILVUS_DATABASE", "default"));
        p.getMilvus().setUsername(System.getenv().getOrDefault("RAG_MILVUS_USERNAME", "root"));
        p.getMilvus().setPassword(required("RAG_MILVUS_PASSWORD"));
        return p;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        require(value != null && !value.isBlank(), "缺少环境变量 " + name);
        return value;
    }

    private static String stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
