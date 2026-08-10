package com.lyw.appgeneration.service.rag.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lyw.appgeneration.service.rag.model.RagDocumentKind;
import com.lyw.appgeneration.service.rag.support.TemplateTestData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateCatalogTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void recursivelyLoadsDocumentsAndProvidesStableCatalogVersion(@TempDir Path tempDir) throws IOException {
        TemplateTestData.write(tempDir.resolve("nested/feature.json"),
                TemplateTestData.featureDocument("feature-login"));
        TemplateTestData.write(tempDir.resolve("skeleton.json"),
                TemplateTestData.skeletonDocument("skeleton-vue"));

        TemplateCatalog firstCatalog = new TemplateCatalog(tempDir, objectMapper);
        TemplateCatalog secondCatalog = new TemplateCatalog(tempDir, objectMapper);

        assertEquals(2, firstCatalog.getDocuments().size());
        assertEquals("feature-login", firstCatalog.getDocuments().getFirst().getId());
        assertEquals(RagDocumentKind.FEATURE_SNIPPET,
                firstCatalog.findDocumentById("feature-login").orElseThrow().getDocumentKind());
        assertEquals(Optional.empty(), firstCatalog.findDocumentById("missing"));
        assertEquals(3, firstCatalog.getChunks().size());
        assertEquals(64, firstCatalog.getCatalogVersion().length());
        assertEquals(firstCatalog.getCatalogVersion(), secondCatalog.getCatalogVersion());
    }

    @Test
    void catalogVersionChangesWhenRelativePathChanges(@TempDir Path tempDir) throws IOException {
        Path firstRoot = tempDir.resolve("first");
        Path secondRoot = tempDir.resolve("second");
        ObjectNode document = TemplateTestData.featureDocument("feature-login");
        TemplateTestData.write(firstRoot.resolve("a.json"), document);
        TemplateTestData.write(secondRoot.resolve("nested/a.json"), document);

        String firstVersion = new TemplateCatalog(firstRoot, objectMapper).getCatalogVersion();
        String secondVersion = new TemplateCatalog(secondRoot, objectMapper).getCatalogVersion();

        assertFalse(firstVersion.equals(secondVersion));
    }

    @Test
    void rejectsDuplicateDocumentIdWithSourcePath(@TempDir Path tempDir) throws IOException {
        TemplateTestData.write(tempDir.resolve("first.json"), TemplateTestData.featureDocument("duplicate"));
        TemplateTestData.write(tempDir.resolve("nested/second.json"), TemplateTestData.featureDocument("duplicate"));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new TemplateCatalog(tempDir, objectMapper));

        assertTrue(exception.getMessage().contains("nested/second.json"));
        assertTrue(exception.getMessage().contains("重复文档 ID"));
    }

    @ParameterizedTest
    @MethodSource("invalidRequiredFields")
    void rejectsMissingRequiredFieldsWithSourcePath(Consumer<ObjectNode> invalidMutation,
                                                     String expectedReason,
                                                     @TempDir Path tempDir) throws IOException {
        ObjectNode document = TemplateTestData.featureDocument("feature-login");
        invalidMutation.accept(document);
        TemplateTestData.write(tempDir.resolve("invalid.json"), document);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new TemplateCatalog(tempDir, objectMapper));

        assertTrue(exception.getMessage().contains("invalid.json"));
        assertTrue(exception.getMessage().contains(expectedReason));
    }

    static Stream<Object[]> invalidRequiredFields() {
        return Stream.of(
                new Object[]{(Consumer<ObjectNode>) node -> node.put("id", "  "), "文档 ID 为空"},
                new Object[]{(Consumer<ObjectNode>) node -> node.putNull("documentKind"), "documentKind 为空"},
                new Object[]{(Consumer<ObjectNode>) node -> node.put("documentKind", "  "), "documentKind 为空"},
                new Object[]{(Consumer<ObjectNode>) node -> node.put("embedText", ""), "embedText 为空"},
                new Object[]{(Consumer<ObjectNode>) node -> node.remove("files"), "files 为空"},
                new Object[]{(Consumer<ObjectNode>) node -> node.putArray("files"), "files 为空"}
        );
    }

    @Test
    void rejectsUnknownDocumentKindWithSourcePath(@TempDir Path tempDir) throws IOException {
        ObjectNode document = TemplateTestData.featureDocument("feature-login");
        document.put("documentKind", "UNKNOWN_KIND");
        TemplateTestData.write(tempDir.resolve("unknown-kind.json"), document);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new TemplateCatalog(tempDir, objectMapper));

        assertTrue(exception.getMessage().contains("unknown-kind.json"));
        assertTrue(exception.getMessage().contains("documentKind"));
    }

    @Test
    void rejectsNullJsonDocumentWithSourcePath(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("null-document.json"), "null");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new TemplateCatalog(tempDir, objectMapper));

        assertTrue(exception.getMessage().contains("null-document.json"));
        assertTrue(exception.getMessage().contains("JSON 对象"));
    }

    @ParameterizedTest
    @MethodSource("unsafePaths")
    void rejectsUnsafeFilePath(String unsafePath, String expectedReason, @TempDir Path tempDir) throws IOException {
        ObjectNode document = TemplateTestData.featureDocument("feature-login");
        ((ObjectNode) document.withArray("files").get(0)).put("path", unsafePath);
        TemplateTestData.write(tempDir.resolve("unsafe-path.json"), document);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new TemplateCatalog(tempDir, objectMapper));

        assertTrue(exception.getMessage().contains("unsafe-path.json"));
        assertTrue(exception.getMessage().contains(expectedReason));
    }

    static Stream<Object[]> unsafePaths() {
        return Stream.of(
                new Object[]{"/etc/passwd", "绝对路径"},
                new Object[]{"src/../secret.txt", ".."},
                new Object[]{"src/secret..txt", ".."}
        );
    }

    @Test
    void rejectsSkeletonMissingRequiredFile(@TempDir Path tempDir) throws IOException {
        ObjectNode document = TemplateTestData.skeletonDocument("skeleton-vue");
        document.withArray("files").remove(4);
        TemplateTestData.write(tempDir.resolve("missing-file.json"), document);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new TemplateCatalog(tempDir, objectMapper));

        assertTrue(exception.getMessage().contains("missing-file.json"));
        assertTrue(exception.getMessage().contains("src/App.vue"));
    }

    @ParameterizedTest
    @MethodSource("invalidQualityScores")
    void rejectsQualityScoreOutsideClosedInterval(Double qualityScore, @TempDir Path tempDir) throws IOException {
        ObjectNode document = TemplateTestData.featureDocument("feature-login");
        if (qualityScore == null) {
            document.putNull("qualityScore");
        } else {
            document.put("qualityScore", qualityScore);
        }
        TemplateTestData.write(tempDir.resolve("invalid-score.json"), document);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new TemplateCatalog(tempDir, objectMapper));

        assertTrue(exception.getMessage().contains("qualityScore"));
        assertTrue(exception.getMessage().contains("[0,1]"));
    }

    static Stream<Double> invalidQualityScores() {
        return Stream.of(-0.01, 1.01, null);
    }

    @Test
    void rejectsInvalidSkeletonPackageJson(@TempDir Path tempDir) throws IOException {
        ObjectNode document = TemplateTestData.skeletonDocument("skeleton-vue");
        ((ObjectNode) document.withArray("files").get(0)).put("content", "{invalid-json");
        TemplateTestData.write(tempDir.resolve("invalid-package.json"), document);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new TemplateCatalog(tempDir, objectMapper));

        assertTrue(exception.getMessage().contains("package.json"));
        assertTrue(exception.getMessage().contains("合法 JSON"));
    }

    @Test
    void rejectsNullSkeletonPackageJsonWithSourcePath(@TempDir Path tempDir) throws IOException {
        ObjectNode document = TemplateTestData.skeletonDocument("skeleton-vue");
        ((ObjectNode) document.withArray("files").get(0)).putNull("content");
        TemplateTestData.write(tempDir.resolve("null-package.json"), document);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new TemplateCatalog(tempDir, objectMapper));

        assertTrue(exception.getMessage().contains("null-package.json"));
        assertTrue(exception.getMessage().contains("package.json"));
    }

    @Test
    void rejectsSkeletonDependencyMismatch(@TempDir Path tempDir) throws IOException {
        ObjectNode document = TemplateTestData.skeletonDocument("skeleton-vue");
        document.with("dependencies").put("vue-router", "^4.6.0");
        TemplateTestData.write(tempDir.resolve("dependency-mismatch.json"), document);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new TemplateCatalog(tempDir, objectMapper));

        assertTrue(exception.getMessage().contains("dependency-mismatch.json"));
        assertTrue(exception.getMessage().contains("dependencies 声明不一致"));
    }
}
