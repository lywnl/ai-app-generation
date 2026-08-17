package com.lyw.appgeneration.ai.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

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
