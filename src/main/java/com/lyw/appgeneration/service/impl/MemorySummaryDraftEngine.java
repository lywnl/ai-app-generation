package com.lyw.appgeneration.service.impl;

import cn.hutool.core.util.StrUtil;
import com.lyw.appgeneration.ai.memory.ChatTokenEstimator;
import com.lyw.appgeneration.ai.memory.MemorySummaryPromptBuilder;
import com.lyw.appgeneration.ai.memory.SummaryCompressionPromptBuilder;
import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.model.entity.AppMemorySummary;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.model.enums.ChatHistoryMessageTypeEnum;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemoryCompressionResult;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/** 生成 L1 摘要草稿，负责分页、完整回合、动态分批和 reducer 收敛。 */
@Slf4j
@Component
public class MemorySummaryDraftEngine {

    private static final int HISTORY_QUERY_BATCH_SIZE = 100;

    private final ChatHistoryService chatHistoryService;
    private final ChatModel summarizationModel;
    private final ExecutorService modelExecutor;
    private final ChatTokenEstimator tokenEstimator;
    private final MemoryTokenProperties properties;
    private final LongSupplier nanoTime;

    @Autowired
    public MemorySummaryDraftEngine(
            ChatHistoryService chatHistoryService,
            @Qualifier("openAiChatModel") ChatModel summarizationModel,
            @Qualifier("memorySummaryModelExecutor") ExecutorService modelExecutor,
            ChatTokenEstimator tokenEstimator,
            MemoryTokenProperties properties) {
        this(chatHistoryService, summarizationModel, modelExecutor,
                tokenEstimator, properties, System::nanoTime);
    }

    MemorySummaryDraftEngine(
            ChatHistoryService chatHistoryService,
            ChatModel summarizationModel,
            ExecutorService modelExecutor,
            ChatTokenEstimator tokenEstimator,
            MemoryTokenProperties properties,
            LongSupplier nanoTime) {
        this.chatHistoryService = Objects.requireNonNull(
                chatHistoryService, "对话历史服务不能为空");
        this.summarizationModel = Objects.requireNonNull(
                summarizationModel, "摘要模型不能为空");
        this.modelExecutor = Objects.requireNonNull(
                modelExecutor, "摘要模型执行器不能为空");
        this.tokenEstimator = Objects.requireNonNull(
                tokenEstimator, "Token 估算器不能为空");
        this.properties = Objects.requireNonNull(
                properties, "Token 配置不能为空");
        this.nanoTime = Objects.requireNonNull(
                nanoTime, "单调时钟不能为空");
    }

    DraftResult buildDraft(
            Long appId,
            long summarizeThroughId,
            AppMemorySummary current,
            long deadlineNanos) {
        long persistedCursor = currentCursor(current);
        String oldSummary = current == null
                ? "" : StrUtil.nullToEmpty(current.getSummary());
        RollingSummaryAccumulator accumulator =
                new RollingSummaryAccumulator(
                        appId, oldSummary, persistedCursor, deadlineNanos);
        accumulator.initialize();
        if (accumulator.hasFailed()) {
            return accumulator.finish();
        }
        readCompleteTurns(
                appId,
                summarizeThroughId,
                persistedCursor,
                deadlineNanos,
                accumulator);
        return accumulator.finish();
    }

    private void readCompleteTurns(
            Long appId,
            long summarizeThroughId,
            long persistedCursor,
            long deadlineNanos,
            RollingSummaryAccumulator accumulator) {
        long scanCursor = persistedCursor;
        ChatHistory pendingUser = null;
        boolean boundaryReached = false;
        while (scanCursor < summarizeThroughId && !boundaryReached) {
            if (isDeadlineExpired(deadlineNanos)) {
                accumulator.fail(MemoryCompressionResult.Status.TIMED_OUT,
                        "摘要截止时间已到");
                break;
            }
            List<ChatHistory> rows = chatHistoryService
                    .listMessagesAfterCursor(
                            appId, scanCursor, HISTORY_QUERY_BATCH_SIZE);
            if (rows == null) {
                accumulator.fail(MemoryCompressionResult.Status.MODEL_FAILED,
                        "数据库返回了空历史批次");
                break;
            }
            if (rows.isEmpty()) {
                break;
            }
            long previousCursor = scanCursor;
            for (ChatHistory row : rows) {
                long rowId = requireNextHistoryId(row, scanCursor);
                if (rowId > summarizeThroughId) {
                    boundaryReached = true;
                    break;
                }
                scanCursor = rowId;
                if (isUserMessage(row)) {
                    pendingUser = row;
                } else if (isAiMessage(row) && pendingUser != null) {
                    accumulator.accept(new SummaryTurn(pendingUser, row));
                    pendingUser = null;
                    if (accumulator.hasFailed()) {
                        break;
                    }
                } else {
                    pendingUser = null;
                }
            }
            if (accumulator.hasFailed() || boundaryReached
                    || rows.size() < HISTORY_QUERY_BATCH_SIZE) {
                break;
            }
            if (scanCursor <= previousCursor) {
                accumulator.fail(MemoryCompressionResult.Status.MODEL_FAILED,
                        "历史游标没有向前推进");
                break;
            }
        }
    }

    private long requireNextHistoryId(
            ChatHistory history, long scanCursor) {
        if (history == null || history.getId() == null
                || history.getId() <= scanCursor) {
            throw new IllegalStateException("历史消息 ID 顺序无效");
        }
        return history.getId();
    }

    private ModelOutput generateAndReduce(
            Long appId,
            String oldSummary,
            String newMessages,
            long deadlineNanos) {
        String prompt = MemorySummaryPromptBuilder.build(
                oldSummary, newMessages);
        if (!isPromptWithinInputBudget(prompt)) {
            return ModelOutput.failure(
                    MemoryCompressionResult.Status.MODEL_FAILED,
                    "摘要模型输入超过硬上限");
        }
        ModelOutput generated = callModel(appId, prompt, deadlineNanos);
        if (generated.failureStatus() != null) {
            return generated;
        }
        return reduceToLimit(appId, generated.summary(), deadlineNanos);
    }

    private ModelOutput reduceToLimit(
            Long appId, String sourceSummary, long deadlineNanos) {
        String current = sourceSummary;
        int currentTokens = tokenEstimator.estimateText(current);
        while (currentTokens
                > MemoryTokenProperties.L1_MAX_SUMMARY_TOKENS) {
            if (isDeadlineExpired(deadlineNanos)) {
                return ModelOutput.failure(
                        MemoryCompressionResult.Status.TIMED_OUT,
                        "摘要压缩截止时间已到");
            }
            String prompt = SummaryCompressionPromptBuilder.build(current);
            if (!isPromptWithinInputBudget(prompt)) {
                return ModelOutput.failure(
                        MemoryCompressionResult.Status.OUTPUT_STILL_TOO_LARGE,
                        "现有摘要过大，无法进入 reducer");
            }
            ModelOutput reduced = callModel(
                    appId, prompt, deadlineNanos);
            if (reduced.failureStatus() != null) {
                return reduced;
            }
            int reducedTokens = tokenEstimator.estimateText(
                    reduced.summary());
            if (reduced.summary().equals(current)
                    || reducedTokens >= currentTokens) {
                return ModelOutput.failure(
                        MemoryCompressionResult.Status.OUTPUT_STILL_TOO_LARGE,
                        "摘要 reducer 未继续收敛");
            }
            current = reduced.summary();
            currentTokens = reducedTokens;
        }
        return ModelOutput.success(current, currentTokens);
    }

    private ModelOutput callModel(
            Long appId, String prompt, long deadlineNanos) {
        if (isDeadlineExpired(deadlineNanos)) {
            return ModelOutput.failure(
                    MemoryCompressionResult.Status.TIMED_OUT,
                    "摘要截止时间已到");
        }
        Future<String> modelCall;
        try {
            modelCall = modelExecutor.submit(() ->
                    summarizationModel.chat(prompt));
        } catch (RejectedExecutionException exception) {
            log.warn("摘要模型任务被拒绝 appId={}", appId);
            return ModelOutput.failure(
                    MemoryCompressionResult.Status.MODEL_FAILED,
                    "摘要模型执行器已满");
        } catch (RuntimeException exception) {
            log.error("提交摘要模型任务失败 appId={} type={}", appId,
                    exception.getClass().getSimpleName(), exception);
            return ModelOutput.failure(
                    MemoryCompressionResult.Status.MODEL_FAILED,
                    "提交摘要模型任务失败");
        }
        long remainingNanos = deadlineNanos - nanoTime.getAsLong();
        if (remainingNanos <= 0L) {
            modelCall.cancel(true);
            return ModelOutput.failure(
                    MemoryCompressionResult.Status.TIMED_OUT,
                    "摘要模型等待时间已耗尽");
        }
        try {
            String output = modelCall.get(
                    remainingNanos, TimeUnit.NANOSECONDS);
            if (isDeadlineExpired(deadlineNanos)) {
                return ModelOutput.failure(
                        MemoryCompressionResult.Status.TIMED_OUT,
                        "摘要模型返回时截止时间已到");
            }
            if (StrUtil.isBlank(output)) {
                return ModelOutput.failure(
                        MemoryCompressionResult.Status.MODEL_FAILED,
                        "摘要模型返回空内容");
            }
            return ModelOutput.success(
                    output, tokenEstimator.estimateText(output));
        } catch (TimeoutException exception) {
            modelCall.cancel(true);
            return ModelOutput.failure(
                    MemoryCompressionResult.Status.TIMED_OUT,
                    "摘要模型调用超时");
        } catch (InterruptedException exception) {
            modelCall.cancel(true);
            Thread.currentThread().interrupt();
            return ModelOutput.failure(
                    MemoryCompressionResult.Status.TIMED_OUT,
                    "等待摘要模型时被中断");
        } catch (CancellationException exception) {
            return ModelOutput.failure(
                    MemoryCompressionResult.Status.MODEL_FAILED,
                    "摘要模型任务被取消");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null
                    ? exception : exception.getCause();
            log.error("摘要模型调用失败 appId={} type={}", appId,
                    cause.getClass().getSimpleName(), cause);
            return ModelOutput.failure(
                    MemoryCompressionResult.Status.MODEL_FAILED,
                    "摘要模型调用失败");
        }
    }

    private boolean isPromptWithinInputBudget(String prompt) {
        return tokenEstimator.estimateText(prompt)
                < properties.getHardInputLimit();
    }

    private boolean isDeadlineExpired(long deadlineNanos) {
        return nanoTime.getAsLong() >= deadlineNanos;
    }

    private long currentCursor(AppMemorySummary current) {
        return current == null || current.getLastSummarizedId() == null
                ? 0L : current.getLastSummarizedId();
    }

    private boolean isUserMessage(ChatHistory history) {
        return ChatHistoryMessageTypeEnum.USER.getValue()
                .equals(history.getMessageType());
    }

    private boolean isAiMessage(ChatHistory history) {
        return ChatHistoryMessageTypeEnum.AI.getValue()
                .equals(history.getMessageType());
    }

    private final class RollingSummaryAccumulator {

        private final Long appId;
        private final long persistedCursor;
        private final long deadlineNanos;
        private final List<SummaryTurn> batch = new ArrayList<>();
        private String workingSummary;
        private int persistedSummaryTokens;
        private int workingTokens;
        private long summarizedThroughId;
        private boolean changed;
        private MemoryCompressionResult.Status failureStatus;
        private String failureDetail = "";

        private RollingSummaryAccumulator(
                Long appId,
                String oldSummary,
                long persistedCursor,
                long deadlineNanos) {
            this.appId = appId;
            this.workingSummary = oldSummary;
            this.persistedCursor = persistedCursor;
            this.summarizedThroughId = persistedCursor;
            this.deadlineNanos = deadlineNanos;
        }

        private void initialize() {
            persistedSummaryTokens = tokenEstimator.estimateText(
                    workingSummary);
            workingTokens = persistedSummaryTokens;
            if (workingTokens
                    <= MemoryTokenProperties.L1_MAX_SUMMARY_TOKENS) {
                return;
            }
            ModelOutput reduced = reduceToLimit(
                    appId, workingSummary, deadlineNanos);
            applyModelOutput(reduced);
            if (!hasFailed()) {
                changed = true;
            }
        }

        private void accept(SummaryTurn turn) {
            if (hasFailed()) {
                return;
            }
            String candidateMessages = renderBatch(turn);
            String prompt = MemorySummaryPromptBuilder.build(
                    workingSummary, candidateMessages);
            if (isPromptWithinInputBudget(prompt)) {
                batch.add(turn);
                return;
            }
            if (batch.isEmpty()) {
                fail(MemoryCompressionResult.Status.MODEL_FAILED,
                        "单个完整回合超过摘要输入预算");
                return;
            }
            flush();
            if (hasFailed()) {
                return;
            }
            String singleTurnPrompt = MemorySummaryPromptBuilder.build(
                    workingSummary, turn.render());
            if (!isPromptWithinInputBudget(singleTurnPrompt)) {
                fail(MemoryCompressionResult.Status.MODEL_FAILED,
                        "单个完整回合超过摘要输入预算");
                return;
            }
            batch.add(turn);
        }

        private DraftResult finish() {
            if (!hasFailed()) {
                flush();
            }
            if (hasFailed()) {
                return DraftResult.failure(
                        persistedCursor,
                        persistedSummaryTokens,
                        failureStatus,
                        failureDetail);
            }
            if (isDeadlineExpired(deadlineNanos)) {
                return DraftResult.failure(
                        persistedCursor,
                        persistedSummaryTokens,
                        MemoryCompressionResult.Status.TIMED_OUT,
                        "摘要草稿完成时截止时间已到");
            }
            return DraftResult.success(
                    workingSummary,
                    summarizedThroughId,
                    workingTokens,
                    changed);
        }

        private void flush() {
            if (batch.isEmpty() || hasFailed()) {
                return;
            }
            ModelOutput output = generateAndReduce(
                    appId,
                    workingSummary,
                    renderBatch(null),
                    deadlineNanos);
            applyModelOutput(output);
            if (hasFailed()) {
                return;
            }
            summarizedThroughId = batch.getLast().completedThroughId();
            changed = true;
            batch.clear();
        }

        private void applyModelOutput(ModelOutput output) {
            if (output.failureStatus() != null) {
                fail(output.failureStatus(), output.detail());
                return;
            }
            workingSummary = output.summary();
            workingTokens = output.tokens();
        }

        private String renderBatch(SummaryTurn additionalTurn) {
            StringBuilder rendered = new StringBuilder();
            for (SummaryTurn turn : batch) {
                rendered.append(turn.render());
            }
            if (additionalTurn != null) {
                rendered.append(additionalTurn.render());
            }
            return rendered.toString();
        }

        private boolean hasFailed() {
            return failureStatus != null;
        }

        private void fail(
                MemoryCompressionResult.Status status, String detail) {
            failureStatus = status;
            failureDetail = detail;
        }
    }

    private record SummaryTurn(
            long turnId,
            long completedThroughId,
            String userText,
            String aiText) {

        private SummaryTurn(ChatHistory user, ChatHistory ai) {
            this(user.getId(), ai.getId(),
                    StrUtil.nullToEmpty(user.getMessage()),
                    StrUtil.nullToEmpty(ai.getMessage()));
            if (turnId <= 0L || completedThroughId <= turnId) {
                throw new IllegalArgumentException("完整回合 ID 边界无效");
            }
        }

        private String render() {
            return "用户:\n" + userText + "\nAI:\n" + aiText + "\n";
        }
    }

    record DraftResult(
            String summary,
            long summarizedThroughId,
            int summaryTokens,
            boolean changed,
            MemoryCompressionResult.Status failureStatus,
            String detail) {

        private static DraftResult success(
                String summary,
                long summarizedThroughId,
                int summaryTokens,
                boolean changed) {
            return new DraftResult(summary, summarizedThroughId,
                    summaryTokens, changed, null, "");
        }

        private static DraftResult failure(
                long persistedCursor,
                int existingSummaryTokens,
                MemoryCompressionResult.Status status,
                String detail) {
            return new DraftResult("", persistedCursor,
                    existingSummaryTokens, false, status, detail);
        }
    }

    private record ModelOutput(
            String summary,
            int tokens,
            MemoryCompressionResult.Status failureStatus,
            String detail) {

        private static ModelOutput success(String summary, int tokens) {
            return new ModelOutput(summary, tokens, null, "");
        }

        private static ModelOutput failure(
                MemoryCompressionResult.Status status, String detail) {
            return new ModelOutput("", 0, status, detail);
        }
    }
}
