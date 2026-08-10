package com.lyw.appgeneration.service.rag.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class VueRetrievalResourceProviderSpringTest {

    @Test
    void springContainerCreatesProviderThroughProductionConstructor() {
        RagProperties properties = new RagProperties();

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(RagProperties.class, () -> properties);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(VueRetrievalResourceProvider.class);

            assertDoesNotThrow(context::refresh);
            assertNotNull(context.getBean(VueRetrievalResourceProvider.class));
        }
    }
}
