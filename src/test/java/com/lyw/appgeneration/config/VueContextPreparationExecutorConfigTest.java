package com.lyw.appgeneration.config;

import com.lyw.appgeneration.core.concurrency.VueTurnAdmissionController;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VueContextPreparationExecutorConfigTest {

    @Test
    void 有界准备池接纳全部在线回合且不超过Rag请求并发上限() throws Exception {
        RagProperties properties = new RagProperties();
        properties.getExecution().setMaxConcurrentRequests(2);
        var executor = new VueContextPreparationExecutorConfig()
                .vueContextPreparationExecutor(properties);
        CountDownLatch twoRunning = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int i = 0; i < VueTurnAdmissionController.MAX_ACTIVE_TURNS; i++) {
                tasks.add(executor.submit(() -> {
                    int current = active.incrementAndGet();
                    peak.accumulateAndGet(current, Math::max);
                    twoRunning.countDown();
                    try {
                        assertTrue(Thread.currentThread().getName().startsWith("vue-context-rag-"));
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                        return null;
                    } finally {
                        active.decrementAndGet();
                    }
                }));
            }
            assertTrue(twoRunning.await(3, TimeUnit.SECONDS));
            assertEquals(2, active.get());
            assertEquals(VueTurnAdmissionController.MAX_ACTIVE_TURNS - 2, executor.getQueue().size());
            release.countDown();
            for (Future<?> task : tasks) {
                task.get(3, TimeUnit.SECONDS);
            }
            assertEquals(2, peak.get());
            assertEquals(0, active.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
        assertThrows(RejectedExecutionException.class, () -> executor.submit(() -> null));
    }
}
