package com.lyw.appgeneration.ai.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** 本地测试替身，不构成 Redis 或跨存储真实证据。 */
public final class SourceMemoryTestStore implements ChatMemoryStore, SourceAwareChatMemoryStore {
    public final AtomicReference<L0Snapshot> value;
    public volatile boolean rejectWrite;

    public SourceMemoryTestStore(L0Snapshot initial) {
        value = new AtomicReference<>(initial);
    }

    @Override public L0Snapshot getSnapshot(Object id) { return value.get(); }
    @Override public List<ChatMessage> getMessages(Object id) { return value.get().messages(); }
    @Override public Duration worstCaseCommitDuration() { return Duration.ZERO; }
    @Override public void deleteMessages(Object id) { value.set(L0Snapshot.legacy(id.toString(), List.of())); }
    @Override public void updateMessages(Object id, List<ChatMessage> messages) {
        value.updateAndGet(old -> L0SourceOperations.reconcile(old, messages, CanonicalUserMessageScope.currentBoundary()));
    }
    @Override public DeadlineAwareReplaceResult replaceMessagesIfMatches(Object id, List<ChatMessage> expected,
                                                                         List<ChatMessage> replacement, AdmissionDeadline deadline) {
        throw new IllegalStateException("来源测试必须使用快照接口");
    }
    @Override public DeadlineAwareReplaceResult replaceSnapshotIfMatches(Object id, L0Snapshot expected,
                                                                          L0Snapshot replacement, AdmissionDeadline deadline) {
        L0SourceOperations.validateTransition(expected, replacement);
        if (deadline.remainingNanos() <= 0L) return DeadlineAwareReplaceResult.TIMED_OUT;
        if (rejectWrite) return DeadlineAwareReplaceResult.DEPENDENCY_FAILED;
        synchronized (value) {
            if (!value.get().equals(expected)) return DeadlineAwareReplaceResult.PREFIX_CHANGED;
            value.set(replacement);
            return DeadlineAwareReplaceResult.REPLACED;
        }
    }
}
