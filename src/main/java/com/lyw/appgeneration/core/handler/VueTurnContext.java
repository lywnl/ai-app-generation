package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.core.builder.VueBuildPhase;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager.VueBuildLease;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager.VueBuildSnapshot;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager.AppOperationLease;
import dev.langchain4j.service.ToolLoopTerminationProtocol.ControlledTermination;

import java.util.Objects;
import java.util.Optional;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** 精确绑定一次 app 操作租约和 Vue 构建租约的在线回合上下文。 */
public final class VueTurnContext {

    public static final Duration TURN_DEADLINE = Duration.ofMinutes(30);

    private final long appId;
    private final long userId;
    private final String turnId;
    private final AppOperationLease operationLease;
    private final VueBuildLease lease;
    private final VueBuildPhase testingPhase;
    private final boolean testingTimedOut;
    private final long startedAtNanos;
    private final long deadlineNanos;
    private final LongSupplier nanoTicker;
    private final AtomicReference<ControlledTermination> controlledTermination =
            new AtomicReference<>();
    private final AtomicReference<VueTurnFinalizer.FinalizationResult> finalization =
            new AtomicReference<>();
    private final AtomicBoolean finalizing = new AtomicBoolean();
    private final AtomicReference<TerminalTrigger> terminalWinner =
            new AtomicReference<>();
    private final AtomicBoolean resourcesClosed = new AtomicBoolean();
    private final AtomicBoolean userCommitted = new AtomicBoolean();
    private final CallbackGate callbackGate = new CallbackGate();

    public VueTurnContext(long appId, long userId, String turnId,
            AppOperationLease operationLease, VueBuildLease lease) {
        this(appId, userId, turnId, operationLease, lease, null, false,
                operationLease.startedAtNanos(), TURN_DEADLINE,
                System::nanoTime);
        VueBuildSnapshot snapshot = lease.snapshot();
        if (operationLease.appId() != appId || snapshot.appId() != appId
                || snapshot.userId() != userId
                || !operationLease.ownerToken().equals(turnId)
                || !snapshot.turnId().equals(turnId)) {
            throw new IllegalArgumentException("回合上下文身份与精确租约不匹配");
        }
    }

    private VueTurnContext(long appId, long userId, String turnId,
            AppOperationLease operationLease, VueBuildLease lease,
            VueBuildPhase testingPhase, boolean testingTimedOut,
            long startedAtNanos, Duration deadlineDuration,
            LongSupplier nanoTicker) {
        if (appId <= 0 || userId <= 0) {
            throw new IllegalArgumentException("appId 和 userId 必须大于 0");
        }
        this.appId = appId;
        this.userId = userId;
        this.turnId = Objects.requireNonNull(turnId, "turnId 不能为空");
        if (turnId.isBlank()) {
            throw new IllegalArgumentException("turnId 不能为空白");
        }
        this.operationLease = operationLease;
        this.lease = lease;
        this.testingPhase = testingPhase;
        this.testingTimedOut = testingTimedOut;
        this.startedAtNanos = startedAtNanos;
        this.nanoTicker = Objects.requireNonNull(nanoTicker, "nanoTicker 不能为空");
        Objects.requireNonNull(deadlineDuration, "deadlineDuration 不能为空");
        if (deadlineDuration.isZero() || deadlineDuration.isNegative()) {
            throw new IllegalArgumentException("回合截止时间必须大于 0");
        }
        this.deadlineNanos = saturatingAdd(
                startedAtNanos, deadlineDuration.toNanos());
    }

    static VueTurnContext testing(
            long appId, long userId, String turnId, VueBuildPhase phase) {
        return new VueTurnContext(appId, userId, turnId, null, null,
                Objects.requireNonNull(phase), false,
                System.nanoTime(), TURN_DEADLINE, System::nanoTime);
    }

    static VueTurnContext testing(
            long appId, long userId, String turnId,
            VueBuildPhase phase, boolean timedOut) {
        return new VueTurnContext(appId, userId, turnId, null, null,
                Objects.requireNonNull(phase), timedOut,
                System.nanoTime(), TURN_DEADLINE, System::nanoTime);
    }

    static VueTurnContext testing(
            long appId, long userId, String turnId, VueBuildPhase phase,
            Duration deadlineDuration, LongSupplier nanoTicker) {
        LongSupplier ticker = Objects.requireNonNull(nanoTicker);
        long startedAt = ticker.getAsLong();
        return new VueTurnContext(appId, userId, turnId, null, null,
                Objects.requireNonNull(phase), false,
                startedAt, deadlineDuration, ticker);
    }

    public long appId() {
        return appId;
    }

    public long userId() {
        return userId;
    }

    public String turnId() {
        return turnId;
    }

    public long startedAtNanos() {
        return startedAtNanos;
    }

    public long deadlineNanos() {
        return deadlineNanos;
    }

    public Duration remainingUntilDeadline() {
        return Duration.ofNanos(Math.max(
                0L, deadlineNanos - nanoTicker.getAsLong()));
    }

    public void markUserCommitted() {
        userCommitted.set(true);
    }

    public boolean isUserCommitted() {
        return userCommitted.get();
    }

    public VueBuildLease lease() {
        if (lease == null) {
            throw new IllegalStateException("测试上下文没有真实 Vue 租约");
        }
        return lease;
    }

    public VueBuildPhase phase() {
        return lease == null ? testingPhase : lease.snapshot().phase();
    }

    public boolean timedOut() {
        return lease == null ? testingTimedOut : lease.snapshot().timedOut();
    }

    public boolean recordControlledTermination(ControlledTermination termination) {
        return controlledTermination.compareAndSet(
                null, Objects.requireNonNull(termination, "受控终止不能为空"));
    }

    public Optional<ControlledTermination> controlledTermination() {
        return Optional.ofNullable(controlledTermination.get());
    }

    /** complete、error 与 cancel 竞争时，只允许一个分支决定规范终态。 */
    public boolean tryClaimTerminal(TerminalTrigger trigger) {
        return terminalWinner.compareAndSet(
                null, Objects.requireNonNull(trigger, "终态触发原因不能为空"));
    }

    /**
     * 原子认领取消类终态并关闭应用外层回调门，再撤销回合内层门和生成动作。
     */
    public boolean tryClaimTerminalAndCancel(TerminalTrigger trigger) {
        Objects.requireNonNull(trigger, "终态触发原因不能为空");
        boolean claimed = operationLease == null
                ? terminalWinner.compareAndSet(null, trigger)
                : operationLease.requestCancellationIf(
                        () -> terminalWinner.compareAndSet(null, trigger));
        if (!claimed) {
            return false;
        }
        callbackGate.revoke();
        if (lease != null) {
            lease.cancel();
        }
        return true;
    }

    public Optional<TerminalTrigger> terminalWinner() {
        return Optional.ofNullable(terminalWinner.get());
    }

    public void ensureTerminalOpen() {
        TerminalTrigger winner = terminalWinner.get();
        if (winner != null) {
            throw new IllegalStateException("Vue 回合终态已由 " + winner + " 占用");
        }
    }

    /**
     * 领取“回合内层 + app 外层”两张回调票据。取消关门后，晚到回调会被静默拒绝。
     */
    public boolean tryRunCallback(Runnable action) {
        Objects.requireNonNull(action, "回调不能为空");
        return tryCallCallback(() -> {
            action.run();
            return Boolean.TRUE;
        }).isPresent();
    }

    /** 在回调双门内执行准备或模型动作；关门后拒绝迟到动作。 */
    public <T> Optional<T> tryCallCallback(Supplier<T> action) {
        Objects.requireNonNull(action, "回调不能为空");
        CallbackGate.Ticket inner = callbackGate.tryEnter();
        if (inner == null) {
            return Optional.empty();
        }
        AutoCloseable outer = null;
        try (inner) {
            if (lease != null) {
                try {
                    outer = lease.enterCallback();
                } catch (IllegalStateException rejected) {
                    return Optional.empty();
                }
            }
            try {
                return Optional.ofNullable(action.get());
            } finally {
                if (outer != null) {
                    outer.close();
                }
            }
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("关闭 Vue 回合回调票据失败", exception);
        }
    }

    public void registerModelCancellation(Runnable cancellationAction) {
        lease().registerModelCancellation(cancellationAction);
    }

    /** 先关闭内层回调门，再触发 Vue/app 租约取消及模型、构建取消动作。 */
    public void cancelGeneration() {
        callbackGate.revoke();
        if (lease != null) {
            lease.cancel();
        }
    }

    public void revokeCallbacks() {
        callbackGate.revoke();
    }

    /** 依次等待回合内层与 app/Vue 外层回调静默，共享同一超时预算。 */
    public boolean awaitQuiescence(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout 不能为空");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 不能为负数");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        if (!callbackGate.awaitQuiescence(timeout)) {
            return false;
        }
        if (lease == null) {
            return true;
        }
        long remaining = Math.max(0L, deadline - System.nanoTime());
        try {
            return lease.awaitQuiescence(Duration.ofNanos(remaining));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    boolean tryStartFinalization() {
        return finalizing.compareAndSet(false, true);
    }

    void completeFinalization(VueTurnFinalizer.FinalizationResult result) {
        if (!finalization.compareAndSet(null, Objects.requireNonNull(result))) {
            throw new IllegalStateException("回合终态已经完成");
        }
        synchronized (finalization) {
            finalization.notifyAll();
        }
    }

    VueTurnFinalizer.FinalizationResult awaitFinalization() {
        boolean interrupted = false;
        synchronized (finalization) {
            while (finalization.get() == null) {
                try {
                    finalization.wait();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return finalization.get();
    }

    public void closeResources() {
        if (!resourcesClosed.compareAndSet(false, true)) {
            return;
        }
        RuntimeException failure = null;
        if (lease != null) {
            try {
                lease.close();
            } catch (RuntimeException exception) {
                failure = exception;
            }
        }
        if (operationLease != null) {
            try {
                operationLease.close();
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static long saturatingAdd(long value, long increment) {
        return increment > 0 && value > Long.MAX_VALUE - increment
                ? Long.MAX_VALUE : value + increment;
    }

    private static final class CallbackGate {

        private boolean active = true;
        private int inFlight;

        private synchronized Ticket tryEnter() {
            if (!active) {
                return null;
            }
            inFlight++;
            return new Ticket(this);
        }

        private synchronized void revoke() {
            active = false;
        }

        private synchronized void leave() {
            inFlight--;
            if (inFlight == 0) {
                notifyAll();
            }
        }

        private synchronized boolean awaitQuiescence(Duration timeout) {
            long remaining = timeout.toNanos();
            long deadline = System.nanoTime() + remaining;
            while (inFlight > 0) {
                if (remaining <= 0) {
                    return false;
                }
                try {
                    wait(Math.max(1L, remaining / 1_000_000L));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                remaining = deadline - System.nanoTime();
            }
            return true;
        }

        private static final class Ticket implements AutoCloseable {

            private final CallbackGate gate;
            private boolean closed;

            private Ticket(CallbackGate gate) {
                this.gate = gate;
            }

            @Override
            public void close() {
                if (!closed) {
                    closed = true;
                    gate.leave();
                }
            }
        }
    }

    public enum TerminalTrigger {
        COMPLETED,
        FAILED,
        CANCELLED,
        TIMED_OUT
    }
}
