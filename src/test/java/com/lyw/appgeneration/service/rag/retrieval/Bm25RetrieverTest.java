package com.lyw.appgeneration.service.rag.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lyw.appgeneration.service.rag.catalog.TemplateCatalog;
import com.lyw.appgeneration.service.rag.model.RagDocumentKind;
import com.lyw.appgeneration.service.rag.model.RankedCandidate;
import com.lyw.appgeneration.service.rag.support.TemplateTestData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Bm25RetrieverTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void retrievesChineseSemanticMatchAndIsolatesDocumentKind(@TempDir Path tempDir) throws IOException {
        writeFeature(tempDir, "feature-login", "账号登录与身份认证", "邮箱密码登录表单", "src/views/LoginView.vue");
        writeFeature(tempDir, "feature-upload", "文件上传管理", "拖拽上传文件与进度展示", "src/views/UploadView.vue");
        writeSkeleton(tempDir, "skeleton-router", "Hash 路由管理后台", "后台导航与页面路由", true);
        TemplateCatalog catalog = new TemplateCatalog(tempDir, objectMapper);

        try (Bm25Retriever retriever = new Bm25Retriever(catalog)) {
            List<RankedCandidate> features = retriever.retrieve(
                    "需要邮箱密码登录和身份认证", RagDocumentKind.FEATURE_SNIPPET, 5);
            List<RankedCandidate> skeletons = retriever.retrieve(
                    "后台导航", RagDocumentKind.PROJECT_SKELETON, 5);

            assertEquals("feature-login", features.getFirst().documentId());
            assertTrue(features.stream().allMatch(candidate ->
                    candidate.documentKind() == RagDocumentKind.FEATURE_SNIPPET));
            assertEquals(List.of("skeleton-router"), skeletons.stream()
                    .map(RankedCandidate::documentId).toList());
            assertTrue(skeletons.stream().allMatch(candidate ->
                    candidate.documentKind() == RagDocumentKind.PROJECT_SKELETON));
        }
    }

    @Test
    void boostsExactPathsAndRouterTerms(@TempDir Path tempDir) throws IOException {
        writeSkeleton(tempDir, "skeleton-basic", "基础展示工程", "极简官网与关于页面", false);
        writeSkeleton(tempDir, "skeleton-router", "Hash 路由工程", "Vue Router Hash 管理后台", true);
        TemplateCatalog catalog = new TemplateCatalog(tempDir, objectMapper);

        try (Bm25Retriever retriever = new Bm25Retriever(catalog)) {
            List<RankedCandidate> packageMatches = retriever.retrieve(
                    "package.json", RagDocumentKind.PROJECT_SKELETON, 5);
            List<RankedCandidate> routerMatches = retriever.retrieve(
                    "vue-router hash", RagDocumentKind.PROJECT_SKELETON, 5);

            assertEquals(2, packageMatches.size());
            assertEquals("skeleton-router", routerMatches.getFirst().documentId());
        }
    }

    @Test
    void boostsMultiWordTechnologyLabelsWithTheSameNormalization(@TempDir Path tempDir) throws IOException {
        ObjectNode matched = TemplateTestData.skeletonDocument("skeleton-z-matched");
        matched.put("title", "统一工程");
        matched.put("embedText", "统一工程描述");
        matched.put("description", "Legacy Navigation");
        matched.withArray("tech").removeAll().add("Aurora Router");
        TemplateTestData.write(tempDir.resolve("skeletons/matched.json"), matched);

        ObjectNode unmatched = TemplateTestData.skeletonDocument("skeleton-a-unmatched");
        unmatched.put("title", "统一工程");
        unmatched.put("embedText", "统一工程描述");
        unmatched.put("description", "Aurora Router");
        unmatched.withArray("tech").removeAll().add("Legacy Navigation");
        TemplateTestData.write(tempDir.resolve("skeletons/unmatched.json"), unmatched);
        TemplateCatalog catalog = new TemplateCatalog(tempDir, objectMapper);

        try (Bm25Retriever retriever = new Bm25Retriever(catalog)) {
            List<RankedCandidate> candidates = retriever.retrieve(
                    "aurora router", RagDocumentKind.PROJECT_SKELETON, 2);

            assertEquals(2, candidates.size());
            assertEquals("skeleton-z-matched", candidates.getFirst().documentId());
            assertTrue(candidates.getFirst().score() > candidates.getLast().score());
        }
    }

    @Test
    void aggregatesChunksAppliesTopKAndProducesStableRanks(@TempDir Path tempDir) throws IOException {
        writeSkeleton(tempDir, "skeleton-b", "管理工程", "数据管理工程", true);
        writeSkeleton(tempDir, "skeleton-a", "管理工程", "数据管理工程", true);
        TemplateCatalog catalog = new TemplateCatalog(tempDir, objectMapper);

        try (Bm25Retriever retriever = new Bm25Retriever(catalog)) {
            List<RankedCandidate> candidates = retriever.retrieve(
                    "管理 vue-router", RagDocumentKind.PROJECT_SKELETON, 2);

            assertEquals(2, candidates.size());
            assertEquals(2, candidates.stream().map(RankedCandidate::documentId).distinct().count());
            assertEquals("skeleton-a", candidates.getFirst().documentId());
            assertEquals(1, candidates.getFirst().rank());
            assertEquals(2, candidates.getLast().rank());
            assertTrue(candidates.getFirst().score() > 0);
        }
    }

    @Test
    void handlesInvalidArgumentsWithoutSearching(@TempDir Path tempDir) throws IOException {
        writeFeature(tempDir, "feature-login", "账号登录", "登录表单", "src/views/LoginView.vue");
        TemplateCatalog catalog = new TemplateCatalog(tempDir, objectMapper);

        try (Bm25Retriever retriever = new Bm25Retriever(catalog)) {
            assertTrue(retriever.retrieve("", RagDocumentKind.FEATURE_SNIPPET, 5).isEmpty());
            assertTrue(retriever.retrieve("登录", null, 5).isEmpty());
            assertTrue(retriever.retrieve("登录", RagDocumentKind.FEATURE_SNIPPET, 0).isEmpty());
            assertTrue(retriever.retrieve("登录", RagDocumentKind.FEATURE_SNIPPET, -1).isEmpty());
        }
    }

    @Test
    void doesNotIndexSourceAndExposesCatalogVersion(@TempDir Path tempDir) throws IOException {
        writeFeature(tempDir, "feature-login", "账号登录", "登录表单", "src/views/LoginView.vue");
        TemplateCatalog catalog = new TemplateCatalog(tempDir, objectMapper);

        try (Bm25Retriever retriever = new Bm25Retriever(catalog)) {
            assertTrue(retriever.retrieve(TemplateTestData.SOURCE_MARKER,
                    RagDocumentKind.FEATURE_SNIPPET, 5).isEmpty());
            assertEquals(catalog.getCatalogVersion(), retriever.getCatalogVersion());
        }
    }

    private void writeFeature(Path root,
                              String id,
                              String title,
                              String description,
                              String filePath) throws IOException {
        ObjectNode document = TemplateTestData.featureDocument(id);
        document.put("title", title);
        document.put("embedText", title + " " + description);
        document.put("description", description);
        ((ObjectNode) document.withArray("files").get(0)).put("path", filePath);
        TemplateTestData.write(root.resolve("features/" + id + ".json"), document);
    }

    private void writeSkeleton(Path root,
                               String id,
                               String title,
                               String description,
                               boolean hashRouter) throws IOException {
        ObjectNode document = TemplateTestData.skeletonDocument(id);
        document.put("title", title);
        document.put("embedText", title + " " + description);
        document.put("description", description);
        document.withArray("tech").add(hashRouter ? "hash" : "landing");
        if (hashRouter) {
            TemplateTestData.addFile(document, "src/router/index.js", "createWebHashHistory()");
        } else {
            TemplateTestData.addFile(document, "src/views/AboutView.vue", "export default {}");
        }
        TemplateTestData.write(root.resolve("skeletons/" + id + ".json"), document);
    }
}
