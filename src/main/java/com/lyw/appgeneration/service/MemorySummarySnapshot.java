package com.lyw.appgeneration.service;

import java.util.Objects;

/** 同一次持久化读取的摘要正文与覆盖边界，必须成对用于请求组装和历史裁剪。 */
public record MemorySummarySnapshot(String summary, long lastSummarizedId) {

    public MemorySummarySnapshot {
        Objects.requireNonNull(summary, "摘要正文不能为空");
        if (lastSummarizedId < 0L
                || (lastSummarizedId == 0L) != summary.isBlank()) {
            throw new IllegalArgumentException("摘要正文与覆盖边界不一致");
        }
    }

    public static MemorySummarySnapshot empty() {
        return new MemorySummarySnapshot("", 0L);
    }
}
