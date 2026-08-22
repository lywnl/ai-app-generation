package com.lyw.appgeneration.rag.vue;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VueEvaluationEnvironmentTest {

    @Test
    void doesNotProbeNetworkWhenEvaluationIsDisabledOrCredentialsAreMissing() {
        CountingPortProbe disabledProbe = new CountingPortProbe(true);
        VueEvaluationEnvironment disabled = VueEvaluationEnvironment.inspect(
                Map.of(), disabledProbe);
        CountingPortProbe missingProbe = new CountingPortProbe(true);
        VueEvaluationEnvironment missing = VueEvaluationEnvironment.inspect(
                Map.of(
                        "RAG_EVAL", "true",
                        "DASHSCOPE_API_KEY", "dashscope-secret",
                        "SPRING_DATASOURCE_PASSWORD", "mysql-secret"), missingProbe);

        assertFalse(disabled.ready());
        assertTrue(disabled.reasons().contains("RAG_EVAL 未设置为 true"));
        assertFalse(missing.ready());
        assertTrue(missing.reasons().stream().anyMatch(reason -> reason.contains("RAG_MILVUS_PASSWORD")));
        assertTrue(disabledProbe.calls == 0);
        assertTrue(missingProbe.calls == 0);
    }

    @Test
    void requiresReachableMilvusAfterRequiredVariablesExist() {
        Map<String, String> environment = Map.of(
                "RAG_EVAL", "true",
                "DASHSCOPE_API_KEY", "secret-not-rendered",
                "SPRING_DATASOURCE_PASSWORD", "mysql-secret",
                "RAG_MILVUS_PASSWORD", "milvus-secret");

        VueEvaluationEnvironment unreachable = VueEvaluationEnvironment.inspect(
                environment, new CountingPortProbe(false));
        VueEvaluationEnvironment ready = VueEvaluationEnvironment.inspect(
                environment, new CountingPortProbe(true));

        assertFalse(unreachable.ready());
        assertTrue(unreachable.reasons().stream().anyMatch(reason -> reason.contains("Milvus")));
        assertTrue(ready.ready());
        assertTrue(ready.reasons().isEmpty());
        assertFalse(unreachable.toString().contains("secret-not-rendered"));
        assertFalse(ready.toString().contains("secret-not-rendered"));
        assertFalse(unreachable.toString().contains("mysql-secret"));
        assertFalse(ready.toString().contains("milvus-secret"));
    }

    private static final class CountingPortProbe implements VueEvaluationEnvironment.PortProbe {

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
