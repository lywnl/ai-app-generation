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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import static com.lyw.appgeneration.ai.memory.ContextAdmissionResult.FailureReason;

/** 统一编排 28K 异步压缩、30K 阻塞压缩和 32K 硬门禁。 */
@Component
public class ContextCompressionCoordinator {

    private final ChatTokenEstimator tokenEstimator;
    private final ChatHistoryService chatHistoryService;
    private final MemorySummaryService summaryService;
    private final MemoryTokenProperties properties;
    private final ExecutorService compressionExecutor;
    private final AppDataLifecycleFence lifecycleFence;
    private final MemoryCompressionMetricsCollector metricsCollector;
    private final LongSupplier nanoTime;
    private final ConversationTurnSelector turnSelector =
            new ConversationTurnSelector();

    @Autowired
    public ContextCompressionCoordinator(
            ChatTokenEstimator tokenEstimator,
            ChatHistoryService chatHistoryService,
            MemorySummaryService summaryService,
            MemoryTokenProperties properties,
            @Qualifier("contextCompressionExecutor")
            ExecutorService compressionExecutor,
            AppDataLifecycleFence lifecycleFence,
            MemoryCompressionMetricsCollector metricsCollector) {
        this(tokenEstimator, chatHistoryService, summaryService, properties,
                compressionExecutor, lifecycleFence, metricsCollector,
                System::nanoTime);
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
        this.lifecycleFence = Objects.requireNonNull(
                lifecycleFence, "应用数据生命周期栅栏不能为空");
        this.metricsCollector = Objects.requireNonNull(
                metricsCollector, "记忆压缩指标收集器不能为空");
        this.nanoTime = Objects.requireNonNull(
                nanoTime, "单调时钟不能为空");
    }

    public ContextAdmissionResult admit(
            CompressionAwareChatMemory memory,
            List<ToolSpecification> tools) {
        return admit(memory, tools, ignored -> { },
                ContextContinuationGate.alwaysOpen());
    }

    public ContextAdmissionResult admit(
            CompressionAwareChatMemory memory,
            List<ToolSpecification> tools,
            Consumer<ContextAdmissionResult> transitionListener) {
        return admit(memory, tools, transitionListener,
                ContextContinuationGate.alwaysOpen());
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
        ContextAdmissionResult result = admitInternal(
                memory, tools, transitionListener, continuationGate);
        metricsCollector.recordContextGate(
                result.mode(), result.failureReason());
        return result;
    }

    private ContextAdmissionResult admitInternal(
            CompressionAwareChatMemory memory,
            List<ToolSpecification> tools,
            Consumer<ContextAdmissionResult> transitionListener,
            ContextContinuationGate continuationGate) {
        Objects.requireNonNull(memory, "在线记忆不能为空");
        Objects.requireNonNull(transitionListener, "状态监听器不能为空");
        Objects.requireNonNull(continuationGate, "回合原子提交门不能为空");
        List<ToolSpecification> stableTools = List.copyOf(
                tools == null ? List.of() : tools);
        long appId = requireAppId(memory.id());
        if (!tryCommitContinuation(continuationGate)) {
            return turnTerminated(ContextCompressionMode.ADMISSION_FAILED,
                    0, 0, 0L, appId);
        }
        if (!isLifecycleOpen(appId)) {
            return failure(ContextCompressionMode.ADMISSION_FAILED,
                    0, 0, 0L, FailureReason.DELETE_REJECTED,
                    "应用删除流程已接管，appId=" + appId);
        }
        CursorApplication cursorApplication = applyCompletedSummaryPrefix(
                appId, memory, continuationGate);
        if (!cursorApplication.applied()) {
            return failure(ContextCompressionMode.ADMISSION_FAILED,
                    0, 0, 0L, cursorApplication.failureReason(),
                    cursorApplication.detail());
        }
        if (!tryCommitContinuation(continuationGate)) {
            return turnTerminated(ContextCompressionMode.ADMISSION_FAILED,
                    0, 0, 0L, appId);
        }
        int initialTokens = estimate(memory, stableTools);
        metricsCollector.recordEstimatedTokens(
                MemoryCompressionMetricsCollector.EstimationStage.BEFORE,
                initialTokens);
        if (initialTokens < properties.getAsyncCompressionThreshold()) {
            if (!tryCommitContinuation(continuationGate)) {
                return turnTerminated(ContextCompressionMode.ADMISSION_FAILED,
                        initialTokens, initialTokens, 0L, appId);
            }
            return success(ContextCompressionMode.NORMAL,
                    initialTokens, initialTokens, 0L, "无需压缩");
        }
        CompressionPlan plan = buildPlan(appId, memory);
        if (!plan.available()) {
            if (initialTokens < properties.getBlockingCompressionThreshold()
                    && plan.failureReason()
                    == FailureReason.NO_COMPRESSIBLE_TURN) {
                if (!tryCommitContinuation(continuationGate)) {
                    return turnTerminated(
                            ContextCompressionMode.ADMISSION_FAILED,
                            initialTokens, initialTokens, 0L, appId);
                }
                return success(ContextCompressionMode.NORMAL,
                        initialTokens, initialTokens, 0L,
                        "低于同步阈值且没有可压缩旧回合，本次继续");
            }
            return failure(planningFailureMode(initialTokens),
                    initialTokens, initialTokens, 0L,
                    plan.failureReason(), plan.detail());
        }
        if (initialTokens < properties.getBlockingCompressionThreshold()) {
            if (!tryCommitContinuation(continuationGate)) {
                return turnTerminated(ContextCompressionMode.ADMISSION_FAILED,
                        initialTokens, initialTokens,
                        plan.summarizeThroughId(), appId);
            }
            if (!isLifecycleOpen(appId)) {
                return failure(ContextCompressionMode.ADMISSION_FAILED,
                        initialTokens, initialTokens,
                        plan.summarizeThroughId(),
                        FailureReason.DELETE_REJECTED,
                        "应用删除流程已接管，appId=" + appId);
            }
            if (!continuationGate.tryRun(() ->
                    summaryService.triggerSummarizationAsync(
                            appId, plan.summarizeThroughId()))) {
                return turnTerminated(
                        ContextCompressionMode.ADMISSION_FAILED,
                        initialTokens, initialTokens,
                        plan.summarizeThroughId(), appId);
            }
            return success(ContextCompressionMode.ASYNC_SCHEDULED,
                    initialTokens, initialTokens,
                    plan.summarizeThroughId(), "已提交异步压缩");
        }
        return blockAndRecheck(
                appId, memory, stableTools, initialTokens, plan,
                transitionListener, continuationGate);
    }

    private ContextAdmissionResult blockAndRecheck(
            long appId,
            CompressionAwareChatMemory memory,
            List<ToolSpecification> tools,
            int initialTokens,
            CompressionPlan plan,
            Consumer<ContextAdmissionResult> transitionListener,
            ContextContinuationGate continuationGate) {
        if (!tryCommitContinuation(continuationGate)) {
            return turnTerminated(ContextCompressionMode.BLOCKING_FAILED,
                    initialTokens, initialTokens,
                    plan.summarizeThroughId(), appId);
        }
        if (!isLifecycleOpen(appId)) {
            return failure(ContextCompressionMode.BLOCKING_FAILED,
                    initialTokens, initialTokens,
                    plan.summarizeThroughId(),
                    FailureReason.DELETE_REJECTED,
                    "应用删除流程已接管，appId=" + appId);
        }
        Duration timeout = properties.getBlockingTimeout();
        long deadlineNanos = deadlineNanos(timeout);
        if (!continuationGate.tryRun(() -> transitionListener.accept(success(
                ContextCompressionMode.BLOCKING_STARTED,
                initialTokens, initialTokens,
                plan.summarizeThroughId(), "开始阻塞压缩")))) {
            return turnTerminated(ContextCompressionMode.BLOCKING_FAILED,
                    initialTokens, initialTokens,
                    plan.summarizeThroughId(), appId);
        }
        if (remainingNanos(deadlineNanos) <= 0L) {
            return blockingFailure(initialTokens,
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
                                remainingDuration(deadlineNanos)));
            });
        } catch (RejectedExecutionException exception) {
            metricsCollector.recordCompressionExecutorRejected(
                    MemoryCompressionMetricsCollector.CompressionMode.BLOCKING);
            return blockingFailure(initialTokens,
                    plan.summarizeThroughId(),
                    FailureReason.EXECUTOR_REJECTED,
                    "上下文压缩执行器已满，appId=" + appId);
        }
        BlockingCompressionExecution execution;
        try {
            long remainingNanos = remainingNanos(deadlineNanos);
            if (remainingNanos <= 0L) {
                future.cancel(true);
                return blockingFailure(initialTokens,
                        plan.summarizeThroughId(), FailureReason.TIMED_OUT,
                        "上下文压缩排队后绝对截止时间已到");
            }
            execution = future.get(
                    remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            return blockingFailure(initialTokens,
                    plan.summarizeThroughId(), FailureReason.TIMED_OUT,
                    "等待上下文压缩超时");
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return blockingFailure(initialTokens,
                    plan.summarizeThroughId(), FailureReason.INTERRUPTED,
                    "等待上下文压缩被中断");
        } catch (ExecutionException exception) {
            return blockingFailure(initialTokens,
                    plan.summarizeThroughId(), FailureReason.MODEL_FAILED,
                    "上下文压缩任务异常");
        }
        if (execution.terminated()) {
            return turnTerminated(ContextCompressionMode.BLOCKING_FAILED,
                    initialTokens, initialTokens,
                    plan.summarizeThroughId(), appId);
        }
        MemoryCompressionResult compressionResult = execution.result();
        if (remainingNanos(deadlineNanos) <= 0L) {
            future.cancel(true);
            return blockingFailure(initialTokens,
                    plan.summarizeThroughId(), FailureReason.TIMED_OUT,
                    "上下文压缩完成时已超过绝对截止时间");
        }
        if (!tryCommitContinuation(continuationGate)) {
            return turnTerminated(ContextCompressionMode.BLOCKING_FAILED,
                    initialTokens, initialTokens,
                    plan.summarizeThroughId(), appId);
        }
        if (!isCompressionSuccess(compressionResult)
                || compressionResult.summarizedThroughId()
                < plan.summarizeThroughId()) {
            return blockingFailure(initialTokens,
                    plan.summarizeThroughId(),
                    failureReason(compressionResult),
                    compressionFailureDetail(appId, compressionResult));
        }
        return applyBlockingPrefixWithinLifecyclePermit(
                appId, memory, tools, initialTokens, plan,
                continuationGate);
    }

    private CompressionPlan buildPlan(
            long appId, CompressionAwareChatMemory memory) {
        Alignment alignment = readAlignment(appId, memory);
        if (!alignment.aligned()) {
            return CompressionPlan.unavailable(
                    FailureReason.ALIGNMENT_FAILED, alignment.detail());
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

    private CursorApplication applyCompletedSummaryPrefix(
            long appId,
            CompressionAwareChatMemory memory,
            ContextContinuationGate continuationGate) {
        long lastSummarizedId;
        try {
            lastSummarizedId = summaryService.lastSummarizedId(appId);
        } catch (RuntimeException exception) {
            return CursorApplication.failed(
                    FailureReason.CURSOR_READ_FAILED,
                    "读取 L1 摘要游标失败，appId=" + appId
                            + "，type="
                            + exception.getClass().getSimpleName());
        }
        if (lastSummarizedId <= 0L) {
            return CursorApplication.success();
        }
        if (!tryCommitContinuation(continuationGate)) {
            return CursorApplication.failed(
                    FailureReason.TURN_TERMINATED,
                    "回合已取消或终态已被占用，appId=" + appId);
        }
        Alignment alignment = readAlignment(appId, memory);
        if (!alignment.aligned()) {
            return CursorApplication.failed(
                    FailureReason.ALIGNMENT_FAILED, alignment.detail());
        }
        int coveredTurns = 0;
        for (ChatHistoryService.StableTurnBoundary boundary
                : alignment.boundaries()) {
            if (boundary.completedThroughId() > lastSummarizedId) {
                break;
            }
            coveredTurns++;
        }
        if (coveredTurns == 0) {
            return CursorApplication.success();
        }
        List<ChatMessage> expectedPrefix = alignment.snapshot()
                .completedTurns().subList(0, coveredTurns).stream()
                .flatMap(turn -> turn.messages().stream())
                .toList();
        AtomicReference<CursorApplication> committed =
                new AtomicReference<>();
        boolean accepted = continuationGate.tryRun(() -> committed.set(
                removeCompletedSummaryPrefixWithinLifecyclePermit(
                        appId, memory, expectedPrefix)));
        if (!accepted) {
            return CursorApplication.failed(
                    FailureReason.TURN_TERMINATED,
                    "回合已取消或终态已被占用，appId=" + appId);
        }
        return requireCommitted(committed, "L0 游标裁剪");
    }

    private CursorApplication removeCompletedSummaryPrefixWithinLifecyclePermit(
            long appId,
            CompressionAwareChatMemory memory,
            List<ChatMessage> expectedPrefix) {
        AppDataLifecycleFence.WriterPermit writerPermit =
                tryAcquireLifecycleWriter(appId);
        if (writerPermit == null) {
            return CursorApplication.failed(
                    FailureReason.DELETE_REJECTED,
                    "应用删除流程已接管，appId=" + appId);
        }
        try (writerPermit) {
            if (!memory.removeCompletedPrefixIfMatches(expectedPrefix)) {
                return CursorApplication.failed(
                        FailureReason.PREFIX_CHANGED,
                        "L0 旧前缀已变化，本次不裁剪");
            }
        }
        return CursorApplication.success();
    }

    private ContextAdmissionResult applyBlockingPrefixWithinLifecyclePermit(
            long appId,
            CompressionAwareChatMemory memory,
            List<ToolSpecification> tools,
            int initialTokens,
            CompressionPlan plan,
            ContextContinuationGate continuationGate) {
        AtomicReference<ContextAdmissionResult> committed =
                new AtomicReference<>();
        boolean accepted = continuationGate.tryRun(() -> committed.set(
                applyBlockingPrefixCommitted(
                        appId, memory, tools, initialTokens, plan)));
        if (!accepted) {
            return turnTerminated(ContextCompressionMode.BLOCKING_FAILED,
                    initialTokens, initialTokens,
                    plan.summarizeThroughId(), appId);
        }
        return requireCommitted(committed, "阻塞压缩完成提交");
    }

    private ContextAdmissionResult applyBlockingPrefixCommitted(
            long appId,
            CompressionAwareChatMemory memory,
            List<ToolSpecification> tools,
            int initialTokens,
            CompressionPlan plan) {
        AppDataLifecycleFence.WriterPermit writerPermit =
                tryAcquireLifecycleWriter(appId);
        if (writerPermit == null) {
            return failure(ContextCompressionMode.BLOCKING_FAILED,
                    initialTokens, initialTokens,
                    plan.summarizeThroughId(),
                    FailureReason.DELETE_REJECTED,
                    "应用删除流程已接管，appId=" + appId);
        }
        try (writerPermit) {
            if (!memory.removeCompletedPrefixIfMatches(plan.expectedPrefix())) {
                return blockingFailure(initialTokens,
                        plan.summarizeThroughId(),
                        FailureReason.PREFIX_CHANGED,
                        "L0 旧前缀已变化，本次不裁剪");
            }
            int finalTokens = estimate(memory, tools);
            metricsCollector.recordEstimatedTokens(
                    MemoryCompressionMetricsCollector.EstimationStage.AFTER,
                    finalTokens);
            if (finalTokens >= properties.getHardInputLimit()) {
                return failure(ContextCompressionMode.HARD_LIMIT_REJECTED,
                        initialTokens, finalTokens,
                        plan.summarizeThroughId(),
                        FailureReason.STILL_OVER_HARD_LIMIT,
                        "压缩后仍达到 32K 输入硬上限");
            }
            return success(ContextCompressionMode.BLOCKING_COMPLETED,
                    initialTokens, finalTokens,
                    plan.summarizeThroughId(), "阻塞压缩完成");
        }
    }

    private long deadlineNanos(Duration timeout) {
        long timeoutNanos;
        try {
            timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException exception) {
            timeoutNanos = Long.MAX_VALUE;
        }
        long startedAt = nanoTime.getAsLong();
        return timeoutNanos > 0L
                && startedAt > Long.MAX_VALUE - timeoutNanos
                ? Long.MAX_VALUE : startedAt + timeoutNanos;
    }

    private Duration remainingDuration(long deadlineNanos) {
        return Duration.ofNanos(remainingNanos(deadlineNanos));
    }

    private long remainingNanos(long deadlineNanos) {
        long currentNanos = nanoTime.getAsLong();
        if (deadlineNanos <= currentNanos) {
            return 0L;
        }
        long remaining = deadlineNanos - currentNanos;
        return remaining < 0L ? Long.MAX_VALUE : remaining;
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
            int initialTokens,
            int finalTokens,
            long summarizeThroughId,
            long appId) {
        return failure(mode, initialTokens, finalTokens,
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
            return Alignment.failed("读取 MySQL 稳定回合边界失败");
        }
        if (!isAligned(snapshot.completedTurns(), boundaries)) {
            return Alignment.failed(
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
                    || !l0.terminalAiText().equals(mysql.aiText())) {
                return false;
            }
            previousCompletedId = mysql.completedThroughId();
        }
        return true;
    }

    private int estimate(
            CompressionAwareChatMemory memory,
            List<ToolSpecification> tools) {
        return tokenEstimator.estimateRequest(memory.messages(), tools);
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
            int initialTokens,
            long summarizeThroughId,
            FailureReason reason,
            String detail) {
        ContextCompressionMode mode = initialTokens
                >= properties.getHardInputLimit()
                ? ContextCompressionMode.HARD_LIMIT_REJECTED
                : ContextCompressionMode.BLOCKING_FAILED;
        return failure(mode, initialTokens, initialTokens,
                summarizeThroughId, reason, detail);
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
            int initialTokens,
            int finalTokens,
            long summarizeThroughId,
            String detail) {
        return new ContextAdmissionResult(mode, initialTokens, finalTokens,
                summarizeThroughId, FailureReason.NONE, detail);
    }

    private ContextAdmissionResult failure(
            ContextCompressionMode mode,
            int initialTokens,
            int finalTokens,
            long summarizeThroughId,
            FailureReason reason,
            String detail) {
        return new ContextAdmissionResult(mode, initialTokens, finalTokens,
                summarizeThroughId, reason, detail);
    }

    private long requireAppId(Object memoryId) {
        if (!(memoryId instanceof Long appId) || appId <= 0L) {
            throw new IllegalArgumentException("ChatMemory 应用 ID 必须为正数 Long");
        }
        return appId;
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

    private record CursorApplication(
            boolean applied,
            FailureReason failureReason,
            String detail) {

        private static CursorApplication success() {
            return new CursorApplication(true, FailureReason.NONE, "");
        }

        private static CursorApplication failed(
                FailureReason failureReason, String detail) {
            return new CursorApplication(false, failureReason, detail);
        }
    }

    private record Alignment(
            boolean aligned,
            ConversationTurnSnapshotParser.Snapshot snapshot,
            List<ChatHistoryService.StableTurnBoundary> boundaries,
            String detail) {

        private static Alignment success(
                ConversationTurnSnapshotParser.Snapshot snapshot,
                List<ChatHistoryService.StableTurnBoundary> boundaries) {
            return new Alignment(true, snapshot,
                    List.copyOf(boundaries), "");
        }

        private static Alignment failed(String detail) {
            return new Alignment(false, null, List.of(), detail);
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
}
