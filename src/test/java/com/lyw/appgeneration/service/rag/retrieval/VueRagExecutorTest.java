package com.lyw.appgeneration.service.rag.retrieval;

import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.service.rag.model.RagDocumentKind;
import com.lyw.appgeneration.service.rag.monitor.VueRagMetricsCollector;
import com.lyw.appgeneration.service.rag.monitor.VueRagStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@Timeout(20)
class VueRagExecutorTest {

    private final List<VueRagExecutor> executors = new ArrayList<>();

    @AfterEach
    void closeExecutors() {
        executors.forEach(VueRagExecutor::close);
        MDC.clear();
    }

    @Test
    void 超额请求在入口等待且完整任务不会超过工作线程上限() throws Exception {
        VueRagExecutor executor = executor(2, 2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean nextAdmitted = new AtomicBoolean();
        CompletableFuture<Integer> nextResult = new CompletableFuture<>();
        try (var first = executor.openRequest(); var second = executor.openRequest()) {
            List<CompletableFuture<Integer>> tasks = new ArrayList<>();
            for (int index = 0; index < 7; index++) {
                for (var request : List.of(first, second)) {
                    tasks.add(request.submit(VueRagStage.BM25, RagDocumentKind.PROJECT_SKELETON, () -> {
                        peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                        started.countDown();
                        try {
                            await(release);
                            return completed.incrementAndGet();
                        } finally {
                            active.decrementAndGet();
                        }
                    }));
                }
            }
            Thread next = start(nextResult, () -> {
                try (var request = executor.openRequest()) {
                    nextAdmitted.set(true);
                    return request.await(request.submit(VueRagStage.EMBEDDING, null, completed::incrementAndGet));
                }
            });
            try {
                await(started);
                awaitWaiting(next);
                assertFalse(nextAdmitted.get());
                assertEquals(2, peak.get());
                release.countDown();
                CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
                first.close();
                second.close();
                assertEquals(15, nextResult.get(5, TimeUnit.SECONDS));
                assertEquals(2, peak.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void 取消Future后仍等待物理调用结束才归还许可() throws Exception {
        VueRagExecutor executor = executor(1, 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean physicalCallFinished = new AtomicBoolean();
        AtomicBoolean queuedCallExecuted = new AtomicBoolean();
        AtomicBoolean nextAdmitted = new AtomicBoolean();
        CompletableFuture<Integer> nextResult = new CompletableFuture<>();
        try (var request = executor.openRequest()) {
            var running = request.submit(VueRagStage.EMBEDDING, null, () -> {
                started.countDown();
                await(release);
                physicalCallFinished.set(true);
                return 1;
            });
            var queued = request.submit(VueRagStage.DENSE, RagDocumentKind.PROJECT_SKELETON, () -> {
                queuedCallExecuted.set(true);
                return 2;
            });
            try {
                await(started);
                assertTrue(running.cancel(true));
                assertTrue(queued.cancel(true));
                request.close();
                Thread next = start(nextResult, () -> {
                    try (var nextRequest = executor.openRequest()) {
                        nextAdmitted.set(true);
                        assertTrue(physicalCallFinished.get());
                        return nextRequest.await(nextRequest.submit(VueRagStage.EMBEDDING, null, () -> 3));
                    }
                });
                awaitWaiting(next);
                assertFalse(nextAdmitted.get());
                release.countDown();
                assertEquals(3, nextResult.get(5, TimeUnit.SECONDS));
                assertFalse(queuedCallExecuted.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void 入口等待中断恢复标志且不会消耗或多归还许可() throws Exception {
        VueRagExecutor executor = executor(1, 1);
        AtomicBoolean interrupted = new AtomicBoolean();
        CompletableFuture<Integer> waitingResult = new CompletableFuture<>();
        try (var held = executor.openRequest()) {
            Thread waiting = start(waitingResult, () -> {
                try (var ignored = executor.openRequest()) {
                    return 1;
                } catch (CancellationException failure) {
                    interrupted.set(Thread.currentThread().isInterrupted());
                    throw failure;
                }
            });
            awaitWaiting(waiting);
            waiting.interrupt();
            assertTrue(waiting.join(Duration.ofSeconds(5)));
            assertTrue(interrupted.get());
            assertThrows(CancellationException.class, waitingResult::join);

            AtomicBoolean nextAdmitted = new AtomicBoolean();
            CompletableFuture<Integer> nextResult = new CompletableFuture<>();
            Thread next = start(nextResult, () -> {
                try (var request = executor.openRequest()) {
                    nextAdmitted.set(true);
                    return request.await(request.submit(VueRagStage.EMBEDDING, null, () -> 2));
                }
            });
            awaitWaiting(next);
            assertFalse(nextAdmitted.get());
            held.close();
            assertEquals(2, nextResult.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void 请求执行中断后在外部调用结束前不接纳下一请求() throws Exception {
        VueRagExecutor executor = executor(1, 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        CompletableFuture<Integer> firstResult = new CompletableFuture<>();
        Thread first = start(firstResult, () -> {
            try (var request = executor.openRequest()) {
                return request.await(request.submit(VueRagStage.EMBEDDING, null, () -> {
                    started.countDown();
                    await(release);
                    return 1;
                }));
            } catch (CancellationException failure) {
                interrupted.set(Thread.currentThread().isInterrupted());
                throw failure;
            }
        });
        try {
            await(started);
            first.interrupt();
            assertTrue(first.join(Duration.ofSeconds(5)));
            assertTrue(interrupted.get());
            assertThrows(CancellationException.class, firstResult::join);
            AtomicBoolean nextAdmitted = new AtomicBoolean();
            CompletableFuture<Integer> nextResult = new CompletableFuture<>();
            Thread next = start(nextResult, () -> {
                try (var request = executor.openRequest()) {
                    nextAdmitted.set(true);
                    return request.await(request.submit(VueRagStage.EMBEDDING, null, () -> 2));
                }
            });
            awaitWaiting(next);
            assertFalse(nextAdmitted.get());
            release.countDown();
            assertEquals(2, nextResult.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    void 外部调用异常后下一请求仍能取得许可() {
        VueRagExecutor executor = executor(1, 1);
        try (var first = executor.openRequest()) {
            var failed = first.submit(VueRagStage.EMBEDDING, null, () -> {
                throw new IllegalStateException("受控失败");
            });
            assertThrows(IllegalStateException.class, () -> first.await(failed));
        }
        try (var next = executor.openRequest()) {
            assertEquals(2, next.await(next.submit(VueRagStage.EMBEDDING, null, () -> 2)));
        }
    }

    @Test
    void 关闭时等待已接纳请求的后续阶段且拒绝新请求() throws Exception {
        VueRagExecutor executor = executor(1, 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Boolean> closed = new CompletableFuture<>();
        try (var request = executor.openRequest()) {
            var chain = request.submit(VueRagStage.EMBEDDING, null, () -> {
                started.countDown();
                await(release);
                return 1;
            }).thenCompose(value -> request.submit(VueRagStage.DENSE, RagDocumentKind.PROJECT_SKELETON,
                    () -> value + 1));
            Thread closer = start(closed, () -> {
                executor.close();
                return true;
            });
            try {
                await(started);
                awaitWaiting(closer);
                assertThrows(RejectedExecutionException.class, executor::openRequest);
                release.countDown();
                assertEquals(2, request.await(chain));
                request.close();
                assertTrue(closed.get(5, TimeUnit.SECONDS));
                assertThrows(RejectedExecutionException.class, executor::openRequest);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void 跨线程和后续阶段保留请求MDC而不同请求之间不串数据() {
        VueRagExecutor executor = executor(1, 1);
        for (String trace : List.of("请求甲", "请求乙")) {
            MDC.put("ragBenchmarkTrace", trace);
            try (var request = executor.openRequest()) {
                var chain = request.submit(VueRagStage.EMBEDDING, null, () -> {
                    assertEquals(trace, MDC.get("ragBenchmarkTrace"));
                    MDC.put("仅限工作线程", "临时值");
                    return 1;
                }).thenCompose(value -> request.submit(VueRagStage.DENSE, RagDocumentKind.PROJECT_SKELETON,
                        () -> MDC.get("ragBenchmarkTrace")));
                assertEquals(trace, request.await(chain));
                assertNull(MDC.get("仅限工作线程"));
            }
        }
        MDC.clear();
        try (var request = executor.openRequest()) {
            assertNull(request.await(request.submit(VueRagStage.EMBEDDING, null,
                    () -> MDC.get("ragBenchmarkTrace"))));
        }
    }

    private VueRagExecutor executor(int workers, int requests) {
        RagProperties properties = new RagProperties();
        properties.getExecution().setWorkerThreads(workers);
        properties.getExecution().setMaxConcurrentRequests(requests);
        properties.getExecution().setQueueCapacity(7 * requests);
        VueRagExecutor executor = new VueRagExecutor(properties, mock(VueRagMetricsCollector.class));
        executors.add(executor);
        return executor;
    }

    private static <T> Thread start(CompletableFuture<T> result, Supplier<T> operation) {
        return Thread.ofPlatform().start(() -> {
            try {
                result.complete(operation.get());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "受控任务没有按预期推进");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("测试任务被中断");
        }
    }

    private static void awaitWaiting(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.isAlive() && thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        assertEquals(Thread.State.WAITING, thread.getState(), "调用线程应停在许可等待处");
    }
}
