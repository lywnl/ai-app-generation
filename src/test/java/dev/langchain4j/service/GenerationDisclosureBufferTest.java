package dev.langchain4j.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationDisclosureBufferTest {

    @Test
    void 暂停期间只允许入队恢复后才按原序披露() {
        GenerationDisclosureBuffer buffer =
                new GenerationDisclosureBuffer();
        List<String> published = new ArrayList<>();

        buffer.pausePublishing();
        buffer.enqueueResolved(() -> published.add("正文"));
        buffer.enqueueResolved(() -> published.add("工具"));

        assertTrue(published.isEmpty());
        buffer.resumePublishing();
        assertEquals(List.of("正文", "工具"), published);
    }

    @Test
    void 多批次暂停必须等待引用计数归零后才披露() {
        GenerationDisclosureBuffer buffer =
                new GenerationDisclosureBuffer();
        List<String> published = new ArrayList<>();

        buffer.pausePublishing();
        buffer.pausePublishing();
        buffer.enqueueResolved(() -> published.add("第一项"));
        buffer.enqueueResolved(() -> published.add("第二项"));

        buffer.resumePublishing();
        assertTrue(published.isEmpty(),
                "仍有一个批次暂停时不得提前调用外部 listener");

        buffer.resumePublishing();
        assertEquals(List.of("第一项", "第二项"), published);
    }

    @Test
    void 恢复下溢失败后仍应允许后续合法暂停和披露() {
        GenerationDisclosureBuffer buffer =
                new GenerationDisclosureBuffer();
        List<String> published = new ArrayList<>();

        assertThrows(IllegalStateException.class,
                buffer::resumePublishing);

        buffer.pausePublishing();
        buffer.enqueueResolved(() -> published.add("安全项"));
        assertTrue(published.isEmpty());

        buffer.resumePublishing();
        assertEquals(List.of("安全项"), published);
    }

    @Test
    void 队首未决时后续披露不得越过且解决后按原序释放() {
        GenerationDisclosureBuffer buffer = new GenerationDisclosureBuffer();
        List<String> published = new ArrayList<>();

        GenerationDisclosureBuffer.Disclosure pending =
                buffer.enqueuePending(() -> published.add("第一项"));
        buffer.enqueueResolved(() -> published.add("第二项"));

        assertTrue(published.isEmpty());

        buffer.resolveDelayed(pending);

        assertEquals(List.of("第一项", "第二项"), published);
    }

    @Test
    void 删除违规披露后必须释放后续安全披露() {
        GenerationDisclosureBuffer buffer = new GenerationDisclosureBuffer();
        List<String> published = new ArrayList<>();

        GenerationDisclosureBuffer.Disclosure violation =
                buffer.enqueuePending(() -> published.add("不得发布"));
        buffer.enqueueResolved(() -> published.add("安全项"));

        buffer.remove(violation);

        assertEquals(List.of("安全项"), published);
    }

    @Test
    void 并发入队只能存在一个发布者() throws Exception {
        GenerationDisclosureBuffer buffer = new GenerationDisclosureBuffer();
        List<String> published = Collections.synchronizedList(
                new ArrayList<>());
        AtomicInteger activePublishers = new AtomicInteger();
        AtomicInteger maximumPublishers = new AtomicInteger();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();

        Thread first = Thread.startVirtualThread(() -> {
            try {
                buffer.enqueueResolved(() -> {
                    int active = activePublishers.incrementAndGet();
                    maximumPublishers.accumulateAndGet(active, Math::max);
                    published.add("第一项");
                    firstEntered.countDown();
                    await(releaseFirst);
                    activePublishers.decrementAndGet();
                });
            } catch (Throwable failure) {
                threadFailure.compareAndSet(null, failure);
            }
        });
        assertTrue(firstEntered.await(1, TimeUnit.SECONDS));

        Thread second = Thread.startVirtualThread(() -> {
            try {
                buffer.enqueueResolved(() -> {
                    int active = activePublishers.incrementAndGet();
                    maximumPublishers.accumulateAndGet(active, Math::max);
                    published.add("第二项");
                    activePublishers.decrementAndGet();
                });
            } catch (Throwable failure) {
                threadFailure.compareAndSet(null, failure);
            }
        });
        second.join(1_000);
        assertTrue(!second.isAlive(), "第二个入队线程不得等待首个 action 完成");
        releaseFirst.countDown();
        first.join(1_000);
        assertTrue(!first.isAlive(), "首个发布线程必须在释放后结束");

        assertEquals(1, maximumPublishers.get());
        assertEquals(List.of("第一项", "第二项"), published);
        assertNull(threadFailure.get());
    }

    @Test
    void 披露动作重入和多次异常后不得卡住且首个异常保持主因() {
        GenerationDisclosureBuffer buffer = new GenerationDisclosureBuffer();
        List<String> published = new ArrayList<>();
        IllegalStateException firstFailure =
                new IllegalStateException("第一项披露失败");
        IllegalArgumentException secondFailure =
                new IllegalArgumentException("第二项披露失败");

        GenerationDisclosureBuffer.Disclosure head =
                buffer.enqueuePending(() -> {
                    published.add("第一项");
                    buffer.enqueueResolved(() -> published.add("重入项"));
                    throw firstFailure;
                });
        buffer.enqueueResolved(() -> {
            published.add("第二项");
            throw secondFailure;
        });
        buffer.enqueueResolved(() -> published.add("第三项"));

        RuntimeException actual = assertThrows(
                RuntimeException.class, () -> buffer.resolveDelayed(head));

        assertSame(firstFailure, actual);
        assertEquals(1, actual.getSuppressed().length);
        assertSame(secondFailure, actual.getSuppressed()[0]);
        assertEquals(
                List.of("第一项", "第二项", "第三项", "重入项"),
                published);

        buffer.enqueueResolved(() -> published.add("异常后新增项"));
        assertEquals("异常后新增项", published.getLast());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("等待并发测试信号超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("并发测试线程被中断", exception);
        }
    }
}
