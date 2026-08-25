package com.lyw.appgeneration.rag.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.service.rag.ingest.NativeTemplateIngestor;
import com.lyw.appgeneration.service.rag.store.MilvusCollectionSchemaVerifier;
import com.lyw.appgeneration.service.rag.store.MilvusEmbeddingStoreFactory;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 只在显式摄取时调用 DashScope 并写入本地 Milvus，日常测试默认跳过。
 */
@EnabledIfEnvironmentVariable(named = "NATIVE_RAG_INGEST_EXECUTE", matches = "true")
class NativeMilvusIngestionExecutionTest {

    @Test
    void ingestsOnlyHtmlAndMultiFileWithStableIds() {
        Map<String, String> environment = System.getenv();
        String apiKey = required(environment, "DASHSCOPE_API_KEY");
        String password = firstNonBlank(
                environment.get("RAG_MILVUS_PASSWORD"),
                required(environment, "INFRA_SHARED_PASSWORD"));
        RagProperties properties = properties(environment, password);
        EmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
                .baseUrl(properties.getEmbedding().getBaseUrl())
                .apiKey(apiKey)
                .modelName(properties.getEmbedding().getModelName())
                .dimensions(properties.getEmbedding().getDimension())
                .timeout(Duration.ofMillis(properties.getEmbedding().getTimeoutMs()))
                .logRequests(false)
                .logResponses(false)
                .build();
        NativeTemplateIngestor ingestor = new NativeTemplateIngestor(
                embeddingModel, new ObjectMapper());
        MilvusEmbeddingStoreFactory factory = new MilvusEmbeddingStoreFactory(
                properties, new MilvusCollectionSchemaVerifier());
        try {
            NativeTemplateIngestor.IngestResult html = ingest(
                    ingestor, factory, "html", CodeGenTypeEnum.HTML, "templates_html");
            NativeTemplateIngestor.IngestResult multi = ingest(
                    ingestor, factory, "multi-file", CodeGenTypeEnum.MULTI_FILE, "templates_multi");
            assertEquals(9, html.documentCount());
            assertEquals(8, multi.documentCount());
        } finally {
            factory.close();
        }
    }

    private NativeTemplateIngestor.IngestResult ingest(
            NativeTemplateIngestor ingestor,
            MilvusEmbeddingStoreFactory factory,
            String directory,
            CodeGenTypeEnum type,
            String collection) {
        EmbeddingStore<TextSegment> store = factory.create(collection);
        seedLegacyRowWithoutCatalogVersion(store, collection);
        return ingestor.ingest(Path.of("embed_text", directory), type, store);
    }

    private void seedLegacyRowWithoutCatalogVersion(
            EmbeddingStore<TextSegment> store,
            String collection) {
        float[] vector = new float[1024];
        vector[0] = 1.0f;
        TextSegment legacySegment = TextSegment.from(
                "旧协议孤儿记录",
                Metadata.from(Map.of(
                        "id", "legacy-obsolete-" + collection,
                        "title", "旧协议记录",
                        "category", "legacy",
                        "code", "[]")));
        store.addAll(
                List.of(UUID.randomUUID().toString()),
                List.of(Embedding.from(vector)),
                List.of(legacySegment));
    }

    private RagProperties properties(Map<String, String> environment, String password) {
        RagProperties properties = new RagProperties();
        properties.getMilvus().setHost(firstNonBlank(
                environment.get("RAG_MILVUS_HOST"), "localhost"));
        properties.getMilvus().setPort(parsePort(environment.get("RAG_MILVUS_PORT")));
        properties.getMilvus().setDatabase(firstNonBlank(
                environment.get("RAG_MILVUS_DATABASE"), "default"));
        properties.getMilvus().setUsername(firstNonBlank(
                environment.get("RAG_MILVUS_USERNAME"), "root"));
        properties.getMilvus().setPassword(password);
        return properties;
    }

    private int parsePort(String value) {
        return value == null || value.isBlank() ? 19530 : Integer.parseInt(value);
    }

    private String required(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + key);
        }
        return value;
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }
}
