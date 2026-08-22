package com.lyw.appgeneration.rag.ingest;

import java.util.Map;

/**
 * Vue 知识摄取使用的非秘密 Milvus 连接目标。
 */
public record VueMilvusTarget(String host, int port, String database, String username) {

    static final String DEFAULT_HOST = "127.0.0.1";
    static final int DEFAULT_PORT = 19530;
    private static final String DEFAULT_DATABASE = "default";
    private static final String DEFAULT_USERNAME = "root";

    public static VueMilvusTarget from(Map<String, String> environment) {
        return new VueMilvusTarget(
                valueOrDefault(environment.get("RAG_MILVUS_HOST"), DEFAULT_HOST),
                validPortOrDefault(environment.get("RAG_MILVUS_PORT")),
                valueOrDefault(environment.get("RAG_MILVUS_DATABASE"), DEFAULT_DATABASE),
                valueOrDefault(environment.get("RAG_MILVUS_USERNAME"), DEFAULT_USERNAME));
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
