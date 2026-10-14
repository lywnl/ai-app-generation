package com.lyw.appgeneration.config;

import com.lyw.appgeneration.ai.AiCodeGenTypeRoutingService;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RoutingAiModelConfigTest {

    private static final String RESPONSE = """
            {"id":"compression-test","object":"chat.completion","model":"deepseek-flash",
            "choices":[{"index":0,"message":{"role":"assistant","content":"VUE_PROJECT"},"finish_reason":"stop"}],
            "usage":{"prompt_tokens":10,"completion_tokens":3,"total_tokens":13}}
            """;

    // 上述响应的 Brotli 压缩版本；错误地协商 br 时复现预读后只剩首字符的故障。
    private static final byte[] BROTLI_RESPONSE = Base64.getDecoder().decode(
            "G/cAABwHdpPPi80mPMphNJYImlvql38wWcpaRJZFxOE0feqw9GXRF5nOrZIqoTQS"
                    + "bM5mOzA3qzOSLMYo5NpKAQ7mM6vJfzoH4VZghRwDeOLxMYnLKAAAzXEVsSn3d7Mn"
                    + "qYcaBi4SvIm9pGip2FZ9ddw4eLd30MAmio/wd7yM97nH1+3Ak6WaVkt5C9w0KLVoDw==");

    @ParameterizedTest(name = "路由完整读取 {0} 压缩响应")
    @ValueSource(strings = {"gzip", "deflate"})
    @Timeout(15)
    void 路由调用避开Brotli并完整解析压缩响应(String encoding) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> acceptedEncoding = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        byte[] compatibleResponse = compressResponse(encoding);
        server.createContext("/chat/completions", exchange -> {
            try {
                calls.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                String accepted = exchange.getRequestHeaders().getFirst("Accept-Encoding");
                acceptedEncoding.set(accepted);
                boolean brotli = accepted != null
                        && accepted.toLowerCase(Locale.ROOT).contains("br");
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.getResponseHeaders().add("Content-Encoding", brotli ? "br" : encoding);
                // 模拟实际模型接口的分块传输，不依赖固定 Content-Length。
                exchange.sendResponseHeaders(200, 0);
                try (var output = exchange.getResponseBody()) {
                    output.write(brotli ? BROTLI_RESPONSE : compatibleResponse);
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            RoutingAiModelConfig config = new RoutingAiModelConfig();
            config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            config.setApiKey("test-key");
            config.setModelName("deepseek-flash");
            config.setMaxTokens(256);
            config.setTemperature(0.0);
            AiCodeGenTypeRoutingService routing = AiServices.builder(AiCodeGenTypeRoutingService.class)
                    .chatModel(config.routingChatModelPrototype())
                    .build();

            assertEquals(CodeGenTypeEnum.VUE_PROJECT,
                    routing.routeCodeGenType("创建包含文章列表、详情页和搜索的个人博客"));
            assertEquals(1, calls.get(), "压缩响应应一次读取成功，无需重试");
            assertFalse(acceptedEncoding.get().toLowerCase(Locale.ROOT).contains("br"),
                    "模型请求不能协商存在兼容问题的 Brotli 解码路径");
        } finally {
            server.stop(0);
        }
    }

    private static byte[] compressResponse(String encoding) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DeflaterOutputStream compressor = "gzip".equals(encoding)
                ? new GZIPOutputStream(output) : new DeflaterOutputStream(output)) {
            compressor.write(RESPONSE.getBytes(StandardCharsets.UTF_8));
        }
        return output.toByteArray();
    }
}
