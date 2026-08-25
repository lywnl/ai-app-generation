package com.lyw.appgeneration.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.service.rag.catalog.NativeTemplateCatalog;
import com.lyw.appgeneration.service.rag.model.TemplateDoc;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeTemplateDatasetTest {

    private static final Pattern FORBIDDEN_EXECUTION = Pattern.compile(
            "(?i)(?:eval\\s*\\(|Function\\s*\\(|javascript\\s*:|on(?:click|submit)\\s*=)");
    private static final Pattern EMPTY_IMAGE_ALT = Pattern.compile(
            "(?i)<img(?=[^>]*\\balt\\s*=\\s*([\"'])\\1)[^>]*>");

    private static NativeTemplateCatalog htmlCatalog;
    private static NativeTemplateCatalog multiFileCatalog;

    @BeforeAll
    static void loadRealDatasets() {
        ObjectMapper objectMapper = new ObjectMapper();
        htmlCatalog = new NativeTemplateCatalog(
                Path.of("embed_text", "html"), CodeGenTypeEnum.HTML, objectMapper);
        multiFileCatalog = new NativeTemplateCatalog(
                Path.of("embed_text", "multi-file"), CodeGenTypeEnum.MULTI_FILE, objectMapper);
    }

    @Test
    void keepsExpectedCountsAndReadableUtf8Sources() {
        assertEquals(9, htmlCatalog.getDocuments().size());
        assertEquals(8, multiFileCatalog.getDocuments().size());

        allDocuments().forEach(document -> document.getFiles().forEach(file -> {
            assertTrue(file.getContent().contains("\n"),
                    () -> document.getId() + "/" + file.getPath() + " 不得压缩为单行");
            assertFalse(file.getContent().contains("�"),
                    () -> document.getId() + "/" + file.getPath() + " 包含乱码替代符");
        }));
    }

    @Test
    void rejectsExecutableStringsInlineEventsAndEmptyImageDescriptions() {
        allDocuments().forEach(document -> document.getFiles().forEach(file -> {
            String content = file.getContent();
            assertFalse(FORBIDDEN_EXECUTION.matcher(content).find(),
                    () -> document.getId() + "/" + file.getPath() + " 包含危险执行方式或内联事件");
            assertFalse(EMPTY_IMAGE_ALT.matcher(content).find(),
                    () -> document.getId() + "/" + file.getPath() + " 包含空图片说明");
        }));
    }

    @Test
    void keepsHtmlSectionsScopedAndKeyboardVisible() {
        htmlCatalog.getDocuments().forEach(document -> {
            String html = document.getFiles().getFirst().getContent();
            String compact = html.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
            assertFalse(compact.contains("body{"), () -> document.getId() + " 污染 body 样式");
            assertFalse(compact.contains("*{"), () -> document.getId() + " 使用全局通配重置");
            assertTrue(html.contains(":focus-visible"),
                    () -> document.getId() + " 缺少键盘焦点样式");
        });
    }

    @Test
    void keepsMultiFilePagesCompleteAndReferencesExactSiblingFiles() {
        multiFileCatalog.getDocuments().forEach(document -> {
            String html = content(document, "index.html");
            assertTrue(html.contains("<meta name=\"viewport\""),
                    () -> document.getId() + " 缺少 viewport");
            assertEquals(1, count(html, "<link rel=\"stylesheet\" href=\"style.css\">"));
            assertEquals(1, count(html, "<script src=\"script.js\"></script>"));
            assertTrue(content(document, "style.css").contains(":focus-visible"),
                    () -> document.getId() + " 缺少键盘焦点样式");
        });
    }

    @Test
    void protectsKnownInteractiveTemplateContracts() {
        String calculator = script("multi-calculator-001");
        assertTrue(calculator.contains("calculateResult"));

        String todo = script("multi-todo-app-001");
        assertTrue(todo.contains("actionButton('编辑', 'edit'"));
        assertTrue(todo.contains("textContent"));
        assertFalse(todo.contains("innerHTML"));

        String markdown = script("multi-markdown-editor-001");
        assertTrue(markdown.contains("DocumentFragment"));
        assertTrue(markdown.contains("['http:', 'https:']"));
        assertFalse(markdown.contains("innerHTML"));

        String galleryHtml = html("multi-image-gallery-001");
        String galleryScript = script("multi-image-gallery-001");
        assertTrue(galleryHtml.contains("role=\"dialog\""));
        assertTrue(galleryHtml.contains("aria-modal=\"true\""));
        assertTrue(galleryScript.contains("previousFocus"));
        assertTrue(galleryScript.contains("Escape"));
        assertTrue(galleryScript.contains("gallery.inert = true"));
        assertTrue(galleryScript.contains("event.key === 'Tab'"));

        String navbarHtml = htmlCatalog.findDocumentById("html-navbar-sticky-001")
                .orElseThrow().getFiles().getFirst().getContent();
        assertTrue(navbarHtml.contains("aria-controls=\"site-menu\""));
        assertTrue(navbarHtml.contains("aria-expanded=\"false\""));
        assertTrue(navbarHtml.contains("submenuButton.focus()"));
        assertFalse(navbarHtml.contains("navbar-template__submenu:focus-within"));

        String quiz = script("multi-quiz-app-001");
        assertTrue(quiz.contains("updateProgress(questions.length)"));

        String pomodoro = script("multi-pomodoro-timer-001");
        assertEquals(2, count(pomodoro, "completed = 0"));
        assertTrue(pomodoro.contains("cycles.textContent = '已完成 0 个番茄'"));
    }

    private static List<TemplateDoc> allDocuments() {
        return java.util.stream.Stream.concat(
                        htmlCatalog.getDocuments().stream(),
                        multiFileCatalog.getDocuments().stream())
                .toList();
    }

    private String html(String documentId) {
        return content(document(documentId), "index.html");
    }

    private String script(String documentId) {
        return content(document(documentId), "script.js");
    }

    private TemplateDoc document(String documentId) {
        return multiFileCatalog.findDocumentById(documentId).orElseThrow();
    }

    private static String content(TemplateDoc document, String path) {
        return document.getFiles().stream()
                .filter(file -> path.equals(file.getPath()))
                .findFirst()
                .orElseThrow()
                .getContent();
    }

    private static int count(String text, String marker) {
        int result = 0;
        int index = 0;
        while ((index = text.indexOf(marker, index)) >= 0) {
            result++;
            index += marker.length();
        }
        return result;
    }
}
