package com.lyw.appgeneration.config;

import com.lyw.appgeneration.monitor.AppCreationTiming;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.task.TaskRejectedException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppCreationExecutorConfigTest {

    @Test
    void 超过命名并发上限的请求等待资源并最终全部完成() throws Exception {
        var executor = new AppCreationExecutorConfig().appCreationNamingExecutor(2);
        var callers = Executors.newFixedThreadPool(8);
        CountDownLatch submitted = new CountDownLatch(8);
        CountDownLatch running = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<Future<?>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                results.add(callers.submit(() -> {
                    submitted.countDown();
                    return executor.submit(() -> {
                        int current = active.incrementAndGet();
                        peak.accumulateAndGet(current, Math::max);
                        running.countDown();
                        try {
                            assertTrue(Thread.currentThread().isVirtual());
                            assertTrue(Thread.currentThread().getName().startsWith("app-create-name-"));
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                            return "名称";
                        } finally {
                            active.decrementAndGet();
                        }
                    }).get(5, TimeUnit.SECONDS);
                }));
            }
            assertTrue(submitted.await(3, TimeUnit.SECONDS));
            assertTrue(running.await(3, TimeUnit.SECONDS));
            assertEquals(2, active.get());
            release.countDown();
            for (Future<?> result : results) {
                assertEquals("名称", result.get(3, TimeUnit.SECONDS));
            }
            assertEquals(2, peak.get());
            assertEquals(0, active.get());
        } finally {
            release.countDown();
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS));
            executor.close();
        }
        assertThrows(TaskRejectedException.class, () -> executor.submit(() -> "不应执行"));
    }

    @Test
    void 命名任务使用提交时的请求标识且不会污染后续任务() throws Exception {
        var previous = MDC.getCopyOfContextMap();
        var executor = new AppCreationExecutorConfig().appCreationNamingExecutor(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            MDC.put(AppCreationTiming.REQUEST_ID_KEY, "请求一");
            var first = executor.submit(() -> {
                started.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return AppCreationTiming.requestId();
            });
            assertTrue(started.await(3, TimeUnit.SECONDS));
            MDC.put(AppCreationTiming.REQUEST_ID_KEY, "请求二");
            release.countDown();
            assertEquals("请求一", first.get(3, TimeUnit.SECONDS));
            assertEquals("请求二", executor.submit(AppCreationTiming::requestId).get(3, TimeUnit.SECONDS));
            MDC.remove(AppCreationTiming.REQUEST_ID_KEY);
            assertEquals("-", executor.submit(AppCreationTiming::requestId).get(3, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.close();
            if (previous == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previous);
            }
        }
    }
}
