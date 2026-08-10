package com.lyw.appgeneration.service.rag.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Vue RAG 目录测试数据工厂。
 */
public final class TemplateTestData {

    public static final String SOURCE_MARKER = "SOURCE_MARKER_SHOULD_NOT_BE_SEARCHED";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private TemplateTestData() {
    }

    public static ObjectNode featureDocument(String id) {
        ObjectNode document = baseDocument(id, "FEATURE_SNIPPET");
        document.put("category", "auth");
        document.putArray("style").add("minimal").add("dark");
        document.putArray("tech").add("Vue 3").add("Vue Router");
        document.put("title", "登录功能片段");
        document.put("embedText", "适用于需要登录表单和路由跳转的 Vue 应用");
        document.put("description", "包含账号密码输入和提交按钮");
        addFile(document, "src/views/LoginView.vue", SOURCE_MARKER);
        document.put("qualityScore", 0.92);
        return document;
    }

    public static ObjectNode skeletonDocument(String id) {
        ObjectNode document = baseDocument(id, "PROJECT_SKELETON");
        document.put("category", "project");
        document.putArray("style").add("minimal");
        document.putArray("tech").add("Vue 3").add("Vue Router").add("Vite");
        document.put("title", "Vue 基础工程骨架");
        document.put("embedText", "可直接启动的 Vue 3 与 Vite 工程骨架");
        document.put("description", "提供路由和标准入口文件");
        addFile(document, "package.json", packageJson("^3.5.0", "^4.5.0", "^7.0.0"));
        addFile(document, "index.html", "<div id=\"app\"></div>");
        addFile(document, "vite.config.js", "export default defineConfig({})");
        addFile(document, "src/main.js", "createApp(App).mount('#app')");
        addFile(document, "src/App.vue", SOURCE_MARKER);
        document.put("qualityScore", 1.0);
        return document;
    }

    public static void write(Path file, ObjectNode document) throws IOException {
        Files.createDirectories(file.getParent());
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), document);
    }

    public static void addFile(ObjectNode document, String path, String content) {
        ArrayNode files = document.withArray("files");
        ObjectNode file = files.addObject();
        file.put("path", path);
        file.put("content", content);
    }

    private static ObjectNode baseDocument(String id, String documentKind) {
        ObjectNode document = OBJECT_MAPPER.createObjectNode();
        document.put("schemaVersion", 1);
        document.put("id", id);
        document.put("documentKind", documentKind);
        document.put("type", "vue-project");
        document.put("version", "1.0.0");
        document.put("framework", "Vue 3");
        document.put("language", "JavaScript");
        document.put("buildTool", "Vite");
        document.putObject("dependencies")
                .put("vue", "^3.5.0")
                .put("vue-router", "^4.5.0");
        document.putObject("devDependencies").put("vite", "^7.0.0");
        document.putArray("files");
        return document;
    }

    private static String packageJson(String vueVersion, String routerVersion, String viteVersion) {
        ObjectNode packageJson = OBJECT_MAPPER.createObjectNode();
        packageJson.put("name", "vue-rag-skeleton");
        packageJson.putObject("dependencies")
                .put("vue", vueVersion)
                .put("vue-router", routerVersion);
        packageJson.putObject("devDependencies").put("vite", viteVersion);
        try {
            return OBJECT_MAPPER.writeValueAsString(packageJson);
        } catch (IOException exception) {
            throw new IllegalStateException("测试 package.json 构造失败", exception);
        }
    }
}
