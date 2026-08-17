package com.lyw.appgeneration.service.impl;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.TimeUnit;

/** 为 app 级摘要 cache-aside 读写提供精确一致性边界。 */
final class AppMemorySummaryConsistencyCoordinator {

    private final ConcurrentHashMap<Long, LockEntry> locks =
            new ConcurrentHashMap<>();

    Permit acquire(long appId) {
        LockEntry entry = register(appId);
        entry.lock.lock();
        return new Permit(this, appId, entry);
    }

    Permit tryAcquireUntil(long appId, long deadlineNanos) {
        LockEntry entry = register(appId);
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            unregister(appId, entry);
            return null;
        }
        try {
            if (entry.lock.tryLock(remainingNanos, TimeUnit.NANOSECONDS)) {
                return new Permit(this, appId, entry);
            }
            unregister(appId, entry);
            return null;
        } catch (InterruptedException exception) {
            unregister(appId, entry);
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private LockEntry register(long appId) {
        return locks.compute(appId, (ignored, current) -> {
            LockEntry selected = current == null
                    ? new LockEntry() : current;
            selected.references++;
            return selected;
        });
    }

    private void release(long appId, LockEntry entry) {
        entry.lock.unlock();
        unregister(appId, entry);
    }

    private void unregister(long appId, LockEntry entry) {
        locks.compute(appId, (ignored, current) -> {
            if (current != entry) {
                throw new IllegalStateException("L1 应用锁注册状态不一致");
            }
            entry.references--;
            if (entry.references < 0) {
                throw new IllegalStateException("L1 应用锁引用计数不能为负数");
            }
            return entry.references == 0 ? null : entry;
        });
    }

    private static final class LockEntry {

        private final ReentrantLock lock = new ReentrantLock();
        private int references;
    }

    static final class Permit implements AutoCloseable {

        private final AppMemorySummaryConsistencyCoordinator coordinator;
        private final long appId;
        private final LockEntry entry;
        private boolean closed;

        private Permit(AppMemorySummaryConsistencyCoordinator coordinator,
                       long appId,
                       LockEntry entry) {
            this.coordinator = coordinator;
            this.appId = appId;
            this.entry = entry;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            coordinator.release(appId, entry);
        }
    }
}
