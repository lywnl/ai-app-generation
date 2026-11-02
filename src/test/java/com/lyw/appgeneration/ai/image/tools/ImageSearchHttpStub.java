package com.lyw.appgeneration.ai.image.tools;

import com.sun.net.httpserver.HttpServer;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 只绑定回环地址的 Pexels 替身，记录真实客户端发出的请求。 */
final class ImageSearchHttpStub implements AutoCloseable {
    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile Function<Map<String, String>, Response> responder = ignored -> new Response(200, "{}");

    ImageSearchHttpStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/search", exchange -> {
            try (exchange) {
                String rawQuery = exchange.getRequestURI().getRawQuery();
                Map<String, String> parameters = rawQuery == null ? Map.of() : Arrays.stream(rawQuery.split("&"))
                        .map(pair -> pair.split("=", 2))
                        .collect(Collectors.toMap(pair -> decode(pair[0]),
                                pair -> pair.length == 2 ? decode(pair[1]) : ""));
                requests.add(new Request(exchange.getRequestMethod(),
                        exchange.getRequestHeaders().getFirst("Authorization"), parameters));
                Response response = responder.apply(parameters);
                byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(response.status(), body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
    }

    ImageSearchTool tool() {
        ImageSearchTool tool = new ImageSearchTool("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/search");
        ReflectionTestUtils.setField(tool, "pexelsApiKey", "test-key");
        return tool;
    }

    void respond(int status, String body) {
        responder = ignored -> new Response(status, body);
    }

    void respond(Function<Map<String, String>, String> body) {
        responder = parameters -> new Response(200, body.apply(parameters));
    }

    List<Request> requests() {
        return List.copyOf(requests);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    record Request(String method, String authorization, Map<String, String> parameters) {
    }

    private record Response(int status, String body) {
    }
}
