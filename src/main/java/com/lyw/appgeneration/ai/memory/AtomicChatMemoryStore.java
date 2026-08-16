package com.lyw.appgeneration.ai.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 为同一 memoryId 的 L0 读写提供进程内共享原子边界。
 *
 * <p>底层 Redis 单次写本身是原子的；本包装层再用条带锁串行化
 * MessageWindow 的读改写、删除和比较替换，避免不同记忆实例互相覆盖。</p>
 */
public final class AtomicChatMemoryStore implements ChatMemoryStore {

    private static final int LOCK_STRIPE_COUNT = 64;

    private final ChatMemoryStore delegate;
    private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPE_COUNT];

    public AtomicChatMemoryStore(ChatMemoryStore delegate) {
        this.delegate = Objects.requireNonNull(
                delegate, "底层 ChatMemoryStore 不能为空");
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock(true);
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

    public <T> T withMemoryLock(Object memoryId, Supplier<T> action) {
        Objects.requireNonNull(action, "原子记忆操作不能为空");
        ReentrantLock lock = lockFor(memoryId);
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    public void withMemoryLock(Object memoryId, Runnable action) {
        Objects.requireNonNull(action, "原子记忆操作不能为空");
        withMemoryLock(memoryId, () -> {
            action.run();
            return null;
        });
    }

    private ReentrantLock lockFor(Object memoryId) {
        int hash = Objects.requireNonNull(
                memoryId, "memoryId 不能为空").hashCode();
        hash ^= hash >>> 16;
        return locks[hash & (LOCK_STRIPE_COUNT - 1)];
    }

    private List<ChatMessage> snapshot(List<ChatMessage> messages) {
        return messages == null ? List.of() : List.copyOf(messages);
    }
}
