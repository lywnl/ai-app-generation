package com.lyw.appgeneration.core.concurrency;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** 协调应用数据写入与删除关门的进程内栅栏。 */
@Component
public final class AppDataLifecycleFence {

    private final ConcurrentHashMap<Long, AppState> states = new ConcurrentHashMap<>();

    public WriterPermit tryAcquireWriter(long appId) {
        AppState state = stateOf(appId);
        state.lock.lock();
        try {
            if (state.phase != Phase.OPEN) {
                return null;
            }
            state.activeWriters++;
            return new DefaultWriterPermit(state);
        } finally {
            state.lock.unlock();
        }
    }

    public DeletePermit beginDelete(long appId, Duration timeout) {
        Objects.requireNonNull(timeout, "删除等待时间不能为空");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("删除等待时间不能为负数");
        }
        AppState state = stateOf(appId);
        state.lock.lock();
        try {
            if (state.phase != Phase.OPEN) {
                return null;
            }
            state.phase = Phase.DELETING;
            long remainingNanos = toSaturatedNanos(timeout);
            while (state.activeWriters > 0) {
                if (remainingNanos <= 0) {
                    reopen(state);
                    return null;
                }
                try {
                    remainingNanos = state.writersDrained.awaitNanos(remainingNanos);
                } catch (InterruptedException exception) {
                    reopen(state);
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("等待应用数据写入结束时被中断", exception);
                }
            }
            return new DefaultDeletePermit(state);
        } finally {
            state.lock.unlock();
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
        state.phase = Phase.OPEN;
        state.writersDrained.signalAll();
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

        private final ReentrantLock lock = new ReentrantLock(true);
        private final Condition writersDrained = lock.newCondition();
        private Phase phase = Phase.OPEN;
        private int activeWriters;
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
            state.lock.lock();
            try {
                state.activeWriters--;
                if (state.activeWriters == 0) {
                    state.writersDrained.signalAll();
                }
            } finally {
                state.lock.unlock();
            }
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
            state.lock.lock();
            try {
                if (state.phase != Phase.DELETING) {
                    throw new IllegalStateException("删除许可对应的应用未处于删除阶段");
                }
                state.phase = targetPhase;
                state.writersDrained.signalAll();
            } finally {
                state.lock.unlock();
            }
        }
    }
}
