package com.lyw.appgeneration.ai.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.TimeUnit;

/**
 * 为 L0 的读取、追加和已完成前缀删除提供同一把锁。
 *
 * <p>该装饰器不会在 {@link #messages()} 时自动裁剪。只有 L1 摘要成功落库后，
 * 协调器才调用 {@link #removeCompletedPrefixIfMatches(List)}，保证先摘要、后裁剪。</p>
 */
public class TokenAwareChatMemory implements ChatMemory {

    private static final String CANONICAL_USER_NAME_PREFIX =
            "canonical-user-sha256:";

    private final ChatMemory delegate;
    private final AtomicChatMemoryStore atomicStore;
    private final Object memoryId;
    private final ReentrantLock memoryLock = new ReentrantLock(true);

    /** 仅供同包内无持久化 store 的既有单元测试使用。 */
    TokenAwareChatMemory(ChatMemory delegate) {
        this(delegate, null);
    }

    public TokenAwareChatMemory(
            ChatMemory delegate, AtomicChatMemoryStore atomicStore) {
        this.delegate = Objects.requireNonNull(
                delegate, "L0 ChatMemory 不能为空");
        this.atomicStore = atomicStore;
        this.memoryId = Objects.requireNonNull(
                delegate.id(), "L0 memoryId 不能为空");
    }

    @Override
    public Object id() {
        return delegate.id();
    }

    @Override
    public void add(ChatMessage message) {
        ChatMessage requiredMessage = canonicalizeUserMessageIfScoped(
                Objects.requireNonNull(
                        message, "待追加消息不能为空"));
        if (hasSourceSnapshot()) {
            var committed = new java.util.concurrent.atomic.AtomicReference<L0Snapshot>();
            requireReplaced(atomicStore.updateSnapshot(memoryId,
                    snapshot -> {
                        var next = L0SourceOperations.append(snapshot, requiredMessage, CanonicalUserMessageScope.currentBoundary());
                        committed.set(next);
                        return next;
                    },
                    AdmissionDeadline.start(java.time.Duration.ofSeconds(60))));
            if (!(requiredMessage instanceof SystemMessage)) {
                SourceCommitScope.committed(committed.get().memoryEpoch(), committed.get().entries().getLast());
            }
            return;
        }
        if (requiredMessage instanceof SystemMessage systemMessage) {
            addSystemMessage(systemMessage);
            return;
        }
        withMemoryLock(() -> delegate.add(requiredMessage));
    }

    static String canonicalUserName(String canonicalText) {
        Objects.requireNonNull(canonicalText, "用户原文不能为空");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalText.getBytes(StandardCharsets.UTF_8));
            return CANONICAL_USER_NAME_PREFIX
                    + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", exception);
        }
    }

    static boolean matchesCanonicalUserText(
            UserMessage message, String persistedText) {
        String name = message.name();
        if (name == null) {
            return message.hasSingleText()
                    && Objects.toString(message.singleText(), "")
                    .equals(Objects.toString(persistedText, ""));
        }
        return name.equals(canonicalUserName(
                Objects.toString(persistedText, "")));
    }

    private ChatMessage canonicalizeUserMessageIfScoped(
            ChatMessage message) {
        if (!(message instanceof UserMessage userMessage)
                || userMessage.name() != null) {
            return message;
        }
        String canonicalText =
                CanonicalUserMessageScope.currentCanonicalText();
        if (canonicalText == null) {
            return message;
        }
        return UserMessage.from(
                canonicalUserName(canonicalText), userMessage.contents());
    }

    @Override
    public List<ChatMessage> messages() {
        return withMemoryLock(() -> List.copyOf(delegate.messages()));
    }

    @Override
    public void clear() {
        if (hasSourceSnapshot()) {
            throw new IllegalStateException("已对齐 L0 不能通过 clear 丢失摘要依赖；请使用显式恢复或应用删除入口");
        }
        withMemoryLock(delegate::clear);
    }

    public boolean supportsSourceSnapshots() {
        return atomicStore != null && atomicStore.supportsSourceSnapshots();
    }

    private boolean hasSourceSnapshot() {
        return supportsSourceSnapshots() && atomicStore.getSnapshot(memoryId).hasKnownSource();
    }

    public L0Snapshot sourceSnapshot() {
        return supportsSourceSnapshots() ? atomicStore.getSnapshot(memoryId)
                : L0Snapshot.legacy(memoryId.toString(), messages());
    }

    public DeadlineAwareReplaceResult replaceSourceSnapshot(L0Snapshot expected, L0Snapshot replacement,
                                                           AdmissionDeadline deadline) {
        if (!supportsSourceSnapshots()) return DeadlineAwareReplaceResult.DEPENDENCY_FAILED;
        return atomicStore.replaceSnapshotIfMatches(memoryId, expected, replacement, deadline);
    }

    /** 来源复核与短数据库提交共享此边界；不得在动作中调用模型或持锁等待模型。 */
    public <T> T withSourceLock(java.util.function.Function<L0Snapshot, T> action) {
        if (!supportsSourceSnapshots()) throw new IllegalStateException("缺少来源存储");
        return atomicStore.withMemoryLock(memoryId, () -> action.apply(atomicStore.getSnapshot(memoryId)));
    }

    public <T> T withSourceLock(AdmissionDeadline deadline, java.util.function.Function<L0Snapshot, T> action) {
        if (!supportsSourceSnapshots()) throw new IllegalStateException("缺少来源存储");
        return atomicStore.withMemoryLock(memoryId, deadline, () -> action.apply(atomicStore.getSnapshot(memoryId)));
    }

    public void closeSourceTurn(long userHistoryId, String turnId, long historyId,
                                com.lyw.appgeneration.model.enums.ChatMemoryOutcome outcome) {
        requireReplaced(atomicStore.updateSnapshot(memoryId,
                source -> L0SourceOperations.closeTurn(source, userHistoryId, turnId, historyId, outcome),
                AdmissionDeadline.start(java.time.Duration.ofSeconds(60))));
    }

    private void requireReplaced(DeadlineAwareReplaceResult result) {
        if (result != DeadlineAwareReplaceResult.REPLACED) {
            throw new IllegalStateException("L0 来源操作未提交: " + result);
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
        return withMemoryLock(() -> {
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
            return replaceSnapshot(current, replacement);
        });
    }

    /**
     * 仅当旧快照未变化时一次性替换完整窗口。
     *
     * @return 快照匹配且底层写成功时返回 {@code true}
     */
    public boolean replaceSnapshotIfMatches(
            List<ChatMessage> expected,
            List<ChatMessage> replacement) {
        List<ChatMessage> expectedSnapshot = List.copyOf(
                Objects.requireNonNull(expected, "旧快照不能为空"));
        List<ChatMessage> replacementSnapshot = List.copyOf(
                Objects.requireNonNull(replacement, "新快照不能为空"));
        return withMemoryLock(() -> replaceSnapshot(
                expectedSnapshot, replacementSnapshot));
    }

    /**
     * 最终门禁提交专用路径；不得先进入普通无界 {@link #withMemoryLock}。
     */
    public DeadlineAwareReplaceResult replaceSnapshotIfMatches(
            List<ChatMessage> expected,
            List<ChatMessage> replacement,
            AdmissionDeadline deadline) {
        List<ChatMessage> expectedSnapshot = List.copyOf(
                Objects.requireNonNull(expected, "旧快照不能为空"));
        List<ChatMessage> replacementSnapshot = List.copyOf(
                Objects.requireNonNull(replacement, "新快照不能为空"));
        Objects.requireNonNull(deadline, "绝对截止不能为空");
        if (atomicStore != null) {
            return atomicStore.replaceMessagesIfMatches(
                    memoryId, expectedSnapshot, replacementSnapshot, deadline);
        }
        boolean acquired = false;
        try {
            long remaining = deadline.remainingNanos();
            if (remaining <= 0L) {
                return DeadlineAwareReplaceResult.TIMED_OUT;
            }
            acquired = memoryLock.tryLock(remaining, TimeUnit.NANOSECONDS);
            if (!acquired || deadline.remainingNanos() <= 0L) {
                return DeadlineAwareReplaceResult.TIMED_OUT;
            }
            return replaceSnapshot(expectedSnapshot, replacementSnapshot)
                    ? DeadlineAwareReplaceResult.REPLACED
                    : DeadlineAwareReplaceResult.PREFIX_CHANGED;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return DeadlineAwareReplaceResult.INTERRUPTED;
        } catch (RuntimeException exception) {
            return DeadlineAwareReplaceResult.DEPENDENCY_FAILED;
        } finally {
            if (acquired) {
                memoryLock.unlock();
            }
        }
    }

    private void addSystemMessage(SystemMessage systemMessage) {
        withMemoryLock(() -> {
            List<ChatMessage> current = List.copyOf(delegate.messages());
            List<ChatMessage> replacement = new ArrayList<>(
                    current.size() + 1);
            replacement.add(systemMessage);
            for (ChatMessage existingMessage : current) {
                if (!(existingMessage instanceof SystemMessage)) {
                    replacement.add(existingMessage);
                }
            }
            if (current.equals(replacement)) {
                return;
            }
            boolean replaced;
            try {
                replaced = replaceSystemSnapshot(current, replacement);
            } catch (RuntimeException exception) {
                throw new IllegalStateException(
                        "原子前置 L0 系统消息失败，memoryId=" + memoryId,
                        exception);
            }
            if (!replaced) {
                throw new IllegalStateException(
                        "L0 系统消息快照在替换前已变化，memoryId=" + memoryId);
            }
        });
    }

    private boolean replaceSystemSnapshot(
            List<ChatMessage> expected,
            List<ChatMessage> replacement) {
        if (atomicStore == null) {
            return replaceSnapshot(expected, replacement);
        }
        return atomicStore.replaceMessagesIfMatches(
                memoryId, expected, replacement);
    }

    private boolean replaceSnapshot(
            List<ChatMessage> expected,
            List<ChatMessage> replacement) {
        if (atomicStore == null) {
            if (!List.copyOf(delegate.messages()).equals(expected)) {
                return false;
            }
            delegate.clear();
            delegate.add(replacement);
            return true;
        }
        try {
            return atomicStore.replaceMessagesIfMatches(
                    memoryId, expected, replacement);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private <T> T withMemoryLock(java.util.function.Supplier<T> action) {
        if (atomicStore != null) {
            return atomicStore.withMemoryLock(memoryId, action);
        }
        memoryLock.lock();
        try {
            return action.get();
        } finally {
            memoryLock.unlock();
        }
    }

    private void withMemoryLock(Runnable action) {
        withMemoryLock(() -> {
            action.run();
            return null;
        });
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
