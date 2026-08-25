package com.lyw.appgeneration.service.rag.catalog;

import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.service.rag.model.TemplateDoc;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 原生模板入库前的源码质量门禁。
 */
final class NativeTemplateQualityGate {

    private static final Pattern DYNAMIC_EXECUTION = Pattern.compile(
            "(?:\\beval\\s*\\(|\\bFunction\\s*\\()"
    );
    private static final Pattern UNSAFE_MARKUP = Pattern.compile(
            "(?:javascript\\s*:|<[^>]*\\bon[a-z]+\\s*=)", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern EMPTY_IMAGE_ALT = Pattern.compile(
            "<img(?=[^>]*\\balt\\s*=\\s*([\"'])\\s*\\1)[^>]*>", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern BODY_SELECTOR = Pattern.compile(
            "(?<![-\\w.#])body\\s*\\{", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern GLOBAL_SELECTOR = Pattern.compile(
            "(?:^|[},])\\s*\\*\\s*(?:,|\\{)",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE
    );

    void validate(TemplateDoc document, CodeGenTypeEnum type, String sourcePath) {
        for (TemplateDoc.TemplateFile file : document.getFiles()) {
            validateCommonSource(file, sourcePath);
        }
        if (type == CodeGenTypeEnum.HTML) {
            validateHtmlSection(document, sourcePath);
        } else if (type == CodeGenTypeEnum.MULTI_FILE) {
            validateMultiFileApplication(document, sourcePath);
        }
    }

    private void validateCommonSource(
            TemplateDoc.TemplateFile file,
            String sourcePath) {
        String content = file.getContent();
        if (!content.contains("\n")) {
            throw invalidFile(sourcePath, file.getPath() + " 源码不得压缩为单行");
        }
        if (content.contains("�")) {
            throw invalidFile(sourcePath, file.getPath() + " 包含 UTF-8 乱码替代符");
        }
        if (DYNAMIC_EXECUTION.matcher(content).find()) {
            throw invalidFile(sourcePath, file.getPath() + " 包含动态代码执行");
        }
        if (UNSAFE_MARKUP.matcher(content).find()) {
            throw invalidFile(sourcePath, file.getPath() + " 包含内联事件或 javascript: 协议");
        }
        if (content.contains("innerHTML")) {
            throw invalidFile(sourcePath, file.getPath() + " 使用 innerHTML");
        }
        if (isHtmlFile(file) && EMPTY_IMAGE_ALT.matcher(content).find()) {
            throw invalidFile(sourcePath, file.getPath() + " 包含空图片说明");
        }
    }

    private void validateHtmlSection(TemplateDoc document, String sourcePath) {
        String html = content(document.getFiles(), "index.html");
        if (BODY_SELECTOR.matcher(html).find()) {
            throw invalidFile(sourcePath, "HTML 片段不得污染 body 样式");
        }
        if (GLOBAL_SELECTOR.matcher(html).find()) {
            throw invalidFile(sourcePath, "HTML 片段不得使用全局通配选择器");
        }
        requireContains(html, ":focus-visible", sourcePath, "HTML 片段缺少键盘焦点样式");
    }

    private void validateMultiFileApplication(TemplateDoc document, String sourcePath) {
        String html = content(document.getFiles(), "index.html");
        String css = content(document.getFiles(), "style.css");
        requireContains(html, "<meta name=\"viewport\"", sourcePath, "多文件模板缺少 viewport");
        requireCount(html, "<link rel=\"stylesheet\" href=\"style.css\">", sourcePath);
        requireCount(html, "<script src=\"script.js\"></script>", sourcePath);
        requireContains(css, ":focus-visible", sourcePath, "多文件模板缺少键盘焦点样式");
    }

    private void requireCount(String text, String marker, String sourcePath) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(marker, offset)) >= 0) {
            count++;
            offset += marker.length();
        }
        if (count != 1) {
            throw invalidFile(sourcePath, "必须恰好引用一次 " + marker);
        }
    }

    private void requireContains(String text, String marker, String sourcePath, String reason) {
        if (!text.toLowerCase(Locale.ROOT).contains(marker.toLowerCase(Locale.ROOT))) {
            throw invalidFile(sourcePath, reason);
        }
    }

    private String content(List<TemplateDoc.TemplateFile> files, String path) {
        return files.stream()
                .filter(file -> path.equals(file.getPath()))
                .findFirst()
                .orElseThrow()
                .getContent();
    }

    private boolean isHtmlFile(TemplateDoc.TemplateFile file) {
        return file.getPath().toLowerCase(Locale.ROOT).endsWith(".html");
    }

    private IllegalArgumentException invalidFile(String sourcePath, String reason) {
        return new IllegalArgumentException("原生模板文件 [" + sourcePath + "] 非法: " + reason);
    }
}
