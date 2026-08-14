package com.lyw.appgeneration.ai.tools;

import com.lyw.appgeneration.core.builder.BuildStage;
import com.lyw.appgeneration.core.builder.VueBuildFailureKind;

import java.util.Objects;

/** Vue 构建工具返回给模型的受信结构化协议。 */
public record BuildProjectToolResult(
        String protocol,
        BuildInvocationStatus invocationStatus,
        Boolean success,
        Integer attempt,
        int maxAttempts,
        BuildStage stage,
        VueBuildFailureKind failureKind,
        Boolean timedOut,
        boolean repairable,
        boolean reflectionRequired,
        BuildNextAction nextAction,
        String message,
        String errorSummary,
        boolean terminateToolLoop,
        String finalResponse
) {

    public static final String PROTOCOL = "vue-build-tool/v1";
    public static final int MAX_ATTEMPTS = 3;
    public static final String SUCCESS_RESPONSE = "项目已生成并构建成功。";
    public static final String FAILURE_RESPONSE = "抱歉，系统遇到了一些问题，请您稍后重试修复";

    public BuildProjectToolResult {
        if (!PROTOCOL.equals(protocol) || maxAttempts != MAX_ATTEMPTS) {
            throw new IllegalArgumentException("Vue 构建工具协议版本不受支持");
        }
        Objects.requireNonNull(invocationStatus, "invocationStatus 不能为空");
        Objects.requireNonNull(message, "message 不能为空");
        validateState(invocationStatus, success, attempt, stage, failureKind, timedOut,
                repairable, reflectionRequired, nextAction, errorSummary,
                terminateToolLoop, finalResponse);
    }

    public static BuildProjectToolResult completedSuccess(int attempt) {
        return completed(true, attempt, BuildStage.SUCCESS, null, false,
                false, false, BuildNextAction.STOP, "构建成功", null,
                true, SUCCESS_RESPONSE);
    }

    public static BuildProjectToolResult completedFailure(
            int attempt,
            BuildStage stage,
            VueBuildFailureKind failureKind,
            boolean timedOut,
            String errorSummary) {
        BuildNextAction action = failureAction(attempt, failureKind);
        boolean repairable = attempt < MAX_ATTEMPTS
                && failureKind == VueBuildFailureKind.CODE;
        boolean reflectionRequired = attempt == 2;
        boolean terminate = attempt >= MAX_ATTEMPTS;
        return completed(false, attempt, stage, failureKind, timedOut,
                repairable, reflectionRequired, action, failureMessage(attempt, action),
                errorSummary, terminate, terminate ? FAILURE_RESPONSE : null);
    }

    public static BuildProjectToolResult buildInProgress() {
        return transientResult(BuildInvocationStatus.BUILD_IN_PROGRESS,
                "当前已有构建正在执行", false, null);
    }

    public static BuildProjectToolResult mutationRequired(String message) {
        return transientResult(
                BuildInvocationStatus.REJECTED, message, false, BuildNextAction.REPAIR);
    }

    public static BuildProjectToolResult rejected(String message) {
        return transientResult(
                BuildInvocationStatus.REJECTED, message, true, BuildNextAction.STOP);
    }

    public static BuildProjectToolResult cancelled(
            Integer attempt, BuildStage stage, String message) {
        return new BuildProjectToolResult(PROTOCOL, BuildInvocationStatus.CANCELLED,
                null, attempt, MAX_ATTEMPTS, stage, null, null,
                false, false, BuildNextAction.STOP, message, null,
                true, null);
    }

    private static BuildProjectToolResult completed(
            boolean success,
            int attempt,
            BuildStage stage,
            VueBuildFailureKind failureKind,
            boolean timedOut,
            boolean repairable,
            boolean reflectionRequired,
            BuildNextAction nextAction,
            String message,
            String errorSummary,
            boolean terminate,
            String finalResponse) {
        return new BuildProjectToolResult(PROTOCOL, BuildInvocationStatus.COMPLETED,
                success, attempt, MAX_ATTEMPTS, stage, failureKind, timedOut,
                repairable, reflectionRequired, nextAction, message, errorSummary,
                terminate, finalResponse);
    }

    private static BuildProjectToolResult transientResult(
            BuildInvocationStatus status,
            String message,
            boolean terminate,
            BuildNextAction nextAction) {
        return new BuildProjectToolResult(PROTOCOL, status, null, null,
                MAX_ATTEMPTS, null, null, null, false, false, nextAction,
                message, null, terminate, null);
    }

    private static BuildNextAction failureAction(
            int attempt, VueBuildFailureKind failureKind) {
        if (attempt >= MAX_ATTEMPTS) {
            return BuildNextAction.STOP;
        }
        if (attempt == 2) {
            return BuildNextAction.FINAL_DIAGNOSIS;
        }
        return failureKind == VueBuildFailureKind.CODE
                ? BuildNextAction.REPAIR : BuildNextAction.RETRY_BUILD;
    }

    private static String failureMessage(int attempt, BuildNextAction action) {
        return switch (action) {
            case REPAIR -> "第 1 次构建失败，请进行最小代码修复";
            case RETRY_BUILD -> "第 1 次构建失败，请直接重试构建";
            case FINAL_DIAGNOSIS -> "第 2 次构建失败，请进行最终诊断";
            case STOP -> "第 " + attempt + " 次构建失败，已停止自动修复";
        };
    }

    private static void validateState(
            BuildInvocationStatus status,
            Boolean success,
            Integer attempt,
            BuildStage stage,
            VueBuildFailureKind failureKind,
            Boolean timedOut,
            boolean repairable,
            boolean reflectionRequired,
            BuildNextAction nextAction,
            String errorSummary,
            boolean terminateToolLoop,
            String finalResponse) {
        if (status == BuildInvocationStatus.COMPLETED) {
            if (success == null || attempt == null || stage == null
                    || timedOut == null || nextAction == null) {
                throw new IllegalArgumentException("已完成构建缺少结果字段");
            }
            if (attempt < 1 || attempt > MAX_ATTEMPTS) {
                throw new IllegalArgumentException("构建次数超出协议范围");
            }
            validateCompletedState(success, attempt, stage, failureKind, timedOut,
                    repairable, reflectionRequired, nextAction, errorSummary,
                    terminateToolLoop, finalResponse);
            return;
        }
        if (status == BuildInvocationStatus.CANCELLED) {
            if (success != null || timedOut != null || nextAction != BuildNextAction.STOP
                    || failureKind != null || repairable || reflectionRequired
                    || errorSummary != null || !terminateToolLoop
                    || (attempt == null) != (stage == null)
                    || finalResponse != null) {
                throw new IllegalArgumentException("取消结果字段组合不合法");
            }
            if (attempt != null && (attempt < 1 || attempt > MAX_ATTEMPTS)) {
                throw new IllegalArgumentException("取消结果的构建次数超出协议范围");
            }
            return;
        }
        if (success != null || attempt != null || stage != null
                || failureKind != null || timedOut != null || repairable
                || reflectionRequired || errorSummary != null) {
            throw new IllegalArgumentException("未完成调用不能伪造构建结果字段");
        }
        if (status == BuildInvocationStatus.BUILD_IN_PROGRESS
                && (nextAction != null || terminateToolLoop || finalResponse != null)) {
            throw new IllegalArgumentException("构建占用状态不能终止工具循环");
        }
        if (status == BuildInvocationStatus.REJECTED
                && (finalResponse != null
                || terminateToolLoop != (nextAction == BuildNextAction.STOP)
                || (!terminateToolLoop && nextAction != BuildNextAction.REPAIR))) {
            throw new IllegalArgumentException("拒绝结果的终止字段组合不合法");
        }
    }

    private static void validateCompletedState(
            boolean success,
            int attempt,
            BuildStage stage,
            VueBuildFailureKind failureKind,
            boolean timedOut,
            boolean repairable,
            boolean reflectionRequired,
            BuildNextAction nextAction,
            String errorSummary,
            boolean terminateToolLoop,
            String finalResponse) {
        if (success) {
            if (stage != BuildStage.SUCCESS || failureKind != null || timedOut
                    || repairable || reflectionRequired || nextAction != BuildNextAction.STOP
                    || errorSummary != null || !terminateToolLoop
                    || !SUCCESS_RESPONSE.equals(finalResponse)) {
                throw new IllegalArgumentException("成功构建结果字段组合不合法");
            }
            return;
        }
        BuildNextAction expectedAction = failureAction(attempt, failureKind);
        boolean expectedRepairable = attempt < MAX_ATTEMPTS
                && failureKind == VueBuildFailureKind.CODE;
        boolean expectedReflection = attempt == 2;
        boolean expectedTerminate = attempt == MAX_ATTEMPTS;
        if (stage == BuildStage.SUCCESS || failureKind == null || errorSummary == null
                || repairable != expectedRepairable
                || reflectionRequired != expectedReflection
                || nextAction != expectedAction
                || terminateToolLoop != expectedTerminate
                || (expectedTerminate
                ? !FAILURE_RESPONSE.equals(finalResponse)
                : finalResponse != null)) {
            throw new IllegalArgumentException("失败构建结果字段组合不合法");
        }
    }

    public enum BuildInvocationStatus {
        COMPLETED,
        BUILD_IN_PROGRESS,
        REJECTED,
        CANCELLED
    }

    public enum BuildNextAction {
        REPAIR,
        RETRY_BUILD,
        FINAL_DIAGNOSIS,
        STOP
    }
}
