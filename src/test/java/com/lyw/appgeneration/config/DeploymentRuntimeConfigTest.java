package com.lyw.appgeneration.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeploymentRuntimeConfigTest {

    @Test
    void 开发Nginx必须在80端口托管工作树部署目录() throws IOException {
        String compose = readProjectFile("dev/docker-compose.yml");
        String nginx = readProjectFile("dev/nginx/nginx.conf");

        assertTrue(compose.contains("127.0.0.1:80:80"));
        assertTrue(compose.contains("../tmp/code_deploy:/opt/code_deploy:ro"));
        assertTrue(nginx.contains("listen 80;"));
        assertTrue(nginx.contains("root /opt/code_deploy;"));
        assertTrue(nginx.contains("^/([A-Za-z0-9]{6})/(.*)$"));
        assertTrue(nginx.indexOf("root /opt/code_deploy;")
                < nginx.indexOf("location / {"),
                "部署产物路由必须先于 Vite 兜底路由");
    }

    @Test
    void 生产Nginx对外和容器内都只能使用80端口() throws IOException {
        String compose = readProjectFile("prod/docker-compose.yml");
        String nginx = readProjectFile("prod/nginx/nginx.conf");
        String dockerfile = readProjectFile("prod/docker/Dockerfile.nginx");
        String env = readProjectFile("prod/.env");
        String envExample = readProjectFile("prod/.env.example");

        assertTrue(compose.contains("- \"80:80\""));
        assertFalse(compose.contains("NGINX_HOST_PORT"));
        assertEquals(List.of("listen 80;"), nginx.lines()
                .map(String::strip)
                .filter(line -> line.startsWith("listen "))
                .toList());
        assertEquals(List.of("EXPOSE 80"), dockerfile.lines()
                .map(String::strip)
                .filter(line -> line.startsWith("EXPOSE "))
                .toList());
        assertFalse(env.contains("NGINX_HOST_PORT"));
        assertFalse(envExample.contains("NGINX_HOST_PORT"));
    }

    @Test
    void 生产部署URL只能由后端新环境变量提供() throws IOException {
        List<String> runtimeFiles = List.of(
                "prod/docker-compose.yml",
                "prod/.env",
                "prod/.env.example",
                "prod/build-artifacts.ps1");

        for (String file : runtimeFiles) {
            String content = readProjectFile(file);
            assertFalse(content.contains("APP_CODE_DEPLOY_HOST"), file);
            assertFalse(content.contains("VITE_DEPLOY_DOMAIN"), file);
        }
        String compose = readProjectFile("prod/docker-compose.yml");
        assertTrue(compose.contains(
                "APP_CODE_DEPLOY_BASE_URL: ${APP_CODE_DEPLOY_BASE_URL}"));
        assertEquals(List.of("APP_CODE_DEPLOY_BASE_URL="),
                matchingLines(
                        readProjectFile("prod/.env.example"),
                        "APP_CODE_DEPLOY_BASE_URL"));
    }

    @Test
    void 应用运行配置不能为部署基地址提供默认值() throws IOException {
        String application = readProjectFile(
                "src/main/resources/application.yml");

        assertTrue(application.contains(
                "base-url: ${APP_CODE_DEPLOY_BASE_URL}"));
        assertFalse(application.contains(
                "base-url: ${APP_CODE_DEPLOY_BASE_URL:"));
    }

    private List<String> matchingLines(String content, String variable) {
        return content.lines()
                .map(String::strip)
                .filter(line -> line.startsWith(variable + "="))
                .toList();
    }

    private String readProjectFile(String relativePath) throws IOException {
        return Files.readString(projectRoot().resolve(relativePath),
                StandardCharsets.UTF_8);
    }

    private Path projectRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("找不到包含 pom.xml 的项目根目录");
    }
}
