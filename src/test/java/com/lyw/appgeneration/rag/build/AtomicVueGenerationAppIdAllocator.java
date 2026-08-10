package com.lyw.appgeneration.rag.build;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内共享的递增 appId 分配器。
 */
final class AtomicVueGenerationAppIdAllocator implements VueGenerationAppIdAllocator {

    private static final AtomicLong SHARED_SEQUENCE =
            new AtomicLong(Math.max(0L, System.currentTimeMillis()));

    private final AtomicLong sequence;

    AtomicVueGenerationAppIdAllocator() {
        this.sequence = SHARED_SEQUENCE;
    }

    AtomicVueGenerationAppIdAllocator(long lastAllocatedAppId) {
        if (lastAllocatedAppId < 0) {
            throw new IllegalArgumentException("lastAllocatedAppId 不能是负数");
        }
        this.sequence = new AtomicLong(lastAllocatedAppId);
    }

    @Override
    public long nextAppId() {
        while (true) {
            long current = sequence.get();
            if (current == Long.MAX_VALUE) {
                throw new IllegalStateException("运行 appId 已耗尽");
            }
            long next = current + 1;
            if (sequence.compareAndSet(current, next)) {
                return next;
            }
        }
    }
}
