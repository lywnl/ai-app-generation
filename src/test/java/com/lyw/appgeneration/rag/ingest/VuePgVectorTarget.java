package com.lyw.appgeneration.rag.ingest;

import java.util.Map;

/**
 * Vue 知识摄取使用的非秘密 PGVector 连接目标。
 */
public record VuePgVectorTarget(String host, int port, String database, String user) {

    static final String DEFAULT_HOST = "127.0.0.1";
    static final int DEFAULT_PORT = 5432;
    private static final String DEFAULT_DATABASE = "ai_codegen_rag";
    private static final String DEFAULT_USER = "admin";

    public static VuePgVectorTarget from(Map<String, String> environment) {
        return new VuePgVectorTarget(
                valueOrDefault(environment.get("RAG_PGVECTOR_HOST"), DEFAULT_HOST),
                validPortOrDefault(environment.get("RAG_PGVECTOR_PORT")),
                valueOrDefault(environment.get("RAG_PGVECTOR_DATABASE"), DEFAULT_DATABASE),
                valueOrDefault(environment.get("RAG_PGVECTOR_USER"), DEFAULT_USER));
    }

    public String jdbcUrl() {
        return "jdbc:postgresql://%s:%d/%s".formatted(host, port, database);
    }

    public String displayName() {
        return "%s:%d/%s".formatted(host, port, database);
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int validPortOrDefault(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            int port = Integer.parseInt(value);
            return port >= 1 && port <= 65_535 ? port : DEFAULT_PORT;
        } catch (NumberFormatException exception) {
            return DEFAULT_PORT;
        }
    }
}
