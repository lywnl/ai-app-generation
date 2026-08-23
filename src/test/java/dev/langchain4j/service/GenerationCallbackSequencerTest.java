package dev.langchain4j.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationCallbackSequencerTest {

    @Test
    void 并发提交必须串行执行且后续提交不得等待前一动作结束()
            throws Exception {
        GenerationCallbackSequencer sequencer =
                new GenerationCallbackSequencer();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondReturned = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        List<String> order = new CopyOnWriteArrayList<>();

        Thread first = Thread.startVirtualThread(() ->
                sequencer.submit(() -> {
                    int current = active.incrementAndGet();
                    maximumActive.accumulateAndGet(current, Math::max);
                    order.add("第一项进入");
                    firstEntered.countDown();
                    await(releaseFirst);
                    order.add("第一项完成");
                    active.decrementAndGet();
                }));
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS));

        Thread second = Thread.startVirtualThread(() -> {
            sequencer.submit(() -> {
                int current = active.incrementAndGet();
                maximumActive.accumulateAndGet(current, Math::max);
                order.add("第二项进入");
                active.decrementAndGet();
            });
            secondReturned.countDown();
        });

        assertTrue(secondReturned.await(2, TimeUnit.SECONDS),
                "后续 provider 回调只负责提交，不得等待前一动作结束");
        assertEquals(List.of("第一项进入"), order);
        releaseFirst.countDown();
        first.join(2_000);
        second.join(2_000);

        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertEquals(1, maximumActive.get());
        assertEquals(List.of("第一项进入", "第一项完成", "第二项进入"),
                order);
    }

    @Test
    void 批次钩子必须包围队列内全部状态提交() {
        List<String> order = new CopyOnWriteArrayList<>();
        GenerationCallbackSequencer sequencer =
                new GenerationCallbackSequencer(
                        () -> order.add("暂停披露"),
                        () -> order.add("恢复披露"));

        sequencer.submit(() -> {
            order.add("检测");
            sequencer.submit(() -> order.add("候选账本"));
            order.add("披露提交");
        });

        assertEquals(List.of(
                "暂停披露", "检测", "披露提交", "候选账本", "恢复披露"),
                order);
    }

    @Test
    void 前置钩子失败不得执行结束钩子且后续提交必须恢复并排空() {
        AtomicInteger beforeAttempts = new AtomicInteger();
        AtomicInteger afterCalls = new AtomicInteger();
        List<String> executed = new CopyOnWriteArrayList<>();
        IllegalStateException expected =
                new IllegalStateException("暂停披露失败");
        GenerationCallbackSequencer sequencer =
                new GenerationCallbackSequencer(
                        () -> {
                            if (beforeAttempts.getAndIncrement() == 0) {
                                throw expected;
                            }
                        },
                        afterCalls::incrementAndGet);

        IllegalStateException actual = assertThrows(
                IllegalStateException.class,
                () -> sequencer.submit(() -> executed.add("第一项")));

        assertSame(expected, actual);
        assertTrue(executed.isEmpty());
        assertEquals(0, afterCalls.get(),
                "前置钩子未成功时不得执行与之配对的结束钩子");

        sequencer.submit(() -> executed.add("第二项"));

        assertEquals(List.of("第一项", "第二项"), executed);
        assertEquals(2, beforeAttempts.get());
        assertEquals(1, afterCalls.get());
    }

    @Test
    void 状态动作失败后必须继续排空且后续批次仍可认领() {
        List<String> executed = new CopyOnWriteArrayList<>();
        IllegalStateException expected =
                new IllegalStateException("状态提交失败");
        GenerationCallbackSequencer sequencer =
                new GenerationCallbackSequencer();

        IllegalStateException actual = assertThrows(
                IllegalStateException.class,
                () -> sequencer.submit(() -> {
                    executed.add("失败项");
                    sequencer.submit(() -> executed.add("已排队项"));
                    throw expected;
                }));

        assertSame(expected, actual);
        assertEquals(List.of("失败项", "已排队项"), executed);

        sequencer.submit(() -> executed.add("后续批次"));

        assertEquals(
                List.of("失败项", "已排队项", "后续批次"),
                executed);
    }

    @Test
    void 结束钩子失败后必须释放所有权且后续批次仍可执行() {
        AtomicInteger afterAttempts = new AtomicInteger();
        List<String> executed = new CopyOnWriteArrayList<>();
        IllegalStateException expected =
                new IllegalStateException("恢复披露失败");
        GenerationCallbackSequencer sequencer =
                new GenerationCallbackSequencer(
                        () -> { },
                        () -> {
                            if (afterAttempts.getAndIncrement() == 0) {
                                throw expected;
                            }
                        });

        IllegalStateException actual = assertThrows(
                IllegalStateException.class,
                () -> sequencer.submit(() -> executed.add("第一批")));

        assertSame(expected, actual);
        assertEquals(List.of("第一批"), executed);

        sequencer.submit(() -> executed.add("第二批"));

        assertEquals(List.of("第一批", "第二批"), executed);
        assertEquals(2, afterAttempts.get());
    }

    @Test
    void 动作与结束钩子都失败时必须保留首个异常并追加受抑制异常() {
        IllegalStateException actionFailure =
                new IllegalStateException("状态提交失败");
        IllegalArgumentException afterFailure =
                new IllegalArgumentException("恢复披露失败");
        GenerationCallbackSequencer sequencer =
                new GenerationCallbackSequencer(
                        () -> { },
                        () -> {
                            throw afterFailure;
                        });

        RuntimeException actual = assertThrows(
                RuntimeException.class,
                () -> sequencer.submit(() -> {
                    throw actionFailure;
                }));

        assertSame(actionFailure, actual);
        assertEquals(1, actual.getSuppressed().length);
        assertSame(afterFailure, actual.getSuppressed()[0]);
    }

    @Test
    void 释放所有权前钩子必须完成且结束钩子阻塞时新批次仍可执行()
            throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch afterEntered = new CountDownLatch(1);
        CountDownLatch releaseAfter = new CountDownLatch(1);
        CountDownLatch secondReturned = new CountDownLatch(1);
        AtomicInteger afterCalls = new AtomicInteger();
        GenerationCallbackSequencer sequencer =
                new GenerationCallbackSequencer(
                        () -> order.add("暂停"),
                        () -> order.add("提交披露"),
                        () -> {
                            order.add("开始发布");
                            if (afterCalls.getAndIncrement() > 0) {
                                return;
                            }
                            afterEntered.countDown();
                            await(releaseAfter);
                        });

        Thread first = Thread.startVirtualThread(() ->
                sequencer.submit(() -> order.add("第一批状态")));
        assertTrue(afterEntered.await(2, TimeUnit.SECONDS));

        Thread second = Thread.startVirtualThread(() -> {
            sequencer.submit(() -> order.add("第二批状态"));
            secondReturned.countDown();
        });
        assertTrue(secondReturned.await(2, TimeUnit.SECONDS),
                "结束钩子阻塞时新批次不得等待旧外部监听器");
        assertEquals(List.of(
                "暂停", "第一批状态", "提交披露", "开始发布",
                "暂停", "第二批状态", "提交披露", "开始发布"),
                order);

        releaseAfter.countDown();
        first.join(2_000);
        second.join(2_000);
        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("等待串行动作释放超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待串行动作被中断", exception);
        }
    }
}
