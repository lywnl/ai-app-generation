package com.lyw.appgeneration.core.builder;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 单次构建作用域的线程安全取消信号。 */
public final class BuildCancellationSignal {

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Map<Thread, Integer> registrationCounts = new HashMap<>();

    /** 注册当前命令线程；作用域关闭后必须注销，避免误中断复用线程。 */
    public Registration registerCurrentThread() {
        Thread thread = Thread.currentThread();
        synchronized (registrationCounts) {
            if (cancelled.get()) {
                thread.interrupt();
            } else {
                registrationCounts.merge(thread, 1, Integer::sum);
            }
        }
        return new Registration(this, thread);
    }

    /** 首次取消时中断所有仍在执行命令的登记线程。 */
    public boolean cancel() {
        synchronized (registrationCounts) {
            if (!cancelled.compareAndSet(false, true)) {
                return false;
            }
            registrationCounts.keySet().forEach(Thread::interrupt);
            registrationCounts.clear();
            return true;
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    private void unregister(Thread thread) {
        synchronized (registrationCounts) {
            registrationCounts.computeIfPresent(
                    thread, (ignored, count) -> count == 1 ? null : count - 1);
        }
    }

    /** 当前线程登记的幂等关闭句柄。 */
    public static final class Registration implements AutoCloseable {

        private final BuildCancellationSignal signal;
        private final Thread thread;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Registration(BuildCancellationSignal signal, Thread thread) {
            this.signal = Objects.requireNonNull(signal);
            this.thread = Objects.requireNonNull(thread);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                signal.unregister(thread);
            }
        }
    }
}
