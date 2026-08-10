package com.lyw.appgeneration.service.rag.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.service.rag.support.TemplateTestData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VueRetrievalResourceProviderTest {

    @Test
    void degradesToUnavailableResourcesWhenTemplatePathIsInvalid() {
        RagProperties properties = new RagProperties();
        properties.setTemplatesDir("\0");

        VueRetrievalResourceProvider provider = assertDoesNotThrow(
                () -> new VueRetrievalResourceProvider(properties, new ObjectMapper()));

        assertTrue(provider.current().isEmpty());
    }

    @Test
    void keepsCatalogAvailableWhenBm25InitializationFails(@TempDir Path tempDir) throws IOException {
        Path vueRoot = tempDir.resolve("vue-project");
        TemplateTestData.write(
                vueRoot.resolve("features/login.json"),
                TemplateTestData.featureDocument("feature-login"));
        RagProperties properties = new RagProperties();
        properties.setTemplatesDir(tempDir.toString());

        VueRetrievalResourceProvider provider = new VueRetrievalResourceProvider(
                properties, new ObjectMapper(), catalog -> {
                    throw new IOException("BM25 初始化失败");
                });

        VueRetrievalResources resources = provider.current().orElseThrow();
        assertTrue(resources.catalog().findDocumentById("feature-login").isPresent());
        assertTrue(resources.bm25Retriever().isEmpty());
    }
}
