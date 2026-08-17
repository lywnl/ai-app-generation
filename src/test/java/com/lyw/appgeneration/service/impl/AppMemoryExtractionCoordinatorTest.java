package com.lyw.appgeneration.service.impl;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppMemoryExtractionCoordinatorTest {

    @Test
    void 哈希碰撞的不同应用不得互相阻塞模型抽取() throws Exception {
        AppMemoryExtractionCoordinator coordinator =
                new AppMemoryExtractionCoordinator();
        CountDownLatch secondAcquired = new CountDownLatch(1);

        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            AppMemoryExtractionCoordinator.Permit first =
                    coordinator.acquire(1L);
            Future<?> second = threads.submit(() -> {
                try (AppMemoryExtractionCoordinator.Permit permit =
                             coordinator.acquire(65L)) {
                    secondAcquired.countDown();
                }
            });
            try {
                assertTrue(secondAcquired.await(
                                200L, TimeUnit.MILLISECONDS),
                        "不同 appId 不得因 64 条带哈希碰撞而串行");
            } finally {
                first.close();
            }
            second.get(1L, TimeUnit.SECONDS);
        }
        assertEquals(0, coordinator.registeredAppCount());
    }

    @Test
    void 同一应用仍必须串行且释放后允许下一任务进入() throws Exception {
        AppMemoryExtractionCoordinator coordinator =
                new AppMemoryExtractionCoordinator();
        CountDownLatch secondAcquired = new CountDownLatch(1);

        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            AppMemoryExtractionCoordinator.Permit first =
                    coordinator.acquire(7L);
            Future<?> second = threads.submit(() -> {
                try (AppMemoryExtractionCoordinator.Permit permit =
                             coordinator.acquire(7L)) {
                    secondAcquired.countDown();
                }
            });

            assertFalse(secondAcquired.await(100L, TimeUnit.MILLISECONDS));
            first.close();
            assertTrue(secondAcquired.await(1L, TimeUnit.SECONDS));
            second.get(1L, TimeUnit.SECONDS);
        }
        assertEquals(0, coordinator.registeredAppCount());
    }
}
