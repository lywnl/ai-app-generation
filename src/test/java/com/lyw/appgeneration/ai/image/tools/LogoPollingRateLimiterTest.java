package com.lyw.appgeneration.ai.image.tools;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class LogoPollingRateLimiterTest {
    @Test
    void 查询额度均匀发放且空闲后不积攒额度() throws Exception {
        AtomicLong now = new AtomicLong(-1_000_000_000L);
        List<Long> sleeps = new ArrayList<>();
        LogoPollingRateLimiter limiter = new LogoPollingRateLimiter(10, now::get, nanos -> {
            sleeps.add(nanos);
            now.addAndGet(nanos);
        });
        for (int i = 0; i < 12; i++) {
            limiter.acquire();
        }
        assertEquals(11, sleeps.size());
        assertTrue(sleeps.stream().allMatch(nanos -> nanos == 100_000_000L));
        now.addAndGet(TimeUnit.SECONDS.toNanos(30));
        limiter.acquire();
        limiter.acquire();
        assertEquals(12, sleeps.size(), "空闲后第二次查询仍需等待额度");
    }

    @Test
    void 多线程等待同一额度且可中断退出() throws Exception {
        AtomicLong now = new AtomicLong();
        CountDownLatch sleeping = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LogoPollingRateLimiter limiter = new LogoPollingRateLimiter(10, now::get, nanos -> {
            sleeping.countDown();
            release.await();
            now.addAndGet(nanos);
        });
        limiter.acquire();
        try (var workers = Executors.newFixedThreadPool(2)) {
            try {
                var firstWaiter = workers.submit(() -> { limiter.acquire(); return true; });
                assertTrue(sleeping.await(2, TimeUnit.SECONDS));
                CountDownLatch secondStarted = new CountDownLatch(1);
                CountDownLatch secondStopped = new CountDownLatch(1);
                var secondWaiter = workers.submit(() -> {
                    secondStarted.countDown();
                    try {
                        limiter.acquire();
                        fail("已取消的等待者不能取得额度");
                    } catch (InterruptedException expected) {
                        secondStopped.countDown();
                    }
                });
                assertTrue(secondStarted.await(2, TimeUnit.SECONDS));
                assertFalse(firstWaiter.isDone());
                secondWaiter.cancel(true);
                assertTrue(secondStopped.await(2, TimeUnit.SECONDS));
                release.countDown();
                assertTrue(firstWaiter.get(2, TimeUnit.SECONDS));
                limiter.acquire();
                assertEquals(200_000_000L, now.get(), "取消的等待者不占用未来额度");
            } finally {
                release.countDown();
                workers.shutdownNow();
            }
        }
    }

    @Test
    void 等待额度时被中断不会额外消耗额度() throws Exception {
        AtomicLong now = new AtomicLong();
        LogoPollingRateLimiter limiter = new LogoPollingRateLimiter(10, now::get,
                nanos -> { throw new InterruptedException("取消"); });
        limiter.acquire();
        assertThrows(InterruptedException.class, limiter::acquire);
        now.set(100_000_000L);
        assertDoesNotThrow(limiter::acquire);
    }

    @Test
    void 拒绝无效频率和轮询间隔() {
        assertThrows(IllegalArgumentException.class, () -> new LogoGeneratorTool(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new LogoGeneratorTool(1000, 0));
        assertThrows(IllegalArgumentException.class, () -> new LogoGeneratorTool(60_001, 10));
    }
}
