package com.lyw.appgeneration.ai.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicChatMemoryStoreConcurrencyTest {

    @Test
    void 哈希碰撞的不同记忆不得互相阻塞Redis操作() throws Exception {
        AtomicChatMemoryStore store = new AtomicChatMemoryStore(
                new EmptyChatMemoryStore());
        CountDownLatch secondAcquired = new CountDownLatch(1);

        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            AtomicReference<Future<?>> second = new AtomicReference<>();
            store.withMemoryLock(1L, () -> {
                second.set(threads.submit(() ->
                        store.withMemoryLock(65L,
                                secondAcquired::countDown)));
                assertTrue(awaitWithin(
                                secondAcquired, 200L, TimeUnit.MILLISECONDS),
                        "不同 memoryId 不得因 64 条带哈希碰撞而串行");
                return null;
            });
            second.get().get(1L, TimeUnit.SECONDS);
        }
        assertEquals(0, store.registeredMemoryCount());
    }

    @Test
    void 同一记忆仍必须串行且全部释放后回收锁对象() throws Exception {
        AtomicChatMemoryStore store = new AtomicChatMemoryStore(
                new EmptyChatMemoryStore());
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);

        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> first = threads.submit(() ->
                    store.withMemoryLock(7L, () -> {
                        firstEntered.countDown();
                        await(releaseFirst);
                    }));
            assertTrue(firstEntered.await(1L, TimeUnit.SECONDS));
            Future<?> second = threads.submit(() ->
                    store.withMemoryLock(7L, secondEntered::countDown));

            assertFalse(secondEntered.await(100L, TimeUnit.MILLISECONDS));
            releaseFirst.countDown();
            first.get(1L, TimeUnit.SECONDS);
            second.get(1L, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
        }
        assertEquals(0, store.registeredMemoryCount());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1L, TimeUnit.SECONDS)) {
                throw new AssertionError("等待测试闩锁超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待测试闩锁被中断", exception);
        }
    }

    private static boolean awaitWithin(
            CountDownLatch latch, long timeout, TimeUnit unit) {
        try {
            return latch.await(timeout, unit);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待测试闩锁被中断", exception);
        }
    }

    private static final class EmptyChatMemoryStore
            implements ChatMemoryStore {

        @Override
        public List<ChatMessage> getMessages(Object memoryId) {
            return List.of();
        }

        @Override
        public void updateMessages(
                Object memoryId, List<ChatMessage> messages) {
        }

        @Override
        public void deleteMessages(Object memoryId) {
        }
    }
}
