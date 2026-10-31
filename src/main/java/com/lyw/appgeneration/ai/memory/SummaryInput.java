package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.service.MemorySummarySnapshot;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 一个草稿的显式来源、原摘要修订和目标覆盖；只有整份草稿成功后才允许提交目标。 */
public record SummaryInput(long appId, long userId, MemorySummarySnapshot previous,
                            L0Snapshot source, List<SummaryUnit> selected,
                            long targetHistoryCursor, SummaryCoverage targetCoverage,
                            Map<Long, DecisionEvidence> evidenceSnapshots, Map<Long, String> historyFingerprints) {
    public SummaryInput {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(source);
        selected = List.copyOf(selected);
        evidenceSnapshots = Map.copyOf(evidenceSnapshots);
        historyFingerprints = Map.copyOf(historyFingerprints);
        if (appId <= 0 || userId <= 0 || !source.memoryId().equals(Long.toString(appId))
                || targetHistoryCursor < previous.lastSummarizedId()
                || selected.stream().anyMatch(unit -> !unit.complete())
                || (targetCoverage != null && targetCoverage.userHistoryId() <= targetHistoryCursor)) {
            throw new IllegalArgumentException("摘要输入来源或目标覆盖无效");
        }
    }

    public MemorySummarySnapshot committed(String summary) {
        return new MemorySummarySnapshot(summary, targetHistoryCursor, Math.addExact(previous.summaryRevision(), 1),
                SummaryCoverageCodec.CURRENT_VERSION, targetCoverage);
    }
}
