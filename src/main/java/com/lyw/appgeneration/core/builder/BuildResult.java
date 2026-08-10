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
        String outputTail,
        long durationMillis
) {

    public static final int MAX_OUTPUT_TAIL_CHARS = 8_000;

    public BuildResult {
        stage = Objects.requireNonNull(stage, "stage 不能为空");
        outputTail = boundedTail(outputTail);
        if (durationMillis < 0) {
            throw new IllegalArgumentException("durationMillis 不能为负数");
        }
    }

    static String boundedTail(String output) {
        String safeOutput = output == null ? "" : output;
        int start = Math.max(0, safeOutput.length() - MAX_OUTPUT_TAIL_CHARS);
        return safeOutput.substring(start);
    }
}
