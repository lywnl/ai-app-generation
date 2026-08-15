package dev.langchain4j.service;

import dev.langchain4j.internal.Json;

import java.util.Map;
import java.util.Set;

/** 只从受信构建工具的完整终态协议中提取通用终止原因。 */
public final class ToolLoopTerminationProtocol {

    private static final String TOOL_NAME = "buildProject";
    private static final String FILE_PROTOCOL = "file-tool/v1";
    private static final Set<String> FILE_TOOLS = Set.of(
            "readFile", "readDir", "writeFile", "modifyFile",
            "deleteFile", "exit");
    private static final Set<String> FILE_RESULT_FIELDS = Set.of(
            "protocol", "operation", "status", "relativePath", "changed",
            "message", "failureReason", "content");
    private static final String PROTOCOL = "vue-build-tool/v1";
    private static final String SUCCESS_RESPONSE = "项目已生成并构建成功。";
    private static final String FAILURE_RESPONSE = "抱歉，系统遇到了一些问题，请您稍后重试修复";
    private static final Set<String> BUILD_STAGES = Set.of(
            "VALIDATION", "NPM_INSTALL", "NPM_BUILD", "DIST_CHECK");
    private static final Set<String> CANCELLATION_STAGES = Set.of(
            "VALIDATION", "NPM_INSTALL", "NPM_BUILD", "DIST_CHECK", "SUCCESS");
    private static final Set<String> FAILURE_KINDS = Set.of(
            "CODE", "DEPENDENCY", "INFRASTRUCTURE");

    private ToolLoopTerminationProtocol() {
    }

    public static ToolLoopTermination parseTrusted(String toolName, String toolResult) {
        if (toolResult == null) {
            return notTerminated();
        }
        try {
            Object parsed = Json.fromJson(toolResult, Object.class);
            if (parsed instanceof Map<?, ?> fields
                    && validFileResourceLimit(toolName, fields)) {
                return terminated(
                        ControlledTerminationReason.RESOURCE_LIMIT_EXCEEDED, null);
            }
            if (!(parsed instanceof Map<?, ?> fields)
                    || !TOOL_NAME.equals(toolName)
                    || !PROTOCOL.equals(fields.get("protocol"))
                    || !Integer.valueOf(3).equals(number(fields.get("maxAttempts")))
                    || !nonBlank(fields.get("message"))) {
                return notTerminated();
            }
            String status = string(fields.get("invocationStatus"));
            if ("COMPLETED".equals(status)) {
                return completed(fields);
            }
            if ("CANCELLED".equals(status) && validCancelled(fields)) {
                return terminated(ControlledTerminationReason.CANCELLED,
                        nullableString(fields.get("finalResponse")));
            }
            if ("REJECTED".equals(status) && validRejected(fields)) {
                return terminated(ControlledTerminationReason.PROTOCOL_ERROR,
                        nullableString(fields.get("finalResponse")));
            }
        } catch (RuntimeException ignored) {
            // 非法或未知协议按普通工具结果处理，不能中断工具链。
        }
        return notTerminated();
    }

    private static ToolLoopTermination completed(Map<?, ?> fields) {
        Integer attempt = number(fields.get("attempt"));
        if (Boolean.TRUE.equals(fields.get("success"))
                && attempt != null && attempt >= 1 && attempt <= 3
                && "SUCCESS".equals(fields.get("stage"))
                && fields.get("failureKind") == null
                && Boolean.FALSE.equals(fields.get("timedOut"))
                && Boolean.FALSE.equals(fields.get("repairable"))
                && Boolean.FALSE.equals(fields.get("reflectionRequired"))
                && "STOP".equals(fields.get("nextAction"))
                && fields.get("errorSummary") == null
                && Boolean.TRUE.equals(fields.get("terminateToolLoop"))
                && SUCCESS_RESPONSE.equals(fields.get("finalResponse"))) {
            return terminated(ControlledTerminationReason.BUILD_SUCCEEDED, SUCCESS_RESPONSE);
        }
        if (Boolean.FALSE.equals(fields.get("success"))
                && Integer.valueOf(3).equals(attempt)
                && BUILD_STAGES.contains(string(fields.get("stage")))
                && FAILURE_KINDS.contains(string(fields.get("failureKind")))
                && fields.get("timedOut") instanceof Boolean
                && Boolean.FALSE.equals(fields.get("repairable"))
                && Boolean.FALSE.equals(fields.get("reflectionRequired"))
                && "STOP".equals(fields.get("nextAction"))
                && fields.get("errorSummary") instanceof String
                && Boolean.TRUE.equals(fields.get("terminateToolLoop"))
                && FAILURE_RESPONSE.equals(fields.get("finalResponse"))) {
            return terminated(ControlledTerminationReason.BUILD_FAILED, FAILURE_RESPONSE);
        }
        return notTerminated();
    }

    private static boolean validCancelled(Map<?, ?> fields) {
        Integer attempt = number(fields.get("attempt"));
        Object stage = fields.get("stage");
        boolean identityValid = attempt == null && stage == null
                || attempt != null && attempt >= 1 && attempt <= 3
                && CANCELLATION_STAGES.contains(string(stage));
        return identityValid
                && fields.get("success") == null
                && fields.get("failureKind") == null
                && fields.get("timedOut") == null
                && Boolean.FALSE.equals(fields.get("repairable"))
                && Boolean.FALSE.equals(fields.get("reflectionRequired"))
                && "STOP".equals(fields.get("nextAction"))
                && fields.get("errorSummary") == null
                && Boolean.TRUE.equals(fields.get("terminateToolLoop"))
                && fields.get("finalResponse") == null;
    }

    private static boolean validRejected(Map<?, ?> fields) {
        return fields.get("success") == null
                && fields.get("attempt") == null
                && fields.get("stage") == null
                && fields.get("failureKind") == null
                && fields.get("timedOut") == null
                && Boolean.FALSE.equals(fields.get("repairable"))
                && Boolean.FALSE.equals(fields.get("reflectionRequired"))
                && "STOP".equals(fields.get("nextAction"))
                && fields.get("errorSummary") == null
                && Boolean.TRUE.equals(fields.get("terminateToolLoop"))
                && string(fields.get("message")).startsWith("PROTOCOL_ERROR")
                && fields.get("finalResponse") == null;
    }

    private static boolean validFileResourceLimit(
            String toolName, Map<?, ?> fields) {
        return FILE_TOOLS.contains(toolName)
                && FILE_RESULT_FIELDS.equals(fields.keySet())
                && FILE_PROTOCOL.equals(fields.get("protocol"))
                && toolName.equals(fields.get("operation"))
                && "REJECTED".equals(fields.get("status"))
                && (fields.get("relativePath") == null
                || fields.get("relativePath") instanceof String)
                && Boolean.FALSE.equals(fields.get("changed"))
                && nonBlank(fields.get("message"))
                && "RESOURCE_LIMIT_EXCEEDED".equals(fields.get("failureReason"))
                && fields.get("content") == null;
    }

    private static Integer number(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        int integer = number.intValue();
        return number.doubleValue() == integer ? integer : null;
    }

    private static String string(Object value) {
        return value instanceof String text ? text : "";
    }

    private static String nullableString(Object value) {
        return value instanceof String text ? text : null;
    }

    private static boolean nonBlank(Object value) {
        return value instanceof String text && !text.isBlank();
    }

    private static ToolLoopTermination terminated(
            ControlledTerminationReason reason, String finalResponse) {
        return new ToolLoopTermination(true, reason, finalResponse);
    }

    private static ToolLoopTermination notTerminated() {
        return new ToolLoopTermination(false, null, null);
    }

    public enum ControlledTerminationReason {
        BUILD_SUCCEEDED,
        BUILD_FAILED,
        EVALUATION_COMPLETED,
        CANCELLED,
        PROTOCOL_ERROR,
        LOOP_LIMIT_EXCEEDED,
        RESOURCE_LIMIT_EXCEEDED
    }

    public record ControlledTermination(
            ControlledTerminationReason reason,
            String finalResponse) {

        public ControlledTermination {
            if (reason == null) {
                throw new IllegalArgumentException("受控终止原因不能为空");
            }
            boolean validFinalResponse = switch (reason) {
                case BUILD_SUCCEEDED -> SUCCESS_RESPONSE.equals(finalResponse);
                case BUILD_FAILED -> FAILURE_RESPONSE.equals(finalResponse);
                case EVALUATION_COMPLETED, CANCELLED, PROTOCOL_ERROR,
                        LOOP_LIMIT_EXCEEDED, RESOURCE_LIMIT_EXCEEDED ->
                        finalResponse == null;
            };
            if (!validFinalResponse) {
                throw new IllegalArgumentException("受控终止原因与最终响应不匹配");
            }
        }
    }

    public record ToolLoopTermination(
            boolean terminate,
            ControlledTerminationReason reason,
            String finalResponse) {

        public ToolLoopTermination {
            if (terminate != (reason != null)) {
                throw new IllegalArgumentException("终止标记与原因必须一致");
            }
        }
    }
}
