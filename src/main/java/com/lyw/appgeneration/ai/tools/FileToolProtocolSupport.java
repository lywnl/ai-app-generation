package com.lyw.appgeneration.ai.tools;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.nio.file.Path;
import java.util.Objects;

/** 文件工具共享的协议序列化与稳定文本解析。 */
final class FileToolProtocolSupport {

    private FileToolProtocolSupport() {
    }

    static String json(FileToolResult result) {
        JSONObject json = new JSONObject();
        json.set("protocol", result.protocol());
        json.set("operation", result.operation());
        json.set("status", result.status().name());
        json.set("relativePath", result.relativePath());
        json.set("changed", result.changed());
        json.set("message", result.message());
        return JSONUtil.toJsonStr(json);
    }

    static FileToolResult parse(String rawResult, String operation, String relativePath) {
        try {
            JSONObject json = JSONUtil.parseObj(rawResult);
            FileToolResult result = new FileToolResult(
                    json.getStr("protocol"),
                    json.getStr("operation"),
                    FileToolResult.FileToolStatus.valueOf(json.getStr("status")),
                    json.getStr("relativePath"),
                    Boolean.TRUE.equals(json.getBool("changed")),
                    json.getStr("message"));
            if (!FileToolResult.PROTOCOL.equals(result.protocol())
                    || !operation.equals(result.operation())
                    || !Objects.equals(normalizePath(relativePath),
                    normalizePath(result.relativePath()))) {
                throw new IllegalArgumentException("工具结果协议、操作名或路径不匹配");
            }
            return result;
        } catch (RuntimeException exception) {
            return FileToolResult.failed(operation, relativePath, "工具结果协议解析失败");
        }
    }

    private static String normalizePath(String relativePath) {
        if (relativePath == null) {
            return null;
        }
        Path path = Path.of(relativePath);
        if (path.isAbsolute()) {
            throw new IllegalArgumentException("工具结果路径不能是绝对路径");
        }
        Path normalized = path.normalize();
        if (normalized.startsWith("..")) {
            throw new IllegalArgumentException("工具结果路径不能越出项目根目录");
        }
        return normalized.toString();
    }

    static String stableSummary(BaseTool tool, FileToolResult result) {
        String path = result.relativePath() == null ? "" : " " + result.relativePath();
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
