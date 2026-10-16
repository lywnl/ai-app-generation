package com.lyw.appgeneration.ai.image.tools;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/** 同一 Logo 工具实例共享的查询限频器，不积攒突发额度。 */
final class LogoPollingRateLimiter {

    @FunctionalInterface
    interface Sleeper {
        void sleep(long nanos) throws InterruptedException;
    }

    private final ReentrantLock lock = new ReentrantLock(true);
    private final long spacingNanos;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;
    private boolean initialized;
    private long nextAllowedAt;

    LogoPollingRateLimiter(int maxPollsPerSecond, LongSupplier nanoTime, Sleeper sleeper) {
        if (maxPollsPerSecond <= 0) {
            throw new IllegalArgumentException("Logo 每秒查询上限必须大于 0");
        }
        this.spacingNanos = Math.ceilDiv(TimeUnit.SECONDS.toNanos(1), maxPollsPerSecond);
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
    }

    void acquire() throws InterruptedException {
        // 公平锁保证等待者依次领取额度；中断退出不预占未来额度。
        lock.lockInterruptibly();
        try {
            while (initialized) {
                long remaining = nextAllowedAt - nanoTime.getAsLong();
                if (remaining <= 0) {
                    break;
                }
                sleeper.sleep(remaining);
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Logo 查询限频等待已中断");
            }
            nextAllowedAt = nanoTime.getAsLong() + spacingNanos;
            initialized = true;
        } finally {
            lock.unlock();
        }
    }
}
