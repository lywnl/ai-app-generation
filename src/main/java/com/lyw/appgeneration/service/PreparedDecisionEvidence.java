package com.lyw.appgeneration.service;

import com.lyw.appgeneration.ai.memory.DecisionEvidence;
import com.lyw.appgeneration.ai.memory.L0Snapshot;
import com.lyw.appgeneration.ai.memory.L0SourceOperations;

/** 已可靠留存的采集边界，仅作为后续摘要提交的依据，本身不授予原文回收权限。 */
public record PreparedDecisionEvidence(long appId, long userId, long userHistoryId, String turnId,
                                        L0Snapshot source, long throughSequence, DecisionEvidence evidence,
                                        String sourceFingerprint) {
    public PreparedDecisionEvidence {
        if (appId <= 0 || userId <= 0 || userHistoryId <= 0 || source == null || !source.hasKnownSource()
                || !source.memoryId().equals(Long.toString(appId)) || evidence == null || evidence.revision() <= 0
                || evidence.userHistoryId() != userHistoryId || !evidence.turnId().equals(turnId)
                || !source.memoryEpoch().equals(evidence.collectedEpoch())
                || evidence.collectedThroughSequence() < throughSequence
                || !L0SourceOperations.fingerprint(source, userHistoryId, throughSequence, evidence.revision()).equals(sourceFingerprint)) {
            throw new IllegalArgumentException("持久决策证据未覆盖所声明的来源边界");
        }
    }

    public boolean matches(L0Snapshot current, DecisionEvidence currentEvidence) {
        return currentEvidence != null && currentEvidence.equals(evidence)
                && L0SourceOperations.containsPrefix(current, source, throughSequence);
    }
}
