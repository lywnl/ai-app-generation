package com.lyw.appgeneration.service.rag.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.service.rag.model.RagDocumentKind;
import com.lyw.appgeneration.service.rag.model.TemplateDoc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeTemplateCatalogTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void loadsNineHtmlSectionsAndCalculatesContentAddressedVersion(@TempDir Path root)
            throws IOException {
        writeCatalog(root, CodeGenTypeEnum.HTML, 9);

        NativeTemplateCatalog first = new NativeTemplateCatalog(
                root, CodeGenTypeEnum.HTML, OBJECT_MAPPER);
        NativeTemplateCatalog same = new NativeTemplateCatalog(
                root, CodeGenTypeEnum.HTML, OBJECT_MAPPER);

        assertEquals(9, first.getDocuments().size());
        assertEquals(CodeGenTypeEnum.HTML, first.getCodeGenType());
        assertTrue(first.getDocuments().stream().allMatch(document ->
                document.getDocumentKind() == RagDocumentKind.PAGE_SECTION));
        assertEquals(first.getCatalogVersion(), same.getCatalogVersion());
        assertEquals(64, first.getCatalogVersion().length());
        assertEquals("html-001", first.findDocumentById("html-001").orElseThrow().getId());

        Path changed = root.resolve("template-09.json");
        Files.writeString(changed,
                Files.readString(changed, StandardCharsets.UTF_8) + "\n",
                StandardCharsets.UTF_8);
        NativeTemplateCatalog updated = new NativeTemplateCatalog(
                root, CodeGenTypeEnum.HTML, OBJECT_MAPPER);
        assertNotEquals(first.getCatalogVersion(), updated.getCatalogVersion());
    }

    @Test
    void loadsEightSinglePageThreeFileApplications(@TempDir Path root) throws IOException {
        writeCatalog(root, CodeGenTypeEnum.MULTI_FILE, 8);

        NativeTemplateCatalog catalog = new NativeTemplateCatalog(
                root, CodeGenTypeEnum.MULTI_FILE, OBJECT_MAPPER);

        assertEquals(8, catalog.getDocuments().size());
        assertTrue(catalog.getDocuments().stream().allMatch(document ->
                document.getDocumentKind() == RagDocumentKind.SINGLE_PAGE_APP));
        assertTrue(catalog.getDocuments().stream().allMatch(document ->
                document.getFiles().stream().map(TemplateDoc.TemplateFile::getPath).toList()
                        .equals(List.of("index.html", "style.css", "script.js"))));
    }

    @Test
    void rejectsWrongCountTypeAndUnsafeFilePath(@TempDir Path root) throws IOException {
        writeCatalog(root, CodeGenTypeEnum.HTML, 8);
        IllegalArgumentException wrongCount = assertThrows(IllegalArgumentException.class,
                () -> new NativeTemplateCatalog(root, CodeGenTypeEnum.HTML, OBJECT_MAPPER));
        assertTrue(wrongCount.getMessage().contains("数量必须为 9"));

        Path ninth = root.resolve("template-09.json");
        writeDocument(ninth, CodeGenTypeEnum.HTML, 9);
        ObjectNode wrongType = (ObjectNode) OBJECT_MAPPER.readTree(ninth.toFile());
        wrongType.put("type", "multi-file");
        OBJECT_MAPPER.writeValue(ninth.toFile(), wrongType);
        IllegalArgumentException typeMismatch = assertThrows(IllegalArgumentException.class,
                () -> new NativeTemplateCatalog(root, CodeGenTypeEnum.HTML, OBJECT_MAPPER));
        assertTrue(typeMismatch.getMessage().contains("type 必须为 html"));

        wrongType.put("type", "html");
        ((ObjectNode) wrongType.withArray("files").get(0)).put("path", "../index.html");
        OBJECT_MAPPER.writeValue(ninth.toFile(), wrongType);
        IllegalArgumentException unsafePath = assertThrows(IllegalArgumentException.class,
                () -> new NativeTemplateCatalog(root, CodeGenTypeEnum.HTML, OBJECT_MAPPER));
        assertTrue(unsafePath.getMessage().contains("文件路径"));
    }

    @Test
    void rejectsDuplicateIdsAndWrongMultiFileSet(@TempDir Path root) throws IOException {
        writeCatalog(root, CodeGenTypeEnum.MULTI_FILE, 8);
        Path last = root.resolve("template-08.json");
        ObjectNode duplicate = (ObjectNode) OBJECT_MAPPER.readTree(last.toFile());
        duplicate.put("id", "multi-001");
        OBJECT_MAPPER.writeValue(last.toFile(), duplicate);

        IllegalArgumentException duplicateId = assertThrows(IllegalArgumentException.class,
                () -> new NativeTemplateCatalog(root, CodeGenTypeEnum.MULTI_FILE, OBJECT_MAPPER));
        assertTrue(duplicateId.getMessage().contains("重复文档 ID"));

        duplicate.put("id", "multi-008");
        duplicate.withArray("files").remove(2);
        OBJECT_MAPPER.writeValue(last.toFile(), duplicate);
        IllegalArgumentException wrongFiles = assertThrows(IllegalArgumentException.class,
                () -> new NativeTemplateCatalog(root, CodeGenTypeEnum.MULTI_FILE, OBJECT_MAPPER));
        assertTrue(wrongFiles.getMessage().contains("必须恰好包含"));
    }

    @Test
    void rejectsMissingTagsAndUnsafeSourceBeforeIngestion(@TempDir Path root) throws IOException {
        writeCatalog(root, CodeGenTypeEnum.HTML, 9);
        Path first = root.resolve("template-01.json");
        ObjectNode document = (ObjectNode) OBJECT_MAPPER.readTree(first.toFile());
        document.remove("style");
        OBJECT_MAPPER.writeValue(first.toFile(), document);

        IllegalArgumentException missingTags = assertThrows(IllegalArgumentException.class,
                () -> new NativeTemplateCatalog(root, CodeGenTypeEnum.HTML, OBJECT_MAPPER));
        assertTrue(missingTags.getMessage().contains("style 为空"));

        document.putArray("style").add("minimal");
        ((ObjectNode) document.withArray("files").get(0))
                .put("content", "<section onclick=\"eval('bad')\">\n内容\n</section>");
        OBJECT_MAPPER.writeValue(first.toFile(), document);

        IllegalArgumentException unsafeSource = assertThrows(IllegalArgumentException.class,
                () -> new NativeTemplateCatalog(root, CodeGenTypeEnum.HTML, OBJECT_MAPPER));
        assertTrue(unsafeSource.getMessage().contains("动态代码执行"));
    }

    @Test
    void rejectsFrameworkAndDependencyDrift(@TempDir Path root) throws IOException {
        writeCatalog(root, CodeGenTypeEnum.HTML, 9);
        Path first = root.resolve("template-01.json");
        ObjectNode document = (ObjectNode) OBJECT_MAPPER.readTree(first.toFile());
        document.put("framework", "vue");
        OBJECT_MAPPER.writeValue(first.toFile(), document);

        IllegalArgumentException framework = assertThrows(IllegalArgumentException.class,
                () -> new NativeTemplateCatalog(root, CodeGenTypeEnum.HTML, OBJECT_MAPPER));
        assertTrue(framework.getMessage().contains("framework 必须为 none"));

        document.put("framework", "none");
        document.with("dependencies").put("lodash", "4.17.21");
        OBJECT_MAPPER.writeValue(first.toFile(), document);

        IllegalArgumentException dependency = assertThrows(IllegalArgumentException.class,
                () -> new NativeTemplateCatalog(root, CodeGenTypeEnum.HTML, OBJECT_MAPPER));
        assertTrue(dependency.getMessage().contains("不得声明工程依赖"));
    }

    private void writeCatalog(Path root, CodeGenTypeEnum type, int count) throws IOException {
        Files.createDirectories(root);
        for (int index = 1; index <= count; index++) {
            writeDocument(root.resolve("template-%02d.json".formatted(index)), type, index);
        }
    }

    private void writeDocument(Path file, CodeGenTypeEnum type, int index) throws IOException {
        boolean html = type == CodeGenTypeEnum.HTML;
        String prefix = html ? "html" : "multi";
        ObjectNode document = OBJECT_MAPPER.createObjectNode();
        document.put("schemaVersion", 1);
        document.put("id", "%s-%03d".formatted(prefix, index));
        document.put("documentKind", html ? "PAGE_SECTION" : "SINGLE_PAGE_APP");
        document.put("type", html ? "html" : "multi-file");
        document.put("version", "1.0.0");
        document.put("framework", "none");
        document.put("language", html ? "html" : "html-css-javascript");
        document.put("buildTool", "none");
        document.putObject("dependencies");
        document.putObject("devDependencies");
        document.put("category", "test");
        document.putArray("style").add("minimal");
        document.putArray("tech").add("html5");
        document.put("title", "模板 " + index);
        document.put("embedText", "模板检索描述 " + index);
        document.put("description", "模板用途描述 " + index);
        document.put("qualityScore", 0.9);
        ArrayNode files = document.putArray("files");
        files.addObject().put("path", "index.html").put("content", html
                ? "<style>.template:focus-visible { outline: 2px solid; }</style>\n"
                + "<main class=\"template\" tabindex=\"0\">模板 " + index + "</main>\n"
                + "<script>\nconst once = true;\n</script>"
                : "<!doctype html>\n<html><head><meta name=\"viewport\" "
                + "content=\"width=device-width, initial-scale=1\">"
                + "<link rel=\"stylesheet\" href=\"style.css\"></head>\n"
                + "<body><main>模板 " + index + "</main>"
                + "<script src=\"script.js\"></script></body></html>");
        if (!html) {
            files.addObject().put("path", "style.css").put("content",
                    "main { display: block; }\nbutton:focus-visible { outline: 2px solid; }");
            files.addObject().put("path", "script.js").put("content",
                    "const message = '模板';\nconsole.log(message);");
        }
        OBJECT_MAPPER.writeValue(file.toFile(), document);
    }
}
