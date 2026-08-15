package com.lyw.appgeneration.ai.memory;

import java.util.Objects;

/** 一次上下文门禁的类型化结果。 */
public record ContextAdmissionResult(
        ContextCompressionMode mode,
        int initialTokens,
        int finalTokens,
        long summarizeThroughId,
        FailureReason failureReason,
        String detail) {

    public ContextAdmissionResult {
        mode = Objects.requireNonNull(mode, "压缩模式不能为空");
        failureReason = Objects.requireNonNull(
                failureReason, "失败原因不能为空");
        if (initialTokens < 0 || finalTokens < 0
                || summarizeThroughId < 0L) {
            throw new IllegalArgumentException(
                    "Token 与摘要边界不能为负数");
        }
        detail = detail == null ? "" : detail;
    }

    public boolean canProceed() {
        return mode == ContextCompressionMode.NORMAL
                || mode == ContextCompressionMode.ASYNC_SCHEDULED
                || mode == ContextCompressionMode.BLOCKING_COMPLETED;
    }

    public enum FailureReason {
        NONE,
        NO_COMPRESSIBLE_TURN,
        ALIGNMENT_FAILED,
        CURSOR_READ_FAILED,
        PREFIX_CHANGED,
        EXECUTOR_REJECTED,
        TURN_TERMINATED,
        TIMED_OUT,
        INTERRUPTED,
        MODEL_FAILED,
        DELETE_REJECTED,
        STILL_OVER_HARD_LIMIT,
        UNKNOWN
    }
}
