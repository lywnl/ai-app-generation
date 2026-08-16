package com.lyw.appgeneration.service.impl;

import java.util.concurrent.locks.ReentrantLock;

/** 为 app 级摘要 cache-aside 读写提供固定条带一致性边界。 */
final class AppMemorySummaryConsistencyCoordinator {

    private static final int STRIPE_COUNT = 64;

    private final ReentrantLock[] stripes = new ReentrantLock[STRIPE_COUNT];

    AppMemorySummaryConsistencyCoordinator() {
        for (int index = 0; index < stripes.length; index++) {
            stripes[index] = new ReentrantLock();
        }
    }

    Permit acquire(long appId) {
        ReentrantLock lock = stripes[
                Math.floorMod(Long.hashCode(appId), stripes.length)];
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
