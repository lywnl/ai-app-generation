package com.lyw.appgeneration.rag.build;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VueGenerationBuildEnvironmentTest {

    @Test
    void disabledEvaluationDoesNotRequireOrExposeCredentials() {
        VueGenerationBuildEnvironment environment = VueGenerationBuildEnvironment.inspect(Map.of(
                "DASHSCOPE_API_KEY", "dashscope-secret",
                "DEEPSEEK_API_KEY", "deepseek-secret",
                "SPRING_DATASOURCE_PASSWORD", "mysql-secret",
                "RAG_PGVECTOR_PASSWORD", "pg-secret"));

        assertFalse(environment.ready());
        assertTrue(environment.reasons().contains("RAG_BUILD_EVAL 未设置为 true"));
        assertFalse(environment.toString().contains("dashscope-secret"));
        assertFalse(environment.toString().contains("deepseek-secret"));
        assertFalse(environment.toString().contains("mysql-secret"));
        assertFalse(environment.toString().contains("pg-secret"));
    }

    @Test
    void enabledEvaluationRequiresEveryExternalCredential() {
        VueGenerationBuildEnvironment missing = VueGenerationBuildEnvironment.inspect(Map.of(
                "RAG_BUILD_EVAL", "true"));
        VueGenerationBuildEnvironment ready = VueGenerationBuildEnvironment.inspect(Map.of(
                "RAG_BUILD_EVAL", "TRUE",
                "DASHSCOPE_API_KEY", "dashscope-secret",
                "DEEPSEEK_API_KEY", "deepseek-secret",
                "SPRING_DATASOURCE_PASSWORD", "mysql-secret",
                "RAG_PGVECTOR_PASSWORD", "pg-secret"));

        assertFalse(missing.ready());
        assertTrue(missing.reasons().contains("缺少环境变量 DASHSCOPE_API_KEY"));
        assertTrue(missing.reasons().contains("缺少环境变量 DEEPSEEK_API_KEY"));
        assertTrue(missing.reasons().contains("缺少环境变量 RAG_PGVECTOR_PASSWORD"));
        assertTrue(ready.ready());
        assertTrue(ready.reasons().isEmpty());
        assertFalse(ready.toString().contains("dashscope-secret"));
        assertFalse(ready.toString().contains("deepseek-secret"));
        assertFalse(ready.toString().contains("mysql-secret"));
        assertFalse(ready.toString().contains("pg-secret"));
    }
}
