package com.lyw.appgeneration.service.impl;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserMemoryConsistencyCoordinatorTest {

    @Test
    void 哈希碰撞的不同用户不得互相阻塞长期记忆操作() throws Exception {
        UserMemoryConsistencyCoordinator coordinator =
                new UserMemoryConsistencyCoordinator();
        CountDownLatch secondAcquired = new CountDownLatch(1);

        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            UserMemoryConsistencyCoordinator.Permit first =
                    coordinator.acquire(1L);
            Future<?> second = threads.submit(() -> {
                try (UserMemoryConsistencyCoordinator.Permit ignored =
                             coordinator.acquire(65L)) {
                    secondAcquired.countDown();
                }
            });
            try {
                assertTrue(secondAcquired.await(
                                200L, TimeUnit.MILLISECONDS),
                        "不同 userId 不得因 64 条带哈希碰撞而串行");
            } finally {
                first.close();
            }
            second.get(1L, TimeUnit.SECONDS);
        }
        assertEquals(0, coordinator.registeredUserCount());
    }

    @Test
    void 同一用户仍必须串行且释放后允许下一操作进入() throws Exception {
        UserMemoryConsistencyCoordinator coordinator =
                new UserMemoryConsistencyCoordinator();
        CountDownLatch secondAcquired = new CountDownLatch(1);

        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            UserMemoryConsistencyCoordinator.Permit first =
                    coordinator.acquire(7L);
            Future<?> second = threads.submit(() -> {
                try (UserMemoryConsistencyCoordinator.Permit ignored =
                             coordinator.acquire(7L)) {
                    secondAcquired.countDown();
                }
            });

            assertFalse(secondAcquired.await(100L, TimeUnit.MILLISECONDS));
            first.close();
            assertTrue(secondAcquired.await(1L, TimeUnit.SECONDS));
            second.get(1L, TimeUnit.SECONDS);
        }
        assertEquals(0, coordinator.registeredUserCount());
    }

    @Test
    void 等待者和持有者全部释放后用户锁注册必须归零() throws Exception {
        UserMemoryConsistencyCoordinator coordinator =
                new UserMemoryConsistencyCoordinator();

        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            UserMemoryConsistencyCoordinator.Permit first =
                    coordinator.acquire(9L);
            Future<?> second = threads.submit(() -> {
                try (UserMemoryConsistencyCoordinator.Permit ignored =
                             coordinator.acquire(9L)) {
                    // 获取后立即释放。
                }
            });
            try {
                assertEquals(1, coordinator.registeredUserCount());
            } finally {
                first.close();
            }
            second.get(1L, TimeUnit.SECONDS);
        }
        assertEquals(0, coordinator.registeredUserCount());
    }
}
