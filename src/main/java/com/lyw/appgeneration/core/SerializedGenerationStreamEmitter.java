package com.lyw.appgeneration.core;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.function.Consumer;

/** 把模型业务信号与生命周期终态串行写入同一个响应流。 */
final class SerializedGenerationStreamEmitter {

    interface Target {

        boolean isCancelled();

        void next(String value);

        void complete();

        void error(Throwable error);
    }

    private enum State {
        OPEN,
        TERMINAL_CLAIMED,
        TERMINATED,
        CANCELLED
    }

    private sealed interface Emission {

        record Action(Consumer<Target> action) implements Emission {
        }

        record Next(String value) implements Emission {
        }

        record Complete(Consumer<Target> beforeComplete) implements Emission {
        }

        record Error(Consumer<Target> beforeError, Throwable error)
                implements Emission {
        }
    }

    private final Target target;
    private final ArrayDeque<Emission> pending = new ArrayDeque<>();
    private State state = State.OPEN;
    private boolean draining;

    SerializedGenerationStreamEmitter(Target target) {
        this.target = Objects.requireNonNull(target, "响应流写入目标不能为空");
    }

    boolean next(String value) {
        Objects.requireNonNull(value, "响应流正文不能为空");
        return submit(new Emission.Next(value), false);
    }

    boolean execute(Consumer<Target> action) {
        return submit(new Emission.Action(
                Objects.requireNonNull(action, "串行任务不能为空")), false);
    }

    boolean complete() {
        return complete(ignored -> { });
    }

    boolean complete(Consumer<Target> beforeComplete) {
        return submit(new Emission.Complete(Objects.requireNonNull(
                beforeComplete, "完成前任务不能为空")), true);
    }

    boolean error(Throwable error) {
        return error(ignored -> { }, error);
    }

    boolean error(Consumer<Target> beforeError, Throwable error) {
        return submit(new Emission.Error(
                Objects.requireNonNull(beforeError, "错误前任务不能为空"),
                Objects.requireNonNull(error, "响应流错误不能为空")), true);
    }

    void cancel() {
        synchronized (this) {
            if (state == State.TERMINATED || state == State.CANCELLED) {
                return;
            }
            state = State.CANCELLED;
            pending.clear();
        }
    }

    private boolean submit(Emission emission, boolean terminal) {
        boolean startDrain;
        synchronized (this) {
            if (state != State.OPEN) {
                return false;
            }
            if (terminal) {
                state = State.TERMINAL_CLAIMED;
            }
            pending.addLast(emission);
            startDrain = !draining;
            if (startDrain) {
                draining = true;
            }
        }
        if (startDrain) {
            drain();
        }
        return true;
    }

    private void drain() {
        RuntimeException deferredFailure = null;
        while (true) {
            Emission emission;
            synchronized (this) {
                if (state == State.CANCELLED || target.isCancelled()) {
                    state = State.CANCELLED;
                    pending.clear();
                    draining = false;
                    return;
                }
                emission = pending.pollFirst();
                if (emission == null) {
                    draining = false;
                    return;
                }
            }
            try {
                switch (emission) {
                    case Emission.Action action ->
                            action.action().accept(target);
                    case Emission.Next next -> target.next(next.value());
                    case Emission.Complete complete -> {
                        try {
                            complete.beforeComplete().accept(target);
                            target.complete();
                        } catch (RuntimeException exception) {
                            terminateWithError(exception);
                            if (deferredFailure != null) {
                                deferredFailure.addSuppressed(exception);
                                throw deferredFailure;
                            }
                            throw exception;
                        }
                        markTerminated();
                        if (deferredFailure != null) {
                            throw deferredFailure;
                        }
                        return;
                    }
                    case Emission.Error error -> {
                        try {
                            error.beforeError().accept(target);
                            target.error(error.error());
                        } catch (RuntimeException exception) {
                            terminateWithError(exception);
                            if (deferredFailure != null) {
                                deferredFailure.addSuppressed(exception);
                                throw deferredFailure;
                            }
                            throw exception;
                        }
                        markTerminated();
                        if (deferredFailure != null) {
                            throw deferredFailure;
                        }
                        return;
                    }
                }
            } catch (RuntimeException exception) {
                if (emission instanceof Emission.Complete
                        || emission instanceof Emission.Error) {
                    throw exception;
                }
                if (preserveClaimedTerminal()) {
                    deferredFailure = mergeFailure(
                            deferredFailure, exception);
                    continue;
                }
                releaseAfterActionFailure();
                throw exception;
            }
        }
    }

    private boolean preserveClaimedTerminal() {
        synchronized (this) {
            if (state != State.TERMINAL_CLAIMED) {
                return false;
            }
            Emission terminal = pending.stream()
                    .filter(emission -> emission instanceof Emission.Complete
                            || emission instanceof Emission.Error)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "终态已认领但队列中不存在终态事件"));
            pending.clear();
            pending.addLast(terminal);
            return true;
        }
    }

    private RuntimeException mergeFailure(
            RuntimeException current, RuntimeException next) {
        if (current == null) {
            return next;
        }
        current.addSuppressed(next);
        return current;
    }

    private void terminateWithError(RuntimeException failure) {
        try {
            target.error(failure);
        } catch (RuntimeException targetFailure) {
            failure.addSuppressed(targetFailure);
        } finally {
            markTerminated();
        }
    }

    private void markTerminated() {
        synchronized (this) {
            state = State.TERMINATED;
            pending.clear();
            draining = false;
        }
    }

    private void releaseAfterActionFailure() {
        synchronized (this) {
            if (state != State.CANCELLED && state != State.TERMINATED) {
                state = State.OPEN;
            }
            pending.clear();
            draining = false;
        }
    }
}
