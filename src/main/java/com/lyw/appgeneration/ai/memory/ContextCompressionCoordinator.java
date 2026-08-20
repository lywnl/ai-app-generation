package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.monitor.MemoryCompressionMetricsCollector;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemoryCompressionResult;
import com.lyw.appgeneration.service.MemorySummaryService;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

import static com.lyw.appgeneration.ai.memory.ContextAdmissionResult.FailureReason;

/** 统一编排异步压缩、阻塞压缩和输入硬上限工具链检查点。 */
@Component
public class ContextCompressionCoordinator {

    private static final RequestSnapshot EMPTY_REQUEST_SNAPSHOT =
            new RequestSnapshot(List.of(), 0);

    private final ChatTokenEstimator tokenEstimator;
    private final ChatHistoryService chatHistoryService;
    private final MemorySummaryService summaryService;
    private final MemoryTokenProperties properties;
    private final ExecutorService compressionExecutor;
    private final ExecutorService memoryReadExecutor;
    private final ExecutorService asyncPlanningExecutor;
    private final AppDataLifecycleFence lifecycleFence;
    private final MemoryCompressionMetricsCollector metricsCollector;
    private final LongSupplier nanoTime;
    private final ConversationTurnSelector turnSelector =
            new ConversationTurnSelector();
    private final ConversationTurnSnapshotParser snapshotParser =
            new ConversationTurnSnapshotParser();
    private final UnfinishedToolChainCheckpointProjector checkpointProjector =
            new UnfinishedToolChainCheckpointProjector();

    @Autowired
    public ContextCompressionCoordinator(
            ChatTokenEstimator tokenEstimator,
            ChatHistoryService chatHistoryService,
            MemorySummaryService summaryService,
            MemoryTokenProperties properties,
            @Qualifier("contextCompressionExecutor")
            ExecutorService compressionExecutor,
            @Qualifier("contextMemoryReadExecutor")
            ExecutorService memoryReadExecutor,
            @Qualifier("contextAsyncCompressionPlanningExecutor")
            ExecutorService asyncPlanningExecutor,
            AppDataLifecycleFence lifecycleFence,
            MemoryCompressionMetricsCollector metricsCollector) {
        this(tokenEstimator, chatHistoryService, summaryService, properties,
                compressionExecutor, memoryReadExecutor, asyncPlanningExecutor,
                lifecycleFence,
                metricsCollector,
                System::nanoTime);
    }

    public ContextCompressionCoordinator(
            ChatTokenEstimator tokenEstimator,
            ChatHistoryService chatHistoryService,
            MemorySummaryService summaryService,
            MemoryTokenProperties properties,
            ExecutorService compressionExecutor,
            AppDataLifecycleFence lifecycleFence,
            MemoryCompressionMetricsCollector metricsCollector) {
        this(tokenEstimator, chatHistoryService, summaryService, properties,
                compressionExecutor, DirectExecutorService.INSTANCE,
                DirectExecutorService.INSTANCE,
                lifecycleFence,
                metricsCollector, System::nanoTime);
    }

    ContextCompressionCoordinator(
            ChatTokenEstimator tokenEstimator,
            ChatHistoryService chatHistoryService,
            MemorySummaryService summaryService,
            MemoryTokenProperties properties,
            ExecutorService compressionExecutor,
            ExecutorService memoryReadExecutor,
            AppDataLifecycleFence lifecycleFence,
            MemoryCompressionMetricsCollector metricsCollector) {
        this(tokenEstimator, chatHistoryService, summaryService, properties,
                compressionExecutor, memoryReadExecutor, memoryReadExecutor,
                lifecycleFence, metricsCollector, System::nanoTime);
    }

    ContextCompressionCoordinator(
            ChatTokenEstimator tokenEstimator,
            ChatHistoryService chatHistoryService,
            MemorySummaryService summaryService,
            MemoryTokenProperties properties,
            ExecutorService compressionExecutor,
            ExecutorService memoryReadExecutor,
            AppDataLifecycleFence lifecycleFence,
            MemoryCompressionMetricsCollector metricsCollector,
            LongSupplier nanoTime) {
        this(tokenEstimator, chatHistoryService, summaryService, properties,
                compressionExecutor, memoryReadExecutor, memoryReadExecutor,
                lifecycleFence, metricsCollector, nanoTime);
    }

    ContextCompressionCoordinator(
            ChatTokenEstimator tokenEstimator,
            ChatHistoryService chatHistoryService,
            MemorySummaryService summaryService,
            MemoryTokenProperties properties,
            ExecutorService compressionExecutor,
            ExecutorService memoryReadExecutor,
            ExecutorService asyncPlanningExecutor,
            AppDataLifecycleFence lifecycleFence,
            MemoryCompressionMetricsCollector metricsCollector,
            LongSupplier nanoTime) {
        this.tokenEstimator = Objects.requireNonNull(
                tokenEstimator, "Token 估算器不能为空");
        this.chatHistoryService = Objects.requireNonNull(
                chatHistoryService, "对话历史服务不能为空");
        this.summaryService = Objects.requireNonNull(
                summaryService, "摘要服务不能为空");
        this.properties = Objects.requireNonNull(
                properties, "Token 配置不能为空");
        this.compressionExecutor = Objects.requireNonNull(
                compressionExecutor, "上下文压缩执行器不能为空");
        this.memoryReadExecutor = Objects.requireNonNull(
                memoryReadExecutor, "上下文记忆读取执行器不能为空");
        this.asyncPlanningExecutor = Objects.requireNonNull(
                asyncPlanningExecutor, "异步压缩计划执行器不能为空");
        this.lifecycleFence = Objects.requireNonNull(
                lifecycleFence, "应用数据生命周期栅栏不能为空");
        this.metricsCollector = Objects.requireNonNull(
                metricsCollector, "记忆压缩指标收集器不能为空");
        this.nanoTime = Objects.requireNonNull(
                nanoTime, "单调时钟不能为空");
    }

    ContextCompressionCoordinator(
            ChatTokenEstimator tokenEstimator,
            ChatHistoryService chatHistoryService,
            MemorySummaryService summaryService,
            MemoryTokenProperties properties,
            ExecutorService compressionExecutor,
            AppDataLifecycleFence lifecycleFence,
            MemoryCompressionMetricsCollector metricsCollector,
            LongSupplier nanoTime) {
        this(tokenEstimator, chatHistoryService, summaryService, properties,
                compressionExecutor, DirectExecutorService.INSTANCE,
                DirectExecutorService.INSTANCE,
                lifecycleFence,
                metricsCollector, nanoTime);
    }

    public ContextAdmissionResult admit(
            CompressionAwareChatMemory memory,
            List<ToolSpecification> tools) {
        return admit(memory, tools, List.of(), ignored -> { },
                ContextContinuationGate.alwaysOpen(),
                new ContextCompressionAttemptState());
    }

    public ContextAdmissionResult admit(
            CompressionAwareChatMemory memory,
            List<ToolSpecification> tools,
            ContextCompressionAttemptState attemptState) {
        return admit(memory, tools, List.of(), ignored -> { },
                ContextContinuationGate.alwaysOpen(), attemptState);
    }

    public ContextAdmissionResult admit(
            CompressionAwareChatMemory memory,
            List<ToolSpecification> tools,
            Consumer<ContextAdmissionResult> transitionListener) {
        return admit(memory, tools, List.of(), transitionListener,
                ContextContinuationGate.alwaysOpen(),
                new ContextCompressionAttemptState());
    }

    /**
     * 通过调用方的原子回调门提交游标裁剪、worker 启动和完成结果。
     *
     * <p>{@code tryRun} 成功表示本次提交先于终态获胜；返回的可继续结果不是
     * 永久通行证，实际模型请求仍须再次通过同一个真实回调门。</p>
     */
    public ContextAdmissionResult admit(
            CompressionAwareChatMemory memory,
            List<ToolSpecification> tools,
            Consumer<ContextAdmissionResult> transitionListener,
            ContextContinuationGate continuationGate) {
        return admit(memory, tools, List.of(), transitionListener,
                continuationGate, new ContextCompressionAttemptState());
    }

    public ContextAdmissionResult admit(
            CompressionAwareChatMemory memory,
            List<ToolSpecification> tools,
            List<ChatMessage> transientMessages,
            Consumer<ContextAdmissionResult> transitionListener,
            ContextContinuationGate continuationGate) {
        return admit(memory, tools, transientMessages, transitionListener,
                continuationGate, new ContextCompressionAttemptState());
    }

    public ContextAdmissionResult admit(
            CompressionAwareChatMemory memory,
            List<ToolSpecification> tools,
            List<ChatMessage> transientMessages,
            Consumer<ContextAdmissionResult> transitionListener,
            ContextContinuationGate continuationGate,
            ContextCompressionAttemptState attemptState) {
        AdmissionDeadline deadline = AdmissionDeadline.start(
                properties.getBlockingTimeout(), nanoTime,
                System::currentTimeMillis,
                nanos -> TimeUnit.NANOSECONDS.sleep(nanos));
        ContextAdmissionResult result = admitInternal(
                memory, tools, transientMessages, transitionListener,
                continuationGate,
                deadline, Objects.requireNonNull(
                        attemptState, "上下文压缩尝试状态不能为空"));
        metricsCollector.recordContextGate(
                result.mode(), result.failureReason());
        return result;
    }

    private ContextAdmissionResult admitInternal(
            CompressionAwareChatMemory memory,
            List<ToolSpecification> tools,
            List<ChatMessage> transientMessages,
            Consumer<ContextAdmissionResult> transitionListener,
            ContextContinuationGate continuationGate,
            AdmissionDeadline deadline,
            ContextCompressionAttemptState attemptState) {
        Objects.requireNonNull(memory, "在线记忆不能为空");
        Objects.requireNonNull(transitionListener, "状态监听器不能为空");
        Objects.requireNonNull(continuationGate, "回合原子提交门不能为空");
        List<ToolSpecification> stableTools = List.copyOf(
                tools == null ? List.of() : tools);
        List<ChatMessage> stableTransientMessages = List.copyOf(
                transientMessages == null ? List.of() : transientMessages);
        long appId = requireAppId(memory.id());
        if (!tryCommitContinuation(continuationGate)) {
            return turnTerminated(ContextCompressionMode.ADMISSION_FAILED,
                    EMPTY_REQUEST_SNAPSHOT, 0L, appId);
        }
        if (!isLifecycleOpen(appId)) {
            return failure(ContextCompressionMode.ADMISSION_FAILED,
                    EMPTY_REQUEST_SNAPSHOT, EMPTY_REQUEST_SNAPSHOT,
                    0L, FailureReason.DELETE_REJECTED,
                    "应用删除流程已接管，appId=" + appId);
        }
        InitialRequestPreparation initialPreparation;
        try {
            initialPreparation = readWithinDeadline(
                    () -> prepareInitialRequest(
                            appId, memory, stableTools,
                            stableTransientMessages,
                            continuationGate), deadline);
        } catch (DependencyReadTimeoutException exception) {
            return failure(ContextCompressionMode.BLOCKING_FAILED,
                    EMPTY_REQUEST_SNAPSHOT, EMPTY_REQUEST_SNAPSHOT,
                    0L, FailureReason.TIMED_OUT,
                    "读取初始上下文超过绝对截止时间");
        } catch (DependencyReadRejectedException exception) {
            return failure(ContextCompressionMode.BLOCKING_FAILED,
                    EMPTY_REQUEST_SNAPSHOT, EMPTY_REQUEST_SNAPSHOT,
                    0L, FailureReason.EXECUTOR_REJECTED,
                    "上下文记忆读取执行器已满");
        } catch (DependencyReadInterruptedException exception) {
            return failure(ContextCompressionMode.BLOCKING_FAILED,
                    EMPTY_REQUEST_SNAPSHOT, EMPTY_REQUEST_SNAPSHOT,
                    0L, FailureReason.INTERRUPTED,
                    "读取初始上下文被中断");
        }
        if (initialPreparation.failureReason() != FailureReason.NONE) {
            return failure(ContextCompressionMode.ADMISSION_FAILED,
                    initialPreparation.request(), initialPreparation.request(),
                    0L, initialPreparation.failureReason(),
                    initialPreparation.detail());
        }
        ContextAdmissionResult initialCommitFailure =
                commitInitialPreparation(
                        appId, memory, continuationGate, deadline,
                        initialPreparation);
        if (initialCommitFailure != null) {
            return initialCommitFailure;
        }
        RequestSnapshot initialRequest = initialPreparation.request();
        int initialTokens = initialRequest.estimatedTokens();
        metricsCollector.recordEstimatedTokens(
                MemoryCompressionMetricsCollector.EstimationStage.BEFORE,
                initialTokens);
        if (attemptState.checkpointProjectionRequired()) {
            return checkpointOrReject(
                    appId, memory, stableTools, stableTransientMessages,
                    initialRequest, initialRequest, 0L, continuationGate,
                    attemptState, null, transitionListener, deadline);
        }
        if (initialTokens < properties.getAsyncCompressionThreshold()) {
            if (!tryCommitContinuation(continuationGate)) {
                return turnTerminated(ContextCompressionMode.ADMISSION_FAILED,
                        initialRequest, 0L, appId);
            }
            return success(ContextCompressionMode.NORMAL,
                    initialRequest, initialRequest, 0L, "无需压缩");
        }
        if (initialTokens < properties.getBlockingCompressionThreshold()) {
            return scheduleAsyncCompressionPlanning(
                    appId, memory, initialRequest, continuationGate);
        }
        CompressionPlan plan;
        try {
            plan = readWithinDeadline(
                    () -> buildPlan(appId, memory), deadline);
        } catch (DependencyReadTimeoutException exception) {
            plan = CompressionPlan.unavailable(
                    FailureReason.TIMED_OUT,
                    "读取压缩计划超过绝对截止时间");
        } catch (DependencyReadRejectedException exception) {
            plan = CompressionPlan.unavailable(
                    FailureReason.EXECUTOR_REJECTED,
                    "上下文记忆读取执行器已满");
        } catch (DependencyReadInterruptedException exception) {
            plan = CompressionPlan.unavailable(
                    FailureReason.INTERRUPTED,
                    "读取压缩计划被中断");
        }
        if (!plan.available()) {
            if (plan.failureReason() == FailureReason.NO_COMPRESSIBLE_TURN
                    && initialTokens < properties.getHardInputLimit()) {
                if (!tryCommitContinuation(continuationGate)) {
                    return turnTerminated(
                            ContextCompressionMode.ADMISSION_FAILED,
                            initialRequest, 0L, appId);
                }
                return success(ContextCompressionMode.NORMAL,
                        initialRequest, initialRequest, 0L,
                        "没有可压缩的旧完整回合，本次请求继续");
            }
            if (plan.failureReason() == FailureReason.NO_COMPRESSIBLE_TURN
                    && initialTokens >= properties.getHardInputLimit()) {
                return checkpointOrReject(
                        appId, memory, stableTools, stableTransientMessages,
                        initialRequest, initialRequest, 0L, continuationGate,
                        attemptState, null, transitionListener, deadline);
            }
            return failure(planningFailureMode(initialTokens),
                    initialRequest, initialRequest, 0L,
                    plan.failureReason(), plan.detail());
        }
        return blockAndRecheck(
                appId, memory, stableTools, stableTransientMessages,
                initialRequest, plan,
                transitionListener, continuationGate, deadline, attemptState);
    }

    private ContextAdmissionResult scheduleAsyncCompressionPlanning(
            long appId,
            CompressionAwareChatMemory memory,
            RequestSnapshot initialRequest,
            ContextContinuationGate continuationGate) {
        if (!tryCommitContinuation(continuationGate)) {
            return turnTerminated(ContextCompressionMode.ADMISSION_FAILED,
                    initialRequest, 0L, appId);
        }
        if (!isLifecycleOpen(appId)) {
            return failure(ContextCompressionMode.ADMISSION_FAILED,
                    initialRequest, initialRequest, 0L,
                    FailureReason.DELETE_REJECTED,
                    "应用删除流程已接管，appId=" + appId);
        }
        try {
            asyncPlanningExecutor.submit(() -> runAsyncCompressionPlanning(
                    appId, memory, continuationGate));
        } catch (RejectedExecutionException exception) {
            return success(ContextCompressionMode.NORMAL,
                    initialRequest, initialRequest, 0L,
                    "异步压缩计划执行器已满，本次继续");
        }
        return success(ContextCompressionMode.ASYNC_SCHEDULED,
                initialRequest, initialRequest, 0L,
                "已提交异步压缩计划");
    }

    private ContextAdmissionResult checkpointOrReject(
            long appId,
            CompressionAwareChatMemory memory,
            List<ToolSpecification> tools,
            List<ChatMessage> transientMessages,
            RequestSnapshot initialRequest,
            RequestSnapshot currentRequest,
            long summarizeThroughId,
            ContextContinuationGate continuationGate,
            ContextCompressionAttemptState attemptState,
            PreparedBlockingRequest blockingPreparation,
            Consumer<ContextAdmissionResult> transitionListener,
            AdmissionDeadline deadline) {
        MemoryCompressionMetricsCollector.CheckpointObservation observation =
                metricsCollector.startToolChainCheckpoint(
                        currentRequest.estimatedTokens());
        ContextAdmissionResult result = checkpointOrRejectObserved(
                appId, memory, tools, transientMessages,
                initialRequest, currentRequest, summarizeThroughId,
                continuationGate, attemptState, blockingPreparation,
                transitionListener, deadline);
        observation.complete(
                checkpointOutcome(result), result.finalTokens());
        return result;
    }

    private ContextAdmissionResult checkpointOrRejectObserved(
            long appId,
            CompressionAwareChatMemory memory,
            List<ToolSpecification> tools,
            List<ChatMessage> transientMessages,
            RequestSnapshot initialRequest,
            RequestSnapshot currentRequest,
            long summarizeThroughId,
            ContextContinuationGate continuationGate,
            ContextCompressionAttemptState attemptState,
            PreparedBlockingRequest blockingPreparation,
            Consumer<ContextAdmissionResult> transitionListener,
            AdmissionDeadline deadline) {
        ContextCompressionAttemptState.CheckpointClaim claim =
                attemptState.tryEnterCheckpointMode();
        ContextCompressionAttemptState.EnterDecision enterDecision =
                claim.decision();
        if (enterDecision == ContextCompressionAttemptState.EnterDecision
                .ALREADY_FAILED
                || enterDecision == ContextCompressionAttemptState
                .EnterDecision.IN_PROGRESS) {
            return failure(ContextCompressionMode.HARD_LIMIT_REJECTED,
                    initialRequest, currentRequest, summarizeThroughId,
                    FailureReason.CHECKPOINT_ALREADY_ATTEMPTED,
                    enterDecision == ContextCompressionAttemptState
                            .EnterDecision.IN_PROGRESS
                            ? "本回合工具链检查点正在构建"
                            : "本回合工具链检查点已失败，禁止递归重试");
        }
        try {
            if (!tryCommitContinuation(continuationGate)) {
                attemptState.markCheckpointFailed(claim);
                return checkpointTurnTerminated(
                        initialRequest, currentRequest,
                        summarizeThroughId, appId);
            }
            if (enterDecision == ContextCompressionAttemptState.EnterDecision
                    .FIRST_ENTRY && blockingPreparation == null
                    && !continuationGate.tryRun(() -> transitionListener.accept(
                    success(ContextCompressionMode
                                    .TOOL_CHAIN_CHECKPOINT_STARTED,
                            initialRequest, currentRequest, summarizeThroughId,
                            "开始生成未完成工具链检查点")))) {
                attemptState.markCheckpointFailed(claim);
                return checkpointTurnTerminated(
                        initialRequest, currentRequest,
                        summarizeThroughId, appId);
            }
            CheckpointPreparation preparation = prepareCheckpointRequest(
                    memory, tools, transientMessages,
                    blockingPreparation, deadline);
            if (!preparation.complete()) {
                attemptState.markCheckpointFailed(claim);
                return failure(ContextCompressionMode.HARD_LIMIT_REJECTED,
                        initialRequest, currentRequest, summarizeThroughId,
                        preparation.failureReason(), preparation.detail());
            }
            AtomicReference<ContextAdmissionResult> committed =
                    new AtomicReference<>();
            boolean accepted = continuationGate.tryRun(() -> committed.set(
                    commitCheckpoint(
                            appId, memory, initialRequest, currentRequest,
                            summarizeThroughId, preparation,
                            blockingPreparation, deadline,
                            attemptState, claim)));
            if (!accepted) {
                attemptState.markCheckpointFailed(claim);
                return checkpointTurnTerminated(
                        initialRequest, currentRequest,
                        summarizeThroughId, appId);
            }
            return requireCommitted(committed, "工具链检查点提交");
        } catch (RuntimeException exception) {
            attemptState.markCheckpointFailed(claim);
            return failure(ContextCompressionMode.HARD_LIMIT_REJECTED,
                    initialRequest, currentRequest, summarizeThroughId,
                    FailureReason.DEPENDENCY_FAILED,
                    "生成工具链检查点依赖异常，type="
                            + exception.getClass().getSimpleName());
        }
    }

    private MemoryCompressionMetricsCollector.CheckpointOutcome checkpointOutcome(
            ContextAdmissionResult result) {
        if (result.failureReason()
                == FailureReason.CHECKPOINT_ALREADY_ATTEMPTED) {
            return MemoryCompressionMetricsCollector.CheckpointOutcome
                    .ALREADY_ATTEMPTED;
        }
        if (result.failureReason() == FailureReason.NO_COMPRESSIBLE_TURN) {
            return MemoryCompressionMetricsCollector.CheckpointOutcome
                    .NO_UNFINISHED_TAIL;
        }
        if (result.canProceed()
                && (result.mode() == ContextCompressionMode
                .TOOL_CHAIN_CHECKPOINT_COMPLETED
                || result.mode() == ContextCompressionMode
                .TOOL_CHAIN_CHECKPOINT_REBUILT)) {
            return MemoryCompressionMetricsCollector.CheckpointOutcome.SUCCESS;
        }
        return MemoryCompressionMetricsCollector.CheckpointOutcome.FAILED;
    }

    private CheckpointPreparation prepareCheckpointRequest(
            CompressionAwareChatMemory memory,
            List<ToolSpecification> tools,
            List<ChatMessage> transientMessages,
            PreparedBlockingRequest blockingPreparation,
            AdmissionDeadline deadline) {
        if (deadline.remainingNanos() <= 0L) {
            return CheckpointPreparation.failed(
                    FailureReason.TIMED_OUT,
                    "准备工具链检查点前截止时间已到");
        }
        List<ChatMessage> currentMessages;
        try {
            currentMessages = blockingPreparation == null
                    ? List.copyOf(memory.messages())
                    : blockingPreparation.messages().requestMessages();
        } catch (RuntimeException exception) {
            return CheckpointPreparation.failed(
                    FailureReason.DEPENDENCY_FAILED,
                    "读取最新活动记忆失败，无法生成工具链检查点");
        }
        ConversationTurnSnapshotParser.Snapshot snapshot =
                snapshotParser.parse(currentMessages);
        if (!snapshot.hasUnfinishedTail()) {
            return CheckpointPreparation.failed(
                    FailureReason.NO_COMPRESSIBLE_TURN,
                    "当前请求达到输入硬上限，但没有可安全压缩的未完成工具链");
        }
        ToolChainCheckpointResult projection = checkpointProjector.project(
                snapshot, registeredToolNames(tools));
        if (!projection.complete()) {
            return CheckpointPreparation.failed(
                    FailureReason.INVALID_TOOL_CHAIN_CHECKPOINT,
                    "未完成工具链不满足可信检查点协议，reason="
                            + projection.failureReason().name());
        }
        List<ChatMessage> baseProjectedMessages = replaceUnfinishedTail(
                currentMessages, snapshot.unfinishedTail(),
                projection.messagesWithoutLatestReadBatch());
        if (deadline.remainingNanos() <= 0L) {
            return CheckpointPreparation.failed(
                    FailureReason.TIMED_OUT,
                    "生成工具链检查点投影时超过截止时间");
        }
        RequestSnapshot baseProjectedRequest;
        try {
            baseProjectedRequest = requestSnapshot(
                    baseProjectedMessages, tools, transientMessages);
        } catch (RuntimeException exception) {
            return CheckpointPreparation.failed(
                    FailureReason.DEPENDENCY_FAILED,
                    "估算工具链检查点请求失败");
        }
        if (deadline.remainingNanos() <= 0L) {
            return CheckpointPreparation.failed(
                    FailureReason.TIMED_OUT,
                    "估算工具链检查点请求时超过截止时间");
        }
        if (baseProjectedRequest.estimatedTokens()
                >= properties.getHardInputLimit()) {
            return CheckpointPreparation.failed(
                    FailureReason.STILL_OVER_HARD_LIMIT,
                    "工具链检查点压缩后仍达到输入硬上限");
        }
        RequestSnapshot projectedRequest = baseProjectedRequest;
        if (!projection.latestReadBatch().isEmpty()) {
            List<ChatMessage> withLatestReadMessages = replaceUnfinishedTail(
                    currentMessages, snapshot.unfinishedTail(),
                    projection.requestMessages());
            try {
                RequestSnapshot withLatestReadRequest = requestSnapshot(
                        withLatestReadMessages, tools, transientMessages);
                if (withLatestReadRequest.estimatedTokens()
                        < properties.getHardInputLimit()) {
                    projectedRequest = withLatestReadRequest;
                } else if (projection.latestReadBatchContainsReadDir()) {
                    return CheckpointPreparation.failed(
                            FailureReason.STILL_OVER_HARD_LIMIT,
                            "最新readDir完整结果无法在输入硬上限内保留");
                }
            } catch (RuntimeException exception) {
                return CheckpointPreparation.failed(
                        FailureReason.DEPENDENCY_FAILED,
                        "估算最新读取批次失败");
            }
        }
        metricsCollector.recordEstimatedTokens(
                MemoryCompressionMetricsCollector.EstimationStage.AFTER,
                projectedRequest.estimatedTokens());
        return CheckpointPreparation.completed(
                projectedRequest, currentMessages);
    }

    private ContextAdmissionResult commitCheckpoint(
            long appId,
            CompressionAwareChatMemory memory,
            RequestSnapshot initialRequest,
            RequestSnapshot currentRequest,
            long summarizeThroughId,
            CheckpointPreparation preparation,
            PreparedBlockingRequest blockingPreparation,
            AdmissionDeadline deadline,
            ContextCompressionAttemptState attemptState,
            ContextCompressionAttemptState.CheckpointClaim claim) {
        if (deadline.remainingNanos() <= 0L) {
            attemptState.markCheckpointFailed(claim);
            return checkpointTimedOut(
                    initialRequest, currentRequest,
                    summarizeThroughId,
                    "提交工具链检查点前截止时间已到");
        }
        AppDataLifecycleFence.WriterPermit writerPermit =
                tryAcquireLifecycleWriter(appId);
        if (writerPermit == null) {
            attemptState.markCheckpointFailed(claim);
            return failure(ContextCompressionMode.HARD_LIMIT_REJECTED,
                    initialRequest, currentRequest, summarizeThroughId,
                    FailureReason.DELETE_REJECTED,
                    "应用删除流程已接管，appId=" + appId);
        }
        try (writerPermit) {
            if (deadline.remainingNanos() <= 0L) {
                attemptState.markCheckpointFailed(claim);
                return checkpointTimedOut(
                        initialRequest, currentRequest,
                        summarizeThroughId,
                        "获取工具链检查点提交许可时超时");
            }
            ContextAdmissionResult consistencyFailure =
                    verifyAndApplyCheckpointBase(
                            memory, initialRequest, currentRequest,
                            summarizeThroughId, preparation,
                            blockingPreparation, deadline);
            if (consistencyFailure != null) {
                attemptState.markCheckpointFailed(claim);
                return consistencyFailure;
            }
            if (deadline.remainingNanos() <= 0L) {
                attemptState.markCheckpointFailed(claim);
                return checkpointTimedOut(
                        initialRequest, currentRequest,
                        summarizeThroughId,
                        "复检工具链检查点请求时超过截止时间");
            }
            if (!attemptState.markCheckpointReady(claim)) {
                return failure(ContextCompressionMode.HARD_LIMIT_REJECTED,
                        initialRequest, currentRequest, summarizeThroughId,
                        FailureReason.CHECKPOINT_ALREADY_ATTEMPTED,
                        "工具链检查点owner已失效");
            }
            return success(
                    claim.decision() == ContextCompressionAttemptState
                            .EnterDecision.FIRST_ENTRY
                            ? ContextCompressionMode
                            .TOOL_CHAIN_CHECKPOINT_COMPLETED
                            : ContextCompressionMode
                            .TOOL_CHAIN_CHECKPOINT_REBUILT,
                    initialRequest, preparation.request(),
                    summarizeThroughId, "未完成工具链检查点已生成");
        }
    }

    private ContextAdmissionResult verifyAndApplyCheckpointBase(
            CompressionAwareChatMemory memory,
            RequestSnapshot initialRequest,
            RequestSnapshot currentRequest,
            long summarizeThroughId,
            CheckpointPreparation preparation,
            PreparedBlockingRequest blockingPreparation,
            AdmissionDeadline deadline) {
        if (blockingPreparation != null) {
            DeadlineAwareReplaceResult replaceResult =
                    memory.applyPreparedPrefix(
                            blockingPreparation.messages(), deadline);
            if (replaceResult == DeadlineAwareReplaceResult.REPLACED) {
                return null;
            }
            return checkpointReplaceFailure(
                    replaceResult, initialRequest, currentRequest,
                    summarizeThroughId);
        }
        List<ChatMessage> latestMessages;
        try {
            latestMessages = List.copyOf(memory.messages());
        } catch (RuntimeException exception) {
            return failure(ContextCompressionMode.HARD_LIMIT_REJECTED,
                    initialRequest, currentRequest, summarizeThroughId,
                    FailureReason.DEPENDENCY_FAILED,
                    "复检最新活动记忆失败");
        }
        if (!latestMessages.equals(preparation.expectedMessages())) {
            return failure(ContextCompressionMode.HARD_LIMIT_REJECTED,
                    initialRequest, currentRequest, summarizeThroughId,
                    FailureReason.PREFIX_CHANGED,
                    "检查点准备后活动记忆已变化");
        }
        return null;
    }

    private ContextAdmissionResult checkpointReplaceFailure(
            DeadlineAwareReplaceResult replaceResult,
            RequestSnapshot initialRequest,
            RequestSnapshot currentRequest,
            long summarizeThroughId) {
        return switch (replaceResult) {
            case PREFIX_CHANGED -> failure(
                    ContextCompressionMode.HARD_LIMIT_REJECTED,
                    initialRequest, currentRequest, summarizeThroughId,
                    FailureReason.PREFIX_CHANGED,
                    "提交可靠L1前L0旧前缀已变化");
            case TIMED_OUT -> failure(
                    ContextCompressionMode.HARD_LIMIT_REJECTED,
                    initialRequest, currentRequest, summarizeThroughId,
                    FailureReason.TIMED_OUT,
                    "提交可靠L1超过绝对截止时间");
            case INTERRUPTED -> failure(
                    ContextCompressionMode.HARD_LIMIT_REJECTED,
                    initialRequest, currentRequest, summarizeThroughId,
                    FailureReason.INTERRUPTED,
                    "提交可靠L1被中断");
            case DEPENDENCY_FAILED -> failure(
                    ContextCompressionMode.HARD_LIMIT_REJECTED,
                    initialRequest, currentRequest, summarizeThroughId,
                    FailureReason.DEPENDENCY_FAILED,
                    "提交可靠L1依赖失败");
            case REPLACED -> throw new IllegalStateException(
                    "成功替换不应进入失败映射");
        };
    }

    private ContextAdmissionResult checkpointTurnTerminated(
            RequestSnapshot initialRequest,
            RequestSnapshot currentRequest,
            long summarizeThroughId,
            long appId) {
        return failure(ContextCompressionMode.HARD_LIMIT_REJECTED,
                initialRequest, currentRequest, summarizeThroughId,
                FailureReason.TURN_TERMINATED,
                "回合已取消或终态已被占用，appId=" + appId);
    }

    private ContextAdmissionResult checkpointTimedOut(
            RequestSnapshot initialRequest,
            RequestSnapshot currentRequest,
            long summarizeThroughId,
            String detail) {
        return failure(ContextCompressionMode.HARD_LIMIT_REJECTED,
                initialRequest, currentRequest, summarizeThroughId,
                FailureReason.TIMED_OUT, detail);
    }

    private Set<String> registeredToolNames(List<ToolSpecification> tools) {
        return tools.stream().map(ToolSpecification::name)
                .collect(Collectors.toUnmodifiableSet());
    }

    private List<ChatMessage> replaceUnfinishedTail(
            List<ChatMessage> currentMessages,
            List<ChatMessage> unfinishedTail,
            List<ChatMessage> replacement) {
        int start = currentMessages.size() - unfinishedTail.size();
        if (start < 0 || !currentMessages.subList(
                start, currentMessages.size()).equals(unfinishedTail)) {
            throw new MemoryPrefixChangedException("未完成工具链已变化");
        }
        List<ChatMessage> projected = new java.util.ArrayList<>(
                start + replacement.size());
        projected.addAll(currentMessages.subList(0, start));
        projected.addAll(replacement);
        return List.copyOf(projected);
    }

    private void runAsyncCompressionPlanning(
            long appId,
            CompressionAwareChatMemory memory,
            ContextContinuationGate continuationGate) {
        if (!tryCommitContinuation(continuationGate)
                || !isLifecycleOpen(appId)) {
            return;
        }
        CompressionPlan plan = buildPlan(appId, memory);
        if (!plan.available()
                || !tryCommitContinuation(continuationGate)
                || !isLifecycleOpen(appId)) {
            return;
        }
        continuationGate.tryRun(() -> summaryService.triggerSummarizationAsync(
                appId, plan.summarizeThroughId(),
                () -> tryCommitContinuation(continuationGate)));
    }

    private ContextAdmissionResult blockAndRecheck(
            long appId,
            CompressionAwareChatMemory memory,
            List<ToolSpecification> tools,
            List<ChatMessage> transientMessages,
            RequestSnapshot initialRequest,
            CompressionPlan plan,
            Consumer<ContextAdmissionResult> transitionListener,
            ContextContinuationGate continuationGate,
            AdmissionDeadline deadline,
            ContextCompressionAttemptState attemptState) {
        if (!tryCommitContinuation(continuationGate)) {
            return turnTerminated(ContextCompressionMode.BLOCKING_FAILED,
                    initialRequest, plan.summarizeThroughId(), appId);
        }
        if (!isLifecycleOpen(appId)) {
            return failure(ContextCompressionMode.BLOCKING_FAILED,
                    initialRequest, initialRequest,
                    plan.summarizeThroughId(),
                    FailureReason.DELETE_REJECTED,
                    "应用删除流程已接管，appId=" + appId);
        }
        if (!continuationGate.tryRun(() -> transitionListener.accept(success(
                ContextCompressionMode.BLOCKING_STARTED,
                initialRequest, initialRequest,
                plan.summarizeThroughId(), "开始阻塞压缩")))) {
            return turnTerminated(ContextCompressionMode.BLOCKING_FAILED,
                    initialRequest, plan.summarizeThroughId(), appId);
        }
        if (deadline.remainingNanos() <= 0L) {
            return blockingFailure(initialRequest,
                    plan.summarizeThroughId(), FailureReason.TIMED_OUT,
                    "上下文压缩绝对截止时间已到");
        }
        Future<BlockingCompressionExecution> future;
        try {
            future = compressionExecutor.submit(() -> {
                // 这里是 worker 启动的线性化点；压缩等待仍在票据外完成。
                if (!tryCommitContinuation(continuationGate)) {
                    return BlockingCompressionExecution.terminatedExecution();
                }
                return BlockingCompressionExecution.completed(
                        summaryService.compressNow(
                                appId, plan.summarizeThroughId(),
                                deadline.remainingDuration()));
            });
        } catch (RejectedExecutionException exception) {
            metricsCollector.recordCompressionExecutorRejected(
                    MemoryCompressionMetricsCollector.CompressionMode.BLOCKING);
            return blockingFailure(initialRequest,
                    plan.summarizeThroughId(),
                    FailureReason.EXECUTOR_REJECTED,
                    "上下文压缩执行器已满，appId=" + appId);
        }
        BlockingCompressionExecution execution;
        try {
            long remainingNanos = deadline.remainingNanos();
            if (remainingNanos <= 0L) {
                future.cancel(true);
                return blockingFailure(initialRequest,
                        plan.summarizeThroughId(), FailureReason.TIMED_OUT,
                        "上下文压缩排队后绝对截止时间已到");
            }
            execution = future.get(
                    remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            return blockingFailure(initialRequest,
                    plan.summarizeThroughId(), FailureReason.TIMED_OUT,
                    "等待上下文压缩超时");
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return blockingFailure(initialRequest,
                    plan.summarizeThroughId(), FailureReason.INTERRUPTED,
                    "等待上下文压缩被中断");
        } catch (ExecutionException exception) {
            return blockingFailure(initialRequest,
                    plan.summarizeThroughId(), FailureReason.MODEL_FAILED,
                    "上下文压缩任务异常");
        }
        if (execution.terminated()) {
            return turnTerminated(ContextCompressionMode.BLOCKING_FAILED,
                    initialRequest, plan.summarizeThroughId(), appId);
        }
        MemoryCompressionResult compressionResult = execution.result();
        if (deadline.remainingNanos() <= 0L) {
            future.cancel(true);
            return blockingFailure(initialRequest,
                    plan.summarizeThroughId(), FailureReason.TIMED_OUT,
                    "上下文压缩完成时已超过绝对截止时间");
        }
        if (!tryCommitContinuation(continuationGate)) {
            return turnTerminated(ContextCompressionMode.BLOCKING_FAILED,
                    initialRequest, plan.summarizeThroughId(), appId);
        }
        if (!isCompressionSuccess(compressionResult)
                || compressionResult.summarizedThroughId()
                < plan.summarizeThroughId()) {
            return blockingFailure(initialRequest,
                    plan.summarizeThroughId(),
                    failureReason(compressionResult),
                    compressionFailureDetail(appId, compressionResult));
        }
        PreparedBlockingRequest prepared;
        try {
            prepared = readWithinDeadline(
                    () -> prepareBlockingRequest(
                            appId, memory, tools, transientMessages,
                            initialRequest,
                            plan, deadline), deadline);
        } catch (DependencyReadTimeoutException exception) {
            return blockingFailure(initialRequest,
                    plan.summarizeThroughId(), FailureReason.TIMED_OUT,
                    "读取可靠 L1 或最终记忆超过绝对截止时间");
        } catch (DependencyReadRejectedException exception) {
            return blockingFailure(initialRequest,
                    plan.summarizeThroughId(), FailureReason.EXECUTOR_REJECTED,
                    "上下文记忆读取执行器已满");
        } catch (DependencyReadInterruptedException exception) {
            return blockingFailure(initialRequest,
                    plan.summarizeThroughId(), FailureReason.INTERRUPTED,
                    "读取可靠 L1 或最终记忆被中断");
        }
        if (prepared.failure() != null) {
            return prepared.failure();
        }
        if (prepared.request().estimatedTokens()
                >= properties.getHardInputLimit()) {
            return checkpointOrReject(
                    appId, memory, tools, transientMessages,
                    initialRequest, prepared.request(),
                    plan.summarizeThroughId(), continuationGate,
                    attemptState, prepared, transitionListener, deadline);
        }
        return commitPreparedBlockingRequest(
                appId, memory, initialRequest, plan,
                continuationGate, deadline, prepared);
    }

    private CompressionPlan buildPlan(
            long appId, CompressionAwareChatMemory memory) {
        Alignment alignment = readAlignment(appId, memory);
        if (!alignment.aligned()) {
            return CompressionPlan.unavailable(
                    alignment.failureReason(), alignment.detail());
        }
        ConversationTurnSnapshotParser.Snapshot snapshot =
                alignment.snapshot();
        List<ConversationTurnSnapshotParser.CompletedTurn> l0Turns =
                snapshot.completedTurns();
        if (l0Turns.isEmpty()) {
            return CompressionPlan.unavailable(
                    FailureReason.NO_COMPRESSIBLE_TURN,
                    "L0 没有可压缩的稳定完整回合");
        }
        List<ChatHistoryService.StableTurnBoundary> boundaries =
                alignment.boundaries();
        List<ConversationTurn> turns = java.util.stream.IntStream
                .range(0, l0Turns.size())
                .mapToObj(index -> toConversationTurn(
                        l0Turns.get(index), boundaries.get(index)))
                .toList();
        RetentionSelection selection = turnSelector.select(
                turns, properties.getL0RetainedTokens());
        if (selection.compressible().isEmpty()) {
            return CompressionPlan.unavailable(
                    FailureReason.NO_COMPRESSIBLE_TURN,
                    "没有可压缩的旧完整回合");
        }
        List<ChatMessage> expectedPrefix = selection.compressible().stream()
                .flatMap(turn -> turn.messages().stream())
                .toList();
        return CompressionPlan.available(
                selection.summarizeThroughId(), expectedPrefix);
    }

    private InitialRequestPreparation prepareInitialRequest(
            long appId,
            CompressionAwareChatMemory memory,
            List<ToolSpecification> tools,
            List<ChatMessage> transientMessages,
            ContextContinuationGate continuationGate) {
        long lastSummarizedId;
        try {
            lastSummarizedId = summaryService.lastSummarizedId(appId);
        } catch (RuntimeException exception) {
            return InitialRequestPreparation.failed(EMPTY_REQUEST_SNAPSHOT,
                    FailureReason.CURSOR_READ_FAILED,
                    "读取 L1 摘要游标失败，appId=" + appId
                            + "，type="
                            + exception.getClass().getSimpleName());
        }
        if (!tryCommitContinuation(continuationGate)) {
            return InitialRequestPreparation.failed(EMPTY_REQUEST_SNAPSHOT,
                    FailureReason.TURN_TERMINATED,
                    "回合已取消或终态已被占用，appId=" + appId);
        }
        if (lastSummarizedId <= 0L) {
            try {
                return InitialRequestPreparation.success(
                        null, captureRequestSnapshot(
                                memory, tools, transientMessages));
            } catch (RuntimeException exception) {
                return InitialRequestPreparation.failed(
                        EMPTY_REQUEST_SNAPSHOT,
                        FailureReason.DEPENDENCY_FAILED,
                        "读取初始请求快照失败，type="
                                + exception.getClass().getSimpleName());
            }
        }
        Alignment alignment = readAlignment(appId, memory);
        if (!alignment.aligned()) {
            return InitialRequestPreparation.failed(EMPTY_REQUEST_SNAPSHOT,
                    alignment.failureReason(), alignment.detail());
        }
        int coveredTurns = 0;
        for (ChatHistoryService.StableTurnBoundary boundary
                : alignment.boundaries()) {
            if (boundary.completedThroughId() > lastSummarizedId) {
                break;
            }
            coveredTurns++;
        }
        List<ChatMessage> expectedPrefix = alignment.snapshot()
                .completedTurns().subList(0, coveredTurns).stream()
                .flatMap(turn -> turn.messages().stream())
                .toList();
        String summary;
        try {
            summary = summaryService.getRequiredSummary(
                    appId, lastSummarizedId);
        } catch (RuntimeException exception) {
            return InitialRequestPreparation.failed(EMPTY_REQUEST_SNAPSHOT,
                    FailureReason.DEPENDENCY_FAILED,
                    "读取可靠 L1 失败，type="
                            + exception.getClass().getSimpleName());
        }
        if (!isStrictSummary(summary)) {
            return InitialRequestPreparation.failed(EMPTY_REQUEST_SNAPSHOT,
                    FailureReason.SUMMARY_READ_FAILED,
                    "L1 摘要不符合严格召回契约");
        }
        try {
            LayeredChatMemory.PreparedLayeredMessages prepared =
                    memory.prepareAfterCompletedPrefix(
                            expectedPrefix, summary);
            RequestSnapshot request = requestSnapshot(
                    prepared.requestMessages(), tools, transientMessages);
            return InitialRequestPreparation.success(prepared, request);
        } catch (MemoryPrefixChangedException exception) {
            return InitialRequestPreparation.failed(EMPTY_REQUEST_SNAPSHOT,
                    FailureReason.PREFIX_CHANGED,
                    "无法构造包含可靠 L1 的初始请求");
        } catch (RuntimeException exception) {
            return InitialRequestPreparation.failed(EMPTY_REQUEST_SNAPSHOT,
                    FailureReason.DEPENDENCY_FAILED,
                    "读取初始分层记忆失败，type="
                            + exception.getClass().getSimpleName());
        }
    }

    private ContextAdmissionResult commitInitialPreparation(
            long appId,
            CompressionAwareChatMemory memory,
            ContextContinuationGate continuationGate,
            AdmissionDeadline deadline,
            InitialRequestPreparation preparation) {
        if (deadline.remainingNanos() <= 0L) {
            return failure(ContextCompressionMode.ADMISSION_FAILED,
                    preparation.request(), preparation.request(),
                    0L, FailureReason.TIMED_OUT,
                    "提交初始上下文前截止时间已到");
        }
        if (preparation.messages() == null) {
            return tryCommitContinuation(continuationGate)
                    ? null : turnTerminated(
                    ContextCompressionMode.ADMISSION_FAILED,
                    preparation.request(), 0L, appId);
        }
        AtomicReference<ContextAdmissionResult> failure =
                new AtomicReference<>();
        boolean accepted = continuationGate.tryRun(() -> {
            if (deadline.remainingNanos() <= 0L) {
                failure.set(failure(
                        ContextCompressionMode.ADMISSION_FAILED,
                        preparation.request(), preparation.request(),
                        0L, FailureReason.TIMED_OUT,
                        "提交初始上下文前截止时间已到"));
                return;
            }
            failure.set(applyInitialPreparation(
                    appId, memory, preparation, deadline));
        });
        if (!accepted) {
            return turnTerminated(ContextCompressionMode.ADMISSION_FAILED,
                    preparation.request(), 0L, appId);
        }
        return failure.get();
    }

    private ContextAdmissionResult applyInitialPreparation(
            long appId,
            CompressionAwareChatMemory memory,
            InitialRequestPreparation preparation,
            AdmissionDeadline deadline) {
        AppDataLifecycleFence.WriterPermit writerPermit =
                tryAcquireLifecycleWriter(appId);
        if (writerPermit == null) {
            return failure(ContextCompressionMode.ADMISSION_FAILED,
                    preparation.request(), preparation.request(),
                    0L,
                    FailureReason.DELETE_REJECTED,
                    "应用删除流程已接管，appId=" + appId);
        }
        try (writerPermit) {
            DeadlineAwareReplaceResult result = memory.applyPreparedPrefix(
                    preparation.messages(), deadline);
            return initialReplaceFailure(result, preparation.request());
        }
    }

    private PreparedBlockingRequest prepareBlockingRequest(
            long appId,
            CompressionAwareChatMemory memory,
            List<ToolSpecification> tools,
            List<ChatMessage> transientMessages,
            RequestSnapshot initialRequest,
            CompressionPlan plan,
            AdmissionDeadline deadline) {
        if (deadline.remainingNanos() <= 0L) {
            return PreparedBlockingRequest.failed(blockingFailure(
                    initialRequest, plan.summarizeThroughId(),
                    FailureReason.TIMED_OUT,
                    "准备阻塞压缩最终请求前截止时间已到"));
        }
        try {
            String summary = summaryService.getRequiredSummary(
                    appId, plan.summarizeThroughId());
            if (!isStrictSummary(summary)) {
                return PreparedBlockingRequest.failed(blockingFailure(
                        initialRequest, plan.summarizeThroughId(),
                        FailureReason.SUMMARY_READ_FAILED,
                        "L1 摘要不符合严格召回契约"));
            }
            LayeredChatMemory.PreparedLayeredMessages prepared =
                    memory.prepareAfterCompletedPrefix(
                            plan.expectedPrefix(), summary);
            RequestSnapshot finalRequest = requestSnapshot(
                    prepared.requestMessages(), tools, transientMessages);
            metricsCollector.recordEstimatedTokens(
                    MemoryCompressionMetricsCollector.EstimationStage.AFTER,
                    finalRequest.estimatedTokens());
            if (deadline.remainingNanos() <= 0L) {
                return PreparedBlockingRequest.failed(blockingFailure(
                        initialRequest, plan.summarizeThroughId(),
                        FailureReason.TIMED_OUT,
                        "准备阻塞压缩最终请求时截止时间已到"));
            }
            return PreparedBlockingRequest.success(prepared, finalRequest);
        } catch (MemoryPrefixChangedException exception) {
            return PreparedBlockingRequest.failed(blockingFailure(
                    initialRequest, plan.summarizeThroughId(),
                    FailureReason.PREFIX_CHANGED,
                    "无法构造包含可靠 L1 的最终请求"));
        } catch (RuntimeException exception) {
            return PreparedBlockingRequest.failed(blockingFailure(
                initialRequest, plan.summarizeThroughId(),
                    FailureReason.DEPENDENCY_FAILED,
                    "读取可靠 L1 或最终记忆失败"));
        }
    }

    private ContextAdmissionResult commitPreparedBlockingRequest(
            long appId,
            CompressionAwareChatMemory memory,
            RequestSnapshot initialRequest,
            CompressionPlan plan,
            ContextContinuationGate continuationGate,
            AdmissionDeadline deadline,
            PreparedBlockingRequest prepared) {
        AtomicReference<ContextAdmissionResult> committed =
                new AtomicReference<>();
        boolean accepted = continuationGate.tryRun(() -> committed.set(
                applyPreparedBlockingRequest(
                        appId, memory, initialRequest, plan,
                        deadline, prepared)));
        if (!accepted) {
            return turnTerminated(ContextCompressionMode.BLOCKING_FAILED,
                    initialRequest, plan.summarizeThroughId(), appId);
        }
        return requireCommitted(committed, "阻塞压缩完成提交");
    }

    private ContextAdmissionResult applyPreparedBlockingRequest(
            long appId,
            CompressionAwareChatMemory memory,
            RequestSnapshot initialRequest,
            CompressionPlan plan,
            AdmissionDeadline deadline,
            PreparedBlockingRequest prepared) {
        if (deadline.remainingNanos() <= 0L) {
            return blockingFailure(initialRequest,
                    plan.summarizeThroughId(), FailureReason.TIMED_OUT,
                    "应用阻塞压缩结果前截止时间已到");
        }
        AppDataLifecycleFence.WriterPermit writerPermit =
                tryAcquireLifecycleWriter(appId);
        if (writerPermit == null) {
            return failure(ContextCompressionMode.BLOCKING_FAILED,
                    initialRequest, initialRequest,
                    plan.summarizeThroughId(),
                    FailureReason.DELETE_REJECTED,
                    "应用删除流程已接管，appId=" + appId);
        }
        try (writerPermit) {
            if (deadline.remainingNanos() <= 0L) {
                return blockingFailure(initialRequest,
                        plan.summarizeThroughId(), FailureReason.TIMED_OUT,
                        "应用阻塞压缩结果前截止时间已到");
            }
            DeadlineAwareReplaceResult result = memory.applyPreparedPrefix(
                    prepared.messages(), deadline);
            return blockingReplaceResult(
                    result, initialRequest, plan, prepared);
        }
    }

    private boolean isStrictSummary(String summary) {
        return summary != null
                && !summary.isBlank()
                && MemorySummaryContract.isRecallable(
                summary, tokenEstimator);
    }

    private ContextAdmissionResult initialReplaceFailure(
            DeadlineAwareReplaceResult result,
            RequestSnapshot request) {
        return switch (result) {
            case REPLACED -> null;
            case PREFIX_CHANGED -> failure(
                    ContextCompressionMode.ADMISSION_FAILED,
                    request, request,
                    0L, FailureReason.PREFIX_CHANGED,
                    "L0 旧前缀已变化，本次不裁剪");
            case TIMED_OUT -> failure(
                    ContextCompressionMode.ADMISSION_FAILED,
                    request, request,
                    0L, FailureReason.TIMED_OUT,
                    "提交初始 L0 裁剪超过绝对截止");
            case INTERRUPTED -> failure(
                    ContextCompressionMode.ADMISSION_FAILED,
                    request, request,
                    0L, FailureReason.INTERRUPTED,
                    "提交初始 L0 裁剪被中断");
            case DEPENDENCY_FAILED -> failure(
                    ContextCompressionMode.ADMISSION_FAILED,
                    request, request,
                    0L, FailureReason.DEPENDENCY_FAILED,
                    "提交初始 L0 裁剪依赖失败");
        };
    }

    private ContextAdmissionResult blockingReplaceResult(
            DeadlineAwareReplaceResult result,
            RequestSnapshot initialRequest,
            CompressionPlan plan,
            PreparedBlockingRequest prepared) {
        return switch (result) {
            case REPLACED -> success(
                    ContextCompressionMode.BLOCKING_COMPLETED,
                    initialRequest, prepared.request(),
                    plan.summarizeThroughId(), "阻塞压缩完成");
            case PREFIX_CHANGED -> blockingFailure(
                    initialRequest, plan.summarizeThroughId(),
                    FailureReason.PREFIX_CHANGED,
                    "L0 旧前缀已变化，本次不裁剪");
            case TIMED_OUT -> blockingFailure(
                    initialRequest, plan.summarizeThroughId(),
                    FailureReason.TIMED_OUT,
                    "提交最终 L0 裁剪超过绝对截止");
            case INTERRUPTED -> blockingFailure(
                    initialRequest, plan.summarizeThroughId(),
                    FailureReason.INTERRUPTED,
                    "提交最终 L0 裁剪被中断");
            case DEPENDENCY_FAILED -> blockingFailure(
                    initialRequest, plan.summarizeThroughId(),
                    FailureReason.DEPENDENCY_FAILED,
                    "提交最终 L0 裁剪依赖失败");
        };
    }

    private <T> T readWithinDeadline(
            Callable<T> action, AdmissionDeadline deadline) {
        long remaining = deadline.remainingNanos();
        if (remaining <= 0L) {
            throw new DependencyReadTimeoutException();
        }
        Future<T> future;
        try {
            future = memoryReadExecutor.submit(action);
        } catch (RejectedExecutionException exception) {
            throw new DependencyReadRejectedException();
        }
        try {
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new DependencyReadTimeoutException();
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new DependencyReadInterruptedException();
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("读取上下文依赖失败", cause);
        }
    }

    private boolean tryCommitContinuation(
            ContextContinuationGate continuationGate) {
        return continuationGate.tryRun(() -> { });
    }

    private <T> T requireCommitted(
            AtomicReference<T> committed, String actionName) {
        T result = committed.get();
        if (result == null) {
            throw new IllegalStateException(
                    actionName + "提交门返回成功但没有执行动作");
        }
        return result;
    }

    private ContextAdmissionResult turnTerminated(
            ContextCompressionMode mode,
            RequestSnapshot requestSnapshot,
            long summarizeThroughId,
            long appId) {
        return failure(mode, requestSnapshot, requestSnapshot,
                summarizeThroughId, FailureReason.TURN_TERMINATED,
                "回合已取消或终态已被占用，appId=" + appId);
    }

    private boolean isLifecycleOpen(long appId) {
        AppDataLifecycleFence.WriterPermit writerPermit =
                tryAcquireLifecycleWriter(appId);
        if (writerPermit == null) {
            return false;
        }
        try (writerPermit) {
            return true;
        }
    }

    private AppDataLifecycleFence.WriterPermit tryAcquireLifecycleWriter(
            long appId) {
        try {
            return lifecycleFence.tryAcquireWriter(appId);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private Alignment readAlignment(
            long appId, CompressionAwareChatMemory memory) {
        ConversationTurnSnapshotParser.Snapshot snapshot =
                memory.completeTurnSnapshot();
        int turnCount = snapshot.completedTurns().size();
        if (turnCount == 0) {
            return Alignment.success(snapshot, List.of());
        }
        List<ChatHistoryService.StableTurnBoundary> boundaries;
        try {
            boundaries = chatHistoryService
                    .listRecentCompleteTurnBoundaries(appId, turnCount);
        } catch (RuntimeException exception) {
            return Alignment.failed(
                    FailureReason.DEPENDENCY_FAILED,
                    "读取 MySQL 稳定回合边界失败，type="
                            + exception.getClass().getSimpleName());
        }
        if (!isAligned(snapshot.completedTurns(), boundaries)) {
            return Alignment.failed(
                    FailureReason.ALIGNMENT_FAILED,
                    "L0 与 MySQL 稳定回合无法一一对齐");
        }
        return Alignment.success(snapshot, boundaries);
    }

    private ConversationTurn toConversationTurn(
            ConversationTurnSnapshotParser.CompletedTurn l0Turn,
            ChatHistoryService.StableTurnBoundary boundary) {
        return new ConversationTurn(
                boundary.turnId(),
                boundary.completedThroughId(),
                l0Turn.messages(),
                tokenEstimator.estimateMessages(l0Turn.messages()));
    }

    private boolean isAligned(
            List<ConversationTurnSnapshotParser.CompletedTurn> l0Turns,
            List<ChatHistoryService.StableTurnBoundary> boundaries) {
        if (boundaries == null || l0Turns.size() != boundaries.size()) {
            return false;
        }
        long previousCompletedId = 0L;
        for (int index = 0; index < l0Turns.size(); index++) {
            ConversationTurnSnapshotParser.CompletedTurn l0 =
                    l0Turns.get(index);
            ChatHistoryService.StableTurnBoundary mysql =
                    boundaries.get(index);
            if (mysql.turnId() <= previousCompletedId
                    || mysql.completedThroughId() <= mysql.turnId()
                    || !TokenAwareChatMemory.matchesCanonicalUserText(
                    l0.userMessage(), mysql.userText())
                    || !l0.terminalAiText().equals(mysql.aiText())) {
                return false;
            }
            previousCompletedId = mysql.completedThroughId();
        }
        return true;
    }

    private RequestSnapshot captureRequestSnapshot(
            CompressionAwareChatMemory memory,
            List<ToolSpecification> tools,
            List<ChatMessage> transientMessages) {
        List<ChatMessage> messages = List.copyOf(memory.messages());
        return requestSnapshot(messages, tools, transientMessages);
    }

    private RequestSnapshot requestSnapshot(
            List<ChatMessage> realMessages,
            List<ToolSpecification> tools,
            List<ChatMessage> transientMessages) {
        List<ChatMessage> requestMessages = new java.util.ArrayList<>(
                realMessages.size() + transientMessages.size());
        requestMessages.addAll(realMessages);
        requestMessages.addAll(transientMessages);
        return new RequestSnapshot(requestMessages,
                tokenEstimator.estimateRequest(requestMessages, tools));
    }

    private boolean isCompressionSuccess(MemoryCompressionResult result) {
        return result != null
                && (result.status() == MemoryCompressionResult.Status.COMPRESSED
                || result.status()
                == MemoryCompressionResult.Status.NOTHING_TO_COMPRESS);
    }

    private FailureReason failureReason(MemoryCompressionResult result) {
        if (result == null) {
            return FailureReason.UNKNOWN;
        }
        return switch (result.status()) {
            case TIMED_OUT -> FailureReason.TIMED_OUT;
            case DELETE_REJECTED -> FailureReason.DELETE_REJECTED;
            default -> FailureReason.MODEL_FAILED;
        };
    }

    private String compressionFailureDetail(
            long appId, MemoryCompressionResult result) {
        if (result == null) {
            return "上下文压缩未返回结果，appId=" + appId;
        }
        return "上下文压缩失败，appId=" + appId
                + "，status=" + result.status().name()
                + "，detail=" + result.detail();
    }

    private ContextAdmissionResult blockingFailure(
            RequestSnapshot initialRequest,
            long summarizeThroughId,
            FailureReason reason,
            String detail) {
        return failure(blockingFailureMode(initialRequest),
                initialRequest, initialRequest,
                summarizeThroughId, reason, detail);
    }

    private ContextCompressionMode blockingFailureMode(
            RequestSnapshot initialRequest) {
        return initialRequest.estimatedTokens() >= properties.getHardInputLimit()
                ? ContextCompressionMode.HARD_LIMIT_REJECTED
                : ContextCompressionMode.BLOCKING_FAILED;
    }

    private ContextCompressionMode planningFailureMode(int initialTokens) {
        if (initialTokens >= properties.getHardInputLimit()) {
            return ContextCompressionMode.HARD_LIMIT_REJECTED;
        }
        if (initialTokens >= properties.getBlockingCompressionThreshold()) {
            return ContextCompressionMode.BLOCKING_FAILED;
        }
        return ContextCompressionMode.ADMISSION_FAILED;
    }

    private ContextAdmissionResult success(
            ContextCompressionMode mode,
            RequestSnapshot initialRequest,
            RequestSnapshot requestSnapshot,
            long summarizeThroughId,
            String detail) {
        return new ContextAdmissionResult(
                mode,
                initialRequest.estimatedTokens(),
                requestSnapshot.estimatedTokens(),
                requestSnapshot.messages(),
                summarizeThroughId,
                FailureReason.NONE,
                detail);
    }

    private ContextAdmissionResult failure(
            ContextCompressionMode mode,
            RequestSnapshot initialRequest,
            RequestSnapshot requestSnapshot,
            long summarizeThroughId,
            FailureReason reason,
            String detail) {
        return new ContextAdmissionResult(
                mode,
                initialRequest.estimatedTokens(),
                requestSnapshot.estimatedTokens(),
                requestSnapshot.messages(),
                summarizeThroughId,
                reason,
                detail);
    }

    private long requireAppId(Object memoryId) {
        if (!(memoryId instanceof Long appId) || appId <= 0L) {
            throw new IllegalArgumentException("ChatMemory 应用 ID 必须为正数 Long");
        }
        return appId;
    }

    private record RequestSnapshot(
            List<ChatMessage> messages,
            int estimatedTokens) {

        private RequestSnapshot {
            messages = List.copyOf(Objects.requireNonNull(
                    messages, "请求消息快照不能为空"));
            if (estimatedTokens < 0) {
                throw new IllegalArgumentException(
                        "请求消息 Token 不能为负数");
            }
        }
    }

    private record CompressionPlan(
            boolean available,
            long summarizeThroughId,
            List<ChatMessage> expectedPrefix,
            FailureReason failureReason,
            String detail) {

        private static CompressionPlan available(
                long summarizeThroughId,
                List<ChatMessage> expectedPrefix) {
            return new CompressionPlan(true, summarizeThroughId,
                    List.copyOf(expectedPrefix), FailureReason.NONE, "");
        }

        private static CompressionPlan unavailable(
                FailureReason failureReason, String detail) {
            return new CompressionPlan(false, 0L, List.of(),
                    failureReason, detail);
        }
    }

    private record InitialRequestPreparation(
            LayeredChatMemory.PreparedLayeredMessages messages,
            RequestSnapshot request,
            FailureReason failureReason,
            String detail) {

        private static InitialRequestPreparation success(
                LayeredChatMemory.PreparedLayeredMessages messages,
                RequestSnapshot request) {
            return new InitialRequestPreparation(
                    messages, request, FailureReason.NONE, "");
        }

        private static InitialRequestPreparation failed(
                RequestSnapshot request,
                FailureReason failureReason, String detail) {
            return new InitialRequestPreparation(
                    null, request, failureReason, detail);
        }
    }

    private static final class DependencyReadTimeoutException
            extends RuntimeException {
    }

    private static final class DependencyReadRejectedException
            extends RuntimeException {
    }

    private static final class DependencyReadInterruptedException
            extends RuntimeException {
    }

    private static final class DirectExecutorService
            extends AbstractExecutorService {

        private static final DirectExecutorService INSTANCE =
                new DirectExecutorService();

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(
                long timeout, TimeUnit unit) {
            return false;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    private record Alignment(
            boolean aligned,
            ConversationTurnSnapshotParser.Snapshot snapshot,
            List<ChatHistoryService.StableTurnBoundary> boundaries,
            FailureReason failureReason,
            String detail) {

        private static Alignment success(
                ConversationTurnSnapshotParser.Snapshot snapshot,
                List<ChatHistoryService.StableTurnBoundary> boundaries) {
            return new Alignment(true, snapshot,
                    List.copyOf(boundaries), FailureReason.NONE, "");
        }

        private static Alignment failed(
                FailureReason failureReason, String detail) {
            return new Alignment(false, null, List.of(),
                    Objects.requireNonNull(
                            failureReason, "对齐失败原因不能为空"),
                    detail);
        }
    }

    private record BlockingCompressionExecution(
            boolean terminated,
            MemoryCompressionResult result) {

        private static BlockingCompressionExecution terminatedExecution() {
            return new BlockingCompressionExecution(true, null);
        }

        private static BlockingCompressionExecution completed(
                MemoryCompressionResult result) {
            return new BlockingCompressionExecution(false, result);
        }
    }

    private record PreparedBlockingRequest(
            LayeredChatMemory.PreparedLayeredMessages messages,
            RequestSnapshot request,
            ContextAdmissionResult failure) {

        private static PreparedBlockingRequest success(
                LayeredChatMemory.PreparedLayeredMessages messages,
                RequestSnapshot request) {
            return new PreparedBlockingRequest(messages, request, null);
        }

        private static PreparedBlockingRequest failed(
                ContextAdmissionResult failure) {
            return new PreparedBlockingRequest(null, null, failure);
        }
    }

    private record CheckpointPreparation(
            boolean complete,
            RequestSnapshot request,
            List<ChatMessage> expectedMessages,
            FailureReason failureReason,
            String detail) {

        private CheckpointPreparation {
            expectedMessages = List.copyOf(expectedMessages);
        }

        private static CheckpointPreparation completed(
                RequestSnapshot request,
                List<ChatMessage> expectedMessages) {
            return new CheckpointPreparation(
                    true, request, expectedMessages, FailureReason.NONE, "");
        }

        private static CheckpointPreparation failed(
                FailureReason failureReason, String detail) {
            return new CheckpointPreparation(
                    false, null, List.of(), failureReason, detail);
        }
    }
}
