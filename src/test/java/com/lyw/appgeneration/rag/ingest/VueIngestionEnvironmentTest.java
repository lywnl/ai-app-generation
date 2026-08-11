package com.lyw.appgeneration.rag.ingest;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VueIngestionEnvironmentTest {

    @Test
    void 默认关闭或缺少凭据时不探测网络且不泄漏秘密() {
        CountingPortProbe disabledProbe = new CountingPortProbe(true);
        VueIngestionEnvironment disabled = VueIngestionEnvironment.inspect(Map.of(), disabledProbe);
        CountingPortProbe missingProbe = new CountingPortProbe(true);
        VueIngestionEnvironment missing = VueIngestionEnvironment.inspect(Map.of(
                "RAG_VUE_INGEST", "true",
                "DASHSCOPE_API_KEY", "dashscope-secret"), missingProbe);

        assertFalse(disabled.ready());
        assertTrue(disabled.reasons().contains("RAG_VUE_INGEST 未设置为 true"));
        assertFalse(missing.ready());
        assertTrue(missing.reasons().contains("缺少环境变量 SPRING_DATASOURCE_PASSWORD"));
        assertEquals(0, disabledProbe.calls);
        assertEquals(0, missingProbe.calls);
        assertFalse(disabled.toString().contains("dashscope-secret"));
        assertFalse(missing.toString().contains("dashscope-secret"));
    }

    @Test
    void 凭据存在后检查端口并解析非秘密目标() {
        Map<String, String> environment = Map.of(
                "RAG_VUE_INGEST", "true",
                "DASHSCOPE_API_KEY", "dashscope-secret",
                "SPRING_DATASOURCE_PASSWORD", "database-secret",
                "RAG_PGVECTOR_HOST", "db.internal",
                "RAG_PGVECTOR_PORT", "15432",
                "RAG_PGVECTOR_DATABASE", "rag_test",
                "RAG_PGVECTOR_USER", "rag_user");

        VueIngestionEnvironment result = VueIngestionEnvironment.inspect(
                environment, (host, port) -> true);

        assertTrue(result.ready());
        assertEquals("jdbc:postgresql://db.internal:15432/rag_test", result.target().jdbcUrl());
        assertEquals("db.internal:15432/rag_test", result.target().displayName());
        assertEquals("rag_user", result.target().user());
        assertFalse(result.toString().contains("dashscope-secret"));
        assertFalse(result.toString().contains("database-secret"));
    }

    private static final class CountingPortProbe implements VueIngestionEnvironment.PortProbe {

        private final boolean reachable;
        private int calls;

        private CountingPortProbe(boolean reachable) {
            this.reachable = reachable;
        }

        @Override
        public boolean isReachable(String host, int port) {
            calls++;
            return reachable;
        }
    }
}
