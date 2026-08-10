package com.lyw.appgeneration.core.builder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.service.rag.model.TemplateDoc;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * 任务 2 五个工程骨架的真实 npm 构建验收。
 */
class VueSkeletonRealBuildTest {

    private static final Path SKELETON_DIRECTORY = Path.of(
            "embed_text/vue-project/skeletons");
    private static final Path BUILD_ROOT = Path.of(
            "target/rag-eval/skeleton-build");
    private static final Set<String> EXPECTED_SOURCE_FILES = Set.of(
            "vue-skeleton-basic-001.json",
            "vue-skeleton-admin-001.json",
            "vue-skeleton-shop-001.json",
            "vue-skeleton-content-001.json",
            "vue-skeleton-dashboard-001.json");

    @Test
    void coversAllFiveTaskTwoSkeletonSources() throws IOException {
        List<Path> sources = skeletonSources();

        assertEquals(5, sources.size());
        assertEquals(EXPECTED_SOURCE_FILES, sources.stream()
                .map(Path::getFileName)
                .map(Path::toString)
                .collect(Collectors.toSet()));
    }

    @TestFactory
    @EnabledIfEnvironmentVariable(named = "RAG_SKELETON_BUILD", matches = "(?i)true")
    List<DynamicTest> buildsAllTaskTwoSkeletonsWithRealNpm() throws IOException {
        return skeletonSources().stream()
                .map(source -> dynamicTest(source.getFileName().toString(),
                        () -> buildSkeleton(source)))
                .toList();
    }

    private List<Path> skeletonSources() throws IOException {
        try (Stream<Path> paths = Files.list(SKELETON_DIRECTORY)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
        }
    }

    private void buildSkeleton(Path source) throws IOException {
        TemplateDoc skeleton = new ObjectMapper().readValue(source.toFile(), TemplateDoc.class);
        Path buildDirectory = BUILD_ROOT.resolve(skeleton.getId()).normalize();
        assertTrue(buildDirectory.startsWith(BUILD_ROOT), "骨架 ID 不得逃逸构建根目录");
        recreateDirectory(buildDirectory);
        for (TemplateDoc.TemplateFile file : skeleton.getFiles()) {
            Path destination = buildDirectory.resolve(file.getPath()).normalize();
            assertTrue(destination.startsWith(buildDirectory), "骨架文件不得逃逸临时目录");
            Files.createDirectories(destination.getParent());
            Files.writeString(destination, file.getContent(), StandardCharsets.UTF_8);
        }

        BuildResult result = new VueProjectBuilder()
                .buildProjectDetailed(buildDirectory.toString());

        Path report = buildDirectory.resolve("build-result.txt");
        Files.writeString(report, formatResult(result), StandardCharsets.UTF_8);
        assertTrue(result.success(), () -> skeleton.getId()
                + " 真实构建失败: " + formatResult(result));
        assertTrue(Files.isDirectory(buildDirectory.resolve("dist")),
                () -> skeleton.getId() + " 构建成功但未生成 dist 目录");
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
