package com.lyw.appgeneration.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerializedGenerationStreamEmitterTest {

    @Test
    void 并发正文与终态必须由单一写入者按认领顺序发布() throws Exception {
        BlockingTarget target = new BlockingTarget();
        SerializedGenerationStreamEmitter emitter =
                new SerializedGenerationStreamEmitter(target);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> emitter.next("第一段"));
            assertTrue(target.firstWriteEntered.await(1, TimeUnit.SECONDS));

            var second = executor.submit(() -> emitter.next("第二段"));
            assertTrue(second.get(1, TimeUnit.SECONDS));
            assertTrue(emitter.complete());
            target.releaseFirstWrite.countDown();

            assertTrue(first.get(1, TimeUnit.SECONDS));
        }

        assertEquals(List.of("第一段", "第二段", "完成"), target.events);
        assertEquals(1, target.maximumConcurrentWriters.get());
        assertFalse(emitter.next("迟到正文"));
        assertFalse(emitter.complete());
        assertFalse(emitter.error(new IllegalStateException("迟到错误")));
    }

    @Test
    void 错误与完成竞争只能有一个终态且取消后丢弃排队内容() {
        RecordingTarget errorTarget = new RecordingTarget();
        SerializedGenerationStreamEmitter errorEmitter =
                new SerializedGenerationStreamEmitter(errorTarget);

        assertTrue(errorEmitter.error(new IllegalStateException("生成失败")));
        assertFalse(errorEmitter.complete());
        assertFalse(errorEmitter.next("迟到正文"));
        assertEquals(List.of("错误:生成失败"), errorTarget.events);

        RecordingTarget cancelledTarget = new RecordingTarget();
        SerializedGenerationStreamEmitter cancelledEmitter =
                new SerializedGenerationStreamEmitter(cancelledTarget);
        cancelledEmitter.cancel();
        assertFalse(cancelledEmitter.next("取消后正文"));
        assertFalse(cancelledEmitter.complete());
        assertEquals(List.of(), cancelledTarget.events);
    }

    @Test
    void 串行任务异常后必须允许受控错误唯一收口() {
        RecordingTarget target = new RecordingTarget();
        SerializedGenerationStreamEmitter emitter =
                new SerializedGenerationStreamEmitter(target);

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> emitter.execute(ignored -> {
                    throw new IllegalArgumentException("非法信号");
                }));

        assertTrue(emitter.error(new IllegalStateException("协议错误")));
        assertFalse(emitter.complete());
        assertEquals(List.of("错误:协议错误"), target.events);
    }

    @Test
    void 前序任务异常不得丢失并发线程已认领的完成终态() throws Exception {
        RecordingTarget target = new RecordingTarget();
        SerializedGenerationStreamEmitter emitter =
                new SerializedGenerationStreamEmitter(target);
        CountDownLatch actionEntered = new CountDownLatch(1);
        CountDownLatch releaseAction = new CountDownLatch(1);
        AtomicReference<Throwable> actionFailure = new AtomicReference<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var action = executor.submit(() -> {
                try {
                    emitter.execute(ignored -> {
                        actionEntered.countDown();
                        try {
                            assertTrue(releaseAction.await(
                                    1, TimeUnit.SECONDS));
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(
                                    "测试等待被中断", exception);
                        }
                        throw new IllegalArgumentException("任务失败");
                    });
                } catch (Throwable error) {
                    actionFailure.set(error);
                }
            });
            assertTrue(actionEntered.await(1, TimeUnit.SECONDS));
            assertTrue(emitter.complete());
            releaseAction.countDown();
            action.get(1, TimeUnit.SECONDS);
        }

        assertTrue(actionFailure.get() instanceof IllegalArgumentException);
        assertEquals(List.of("完成"), target.events);
        assertFalse(emitter.next("迟到正文"));
        assertFalse(emitter.complete());
    }

    @Test
    void 终态前任务异常后不得重新开放业务写入() {
        RecordingTarget target = new RecordingTarget();
        SerializedGenerationStreamEmitter emitter =
                new SerializedGenerationStreamEmitter(target);

        assertThrows(IllegalArgumentException.class,
                () -> emitter.complete(ignored -> {
                    throw new IllegalArgumentException("收尾失败");
                }));

        assertFalse(emitter.next("迟到正文"));
        assertFalse(emitter.complete());
        assertEquals(List.of("错误:收尾失败"), target.events);
    }

    private static class RecordingTarget
            implements SerializedGenerationStreamEmitter.Target {

        protected final List<String> events =
                java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void next(String value) {
            events.add(value);
        }

        @Override
        public void complete() {
            events.add("完成");
        }

        @Override
        public void error(Throwable error) {
            events.add("错误:" + error.getMessage());
        }
    }

    private static final class BlockingTarget extends RecordingTarget {

        private final CountDownLatch firstWriteEntered =
                new CountDownLatch(1);
        private final CountDownLatch releaseFirstWrite =
                new CountDownLatch(1);
        private final AtomicInteger activeWriters = new AtomicInteger();
        private final AtomicInteger maximumConcurrentWriters =
                new AtomicInteger();

        @Override
        public void next(String value) {
            int active = activeWriters.incrementAndGet();
            maximumConcurrentWriters.accumulateAndGet(active, Math::max);
            try {
                if ("第一段".equals(value)) {
                    firstWriteEntered.countDown();
                    assertTrue(releaseFirstWrite.await(
                            1, TimeUnit.SECONDS));
                }
                super.next(value);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("测试等待被中断", exception);
            } finally {
                activeWriters.decrementAndGet();
            }
        }
    }
}
