package com.lyw.appgeneration.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.service.rag.catalog.TemplateCatalog;
import com.lyw.appgeneration.service.rag.model.RagDocumentKind;
import com.lyw.appgeneration.service.rag.model.TemplateDoc;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VueTemplateDatasetTest {

    private static final Path DATASET_ROOT = Path.of("embed_text/vue-project");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Map<String, String> FEATURE_SOURCE_HASHES = Map.ofEntries(
            Map.entry("vue-article-detail-001", "f6905f5f3ef23779669958d94c5a69124371f2db0b54f31fecc91ba021333dc0"),
            Map.entry("vue-chat-list-001", "7e7af9956176ac20fb04effa09946e367fae3dce8d2bff0375bc711fc9cf58f8"),
            Map.entry("vue-dashboard-layout-001", "77d84261c7e4b597f5005638beb6067ad78a1d85f1a644ab34156054d93709f3"),
            Map.entry("vue-data-table-001", "fa3320c835e0dc7aa3ffd91c30bf36d5e2d5d77669237efcd7b0dc5b73cc18b3"),
            Map.entry("vue-file-upload-001", "55450edd2271a19ad9cecbd908f890038077083a042204c2a259e0c03697765e"),
            Map.entry("vue-login-form-001", "4e676248eccd6ed5d07871912f84ae01b0118952d2678c67a940806eeacc4ed2"),
            Map.entry("vue-404-page-001", "1eff72ecd308ff4a7979e54b848251f4c6803b6a40d3b3d54cd3320c1a83b55b"),
            Map.entry("vue-order-detail-001", "a3ec243f40b55de53e8a9ed059c94d9ab5e7db2a5ba73138657a01684f2eb833"),
            Map.entry("vue-product-list-001", "f7b9a394d4323813712099a7f49bc09f3c799b2badaa5131b499fbcba3c07347"),
            Map.entry("vue-profile-page-001", "48465a24b5c8fb3046b2d6e31eb00e890b6a244d6af0ec39c002f604f8b60ad3"),
            Map.entry("vue-register-form-001", "a307b091d0c3d3bb07f77e2bfdeaf971365776fd10e05dc53814118b3260b64f"),
            Map.entry("vue-settings-page-001", "5bb5128f9e7d2d02eaa453a03e2222dbb8b562f6d48e46b745c9ca02a12e95a2"),
            Map.entry("vue-stats-dashboard-001", "c7b45b55fbefdceb36376066d1e02bf2ddf9d8de6a2c8858c72e1d79e737edd6")
    );

    private static final Set<String> ROUTER_FEATURE_IDS = Set.of(
            "vue-dashboard-layout-001",
            "vue-404-page-001",
            "vue-register-form-001"
    );

    private static final Set<String> ICON_FEATURE_IDS = Set.of(
            "vue-dashboard-layout-001",
            "vue-file-upload-001",
            "vue-settings-page-001"
    );

    private static final Set<String> SKELETON_IDS = Set.of(
            "vue-skeleton-basic-001",
            "vue-skeleton-admin-001",
            "vue-skeleton-shop-001",
            "vue-skeleton-content-001",
            "vue-skeleton-dashboard-001"
    );

    private static TemplateCatalog catalog;
    private static List<TemplateDoc> documents;

    @BeforeAll
    static void loadDataset() {
        catalog = new TemplateCatalog(DATASET_ROOT, OBJECT_MAPPER);
        documents = catalog.getDocuments();
    }

    @Test
    void loadsEighteenParentDocumentsAndTwentyThreeKnowledgeChunks() {
        assertEquals(18, documents.size());
        assertEquals(13, documents.stream()
                .filter(document -> document.getDocumentKind() == RagDocumentKind.FEATURE_SNIPPET)
                .count());
        assertEquals(5, documents.stream()
                .filter(document -> document.getDocumentKind() == RagDocumentKind.PROJECT_SKELETON)
                .count());
        assertEquals(23, catalog.getChunks().size());
    }

    @Test
    void keepsAllExistingFeatureIdsAndSourceContent() {
        Map<String, TemplateDoc> features = documents.stream()
                .filter(document -> document.getDocumentKind() == RagDocumentKind.FEATURE_SNIPPET)
                .collect(Collectors.toMap(TemplateDoc::getId, document -> document));

        assertEquals(FEATURE_SOURCE_HASHES.keySet(), features.keySet());
        FEATURE_SOURCE_HASHES.forEach((id, expectedHash) -> {
            TemplateDoc feature = features.get(id);
            assertEquals(1, feature.getFiles().size(), id + " 必须继续只表达一个可复用功能文件");
            assertEquals(expectedHash, sha256(feature.getFiles().getFirst().getContent()), id + " 源码不得改变");
            assertFeatureMetadata(feature);
        });
    }

    @Test
    void storesEveryJsonBelowFeaturesOrSkeletons() throws IOException {
        try (Stream<Path> files = Files.walk(DATASET_ROOT)) {
            List<Path> jsonFiles = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .toList();
            assertEquals(18, jsonFiles.size());
            assertTrue(jsonFiles.stream().allMatch(this::isDatasetChild));
        }
    }

    @Test
    void providesFiveDistinctBuildableSkeletonContracts() throws IOException {
        Map<String, TemplateDoc> skeletons = documents.stream()
                .filter(document -> document.getDocumentKind() == RagDocumentKind.PROJECT_SKELETON)
                .collect(Collectors.toMap(TemplateDoc::getId, document -> document));

        assertEquals(SKELETON_IDS, skeletons.keySet());
        for (TemplateDoc skeleton : skeletons.values()) {
            assertCommonMetadata(skeleton);
            assertEquals("vue-project", skeleton.getType());
            assertRequiredFiles(skeleton);
            assertExactDependencyVersions(skeleton.getDependencies());
            assertExactDependencyVersions(skeleton.getDevDependencies());
            assertPackageJsonMatchesParent(skeleton);
            assertStaticDeploymentConfig(skeleton);
        }
        assertEquals(5, skeletons.values().stream().map(TemplateDoc::getEmbedText).distinct().count());
        assertEquals(5, skeletons.values().stream().map(TemplateDoc::getCategory).distinct().count());
    }

    private static void assertFeatureMetadata(TemplateDoc feature) {
        assertCommonMetadata(feature);
        Map<String, String> expectedDependencies = new HashMap<>();
        expectedDependencies.put("vue", "3.3.4");
        expectedDependencies.put("element-plus", "2.8.8");
        if (ROUTER_FEATURE_IDS.contains(feature.getId())) {
            expectedDependencies.put("vue-router", "4.2.4");
        }
        if (ICON_FEATURE_IDS.contains(feature.getId())) {
            expectedDependencies.put("@element-plus/icons-vue", "2.3.1");
        }
        assertEquals(expectedDependencies, feature.getDependencies(), feature.getId());
        assertEquals(Map.of("@vitejs/plugin-vue", "4.2.3", "vite", "4.4.5"),
                feature.getDevDependencies(), feature.getId());
    }

    private static void assertCommonMetadata(TemplateDoc document) {
        assertEquals(1, document.getSchemaVersion());
        assertEquals("1.0.0", document.getVersion());
        assertEquals("vue@3.3.4", document.getFramework());
        assertEquals("javascript", document.getLanguage());
        assertEquals("vite@4.4.5", document.getBuildTool());
    }

    private static void assertRequiredFiles(TemplateDoc skeleton) {
        Set<String> filePaths = skeleton.getFiles().stream()
                .map(TemplateDoc.TemplateFile::getPath)
                .collect(Collectors.toSet());
        assertTrue(filePaths.containsAll(Set.of(
                "package.json", "index.html", "vite.config.js", "src/main.js", "src/App.vue"
        )), skeleton.getId());
        assertTrue(filePaths.stream().anyMatch(path -> path.contains("router")), skeleton.getId());
        assertTrue(filePaths.stream().anyMatch(path -> path.startsWith("src/views/")), skeleton.getId());
    }

    private static void assertExactDependencyVersions(Map<String, String> dependencies) {
        Map<String, String> allowedVersions = Map.of(
                "vue", "3.3.4",
                "vue-router", "4.2.4",
                "element-plus", "2.8.8",
                "@element-plus/icons-vue", "2.3.1",
                "echarts", "5.5.1",
                "vite", "4.4.5",
                "@vitejs/plugin-vue", "4.2.3"
        );
        dependencies.forEach((name, version) -> {
            assertFalse(version.startsWith("^") || version.startsWith("~"), name);
            assertEquals(allowedVersions.get(name), version, name);
        });
    }

    private static void assertPackageJsonMatchesParent(TemplateDoc skeleton) throws IOException {
        String packageJsonContent = skeleton.getFiles().stream()
                .filter(file -> "package.json".equals(file.getPath()))
                .findFirst()
                .orElseThrow()
                .getContent();
        JsonNode packageJson = OBJECT_MAPPER.readTree(packageJsonContent);
        assertEquals(skeleton.getDependencies(), stringMap(packageJson.get("dependencies")));
        assertEquals(skeleton.getDevDependencies(), stringMap(packageJson.get("devDependencies")));
        assertEquals("vite build", packageJson.path("scripts").path("build").asText());
    }

    private static void assertStaticDeploymentConfig(TemplateDoc skeleton) {
        String viteConfig = fileContent(skeleton, "vite.config.js");
        String routerConfig = skeleton.getFiles().stream()
                .filter(file -> file.getPath().contains("router"))
                .findFirst()
                .orElseThrow()
                .getContent();
        assertTrue(viteConfig.contains("base: './'"), skeleton.getId() + " 必须使用相对资源路径");
        assertTrue(routerConfig.contains("createWebHashHistory"), skeleton.getId() + " 必须使用 Hash 路由");
        assertFalse(routerConfig.contains("createWebHistory"), skeleton.getId() + " 不应依赖服务端路由回退");
    }

    private static String fileContent(TemplateDoc skeleton, String path) {
        return skeleton.getFiles().stream()
                .filter(file -> path.equals(file.getPath()))
                .findFirst()
                .orElseThrow()
                .getContent();
    }

    private static Map<String, String> stringMap(JsonNode node) {
        return OBJECT_MAPPER.convertValue(node, OBJECT_MAPPER.getTypeFactory()
                .constructMapType(Map.class, String.class, String.class));
    }

    private boolean isDatasetChild(Path file) {
        Path relativePath = DATASET_ROOT.relativize(file);
        return relativePath.getNameCount() == 2
                && Set.of("features", "skeletons").contains(relativePath.getName(0).toString());
    }

    private static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }
}
