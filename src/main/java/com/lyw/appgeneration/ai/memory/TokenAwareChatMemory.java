package com.lyw.appgeneration.ai.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 为 L0 的读取、追加和已完成前缀删除提供同一把锁。
 *
 * <p>该装饰器不会在 {@link #messages()} 时自动裁剪。只有 L1 摘要成功落库后，
 * 协调器才调用 {@link #removeCompletedPrefixIfMatches(List)}，保证先摘要、后裁剪。</p>
 */
public class TokenAwareChatMemory implements ChatMemory {

    private final ChatMemory delegate;
    private final ReentrantLock memoryLock = new ReentrantLock(true);

    public TokenAwareChatMemory(ChatMemory delegate) {
        this.delegate = Objects.requireNonNull(
                delegate, "L0 ChatMemory 不能为空");
    }

    @Override
    public Object id() {
        return delegate.id();
    }

    @Override
    public void add(ChatMessage message) {
        memoryLock.lock();
        try {
            delegate.add(message);
        } finally {
            memoryLock.unlock();
        }
    }

    @Override
    public List<ChatMessage> messages() {
        memoryLock.lock();
        try {
            return List.copyOf(delegate.messages());
        } finally {
            memoryLock.unlock();
        }
    }

    @Override
    public void clear() {
        memoryLock.lock();
        try {
            delegate.clear();
        } finally {
            memoryLock.unlock();
        }
    }

    /**
     * 仅当任务启动时确认的旧完整前缀仍原样存在时删除它。
     *
     * <p>删除在追加消息共用的锁内完成，晚于快照追加的完整回合、工具消息和
     * 当前未完成回合都不会被旧快照覆盖。</p>
     *
     * @return 前缀匹配并完成删除时返回 {@code true}
     */
    public boolean removeCompletedPrefixIfMatches(
            List<ChatMessage> expectedPrefix) {
        List<ChatMessage> expected = List.copyOf(Objects.requireNonNull(
                expectedPrefix, "待删除前缀不能为空"));
        if (expected.isEmpty()) {
            return true;
        }
        if (!isCompleteTurnSequence(expected)) {
            return false;
        }
        memoryLock.lock();
        try {
            List<ChatMessage> current = List.copyOf(delegate.messages());
            int firstUser = firstUserIndex(current);
            int expectedEnd = firstUser + expected.size();
            if (firstUser < 0 || expectedEnd > current.size()
                    || !current.subList(firstUser, expectedEnd)
                    .equals(expected)) {
                return false;
            }
            List<ChatMessage> replacement = new ArrayList<>(
                    current.size() - expected.size());
            replacement.addAll(current.subList(0, firstUser));
            replacement.addAll(current.subList(expectedEnd, current.size()));
            delegate.clear();
            delegate.add(replacement);
            return true;
        } finally {
            memoryLock.unlock();
        }
    }

    private boolean isCompleteTurnSequence(List<ChatMessage> messages) {
        try {
            ConversationTurnSnapshotParser.Snapshot snapshot =
                    new ConversationTurnSnapshotParser().parse(messages);
            return snapshot.leadingMessages().isEmpty()
                    && snapshot.unfinishedTail().isEmpty()
                    && !snapshot.completedTurns().isEmpty();
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private int firstUserIndex(List<ChatMessage> messages) {
        for (int index = 0; index < messages.size(); index++) {
            if (messages.get(index) instanceof UserMessage) {
                return index;
            }
        }
        return -1;
    }

}
