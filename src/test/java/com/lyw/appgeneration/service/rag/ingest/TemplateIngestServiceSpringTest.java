package com.lyw.appgeneration.service.rag.ingest;

import com.lyw.appgeneration.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class TemplateIngestServiceSpringTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withBean(RagProperties.class, RagProperties::new)
                    .withBean("embeddingStoreByType", Map.class, Map::of)
                    .withBean(NativeTemplateIngestor.class,
                            () -> mock(NativeTemplateIngestor.class))
                    .withBean(VueKnowledgeIngestor.class,
                            () -> mock(VueKnowledgeIngestor.class))
                    .withUserConfiguration(TemplateIngestService.class);

    @Test
    void Rag总开关关闭时即使误开摄取也不得创建摄取服务() {
        contextRunner.withPropertyValues(
                        "rag.enabled=false",
                        "rag.ingest.enabled=true")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.containsBean("templateIngestService"));
                });
    }

    @Test
    void Rag与摄取开关同时开启时必须创建摄取服务() {
        contextRunner.withPropertyValues(
                        "rag.enabled=true",
                        "rag.ingest.enabled=true")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.containsBean("templateIngestService"));
                });
    }
}
