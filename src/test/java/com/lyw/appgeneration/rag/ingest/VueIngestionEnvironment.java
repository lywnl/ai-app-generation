package com.lyw.appgeneration.rag.ingest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Vue 知识摄取的无秘密环境前置检查。
 */
public record VueIngestionEnvironment(
        boolean ready,
        List<String> reasons,
        VuePgVectorTarget target) {

    public VueIngestionEnvironment {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public static VueIngestionEnvironment inspect(Map<String, String> environment) {
        return inspect(environment, new SocketPortProbe(Duration.ofSeconds(1)));
    }

    static VueIngestionEnvironment inspect(Map<String, String> environment, PortProbe probe) {
        VuePgVectorTarget target = VuePgVectorTarget.from(environment);
        if (!"true".equalsIgnoreCase(environment.get("RAG_VUE_INGEST"))) {
            return new VueIngestionEnvironment(
                    false, List.of("RAG_VUE_INGEST 未设置为 true"), target);
        }

        List<String> reasons = new ArrayList<>();
        require(environment, "DASHSCOPE_API_KEY", reasons);
        require(environment, "RAG_PGVECTOR_PASSWORD", reasons);
        if (reasons.isEmpty() && !probe.isReachable(target.host(), target.port())) {
            reasons.add("PGVector 端口不可达: " + target.host() + ":" + target.port());
        }
        return new VueIngestionEnvironment(reasons.isEmpty(), reasons, target);
    }

    private static void require(Map<String, String> environment, String name, List<String> reasons) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            reasons.add("缺少环境变量 " + name);
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
