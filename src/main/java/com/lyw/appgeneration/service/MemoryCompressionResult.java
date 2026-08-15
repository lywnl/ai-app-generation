package com.lyw.appgeneration.service;

import java.util.Objects;

/** 一次 L1 摘要压缩的类型化结果。 */
public record MemoryCompressionResult(Status status,
                                      long summarizedThroughId,
                                      int summaryTokens,
                                      String detail) {

    public MemoryCompressionResult {
        status = Objects.requireNonNull(status, "压缩状态不能为空");
        if (summarizedThroughId < 0L || summaryTokens < 0) {
            throw new IllegalArgumentException("摘要游标和 Token 不能为负数");
        }
        detail = detail == null ? "" : detail;
    }

    public enum Status {
        COMPRESSED,
        NOTHING_TO_COMPRESS,
        TIMED_OUT,
        MODEL_FAILED,
        OUTPUT_STILL_TOO_LARGE,
        DELETE_REJECTED
    }
}
