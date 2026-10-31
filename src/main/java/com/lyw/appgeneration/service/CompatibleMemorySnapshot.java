package com.lyw.appgeneration.service;

import com.lyw.appgeneration.ai.memory.L0Snapshot;
import com.lyw.appgeneration.ai.memory.L0SourceOperations;
import com.lyw.appgeneration.ai.memory.MemorySummaryFormat;
import dev.langchain4j.data.message.UserMessage;

import java.util.Objects;

/**
 * 一次兼容性校验通过后固定的摘要和原文副本；下游只能消费此副本，不再隐式读取活动窗口。
 * 新摘要可搭配尚未回收的旧原文，已复制的完整旧组合也不受之后普通回收的影响。
 */
public record CompatibleMemorySnapshot(long appId, long userHistoryId, String turnId,
                                        MemorySummarySnapshot summary, L0Snapshot source) {
    public CompatibleMemorySnapshot {
        Objects.requireNonNull(summary);
        Objects.requireNonNull(source);
        if (appId <= 0 || userHistoryId <= summary.lastSummarizedId() || turnId == null
                || !source.memoryId().equals(Long.toString(appId)) || !source.hasKnownSource()
                || summary.summaryRevision() < source.appliedSummaryRevision()) {
            throw new IllegalArgumentException("摘要无法承接原文窗口的已应用修订");
        }
        var active = source.turn(userHistoryId);
        if (!summary.summary().isEmpty() && !MemorySummaryFormat.isValid(summary.summary())) {
            throw new IllegalArgumentException("相容快照的摘要正文格式无效");
        }
        if (active == null || !active.turnId().equals(turnId) || active.closed()
                || source.turns().getLast().userHistoryId() != userHistoryId
                || source.entries().stream().noneMatch(entry -> entry.userHistoryId() == userHistoryId
                && entry.payload() instanceof UserMessage)) {
            throw new IllegalArgumentException("请求窗口缺少当前持久用户回合及原话");
        }
        var coverage = summary.coverageState();
        if (coverage != null && coverage.userHistoryId() > userHistoryId) {
            throw new IllegalArgumentException("摘要部分前沿越过当前回合");
        }
        if (coverage != null && coverage.memoryEpoch().equals(source.memoryEpoch())) {
            var turn = source.turn(coverage.userHistoryId());
            if (turn == null || !turn.turnId().equals(coverage.turnId())
                    || coverage.coveredSequence() >= source.nextSequence()
                    || (turn.closed() && coverage.terminalHistoryId() != null
                    && (!Objects.equals(turn.terminalHistoryId(), coverage.terminalHistoryId())
                    || turn.terminalOutcome() != coverage.terminalOutcome()))) {
                throw new IllegalArgumentException("摘要覆盖与原文来源无法对齐");
            }
        } else if (coverage != null && source.appliedSummaryRevision() == 0L) {
            throw new IllegalArgumentException("更换 epoch 的窗口尚未记录实际摘要依赖");
        }
        // 此操作只构造候选，用于校验可回收前缀的来源证明，不执行任何存储写入。
        if (summary.summaryRevision() > 0) L0SourceOperations.prune(source, summary, userHistoryId);
    }
}
