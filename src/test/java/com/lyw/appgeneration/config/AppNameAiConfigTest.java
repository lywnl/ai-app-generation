package com.lyw.appgeneration.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.monitor.AiModelMonitorListener;
import com.lyw.appgeneration.service.AppNameService;
import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AppNameAiConfigTest {
    private final List<CloseableHttpClient> transports = new ArrayList<>();

    @AfterEach
    void closeTransports() throws Exception {
        for (var transport : transports) transport.close();
    }
    private static final String RESPONSE = """
            {"id":"name-test","object":"chat.completion","model":"name-model",
            "choices":[{"index":0,"message":{"role":"assistant","content":"个人博客"},"finish_reason":"stop"}],
            "usage":{"prompt_tokens":12,"completion_tokens":4,"total_tokens":16}}
            """;

    @Test
    void 生产配置复用路由连接但使用独立命名预算() {
        new ApplicationContextRunner().withUserConfiguration(Binding.class, AppNameAiConfig.class)
                .withBean(AiModelMonitorListener.class, () -> mock(AiModelMonitorListener.class))
                .withInitializer(context -> {
                    try {
                        var sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
                        sources.forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
                    } catch (java.io.IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                })
                .withPropertyValues("langchain4j.open-ai.routing-chat-model.api-key=test-key",
                        "langchain4j.open-ai.routing-chat-model.base-url=http://127.0.0.1:1/v1",
                        "langchain4j.open-ai.routing-chat-model.model-name=name-model")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var config = context.getBean(AppNameAiConfig.class);
                    assertEquals("test-key", config.getApiKey());
                    assertEquals("http://127.0.0.1:1/v1", config.getBaseUrl());
                    assertEquals("name-model", config.getModelName());
                    assertEquals(Duration.ofSeconds(5), config.getTimeout());
                    assertEquals(256, config.getMaxTokens());
                    assertEquals(0.0, config.getTemperature());
                    assertFalse(config.toString().contains("test-key"));
                });
    }

    @Test
    void 请求仅含独立系统提示及本次需求且协议参数正确() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<JsonNode> requests = java.util.Collections.synchronizedList(new ArrayList<>());
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(new ObjectMapper().readTree(exchange.getRequestBody()));
            byte[] body = RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            AppNameAiConfig config = config(server);
            var ai = config.aiAppNameService(model(config));
            assertEquals("个人博客", ai.generateAppName("第一次博客需求"));
            assertEquals("个人博客", ai.generateAppName("第二次商城需求"));
            assertEquals(2, requests.size());
            for (JsonNode request : requests) {
                assertEquals(256, request.path("max_tokens").asInt());
                assertEquals(0.0, request.path("temperature").asDouble());
                assertFalse(request.has("tools"));
                assertFalse(request.path("stream").asBoolean());
                assertEquals(2, request.path("messages").size());
                assertEquals("system", request.path("messages").get(0).path("role").asText());
                String system = request.path("messages").get(0).path("content").asText();
                assertTrue(system.contains("简体中文"));
                assertTrue(system.contains("明确指定"));
                assertTrue(system.contains("只输出"));
                assertTrue(system.contains("不要创造用户未提供的品牌名、诗意词语或人名"));
                assertTrue(system.contains("作品展示"));
                assertEquals("user", request.path("messages").get(1).path("role").asText());
            }
            assertEquals("第二次商城需求", requests.getLast().path("messages").get(1).path("content").asText());
            assertFalse(requests.getLast().toString().contains("第一次博客需求"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 服务端错误不自动重试() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            var config = config(server);
            assertEquals("创建个人博客", new AppNameService(config.aiAppNameService(model(config)))
                    .generateName("创建个人博客"));
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 慢响应触发客户端短超时并返回兜底() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var config = config(server);
            config.setTimeout(Duration.ofMillis(150));
            var naming = new AppNameService(config.aiAppNameService(model(config)));
            long started = System.nanoTime();
            assertEquals("创建个人博客", naming.generateName("创建个人博客"));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 2000);
            assertEquals(1, calls.get());
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    private AppNameAiConfig config(HttpServer server) {
        var config = new AppNameAiConfig();
        config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        config.setApiKey("test-key");
        config.setModelName("name-model");
        return config;
    }

    @Test
    void 禁止通过无效超时配置引入无限等待() {
        for (Duration timeout : new Duration[]{null, Duration.ZERO, Duration.ofSeconds(-1)}) {
            var config = new AppNameAiConfig();
            config.setTimeout(timeout);
            assertThrows(IllegalArgumentException.class, config::appNameTransport);
        }
    }

    @Test
    void HTTPS握手停滞也遵守命名短超时() throws Exception {
        try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
             var worker = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch release = new CountDownLatch(1);
            worker.submit(() -> {
                try (var accepted = socket.accept()) {
                    release.await(3, TimeUnit.SECONDS);
                } catch (java.io.IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            try {
                var config = new AppNameAiConfig();
                config.setBaseUrl("https://127.0.0.1:" + socket.getLocalPort() + "/v1");
                config.setApiKey("test-key");
                config.setModelName("name-model");
                config.setTimeout(Duration.ofMillis(150));
                var naming = new AppNameService(config.aiAppNameService(model(config)));
                long started = System.nanoTime();
                assertEquals("创建个人博客", naming.generateName("创建个人博客"));
                assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 2000,
                        "TLS 握手不能等到模拟服务端 3 秒后关闭才返回");
            } finally {
                release.countDown();
            }
        }
    }

    private dev.langchain4j.model.chat.ChatModel model(AppNameAiConfig config) {
        var transport = config.appNameTransport();
        transports.add(transport);
        return config.appNameChatModel(transport, mock(AiModelMonitorListener.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties
    static class Binding { }
}
