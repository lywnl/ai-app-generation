package dev.langchain4j.service;

import dev.langchain4j.model.chat.response.StreamingRequestHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static dev.langchain4j.service.ToolLoopTerminationProtocol.ControlledTermination;
import static dev.langchain4j.service.ToolLoopTerminationProtocol.ControlledTerminationReason.CANCELLED;
import static dev.langchain4j.service.ToolLoopTerminationProtocol.ControlledTerminationReason.LOOP_LIMIT_EXCEEDED;

/** 跨递归模型请求共享的通用取消门、计数器和受控终止状态。 */
public final class StreamingRequestController {

    private static final Logger LOG = LoggerFactory.getLogger(StreamingRequestController.class);

    public static final int MAX_MODEL_REQUESTS = 64;
    public static final int MAX_TOOL_EXECUTIONS = 64;

    private State state = State.ACTIVE;
    private int modelRequestCount;
    private int toolExecutionCount;
    private int activeCallbacks;
    private HandleSlot latestHandle;
    private long latestModelRequestGeneration;
    private long latestHandleGeneration = -1;
    private ControlledTermination termination;
    private Consumer<ControlledTermination> terminationHandler;
    private boolean terminationDelivered;

    public boolean beforeModelRequest() {
        return beforeOperation(true);
    }

    public boolean beforeToolExecution() {
        return beforeOperation(false);
    }

    private boolean beforeOperation(boolean modelRequest) {
        ControlledTermination limit = null;
        synchronized (this) {
            if (state != State.ACTIVE) {
                return false;
            }
            int count = modelRequest ? modelRequestCount : toolExecutionCount;
            int maximum = modelRequest ? MAX_MODEL_REQUESTS : MAX_TOOL_EXECUTIONS;
            if (count >= maximum) {
                limit = new ControlledTermination(LOOP_LIMIT_EXCEEDED, null);
                state = State.CONTROLLED_TERMINATION;
                termination = limit;
            } else {
                if (modelRequest) {
                    modelRequestCount++;
                    latestModelRequestGeneration++;
                } else {
                    toolExecutionCount++;
                }
                return true;
            }
        }
        return false;
    }

    public void registerRequestHandle(StreamingRequestHandle handle) {
        registerRequestHandle(latestModelRequestGeneration(), handle);
    }

    public void registerRequestHandle(
            long requestGeneration, StreamingRequestHandle handle) {
        Objects.requireNonNull(handle, "请求句柄不能为空");
        HandleSlot slot = new HandleSlot(handle);
        boolean cancelImmediately;
        synchronized (this) {
            cancelImmediately = state != State.ACTIVE
                    || requestGeneration < latestModelRequestGeneration;
            if (!cancelImmediately && state == State.ACTIVE
                    && requestGeneration >= latestHandleGeneration) {
                latestHandle = slot;
                latestHandleGeneration = requestGeneration;
            }
        }
        if (cancelImmediately) {
            slot.cancel();
        }
    }

    public void cancel() {
        HandleSlot handle;
        ControlledTermination cancelled;
        synchronized (this) {
            if (state != State.ACTIVE) {
                return;
            }
            state = State.CANCELLED;
            cancelled = new ControlledTermination(CANCELLED, null);
            termination = cancelled;
            handle = latestHandle;
            notifyAll();
        }
        if (handle != null) {
            handle.cancel();
        }
        dispatchTermination(cancelled);
    }

    public boolean terminate(ControlledTermination controlledTermination) {
        HandleSlot handle = claimControlledTerminationAndGetHandle(
                controlledTermination);
        if (handle == REJECTED_TERMINATION) {
            return false;
        }
        if (handle != null) {
            handle.cancel();
        }
        dispatchTermination(controlledTermination);
        return true;
    }

    private static final HandleSlot REJECTED_TERMINATION = new HandleSlot(() -> { });

    private HandleSlot claimControlledTerminationAndGetHandle(
            ControlledTermination controlledTermination) {
        Objects.requireNonNull(controlledTermination, "受控终止不能为空");
        synchronized (this) {
            if (state != State.ACTIVE) {
                return REJECTED_TERMINATION;
            }
            state = State.CONTROLLED_TERMINATION;
            termination = controlledTermination;
            notifyAll();
            return latestHandle;
        }
    }

    boolean claimControlledTermination(ControlledTermination controlledTermination) {
        Objects.requireNonNull(controlledTermination, "受控终止不能为空");
        synchronized (this) {
            if (state != State.ACTIVE) {
                return false;
            }
            state = State.CONTROLLED_TERMINATION;
            termination = controlledTermination;
        }
        return true;
    }

    void dispatchClaimedTermination() {
        ControlledTermination current;
        synchronized (this) {
            current = termination;
        }
        if (current != null) {
            dispatchTermination(current);
        }
    }

    public boolean completeNormally() {
        synchronized (this) {
            if (state != State.ACTIVE) {
                return false;
            }
            state = State.COMPLETED;
            return true;
        }
    }

    boolean claimNormalCompletion() {
        synchronized (this) {
            if (state != State.ACTIVE) {
                return false;
            }
            state = State.NORMAL_COMPLETING;
            return true;
        }
    }

    boolean finishNormalCompletion() {
        synchronized (this) {
            if (state != State.NORMAL_COMPLETING) {
                return false;
            }
            state = State.COMPLETED;
            return true;
        }
    }

    boolean failNormalCompletion(Throwable error, Consumer<Throwable> errorHandler) {
        Objects.requireNonNull(error, "普通完成错误不能为空");
        synchronized (this) {
            if (state != State.NORMAL_COMPLETING) {
                return false;
            }
            state = State.COMPLETED;
        }
        if (errorHandler == null) {
            LOG.warn("Ignored error", error);
            return true;
        }
        try {
            errorHandler.accept(error);
        } catch (RuntimeException handlerError) {
            LOG.error("While handling the following error...", error);
            LOG.error("...the following error happened", handlerError);
        }
        return true;
    }

    public CallbackTicket enterCallback() {
        synchronized (this) {
            if (state != State.ACTIVE) {
                return null;
            }
            activeCallbacks++;
            return new CallbackTicket(this);
        }
    }

    public void onControlledTermination(Consumer<ControlledTermination> handler) {
        Objects.requireNonNull(handler, "受控终止回调不能为空");
        ControlledTermination pending;
        synchronized (this) {
            terminationHandler = handler;
            pending = termination;
        }
        if (pending != null) {
            dispatchTermination(pending);
        }
    }

    public synchronized boolean isCancelled() {
        return state == State.CANCELLED;
    }

    public synchronized boolean isOpen() {
        return state == State.ACTIVE;
    }

    public boolean runIfOpen(Runnable action) {
        Objects.requireNonNull(action, "受控动作不能为空");
        synchronized (this) {
            if (state != State.ACTIVE) {
                return false;
            }
            action.run();
            return true;
        }
    }

    /**
     * 在不持有控制器锁的情况下启动可能阻塞的外部模型请求。
     * 状态检查是线性化点；随后发生的取消由迟到 handle 注册立即取消来收敛。
     */
    public boolean startModelRequestIfOpen(Runnable action) {
        Objects.requireNonNull(action, "模型启动动作不能为空");
        synchronized (this) {
            if (state != State.ACTIVE) {
                return false;
            }
        }
        action.run();
        return true;
    }

    public synchronized ControlledTermination controlledTermination() {
        return termination;
    }

    public synchronized int modelRequestCount() {
        return modelRequestCount;
    }

    public synchronized long latestModelRequestGeneration() {
        return latestModelRequestGeneration;
    }

    public synchronized int toolExecutionCount() {
        return toolExecutionCount;
    }

    public boolean awaitQuiescence(Duration timeout) {
        Objects.requireNonNull(timeout, "等待时长不能为空");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("等待时长不能为负数");
        }
        long remainingNanos = timeout.toNanos();
        long deadline = System.nanoTime() + remainingNanos;
        synchronized (this) {
            while (activeCallbacks > 0) {
                if (remainingNanos <= 0) {
                    return false;
                }
                try {
                    long millis = Math.max(1L, remainingNanos / 1_000_000L);
                    wait(millis);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                remainingNanos = deadline - System.nanoTime();
            }
            return true;
        }
    }

    private void dispatchTermination(ControlledTermination value) {
        Consumer<ControlledTermination> handler;
        synchronized (this) {
            if (terminationDelivered || terminationHandler == null
                    || activeCallbacks > 0) {
                return;
            }
            terminationDelivered = true;
            handler = terminationHandler;
        }
        safelyNotify(handler, value);
    }

    private void safelyNotify(
            Consumer<ControlledTermination> handler, ControlledTermination value) {
        try {
            handler.accept(value);
        } catch (RuntimeException ignored) {
            // 上层收尾失败不能重开终态门，也不能触发第二次通知。
        }
    }

    private void leaveCallback() {
        ControlledTermination pending = null;
        synchronized (this) {
            activeCallbacks--;
            if (activeCallbacks == 0) {
                notifyAll();
                if (termination != null && !terminationDelivered) {
                    pending = termination;
                }
            }
        }
        if (pending != null) {
            dispatchTermination(pending);
        }
    }

    public static final class CallbackTicket implements AutoCloseable {

        private final StreamingRequestController owner;
        private final AtomicBoolean closed = new AtomicBoolean();

        private CallbackTicket(StreamingRequestController owner) {
            this.owner = owner;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.leaveCallback();
            }
        }
    }

    private static final class HandleSlot {

        private final StreamingRequestHandle handle;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private HandleSlot(StreamingRequestHandle handle) {
            this.handle = handle;
        }

        private void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                handle.cancel();
            }
        }
    }

    private enum State {
        ACTIVE,
        NORMAL_COMPLETING,
        CONTROLLED_TERMINATION,
        CANCELLED,
        COMPLETED
    }
}
