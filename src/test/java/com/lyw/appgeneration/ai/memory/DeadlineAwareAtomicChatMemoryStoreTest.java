package com.lyw.appgeneration.ai.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeadlineAwareAtomicChatMemoryStoreTest {

    private static final long MEMORY_ID = 7L;
    private static final List<ChatMessage> EXPECTED = List.of(
            UserMessage.from("旧问题"), AiMessage.from("旧回答"));
    private static final List<ChatMessage> REPLACEMENT = List.of(
            UserMessage.from("新问题"), AiMessage.from("新回答"));

    @Test
    void 同一记忆锁被占用到预算耗尽时不得访问委托或迟到替换()
            throws Exception {
        CountingDeadlineStore delegate = new CountingDeadlineStore(
                Duration.ZERO);
        AtomicChatMemoryStore store = new AtomicChatMemoryStore(delegate);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> owner = threads.submit(() -> store.withMemoryLock(
                    MEMORY_ID, () -> {
                        locked.countDown();
                        await(release);
                    }));
            assertTrue(locked.await(1L, TimeUnit.SECONDS));
            AdmissionDeadline deadline = AdmissionDeadline.start(
                    Duration.ofNanos(1L));

            DeadlineAwareReplaceResult result =
                    store.replaceMessagesIfMatches(
                            MEMORY_ID, EXPECTED, REPLACEMENT, deadline);

            assertEquals(DeadlineAwareReplaceResult.TIMED_OUT, result);
            assertEquals(0, delegate.totalCalls());
            release.countDown();
            owner.get(1L, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        assertEquals(EXPECTED, delegate.messages());
        assertEquals(0, delegate.totalCalls());
    }

    @Test
    void 剩余预算不足三段最坏提交耗时时不得访问委托() {
        CountingDeadlineStore delegate = new CountingDeadlineStore(
                Duration.ofSeconds(9L));
        AtomicChatMemoryStore store = new AtomicChatMemoryStore(delegate);

        DeadlineAwareReplaceResult result = store.replaceMessagesIfMatches(
                MEMORY_ID, EXPECTED, REPLACEMENT,
                AdmissionDeadline.start(Duration.ofSeconds(8L)));

        assertEquals(DeadlineAwareReplaceResult.TIMED_OUT, result);
        assertEquals(0, delegate.totalCalls());
        assertEquals(EXPECTED, delegate.messages());
    }

    @Test
    void 新旧参数相等时仍必须校验真实快照没有晚到消息() {
        CountingDeadlineStore delegate = new CountingDeadlineStore(
                Duration.ZERO);
        delegate.updateMessages(MEMORY_ID, REPLACEMENT);
        AtomicChatMemoryStore store = new AtomicChatMemoryStore(delegate);

        DeadlineAwareReplaceResult result = store.replaceMessagesIfMatches(
                MEMORY_ID, EXPECTED, EXPECTED,
                AdmissionDeadline.start(Duration.ofSeconds(1L)));

        assertEquals(DeadlineAwareReplaceResult.PREFIX_CHANGED, result);
        assertEquals(2, delegate.totalCalls());
        assertEquals(REPLACEMENT, delegate.messages());
    }

    @Test
    void 最坏提交预算探测异常映射为依赖失败且不注册锁() {
        AtomicChatMemoryStore store = new AtomicChatMemoryStore(
                new ThrowingDeadlineStore(true));

        DeadlineAwareReplaceResult result = store.replaceMessagesIfMatches(
                MEMORY_ID, EXPECTED, REPLACEMENT,
                AdmissionDeadline.start(Duration.ofSeconds(1L)));

        assertEquals(DeadlineAwareReplaceResult.DEPENDENCY_FAILED, result);
        assertEquals(0, store.registeredMemoryCount());
    }

    @Test
    void 持锁后CAS依赖异常映射为依赖失败且释放锁引用() {
        AtomicChatMemoryStore store = new AtomicChatMemoryStore(
                new ThrowingDeadlineStore(false));

        DeadlineAwareReplaceResult result = store.replaceMessagesIfMatches(
                MEMORY_ID, EXPECTED, REPLACEMENT,
                AdmissionDeadline.start(Duration.ofSeconds(1L)));

        assertEquals(DeadlineAwareReplaceResult.DEPENDENCY_FAILED, result);
        assertEquals(0, store.registeredMemoryCount());
        assertEquals("已释放", store.withMemoryLock(MEMORY_ID, () -> "已释放"));
        assertEquals(0, store.registeredMemoryCount());
    }

    @Test
    void 本地参数校验异常不得映射为依赖失败() {
        AtomicChatMemoryStore store = new AtomicChatMemoryStore(
                new CountingDeadlineStore(Duration.ZERO));

        assertThrows(NullPointerException.class,
                () -> store.replaceMessagesIfMatches(
                        MEMORY_ID, EXPECTED, REPLACEMENT, null));
        assertThrows(NullPointerException.class,
                () -> store.replaceMessagesIfMatches(
                        null, EXPECTED, REPLACEMENT,
                        AdmissionDeadline.start(Duration.ofSeconds(1L))));
        assertThrows(NullPointerException.class,
                () -> store.replaceMessagesIfMatches(
                        null, EXPECTED, EXPECTED,
                        AdmissionDeadline.start(Duration.ofSeconds(1L))));
        assertThrows(NullPointerException.class,
                () -> store.replaceMessagesIfMatches(
                        MEMORY_ID, EXPECTED, EXPECTED, null));
        assertThrows(IllegalArgumentException.class,
                () -> store.replaceMessagesIfMatches(
                        "  ", EXPECTED, REPLACEMENT,
                        AdmissionDeadline.start(Duration.ofSeconds(1L))));
        assertThrows(IllegalArgumentException.class,
                () -> store.replaceMessagesIfMatches(
                        "  ", EXPECTED, EXPECTED,
                        AdmissionDeadline.start(Duration.ofSeconds(1L))));
    }

    @Test
    void 单调时钟跨过long回绕点仍按经过时间计算剩余预算() {
        java.util.concurrent.atomic.AtomicLong nanoTime =
                new java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE - 4L);
        AdmissionDeadline deadline = AdmissionDeadline.start(
                Duration.ofNanos(10L), nanoTime::get, () -> 1_000L,
                ignored -> { });

        nanoTime.set(Long.MIN_VALUE + 1L);

        assertEquals(4L, deadline.remainingNanos());
        assertFalse(deadline.canStart(Duration.ofNanos(4L)));
        assertTrue(deadline.canStart(Duration.ofNanos(3L)));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(1L, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待测试闩锁被中断", exception);
        }
    }

    private static final class CountingDeadlineStore
            implements ChatMemoryStore, DeadlineAwareChatMemoryStore {

        private final AtomicInteger getCalls = new AtomicInteger();
        private final AtomicInteger updateCalls = new AtomicInteger();
        private final AtomicInteger deleteCalls = new AtomicInteger();
        private final AtomicInteger casCalls = new AtomicInteger();
        private final Duration commitBudget;
        private List<ChatMessage> messages = EXPECTED;

        private CountingDeadlineStore(Duration commitBudget) {
            this.commitBudget = commitBudget;
        }

        @Override
        public synchronized List<ChatMessage> getMessages(Object memoryId) {
            getCalls.incrementAndGet();
            return messages;
        }

        @Override
        public synchronized void updateMessages(
                Object memoryId, List<ChatMessage> updated) {
            updateCalls.incrementAndGet();
            messages = List.copyOf(updated);
        }

        @Override
        public synchronized void deleteMessages(Object memoryId) {
            deleteCalls.incrementAndGet();
            messages = List.of();
        }

        @Override
        public Duration worstCaseCommitDuration() {
            return commitBudget;
        }

        @Override
        public synchronized DeadlineAwareReplaceResult
                replaceMessagesIfMatches(
                        Object memoryId,
                        List<ChatMessage> expected,
                        List<ChatMessage> replacement,
                        AdmissionDeadline deadline) {
            casCalls.incrementAndGet();
            if (!messages.equals(expected)) {
                return DeadlineAwareReplaceResult.PREFIX_CHANGED;
            }
            messages = List.copyOf(replacement);
            return DeadlineAwareReplaceResult.REPLACED;
        }

        private synchronized List<ChatMessage> messages() {
            return messages;
        }

        private int totalCalls() {
            return getCalls.get() + updateCalls.get()
                    + deleteCalls.get() + casCalls.get();
        }
    }

    private static final class ThrowingDeadlineStore
            implements ChatMemoryStore, DeadlineAwareChatMemoryStore {

        private final boolean failDuringBudgetRead;

        private ThrowingDeadlineStore(boolean failDuringBudgetRead) {
            this.failDuringBudgetRead = failDuringBudgetRead;
        }

        @Override
        public List<ChatMessage> getMessages(Object memoryId) {
            return EXPECTED;
        }

        @Override
        public void updateMessages(
                Object memoryId, List<ChatMessage> messages) {
        }

        @Override
        public void deleteMessages(Object memoryId) {
        }

        @Override
        public Duration worstCaseCommitDuration() {
            if (failDuringBudgetRead) {
                throw new IllegalStateException("无法读取提交预算");
            }
            return Duration.ZERO;
        }

        @Override
        public DeadlineAwareReplaceResult replaceMessagesIfMatches(
                Object memoryId,
                List<ChatMessage> expected,
                List<ChatMessage> replacement,
                AdmissionDeadline deadline) {
            throw new IllegalStateException("底层 CAS 失败");
        }
    }
}
