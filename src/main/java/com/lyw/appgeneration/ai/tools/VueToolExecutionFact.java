package com.lyw.appgeneration.ai.tools;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 从真实工具返回协议中提取的最小可信 Vue 回合事实。 */
public final class VueToolExecutionFact {

    public static final int MAX_PATH_LENGTH = 512;
    public static final int MAX_ERROR_SUMMARY_LENGTH = 256;

    private static final Set<String> FILE_TOOLS = Set.of(
            "readFile", "readDir", "writeFile", "modifyFile", "deleteFile", "exit");
    private static final Set<String> READ_TOOLS = Set.of("readFile", "readDir");

    private final String toolName;
    private final String relativePath;
    private final String changedRelativePath;
    private final ExecutionStatus status;
    private final Integer buildAttempt;
    private final String buildErrorSummary;

    private VueToolExecutionFact(
            String toolName,
            String relativePath,
            String changedRelativePath,
            ExecutionStatus status,
            Integer buildAttempt,
            String buildErrorSummary) {
        this.toolName = Objects.requireNonNull(toolName, "工具名不能为空");
        this.relativePath = relativePath;
        this.changedRelativePath = changedRelativePath;
        this.status = Objects.requireNonNull(status, "工具状态不能为空");
        this.buildAttempt = buildAttempt;
        this.buildErrorSummary = buildErrorSummary;
    }

    public static Optional<VueToolExecutionFact> parse(
            String toolName, String rawResult) {
        try {
            if ("buildProject".equals(toolName)) {
                return Optional.of(fromBuildResult(
                        BuildProjectProtocolSupport.parse(rawResult)));
            }
            if (!FILE_TOOLS.contains(toolName)) {
                return Optional.empty();
            }
            return Optional.of(fromFileResult(
                    FileToolProtocolSupport.parseTrustedResult(rawResult, toolName)));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static VueToolExecutionFact fromFileResult(FileToolResult result) {
        String path = trustedObservedPath(result);
        String changedPath = result.changed() ? path : null;
        return new VueToolExecutionFact(
                result.operation(), path, changedPath,
                fileStatus(result.status()), null, null);
    }

    private static String trustedObservedPath(FileToolResult result) {
        if ("exit".equals(result.operation()) || result.relativePath() == null) {
            return null;
        }
        try {
            return normalizeRelativePath(result.relativePath());
        } catch (IllegalArgumentException exception) {
            if (result.status() == FileToolResult.FileToolStatus.APPLIED) {
                throw exception;
            }
            // 非变更失败仍保留工具状态，但不传播未通过信任边界的路径。
            return null;
        }
    }

    private static VueToolExecutionFact fromBuildResult(
            BuildProjectToolResult result) {
        return new VueToolExecutionFact(
                "buildProject", null, null, buildStatus(result),
                result.attempt(), structuredBuildErrorSummary(result));
    }

    private static ExecutionStatus fileStatus(
            FileToolResult.FileToolStatus status) {
        return switch (status) {
            case APPLIED -> ExecutionStatus.SUCCEEDED;
            case NO_CHANGE -> ExecutionStatus.NO_CHANGE;
            case REJECTED -> ExecutionStatus.REJECTED;
            case NOT_FOUND -> ExecutionStatus.NOT_FOUND;
            case CANCELLED -> ExecutionStatus.CANCELLED;
            case FAILED -> ExecutionStatus.FAILED;
        };
    }

    private static ExecutionStatus buildStatus(BuildProjectToolResult result) {
        return switch (result.invocationStatus()) {
            case COMPLETED -> Boolean.TRUE.equals(result.success())
                    ? ExecutionStatus.SUCCEEDED
                    : Boolean.TRUE.equals(result.timedOut())
                    ? ExecutionStatus.TIMED_OUT : ExecutionStatus.FAILED;
            case BUILD_IN_PROGRESS -> ExecutionStatus.IN_PROGRESS;
            case REJECTED -> ExecutionStatus.REJECTED;
            case CANCELLED -> ExecutionStatus.CANCELLED;
        };
    }

    private static String normalizeRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()
                || relativePath.length() > MAX_PATH_LENGTH) {
            throw new IllegalArgumentException("工具事实路径为空或超长");
        }
        requireSafeSingleLine(relativePath, "工具事实路径包含非法字符");
        if (relativePath.indexOf('\\') >= 0
                || relativePath.contains("//") || relativePath.endsWith("/")) {
            throw new IllegalArgumentException("工具事实路径包含歧义分隔符");
        }
        Path path = Path.of(relativePath);
        if (path.isAbsolute()) {
            throw new IllegalArgumentException("工具事实路径不能是绝对路径");
        }
        Path normalized = path.normalize();
        if (normalized.startsWith("..")) {
            throw new IllegalArgumentException("工具事实路径不能越出项目根目录");
        }
        String normalizedText = normalized.toString().replace('\\', '/');
        if (normalizedText.isBlank() || ".".equals(normalizedText)
                || normalizedText.length() > MAX_PATH_LENGTH) {
            throw new IllegalArgumentException("工具事实路径无效");
        }
        return normalizedText;
    }

    private static String structuredBuildErrorSummary(
            BuildProjectToolResult result) {
        if (result.invocationStatus()
                != BuildProjectToolResult.BuildInvocationStatus.COMPLETED
                || Boolean.TRUE.equals(result.success())) {
            return null;
        }
        String resultText = Boolean.TRUE.equals(result.timedOut())
                ? "超时" : "失败";
        return "阶段=" + result.stage().name()
                + "，失败类型=" + result.failureKind().name()
                + "，结果=" + resultText;
    }

    private static void requireSafeSingleLine(String value, String message) {
        if (value.codePoints().anyMatch(VueToolExecutionFact::isControl)) {
            throw new IllegalArgumentException(message);
        }
    }

    private static boolean isControl(int codePoint) {
        return codePoint <= 0x1F
                || codePoint >= 0x7F && codePoint <= 0x9F
                || codePoint == 0x2028 || codePoint == 0x2029;
    }

    public String toolName() {
        return toolName;
    }

    public String relativePath() {
        return relativePath;
    }

    public String changedRelativePath() {
        return changedRelativePath;
    }

    public ExecutionStatus status() {
        return status;
    }

    public Integer buildAttempt() {
        return buildAttempt;
    }

    public String buildErrorSummary() {
        return buildErrorSummary;
    }

    public boolean isRead() {
        return READ_TOOLS.contains(toolName);
    }

    public enum ExecutionStatus {
        SUCCEEDED,
        NO_CHANGE,
        REJECTED,
        NOT_FOUND,
        CANCELLED,
        FAILED,
        TIMED_OUT,
        IN_PROGRESS
    }
}
