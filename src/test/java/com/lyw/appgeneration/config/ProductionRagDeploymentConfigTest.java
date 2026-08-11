package com.lyw.appgeneration.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionRagDeploymentConfigTest {

    private static final String HYBRID_ENABLED_LINE =
            "RAG_HYBRID_ENABLED: ${RAG_HYBRID_ENABLED:-false}";

    @Test
    void mysql服务位于backend之前时仍能定位Hybrid开关() {
        String compose = """
                services:
                  mysql:
                    image: mysql:8.0
                  backend:
                    environment:
                      RAG_HYBRID_ENABLED: ${RAG_HYBRID_ENABLED:-false}
                """;

        assertHybridEnabledInBackendEnvironment(compose);
    }

    @Test
    void Hybrid开关额外出现在其他服务时必须失败() {
        String compose = """
                services:
                  backend:
                    environment:
                      RAG_HYBRID_ENABLED: ${RAG_HYBRID_ENABLED:-false}
                  worker:
                    environment:
                      RAG_HYBRID_ENABLED: ${RAG_HYBRID_ENABLED:-false}
                """;

        assertThrows(AssertionError.class,
                () -> assertHybridEnabledInBackendEnvironment(compose));
    }

    @Test
    void Hybrid开关位于backend但不在environment时必须失败() {
        String compose = """
                services:
                  backend:
                    labels:
                      RAG_HYBRID_ENABLED: ${RAG_HYBRID_ENABLED:-false}
                """;

        assertThrows(AssertionError.class,
                () -> assertHybridEnabledInBackendEnvironment(compose));
    }

    @Test
    void 生产Hybrid开关默认关闭且仅在三项真实门禁通过后按顺序开启() throws IOException {
        String compose = readProductionFile("docker-compose.yml");
        String environmentExample = readProductionFile(".env.example");
        String readme = readProductionFile("README.md");

        assertHybridEnabledInBackendEnvironment(compose);
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

    private List<String> matchingLines(String content, String variableName) {
        return content.lines()
                .map(String::strip)
                .filter(line -> line.contains(variableName))
                .toList();
    }

    private void assertHybridEnabledInBackendEnvironment(String compose) {
        List<ComposeLine> lines = composeLines(compose);
        List<ComposeLine> hybridEnabledLines = lines.stream()
                .filter(line -> !line.stripped().startsWith("#"))
                .filter(line -> line.stripped().contains("RAG_HYBRID_ENABLED"))
                .toList();

        assertEquals(List.of(HYBRID_ENABLED_LINE), hybridEnabledLines.stream()
                .map(ComposeLine::stripped)
                .toList());

        ComposeBlock services = topLevelBlock(lines, "services:");
        ComposeBlock backend = childBlock(lines, services, "backend:");
        ComposeBlock environment = childBlock(lines, backend, "environment:");
        ComposeLine hybridEnabled = hybridEnabledLines.getFirst();

        assertTrue(hybridEnabled.index() > environment.startIndex()
                        && hybridEnabled.index() < environment.endIndex()
                        && hybridEnabled.indentation() > environment.indentation(),
                "RAG_HYBRID_ENABLED 必须仅位于 services.backend.environment");
    }

    private List<ComposeLine> composeLines(String compose) {
        List<ComposeLine> lines = new ArrayList<>();
        String[] rawLines = compose.split("\\R", -1);
        for (int index = 0; index < rawLines.length; index++) {
            String rawLine = rawLines[index];
            lines.add(new ComposeLine(index, rawLine.strip(), indentationOf(rawLine)));
        }
        return lines;
    }

    private ComposeBlock topLevelBlock(List<ComposeLine> lines, String key) {
        List<ComposeLine> matches = lines.stream()
                .filter(this::isConfigurationLine)
                .filter(line -> line.indentation() == 0 && line.stripped().equals(key))
                .toList();
        assertEquals(1, matches.size(), "Compose 必须包含唯一的顶层 " + key);
        return blockFrom(lines, matches.getFirst(), lines.size());
    }

    private ComposeBlock childBlock(
            List<ComposeLine> lines,
            ComposeBlock parent,
            String key) {
        int childIndentation = lines.subList(parent.startIndex() + 1, parent.endIndex()).stream()
                .filter(this::isConfigurationLine)
                .mapToInt(ComposeLine::indentation)
                .filter(indentation -> indentation > parent.indentation())
                .min()
                .orElseThrow(() -> new AssertionError("缺少 " + key + " 所在层级"));
        List<ComposeLine> matches = lines.subList(parent.startIndex() + 1, parent.endIndex()).stream()
                .filter(this::isConfigurationLine)
                .filter(line -> line.indentation() == childIndentation && line.stripped().equals(key))
                .toList();
        assertEquals(1, matches.size(), "必须包含唯一的 " + key);
        return blockFrom(lines, matches.getFirst(), parent.endIndex());
    }

    private ComposeBlock blockFrom(List<ComposeLine> lines, ComposeLine start, int parentEndIndex) {
        int endIndex = parentEndIndex;
        for (int index = start.index() + 1; index < parentEndIndex; index++) {
            ComposeLine candidate = lines.get(index);
            if (isConfigurationLine(candidate) && candidate.indentation() <= start.indentation()) {
                endIndex = index;
                break;
            }
        }
        return new ComposeBlock(start.index(), endIndex, start.indentation());
    }

    private boolean isConfigurationLine(ComposeLine line) {
        return !line.stripped().isEmpty() && !line.stripped().startsWith("#");
    }

    private int indentationOf(String line) {
        int indentation = 0;
        while (indentation < line.length() && line.charAt(indentation) == ' ') {
            indentation++;
        }
        return indentation;
    }

    private record ComposeLine(int index, String stripped, int indentation) {
    }

    private record ComposeBlock(int startIndex, int endIndex, int indentation) {
    }
}
