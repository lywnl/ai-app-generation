package com.lyw.appgeneration.service.impl;

import cn.hutool.core.util.StrUtil;
import com.lyw.appgeneration.ai.memory.ChatTokenEstimator;
import com.lyw.appgeneration.ai.memory.MemorySummaryContract;
import com.lyw.appgeneration.ai.memory.MemorySummaryFormat;
import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.mapper.AppMemorySummaryMapper;
import com.lyw.appgeneration.monitor.MemoryCompressionMetricsCollector;
import com.lyw.appgeneration.model.entity.AppMemorySummary;
import com.lyw.appgeneration.service.MemoryCacheInvalidationResult;
import com.lyw.appgeneration.service.MemoryCompressionResult;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** L1 唯一 3K 硬上限滚动摘要服务。 */
@Slf4j
@Service
public class MemorySummaryServiceImpl implements MemorySummaryService {

    private static final String CACHE_KEY_PREFIX = "mem:summary:";
    private static final Duration CACHE_TTL = Duration.ofHours(1);
    private static final Duration RETRY_BASE_DELAY = Duration.ofSeconds(5);
    private static final Duration RETRY_MAX_DELAY = Duration.ofMinutes(5);
    private static final int FALLBACK_RETRY_MAX_ENTRIES = 1_024;

    private final AppMemorySummaryMapper summaryMapper;
    private final MemorySummaryDraftEngine draftEngine;
    private final ExecutorService executor;
    private final StringRedisTemplate redisTemplate;
    private final AppDataLifecycleFence lifecycleFence;
    private final ChatTokenEstimator tokenEstimator;
    private final MemoryTokenProperties properties;
    private final MemoryCompressionMetricsCollector metricsCollector;
    private final PlatformTransactionManager transactionManager;
    private final TransactionOperations testTransactionOperations;
    private final Clock clock;
    private final AppMemorySummaryConsistencyCoordinator
            consistencyCoordinator =
            new AppMemorySummaryConsistencyCoordinator();

    private final ConcurrentHashMap<Long, CompletableFuture<MemoryCompressionResult>>
            inFlight = new ConcurrentHashMap<>();
    private final Map<Long, FallbackRetryState> fallbackRetryAfter =
            Collections.synchronizedMap(new LinkedHashMap<>(
                    16, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<Long, FallbackRetryState> eldest) {
                    return size() > FALLBACK_RETRY_MAX_ENTRIES;
                }
            });

    @Autowired
    public MemorySummaryServiceImpl(
            AppMemorySummaryMapper summaryMapper,
            MemorySummaryDraftEngine draftEngine,
            @Qualifier("memorySummarizationExecutor") ExecutorService executor,
            StringRedisTemplate redisTemplate,
            AppDataLifecycleFence lifecycleFence,
            ChatTokenEstimator tokenEstimator,
            MemoryTokenProperties properties,
            MemoryCompressionMetricsCollector metricsCollector,
            ObjectProvider<PlatformTransactionManager>
                    transactionManagerProvider) {
        this(summaryMapper, draftEngine, executor, redisTemplate,
                lifecycleFence, tokenEstimator, properties,
                metricsCollector,
                requireTransactionManager(transactionManagerProvider),
                null, Clock.systemDefaultZone());
    }

    MemorySummaryServiceImpl(
            AppMemorySummaryMapper summaryMapper,
            MemorySummaryDraftEngine draftEngine,
            ExecutorService executor,
            StringRedisTemplate redisTemplate,
            AppDataLifecycleFence lifecycleFence,
            ChatTokenEstimator tokenEstimator,
            MemoryTokenProperties properties,
            MemoryCompressionMetricsCollector metricsCollector,
            Clock clock) {
        this(summaryMapper, draftEngine, executor, redisTemplate,
                lifecycleFence, tokenEstimator, properties, metricsCollector,
                null, TransactionOperations.withoutTransaction(), clock);
    }

    MemorySummaryServiceImpl(
            AppMemorySummaryMapper summaryMapper,
            MemorySummaryDraftEngine draftEngine,
            ExecutorService executor,
            StringRedisTemplate redisTemplate,
            AppDataLifecycleFence lifecycleFence,
            ChatTokenEstimator tokenEstimator,
            MemoryTokenProperties properties,
            MemoryCompressionMetricsCollector metricsCollector,
            TransactionOperations transactionOperations,
            Clock clock) {
        this(summaryMapper, draftEngine, executor, redisTemplate,
                lifecycleFence, tokenEstimator, properties, metricsCollector,
                null, transactionOperations, clock);
    }

    private MemorySummaryServiceImpl(
            AppMemorySummaryMapper summaryMapper,
            MemorySummaryDraftEngine draftEngine,
            ExecutorService executor,
            StringRedisTemplate redisTemplate,
            AppDataLifecycleFence lifecycleFence,
            ChatTokenEstimator tokenEstimator,
            MemoryTokenProperties properties,
            MemoryCompressionMetricsCollector metricsCollector,
            PlatformTransactionManager transactionManager,
            TransactionOperations testTransactionOperations,
            Clock clock) {
        this.summaryMapper = Objects.requireNonNull(
                summaryMapper, "摘要 Mapper 不能为空");
        this.draftEngine = Objects.requireNonNull(
                draftEngine, "摘要草稿引擎不能为空");
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
        this.metricsCollector = Objects.requireNonNull(
                metricsCollector, "记忆压缩指标收集器不能为空");
        this.transactionManager = transactionManager;
        this.testTransactionOperations = testTransactionOperations;
        if (transactionManager == null && testTransactionOperations == null) {
            throw new IllegalArgumentException("L1 摘要事务执行器不能为空");
        }
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
    }

    private static PlatformTransactionManager requireTransactionManager(
            ObjectProvider<PlatformTransactionManager> provider) {
        Objects.requireNonNull(provider, "事务管理器提供器不能为空");
        PlatformTransactionManager manager = provider.getIfAvailable();
        if (manager == null) {
            throw new IllegalStateException("L1 摘要事务管理器不可用");
        }
        return manager;
    }

    @Override
    public void triggerSummarizationAsync(
            Long appId, long summarizeThroughId) {
        triggerSummarizationAsync(appId, summarizeThroughId, () -> true);
    }

    @Override
    public void triggerSummarizationAsync(
            Long appId,
            long summarizeThroughId,
            BooleanSupplier startPermit) {
        if (!isValidBoundary(appId, summarizeThroughId)
                || !hasLifecycleWriterAccess(appId)
                || !isFallbackRetryReady(appId)
                || startPermit == null) {
            return;
        }
        CompletableFuture<MemoryCompressionResult> flight =
                new CompletableFuture<>();
        if (inFlight.putIfAbsent(appId, flight) != null) {
            return;
        }
        MemoryCompressionResult completion = null;
        boolean taskSubmitted = false;
        try {
            try {
                executor.submit(() -> runBackgroundCompression(
                        appId, summarizeThroughId, flight, startPermit));
            } catch (RejectedExecutionException exception) {
                metricsCollector.recordCompressionExecutorRejected(
                        MemoryCompressionMetricsCollector.CompressionMode.ASYNC);
                throw exception;
            }
            taskSubmitted = true;
        } catch (RuntimeException exception) {
            completion = result(
                    MemoryCompressionResult.Status.MODEL_FAILED,
                    0L, 0, "摘要任务提交失败");
            ensureFallbackRetryDelayIfWritable(appId);
            log.warn("启动摘要任务失败 appId={} type={}",
                    appId, exception.getClass().getSimpleName());
        } finally {
            if (!taskSubmitted) {
                finishFlight(appId, flight, completion);
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
        if (!hasLifecycleWriterAccess(appId)) {
            return result(MemoryCompressionResult.Status.DELETE_REJECTED,
                    0L, 0, "应用删除流程已接管");
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

    private boolean hasLifecycleWriterAccess(Long appId) {
        try {
            return lifecycleFence.isOpen(appId);
        } catch (RuntimeException exception) {
            log.warn("检查摘要生命周期许可失败 appId={} type={}", appId,
                    exception.getClass().getSimpleName());
            return false;
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
        MemoryCompressionResult compressionResult = null;
        try {
            compressionResult = executeCompression(
                    appId, summarizeThroughId, deadlineNanos,
                    MemoryCompressionMetricsCollector.CompressionMode.BLOCKING,
                    () -> true);
            return new CompressionAttempt(compressionResult, false);
        } catch (RuntimeException exception) {
            log.error("同步摘要 owner 异常 appId={} type={}", appId,
                    exception.getClass().getSimpleName());
            compressionResult = result(
                    MemoryCompressionResult.Status.MODEL_FAILED,
                    0L, 0, "同步摘要任务异常");
            return new CompressionAttempt(compressionResult, false);
        } finally {
            finishFlight(appId, flight, compressionResult);
        }
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
            BooleanSupplier startPermit) {
        MemoryCompressionResult compressionResult = null;
        try {
            if (!startPermit.getAsBoolean()) {
                compressionResult = result(
                        MemoryCompressionResult.Status.NOTHING_TO_COMPRESS,
                        0L, 0, "回合已终止，跳过后台摘要");
                return;
            }
            compressionResult = checkBackgroundCompressionReady(appId);
            if (compressionResult != null) {
                return;
            }
            compressionResult = executeCompression(
                    appId,
                    summarizeThroughId,
                    deadlineNanos(properties.getBlockingTimeout()),
                    MemoryCompressionMetricsCollector.CompressionMode.ASYNC,
                    startPermit);
        } catch (RuntimeException exception) {
            log.error("后台摘要任务异常 appId={} type={}", appId,
                    exception.getClass().getSimpleName());
            ensureFallbackRetryDelayIfWritable(appId);
            compressionResult = result(
                    MemoryCompressionResult.Status.MODEL_FAILED,
                    0L, 0, "后台摘要任务异常");
        } finally {
            finishFlight(appId, flight, compressionResult);
        }
    }

    private MemoryCompressionResult checkBackgroundCompressionReady(
            Long appId) {
        AppMemorySummary current = null;
        try {
            AppDataLifecycleFence.WriterPermit writerPermit =
                    lifecycleFence.tryAcquireWriter(appId);
            if (writerPermit == null) {
                return result(MemoryCompressionResult.Status.DELETE_REJECTED,
                        0L, 0, "应用删除流程已接管");
            }
            try (writerPermit) {
                current = selectCurrentSummary(appId);
                if (!isDatabaseRetryReady(current)
                        || !isFallbackRetryReady(appId)) {
                    return result(
                            MemoryCompressionResult.Status.NOTHING_TO_COMPRESS,
                            currentCursor(current),
                            currentSummaryTokens(current),
                            "后台摘要退避尚未到期");
                }
            }
            return null;
        } catch (RuntimeException exception) {
            ensureFallbackRetryDelayIfWritable(appId);
            log.warn("读取摘要退避元数据失败 appId={} type={}",
                    appId, exception.getClass().getSimpleName());
            return result(MemoryCompressionResult.Status.MODEL_FAILED,
                    currentCursor(current), currentSummaryTokens(current),
                    "读取摘要退避元数据失败");
        }
    }

    private MemoryCompressionResult executeCompression(
            Long appId,
            long summarizeThroughId,
            long deadlineNanos,
            MemoryCompressionMetricsCollector.CompressionMode mode,
            BooleanSupplier modelStartPermit) {
        AppMemorySummary current = null;
        MemoryCompressionMetricsCollector.CompressionObservation observation =
                null;
        MemoryCompressionResult compressionResult = null;
        try {
            AppDataLifecycleFence.WriterPermit preparePermit =
                    lifecycleFence.tryAcquireWriter(appId);
            if (preparePermit == null) {
                return result(MemoryCompressionResult.Status.DELETE_REJECTED,
                        0L, 0, "应用删除流程已接管");
            }
            observation = metricsCollector.startCompression(mode);
            try (preparePermit) {
                current = selectCurrentSummary(appId);
            }
            MemorySummaryDraftEngine.DraftResult draft = draftEngine.buildDraft(
                    appId, summarizeThroughId, current, deadlineNanos,
                    modelStartPermit);
            AppDataLifecycleFence.WriterPermit commitPermit =
                    lifecycleFence.tryAcquireWriter(appId);
            if (commitPermit == null) {
                compressionResult = result(
                        MemoryCompressionResult.Status.DELETE_REJECTED,
                        currentCursor(current), currentSummaryTokens(current),
                        "应用删除流程已接管，丢弃迟到摘要结果");
                return compressionResult;
            }
            try {
                compressionResult = isDeadlineExpired(deadlineNanos)
                        ? timedOut(current,
                        "取得摘要提交许可时截止时间已到")
                        : commitDraftWithinPermit(
                                appId, current, draft, deadlineNanos);
                return compressionResult;
            } finally {
                closeWriterPermit(appId, commitPermit);
            }
        } catch (PersistenceCommitUncertainException exception) {
            log.error("摘要事务提交结果不确定 appId={} type={}", appId,
                    exception.getCause().getClass().getSimpleName());
            ensureFallbackRetryDelay(
                    appId, failureMetadata(current));
            compressionResult = result(
                    MemoryCompressionResult.Status.MODEL_FAILED,
                    currentCursor(current), currentSummaryTokens(current),
                    "摘要事务提交结果不确定，等待数据库事实重新确认");
            return compressionResult;
        } catch (RuntimeException exception) {
            log.error("摘要压缩异常 appId={} type={}", appId,
                    exception.getClass().getSimpleName());
            if (isDeadlineExpired(deadlineNanos)) {
                compressionResult = timedOut(
                        current, "摘要内部异常返回时截止时间已到");
                return compressionResult;
            }
            compressionResult = recordFailureIfWritable(
                    appId, current,
                    MemoryCompressionResult.Status.MODEL_FAILED,
                    "摘要压缩内部失败", deadlineNanos);
            return compressionResult;
        } finally {
            completeObservation(observation, compressionResult);
        }
    }

    private MemoryCompressionResult commitDraftWithinPermit(
            Long appId,
            AppMemorySummary current,
            MemorySummaryDraftEngine.DraftResult draft,
            long deadlineNanos) {
        try {
            if (draft.failureStatus() != null) {
                metricsCollector.recordSummaryDraftFailure(
                        draft.reducerRounds());
                if (draft.failureStatus()
                        == MemoryCompressionResult.Status.TIMED_OUT) {
                    return timedOut(current, draft.detail());
                }
                return recordFailureWithinDeadline(
                        appId, current, draft.failureStatus(),
                        draft.detail(), deadlineNanos);
            }
            metricsCollector.recordSummaryDraftSuccess(
                    draft.summaryTokens(), draft.reducerRounds());
            if (!draft.changed()) {
                PersistenceOutcome cleared =
                        clearPersistentFailureMetadataWithinDeadline(
                                current, deadlineNanos);
                if (cleared == PersistenceOutcome.TIMED_OUT) {
                    return timedOut(current,
                            "清除摘要失败元数据时截止时间已到");
                }
                clearFallbackRetryDelay(appId);
                return result(
                        MemoryCompressionResult.Status.NOTHING_TO_COMPRESS,
                        draft.summarizedThroughId(), draft.summaryTokens(),
                        "没有可压缩的稳定完整回合");
            }
            if (isDeadlineExpired(deadlineNanos)) {
                return timedOut(current, "摘要持久化前截止时间已到");
            }
            PersistenceOutcome persistence = upsertSummary(
                    appId, current, draft.summary(),
                    draft.summarizedThroughId(), draft.summaryTokens(),
                    deadlineNanos);
            if (persistence == PersistenceOutcome.TIMED_OUT) {
                return timedOut(current, "等待摘要一致性提交时截止时间已到");
            }
            clearFallbackRetryDelay(appId);
            return result(MemoryCompressionResult.Status.COMPRESSED,
                    draft.summarizedThroughId(), draft.summaryTokens(),
                    "摘要压缩完成");
        } catch (PersistenceCommitUncertainException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.error("摘要压缩异常 appId={} type={}", appId,
                    exception.getClass().getSimpleName());
            if (isDeadlineExpired(deadlineNanos)) {
                ensureFallbackRetryDelay(
                        appId, failureMetadata(current));
                return timedOut(current,
                        "摘要内部异常返回时截止时间已到");
            }
            return recordFailureWithinDeadline(
                    appId, current,
                    MemoryCompressionResult.Status.MODEL_FAILED,
                    "摘要压缩内部失败", deadlineNanos);
        }
    }

    private MemoryCompressionResult recordFailureWithinDeadline(
            Long appId,
            AppMemorySummary current,
            MemoryCompressionResult.Status status,
            String detail,
            long deadlineNanos) {
        FailureMetadata failureMetadata = failureMetadata(current);
        PersistenceOutcome persistence;
        try {
            persistence = executePersistenceTransaction(
                    deadlineNanos, transactionStatus -> {
                        persistFailureMetadata(
                                appId, current, failureMetadata);
                        if (isDeadlineExpired(deadlineNanos)) {
                            transactionStatus.setRollbackOnly();
                            return PersistenceOutcome.TIMED_OUT;
                        }
                        return PersistenceOutcome.PERSISTED;
                    });
        } catch (RuntimeException exception) {
            log.error("记录摘要失败元数据异常 appId={} type={}", appId,
                    exception.getClass().getSimpleName());
            ensureFallbackRetryDelay(
                    appId, failureMetadata);
            return isDeadlineExpired(deadlineNanos)
                    ? timedOut(current,
                    "记录摘要失败元数据时截止时间已到")
                    : result(status, currentCursor(current),
                    currentSummaryTokens(current), detail);
        }
        if (persistence == PersistenceOutcome.PERSISTED) {
            clearFallbackRetryDelay(appId);
            return result(status, currentCursor(current),
                    currentSummaryTokens(current), detail);
        }
        ensureFallbackRetryDelay(appId, failureMetadata);
        return timedOut(current,
                "记录摘要失败元数据时截止时间已到");
    }

    private PersistenceOutcome clearPersistentFailureMetadataWithinDeadline(
            AppMemorySummary current, long deadlineNanos) {
        return executePersistenceTransaction(
                deadlineNanos, transactionStatus -> {
                    clearPersistentFailureMetadata(current);
                    if (isDeadlineExpired(deadlineNanos)) {
                        transactionStatus.setRollbackOnly();
                        return PersistenceOutcome.TIMED_OUT;
                    }
                    return PersistenceOutcome.PERSISTED;
                });
    }

    private MemoryCompressionResult recordFailureIfWritable(
            Long appId,
            AppMemorySummary current,
            MemoryCompressionResult.Status status,
            String detail,
            long deadlineNanos) {
        AppDataLifecycleFence.WriterPermit writerPermit;
        try {
            writerPermit = lifecycleFence.tryAcquireWriter(appId);
        } catch (RuntimeException exception) {
            ensureFallbackRetryDelay(
                    appId, failureMetadata(current));
            return result(status, currentCursor(current),
                    currentSummaryTokens(current), detail);
        }
        if (writerPermit == null) {
            return result(MemoryCompressionResult.Status.DELETE_REJECTED,
                    currentCursor(current), currentSummaryTokens(current),
                    "应用删除流程已接管，丢弃迟到摘要失败结果");
        }
        MemoryCompressionResult result;
        try {
            result = recordFailureWithinDeadline(
                    appId, current, status, detail, deadlineNanos);
        } catch (RuntimeException exception) {
            ensureFallbackRetryDelay(
                    appId, failureMetadata(current));
            result = result(status, currentCursor(current),
                    currentSummaryTokens(current), detail);
        } finally {
            closeWriterPermit(appId, writerPermit);
        }
        return result;
    }

    private AppMemorySummary selectCurrentSummary(Long appId) {
        return summaryMapper.selectOneByQuery(
                QueryWrapper.create().eq("appId", appId));
    }

    private FailureMetadata failureMetadata(AppMemorySummary current) {
        int failCount = current == null || current.getFailCount() == null
                ? 1 : incrementFailCount(current.getFailCount());
        return new FailureMetadata(
                failCount,
                retryDelay(failCount),
                LocalDateTime.now(clock));
    }

    private int incrementFailCount(int failCount) {
        if (failCount >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, failCount) + 1;
    }

    private void persistFailureMetadata(
            Long appId,
            AppMemorySummary current,
            FailureMetadata failureMetadata) {
        LocalDateTime nextRetryTime = failureMetadata.createdAt()
                .plus(failureMetadata.retryDelay());
        if (current == null) {
            int affectedRows = summaryMapper.insert(
                    AppMemorySummary.builder()
                        .appId(appId)
                        .summary("")
                        .lastSummarizedId(0L)
                        .summaryTokens(0)
                        .failCount(failureMetadata.failCount())
                        .nextRetryTime(nextRetryTime)
                        .createTime(failureMetadata.createdAt())
                        .updateTime(failureMetadata.createdAt())
                        .build());
            requireExactlyOneRow(affectedRows, "新增摘要失败元数据");
            return;
        }
        AppMemorySummary failed = AppMemorySummary.builder()
                .id(current.getId())
                .appId(current.getAppId())
                .summary(current.getSummary())
                .lastSummarizedId(current.getLastSummarizedId())
                .summaryTokens(current.getSummaryTokens())
                .failCount(failureMetadata.failCount())
                .nextRetryTime(nextRetryTime)
                .createTime(current.getCreateTime())
                .updateTime(failureMetadata.createdAt())
                .isDelete(current.getIsDelete())
                .build();
        int affectedRows = summaryMapper.update(failed);
        requireExactlyOneRow(affectedRows, "更新摘要失败元数据");
    }

    private void ensureFallbackRetryDelay(Long appId) {
        ensureFallbackRetryDelay(appId, null);
    }

    private void ensureFallbackRetryDelayIfWritable(Long appId) {
        AppDataLifecycleFence.WriterPermit writerPermit;
        try {
            writerPermit = lifecycleFence.tryAcquireWriter(appId);
        } catch (RuntimeException exception) {
            log.error("获取摘要兜底退避写许可异常 appId={} type={}", appId,
                    exception.getClass().getSimpleName());
            return;
        }
        if (writerPermit == null) {
            return;
        }
        try (writerPermit) {
            ensureFallbackRetryDelay(appId);
        } catch (RuntimeException exception) {
            log.error("设置摘要兜底退避写许可异常 appId={} type={}", appId,
                    exception.getClass().getSimpleName());
        }
    }

    private void ensureFallbackRetryDelay(
            Long appId, FailureMetadata persistedCandidate) {
        try {
            synchronized (fallbackRetryAfter) {
                FallbackRetryState existing = fallbackRetryAfter.get(appId);
                int localFailCount = existing == null
                        ? 0 : existing.failCount();
                int persistedFailCount = persistedCandidate == null
                        ? 1 : persistedCandidate.failCount();
                int nextFailCount = Math.max(
                        incrementFailCount(localFailCount),
                        Math.max(1, persistedFailCount));
                Instant candidateRetryTime = clock.instant()
                        .plus(retryDelay(nextFailCount));
                Instant existingRetryTime = existing == null
                        ? null : existing.retryAfter();
                Instant nextRetryTime = existingRetryTime != null
                        && existingRetryTime.isAfter(candidateRetryTime)
                        ? existingRetryTime : candidateRetryTime;
                fallbackRetryAfter.put(appId,
                        new FallbackRetryState(nextFailCount, nextRetryTime));
            }
        } catch (RuntimeException exception) {
            log.error("设置摘要兜底退避异常 appId={} type={}", appId,
                    exception.getClass().getSimpleName());
        }
    }

    private void clearPersistentFailureMetadata(AppMemorySummary current) {
        if (current == null
                || ((current.getFailCount() == null
                || current.getFailCount() == 0)
                && current.getNextRetryTime() == null)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        AppMemorySummary cleared = AppMemorySummary.builder()
                .id(current.getId())
                .appId(current.getAppId())
                .summary(current.getSummary())
                .lastSummarizedId(current.getLastSummarizedId())
                .summaryTokens(current.getSummaryTokens())
                .failCount(0)
                .nextRetryTime(null)
                .createTime(current.getCreateTime())
                .updateTime(now)
                .isDelete(current.getIsDelete())
                .build();
        // cleared 复制了数据库行全部字段；false 用于显式写入 nextRetryTime=NULL。
        requireExactlyOneRow(summaryMapper.update(cleared, false),
                "清除摘要失败元数据");
    }

    private Duration retryDelay(int failCount) {
        int exponent = Math.min(Math.max(failCount - 1, 0), 6);
        long seconds = RETRY_BASE_DELAY.toSeconds() << exponent;
        return Duration.ofSeconds(Math.min(
                seconds, RETRY_MAX_DELAY.toSeconds()));
    }

    private PersistenceOutcome upsertSummary(
            Long appId,
            AppMemorySummary current,
            String summary,
            long summarizedThroughId,
            int summaryTokens,
            long deadlineNanos) {
        int verifiedSummaryTokens = tokenEstimator.estimateText(summary);
        if (!MemorySummaryFormat.isValid(summary)
                || summaryTokens != verifiedSummaryTokens
                || verifiedSummaryTokens
                > MemoryTokenProperties.L1_MAX_SUMMARY_TOKENS) {
            throw new IllegalStateException("摘要未满足 3K 落库门禁");
        }
        String cacheKey = CACHE_KEY_PREFIX + appId;
        AppMemorySummaryConsistencyCoordinator.Permit permit =
                consistencyCoordinator.tryAcquireUntil(appId, deadlineNanos);
        if (permit == null) {
            return PersistenceOutcome.TIMED_OUT;
        }
        try (permit) {
            if (isDeadlineExpired(deadlineNanos)) {
                return PersistenceOutcome.TIMED_OUT;
            }
            deleteCacheStrict(cacheKey, appId);
            if (isDeadlineExpired(deadlineNanos)) {
                return PersistenceOutcome.TIMED_OUT;
            }
            PersistenceOutcome persistence = executePersistenceTransaction(
                    deadlineNanos, status -> {
                        persistSummary(appId, current, summary,
                                summarizedThroughId, summaryTokens);
                        if (isDeadlineExpired(deadlineNanos)) {
                            status.setRollbackOnly();
                            return PersistenceOutcome.TIMED_OUT;
                        }
                        return PersistenceOutcome.PERSISTED;
                    });
            return persistence == null
                    ? PersistenceOutcome.TIMED_OUT : persistence;
        }
    }

    private PersistenceOutcome executePersistenceTransaction(
            long deadlineNanos,
            org.springframework.transaction.support
                    .TransactionCallback<PersistenceOutcome> callback) {
        TransactionOperations operations;
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            return PersistenceOutcome.TIMED_OUT;
        }
        if (transactionManager == null) {
            operations = testTransactionOperations;
        } else {
            long timeoutSeconds = remainingNanos
                    / TimeUnit.SECONDS.toNanos(1L);
            if (timeoutSeconds <= 0L) {
                return PersistenceOutcome.TIMED_OUT;
            }
            DefaultTransactionDefinition definition =
                    new DefaultTransactionDefinition();
            definition.setName("l1-summary-persistence");
            definition.setPropagationBehavior(
                    TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            definition.setTimeout((int) Math.min(
                    Integer.MAX_VALUE, timeoutSeconds));
            operations = new TransactionTemplate(
                    transactionManager, definition);
        }
        AtomicBoolean callbackCompleted = new AtomicBoolean();
        try {
            return operations.execute(status -> {
                PersistenceOutcome outcome = callback.doInTransaction(status);
                callbackCompleted.set(true);
                return outcome;
            });
        } catch (RuntimeException exception) {
            if (callbackCompleted.get()) {
                throw new PersistenceCommitUncertainException(exception);
            }
            throw exception;
        }
    }

    private MemoryCompressionResult timedOut(
            AppMemorySummary current, String detail) {
        return result(MemoryCompressionResult.Status.TIMED_OUT,
                currentCursor(current), currentSummaryTokens(current), detail);
    }

    private enum PersistenceOutcome {
        PERSISTED,
        TIMED_OUT
    }

    private record FailureMetadata(
            int failCount,
            Duration retryDelay,
            LocalDateTime createdAt) {
    }

    private record FallbackRetryState(
            int failCount,
            Instant retryAfter) {
    }

    private static final class PersistenceCommitUncertainException
            extends RuntimeException {

        private PersistenceCommitUncertainException(Throwable cause) {
            super(cause);
        }
    }

    private void persistSummary(
            Long appId,
            AppMemorySummary current,
            String summary,
            long summarizedThroughId,
            int summaryTokens) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (current == null) {
            int affectedRows = summaryMapper.insert(
                    AppMemorySummary.builder()
                            .appId(appId)
                            .summary(summary)
                            .lastSummarizedId(summarizedThroughId)
                            .summaryTokens(summaryTokens)
                            .failCount(0)
                            .nextRetryTime(null)
                            .createTime(now)
                            .updateTime(now)
                            .build());
            requireExactlyOneRow(affectedRows, "新增摘要");
            return;
        }
        // 更新实体覆盖完整数据库行；false 用于显式清除持久化退避时间。
        int affectedRows = summaryMapper.update(
                AppMemorySummary.builder()
                        .id(current.getId())
                        .appId(current.getAppId())
                        .summary(summary)
                        .lastSummarizedId(summarizedThroughId)
                        .summaryTokens(summaryTokens)
                        .failCount(0)
                        .nextRetryTime(null)
                        .createTime(current.getCreateTime())
                        .updateTime(now)
                        .isDelete(current.getIsDelete())
                        .build(), false);
        requireExactlyOneRow(affectedRows, "更新摘要");
    }

    private void requireExactlyOneRow(int affectedRows, String operation) {
        if (affectedRows != 1) {
            throw new IllegalStateException(
                    operation + "影响行数必须为 1，实际为 " + affectedRows);
        }
    }

    @Override
    public String getCurrentSummary(Long appId) {
        if (appId == null || appId <= 0L) {
            return "";
        }
        AppDataLifecycleFence.WriterPermit writerPermit;
        try {
            writerPermit = lifecycleFence.tryAcquireWriter(appId);
        } catch (RuntimeException exception) {
            log.warn("获取摘要读取许可失败 appId={} type={}", appId,
                    exception.getClass().getSimpleName());
            return "";
        }
        if (writerPermit == null) {
            return "";
        }
        try (writerPermit;
             AppMemorySummaryConsistencyCoordinator.Permit ignored =
                     consistencyCoordinator.acquire(appId)) {
            return readCurrentSummaryWithinPermit(appId);
        } catch (RuntimeException exception) {
            log.warn("释放摘要读取许可失败 appId={} type={}", appId,
                    exception.getClass().getSimpleName());
            return "";
        }
    }

    private String readCurrentSummaryWithinPermit(Long appId) {
        String cacheKey = CACHE_KEY_PREFIX + appId;
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (MemorySummaryContract.isRecallable(cached, tokenEstimator)) {
                return cached;
            }
            if (cached != null) {
                deleteInvalidCache(cacheKey, appId);
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
            long persistedCursor = current == null
                    || current.getLastSummarizedId() == null
                    ? 0L : current.getLastSummarizedId();
            if (!MemorySummaryContract.isUsablePersistedState(
                    summary, persistedCursor, tokenEstimator)) {
                log.warn("跳过不符合 L1 召回契约的摘要 appId={} estimatedTokens={}",
                        appId, tokenEstimator.estimateText(summary));
                summary = "";
            }
        } catch (RuntimeException exception) {
            log.warn("读取摘要失败 appId={} type={}", appId,
                    exception.getClass().getSimpleName());
            return "";
        }
        writeCache(cacheKey, summary);
        return summary;
    }

    @Override
    public String getRequiredSummary(
            Long appId, long summarizedThroughId) {
        requirePositiveId(appId, "应用 ID");
        if (summarizedThroughId <= 0L) {
            throw new IllegalArgumentException("摘要边界必须为正数");
        }
        AppDataLifecycleFence.WriterPermit writerPermit =
                lifecycleFence.tryAcquireWriter(appId);
        if (writerPermit == null) {
            throw new IllegalStateException("应用删除流程已接管，无法读取摘要");
        }
        try (writerPermit;
             AppMemorySummaryConsistencyCoordinator.Permit ignored =
                     consistencyCoordinator.acquire(appId)) {
            AppMemorySummary current = selectCurrentSummary(appId);
            String summary = current == null
                    ? "" : StrUtil.nullToEmpty(current.getSummary());
            long cursor = currentCursor(current);
            if (cursor < summarizedThroughId
                    || !MemorySummaryContract.isUsablePersistedState(
                    summary, cursor, tokenEstimator)) {
                throw new IllegalStateException("L1 摘要未覆盖指定边界");
            }
            return summary;
        }
    }

    private void deleteInvalidCache(String cacheKey, Long appId) {
        try {
            redisTemplate.delete(cacheKey);
        } catch (RuntimeException exception) {
            log.warn("失效非法摘要缓存失败 appId={} type={}", appId,
                    exception.getClass().getSimpleName());
        }
    }

    @Override
    public long lastSummarizedId(Long appId) {
        requirePositiveId(appId, "应用 ID");
        AppDataLifecycleFence.WriterPermit writerPermit =
                lifecycleFence.tryAcquireWriter(appId);
        if (writerPermit == null) {
            throw new IllegalStateException("应用删除流程已接管，无法读取摘要游标");
        }
        try (writerPermit) {
            try {
                return currentCursor(selectCurrentSummary(appId));
            } catch (RuntimeException exception) {
                throw new IllegalStateException(
                        "读取 L1 摘要游标失败，appId=" + appId,
                        exception);
            }
        }
    }

    private void writeCache(String cacheKey, String summary) {
        try {
            redisTemplate.opsForValue().set(cacheKey, summary, CACHE_TTL);
        } catch (RuntimeException exception) {
            log.warn("写摘要缓存失败 key={} type={}", cacheKey,
                    exception.getClass().getSimpleName());
        }
    }

    private void deleteCacheStrict(String cacheKey, Long appId) {
        try {
            redisTemplate.delete(cacheKey);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "失效 L1 摘要缓存失败，appId=" + appId,
                    exception);
        }
    }

    @Override
    public MemoryCacheInvalidationResult invalidateCache(Long appId) {
        requirePositiveId(appId, "应用 ID");
        clearFallbackRetryDelay(appId);
        inFlight.remove(appId);
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

    private void closeWriterPermit(
            Long appId,
            AppDataLifecycleFence.WriterPermit writerPermit) {
        if (writerPermit == null) {
            return;
        }
        try {
            writerPermit.close();
        } catch (RuntimeException exception) {
            log.error("释放摘要写许可异常 appId={} type={}", appId,
                    exception.getClass().getSimpleName());
        }
    }

    private void finishFlight(
            Long appId,
            CompletableFuture<MemoryCompressionResult> flight,
            MemoryCompressionResult compressionResult) {
        try {
            if (compressionResult == null) {
                flight.completeExceptionally(new IllegalStateException(
                        "摘要 owner 未产生可用结果"));
            } else {
                flight.complete(compressionResult);
            }
        } finally {
            inFlight.remove(appId, flight);
        }
    }

    private void completeObservation(
            MemoryCompressionMetricsCollector.CompressionObservation observation,
            MemoryCompressionResult compressionResult) {
        if (observation == null || compressionResult == null) {
            return;
        }
        observation.complete(compressionResult.status());
    }

    private boolean isDatabaseRetryReady(AppMemorySummary current) {
        LocalDateTime nextRetryTime = current == null
                ? null : current.getNextRetryTime();
        return nextRetryTime == null
                || !LocalDateTime.now(clock).isBefore(nextRetryTime);
    }

    private boolean isFallbackRetryReady(Long appId) {
        try {
            synchronized (fallbackRetryAfter) {
                FallbackRetryState state = fallbackRetryAfter.get(appId);
                if (state == null) {
                    return true;
                }
                return !clock.instant().isBefore(state.retryAfter());
            }
        } catch (RuntimeException exception) {
            log.error("检查摘要兜底退避异常 appId={} type={}", appId,
                    exception.getClass().getSimpleName());
            return false;
        }
    }

    private void clearFallbackRetryDelay(Long appId) {
        fallbackRetryAfter.remove(appId);
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
        if (current == null || current.getLastSummarizedId() == null) {
            return 0L;
        }
        long cursor = current.getLastSummarizedId();
        return MemorySummaryContract.isUsablePersistedState(
                current.getSummary(), cursor, tokenEstimator) ? cursor : 0L;
    }

    private int currentSummaryTokens(AppMemorySummary current) {
        if (current == null || currentCursor(current) == 0L) {
            return 0;
        }
        if (current.getSummaryTokens() != null) {
            return Math.max(0, current.getSummaryTokens());
        }
        return tokenEstimator.estimateText(
                StrUtil.nullToEmpty(current.getSummary()));
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

    private record CompressionAttempt(
            MemoryCompressionResult result,
            boolean joinedExistingFlight) {
    }
}
