package com.lyw.appgeneration.service.impl;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 为用户级共享记忆缓存提供进程内一致性边界。
 *
 * <p>固定条带避免按 userId 持有无界锁对象；不同用户发生条带碰撞时只会降低并发度，
 * 不会改变一致性语义。
 */
final class UserMemoryConsistencyCoordinator {

    private static final int STRIPE_COUNT = 64;

    private final ReentrantLock[] stripes = new ReentrantLock[STRIPE_COUNT];

    UserMemoryConsistencyCoordinator() {
        for (int index = 0; index < stripes.length; index++) {
            stripes[index] = new ReentrantLock();
        }
    }

    Permit acquire(long userId) {
        ReentrantLock lock = stripes[
                Math.floorMod(Long.hashCode(userId), stripes.length)];
        lock.lock();
        return new Permit(lock);
    }

    static final class Permit implements AutoCloseable {

        private final ReentrantLock lock;
        private boolean closed;

        private Permit(ReentrantLock lock) {
            this.lock = lock;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            lock.unlock();
        }
    }
}
