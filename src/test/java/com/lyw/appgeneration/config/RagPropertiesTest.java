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
    void Rerank默认超时为五秒() {
        assertTrue(new RagProperties().getRerank().getTimeoutMs() == 5000L);
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
    void applicationYaml将Rerank超时配置为五秒() throws IOException {
        try (var input = getClass().getResourceAsStream("/application.yml")) {
            String yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(yaml.contains("rerank:\n    enabled: true"));
            assertTrue(yaml.contains("doc-char-limit: 2000\n    timeout-ms: 5000"));
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
