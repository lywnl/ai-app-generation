package com.lyw.appgeneration.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryDependencyTimeoutConfigTest {

    @Test
    void 记忆依赖超时必须短于六十秒压缩总截止() throws IOException {
        String application = readProjectFile(
                "src/main/resources/application.yml");
        String productionCompose = readProjectFile(
                "prod/docker-compose.yml");

        assertTrue(application.contains("connectTimeout=5000"));
        assertTrue(application.contains("socketTimeout=30000"));
        assertTrue(application.contains("connect-timeout: 3s"));
        assertTrue(application.contains("timeout: 3s"));
        assertTrue(productionCompose.contains("connectTimeout=5000"));
        assertTrue(productionCompose.contains("socketTimeout=30000"));
    }

    private String readProjectFile(String relativePath) throws IOException {
        return Files.readString(projectRoot().resolve(relativePath),
                StandardCharsets.UTF_8);
    }

    private Path projectRoot() {
        Path candidate = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("找不到包含 pom.xml 的项目根目录");
    }
}
