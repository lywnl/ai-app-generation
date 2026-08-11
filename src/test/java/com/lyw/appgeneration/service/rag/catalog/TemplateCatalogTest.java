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
import java.util.Map;
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

    @ParameterizedTest
    @MethodSource("invalidProjectMetadata")
    void rejectsInvalidProjectMetadataWithSourcePath(Consumer<ObjectNode> invalidMutation,
                                                     String expectedField,
                                                     @TempDir Path tempDir) throws IOException {
        ObjectNode document = TemplateTestData.featureDocument("feature-login");
        invalidMutation.accept(document);
        TemplateTestData.write(tempDir.resolve("invalid-metadata.json"), document);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new TemplateCatalog(tempDir, objectMapper));

        assertTrue(exception.getMessage().contains("invalid-metadata.json"));
        assertTrue(exception.getMessage().contains(expectedField));
    }

    static Stream<Object[]> invalidProjectMetadata() {
        return Stream.of(
                new Object[]{(Consumer<ObjectNode>) node -> node.remove("schemaVersion"), "schemaVersion"},
                new Object[]{(Consumer<ObjectNode>) node -> node.putNull("schemaVersion"), "schemaVersion"},
                new Object[]{(Consumer<ObjectNode>) node -> node.put("schemaVersion", 2), "schemaVersion"},
                new Object[]{(Consumer<ObjectNode>) node -> node.put("schemaVersion", "1"), "schemaVersion"},
                new Object[]{(Consumer<ObjectNode>) node -> node.put("schemaVersion", 1.0), "schemaVersion"},
                new Object[]{(Consumer<ObjectNode>) node -> node.remove("version"), "version"},
                new Object[]{(Consumer<ObjectNode>) node -> node.putNull("version"), "version"},
                new Object[]{(Consumer<ObjectNode>) node -> node.put("version", "  "), "version"},
                new Object[]{(Consumer<ObjectNode>) node -> node.remove("framework"), "framework"},
                new Object[]{(Consumer<ObjectNode>) node -> node.putNull("framework"), "framework"},
                new Object[]{(Consumer<ObjectNode>) node -> node.put("framework", "  "), "framework"},
                new Object[]{(Consumer<ObjectNode>) node -> node.remove("language"), "language"},
                new Object[]{(Consumer<ObjectNode>) node -> node.putNull("language"), "language"},
                new Object[]{(Consumer<ObjectNode>) node -> node.put("language", "  "), "language"},
                new Object[]{(Consumer<ObjectNode>) node -> node.remove("buildTool"), "buildTool"},
                new Object[]{(Consumer<ObjectNode>) node -> node.putNull("buildTool"), "buildTool"},
                new Object[]{(Consumer<ObjectNode>) node -> node.put("buildTool", "  "), "buildTool"}
        );
    }

    @ParameterizedTest
    @MethodSource("invalidDependencyDeclarations")
    void rejectsInvalidDependencyDeclarationsWithSourcePath(Consumer<ObjectNode> invalidMutation,
                                                            String expectedField,
                                                            @TempDir Path tempDir) throws IOException {
        ObjectNode document = TemplateTestData.featureDocument("feature-login");
        invalidMutation.accept(document);
        TemplateTestData.write(tempDir.resolve("invalid-dependency.json"), document);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new TemplateCatalog(tempDir, objectMapper));

        assertTrue(exception.getMessage().contains("invalid-dependency.json"));
        assertTrue(exception.getMessage().contains(expectedField));
    }

    static Stream<Object[]> invalidDependencyDeclarations() {
        return Stream.of(
                new Object[]{(Consumer<ObjectNode>) node -> node.with("dependencies").put("  ", "^1.0.0"),
                        "dependencies"},
                new Object[]{(Consumer<ObjectNode>) node -> node.with("dependencies").put("vue", "  "),
                        "dependencies"},
                new Object[]{(Consumer<ObjectNode>) node -> node.with("dependencies").putNull("vue"),
                        "dependencies"},
                new Object[]{(Consumer<ObjectNode>) node -> node.with("devDependencies").put("  ", "^1.0.0"),
                        "devDependencies"},
                new Object[]{(Consumer<ObjectNode>) node -> node.with("devDependencies").put("vite", "  "),
                        "devDependencies"},
                new Object[]{(Consumer<ObjectNode>) node -> node.with("devDependencies").putNull("vite"),
                        "devDependencies"}
        );
    }

    @ParameterizedTest
    @MethodSource("emptyDependencyDeclarations")
    void treatsMissingAndNullDependencyDeclarationsAsEmpty(Consumer<ObjectNode> mutation,
                                                           @TempDir Path tempDir) throws IOException {
        ObjectNode document = TemplateTestData.featureDocument("feature-login");
        mutation.accept(document);
        TemplateTestData.write(tempDir.resolve("empty-dependency.json"), document);

        TemplateCatalog catalog = new TemplateCatalog(tempDir, objectMapper);

        assertEquals(1, catalog.getDocuments().size());
        var loaded = catalog.getDocuments().getFirst();
        assertTrue(loaded.getDependencies() != null);
        assertTrue(loaded.getDevDependencies() != null);
        if (!mutationTargetsDependencies(mutation)) {
            assertTrue(loaded.getDependencies().isEmpty());
            assertThrows(UnsupportedOperationException.class,
                    () -> loaded.getDependencies().put("vue", "3.5.0"));
        } else {
            assertTrue(loaded.getDevDependencies().isEmpty());
            assertThrows(UnsupportedOperationException.class,
                    () -> loaded.getDevDependencies().put("vite", "7.0.0"));
        }
    }

    static Stream<Consumer<ObjectNode>> emptyDependencyDeclarations() {
        return Stream.of(
                removeField("dependencies"),
                nullField("dependencies"),
                removeField("devDependencies"),
                nullField("devDependencies")
        );
    }

    @Test
    void rejectsDifferentVersionsAcrossDependencyScopesWithCompleteSourceDetails(
            @TempDir Path tempDir) throws IOException {
        ObjectNode document = TemplateTestData.featureDocument("feature-conflict");
        document.with("dependencies").put("shared-package", "^4.1.0");
        document.with("devDependencies").put("shared-package", "^5.0.0");
        TemplateTestData.write(tempDir.resolve("nested/dependency-conflict.json"), document);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new TemplateCatalog(tempDir, objectMapper));

        assertTrue(exception.getMessage().contains("nested/dependency-conflict.json"));
        assertTrue(exception.getMessage().contains("shared-package"));
        assertTrue(exception.getMessage().contains("dependencies=^4.1.0"));
        assertTrue(exception.getMessage().contains("devDependencies=^5.0.0"));
    }

    @Test
    void allowsSameExactVersionAcrossDependencyScopes(@TempDir Path tempDir) throws IOException {
        ObjectNode document = TemplateTestData.featureDocument("feature-shared-version");
        document.with("dependencies").put("shared-package", "4.1.0");
        document.with("devDependencies").put("shared-package", "4.1.0");
        TemplateTestData.write(tempDir.resolve("shared-version.json"), document);

        TemplateCatalog catalog = new TemplateCatalog(tempDir, objectMapper);

        assertEquals(Map.of(
                        "vue", "^3.5.0",
                        "vue-router", "^4.5.0",
                        "shared-package", "4.1.0"),
                catalog.getDocuments().getFirst().getDependencies());
        assertEquals(Map.of(
                        "vite", "^7.0.0",
                        "shared-package", "4.1.0"),
                catalog.getDocuments().getFirst().getDevDependencies());
    }

    private static Consumer<ObjectNode> removeField(String fieldName) {
        return new DependencyFieldMutation(fieldName, false);
    }

    private static Consumer<ObjectNode> nullField(String fieldName) {
        return new DependencyFieldMutation(fieldName, true);
    }

    private static boolean mutationTargetsDependencies(Consumer<ObjectNode> mutation) {
        return mutation instanceof DependencyFieldMutation fieldMutation
                && "devDependencies".equals(fieldMutation.fieldName());
    }

    private record DependencyFieldMutation(String fieldName, boolean writeNull)
            implements Consumer<ObjectNode> {

        @Override
        public void accept(ObjectNode node) {
            if (writeNull) {
                node.putNull(fieldName);
            } else {
                node.remove(fieldName);
            }
        }
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

    @Test
    void rejectsSkeletonBeyondDerivedFileLimit(@TempDir Path tempDir) throws IOException {
        ObjectNode document = TemplateTestData.skeletonDocument("oversized-skeleton");
        for (int index = 5; index < 11; index++) {
            TemplateTestData.addFile(document, "src/extra-" + index + ".vue", "<template />");
        }
        TemplateTestData.write(tempDir.resolve("oversized-skeleton.json"), document);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new TemplateCatalog(tempDir, objectMapper));

        assertTrue(exception.getMessage().contains("oversized-skeleton.json"));
        assertTrue(exception.getMessage().contains("PROJECT_SKELETON"));
        assertTrue(exception.getMessage().contains("实际 11"));
        assertTrue(exception.getMessage().contains("上限 10"));
    }

    @Test
    void rejectsFeatureBeyondDerivedFileLimit(@TempDir Path tempDir) throws IOException {
        ObjectNode document = TemplateTestData.featureDocument("oversized-feature");
        for (int index = 1; index < 6; index++) {
            TemplateTestData.addFile(document, "src/extra-" + index + ".vue", "<template />");
        }
        TemplateTestData.write(tempDir.resolve("oversized-feature.json"), document);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new TemplateCatalog(tempDir, objectMapper));

        assertTrue(exception.getMessage().contains("oversized-feature.json"));
        assertTrue(exception.getMessage().contains("FEATURE_SNIPPET"));
        assertTrue(exception.getMessage().contains("实际 6"));
        assertTrue(exception.getMessage().contains("上限 5"));
    }
}
