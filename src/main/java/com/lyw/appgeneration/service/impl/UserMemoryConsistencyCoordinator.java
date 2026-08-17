package com.lyw.appgeneration.service.impl;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 为用户级共享记忆缓存提供进程内一致性边界。
 */
final class UserMemoryConsistencyCoordinator {

    private final ConcurrentHashMap<Long, LockEntry> locks =
            new ConcurrentHashMap<>();

    Permit acquire(long userId) {
        LockEntry entry = locks.compute(userId, (ignored, current) -> {
            LockEntry selected = current == null
                    ? new LockEntry() : current;
            selected.references++;
            return selected;
        });
        entry.lock.lock();
        return new Permit(this, userId, entry);
    }

    int registeredUserCount() {
        return locks.size();
    }

    private void release(long userId, LockEntry entry) {
        entry.lock.unlock();
        locks.compute(userId, (ignored, current) -> {
            if (current != entry) {
                throw new IllegalStateException("L2 用户锁注册状态不一致");
            }
            entry.references--;
            if (entry.references < 0) {
                throw new IllegalStateException("L2 用户锁引用计数不能为负数");
            }
            return entry.references == 0 ? null : entry;
        });
    }

    private static final class LockEntry {

        private final ReentrantLock lock = new ReentrantLock();
        private int references;
    }

    static final class Permit implements AutoCloseable {

        private final UserMemoryConsistencyCoordinator coordinator;
        private final long userId;
        private final LockEntry entry;
        private boolean closed;

        private Permit(UserMemoryConsistencyCoordinator coordinator,
                       long userId,
                       LockEntry entry) {
            this.coordinator = coordinator;
            this.userId = userId;
            this.entry = entry;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            coordinator.release(userId, entry);
        }
    }
}
