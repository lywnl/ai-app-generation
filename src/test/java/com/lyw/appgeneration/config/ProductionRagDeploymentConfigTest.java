package com.lyw.appgeneration.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionRagDeploymentConfigTest {

    @Test
    void 生产Hybrid开关默认关闭且仅在三项真实门禁通过后按顺序开启() throws IOException {
        String compose = readProductionFile("docker-compose.yml");
        String backend = backendSection(compose);
        String environmentExample = readProductionFile(".env.example");
        String readme = readProductionFile("README.md");

        assertEquals(List.of("RAG_HYBRID_ENABLED: ${RAG_HYBRID_ENABLED:-false}"),
                matchingLines(backend, "RAG_HYBRID_ENABLED"));
        assertEquals(List.of("RAG_HYBRID_ENABLED=false"),
                matchingLines(environmentExample, "RAG_HYBRID_ENABLED"));

        int ingestion = readme.indexOf("正式 23 条摄取并物理核验通过");
        int retrieval = readme.indexOf("30 条真实检索达标");
        int generation = readme.indexOf("十条首次生成 10/10");
        int enable = readme.indexOf("RAG_HYBRID_ENABLED=true");
        int restart = readme.indexOf("重启 backend");

        assertTrue(ingestion >= 0, "README 必须要求正式摄取与物理核验通过");
        assertTrue(retrieval > ingestion, "真实检索必须在正式摄取之后达标");
        assertTrue(generation > retrieval, "十条首次生成必须在真实检索之后达到 10/10");
        assertTrue(enable > generation, "只能在三项真实成绩之后设置开关为 true");
        assertTrue(restart > enable, "设置开关后必须重启 backend");
        assertTrue(readme.contains("任一步失败都保持 `RAG_HYBRID_ENABLED=false`。"));
        assertTrue(readme.contains("默认 Maven、PGVector 协议探针、五骨架策展构建都不能替代"
                + "以上三项真实成绩，也不得据此开启 Hybrid。"));
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

    private String backendSection(String compose) {
        int backendStart = compose.indexOf("\n  backend:\n");
        int mysqlStart = compose.indexOf("\n  mysql:\n");

        assertTrue(backendStart >= 0, "Compose 必须包含 backend 服务");
        assertTrue(mysqlStart > backendStart, "Compose 中 backend 后必须存在 mysql 服务");
        return compose.substring(backendStart, mysqlStart);
    }

    private List<String> matchingLines(String content, String variableName) {
        return content.lines()
                .map(String::strip)
                .filter(line -> line.contains(variableName))
                .toList();
    }
}
