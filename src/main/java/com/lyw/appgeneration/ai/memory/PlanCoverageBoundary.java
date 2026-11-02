package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.service.MemorySummarySnapshot;

/** 实际覆盖身份；终态登记造成的 summaryRevision 变化不使计划恢复失效。 */
public record PlanCoverageBoundary(long lastSummarizedId, long userHistoryId, String turnId,
                                   String memoryEpoch, long coveredSequence) {
    public PlanCoverageBoundary {
        if (lastSummarizedId < 0 || userHistoryId < 0 || coveredSequence < 0
                || (userHistoryId == 0 && (turnId != null || memoryEpoch != null || coveredSequence != 0))
                || (userHistoryId > 0 && (turnId == null || turnId.isBlank()
                || memoryEpoch == null || memoryEpoch.isBlank() || coveredSequence == 0))) {
            throw new IllegalArgumentException("计划恢复的摘要覆盖键无效");
        }
    }

    public static PlanCoverageBoundary of(MemorySummarySnapshot summary) {
        var frontier = summary.coverageState();
        return frontier == null ? new PlanCoverageBoundary(summary.lastSummarizedId(), 0, null, null, 0)
                : new PlanCoverageBoundary(summary.lastSummarizedId(), frontier.userHistoryId(), frontier.turnId(),
                frontier.memoryEpoch(), frontier.coveredSequence());
    }
}
