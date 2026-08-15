package com.lyw.appgeneration.ai.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 为 L0 的读取、追加和已完成前缀替换提供同一把锁。
 *
 * <p>该装饰器不会在 {@link #messages()} 时自动裁剪。只有 L1 摘要成功落库后，
 * 协调器才调用 {@link #replaceCompletedPrefix(List)}，保证先摘要、后裁剪。</p>
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
     * 用新的稳定完整回合替换旧前缀，同时保留系统前缀和当前未完成回合。
     */
    public void replaceCompletedPrefix(
            List<ChatMessage> retainedCompletedMessages) {
        List<ChatMessage> retained = List.copyOf(Objects.requireNonNull(
                retainedCompletedMessages, "保留消息不能为空"));
        memoryLock.lock();
        try {
            List<ChatMessage> current = List.copyOf(delegate.messages());
            int firstUser = firstUserIndex(current);
            if (firstUser < 0) {
                return;
            }
            int unfinishedTail = unfinishedTailStart(current);
            List<ChatMessage> replacement = new ArrayList<>(
                    firstUser + retained.size()
                            + Math.max(0, current.size() - unfinishedTail));
            replacement.addAll(current.subList(0, firstUser));
            replacement.addAll(retained);
            if (unfinishedTail < current.size()) {
                replacement.addAll(current.subList(
                        unfinishedTail, current.size()));
            }
            delegate.clear();
            delegate.add(replacement);
        } finally {
            memoryLock.unlock();
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

    private int unfinishedTailStart(List<ChatMessage> messages) {
        int lastUser = -1;
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof UserMessage) {
                lastUser = index;
                break;
            }
        }
        if (lastUser < 0 || hasTerminalAi(messages, lastUser)) {
            return messages.size();
        }
        return lastUser;
    }

    private boolean hasTerminalAi(
            List<ChatMessage> messages, int lastUser) {
        if (lastUser + 1 >= messages.size()) {
            return false;
        }
        ChatMessage last = messages.getLast();
        return last instanceof AiMessage aiMessage
                && !aiMessage.hasToolExecutionRequests();
    }
}
