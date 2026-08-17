package com.lyw.appgeneration.service.impl;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppMemorySummaryConsistencyCoordinatorTest {

    @Test
    void 哈希碰撞的不同应用不得互相阻塞摘要一致性操作() throws Exception {
        AppMemorySummaryConsistencyCoordinator coordinator =
                new AppMemorySummaryConsistencyCoordinator();
        CountDownLatch secondAcquired = new CountDownLatch(1);

        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            AppMemorySummaryConsistencyCoordinator.Permit first =
                    coordinator.acquire(1L);
            Future<?> second = threads.submit(() -> {
                try (AppMemorySummaryConsistencyCoordinator.Permit ignored =
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
        assertEquals(0, registeredAppCount(coordinator));
    }

    @Test
    void 同一应用仍必须串行且释放后允许下一操作进入() throws Exception {
        AppMemorySummaryConsistencyCoordinator coordinator =
                new AppMemorySummaryConsistencyCoordinator();
        CountDownLatch secondAcquired = new CountDownLatch(1);

        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            AppMemorySummaryConsistencyCoordinator.Permit first =
                    coordinator.acquire(7L);
            Future<?> second = threads.submit(() -> {
                try (AppMemorySummaryConsistencyCoordinator.Permit ignored =
                             coordinator.acquire(7L)) {
                    secondAcquired.countDown();
                }
            });

            assertFalse(secondAcquired.await(100L, TimeUnit.MILLISECONDS));
            first.close();
            assertTrue(secondAcquired.await(1L, TimeUnit.SECONDS));
            second.get(1L, TimeUnit.SECONDS);
        }
        assertEquals(0, registeredAppCount(coordinator));
    }

    @Test
    void 截止时间耗尽的等待者不得泄漏应用锁注册() throws Exception {
        AppMemorySummaryConsistencyCoordinator coordinator =
                new AppMemorySummaryConsistencyCoordinator();

        try (AppMemorySummaryConsistencyCoordinator.Permit ignored =
                     coordinator.acquire(9L);
             var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<AppMemorySummaryConsistencyCoordinator.Permit> timed =
                    threads.submit(() -> coordinator.tryAcquireUntil(
                            9L, System.nanoTime()
                                    + Duration.ofMillis(50).toNanos()));

            assertNull(timed.get(1L, TimeUnit.SECONDS));
            assertEquals(1, registeredAppCount(coordinator),
                    "超时等待者必须释放自己的引用，仅保留持锁者");
        }
        assertEquals(0, registeredAppCount(coordinator));
    }

    private int registeredAppCount(
            AppMemorySummaryConsistencyCoordinator coordinator) {
        for (Field field : coordinator.getClass().getDeclaredFields()) {
            if (!Map.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                return ((Map<?, ?>) field.get(coordinator)).size();
            } catch (IllegalAccessException exception) {
                throw new AssertionError("无法读取摘要锁注册表", exception);
            }
        }
        return Integer.MAX_VALUE;
    }
}
