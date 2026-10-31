package com.lyw.appgeneration.ai.memory;

/** 元数据及 payload 必须同值提交；调用方在共享 memoryId 锁内执行来源操作。 */
public interface SourceAwareChatMemoryStore extends DeadlineAwareChatMemoryStore {
    L0Snapshot getSnapshot(Object memoryId);

    DeadlineAwareReplaceResult replaceSnapshotIfMatches(Object memoryId, L0Snapshot expected,
                                                        L0Snapshot replacement, AdmissionDeadline deadline);
}
