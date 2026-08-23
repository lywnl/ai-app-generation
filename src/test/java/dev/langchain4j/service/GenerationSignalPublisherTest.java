package dev.langchain4j.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationSignalPublisherTest {

    @Test
    void 同一状态动作的全部信号必须先原子入队再允许外部监听器执行()
            throws Exception {
        GenerationDisclosureBuffer buffer =
                new GenerationDisclosureBuffer();
        List<GenerationStreamSignal> published =
                new CopyOnWriteArrayList<>();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        GenerationSignalPublisher publisher =
                new GenerationSignalPublisher(buffer, signal -> {
                    published.add(signal);
                    if (signal instanceof GenerationStreamSignal.Recovery) {
                        firstEntered.countDown();
                        await(releaseFirst);
                    }
                });
        GenerationStreamSignal first = recoverySignal();
        GenerationStreamSignal second =
                new GenerationStreamSignal.AiText(2L, "恢复正文");
        GenerationStreamSignal third =
                new GenerationStreamSignal.Rollback(2L, 4, java.util.Set.of());

        Thread batchThread = Thread.startVirtualThread(() ->
                publisher.publishAtomically(() -> {
                    publisher.accept(first);
                    publisher.accept(second);
                }));
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS));

        publisher.accept(third);
        assertEquals(List.of(first), published,
                "首个 listener 阻塞时后续信号只能入队");

        releaseFirst.countDown();
        batchThread.join(2_000);

        assertFalse(batchThread.isAlive());
        assertEquals(List.of(first, second, third), published);
    }

    @Test
    void 批次尾部动作必须等待全部信号监听完成后再执行() {
        GenerationDisclosureBuffer buffer =
                new GenerationDisclosureBuffer();
        List<String> order = new CopyOnWriteArrayList<>();
        GenerationSignalPublisher publisher =
                new GenerationSignalPublisher(buffer, signal ->
                        order.add(signal instanceof GenerationStreamSignal
                                .Recovery ? "恢复信号" : "正文信号"));

        publisher.pausePublishing();
        publisher.publishAtomically(() -> {
            publisher.accept(recoverySignal());
            publisher.accept(new GenerationStreamSignal.AiText(
                    2L, "恢复正文"));
        }, () -> order.add("批次尾部"));

        assertTrue(order.isEmpty());
        publisher.resumePublishing();

        assertEquals(List.of("恢复信号", "正文信号", "批次尾部"),
                order);
    }

    private GenerationStreamSignal recoverySignal() {
        return new GenerationStreamSignal.Recovery(
                GenerationStreamSignal.Recovery.Phase.RECOVERED,
                1L, 2L, null);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("等待释放 generation listener 超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待被中断", exception);
        }
    }
}
