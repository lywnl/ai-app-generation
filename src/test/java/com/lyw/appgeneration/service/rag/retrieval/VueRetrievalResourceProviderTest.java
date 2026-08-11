package com.lyw.appgeneration.service.rag.retrieval;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.service.rag.catalog.TemplateCatalog;
import com.lyw.appgeneration.service.rag.support.TemplateTestData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

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

    @Test
    void initializationFailureLogsNeverExposeExceptionType(@TempDir Path tempDir) throws IOException {
        Logger logger = (Logger) LoggerFactory.getLogger(VueRetrievalResourceProvider.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            RagProperties invalidPath = new RagProperties();
            invalidPath.setTemplatesDir("\0");
            new VueRetrievalResourceProvider(invalidPath, new ObjectMapper());

            Path vueRoot = tempDir.resolve("vue-project");
            TemplateTestData.write(
                    vueRoot.resolve("features/login.json"),
                    TemplateTestData.featureDocument("feature-login"));
            RagProperties bm25Failure = new RagProperties();
            bm25Failure.setTemplatesDir(tempDir.toString());
            new VueRetrievalResourceProvider(
                    bm25Failure, new ObjectMapper(), catalog -> {
                        throw new IOException("敏感异常消息");
                    });

            Bm25Retriever closeFailure = mock(Bm25Retriever.class);
            doThrow(new IOException("关闭时的敏感异常消息")).when(closeFailure).close();
            VueRetrievalResourceProvider closeProvider = new VueRetrievalResourceProvider(
                    bm25Failure, new ObjectMapper(), catalog -> closeFailure);
            closeProvider.close();

            String logs = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertFalse(logs.contains("exceptionType"));
            assertFalse(logs.contains("IllegalArgumentException"));
            assertFalse(logs.contains("IOException"));
            assertFalse(logs.contains("敏感异常消息"));
            assertFalse(logs.contains("关闭时的敏感异常消息"));
            assertTrue(appender.list.stream().allMatch(event -> event.getThrowableProxy() == null));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
