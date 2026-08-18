package com.lyw.appgeneration.ai.tools;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONNull;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/** 文件工具共享的协议序列化与稳定文本解析。 */
final class FileToolProtocolSupport {

    private static final Set<String> PROTOCOL_FIELDS = Set.of(
            "protocol", "operation", "status", "relativePath",
            "changed", "message", "failureReason", "content");

    private FileToolProtocolSupport() {
    }

    static String json(FileToolResult result) {
        JSONObject json = new JSONObject(
                JSONConfig.create().setIgnoreNullValue(false));
        json.set("protocol", result.protocol());
        json.set("operation", result.operation());
        json.set("status", result.status().name());
        json.set("relativePath", result.relativePath());
        json.set("changed", result.changed());
        json.set("message", result.message());
        json.set("failureReason", result.failureReason());
        json.set("content", result.content());
        return JSONUtil.toJsonStr(json);
    }

    static FileToolResult parse(String rawResult, String operation, String relativePath) {
        try {
            return parseStrict(rawResult, operation, relativePath);
        } catch (RuntimeException exception) {
            return FileToolResult.failed(operation, relativePath, "工具结果协议解析失败");
        }
    }

    static String clientSafeReadResult(
            String rawResult, String operation, String relativePath) {
        try {
            FileToolResult result = parseStrict(rawResult, operation, relativePath);
            if (!Set.of("readFile", "readDir").contains(operation)) {
                throw new IllegalArgumentException("只有读取工具可以清理正文载荷");
            }
            JSONObject json = new JSONObject(
                    JSONConfig.create().setIgnoreNullValue(false));
            json.set("protocol", result.protocol());
            json.set("operation", result.operation());
            json.set("status", result.status().name());
            json.set("relativePath", result.relativePath());
            json.set("changed", result.changed());
            json.set("message", result.message());
            json.set("failureReason", result.failureReason());
            json.set("content", JSONNull.NULL);
            return JSONUtil.toJsonStr(json);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    static FileToolResult parseTrustedResult(
            String rawResult, String operation) {
        FileToolResult result = decodeStrict(rawResult);
        if (!operation.equals(result.operation())) {
            throw new IllegalArgumentException("工具结果操作名不匹配");
        }
        if (!result.changed()) {
            return result;
        }
        String normalizedPath = normalizeChangedPath(result.relativePath());
        if (normalizedPath == null || normalizedPath.isBlank()) {
            throw new IllegalArgumentException("变更工具结果必须包含相对路径");
        }
        return new FileToolResult(
                result.protocol(), result.operation(), result.status(),
                normalizedPath, result.changed(),
                result.message(), result.failureReason(), result.content());
    }

    private static FileToolResult parseStrict(
            String rawResult, String operation, String relativePath) {
        FileToolResult result = parseTrustedResult(rawResult, operation);
        if (!pathsMatch(relativePath, result)) {
            throw new IllegalArgumentException("工具结果路径不匹配");
        }
        return result;
    }

    private static FileToolResult decodeStrict(String rawResult) {
        ObjectNode json = StrictToolJsonSupport.parseObject(rawResult);
        validateFields(json);
        return new FileToolResult(
                requiredString(json, "protocol"),
                requiredString(json, "operation"),
                FileToolResult.FileToolStatus.valueOf(
                        requiredString(json, "status")),
                nullableString(json, "relativePath"),
                requiredBoolean(json, "changed"),
                requiredString(json, "message"),
                nullableString(json, "failureReason"),
                nullableString(json, "content"));
    }

    static boolean isAppliedMutation(String rawResult, String operation) {
        try {
            FileToolResult result = parseTrustedResult(rawResult, operation);
            return result.status() == FileToolResult.FileToolStatus.APPLIED
                    && result.changed();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static void validateFields(ObjectNode json) {
        Set<String> fields = json.properties().stream()
                .map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
        if (!PROTOCOL_FIELDS.equals(fields)) {
            throw new IllegalArgumentException("文件工具协议字段不完整或包含未知字段");
        }
    }

    private static String requiredString(ObjectNode json, String field) {
        JsonNode value = json.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("文件工具协议字段必须是字符串: " + field);
        }
        return value.textValue();
    }

    private static String nullableString(ObjectNode json, String field) {
        JsonNode value = json.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            return value.textValue();
        }
        throw new IllegalArgumentException("文件工具协议字段必须是字符串或 null: " + field);
    }

    private static boolean requiredBoolean(ObjectNode json, String field) {
        JsonNode value = json.get(field);
        if (value == null || !value.isBoolean()) {
            throw new IllegalArgumentException("文件工具协议字段必须是布尔值: " + field);
        }
        return value.booleanValue();
    }

    private static boolean pathsMatch(String expected, FileToolResult result) {
        if (Objects.equals(expected, result.relativePath())) {
            return true;
        }
        try {
            return Objects.equals(
                    normalizeChangedPath(expected), result.changed()
                            ? result.relativePath()
                            : normalizeChangedPath(result.relativePath()));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String normalizeChangedPath(String relativePath) {
        if (relativePath == null) {
            return null;
        }
        requireSafePathText(relativePath);
        Path path = Path.of(relativePath);
        if (path.isAbsolute()) {
            throw new IllegalArgumentException("工具结果路径不能是绝对路径");
        }
        Path normalized = path.normalize();
        if (normalized.startsWith("..")) {
            throw new IllegalArgumentException("工具结果路径不能越出项目根目录");
        }
        String normalizedText = normalized.toString().replace('\\', '/');
        if (normalizedText.isBlank() || ".".equals(normalizedText)) {
            throw new IllegalArgumentException("工具结果路径不能为空或项目根目录");
        }
        return normalizedText;
    }

    private static void requireSafePathText(String relativePath) {
        if (relativePath.indexOf('\\') >= 0
                || relativePath.contains("//") || relativePath.endsWith("/")
                || relativePath.codePoints().anyMatch(codePoint ->
                codePoint <= 0x1F || codePoint >= 0x7F && codePoint <= 0x9F
                        || codePoint == 0x2028 || codePoint == 0x2029)) {
            throw new IllegalArgumentException("工具结果路径包含歧义或控制字符");
        }
    }

    static String stableSummary(BaseTool tool, FileToolResult result) {
        String path = result.relativePath() == null || result.relativePath().isBlank()
                ? "" : " " + result.relativePath();
        return "[工具调用] " + tool.getDisplayName() + path
                + "（" + statusText(result.status()) + "）";
    }

    static String rejected(String operation, String path, RuntimeException exception) {
        FileToolResult result = exception
                instanceof FileToolExecutionScopeManager.ScopeCancelledException
                ? FileToolResult.cancelled(operation, path, exception.getMessage())
                : FileToolResult.rejected(operation, path, exception.getMessage());
        return json(result);
    }

    private static String statusText(FileToolResult.FileToolStatus status) {
        return switch (status) {
            case APPLIED -> "已应用";
            case NO_CHANGE -> "未变更";
            case REJECTED -> "已拒绝";
            case NOT_FOUND -> "未找到";
            case CANCELLED -> "已取消";
            case FAILED -> "失败";
        };
    }
}
