package com.lyw.appgeneration.service.impl;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** 同一应用的 L2 抽取串行边界，不参与应用删除等待。 */
final class AppMemoryExtractionCoordinator {

    private final ConcurrentHashMap<Long, LockEntry> locks =
            new ConcurrentHashMap<>();

    Permit acquire(long appId) {
        LockEntry entry = locks.compute(appId, (ignored, current) -> {
            LockEntry selected = current == null
                    ? new LockEntry() : current;
            selected.references++;
            return selected;
        });
        entry.lock.lock();
        return new Permit(this, appId, entry);
    }

    int registeredAppCount() {
        return locks.size();
    }

    private void release(long appId, LockEntry entry) {
        entry.lock.unlock();
        locks.compute(appId, (ignored, current) -> {
            if (current != entry) {
                throw new IllegalStateException("L2 应用锁注册状态不一致");
            }
            entry.references--;
            if (entry.references < 0) {
                throw new IllegalStateException("L2 应用锁引用计数不能为负数");
            }
            return entry.references == 0 ? null : entry;
        });
    }

    private static final class LockEntry {

        private final ReentrantLock lock = new ReentrantLock();
        private int references;
    }

    static final class Permit implements AutoCloseable {

        private final AppMemoryExtractionCoordinator coordinator;
        private final long appId;
        private final LockEntry entry;
        private boolean closed;

        private Permit(AppMemoryExtractionCoordinator coordinator,
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
