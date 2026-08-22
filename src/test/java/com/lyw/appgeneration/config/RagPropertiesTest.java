package com.lyw.appgeneration.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagPropertiesTest {

    @Test
    void hybridRetrievalIsEnabledByDefault() {
        assertTrue(new RagProperties().getHybrid().isEnabled());
    }

    @Test
    void Milvus默认协议与应用配置保持一致() {
        RagProperties.Milvus milvus = new RagProperties().getMilvus();

        assertTrue("localhost".equals(milvus.getHost()));
        assertTrue(milvus.getPort() == 19530);
        assertTrue("default".equals(milvus.getDatabase()));
        assertTrue("root".equals(milvus.getUsername()));
    }

    @Test
    void applicationYamlExplicitlyEnablesHybridByDefault() throws IOException {
        try (var input = getClass().getResourceAsStream("/application.yml")) {
            String yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(yaml.contains("hybrid:\n    enabled: ${RAG_HYBRID_ENABLED:true}"));
        }
    }

    @Test
    void applicationYaml只使用Milvus配置() throws IOException {
        try (var input = getClass().getResourceAsStream("/application.yml")) {
            String yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(yaml.contains("milvus:"));
            assertFalse(yaml.contains("pg" + "vector:"));
            assertFalse(yaml.contains("RAG_PG" + "VECTOR_"));
        }
    }
}
