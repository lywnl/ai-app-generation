package com.lyw.appgeneration.core.builder;

import java.util.Objects;

/**
 * Vue 工程构建的不可变诊断结果。
 *
 * @param success 构建是否成功
 * @param stage 结束时所处阶段
 * @param exitCode 最后一个已启动进程的退出码；进程未启动或超时时为空
 * @param timedOut 是否因命令超时结束
 * @param outputTail 构建输出的最后至多 8000 个字符
 * @param durationMillis 从校验到结束的总耗时
 */
public record BuildResult(
        boolean success,
        BuildStage stage,
        Integer exitCode,
        boolean timedOut,
        boolean cancelled,
        VueBuildFailureKind failureKind,
        String outputTail,
        long durationMillis
) {

    public static final int MAX_OUTPUT_TAIL_CHARS = 8_000;

    public BuildResult {
        stage = Objects.requireNonNull(stage, "stage 不能为空");
        validateTerminalState(success, stage, exitCode, timedOut, cancelled, failureKind);
        outputTail = boundedTail(outputTail);
        if (durationMillis < 0) {
            throw new IllegalArgumentException("durationMillis 不能为负数");
        }
    }

    public BuildResult(
            boolean success,
            BuildStage stage,
            Integer exitCode,
            boolean timedOut,
            String outputTail,
            long durationMillis) {
        this(success, stage, exitCode, timedOut, false,
                legacyFailureKind(success, stage, timedOut), outputTail, durationMillis);
    }

    private static VueBuildFailureKind legacyFailureKind(
            boolean success, BuildStage stage, boolean timedOut) {
        Objects.requireNonNull(stage, "stage 不能为空");
        if (success) {
            return null;
        }
        if (timedOut) {
            return VueBuildFailureKind.INFRASTRUCTURE;
        }
        return switch (stage) {
            case VALIDATION, NPM_BUILD, DIST_CHECK -> VueBuildFailureKind.CODE;
            case NPM_INSTALL -> VueBuildFailureKind.DEPENDENCY;
            case SUCCESS -> throw new IllegalArgumentException("失败结果不能处于 SUCCESS 阶段");
        };
    }

    private static void validateTerminalState(
            boolean success,
            BuildStage stage,
            Integer exitCode,
            boolean timedOut,
            boolean cancelled,
            VueBuildFailureKind failureKind) {
        if (success) {
            if (stage != BuildStage.SUCCESS || !Integer.valueOf(0).equals(exitCode)
                    || timedOut || cancelled || failureKind != null) {
                throw new IllegalArgumentException("成功结果必须为纯净的 SUCCESS 终态");
            }
            return;
        }
        if (stage == BuildStage.SUCCESS || (timedOut && cancelled)) {
            throw new IllegalArgumentException("失败结果的阶段或终止原因不合法");
        }
        if (cancelled) {
            if (failureKind != null || timedOut || exitCode != null) {
                throw new IllegalArgumentException("取消结果不能包含退出码、超时或失败分类");
            }
            return;
        }
        if (failureKind == null) {
            throw new IllegalArgumentException("普通失败必须包含 failureKind");
        }
        if (timedOut && exitCode != null) {
            throw new IllegalArgumentException("超时结果不能包含退出码");
        }
        if (!timedOut && stage != BuildStage.DIST_CHECK
                && Integer.valueOf(0).equals(exitCode)) {
            throw new IllegalArgumentException("普通失败不能包含成功退出码");
        }
    }

    static String boundedTail(String output) {
        String safeOutput = output == null ? "" : output;
        int start = Math.max(0, safeOutput.length() - MAX_OUTPUT_TAIL_CHARS);
        if (start > 0 && Character.isLowSurrogate(safeOutput.charAt(start))
                && Character.isHighSurrogate(safeOutput.charAt(start - 1))) {
            start++;
        }
        return safeOutput.substring(start);
    }
}
