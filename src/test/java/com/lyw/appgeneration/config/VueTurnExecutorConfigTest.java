package com.lyw.appgeneration.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            assertEquals(VueTurnExecutorConfig.MAX_CONCURRENCY,
                    executor.getConcurrencyLimit());
        }
    }
}
