package com.lyw.appgeneration.service.impl;

import cn.hutool.core.util.StrUtil;
import com.lyw.appgeneration.ai.memory.ChatTokenEstimator;
import com.lyw.appgeneration.ai.memory.ConservativeChatTokenEstimator;
import com.lyw.appgeneration.ai.memory.MemorySummaryPromptBuilder;
import com.lyw.appgeneration.ai.memory.SummaryCompressionPromptBuilder;
import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.mapper.AppMemorySummaryMapper;
import com.lyw.appgeneration.model.entity.AppMemorySummary;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.model.enums.ChatHistoryMessageTypeEnum;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemoryCacheInvalidationResult;
import com.lyw.appgeneration.service.MemoryCompressionResult;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.mybatisflex.core.query.QueryWrapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** L1 唯一 3K 硬上限滚动摘要服务。 */
@Slf4j
@Service
public class MemorySummaryServiceImpl implements MemorySummaryService {

    private static final int HISTORY_QUERY_BATCH_SIZE = 100;
    private static final String CACHE_KEY_PREFIX = "mem:summary:";
    private static final Duration CACHE_TTL = Duration.ofHours(1);
    private static final Duration RETRY_BASE_DELAY = Duration.ofSeconds(5);
    private static final Duration RETRY_MAX_DELAY = Duration.ofMinutes(5);

    private final ChatHistoryService chatHistoryService;
    private final AppMemorySummaryMapper summaryMapper;
    private final ChatModel summarizationModel;
    private final ExecutorService executor;
    private final StringRedisTemplate redisTemplate;
    private final AppDataLifecycleFence lifecycleFence;
    private final ChatTokenEstimator tokenEstimator;
    private final MemoryTokenProperties properties;
    private final Clock clock;

    private final ConcurrentHashMap<Long, CompletableFuture<MemoryCompressionResult>>
            inFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Instant> retryAfter =
            new ConcurrentHashMap<>();

    @Autowired
    public MemorySummaryServiceImpl(
            ChatHistoryService chatHistoryService,
            AppMemorySummaryMapper summaryMapper,
            @Qualifier("openAiChatModel") ChatModel summarizationModel,
            @Qualifier("memorySummarizationExecutor") ExecutorService executor,
            StringRedisTemplate redisTemplate,
            AppDataLifecycleFence lifecycleFence,
            ChatTokenEstimator tokenEstimator,
            MemoryTokenProperties properties) {
        this(chatHistoryService, summaryMapper, summarizationModel, executor,
                redisTemplate, lifecycleFence, tokenEstimator, properties,
                Clock.systemUTC());
    }

    /** 保留现有集成测试和非 Spring 直接构造方式。 */
    public MemorySummaryServiceImpl(
            ChatHistoryService chatHistoryService,
            AppMemorySummaryMapper summaryMapper,
            ChatModel summarizationModel,
            ExecutorService executor,
            StringRedisTemplate redisTemplate,
            AppDataLifecycleFence lifecycleFence) {
        this(chatHistoryService, summaryMapper, summarizationModel, executor,
                redisTemplate, lifecycleFence, legacyDefaults());
    }

    private MemorySummaryServiceImpl(
            ChatHistoryService chatHistoryService,
            AppMemorySummaryMapper summaryMapper,
            ChatModel summarizationModel,
            ExecutorService executor,
            StringRedisTemplate redisTemplate,
            AppDataLifecycleFence lifecycleFence,
            LegacyDefaults defaults) {
        this(chatHistoryService, summaryMapper, summarizationModel, executor,
                redisTemplate, lifecycleFence, defaults.estimator(),
                defaults.properties(), Clock.systemUTC());
    }

    MemorySummaryServiceImpl(
            ChatHistoryService chatHistoryService,
            AppMemorySummaryMapper summaryMapper,
            ChatModel summarizationModel,
            ExecutorService executor,
            StringRedisTemplate redisTemplate,
            AppDataLifecycleFence lifecycleFence,
            ChatTokenEstimator tokenEstimator,
            MemoryTokenProperties properties,
            Clock clock) {
        this.chatHistoryService = Objects.requireNonNull(
                chatHistoryService, "对话历史服务不能为空");
        this.summaryMapper = Objects.requireNonNull(
                summaryMapper, "摘要 Mapper 不能为空");
        this.summarizationModel = Objects.requireNonNull(
                summarizationModel, "摘要模型不能为空");
        this.executor = Objects.requireNonNull(
                executor, "摘要执行器不能为空");
        this.redisTemplate = Objects.requireNonNull(
                redisTemplate, "Redis 模板不能为空");
        this.lifecycleFence = Objects.requireNonNull(
                lifecycleFence, "应用数据生命周期栅栏不能为空");
        this.tokenEstimator = Objects.requireNonNull(
                tokenEstimator, "Token 估算器不能为空");
        this.properties = Objects.requireNonNull(
                properties, "Token 配置不能为空");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
    }

    @Override
    public void triggerSummarizationAsync(
            Long appId, long summarizeThroughId) {
        if (!isValidBoundary(appId, summarizeThroughId)
                || !isBackgroundRetryReady(appId)) {
            return;
        }
        CompletableFuture<MemoryCompressionResult> flight =
                new CompletableFuture<>();
        if (inFlight.putIfAbsent(appId, flight) != null) {
            return;
        }
        AppDataLifecycleFence.WriterPermit writerPermit =
                lifecycleFence.tryAcquireWriter(appId);
        if (writerPermit == null) {
            completeFlight(appId, flight, result(
                    MemoryCompressionResult.Status.DELETE_REJECTED,
                    0L, 0, "应用删除流程已接管"));
            return;
        }
        try {
            executor.submit(() -> runBackgroundCompression(
                    appId, summarizeThroughId, flight, writerPermit));
        } catch (RuntimeException exception) {
            MemoryCompressionResult failure = result(
                    MemoryCompressionResult.Status.MODEL_FAILED,
                    0L, 0, "摘要任务提交失败");
            try (writerPermit) {
                try {
                    failure = recordFailure(
                            appId,
                            selectCurrentSummary(appId),
                            MemoryCompressionResult.Status.MODEL_FAILED,
                            "摘要任务提交失败");
                } catch (RuntimeException metadataException) {
                    retryAfter.put(appId,
                            clock.instant().plus(RETRY_BASE_DELAY));
                    log.error("记录摘要提交失败元数据异常 appId={} type={}",
                            appId,
                            metadataException.getClass().getSimpleName(),
                            metadataException);
                }
            } catch (RuntimeException closeException) {
                log.error("释放摘要写许可异常 appId={} type={}", appId,
                        closeException.getClass().getSimpleName(),
                        closeException);
            } finally {
                log.warn("提交摘要任务失败 appId={} type={}",
                        appId, exception.getClass().getSimpleName());
                completeFlight(appId, flight, failure);
            }
        }
    }

    @Override
    public MemoryCompressionResult compressNow(
            Long appId, long summarizeThroughId, Duration timeout) {
        requirePositiveId(appId, "应用 ID");
        if (summarizeThroughId <= 0L) {
            throw new IllegalArgumentException("摘要边界必须为正数");
        }
        Objects.requireNonNull(timeout, "压缩超时不能为空");
        if (timeout.isZero() || timeout.isNegative()) {
            return result(MemoryCompressionResult.Status.TIMED_OUT,
                    0L, 0, "摘要等待时间已耗尽");
        }
        long deadlineNanos = deadlineNanos(timeout);
        while (true) {
            CompressionAttempt attempt = attemptCompression(
                    appId, summarizeThroughId, deadlineNanos);
            MemoryCompressionResult compressionResult = attempt.result();
            if (!attempt.joinedExistingFlight()
                    || !canContinueAfterEarlierFlight(
                            compressionResult, summarizeThroughId)) {
                return compressionResult;
            }
            if (isDeadlineExpired(deadlineNanos)) {
                return result(MemoryCompressionResult.Status.TIMED_OUT,
                        compressionResult.summarizedThroughId(),
                        compressionResult.summaryTokens(),
                        "等待较早摘要任务后截止时间已到");
            }
        }
    }

    private CompressionAttempt attemptCompression(
            Long appId,
            long summarizeThroughId,
            long deadlineNanos) {
        CompletableFuture<MemoryCompressionResult> flight =
                new CompletableFuture<>();
        CompletableFuture<MemoryCompressionResult> existing =
                inFlight.putIfAbsent(appId, flight);
        if (existing != null) {
            return new CompressionAttempt(
                    awaitExistingFlight(existing, deadlineNanos), true);
        }
        MemoryCompressionResult compressionResult;
        AppDataLifecycleFence.WriterPermit writerPermit =
                lifecycleFence.tryAcquireWriter(appId);
        if (writerPermit == null) {
            compressionResult = result(
                    MemoryCompressionResult.Status.DELETE_REJECTED,
                    0L, 0, "应用删除流程已接管");
        } else {
            try (writerPermit) {
                compressionResult = compressWithinPermit(
                        appId, summarizeThroughId, deadlineNanos);
            }
        }
        completeFlight(appId, flight, compressionResult);
        return new CompressionAttempt(compressionResult, false);
    }

    private boolean canContinueAfterEarlierFlight(
            MemoryCompressionResult compressionResult,
            long requestedBoundary) {
        return compressionResult.summarizedThroughId() < requestedBoundary
                && (compressionResult.status()
                == MemoryCompressionResult.Status.COMPRESSED
                || compressionResult.status()
                == MemoryCompressionResult.Status.NOTHING_TO_COMPRESS);
    }

    /** 旧测试入口；生产在线路径只使用带明确边界的接口。 */
    @Deprecated
    public void summarizeNow(Long appId) {
        if (appId == null || appId <= 0L) {
            return;
        }
        compressNow(appId, Long.MAX_VALUE, properties.getBlockingTimeout());
    }

    private void runBackgroundCompression(
            Long appId,
            long summarizeThroughId,
            CompletableFuture<MemoryCompressionResult> flight,
            AppDataLifecycleFence.WriterPermit writerPermit) {
        MemoryCompressionResult compressionResult;
        try (writerPermit) {
            compressionResult = compressWithinPermit(
                    appId,
                    summarizeThroughId,
                    deadlineNanos(properties.getBlockingTimeout()));
        } catch (RuntimeException exception) {
            log.error("后台摘要任务异常 appId={} type={}", appId,
                    exception.getClass().getSimpleName(), exception);
            compressionResult = result(
                    MemoryCompressionResult.Status.MODEL_FAILED,
                    0L, 0, "后台摘要任务异常");
        }
        completeFlight(appId, flight, compressionResult);
    }

    private MemoryCompressionResult compressWithinPermit(
            Long appId, long summarizeThroughId, long deadlineNanos) {
        AppMemorySummary current = null;
        try {
            current = selectCurrentSummary(appId);
            DraftResult draft = buildDraft(
                    appId, summarizeThroughId, current, deadlineNanos);
            if (draft.failureStatus() != null) {
                return recordFailure(appId, current,
                        draft.failureStatus(), draft.detail());
            }
            if (!draft.changed()) {
                retryAfter.remove(appId);
                return result(
                        MemoryCompressionResult.Status.NOTHING_TO_COMPRESS,
                        draft.summarizedThroughId(), draft.summaryTokens(),
                        "没有可压缩的稳定完整回合");
            }
            upsertSummary(appId, current, draft.summary(),
                    draft.summarizedThroughId(), draft.summaryTokens());
            retryAfter.remove(appId);
            return result(MemoryCompressionResult.Status.COMPRESSED,
                    draft.summarizedThroughId(), draft.summaryTokens(),
                    "摘要压缩完成");
        } catch (RuntimeException exception) {
            log.error("摘要压缩异常 appId={} type={}", appId,
                    exception.getClass().getSimpleName(), exception);
            return recordFailure(appId, current,
                    MemoryCompressionResult.Status.MODEL_FAILED,
                    "摘要压缩内部失败");
        }
    }

    private DraftResult buildDraft(
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
        return accumulator.finish();
    }

    private long requireNextHistoryId(ChatHistory history, long scanCursor) {
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
                oldSummary,
                newMessages,
                properties.getL1MaxSummaryTokens());
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
        while (currentTokens > properties.getL1MaxSummaryTokens()) {
            if (isDeadlineExpired(deadlineNanos)) {
                return ModelOutput.failure(
                        MemoryCompressionResult.Status.TIMED_OUT,
                        "摘要压缩截止时间已到");
            }
            String prompt = SummaryCompressionPromptBuilder.build(
                    current, properties.getL1MaxSummaryTokens());
            if (!isPromptWithinInputBudget(prompt)) {
                return ModelOutput.failure(
                        MemoryCompressionResult.Status.OUTPUT_STILL_TOO_LARGE,
                        "现有摘要过大，无法进入 reducer");
            }
            ModelOutput reduced = callModel(appId, prompt, deadlineNanos);
            if (reduced.failureStatus() != null) {
                return reduced;
            }
            int reducedTokens = tokenEstimator.estimateText(reduced.summary());
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
        String output;
        try {
            output = summarizationModel.chat(prompt);
        } catch (RuntimeException exception) {
            log.error("摘要模型调用失败 appId={} type={}", appId,
                    exception.getClass().getSimpleName(), exception);
            return ModelOutput.failure(
                    MemoryCompressionResult.Status.MODEL_FAILED,
                    "摘要模型调用失败");
        }
        if (isDeadlineExpired(deadlineNanos)) {
            return ModelOutput.failure(
                    MemoryCompressionResult.Status.TIMED_OUT,
                    "摘要模型返回时已超时");
        }
        if (StrUtil.isBlank(output)) {
            return ModelOutput.failure(
                    MemoryCompressionResult.Status.MODEL_FAILED,
                    "摘要模型返回空内容");
        }
        return ModelOutput.success(output, tokenEstimator.estimateText(output));
    }

    private boolean isPromptWithinInputBudget(String prompt) {
        return tokenEstimator.estimateText(prompt)
                < properties.getHardInputLimit();
    }

    private AppMemorySummary selectCurrentSummary(Long appId) {
        return summaryMapper.selectOneByQuery(
                QueryWrapper.create().eq("appId", appId));
    }

    private MemoryCompressionResult recordFailure(
            Long appId,
            AppMemorySummary current,
            MemoryCompressionResult.Status status,
            String detail) {
        int failCount = current == null || current.getFailCount() == null
                ? 1 : current.getFailCount() + 1;
        try {
            if (current == null) {
                LocalDateTime now = LocalDateTime.now(clock);
                summaryMapper.insert(AppMemorySummary.builder()
                        .appId(appId)
                        .summary("")
                        .lastSummarizedId(0L)
                        .summaryTokens(0)
                        .failCount(failCount)
                        .createTime(now)
                        .updateTime(now)
                        .build());
            } else {
                current.setFailCount(failCount);
                current.setUpdateTime(LocalDateTime.now(clock));
                summaryMapper.update(current);
            }
        } catch (RuntimeException exception) {
            log.error("记录摘要失败元数据异常 appId={} type={}", appId,
                    exception.getClass().getSimpleName(), exception);
        }
        retryAfter.put(appId,
                clock.instant().plus(retryDelay(failCount)));
        return result(status, currentCursor(current),
                currentSummaryTokens(current), detail);
    }

    private Duration retryDelay(int failCount) {
        int exponent = Math.min(Math.max(failCount - 1, 0), 6);
        long seconds = RETRY_BASE_DELAY.toSeconds() << exponent;
        return Duration.ofSeconds(Math.min(
                seconds, RETRY_MAX_DELAY.toSeconds()));
    }

    private void upsertSummary(
            Long appId,
            AppMemorySummary current,
            String summary,
            long summarizedThroughId,
            int summaryTokens) {
        if (StrUtil.isBlank(summary)
                || summaryTokens > properties.getL1MaxSummaryTokens()) {
            throw new IllegalStateException("摘要未满足 3K 落库门禁");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (current == null) {
            summaryMapper.insert(AppMemorySummary.builder()
                    .appId(appId)
                    .summary(summary)
                    .lastSummarizedId(summarizedThroughId)
                    .summaryTokens(summaryTokens)
                    .failCount(0)
                    .createTime(now)
                    .updateTime(now)
                    .build());
        } else {
            summaryMapper.update(AppMemorySummary.builder()
                    .id(current.getId())
                    .appId(current.getAppId())
                    .summary(summary)
                    .lastSummarizedId(summarizedThroughId)
                    .summaryTokens(summaryTokens)
                    .failCount(0)
                    .createTime(current.getCreateTime())
                    .updateTime(now)
                    .isDelete(current.getIsDelete())
                    .build());
        }
        writeCache(CACHE_KEY_PREFIX + appId, summary);
    }

    @Override
    public String getCurrentSummary(Long appId) {
        String cacheKey = CACHE_KEY_PREFIX + appId;
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return cached;
            }
        } catch (RuntimeException exception) {
            log.warn("读取摘要缓存失败 appId={} type={}", appId,
                    exception.getClass().getSimpleName());
        }
        String summary;
        try {
            AppMemorySummary current = selectCurrentSummary(appId);
            summary = current == null
                    ? "" : StrUtil.nullToEmpty(current.getSummary());
        } catch (RuntimeException exception) {
            log.warn("读取摘要失败 appId={} type={}", appId,
                    exception.getClass().getSimpleName());
            return "";
        }
        writeCache(cacheKey, summary);
        return summary;
    }

    private void writeCache(String cacheKey, String summary) {
        try {
            redisTemplate.opsForValue().set(cacheKey, summary, CACHE_TTL);
        } catch (RuntimeException exception) {
            log.warn("写摘要缓存失败 key={} type={}", cacheKey,
                    exception.getClass().getSimpleName());
        }
    }

    @Override
    public MemoryCacheInvalidationResult invalidateCache(Long appId) {
        requirePositiveId(appId, "应用 ID");
        try {
            redisTemplate.delete(CACHE_KEY_PREFIX + appId);
            return MemoryCacheInvalidationResult.success();
        } catch (RuntimeException exception) {
            log.warn("清理 L1 摘要缓存失败 appId={} type={}", appId,
                    exception.getClass().getSimpleName());
            return MemoryCacheInvalidationResult.failure(
                    "L1_SUMMARY_REDIS", exception);
        }
    }

    private MemoryCompressionResult awaitExistingFlight(
            CompletableFuture<MemoryCompressionResult> existing,
            long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            return result(MemoryCompressionResult.Status.TIMED_OUT,
                    0L, 0, "等待已有摘要任务超时");
        }
        try {
            return existing.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            return result(MemoryCompressionResult.Status.TIMED_OUT,
                    0L, 0, "等待已有摘要任务超时");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return result(MemoryCompressionResult.Status.TIMED_OUT,
                    0L, 0, "等待摘要任务被中断");
        } catch (ExecutionException exception) {
            return result(MemoryCompressionResult.Status.MODEL_FAILED,
                    0L, 0, "已有摘要任务异常结束");
        }
    }

    private void completeFlight(
            Long appId,
            CompletableFuture<MemoryCompressionResult> flight,
            MemoryCompressionResult compressionResult) {
        flight.complete(compressionResult);
        inFlight.remove(appId, flight);
    }

    private boolean isBackgroundRetryReady(Long appId) {
        Instant nextRetryTime = retryAfter.get(appId);
        return nextRetryTime == null
                || !clock.instant().isBefore(nextRetryTime);
    }

    private boolean isValidBoundary(Long appId, long summarizeThroughId) {
        return appId != null && appId > 0L && summarizeThroughId > 0L;
    }

    private long deadlineNanos(Duration timeout) {
        long now = System.nanoTime();
        long timeoutNanos;
        try {
            timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
        return timeoutNanos >= Long.MAX_VALUE - now
                ? Long.MAX_VALUE : now + timeoutNanos;
    }

    private boolean isDeadlineExpired(long deadlineNanos) {
        return System.nanoTime() >= deadlineNanos;
    }

    private long currentCursor(AppMemorySummary current) {
        return current == null || current.getLastSummarizedId() == null
                ? 0L : current.getLastSummarizedId();
    }

    private int currentSummaryTokens(AppMemorySummary current) {
        if (current == null) {
            return 0;
        }
        if (current.getSummaryTokens() != null) {
            return Math.max(0, current.getSummaryTokens());
        }
        return tokenEstimator.estimateText(
                StrUtil.nullToEmpty(current.getSummary()));
    }

    private boolean isUserMessage(ChatHistory history) {
        return ChatHistoryMessageTypeEnum.USER.getValue()
                .equals(history.getMessageType());
    }

    private boolean isAiMessage(ChatHistory history) {
        return ChatHistoryMessageTypeEnum.AI.getValue()
                .equals(history.getMessageType());
    }

    private void requirePositiveId(Long id, String name) {
        if (id == null || id <= 0L) {
            throw new IllegalArgumentException(name + " 必须为正数");
        }
    }

    private MemoryCompressionResult result(
            MemoryCompressionResult.Status status,
            long summarizedThroughId,
            int summaryTokens,
            String detail) {
        return new MemoryCompressionResult(
                status, summarizedThroughId, summaryTokens, detail);
    }

    private static LegacyDefaults legacyDefaults() {
        MemoryTokenProperties properties = new MemoryTokenProperties();
        return new LegacyDefaults(
                new ConservativeChatTokenEstimator(properties), properties);
    }

    private final class RollingSummaryAccumulator {

        private final Long appId;
        private final long persistedCursor;
        private final long deadlineNanos;
        private final List<SummaryTurn> batch = new ArrayList<>();
        private String workingSummary;
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
            workingTokens = tokenEstimator.estimateText(workingSummary);
            if (workingTokens <= properties.getL1MaxSummaryTokens()) {
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
                    workingSummary,
                    candidateMessages,
                    properties.getL1MaxSummaryTokens());
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
                    workingSummary,
                    turn.render(),
                    properties.getL1MaxSummaryTokens());
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
                        tokenEstimator.estimateText(workingSummary),
                        failureStatus,
                        failureDetail);
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

    private record DraftResult(
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

    private record LegacyDefaults(
            ChatTokenEstimator estimator,
            MemoryTokenProperties properties) {
    }

    private record CompressionAttempt(
            MemoryCompressionResult result,
            boolean joinedExistingFlight) {
    }
}
