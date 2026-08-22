package com.lyw.appgeneration.rag.vue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 高成本 Vue 检索评测的无秘密环境前置检查。
 */
public record VueEvaluationEnvironment(boolean ready, List<String> reasons) {

    private static final String DEFAULT_MILVUS_HOST = "127.0.0.1";
    private static final int DEFAULT_MILVUS_PORT = 19530;

    public VueEvaluationEnvironment {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public static VueEvaluationEnvironment inspect(Map<String, String> environment) {
        return inspect(environment, new SocketPortProbe(Duration.ofSeconds(1)));
    }

    static VueEvaluationEnvironment inspect(
            Map<String, String> environment,
            PortProbe portProbe) {
        List<String> reasons = new ArrayList<>();
        if (!"true".equalsIgnoreCase(environment.get("RAG_EVAL"))) {
            reasons.add("RAG_EVAL 未设置为 true");
            return new VueEvaluationEnvironment(false, reasons);
        }
        requireEnvironment(environment, "DASHSCOPE_API_KEY", reasons);
        requireEnvironment(environment, "RAG_MILVUS_PASSWORD", reasons);
        if (!reasons.isEmpty()) {
            return new VueEvaluationEnvironment(false, reasons);
        }

        String host = nonBlankOrDefault(environment.get("RAG_MILVUS_HOST"), DEFAULT_MILVUS_HOST);
        int port = parsePort(environment.get("RAG_MILVUS_PORT"));
        if (!portProbe.isReachable(host, port)) {
            reasons.add("Milvus 端口不可达: " + host + ":" + port);
        }
        return new VueEvaluationEnvironment(reasons.isEmpty(), reasons);
    }

    private static void requireEnvironment(
            Map<String, String> environment,
            String name,
            List<String> reasons) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            reasons.add("缺少环境变量 " + name);
        }
    }

    private static String nonBlankOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int parsePort(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_MILVUS_PORT;
        }
        try {
            int port = Integer.parseInt(value);
            return port > 0 && port <= 65_535 ? port : DEFAULT_MILVUS_PORT;
        } catch (NumberFormatException exception) {
            return DEFAULT_MILVUS_PORT;
        }
    }

    @FunctionalInterface
    interface PortProbe {
        boolean isReachable(String host, int port);
    }

    private record SocketPortProbe(Duration timeout) implements PortProbe {

        @Override
        public boolean isReachable(String host, int port) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), Math.toIntExact(timeout.toMillis()));
                return true;
            } catch (IOException | RuntimeException exception) {
                return false;
            }
        }
    }
}
