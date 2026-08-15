package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.ai.memory.ContextContinuationGate;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager.AppOperationLease;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager.CallbackRegistration;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager.CancellationRegistration;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager.DeleteTakeoverRegistration;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** 持有一次 HTML 或多文件生成所需的精确租约与取消边界。 */
public final class SimpleGenerationTurnContext
        implements AutoCloseable, ContextContinuationGate {

    private final AppOperationLease operationLease;
    private final CallbackRegistration callbackRegistration;
    private final CancellationRegistration cancellationRegistration;
    private final DeleteTakeoverRegistration deleteTakeoverRegistration;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<UpstreamCancellation> upstreamCancellation =
            new AtomicReference<>();
    private final Sinks.Empty<Void> cancellationSignal = Sinks.empty();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CallbackGate callbackGate = new CallbackGate();

    public SimpleGenerationTurnContext(AppOperationLease operationLease) {
        this.operationLease = Objects.requireNonNull(
                operationLease, "普通回合操作租约不能为空");
        CallbackRegistration callback = operationLease.enterCallback();
        CancellationRegistration cancellation = null;
        try {
            cancellation = operationLease.registerCancellation(
                    this::cancelFromOperation);
            deleteTakeoverRegistration = operationLease
                    .registerDeleteTakeoverParticipant(context -> {
                        if (!context.awaitQuiescence()) {
                            throw new IllegalStateException(
                                    "普通生成回合未在删除截止时间内静默");
                        }
                    });
            cancellationRegistration = cancellation;
            callbackRegistration = callback;
        } catch (RuntimeException exception) {
            if (cancellation != null) {
                cancellation.close();
            }
            callback.close();
            operationLease.close();
            throw exception;
        }
    }

    public long appId() {
        return operationLease.appId();
    }

    public boolean isCancelled() {
        return cancelled.get() || operationLease.isCancellationRequested();
    }

    /** 在普通回合原子门内执行快速提交或模型启动动作。 */
    @Override
    public boolean tryRun(Runnable action) {
        Objects.requireNonNull(action, "普通回合继续动作不能为空");
        CallbackGate.Ticket ticket = callbackGate.tryEnter();
        if (ticket == null) {
            return false;
        }
        try (ticket) {
            CallbackRegistration outer;
            try {
                outer = operationLease.enterCallback();
            } catch (IllegalStateException rejected) {
                return false;
            }
            try (outer) {
                action.run();
                return true;
            }
        }
    }

    /** DELETE 触发时完成该信号，使外层 Reactor 回合可靠进入终态清理。 */
    public Mono<Void> cancellationSignal() {
        return cancellationSignal.asMono();
    }

    /** 绑定真实模型订阅；删除已先关门时立即取消这个晚到上游。 */
    public void bindUpstream(Runnable cancellationAction) {
        Objects.requireNonNull(cancellationAction, "上游取消动作不能为空");
        UpstreamCancellation bound = new UpstreamCancellation(cancellationAction);
        if (!upstreamCancellation.compareAndSet(null, bound)) {
            throw new IllegalStateException("普通回合已经绑定模型上游");
        }
        if (isCancelled()) {
            bound.cancel();
        }
    }

    boolean awaitCancellation(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "等待时间不能为空");
        long remainingNanos = timeout.toNanos();
        long deadline = System.nanoTime() + remainingNanos;
        synchronized (cancelled) {
            while (!cancelled.get()) {
                if (remainingNanos <= 0L) {
                    return false;
                }
                long millis = Math.max(1L, remainingNanos / 1_000_000L);
                cancelled.wait(millis);
                remainingNanos = deadline - System.nanoTime();
            }
            return true;
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        callbackGate.revoke();
        RuntimeException failure = closeDeleteTakeoverRegistration();
        try {
            cancellationRegistration.close();
        } catch (RuntimeException exception) {
            failure = appendFailure(failure, exception);
        }
        try {
            callbackRegistration.close();
        } catch (RuntimeException exception) {
            failure = appendFailure(failure, exception);
        }
        try {
            operationLease.close();
        } catch (RuntimeException exception) {
            failure = appendFailure(failure, exception);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void cancelFromOperation() {
        callbackGate.revoke();
        cancelled.set(true);
        synchronized (cancelled) {
            cancelled.notifyAll();
        }
        try {
            UpstreamCancellation cancellation = upstreamCancellation.get();
            if (cancellation != null) {
                cancellation.cancel();
            }
        } finally {
            cancellationSignal.tryEmitEmpty();
        }
    }

    private RuntimeException closeDeleteTakeoverRegistration() {
        try {
            deleteTakeoverRegistration.close();
            return null;
        } catch (RuntimeException exception) {
            return exception;
        }
    }

    private RuntimeException appendFailure(
            RuntimeException current, RuntimeException next) {
        if (current == null) {
            return next;
        }
        current.addSuppressed(next);
        return current;
    }

    private static final class UpstreamCancellation {

        private final Runnable action;
        private final AtomicBoolean invoked = new AtomicBoolean();

        private UpstreamCancellation(Runnable action) {
            this.action = action;
        }

        private void cancel() {
            if (invoked.compareAndSet(false, true)) {
                action.run();
            }
        }
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

        private static final class Ticket implements AutoCloseable {

            private final CallbackGate gate;
            private final AtomicBoolean closed = new AtomicBoolean();

            private Ticket(CallbackGate gate) {
                this.gate = gate;
            }

            @Override
            public void close() {
                if (closed.compareAndSet(false, true)) {
                    gate.leave();
                }
            }
        }
    }
}
