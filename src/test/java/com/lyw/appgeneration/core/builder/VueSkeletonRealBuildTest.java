package com.lyw.appgeneration.core.builder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.service.rag.model.TemplateDoc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务 2 基础骨架的真实 npm 构建验收。
 */
@EnabledIfEnvironmentVariable(named = "RAG_SKELETON_BUILD", matches = "(?i)true")
class VueSkeletonRealBuildTest {

    private static final Path SOURCE = Path.of(
            "embed_text/vue-project/skeletons/vue-skeleton-basic-001.json");
    private static final Path BUILD_DIRECTORY = Path.of(
            "target/rag-eval/skeleton-build/vue-skeleton-basic-001");

    @Test
    void buildsTaskTwoBasicSkeletonWithRealNpm() throws IOException {
        TemplateDoc skeleton = new ObjectMapper().readValue(SOURCE.toFile(), TemplateDoc.class);
        recreateDirectory(BUILD_DIRECTORY);
        for (TemplateDoc.TemplateFile file : skeleton.getFiles()) {
            Path destination = BUILD_DIRECTORY.resolve(file.getPath()).normalize();
            assertTrue(destination.startsWith(BUILD_DIRECTORY), "骨架文件不得逃逸临时目录");
            Files.createDirectories(destination.getParent());
            Files.writeString(destination, file.getContent(), StandardCharsets.UTF_8);
        }

        BuildResult result = new VueProjectBuilder()
                .buildProjectDetailed(BUILD_DIRECTORY.toString());

        Path report = BUILD_DIRECTORY.getParent().resolve("build-result.txt");
        Files.writeString(report, formatResult(result), StandardCharsets.UTF_8);
        assertTrue(result.success(), () -> "基础骨架真实构建失败: " + formatResult(result));
    }

    private void recreateDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        Files.createDirectories(directory);
    }

    private String formatResult(BuildResult result) {
        return """
                success=%s
                stage=%s
                exitCode=%s
                timedOut=%s
                durationMillis=%d
                outputTail:
                %s
                """.formatted(
                result.success(),
                result.stage(),
                result.exitCode(),
                result.timedOut(),
                result.durationMillis(),
                result.outputTail());
    }
}
