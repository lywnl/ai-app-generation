package com.lyw.appgeneration.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserMemoryDebounceExecutorConfigTest {

    @Test
    @DisplayName("L2 防抖使用独立单线程调度器且不在调用线程执行")
    void 防抖任务运行在独立单线程() throws Exception {
        ThreadPoolTaskScheduler scheduler =
                new UserMemoryDebounceExecutorConfig()
                        .userMemoryDebounceScheduler();
        scheduler.initialize();
        try {
            CountDownLatch completed = new CountDownLatch(1);
            AtomicReference<String> taskThread = new AtomicReference<>();
            String callerThread = Thread.currentThread().getName();

            scheduler.schedule(() -> {
                taskThread.set(Thread.currentThread().getName());
                completed.countDown();
            }, Instant.now());

            assertTrue(completed.await(1, TimeUnit.SECONDS));
            assertEquals(1, scheduler.getPoolSize());
            assertNotEquals(callerThread, taskThread.get());
            assertTrue(taskThread.get().startsWith("User-Memory-Debounce-"));
        } finally {
            scheduler.destroy();
        }
    }
}
