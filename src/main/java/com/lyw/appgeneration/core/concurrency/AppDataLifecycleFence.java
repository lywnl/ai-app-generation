package com.lyw.appgeneration.core.concurrency;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/** 协调应用数据写入与删除关门的进程内栅栏。 */
@Component
public final class AppDataLifecycleFence {

    private final ConcurrentHashMap<Long, AppState> states = new ConcurrentHashMap<>();

    public WriterPermit tryAcquireWriter(long appId) {
        AppState state = stateOf(appId);
        if (state.phase.get() != Phase.OPEN) {
            return null;
        }
        int writers = state.activeWriters.incrementAndGet();
        if (writers <= 0) {
            state.activeWriters.decrementAndGet();
            throw new IllegalStateException("应用数据 writer 计数溢出");
        }
        if (state.phase.get() == Phase.OPEN) {
            return new DefaultWriterPermit(state);
        }
        releaseWriter(state);
        return null;
    }

    /** 返回调用瞬间是否仍允许新的应用数据写入，不占用 writer 许可。 */
    public boolean isOpen(long appId) {
        return stateOf(appId).phase.get() == Phase.OPEN;
    }

    public DeletePermit beginDelete(long appId, Duration timeout) {
        Objects.requireNonNull(timeout, "删除等待时间不能为空");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("删除等待时间不能为负数");
        }
        AppState state = stateOf(appId);
        if (!state.phase.compareAndSet(Phase.OPEN, Phase.DELETING)) {
            return null;
        }
        long timeoutNanos = toSaturatedNanos(timeout);
        long startedAt = System.nanoTime();
        Thread waiter = Thread.currentThread();
        state.deleteWaiter.set(waiter);
        try {
            while (state.activeWriters.get() > 0) {
                long remainingNanos = remainingNanos(
                        startedAt, timeoutNanos);
                if (remainingNanos <= 0L) {
                    reopen(state);
                    return null;
                }
                LockSupport.parkNanos(state, remainingNanos);
                if (Thread.interrupted()) {
                    reopen(state);
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("等待应用数据写入结束时被中断");
                }
            }
            return new DefaultDeletePermit(state);
        } finally {
            state.deleteWaiter.compareAndSet(waiter, null);
        }
    }

    private AppState stateOf(long appId) {
        if (appId <= 0) {
            throw new IllegalArgumentException("应用 ID 必须为正数");
        }
        return states.computeIfAbsent(appId, ignored -> new AppState());
    }

    private long toSaturatedNanos(Duration timeout) {
        try {
            return timeout.toNanos();
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private void reopen(AppState state) {
        if (!state.phase.compareAndSet(Phase.DELETING, Phase.OPEN)) {
            throw new IllegalStateException("删除失败后无法重新开放应用数据栅栏");
        }
    }

    private long remainingNanos(long startedAt, long timeoutNanos) {
        long elapsed = System.nanoTime() - startedAt;
        return elapsed >= timeoutNanos ? 0L : timeoutNanos - elapsed;
    }

    private static void releaseWriter(AppState state) {
        int remaining = state.activeWriters.decrementAndGet();
        if (remaining < 0) {
            throw new IllegalStateException("应用数据 writer 计数不能为负数");
        }
        if (remaining == 0) {
            LockSupport.unpark(state.deleteWaiter.get());
        }
    }

    public interface WriterPermit extends AutoCloseable {

        @Override
        void close();
    }

    public interface DeletePermit extends AutoCloseable {

        void commitTombstone();

        void abortAndReopen();

        @Override
        void close();
    }

    private enum Phase {
        OPEN,
        DELETING,
        TOMBSTONED
    }

    private static final class AppState {

        private final AtomicReference<Phase> phase =
                new AtomicReference<>(Phase.OPEN);
        private final AtomicInteger activeWriters = new AtomicInteger();
        private final AtomicReference<Thread> deleteWaiter =
                new AtomicReference<>();
    }

    private static final class DefaultWriterPermit implements WriterPermit {

        private final AppState state;
        private final AtomicBoolean closed = new AtomicBoolean();

        private DefaultWriterPermit(AppState state) {
            this.state = state;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            releaseWriter(state);
        }
    }

    private static final class DefaultDeletePermit implements DeletePermit {

        private final AppState state;
        private final AtomicBoolean completed = new AtomicBoolean();

        private DefaultDeletePermit(AppState state) {
            this.state = state;
        }

        @Override
        public void commitTombstone() {
            complete(Phase.TOMBSTONED);
        }

        @Override
        public void abortAndReopen() {
            complete(Phase.OPEN);
        }

        @Override
        public void close() {
            abortAndReopen();
        }

        private void complete(Phase targetPhase) {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            if (!state.phase.compareAndSet(Phase.DELETING, targetPhase)) {
                throw new IllegalStateException("删除许可对应的应用未处于删除阶段");
            }
        }
    }
}
