package com.lyw.appgeneration.service;

import com.lyw.appgeneration.ai.memory.SummaryCoverage;
import com.lyw.appgeneration.ai.memory.SummaryCoverageCodec;

import java.util.Objects;

/** 同一次持久化读取的摘要正文与覆盖边界，必须成对用于请求组装和历史裁剪。 */
public record MemorySummarySnapshot(String summary, long lastSummarizedId,
                                    long summaryRevision, int coverageVersion,
                                    SummaryCoverage coverageState) {

    /** 保持旧调用的结构校验；五段格式及 Token 校验仍由召回契约负责。 */
    public MemorySummarySnapshot(String summary, long lastSummarizedId) {
        this(summary, lastSummarizedId, 0L, SummaryCoverageCodec.LEGACY_VERSION, null);
    }

    public MemorySummarySnapshot {
        Objects.requireNonNull(summary, "摘要正文不能为空");
        if (lastSummarizedId < 0L || summaryRevision < 0L
                || (coverageVersion != SummaryCoverageCodec.LEGACY_VERSION
                && coverageVersion != SummaryCoverageCodec.CURRENT_VERSION)) {
            throw new IllegalArgumentException("摘要游标、修订或覆盖版本无效");
        }
        if (coverageVersion == SummaryCoverageCodec.LEGACY_VERSION && coverageState != null) {
            throw new IllegalArgumentException("旧摘要不能携带部分覆盖");
        }
        if (coverageVersion == SummaryCoverageCodec.CURRENT_VERSION && summaryRevision == 0L) {
            throw new IllegalArgumentException("新摘要必须具有已提交修订");
        }
        boolean hasCoverage = lastSummarizedId > 0L || coverageState != null;
        if (hasCoverage == summary.isBlank()
                || (!hasCoverage && summaryRevision != 0L)
                || (coverageState != null && coverageState.userHistoryId() <= lastSummarizedId)) {
            throw new IllegalArgumentException("摘要正文与覆盖边界不一致");
        }
    }

    public static MemorySummarySnapshot empty() {
        return new MemorySummarySnapshot("", 0L);
    }
}
