package com.lyw.appgeneration.service.rag.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemplateIngestServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void delegatesVueDirectoryToKnowledgeIngestor(@TempDir Path tempDir) throws IOException {
        Path vueRoot = Files.createDirectories(tempDir.resolve("vue-project/features"));
        Files.writeString(vueRoot.resolve("nested.json"), "{}");
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        EmbeddingStore<TextSegment> vueStore = store();
        VueKnowledgeIngestor vueIngestor = mock(VueKnowledgeIngestor.class);
        when(vueIngestor.ingest(tempDir.resolve("vue-project"), vueStore))
                .thenReturn(new VueKnowledgeIngestor.IngestResult("catalog-version", 23));

        service(tempDir, embeddingModel, Map.of(CodeGenTypeEnum.VUE_PROJECT, vueStore), vueIngestor)
                .ingestAll();

        verify(vueIngestor).ingest(tempDir.resolve("vue-project"), vueStore);
        verify(embeddingModel, never()).embed(any(String.class));
        verify(vueStore, never()).add(any(Embedding.class), any(TextSegment.class));
    }

    @Test
    void keepsLegacyHtmlAndMultiFileIngestion(@TempDir Path tempDir) throws IOException {
        Path htmlRoot = Files.createDirectories(tempDir.resolve("html"));
        Path multiRoot = Files.createDirectories(tempDir.resolve("multi-file"));
        writeLegacyDocument(htmlRoot.resolve("landing.json"), "html-001", "HTML 检索描述");
        writeLegacyDocument(multiRoot.resolve("app.json"), "multi-001", "多文件检索描述");
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        Embedding htmlEmbedding = Embedding.from(new float[]{1, 2});
        Embedding multiEmbedding = Embedding.from(new float[]{3, 4});
        when(embeddingModel.embed("HTML 检索描述")).thenReturn(Response.from(htmlEmbedding));
        when(embeddingModel.embed("多文件检索描述")).thenReturn(Response.from(multiEmbedding));
        EmbeddingStore<TextSegment> htmlStore = store();
        EmbeddingStore<TextSegment> multiStore = store();
        VueKnowledgeIngestor vueIngestor = mock(VueKnowledgeIngestor.class);

        service(tempDir, embeddingModel, Map.of(
                CodeGenTypeEnum.HTML, htmlStore,
                CodeGenTypeEnum.MULTI_FILE, multiStore
        ), vueIngestor).ingestAll();

        verify(embeddingModel).embed("HTML 检索描述");
        verify(embeddingModel).embed("多文件检索描述");
        verify(htmlStore).add(any(Embedding.class), any(TextSegment.class));
        verify(multiStore).add(any(Embedding.class), any(TextSegment.class));
        verify(vueIngestor, never()).ingest(any(Path.class), any());
    }

    private TemplateIngestService service(Path root,
                                          EmbeddingModel embeddingModel,
                                          Map<CodeGenTypeEnum, EmbeddingStore<TextSegment>> stores,
                                          VueKnowledgeIngestor vueIngestor) {
        RagProperties properties = new RagProperties();
        properties.setTemplatesDir(root.toString());
        return new TemplateIngestService(embeddingModel, stores, properties, vueIngestor);
    }

    private void writeLegacyDocument(Path path, String id, String embedText) throws IOException {
        ObjectNode document = objectMapper.createObjectNode();
        document.put("id", id);
        document.put("title", id);
        document.put("embedText", embedText);
        document.putArray("files").addObject()
                .put("path", "index.html")
                .put("content", "<main>旧摄取路径</main>");
        objectMapper.writeValue(path.toFile(), document);
    }

    @SuppressWarnings("unchecked")
    private EmbeddingStore<TextSegment> store() {
        return mock(EmbeddingStore.class);
    }
}
