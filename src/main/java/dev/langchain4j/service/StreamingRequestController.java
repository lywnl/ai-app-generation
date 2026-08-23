package dev.langchain4j.service;

import dev.langchain4j.model.chat.response.StreamingRequestHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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
    private long cancelledGeneration = -1L;
    private long latestHandleGeneration = -1;
    private ModelRequestClaim pendingModelRequestClaim;
    private ToolBatchTicket activeToolBatch;
    private ControlledTermination termination;
    private Consumer<ControlledTermination> terminationHandler;
    private boolean terminationDelivered;
    private final List<RecoveryReadinessWaiter> recoveryReadinessWaiters =
            new ArrayList<>();
    private boolean recoveryReadinessDispatching;
    private final RepeatedReadLoopGuard repeatedReadLoopGuard =
            new RepeatedReadLoopGuard();

    RepeatedReadLoopGuard.Action observeRepeatedRead(
            dev.langchain4j.agent.tool.ToolExecutionRequest request,
            String result) {
        return repeatedReadLoopGuard.observe(request, result);
    }

    List<dev.langchain4j.data.message.ChatMessage>
            claimRepeatedReadCorrection() {
        return repeatedReadLoopGuard.claimTransientMessages();
    }

    /**
     * 等待恢复来源代的旧回调与工具批次都完成，避免门禁读取到尚未配对的
     * tool_calls。等待结果只在 controller monitor 外完成，回调不得反入锁内。
     */
    CompletionStage<RecoveryReadiness> awaitRecoveryReadiness(
            long sourceGeneration) {
        if (sourceGeneration < 0L) {
            throw new IllegalArgumentException("恢复来源代次不能为负数");
        }
        CompletableFuture<RecoveryReadiness> future =
                new CompletableFuture<>();
        RecoveryReadiness readiness;
        synchronized (this) {
            readiness = recoveryReadiness(sourceGeneration);
            if (readiness == null) {
                recoveryReadinessWaiters.add(
                        new RecoveryReadinessWaiter(
                                sourceGeneration, future));
            }
        }
        if (readiness != null) {
            future.complete(readiness);
        }
        return future.minimalCompletionStage();
    }

    private RecoveryReadiness recoveryReadiness(long sourceGeneration) {
        if (latestModelRequestGeneration != sourceGeneration) {
            return RecoveryReadiness.STALE_AFTER_START;
        }
        if (state != State.ACTIVE
                || cancelledGeneration != sourceGeneration) {
            return RecoveryReadiness.CANCELLED_OR_TERMINATED;
        }
        if (activeCallbacks == 0
                && activeToolBatch == null
                && pendingModelRequestClaim == null) {
            return RecoveryReadiness.READY;
        }
        return null;
    }

    private void dispatchRecoveryReadinessWaiters() {
        if (Thread.holdsLock(this)) {
            throw new IllegalStateException(
                    "恢复就绪结果不得在 controller monitor 内完成");
        }
        synchronized (this) {
            if (recoveryReadinessDispatching) {
                return;
            }
            recoveryReadinessDispatching = true;
        }
        while (true) {
            RecoveryReadinessCompletion completion =
                    nextRecoveryReadinessCompletion();
            if (completion == null) {
                return;
            }
            completion.future().complete(completion.readiness());
        }
    }

    private synchronized RecoveryReadinessCompletion
            nextRecoveryReadinessCompletion() {
        var iterator = recoveryReadinessWaiters.iterator();
        while (iterator.hasNext()) {
            RecoveryReadinessWaiter waiter = iterator.next();
            RecoveryReadiness readiness = recoveryReadiness(
                    waiter.sourceGeneration());
            if (readiness != null) {
                iterator.remove();
                return new RecoveryReadinessCompletion(
                        waiter.future(), readiness);
            }
        }
        recoveryReadinessDispatching = false;
        return null;
    }

    public boolean beforeModelRequest() {
        ModelRequestClaim claim = claimModelRequestResult(null, false).claim();
        return claim != null && tryCommitModelRequestStart(claim);
    }

    /** 仅允许当前 generation 的完成回调推进下一次模型请求。 */
    public boolean beforeModelRequest(long expectedCurrentGeneration) {
        if (expectedCurrentGeneration < 0L) {
            throw new IllegalArgumentException("期望的模型请求代次不能为负数");
        }
        boolean recoverySource = isRecoverySourceGeneration(
                expectedCurrentGeneration);
        ModelRequestClaim claim = claimModelRequestResult(
                expectedCurrentGeneration, recoverySource).claim();
        return claim != null && tryCommitModelRequestStart(claim);
    }

    public boolean beforeToolExecution() {
        return claimToolExecution(null);
    }

    /**
     * 原子认领普通 initial/continuation 模型请求，并返回新 generation。
     * 已因恢复撤销的来源代必须使用 {@link #claimRecoveryModelRequest(long)}。
     */
    ModelRequestClaim claimModelRequest(long sourceGeneration) {
        if (sourceGeneration < 0L) {
            throw new IllegalArgumentException("模型请求来源代次不能为负数");
        }
        return claimModelRequestResult(sourceGeneration, false).claim();
    }

    /** 原子认领由已撤销 generation 派生的唯一恢复模型请求。 */
    ModelRequestClaim claimRecoveryModelRequest(long sourceGeneration) {
        if (sourceGeneration < 0L) {
            throw new IllegalArgumentException("恢复请求来源代次不能为负数");
        }
        return claimModelRequestResult(sourceGeneration, true).claim();
    }

    ModelRequestClaimResult claimModelRequestResult(long sourceGeneration) {
        return claimModelRequestResult(sourceGeneration, false);
    }

    ModelRequestClaimResult claimRecoveryModelRequestResult(
            long sourceGeneration) {
        return claimModelRequestResult(sourceGeneration, true);
    }

    private ModelRequestClaimResult claimModelRequestResult(
            Long sourceGeneration, boolean recoverySource) {
        ModelRequestClaimResult result;
        synchronized (this) {
            result = claimModelRequestResultLocked(
                    sourceGeneration, recoverySource);
        }
        dispatchRecoveryReadinessWaiters();
        return result;
    }

    private ModelRequestClaimResult claimModelRequestResultLocked(
            Long sourceGeneration, boolean recoverySource) {
            if (state != State.ACTIVE) {
                return ModelRequestClaimResult.sourceInvalid();
            }
            if (sourceGeneration != null
                    && latestModelRequestGeneration != sourceGeneration) {
                return ModelRequestClaimResult.sourceInvalid();
            }
            if (sourceGeneration != null) {
                boolean cancelledSource =
                        cancelledGeneration == sourceGeneration;
                if (recoverySource != cancelledSource) {
                    return ModelRequestClaimResult.sourceInvalid();
                }
            }
            if (modelRequestCount >= MAX_MODEL_REQUESTS) {
                state = State.CONTROLLED_TERMINATION;
                termination = new ControlledTermination(
                        LOOP_LIMIT_EXCEEDED, null);
                pendingModelRequestClaim = null;
                return ModelRequestClaimResult.loopLimitExceeded();
            }
            modelRequestCount++;
            latestModelRequestGeneration++;
            ModelRequestClaim claim = new ModelRequestClaim(
                    latestModelRequestGeneration);
            pendingModelRequestClaim = claim;
            return ModelRequestClaimResult.claimed(claim);
    }

    /**
     * 在线性化点提交当前 generation 的真实 SDK 启动。
     * 取消先获得 controller monitor 时提交失败；提交先获得时允许启动，
     * 随后注册的迟到 handle 会由取消终态立即撤销。
     */
    boolean tryCommitModelRequestStart(ModelRequestClaim claim) {
        Objects.requireNonNull(claim, "模型请求认领票据不能为空");
        boolean committed;
        synchronized (this) {
            if (pendingModelRequestClaim != claim
                    || !isCurrentGenerationActive(claim.generation())) {
                committed = false;
            } else {
                pendingModelRequestClaim = null;
                committed = true;
            }
        }
        dispatchRecoveryReadinessWaiters();
        return committed;
    }

    boolean claimToolExecution(long requestGeneration) {
        if (requestGeneration < 0L) {
            throw new IllegalArgumentException("工具执行代次不能为负数");
        }
        return claimToolExecution(Long.valueOf(requestGeneration));
    }

    private boolean claimToolExecution(Long requestGeneration) {
        boolean claimed;
        synchronized (this) {
            if (state != State.ACTIVE) {
                return false;
            }
            if (requestGeneration != null
                    && !isCurrentGenerationActive(requestGeneration)) {
                return false;
            }
            if (toolExecutionCount >= MAX_TOOL_EXECUTIONS) {
                state = State.CONTROLLED_TERMINATION;
                termination = new ControlledTermination(
                        LOOP_LIMIT_EXCEEDED, null);
                claimed = false;
            } else {
                toolExecutionCount++;
                claimed = true;
            }
        }
        dispatchRecoveryReadinessWaiters();
        return claimed;
    }

    /**
     * 原子预留一个结构化工具调用批次。票据须先取得 memory 写入启动许可，
     * 再在写入成功后提交，才允许认领工具执行。
     */
    ToolBatchTicket prepareToolBatch(
            long requestGeneration, int toolRequestCount) {
        if (requestGeneration < 0L) {
            throw new IllegalArgumentException("工具批次代次不能为负数");
        }
        if (toolRequestCount <= 0) {
            throw new IllegalArgumentException("工具批次不能为空");
        }
        synchronized (this) {
            if (!isCurrentGenerationActive(requestGeneration)
                    || activeToolBatch != null) {
                return null;
            }
            ToolBatchTicket ticket = new ToolBatchTicket(
                    requestGeneration, toolRequestCount);
            activeToolBatch = ticket;
            return ticket;
        }
    }

    /**
     * 原子申请工具请求 memory 写入启动许可。取消先赢时回滚票据；许可先赢时
     * memory 写入可在 controller monitor 外完成，且取消不得撤销该批次。
     */
    boolean tryStartToolBatchWrite(ToolBatchTicket ticket) {
        Objects.requireNonNull(ticket, "工具批次票据不能为空");
        boolean started;
        synchronized (this) {
            if (activeToolBatch != ticket
                    || ticket.state != ToolBatchState.PREPARED) {
                return false;
            }
            if (!isCurrentGenerationActive(ticket.generation)) {
                rollbackToolBatch(ticket);
                started = false;
            } else {
                ticket.state = ToolBatchState.WRITE_STARTED;
                started = true;
            }
        }
        dispatchRecoveryReadinessWaiters();
        return started;
    }

    /**
     * 确认工具请求消息已成功写入；取消不会撤销已经持久化的请求批次，
     * 后续仍须为每个 tool_call 写入真实或明确跳过结果。
     */
    boolean commitToolBatch(ToolBatchTicket ticket) {
        Objects.requireNonNull(ticket, "工具批次票据不能为空");
        synchronized (this) {
            if (activeToolBatch != ticket
                    || ticket.state != ToolBatchState.WRITE_STARTED) {
                return false;
            }
            ticket.state = ToolBatchState.COMMITTED;
            return true;
        }
    }

    /**
     * 回滚未持久化的工具请求批次，并按 generation 唯一认领普通错误终态。
     */
    boolean failPreparedToolBatch(ToolBatchTicket ticket) {
        Objects.requireNonNull(ticket, "工具批次票据不能为空");
        boolean errorClaimed;
        synchronized (this) {
            if (activeToolBatch != ticket
                    || (ticket.state != ToolBatchState.PREPARED
                    && ticket.state != ToolBatchState.WRITE_STARTED)) {
                return false;
            }
            rollbackToolBatch(ticket);
            errorClaimed = claimToolBatchError(ticket.generation);
        }
        dispatchRecoveryReadinessWaiters();
        return errorClaimed;
    }

    private void rollbackToolBatch(ToolBatchTicket ticket) {
        ticket.state = ToolBatchState.ROLLED_BACK;
        activeToolBatch = null;
    }

    /** 在锁内只认领执行额度；真实工具回调与 executor 必须在锁外运行。 */
    ToolExecutionDecision claimToolExecution(
            ToolBatchTicket ticket, int toolIndex) {
        Objects.requireNonNull(ticket, "工具批次票据不能为空");
        synchronized (this) {
            if (ticket.state != ToolBatchState.COMMITTED) {
                return ToolExecutionDecision.REJECTED;
            }
            ToolItemState itemState = toolItemState(ticket, toolIndex);
            if (itemState != ToolItemState.PENDING) {
                return ToolExecutionDecision.REJECTED;
            }
            if (isToolBatchCancelled(ticket)) {
                ticket.itemStates[toolIndex] = ToolItemState.EXECUTION_SKIPPED;
                return ToolExecutionDecision.CANCELLED;
            }
            if (state != State.ACTIVE) {
                ticket.itemStates[toolIndex] = ToolItemState.EXECUTION_SKIPPED;
                return ToolExecutionDecision.TERMINATED;
            }
            if (toolExecutionCount >= MAX_TOOL_EXECUTIONS) {
                ticket.itemStates[toolIndex] = ToolItemState.EXECUTION_SKIPPED;
                return ToolExecutionDecision.LOOP_LIMIT_EXCEEDED;
            }
            toolExecutionCount++;
            ticket.itemStates[toolIndex] = ToolItemState.EXECUTING;
            return ToolExecutionDecision.EXECUTE;
        }
    }

    /** 原子预留锁外应持久化的真实/跳过结果或明确取消结果。 */
    ToolResultClaim prepareToolResult(
            ToolBatchTicket ticket,
            int toolIndex,
            ControlledTermination requestedTermination) {
        Objects.requireNonNull(ticket, "工具批次票据不能为空");
        synchronized (this) {
            if (ticket.state != ToolBatchState.COMMITTED) {
                return ToolResultClaim.rejected();
            }
            ToolItemState itemState = toolItemState(ticket, toolIndex);
            if (itemState == ToolItemState.REJECTED
                    || itemState == ToolItemState.RESULT_PREPARED
                    || itemState == ToolItemState.RESULT_COMMITTED
                    || itemState == ToolItemState.RESULT_ROLLED_BACK) {
                return ToolResultClaim.rejected();
            }
            ticket.itemStates[toolIndex] = ToolItemState.RESULT_PREPARED;
            if (isToolBatchCancelled(ticket)) {
                return ToolResultClaim.prepared(
                        ToolResultDecision.CANCELLED, null);
            }
            if (requestedTermination != null && state == State.ACTIVE) {
                return ToolResultClaim.prepared(
                        ToolResultDecision.TERMINATED,
                        requestedTermination);
            }
            return ToolResultClaim.prepared(
                    ToolResultDecision.PROVIDED, null);
        }
    }

    /**
     * 确认工具结果已经写入 memory；受控终止只能在持久化成功点生效。
     */
    ToolResultDecision commitToolResult(
            ToolBatchTicket ticket,
            int toolIndex,
            ToolResultClaim claim) {
        Objects.requireNonNull(ticket, "工具批次票据不能为空");
        Objects.requireNonNull(claim, "工具结果认领不能为空");
        ToolResultDecision decision;
        synchronized (this) {
            if (activeToolBatch != ticket
                    || ticket.state != ToolBatchState.COMMITTED
                    || claim.decision == ToolResultDecision.REJECTED
                    || toolItemState(ticket, toolIndex)
                    != ToolItemState.RESULT_PREPARED) {
                return ToolResultDecision.REJECTED;
            }
            ticket.itemStates[toolIndex] = ToolItemState.RESULT_COMMITTED;
            if (claim.requestedTermination != null
                    && state == State.ACTIVE) {
                state = State.CONTROLLED_TERMINATION;
                termination = claim.requestedTermination;
                pendingModelRequestClaim = null;
                decision = ToolResultDecision.TERMINATED;
            } else if (claim.requestedTermination != null) {
                decision = ToolResultDecision.PROVIDED;
            } else {
                decision = claim.decision;
            }
        }
        dispatchRecoveryReadinessWaiters();
        return decision;
    }

    /**
     * 工具结果写入失败时回滚预留、废弃整个批次并唯一认领普通错误终态。
     */
    boolean failPreparedToolResult(
            ToolBatchTicket ticket, int toolIndex, ToolResultClaim claim) {
        Objects.requireNonNull(ticket, "工具批次票据不能为空");
        Objects.requireNonNull(claim, "工具结果认领不能为空");
        boolean errorClaimed;
        synchronized (this) {
            if (activeToolBatch != ticket
                    || ticket.state != ToolBatchState.COMMITTED
                    || claim.decision == ToolResultDecision.REJECTED
                    || toolItemState(ticket, toolIndex)
                    != ToolItemState.RESULT_PREPARED) {
                return false;
            }
            ticket.itemStates[toolIndex] = ToolItemState.RESULT_ROLLED_BACK;
            ticket.state = ToolBatchState.ROLLED_BACK;
            activeToolBatch = null;
            errorClaimed = claimToolBatchError(ticket.generation);
        }
        dispatchRecoveryReadinessWaiters();
        return errorClaimed;
    }

    private boolean claimToolBatchError(long requestGeneration) {
        if (!isCurrentGenerationActive(requestGeneration)) {
            return false;
        }
        state = State.COMPLETED;
        pendingModelRequestClaim = null;
        return true;
    }

    /** 完成整个工具批次；返回值表示当前代仍可继续进入下一次模型请求。 */
    boolean finishToolBatch(ToolBatchTicket ticket) {
        Objects.requireNonNull(ticket, "工具批次票据不能为空");
        boolean continueModelRequest;
        synchronized (this) {
            if (activeToolBatch != ticket
                    || ticket.state != ToolBatchState.COMMITTED) {
                return false;
            }
            for (ToolItemState itemState : ticket.itemStates) {
                if (itemState != ToolItemState.RESULT_COMMITTED) {
                    return false;
                }
            }
            ticket.state = ToolBatchState.FINISHED;
            activeToolBatch = null;
            continueModelRequest = isCurrentGenerationActive(
                    ticket.generation);
        }
        dispatchRecoveryReadinessWaiters();
        return continueModelRequest;
    }

    private ToolItemState toolItemState(
            ToolBatchTicket ticket, int toolIndex) {
        if (activeToolBatch != ticket
                || toolIndex < 0
                || toolIndex >= ticket.itemStates.length) {
            return ToolItemState.REJECTED;
        }
        return ticket.itemStates[toolIndex];
    }

    private boolean isToolBatchCancelled(ToolBatchTicket ticket) {
        return state == State.CANCELLED
                || ticket.generation != latestModelRequestGeneration
                || ticket.generation == cancelledGeneration;
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
                    || requestGeneration != latestModelRequestGeneration
                    || requestGeneration == cancelledGeneration;
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
            pendingModelRequestClaim = null;
            rollbackUnstartedToolBatch();
            cancelled = new ControlledTermination(CANCELLED, null);
            termination = cancelled;
            handle = latestHandle;
            notifyAll();
        }
        dispatchRecoveryReadinessWaiters();
        if (handle != null) {
            handle.cancel();
        }
        dispatchTermination(cancelled);
    }

    boolean cancelIfCurrentGeneration(long requestGeneration) {
        return cancelIfGeneration(requestGeneration, false);
    }

    boolean cancelIfRecoverySourceGeneration(long requestGeneration) {
        return cancelIfGeneration(requestGeneration, true);
    }

    private boolean cancelIfGeneration(
            long requestGeneration, boolean recoverySource) {
        HandleSlot handle;
        ControlledTermination cancelled;
        synchronized (this) {
            boolean matches = recoverySource
                    ? isRecoverySourceGenerationActive(requestGeneration)
                    : isCurrentGenerationActive(requestGeneration);
            if (!matches) {
                return false;
            }
            state = State.CANCELLED;
            pendingModelRequestClaim = null;
            rollbackUnstartedToolBatch();
            cancelled = new ControlledTermination(CANCELLED, null);
            termination = cancelled;
            handle = latestHandle;
            notifyAll();
        }
        dispatchRecoveryReadinessWaiters();
        if (handle != null) {
            handle.cancel();
        }
        dispatchTermination(cancelled);
        return true;
    }

    private void rollbackUnstartedToolBatch() {
        if (activeToolBatch != null
                && activeToolBatch.state == ToolBatchState.PREPARED) {
            rollbackToolBatch(activeToolBatch);
        }
    }

    public boolean terminate(ControlledTermination controlledTermination) {
        HandleSlot handle = claimControlledTerminationAndGetHandle(
                controlledTermination);
        if (handle == REJECTED_TERMINATION) {
            return false;
        }
        dispatchRecoveryReadinessWaiters();
        if (handle != null) {
            handle.cancel();
        }
        dispatchTermination(controlledTermination);
        return true;
    }

    /**
     * 仅允许当前 generation 触发受控终止，并只取消属于该 generation 的
     * 底层请求句柄。恢复流程撤销后的旧回调必须被拒绝，不能终止新请求。
     */
    boolean terminate(
            long requestGeneration,
            ControlledTermination controlledTermination) {
        if (requestGeneration < 0L) {
            throw new IllegalArgumentException("模型请求代次不能为负数");
        }
        Objects.requireNonNull(controlledTermination, "受控终止不能为空");
        HandleSlot handle;
        synchronized (this) {
            if (!isCurrentGenerationActive(requestGeneration)) {
                return false;
            }
            state = State.CONTROLLED_TERMINATION;
            pendingModelRequestClaim = null;
            termination = controlledTermination;
            handle = latestHandleGeneration == requestGeneration
                    ? latestHandle : null;
            notifyAll();
        }
        dispatchRecoveryReadinessWaiters();
        if (handle != null) {
            cancelHandleBestEffort(handle);
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
            pendingModelRequestClaim = null;
            termination = controlledTermination;
            notifyAll();
            return latestHandle;
        }
    }

    boolean claimControlledTermination(ControlledTermination controlledTermination) {
        Objects.requireNonNull(controlledTermination, "受控终止不能为空");
        boolean claimed;
        synchronized (this) {
            if (state != State.ACTIVE) {
                return false;
            }
            state = State.CONTROLLED_TERMINATION;
            pendingModelRequestClaim = null;
            termination = controlledTermination;
            claimed = true;
        }
        dispatchRecoveryReadinessWaiters();
        return claimed;
    }

    boolean claimControlledTermination(
            long requestGeneration,
            ControlledTermination controlledTermination) {
        Objects.requireNonNull(controlledTermination, "受控终止不能为空");
        boolean claimed;
        synchronized (this) {
            if (!isCurrentGenerationActive(requestGeneration)) {
                return false;
            }
            state = State.CONTROLLED_TERMINATION;
            pendingModelRequestClaim = null;
            termination = controlledTermination;
            claimed = true;
        }
        dispatchRecoveryReadinessWaiters();
        return claimed;
    }

    /** 原子认领仍由已撤销 generation 派生的恢复准备受控终止。 */
    boolean claimRecoverySourceControlledTermination(
            long sourceGeneration,
            ControlledTermination controlledTermination) {
        Objects.requireNonNull(controlledTermination, "受控终止不能为空");
        boolean claimed;
        synchronized (this) {
            if (!isRecoverySourceGenerationActive(sourceGeneration)) {
                return false;
            }
            state = State.CONTROLLED_TERMINATION;
            pendingModelRequestClaim = null;
            termination = controlledTermination;
            claimed = true;
        }
        dispatchRecoveryReadinessWaiters();
        return claimed;
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
        boolean completed;
        synchronized (this) {
            if (state != State.ACTIVE) {
                return false;
            }
            state = State.COMPLETED;
            pendingModelRequestClaim = null;
            completed = true;
        }
        dispatchRecoveryReadinessWaiters();
        return completed;
    }

    boolean claimNormalCompletion() {
        boolean claimed;
        synchronized (this) {
            if (state != State.ACTIVE) {
                return false;
            }
            state = State.NORMAL_COMPLETING;
            pendingModelRequestClaim = null;
            claimed = true;
        }
        dispatchRecoveryReadinessWaiters();
        return claimed;
    }

    boolean claimNormalCompletion(long requestGeneration) {
        boolean claimed;
        synchronized (this) {
            if (!isCurrentGenerationActive(requestGeneration)) {
                return false;
            }
            state = State.NORMAL_COMPLETING;
            pendingModelRequestClaim = null;
            claimed = true;
        }
        dispatchRecoveryReadinessWaiters();
        return claimed;
    }

    /** 原子认领当前 generation 的普通错误终态。 */
    boolean claimErrorCompletion(long requestGeneration) {
        boolean claimed;
        synchronized (this) {
            if (!isCurrentGenerationActive(requestGeneration)) {
                return false;
            }
            state = State.COMPLETED;
            pendingModelRequestClaim = null;
            claimed = true;
        }
        dispatchRecoveryReadinessWaiters();
        return claimed;
    }

    /** 原子认领仍由已撤销 generation 派生的恢复准备失败。 */
    boolean claimRecoverySourceFailure(long sourceGeneration) {
        boolean claimed;
        synchronized (this) {
            if (!isRecoverySourceGenerationActive(sourceGeneration)) {
                return false;
            }
            state = State.COMPLETED;
            pendingModelRequestClaim = null;
            claimed = true;
        }
        dispatchRecoveryReadinessWaiters();
        return claimed;
    }

    boolean finishNormalCompletion() {
        boolean completed;
        synchronized (this) {
            if (state != State.NORMAL_COMPLETING) {
                return false;
            }
            state = State.COMPLETED;
            completed = true;
        }
        dispatchRecoveryReadinessWaiters();
        return completed;
    }

    boolean failNormalCompletion(Throwable error, Consumer<Throwable> errorHandler) {
        Objects.requireNonNull(error, "普通完成错误不能为空");
        synchronized (this) {
            if (state != State.NORMAL_COMPLETING) {
                return false;
            }
            state = State.COMPLETED;
        }
        dispatchRecoveryReadinessWaiters();
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

    public CallbackTicket enterCallback(long requestGeneration) {
        synchronized (this) {
            if (!isCurrentGenerationActive(requestGeneration)) {
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

    public synchronized boolean isCurrentGeneration(
            long requestGeneration) {
        return isCurrentGenerationActive(requestGeneration);
    }

    public synchronized boolean isRecoverySourceGeneration(
            long requestGeneration) {
        return isRecoverySourceGenerationActive(requestGeneration);
    }

    private boolean isRecoverySourceGenerationActive(
            long requestGeneration) {
        return state == State.ACTIVE
                && latestModelRequestGeneration == requestGeneration
                && cancelledGeneration == requestGeneration;
    }

    public GenerationCancellation cancelGenerationForRecovery(
            long expectedGeneration) {
        HandleSlot handle;
        synchronized (this) {
            if (!isCurrentGenerationActive(expectedGeneration)) {
                return GenerationCancellation.REJECTED;
            }
            cancelledGeneration = expectedGeneration;
            rollbackUnstartedToolBatch();
            handle = latestHandleGeneration == expectedGeneration
                    ? latestHandle : null;
            if (handle != null) {
                latestHandle = null;
                latestHandleGeneration = -1L;
            }
        }
        dispatchRecoveryReadinessWaiters();
        if (handle != null) {
            cancelHandleBestEffort(handle);
        }
        return GenerationCancellation.CANCELLED;
    }

    private void cancelHandleBestEffort(HandleSlot handle) {
        try {
            handle.cancel();
        } catch (RuntimeException exception) {
            LOG.warn("Streaming request handle cancellation failed: type={}",
                    exception.getClass().getSimpleName());
        }
    }

    private boolean isCurrentGenerationActive(long requestGeneration) {
        return state == State.ACTIVE
                && requestGeneration == latestModelRequestGeneration
                && requestGeneration != cancelledGeneration;
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
        dispatchRecoveryReadinessWaiters();
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

    public enum GenerationCancellation {
        CANCELLED,
        REJECTED
    }

    enum RecoveryReadiness {
        READY,
        CANCELLED_OR_TERMINATED,
        STALE_AFTER_START
    }

    private record RecoveryReadinessWaiter(
            long sourceGeneration,
            CompletableFuture<RecoveryReadiness> future) {

        private RecoveryReadinessWaiter {
            Objects.requireNonNull(future, "恢复就绪等待结果不能为空");
        }
    }

    private record RecoveryReadinessCompletion(
            CompletableFuture<RecoveryReadiness> future,
            RecoveryReadiness readiness) {

        private RecoveryReadinessCompletion {
            Objects.requireNonNull(future, "恢复就绪完成结果不能为空");
            Objects.requireNonNull(readiness, "恢复就绪状态不能为空");
        }
    }

    static final class ModelRequestClaim {

        private final long generation;

        private ModelRequestClaim(long generation) {
            if (generation <= 0L) {
                throw new IllegalArgumentException("模型请求代次必须为正数");
            }
            this.generation = generation;
        }

        long generation() {
            return generation;
        }
    }

    record ModelRequestClaimResult(
            ModelRequestClaimStatus status, ModelRequestClaim claim) {

        ModelRequestClaimResult {
            Objects.requireNonNull(status, "模型请求认领状态不能为空");
            if ((status == ModelRequestClaimStatus.CLAIMED) != (claim != null)) {
                throw new IllegalArgumentException("模型请求认领状态与票据不一致");
            }
        }

        private static ModelRequestClaimResult claimed(ModelRequestClaim claim) {
            return new ModelRequestClaimResult(
                    ModelRequestClaimStatus.CLAIMED, claim);
        }

        private static ModelRequestClaimResult sourceInvalid() {
            return new ModelRequestClaimResult(
                    ModelRequestClaimStatus.SOURCE_INVALID, null);
        }

        private static ModelRequestClaimResult loopLimitExceeded() {
            return new ModelRequestClaimResult(
                    ModelRequestClaimStatus.LOOP_LIMIT_EXCEEDED, null);
        }
    }

    enum ModelRequestClaimStatus {
        CLAIMED,
        SOURCE_INVALID,
        LOOP_LIMIT_EXCEEDED
    }

    static final class ToolBatchTicket {

        private final long generation;
        private final ToolItemState[] itemStates;
        private ToolBatchState state = ToolBatchState.PREPARED;

        private ToolBatchTicket(long generation, int toolRequestCount) {
            this.generation = generation;
            this.itemStates = new ToolItemState[toolRequestCount];
            java.util.Arrays.fill(this.itemStates, ToolItemState.PENDING);
        }
    }

    enum ToolExecutionDecision {
        EXECUTE,
        CANCELLED,
        LOOP_LIMIT_EXCEEDED,
        TERMINATED,
        REJECTED
    }

    enum ToolResultDecision {
        PROVIDED,
        CANCELLED,
        TERMINATED,
        REJECTED
    }

    static final class ToolResultClaim {

        private static final ToolResultClaim REJECTED = new ToolResultClaim(
                ToolResultDecision.REJECTED, null);

        private final ToolResultDecision decision;
        private final ControlledTermination requestedTermination;

        private ToolResultClaim(
                ToolResultDecision decision,
                ControlledTermination requestedTermination) {
            this.decision = Objects.requireNonNull(
                    decision, "工具结果决定不能为空");
            this.requestedTermination = requestedTermination;
        }

        private static ToolResultClaim prepared(
                ToolResultDecision decision,
                ControlledTermination requestedTermination) {
            return new ToolResultClaim(decision, requestedTermination);
        }

        private static ToolResultClaim rejected() {
            return REJECTED;
        }

        ToolResultDecision decision() {
            return decision;
        }
    }

    private enum ToolBatchState {
        PREPARED,
        WRITE_STARTED,
        COMMITTED,
        ROLLED_BACK,
        FINISHED
    }

    private enum ToolItemState {
        PENDING,
        EXECUTING,
        EXECUTION_SKIPPED,
        RESULT_PREPARED,
        RESULT_COMMITTED,
        RESULT_ROLLED_BACK,
        REJECTED
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
