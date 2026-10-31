package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;

/**
 * 完整历史游标之后的唯一部分前沿。序号仅能在同一 memoryEpoch 内比较。
 * terminalHistoryId 表示后端已登记结束；terminalIncluded 才表示终态已经进入语义摘要。
 * evidenceRevision 表示已经纳入摘要的证据修订，不能因终态登记而提前推进。
 */
public record SummaryCoverage(long userHistoryId,
                              String turnId,
                              String memoryEpoch,
                              long coveredSequence,
                              String sourceFingerprint,
                              long evidenceRevision,
                              Long terminalHistoryId,
                              ChatMemoryOutcome terminalOutcome,
                              boolean terminalIncluded) {

    public SummaryCoverage {
        if (userHistoryId <= 0 || coveredSequence < 0 || evidenceRevision < 0) {
            throw new IllegalArgumentException("部分覆盖身份、序号或证据修订无效");
        }
        requireIdentity(turnId, "运行回合");
        requireIdentity(memoryEpoch, "来源窗口");
        if (sourceFingerprint == null || !sourceFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("部分覆盖缺少有效来源指纹");
        }
        if ((terminalHistoryId == null) != (terminalOutcome == null)
                || (terminalHistoryId != null && terminalHistoryId <= userHistoryId)
                || (terminalIncluded && terminalHistoryId == null)) {
            throw new IllegalArgumentException("部分覆盖的终态身份不完整");
        }
    }

    private static void requireIdentity(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(name + "身份无效");
        }
    }

    /** 只登记可靠终态，不授予尚未摘要材料的回收权限。 */
    public SummaryCoverage withTerminal(long historyId, ChatMemoryOutcome outcome) {
        if (terminalHistoryId != null
                && (terminalHistoryId != historyId || terminalOutcome != outcome)) {
            throw new IllegalArgumentException("不能替换已经登记的真实终态");
        }
        return new SummaryCoverage(userHistoryId, turnId, memoryEpoch, coveredSequence,
                sourceFingerprint, evidenceRevision, historyId, outcome, terminalIncluded);
    }
}
