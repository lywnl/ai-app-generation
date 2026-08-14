package com.lyw.appgeneration.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VueTurnExecutorConfigTest {

    @Test
    void cancellationExecutorUsesManagedVirtualThreadsAndBoundedConcurrency()
            throws Exception {
        SimpleAsyncTaskExecutor executor = new VueTurnExecutorConfig()
                .vueTurnCancellationExecutor();
        CountDownLatch ran = new CountDownLatch(1);
        AtomicBoolean virtual = new AtomicBoolean();
        try (executor) {
            executor.execute(() -> {
                virtual.set(Thread.currentThread().isVirtual());
                ran.countDown();
            });
            assertTrue(ran.await(1, TimeUnit.SECONDS));
            assertTrue(virtual.get());
            assertEquals(SimpleAsyncTaskExecutor.UNBOUNDED_CONCURRENCY,
                    executor.getConcurrencyLimit());
        }
    }

    @Test
    void 六十五个已准入回合的清理提交不得阻塞Reactor线程()
            throws Exception {
        SimpleAsyncTaskExecutor executor = new VueTurnExecutorConfig()
                .vueTurnCancellationExecutor();
        int taskCount = 65;
        CountDownLatch allRunning = new CountDownLatch(taskCount);
        CountDownLatch release = new CountDownLatch(1);
        Set<String> threadNames = ConcurrentHashMap.newKeySet();
        AtomicBoolean allVirtual = new AtomicBoolean(true);
        try (executor) {
            StepVerifier.create(Flux.range(0, taskCount)
                            .parallel()
                            .runOn(Schedulers.parallel())
                            .doOnNext(ignored -> executor.execute(() -> {
                                Thread thread = Thread.currentThread();
                                threadNames.add(thread.getName());
                                allVirtual.compareAndSet(true,
                                        thread.isVirtual());
                                allRunning.countDown();
                                try {
                                    release.await();
                                } catch (InterruptedException exception) {
                                    Thread.currentThread().interrupt();
                                }
                            }))
                            .sequential())
                    .expectNextCount(taskCount)
                    .expectComplete()
                    .verify(Duration.ofSeconds(2));
            assertTrue(allRunning.await(2, TimeUnit.SECONDS),
                    "全部已准入回合的必要清理都必须及时启动");
            assertTrue(allVirtual.get());
            assertFalse(threadNames.isEmpty());
            assertTrue(threadNames.stream()
                    .allMatch(name -> name.startsWith("vue-turn-cancel-")));
        } finally {
            release.countDown();
        }
    }

    @Test
    void closedExecutorRejectsNewFinalizationTasks() {
        SimpleAsyncTaskExecutor executor = new VueTurnExecutorConfig()
                .vueTurnCancellationExecutor();
        executor.close();

        assertThrows(TaskRejectedException.class,
                () -> executor.execute(() -> { }));
    }
}
