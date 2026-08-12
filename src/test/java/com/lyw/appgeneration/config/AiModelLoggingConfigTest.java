package com.lyw.appgeneration.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiModelLoggingConfigTest {

    private static final String OPEN_AI_PREFIX = "langchain4j.open-ai.";
    private static final List<String> MODEL_PREFIXES = List.of(
            "chat-model",
            "streaming-chat-model",
            "reasoning-streaming-chat-model",
            "routing-chat-model");

    @Test
    void 所有模型正文日志默认关闭且只能由显式本地开关启用() throws IOException {
        PropertySource<?> properties = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .getFirst();

        for (String modelPrefix : MODEL_PREFIXES) {
            String prefix = OPEN_AI_PREFIX + modelPrefix;
            assertEquals("${AI_MODEL_LOG_REQUESTS:false}",
                    properties.getProperty(prefix + ".log-requests"));
            assertEquals("${AI_MODEL_LOG_RESPONSES:false}",
                    properties.getProperty(prefix + ".log-responses"));
        }
    }

    @Test
    void 生产部署显式关闭模型请求和响应正文日志() throws IOException {
        String compose = readProductionFile("docker-compose.yml");
        String environmentExample = readProductionFile(".env.example");

        assertEquals(List.of("AI_MODEL_LOG_REQUESTS: \"false\""),
                matchingLines(compose, "AI_MODEL_LOG_REQUESTS"));
        assertEquals(List.of("AI_MODEL_LOG_RESPONSES: \"false\""),
                matchingLines(compose, "AI_MODEL_LOG_RESPONSES"));
        assertEquals(List.of(),
                matchingLines(environmentExample, "AI_MODEL_LOG_REQUESTS"));
        assertEquals(List.of(),
                matchingLines(environmentExample, "AI_MODEL_LOG_RESPONSES"));
        assertTrue(isInsideBackendEnvironment(compose, "AI_MODEL_LOG_REQUESTS"));
        assertTrue(isInsideBackendEnvironment(compose, "AI_MODEL_LOG_RESPONSES"));
    }

    private String readProductionFile(String fileName) throws IOException {
        return Files.readString(projectRoot().resolve("prod").resolve(fileName),
                StandardCharsets.UTF_8);
    }

    private Path projectRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))
                    && Files.isDirectory(candidate.resolve("prod"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("找不到包含 prod 目录的项目根目录");
    }

    private List<String> matchingLines(String content, String variableName) {
        return content.lines()
                .map(String::strip)
                .filter(line -> !line.startsWith("#"))
                .filter(line -> line.contains(variableName))
                .toList();
    }

    private boolean isInsideBackendEnvironment(String compose, String variableName) {
        boolean insideBackend = false;
        boolean insideEnvironment = false;
        for (String line : compose.split("\\R")) {
            String stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                continue;
            }
            int indentation = line.indexOf(stripped);
            if (indentation == 2) {
                insideBackend = stripped.equals("backend:");
                insideEnvironment = false;
                continue;
            }
            if (!insideBackend) {
                continue;
            }
            if (indentation == 4) {
                insideEnvironment = stripped.equals("environment:");
                continue;
            }
            if (insideEnvironment && indentation > 4 && stripped.contains(variableName)) {
                return true;
            }
        }
        return false;
    }
}
