package com.lyw.appgeneration.ai.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * 为同一 memoryId 的 L0 读写提供进程内共享原子边界。
 *
 * <p>底层 Redis 单次写本身是原子的；本包装层再用精确 memoryId 锁串行化
 * MessageWindow 的读改写、删除和比较替换，避免不同记忆实例互相覆盖。</p>
 */
public final class AtomicChatMemoryStore implements ChatMemoryStore {

    private final ChatMemoryStore delegate;
    private final ConcurrentHashMap<Object, LockEntry> locks =
            new ConcurrentHashMap<>();

    public AtomicChatMemoryStore(ChatMemoryStore delegate) {
        this.delegate = Objects.requireNonNull(
                delegate, "底层 ChatMemoryStore 不能为空");
    }

    public boolean supportsSourceSnapshots() {
        return delegate instanceof SourceAwareChatMemoryStore;
    }

    public L0Snapshot getSnapshot(Object memoryId) {
        return withMemoryLock(memoryId, () -> sourceStore().getSnapshot(memoryId));
    }

    /** 来源及正文按同一预期值比较，提交不能拆成两个字段写入。 */
    public DeadlineAwareReplaceResult replaceSnapshotIfMatches(Object memoryId, L0Snapshot expected,
                                                               L0Snapshot replacement, AdmissionDeadline deadline) {
        return withSourceDeadline(memoryId, deadline, () ->
                sourceStore().replaceSnapshotIfMatches(memoryId, expected, replacement, deadline));
    }

    public DeadlineAwareReplaceResult updateSnapshot(Object memoryId, UnaryOperator<L0Snapshot> operation,
                                                     AdmissionDeadline deadline) {
        Objects.requireNonNull(operation, "L0 来源操作不能为空");
        return withSourceDeadline(memoryId, deadline, () -> {
            L0Snapshot current = sourceStore().getSnapshot(memoryId);
            L0Snapshot next = operation.apply(current);
            return sourceStore().replaceSnapshotIfMatches(memoryId, current, next, deadline);
        });
    }

    private SourceAwareChatMemoryStore sourceStore() {
        if (delegate instanceof SourceAwareChatMemoryStore source) return source;
        throw new IllegalStateException("L0 存储不支持来源快照");
    }

    private DeadlineAwareReplaceResult withSourceDeadline(Object memoryId, AdmissionDeadline deadline,
                                                          Supplier<DeadlineAwareReplaceResult> action) {
        Object key = requireMemoryId(memoryId);
        Objects.requireNonNull(deadline, "绝对截止不能为空");
        var duration = sourceStore().worstCaseCommitDuration();
        long wait = deadline.lockWaitNanos(duration);
        if (wait <= 0) return DeadlineAwareReplaceResult.TIMED_OUT;
        LockEntry entry = register(key);
        boolean acquired = false;
        try {
            acquired = entry.lock.tryLock(wait, TimeUnit.NANOSECONDS);
            if (!acquired || !deadline.canStart(duration)) return DeadlineAwareReplaceResult.TIMED_OUT;
            return action.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return DeadlineAwareReplaceResult.INTERRUPTED;
        } finally {
            if (acquired) entry.lock.unlock();
            unregister(key, entry);
        }
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        return withMemoryLock(memoryId,
                () -> snapshot(delegate.getMessages(memoryId)));
    }

    @Override
    public void updateMessages(
            Object memoryId, List<ChatMessage> messages) {
        List<ChatMessage> replacement = snapshot(messages);
        withMemoryLock(memoryId,
                () -> delegate.updateMessages(memoryId, replacement));
    }

    @Override
    public void deleteMessages(Object memoryId) {
        withMemoryLock(memoryId,
                () -> delegate.deleteMessages(memoryId));
    }

    /**
     * 当前快照仍等于 expected 时，用一次底层写替换为 replacement。
     *
     * @return 快照匹配且底层写成功时返回 {@code true}
     */
    public boolean replaceMessagesIfMatches(
            Object memoryId,
            List<ChatMessage> expected,
            List<ChatMessage> replacement) {
        List<ChatMessage> expectedSnapshot = snapshot(expected);
        List<ChatMessage> replacementSnapshot = snapshot(replacement);
        return withMemoryLock(memoryId, () -> {
            if (supportsSourceSnapshots()) {
                L0Snapshot currentSource = sourceStore().getSnapshot(memoryId);
                if (currentSource.hasKnownSource()) {
                    if (!currentSource.originalMessages().equals(expectedSnapshot)) return false;
                    L0Snapshot next;
                    try {
                        next = L0SourceOperations.reconcile(currentSource, replacementSnapshot,
                                CanonicalUserMessageScope.currentBoundary());
                    } catch (IllegalStateException exception) {
                        next = L0SourceOperations.resetActive(currentSource, replacementSnapshot);
                    }
                    return sourceStore().replaceSnapshotIfMatches(memoryId, currentSource, next,
                            AdmissionDeadline.start(java.time.Duration.ofSeconds(60))) == DeadlineAwareReplaceResult.REPLACED;
                }
            }
            List<ChatMessage> current = snapshot(
                    delegate.getMessages(memoryId));
            if (!current.equals(expectedSnapshot)) {
                return false;
            }
            if (replacementSnapshot.isEmpty()) {
                delegate.deleteMessages(memoryId);
            } else {
                delegate.updateMessages(memoryId, replacementSnapshot);
            }
            return true;
        });
    }

    /** 在同一绝对截止内等待本地锁并执行底层单命令 CAS。 */
    public DeadlineAwareReplaceResult replaceMessagesIfMatches(
            Object memoryId,
            List<ChatMessage> expected,
            List<ChatMessage> replacement,
            AdmissionDeadline deadline) {
        Object key = requireMemoryId(memoryId);
        Objects.requireNonNull(deadline, "绝对截止不能为空");
        List<ChatMessage> expectedSnapshot = snapshot(expected);
        List<ChatMessage> replacementSnapshot = snapshot(replacement);
        if (!(delegate instanceof DeadlineAwareChatMemoryStore deadlineStore)) {
            return DeadlineAwareReplaceResult.DEPENDENCY_FAILED;
        }
        java.time.Duration worstCaseCommitDuration;
        try {
            worstCaseCommitDuration =
                    deadlineStore.worstCaseCommitDuration();
        } catch (RuntimeException exception) {
            return DeadlineAwareReplaceResult.DEPENDENCY_FAILED;
        }
        long lockWaitNanos;
        try {
            lockWaitNanos = deadline.lockWaitNanos(
                    worstCaseCommitDuration);
        } catch (RuntimeException exception) {
            return DeadlineAwareReplaceResult.DEPENDENCY_FAILED;
        }
        if (lockWaitNanos <= 0L) {
            return DeadlineAwareReplaceResult.TIMED_OUT;
        }
        LockEntry entry = register(key);
        boolean acquired = false;
        try {
            acquired = entry.lock.tryLock(
                    lockWaitNanos, TimeUnit.NANOSECONDS);
            if (!acquired) {
                return DeadlineAwareReplaceResult.TIMED_OUT;
            }
            if (!deadline.canStart(worstCaseCommitDuration)) {
                return DeadlineAwareReplaceResult.TIMED_OUT;
            }
            if (supportsSourceSnapshots()) {
                L0Snapshot currentSource = sourceStore().getSnapshot(memoryId);
                if (currentSource.hasKnownSource()) {
                    if (!currentSource.originalMessages().equals(expectedSnapshot)) return DeadlineAwareReplaceResult.PREFIX_CHANGED;
                    L0Snapshot next;
                    try {
                        next = L0SourceOperations.reconcile(currentSource, replacementSnapshot,
                                CanonicalUserMessageScope.currentBoundary());
                    } catch (IllegalStateException exception) {
                        next = L0SourceOperations.resetActive(currentSource, replacementSnapshot);
                    }
                    return sourceStore().replaceSnapshotIfMatches(memoryId, currentSource, next, deadline);
                }
            }
            return deadlineStore.replaceMessagesIfMatches(
                    memoryId, expectedSnapshot, replacementSnapshot, deadline);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return DeadlineAwareReplaceResult.INTERRUPTED;
        } catch (RuntimeException exception) {
            return DeadlineAwareReplaceResult.DEPENDENCY_FAILED;
        } finally {
            if (acquired) {
                entry.lock.unlock();
            }
            unregister(key, entry);
        }
    }

    public <T> T withMemoryLock(Object memoryId, Supplier<T> action) {
        Objects.requireNonNull(action, "原子记忆操作不能为空");
        Object key = Objects.requireNonNull(memoryId, "memoryId 不能为空");
        LockEntry entry = register(key);
        entry.lock.lock();
        try {
            return action.get();
        } finally {
            entry.lock.unlock();
            unregister(key, entry);
        }
    }

    /** 带原截止的短来源校验区间；锁等待不得延长一次请求的总截止。 */
    public <T> T withMemoryLock(Object memoryId, AdmissionDeadline deadline, Supplier<T> action) {
        Object key = requireMemoryId(memoryId);
        LockEntry entry = register(key);
        boolean acquired = false;
        try {
            acquired = entry.lock.tryLock(deadline.remainingNanos(), TimeUnit.NANOSECONDS);
            if (!acquired || deadline.remainingNanos() <= 0) throw new java.util.concurrent.CancellationException("L0 来源锁等待截止");
            return action.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("L0 来源锁等待被中断");
        } finally {
            if (acquired) entry.lock.unlock();
            unregister(key, entry);
        }
    }

    public void withMemoryLock(Object memoryId, Runnable action) {
        Objects.requireNonNull(action, "原子记忆操作不能为空");
        withMemoryLock(memoryId, () -> {
            action.run();
            return null;
        });
    }

    int registeredMemoryCount() {
        return locks.size();
    }

    int registeredReferenceCount(Object memoryId) {
        LockEntry entry = locks.get(memoryId);
        return entry == null ? 0 : entry.references;
    }

    private LockEntry register(Object memoryId) {
        return locks.compute(memoryId, (ignored, current) -> {
            LockEntry selected = current == null
                    ? new LockEntry() : current;
            selected.references++;
            return selected;
        });
    }

    private Object requireMemoryId(Object memoryId) {
        Object key = Objects.requireNonNull(memoryId, "memoryId 不能为空");
        if (key.toString().trim().isEmpty()) {
            throw new IllegalArgumentException("memoryId 不能为空字符串");
        }
        return key;
    }

    private void unregister(Object memoryId, LockEntry entry) {
        locks.compute(memoryId, (ignored, current) -> {
            if (current != entry) {
                throw new IllegalStateException("L0 记忆锁注册状态不一致");
            }
            entry.references--;
            if (entry.references < 0) {
                throw new IllegalStateException("L0 记忆锁引用计数不能为负数");
            }
            return entry.references == 0 ? null : entry;
        });
    }

    private static final class LockEntry {

        private final ReentrantLock lock = new ReentrantLock(true);
        private volatile int references;
    }

    private List<ChatMessage> snapshot(List<ChatMessage> messages) {
        return messages == null ? List.of() : List.copyOf(messages);
    }
}
