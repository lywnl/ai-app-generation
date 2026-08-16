package com.lyw.appgeneration.service.impl;

import cn.hutool.core.util.StrUtil;
import com.lyw.appgeneration.ai.memory.ChatTokenEstimator;
import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.mapper.AppMemorySummaryMapper;
import com.lyw.appgeneration.model.entity.AppMemorySummary;
import com.lyw.appgeneration.service.MemoryCacheInvalidationResult;
import com.lyw.appgeneration.service.MemoryCompressionResult;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    private final Clock clock;

    private final ConcurrentHashMap<Long, CompletableFuture<MemoryCompressionResult>>
            inFlight = new ConcurrentHashMap<>();
    private final Map<Long, Instant> fallbackRetryAfter =
            Collections.synchronizedMap(new LinkedHashMap<>(
                    16, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<Long, Instant> eldest) {
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
            MemoryTokenProperties properties) {
        this(summaryMapper, draftEngine, executor, redisTemplate,
                lifecycleFence, tokenEstimator, properties,
                Clock.systemDefaultZone());
    }

    MemorySummaryServiceImpl(
            AppMemorySummaryMapper summaryMapper,
            MemorySummaryDraftEngine draftEngine,
            ExecutorService executor,
            StringRedisTemplate redisTemplate,
            AppDataLifecycleFence lifecycleFence,
            ChatTokenEstimator tokenEstimator,
            MemoryTokenProperties properties,
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
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
    }

    @Override
    public void triggerSummarizationAsync(
            Long appId, long summarizeThroughId) {
        if (!isValidBoundary(appId, summarizeThroughId)
                || !isFallbackRetryReady(appId)) {
            return;
        }
        CompletableFuture<MemoryCompressionResult> flight =
                new CompletableFuture<>();
        if (inFlight.putIfAbsent(appId, flight) != null) {
            return;
        }
        AppDataLifecycleFence.WriterPermit writerPermit = null;
        AppMemorySummary current = null;
        MemoryCompressionResult completion = null;
        boolean ownershipTransferred = false;
        try {
            writerPermit = lifecycleFence.tryAcquireWriter(appId);
            if (writerPermit == null) {
                completion = result(
                        MemoryCompressionResult.Status.DELETE_REJECTED,
                        0L, 0, "应用删除流程已接管");
                return;
            }
            try {
                current = selectCurrentSummary(appId);
                if (!isDatabaseRetryReady(current)
                        || !isFallbackRetryReady(appId)) {
                    completion = result(
                            MemoryCompressionResult.Status.NOTHING_TO_COMPRESS,
                            currentCursor(current),
                            currentSummaryTokens(current),
                            "后台摘要退避尚未到期");
                    return;
                }
            } catch (RuntimeException exception) {
                completion = result(
                        MemoryCompressionResult.Status.MODEL_FAILED,
                        currentCursor(current),
                        currentSummaryTokens(current),
                        "读取摘要退避元数据失败");
                ensureFallbackRetryDelay(appId);
                log.warn("读取摘要退避元数据失败 appId={} type={}",
                        appId, exception.getClass().getSimpleName());
                return;
            }
            AppDataLifecycleFence.WriterPermit taskPermit = writerPermit;
            executor.submit(() -> runBackgroundCompression(
                    appId, summarizeThroughId, flight, taskPermit));
            ownershipTransferred = true;
            writerPermit = null;
        } catch (RuntimeException exception) {
            completion = result(
                    MemoryCompressionResult.Status.MODEL_FAILED,
                    0L, 0, "摘要任务提交失败");
            if (writerPermit == null) {
                ensureFallbackRetryDelayIfWritable(appId);
            } else {
                ensureFallbackRetryDelay(appId);
            }
            log.warn("启动摘要任务失败 appId={} type={}",
                    appId, exception.getClass().getSimpleName());
        } finally {
            if (!ownershipTransferred) {
                try {
                    closeWriterPermit(appId, writerPermit);
                } finally {
                    finishFlight(appId, flight, completion);
                }
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
        MemoryCompressionResult compressionResult = null;
        try {
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
            return new CompressionAttempt(compressionResult, false);
        } catch (RuntimeException exception) {
            log.error("同步摘要 owner 异常 appId={} type={}", appId,
                    exception.getClass().getSimpleName(), exception);
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
            AppDataLifecycleFence.WriterPermit writerPermit) {
        MemoryCompressionResult compressionResult = null;
        try (writerPermit) {
            compressionResult = compressWithinPermit(
                    appId,
                    summarizeThroughId,
                    deadlineNanos(properties.getBlockingTimeout()));
        } catch (RuntimeException exception) {
            log.error("后台摘要任务异常 appId={} type={}", appId,
                    exception.getClass().getSimpleName(), exception);
            ensureFallbackRetryDelayIfWritable(appId);
            compressionResult = result(
                    MemoryCompressionResult.Status.MODEL_FAILED,
                    0L, 0, "后台摘要任务异常");
        } finally {
            finishFlight(appId, flight, compressionResult);
        }
    }

    private MemoryCompressionResult compressWithinPermit(
            Long appId, long summarizeThroughId, long deadlineNanos) {
        AppMemorySummary current = null;
        try {
            current = selectCurrentSummary(appId);
            MemorySummaryDraftEngine.DraftResult draft = draftEngine.buildDraft(
                    appId, summarizeThroughId, current, deadlineNanos);
            if (draft.failureStatus() != null) {
                return recordFailure(appId, current,
                        draft.failureStatus(), draft.detail());
            }
            if (!draft.changed()) {
                clearPersistentFailureMetadata(current);
                clearFallbackRetryDelay(appId);
                return result(
                        MemoryCompressionResult.Status.NOTHING_TO_COMPRESS,
                        draft.summarizedThroughId(), draft.summaryTokens(),
                        "没有可压缩的稳定完整回合");
            }
            upsertSummary(appId, current, draft.summary(),
                    draft.summarizedThroughId(), draft.summaryTokens());
            clearFallbackRetryDelay(appId);
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
        Duration delay = retryDelay(failCount);
        LocalDateTime now;
        LocalDateTime nextRetryTime;
        try {
            now = LocalDateTime.now(clock);
            nextRetryTime = now.plus(delay);
            if (current == null) {
                int affectedRows = summaryMapper.insert(
                        AppMemorySummary.builder()
                        .appId(appId)
                        .summary("")
                        .lastSummarizedId(0L)
                        .summaryTokens(0)
                        .failCount(failCount)
                        .nextRetryTime(nextRetryTime)
                        .createTime(now)
                        .updateTime(now)
                        .build());
                requireExactlyOneRow(affectedRows, "新增摘要失败元数据");
            } else {
                AppMemorySummary failed = AppMemorySummary.builder()
                        .id(current.getId())
                        .appId(current.getAppId())
                        .summary(current.getSummary())
                        .lastSummarizedId(current.getLastSummarizedId())
                        .summaryTokens(current.getSummaryTokens())
                        .failCount(failCount)
                        .nextRetryTime(nextRetryTime)
                        .createTime(current.getCreateTime())
                        .updateTime(now)
                        .isDelete(current.getIsDelete())
                        .build();
                int affectedRows = summaryMapper.update(failed);
                requireExactlyOneRow(affectedRows, "更新摘要失败元数据");
                current.setFailCount(failCount);
                current.setNextRetryTime(nextRetryTime);
                current.setUpdateTime(now);
            }
            clearFallbackRetryDelay(appId);
        } catch (RuntimeException exception) {
            log.error("记录摘要失败元数据异常 appId={} type={}", appId,
                    exception.getClass().getSimpleName(), exception);
            ensureFallbackRetryDelay(appId, delay);
        }
        return result(status, currentCursor(current),
                currentSummaryTokens(current), detail);
    }

    private void ensureFallbackRetryDelay(Long appId) {
        ensureFallbackRetryDelay(appId, RETRY_BASE_DELAY);
    }

    private void ensureFallbackRetryDelayIfWritable(Long appId) {
        AppDataLifecycleFence.WriterPermit writerPermit;
        try {
            writerPermit = lifecycleFence.tryAcquireWriter(appId);
        } catch (RuntimeException exception) {
            log.error("获取摘要兜底退避写许可异常 appId={} type={}", appId,
                    exception.getClass().getSimpleName(), exception);
            return;
        }
        if (writerPermit == null) {
            return;
        }
        try (writerPermit) {
            ensureFallbackRetryDelay(appId);
        } catch (RuntimeException exception) {
            log.error("设置摘要兜底退避写许可异常 appId={} type={}", appId,
                    exception.getClass().getSimpleName(), exception);
        }
    }

    private void ensureFallbackRetryDelay(Long appId, Duration delay) {
        try {
            Instant fallbackRetryTime = clock.instant()
                    .plus(delay);
            fallbackRetryAfter.merge(
                    appId,
                    fallbackRetryTime,
                    (existingRetryTime, candidateRetryTime) ->
                            existingRetryTime.isAfter(candidateRetryTime)
                                    ? existingRetryTime
                                    : candidateRetryTime);
        } catch (RuntimeException exception) {
            log.error("设置摘要兜底退避异常 appId={} type={}", appId,
                    exception.getClass().getSimpleName(), exception);
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
        current.setFailCount(0);
        current.setNextRetryTime(null);
        current.setUpdateTime(now);
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
                || summaryTokens > MemoryTokenProperties.L1_MAX_SUMMARY_TOKENS) {
            throw new IllegalStateException("摘要未满足 3K 落库门禁");
        }
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
        } else {
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
        writeCache(CACHE_KEY_PREFIX + appId, summary);
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
        try (writerPermit) {
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
                    exception.getClass().getSimpleName(), exception);
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

    private boolean isDatabaseRetryReady(AppMemorySummary current) {
        LocalDateTime nextRetryTime = current == null
                ? null : current.getNextRetryTime();
        return nextRetryTime == null
                || !LocalDateTime.now(clock).isBefore(nextRetryTime);
    }

    private boolean isFallbackRetryReady(Long appId) {
        try {
            synchronized (fallbackRetryAfter) {
                Instant nextRetryTime = fallbackRetryAfter.get(appId);
                if (nextRetryTime == null) {
                    return true;
                }
                if (clock.instant().isBefore(nextRetryTime)) {
                    return false;
                }
                fallbackRetryAfter.remove(appId);
                return true;
            }
        } catch (RuntimeException exception) {
            log.error("检查摘要兜底退避异常 appId={} type={}", appId,
                    exception.getClass().getSimpleName(), exception);
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
