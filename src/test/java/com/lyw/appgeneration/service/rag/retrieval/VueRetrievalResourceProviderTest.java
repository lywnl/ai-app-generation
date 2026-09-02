package com.lyw.appgeneration.service.rag.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.service.rag.catalog.TemplateCatalog;
import com.lyw.appgeneration.service.rag.support.TemplateTestData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VueRetrievalResourceProviderTest {

    @Test
    void 使用已加载目录时复用同一快照实例(@TempDir Path tempDir) throws IOException {
        Path vueRoot = tempDir.resolve("vue-project");
        TemplateTestData.write(
                vueRoot.resolve("features/login.json"),
                TemplateTestData.featureDocument("feature-login"));
        TemplateCatalog catalog = new TemplateCatalog(vueRoot, new ObjectMapper());

        VueRetrievalResourceProvider provider = new VueRetrievalResourceProvider(catalog);

        assertSame(catalog, provider.current().orElseThrow().catalog());
        provider.close();
    }

    @Test
    void degradesToUnavailableResourcesWhenTemplatePathIsInvalid() {
        RagProperties properties = new RagProperties();
        properties.setTemplatesDir("\0");

        VueRetrievalResourceProvider provider = assertDoesNotThrow(
                () -> new VueRetrievalResourceProvider(properties, new ObjectMapper()));

        assertTrue(provider.current().isEmpty());
    }

    @Test
    void Rag总开关关闭时不得加载模板() {
        RagProperties properties = new RagProperties();
        properties.setEnabled(false);
        properties.setTemplatesDir("\0");

        VueRetrievalResourceProvider provider =
                new VueRetrievalResourceProvider(properties, new ObjectMapper());

        assertTrue(provider.current().isEmpty());
    }
}
