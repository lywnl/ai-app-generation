package com.lyw.appgeneration.ai.tools;

import java.util.Objects;

/** 文件工具返回给模型的受信结构化协议。 */
public record FileToolResult(
        String protocol,
        String operation,
        FileToolStatus status,
        String relativePath,
        boolean changed,
        String message
) {

    public static final String PROTOCOL = "file-tool/v1";

    public FileToolResult {
        if (!PROTOCOL.equals(protocol)) {
            throw new IllegalArgumentException("文件工具协议版本不受支持");
        }
        Objects.requireNonNull(operation, "operation 不能为空");
        Objects.requireNonNull(status, "status 不能为空");
        message = message == null ? "" : message;
        if (changed && status != FileToolStatus.APPLIED) {
            throw new IllegalArgumentException("只有已应用操作才能标记文件已变更");
        }
    }

    public static FileToolResult applied(
            String operation, String path, boolean changed, String message) {
        return new FileToolResult(
                PROTOCOL, operation, FileToolStatus.APPLIED, path, changed, message);
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
        return new FileToolResult(PROTOCOL, operation, status, path, false, message);
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
