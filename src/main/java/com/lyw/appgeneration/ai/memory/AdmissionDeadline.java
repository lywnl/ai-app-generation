package com.lyw.appgeneration.ai.memory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** 同时携带进程内单调剩余时间与 Redis 服务端 wall-clock 截止。 */
public final class AdmissionDeadline {

    @FunctionalInterface
    interface WaitStrategy {
        void awaitNanos(long nanos) throws InterruptedException;
    }

    private final long startedAtNanos;
    private final long timeoutNanos;
    private final long serverDeadlineEpochMillis;
    private final LongSupplier nanoTime;
    private final WaitStrategy waitStrategy;

    private AdmissionDeadline(
            long startedAtNanos,
            long timeoutNanos,
            long serverDeadlineEpochMillis,
            LongSupplier nanoTime,
            WaitStrategy waitStrategy) {
        this.startedAtNanos = startedAtNanos;
        this.timeoutNanos = timeoutNanos;
        this.serverDeadlineEpochMillis = serverDeadlineEpochMillis;
        this.nanoTime = nanoTime;
        this.waitStrategy = waitStrategy;
    }

    public static AdmissionDeadline start(Duration timeout) {
        return start(timeout, System::nanoTime, System::currentTimeMillis,
                nanos -> TimeUnit.NANOSECONDS.sleep(nanos));
    }

    static AdmissionDeadline start(
            Duration timeout,
            LongSupplier nanoTime,
            LongSupplier currentTimeMillis,
            WaitStrategy waitStrategy) {
        Objects.requireNonNull(timeout, "截止时长不能为空");
        LongSupplier monotonic = Objects.requireNonNull(
                nanoTime, "单调时钟不能为空");
        long timeoutNanos = saturatedNanos(timeout);
        long timeoutMillis = saturatedMillis(timeout);
        return new AdmissionDeadline(
                monotonic.getAsLong(), timeoutNanos,
                saturatedAdd(currentTimeMillis.getAsLong(), timeoutMillis),
                monotonic,
                Objects.requireNonNull(waitStrategy, "截止等待策略不能为空"));
    }

    public long remainingNanos() {
        long current = nanoTime.getAsLong();
        // nanoTime 只比较差值；有符号减法在 long 回绕时仍保持短区间经过时间。
        long elapsed = current - startedAtNanos;
        if (elapsed < 0L) {
            elapsed = 0L;
        }
        if (elapsed >= timeoutNanos) {
            return 0L;
        }
        return timeoutNanos - elapsed;
    }

    public Duration remainingDuration() {
        return Duration.ofNanos(remainingNanos());
    }

    public boolean canStart(Duration worstCaseOperation) {
        Objects.requireNonNull(worstCaseOperation, "最坏操作耗时不能为空");
        return remainingNanos() > saturatedNanos(worstCaseOperation);
    }

    public long lockWaitNanos(Duration worstCaseCommit) {
        long remaining = remainingNanos();
        long commitNanos = saturatedNanos(Objects.requireNonNull(
                worstCaseCommit, "最坏提交耗时不能为空"));
        return remaining > commitNanos ? remaining - commitNanos : 0L;
    }

    public long serverDeadlineEpochMillis() {
        return serverDeadlineEpochMillis;
    }

    /** 客户端结果不确定时同步等到绝对截止；不创建后台任务。 */
    public boolean awaitExpirationPreservingInterrupt() {
        boolean interrupted = false;
        long remaining;
        while ((remaining = remainingNanos()) > 0L) {
            try {
                waitStrategy.awaitNanos(remaining);
            } catch (InterruptedException exception) {
                interrupted = true;
                Thread.interrupted();
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return interrupted;
    }

    private static long saturatedNanos(Duration duration) {
        if (duration.isNegative() || duration.isZero()) {
            return 0L;
        }
        try {
            return duration.toNanos();
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static long saturatedMillis(Duration duration) {
        if (duration.isNegative() || duration.isZero()) {
            return 0L;
        }
        try {
            return duration.toMillis();
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
