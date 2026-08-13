package com.lyw.appgeneration.ai.tools;

import java.util.Objects;
import java.util.Set;

/** 文件工具返回给模型的受信结构化协议。 */
public record FileToolResult(
        String protocol,
        String operation,
        FileToolStatus status,
        String relativePath,
        boolean changed,
        String message,
        String content
) {

    public static final String PROTOCOL = "file-tool/v1";
    private static final Set<String> READ_OPERATIONS = Set.of("readFile", "readDir");
    private static final Set<String> MUTATION_OPERATIONS = Set.of(
            "writeFile", "modifyFile", "deleteFile");
    private static final Set<String> SUPPORTED_OPERATIONS = Set.of(
            "readFile", "readDir", "writeFile", "modifyFile", "deleteFile", "exit");

    public FileToolResult {
        if (!PROTOCOL.equals(protocol)) {
            throw new IllegalArgumentException("文件工具协议版本不受支持");
        }
        Objects.requireNonNull(operation, "operation 不能为空");
        Objects.requireNonNull(status, "status 不能为空");
        if (!SUPPORTED_OPERATIONS.contains(operation)) {
            throw new IllegalArgumentException("不支持的文件工具操作");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("文件工具状态说明不能为空");
        }
        validateStatus(operation, status);
        validateChanged(operation, status, changed);
        validateContent(operation, status, content);
    }

    public static FileToolResult applied(
            String operation, String path, boolean changed, String message) {
        return new FileToolResult(
                PROTOCOL, operation, FileToolStatus.APPLIED,
                path, changed, message, null);
    }

    public static FileToolResult readApplied(
            String operation, String path, String message, String content) {
        return new FileToolResult(
                PROTOCOL, operation, FileToolStatus.APPLIED,
                path, false, message, content);
    }

    public static FileToolResult noChange(String operation, String path, String message) {
        return result(operation, FileToolStatus.NO_CHANGE, path, message);
    }

    public static FileToolResult rejected(String operation, String path, String message) {
        return result(operation, FileToolStatus.REJECTED, path, message);
    }

    public static FileToolResult notFound(String operation, String path, String message) {
        return result(operation, FileToolStatus.NOT_FOUND, path, message);
    }

    public static FileToolResult cancelled(String operation, String path, String message) {
        return result(operation, FileToolStatus.CANCELLED, path, message);
    }

    public static FileToolResult failed(String operation, String path, String message) {
        return result(operation, FileToolStatus.FAILED, path, message);
    }

    private static FileToolResult result(
            String operation, FileToolStatus status, String path, String message) {
        return new FileToolResult(
                PROTOCOL, operation, status, path, false, message, null);
    }

    private static void validateStatus(String operation, FileToolStatus status) {
        boolean supported = switch (operation) {
            case "readFile", "readDir" -> status != FileToolStatus.NO_CHANGE;
            case "writeFile" -> status != FileToolStatus.NOT_FOUND;
            case "modifyFile" -> true;
            case "deleteFile" -> status != FileToolStatus.NO_CHANGE;
            case "exit" -> status != FileToolStatus.NOT_FOUND;
            default -> false;
        };
        if (!supported) {
            throw new IllegalArgumentException("文件工具操作与状态不匹配");
        }
    }

    private static void validateChanged(
            String operation, FileToolStatus status, boolean changed) {
        boolean shouldChange = status == FileToolStatus.APPLIED
                && MUTATION_OPERATIONS.contains(operation);
        if (changed != shouldChange) {
            throw new IllegalArgumentException("文件工具操作与变更状态不匹配");
        }
    }

    private static void validateContent(
            String operation, FileToolStatus status, String content) {
        boolean shouldContainContent = status == FileToolStatus.APPLIED
                && READ_OPERATIONS.contains(operation);
        if (shouldContainContent != (content != null)) {
            throw new IllegalArgumentException("文件工具操作与内容载荷不匹配");
        }
    }

    public enum FileToolStatus {
        APPLIED,
        NO_CHANGE,
        REJECTED,
        NOT_FOUND,
        CANCELLED,
        FAILED
    }
}
