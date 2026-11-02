package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.ai.plan.AppPlan;
import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.core.handler.VueTurnContext;
import com.lyw.appgeneration.monitor.MemoryCompressionMetricsCollector;
import com.lyw.appgeneration.service.CompatibleMemorySnapshot;
import com.lyw.appgeneration.service.MemoryCompressionResult;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.UnifiedSummaryRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static com.lyw.appgeneration.ai.memory.ContextAdmissionResult.FailureReason;

/** Vue 请求从整对记忆准备到最终计量共用一次截止；所有依赖等待均在回合票据之外。 */
@lombok.extern.slf4j.Slf4j
final class UnifiedContextRequestPreparer {
    private final ChatTokenEstimator estimator;
    private final MemorySummaryService summaries;
    private final MemoryTokenProperties properties;
    private final ExecutorService reads;
    private final ExecutorService compression;
    private final AppDataLifecycleFence lifecycle;
    private final MemoryCompressionMetricsCollector metrics;

    UnifiedContextRequestPreparer(ChatTokenEstimator estimator, MemorySummaryService summaries,
            MemoryTokenProperties properties, ExecutorService reads, ExecutorService compression,
            AppDataLifecycleFence lifecycle, MemoryCompressionMetricsCollector metrics) {
        this.estimator = estimator;
        this.summaries = summaries;
        this.properties = properties;
        this.reads = reads;
        this.compression = compression;
        this.lifecycle = lifecycle;
        this.metrics = metrics;
    }

    ContextAdmissionResult admit(CompressionAwareChatMemory memory, VueTurnContext context,
            TurnRequestBoundary boundary, List<ToolSpecification> tools, List<ChatMessage> transientMessages,
            Consumer<ContextAdmissionResult> transitions, AdmissionDeadline deadline) {
        RequestContextSnapshot initial = null;
        RequestContextSnapshot current = null;
        try {
            requireActive(context, deadline);
            if (boundary == null || boundary.userHistoryId() != context.userHistoryId()
                    || !Objects.equals(boundary.turnId(), context.turnId())) {
                throw new PreparationFailure(FailureReason.ALIGNMENT_FAILED, "请求未绑定当前持久用户回合");
            }
            UnifiedSummaryRequest work = new UnifiedSummaryRequest(memory.l0Memory(), context);
            List<ToolSpecification> fixedTools = List.copyOf(tools == null ? List.of() : tools);
            List<ChatMessage> fixedTransient = List.copyOf(transientMessages == null ? List.of() : transientMessages);
            initial = await(reads, () -> prepare(memory, work, boundary, fixedTools, fixedTransient, deadline), deadline);
            current = initial;
            metrics.recordEstimatedTokens(MemoryCompressionMetricsCollector.EstimationStage.BEFORE, initial.estimatedTokens());
            requireActive(context, deadline);
            if (initial.estimatedTokens() < properties.getAsyncCompressionThreshold()) {
                return result(ContextCompressionMode.NORMAL, initial, current, FailureReason.NONE, "完整请求无需压缩");
            }
            if (initial.estimatedTokens() < properties.getBlockingCompressionThreshold()) {
                if (!context.tryRun(() -> summaries.triggerUnifiedAsync(work, deadline))) {
                    throw new PreparationFailure(FailureReason.TURN_TERMINATED, "后台摘要启动前回合已结束");
                }
                requireActive(context, deadline);
                return result(ContextCompressionMode.ASYNC_SCHEDULED, initial, current, FailureReason.NONE, "已请求后台增量摘要");
            }
            ContextAdmissionResult started = result(ContextCompressionMode.BLOCKING_STARTED,
                    initial, current, FailureReason.NONE, "开始整理统一摘要");
            if (!context.tryRun(() -> transitions.accept(started))) {
                throw new PreparationFailure(FailureReason.TURN_TERMINATED, "同步摘要启动前回合已结束");
            }
            MemoryCompressionResult compressed = await(compression, () -> {
                requireActive(context, deadline);
                return summaries.compressUnified(work, deadline);
            }, deadline);
            requireActive(context, deadline);
            if (compressed == null) throw new PreparationFailure(FailureReason.DEPENDENCY_FAILED, "统一摘要未返回结果");
            if (compressed.status() != MemoryCompressionResult.Status.COMPRESSED
                    && compressed.status() != MemoryCompressionResult.Status.NOTHING_TO_COMPRESS) {
                throw new PreparationFailure(switch (compressed.status()) {
                    case TIMED_OUT -> FailureReason.TIMED_OUT;
                    case DELETE_REJECTED -> FailureReason.DELETE_REJECTED;
                    default -> FailureReason.MODEL_FAILED;
                }, "统一摘要失败，status=" + compressed.status());
            }
            // 等待中摘要、原文及计划都可能变化，必须重组整份请求，复用已领取的资料与反馈。
            current = await(reads, () -> prepare(memory, work, boundary, fixedTools, fixedTransient, deadline), deadline);
            metrics.recordEstimatedTokens(MemoryCompressionMetricsCollector.EstimationStage.AFTER, current.estimatedTokens());
            requireActive(context, deadline);
            if (current.estimatedTokens() >= properties.getHardInputLimit()) {
                return result(ContextCompressionMode.HARD_LIMIT_REJECTED, initial, current,
                        FailureReason.STILL_OVER_HARD_LIMIT, "必要原文及固定资料仍达到输入硬上限");
            }
            return result(ContextCompressionMode.BLOCKING_COMPLETED, initial, current, FailureReason.NONE,
                    compressed.status() == MemoryCompressionResult.Status.COMPRESSED ? "统一摘要完成" : "没有新增可压缩来源，完整请求仍可容纳");
        } catch (PreparationFailure failure) {
            return failed(initial, current, failure.reason, failure.getMessage());
        } catch (CancellationException failure) {
            FailureReason reason = context.terminalWinner().isPresent() || !context.tryRun(() -> { }) ? FailureReason.TURN_TERMINATED
                    : Thread.currentThread().isInterrupted() ? FailureReason.INTERRUPTED : FailureReason.TIMED_OUT;
            return failed(initial, current, reason, "请求准备已终止");
        } catch (RuntimeException failure) {
            return failed(initial, current, FailureReason.DEPENDENCY_FAILED,
                    "请求准备依赖失败，type=" + failure.getClass().getSimpleName());
        }
    }

    private RequestContextSnapshot prepare(CompressionAwareChatMemory memory, UnifiedSummaryRequest work,
            TurnRequestBoundary boundary, List<ToolSpecification> tools, List<ChatMessage> transientMessages,
            AdmissionDeadline deadline) {
        var initialState = memory.capturePlanContext();
        while (true) {
            requireActive(work.context(), deadline);
            var captured = memory.capturePlanContext();
            requireGeneration(memory, initialState);
            CompatibleMemorySnapshot compatible = Objects.requireNonNull(summaries.readCompatible(work, deadline));
            var observation = compatible;
            // 已可靠覆盖而回收尚未成功时重试；成功后必须使用实际提交的同一来源窗口。
            if (compatible.summary().summaryRevision() > 0
                    && !L0SourceOperations.prune(compatible.source(), compatible.summary(), boundary.userHistoryId())
                            .equals(compatible.source())) {
                DeadlineAwareReplaceResult reclaimed = summaries.reclaimUnified(work, deadline);
                if (reclaimed == DeadlineAwareReplaceResult.REPLACED || reclaimed == DeadlineAwareReplaceResult.PREFIX_CHANGED) {
                    compatible = summaries.readCompatible(work, deadline);
                }
                if (reclaimed == DeadlineAwareReplaceResult.INTERRUPTED) {
                    throw new PreparationFailure(FailureReason.INTERRUPTED, "原文回收被中断");
                }
                if (reclaimed == DeadlineAwareReplaceResult.TIMED_OUT) {
                    throw new PreparationFailure(FailureReason.TIMED_OUT, "原文回收超过截止");
                }
            }
            if (compatible.source().entries().stream().noneMatch(entry -> entry.userHistoryId() == boundary.userHistoryId()
                    && entry.payload().equals(boundary.userMessage()))) {
                throw new PreparationFailure(FailureReason.ALIGNMENT_FAILED, "来源窗口与当前用户原话不一致");
            }
            var change = boundary.planDefinitionChange();
            var checked = captured.checked();
            AppPlan readPlan = null;
            boolean needsCheck = checked == null || !checked.matches(compatible, change);
            if (needsCheck) {
                var visible = TrustedTurnRequestViewProjector.visibleSource(compatible);
                boolean pending = visible.units().stream().anyMatch(unit -> !unit.complete());
                var oldRestore = compatible.source().planRestore();
                var coverage = PlanCoverageBoundary.of(compatible.summary());
                boolean sameBoundary = checked != null && checked.epoch().equals(compatible.source().memoryEpoch())
                        && checked.coverage().equals(coverage);
                L0Snapshot replacement;
                if (PlanContext.hasFullPlan(visible, change.definition())) {
                    replacement = L0SourceOperations.replacePlanRestore(compatible.source(), null, visible);
                } else if (sameBoundary && oldRestore != null && oldRestore.coverage().equals(coverage)
                        && oldRestore.definition().equals(change.definition())) {
                    replacement = compatible.source();
                } else {
                    requireActive(work.context(), deadline);
                    try {
                        readPlan = boundary.readLatestPlan().orElse(null);
                    } catch (RuntimeException failure) {
                        throw new PreparationFailure(FailureReason.PLAN_READ_FAILED, "按需读取计划失败");
                    }
                    requireActive(work.context(), deadline);
                    replacement = PlanContext.select(compatible, readPlan, sameBoundary);
                }
                var selected = new CompatibleMemorySnapshot(compatible.appId(), compatible.userHistoryId(),
                        compatible.turnId(), compatible.summary(), replacement);
                var nextCheck = PlanContext.checked(selected, change, pending);
                if (!commitPlanContext(memory, captured, compatible.source(), replacement, nextCheck,
                        work.context(), boundary, deadline)) {
                    requireGeneration(memory, initialState);
                    continue;
                }
                if (!Objects.equals(oldRestore, replacement.planRestore())) {
                    var restore = replacement.planRestore();
                    String reason = checked == null ? "FIRST_LOAD" : !checked.epoch().equals(replacement.memoryEpoch())
                            ? "EPOCH_CHANGED" : !checked.coverage().equals(coverage) ? "COVERAGE_ADVANCED" : "DEFINITION_CHANGED";
                    log.info("计划热窗口提交 appId={} reason={} version={} restoreId={} result={}", compatible.appId(), reason,
                            restore == null ? (change.definition() == null ? null : change.definition().version()) : restore.definition().version(),
                            restore == null ? null : restore.restoreId(), restore == null ? "REMOVED" : "RESTORED");
                }
                compatible = selected;
            }
            List<ChatMessage> messages = new ArrayList<>(memory.prepareCompatible(compatible, work.context().currentRequestState()));
            messages.addAll(transientMessages);
            List<ChatMessage> frozen = List.copyOf(messages);
            int tokens = estimator.estimateRequest(frozen, tools);
            requireActive(work.context(), deadline);
            requireGeneration(memory, initialState);
            // 读盘或计量期间若定义成功变更，重新检查；不让旧版本越过本次发送门禁。
            if (!change.equals(boundary.planDefinitionChange())) continue;
            observeSnapshot(observation, boundary.userHistoryId());
            return new RequestContextSnapshot(compatible, readPlan, frozen, tools, tokens);
        }
    }

    private void requireGeneration(CompressionAwareChatMemory memory, PlanContext.State initial) {
        if (memory.capturePlanContext().generation() != initial.generation()) {
            throw new PreparationFailure(FailureReason.ALIGNMENT_FAILED, "计划检查状态已失效，拒绝迟到准备");
        }
    }

    private boolean commitPlanContext(CompressionAwareChatMemory memory, PlanContext.State captured,
            L0Snapshot expected, L0Snapshot replacement, PlanContext.Check checked, VueTurnContext context,
            TurnRequestBoundary boundary, AdmissionDeadline deadline) {
        try (var writer = lifecycle.tryAcquireWriter(context.appId())) {
            if (writer == null) throw new PreparationFailure(FailureReason.DELETE_REJECTED, "应用删除已经接管计划上下文");
            var committed = new java.util.concurrent.atomic.AtomicBoolean();
            boolean accepted = context.tryRun(() -> context.commitSummaryWhileActive(deadline, () -> {
                if (!lifecycle.isOpen(context.appId())) {
                    throw new PreparationFailure(FailureReason.DELETE_REJECTED, "应用删除已经接管计划上下文");
                }
                committed.set(memory.commitPlanContext(captured, checked, () -> {
                    if (!checked.change().equals(boundary.planDefinitionChange())) return false;
                    return memory.l0Memory().withSourceLock(deadline, current -> {
                        // 无窗口写入时，普通回收不使已捕获的相容副本失效；新 epoch 必须重读。
                        if (replacement.equals(expected) && current.hasKnownSource()
                                && current.memoryEpoch().equals(expected.memoryEpoch())) return true;
                        if (!current.equals(expected)) return false;
                        var result = memory.l0Memory().replaceSourceSnapshot(expected, replacement, deadline);
                        return switch (result) {
                            case REPLACED -> true;
                            case PREFIX_CHANGED -> false;
                            case TIMED_OUT -> throw new PreparationFailure(FailureReason.TIMED_OUT, "计划恢复写入超过截止");
                            case INTERRUPTED -> throw new PreparationFailure(FailureReason.INTERRUPTED, "计划恢复写入被中断");
                            default -> throw new PreparationFailure(FailureReason.DEPENDENCY_FAILED, "计划恢复写入未确认");
                        };
                    });
                }));
                return null;
            }));
            if (!accepted) throw new PreparationFailure(FailureReason.TURN_TERMINATED, "计划恢复提交前回合已结束");
            return committed.get();
        }
    }

    private void observeSnapshot(CompatibleMemorySnapshot compatible, long userHistoryId) {
        try {
            var summary = compatible.summary();
            var source = summary.summaryRevision() > 0
                    ? L0SourceOperations.prune(compatible.source(), summary, userHistoryId) : compatible.source();
            var originals = source.entries().stream().filter(entry -> entry.userHistoryId() > 0
                    && !(entry.payload() instanceof AiMessage && entry.historyRecordId() != null))
                    .map(L0Snapshot.Message::payload).toList();
            int deduplicated = (int) compatible.source().entries().stream().filter(entry -> {
                if (!(entry.payload() instanceof AiMessage) || entry.historyRecordId() == null) return false;
                if (entry.historyRecordId() <= summary.lastSummarizedId()) return true;
                var frontier = summary.coverageState();
                return frontier != null && frontier.terminalIncluded()
                        && Objects.equals(frontier.terminalHistoryId(), entry.historyRecordId());
            }).count();
            metrics.recordRequestSnapshot(originals.size(), estimator.estimateMessages(originals), deduplicated);
            if (!summary.summary().isEmpty()) metrics.recordSummaryReuse(MemoryCompressionMetricsCollector.SummaryReuse.COMMITTED_SNAPSHOT);
        } catch (RuntimeException ignored) {
            // 原文量的旁路计量失败不能改变已经审核的完整请求及其预算。
        }
    }

    private void requireActive(VueTurnContext context, AdmissionDeadline deadline) {
        if (Thread.currentThread().isInterrupted()) throw new PreparationFailure(FailureReason.INTERRUPTED, "请求准备被中断");
        if (deadline.remainingNanos() <= 0) throw new PreparationFailure(FailureReason.TIMED_OUT, "请求准备截止已耗尽");
        if (context.terminalWinner().isPresent() || !context.tryRun(() -> { })) {
            throw new PreparationFailure(FailureReason.TURN_TERMINATED, "回合已经结束");
        }
        try (var writer = lifecycle.tryAcquireWriter(context.appId())) {
            if (writer == null) throw new PreparationFailure(FailureReason.DELETE_REJECTED, "应用删除已经接管");
        }
    }

    private <T> T await(ExecutorService executor, Callable<T> action, AdmissionDeadline deadline) {
        if (deadline.remainingNanos() <= 0) throw new PreparationFailure(FailureReason.TIMED_OUT, "请求准备截止已耗尽");
        Future<T> future;
        try {
            future = executor.submit(action);
        } catch (RejectedExecutionException failure) {
            throw new PreparationFailure(FailureReason.EXECUTOR_REJECTED, "上下文准备执行器已满");
        }
        try {
            T value = future.get(deadline.remainingNanos(), TimeUnit.NANOSECONDS);
            if (deadline.remainingNanos() <= 0) throw new PreparationFailure(FailureReason.TIMED_OUT, "丢弃截止后的准备结果");
            return value;
        } catch (TimeoutException failure) {
            future.cancel(true);
            throw new PreparationFailure(FailureReason.TIMED_OUT, "上下文准备等待超过截止");
        } catch (InterruptedException failure) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new PreparationFailure(FailureReason.INTERRUPTED, "上下文准备等待被中断");
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new PreparationFailure(FailureReason.DEPENDENCY_FAILED, "上下文准备依赖异常");
        }
    }

    private ContextAdmissionResult failed(RequestContextSnapshot initial, RequestContextSnapshot current,
            FailureReason reason, String detail) {
        ContextCompressionMode mode = current != null && current.estimatedTokens() >= properties.getHardInputLimit()
                ? ContextCompressionMode.HARD_LIMIT_REJECTED : ContextCompressionMode.BLOCKING_FAILED;
        return result(mode, initial, current, reason, detail);
    }

    private ContextAdmissionResult result(ContextCompressionMode mode, RequestContextSnapshot initial,
            RequestContextSnapshot current, FailureReason reason, String detail) {
        return new ContextAdmissionResult(mode, initial == null ? 0 : initial.estimatedTokens(),
                current == null ? 0 : current.estimatedTokens(), current == null ? List.of() : current.messages(),
                current == null ? 0 : current.memory().summary().lastSummarizedId(), reason, detail);
    }

    private static final class PreparationFailure extends RuntimeException {
        private final FailureReason reason;
        PreparationFailure(FailureReason reason, String message) { super(message); this.reason = reason; }
    }
}
