package com.lyw.appgeneration.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.lyw.appgeneration.ai.memory.ChatTokenEstimator;
import com.lyw.appgeneration.ai.memory.ChatHistoryMemoryResolver;
import com.lyw.appgeneration.ai.memory.UserMemoryOperation;
import com.lyw.appgeneration.ai.memory.UserPreferencePromptBuilder;
import static com.lyw.appgeneration.service.impl.UserMemoryState.*;
import com.lyw.appgeneration.ai.memory.UserPreferenceMessageFragmentBuilder;
import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.mapper.AppMapper;
import com.lyw.appgeneration.mapper.AppMemoryExtractCursorMapper;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.entity.AppMemory;
import com.lyw.appgeneration.model.entity.AppMemoryExtractCursor;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.monitor.MemoryCompressionMetricsCollector;
import com.lyw.appgeneration.monitor.MemoryCompressionMetricsCollector.CandidateStatus;
import com.lyw.appgeneration.monitor.MemoryCompressionMetricsCollector.DebounceOutcome;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.UserMemoryService;
import com.lyw.appgeneration.service.MemoryCacheInvalidationResult;
import com.mybatisflex.core.query.QueryWrapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * L2 跨 app 用户长期记忆服务实现。
 *
 * <p>抽取由稳定回合钩子触发，按 userId 防抖且同一用户单轮执行；每个 app 独立维护游标，
 * 模型只接收完整回合的用户文本，正式版本和候选在独立表内维护。召回只拼接 ACTIVE 偏好，
 * EXPLICIT 优先且严格控制在 1K Token 内，结果使用版本化 Redis 键缓存。失败时游标不推进并记录
 * failCount，调度按 5 秒至 5 分钟指数退避恢复，不会因连续失败永久停更。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
@Slf4j
@Service
public class UserMemoryServiceImpl implements UserMemoryService {

    private static final int HISTORY_SCAN_PAGE_SIZE = 100;
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String LEGACY_PREF_CACHE_PREFIX = "mem:pref:";
    private static final String PREVIOUS_PREF_CACHE_PREFIX = "mem:pref:v2:";
    private static final String PREF_CACHE_PREFIX = "mem:pref:v3:";
    private static final Duration CACHE_TTL = Duration.ofHours(1);
    private static final Duration RETRY_BASE_DELAY = Duration.ofSeconds(5);
    private static final Duration RETRY_MAX_DELAY = Duration.ofMinutes(5);

    private final ChatHistoryService chatHistoryService;
    private final VersionedUserMemoryRepository memoryRepository;
    private final AppMemoryExtractCursorMapper cursorMapper;
    private final AppMapper appMapper;
    private final ChatModel extractionModel;
    private final ExecutorService executor;
    private final StringRedisTemplate redisTemplate;
    private final AppDataLifecycleFence lifecycleFence;
    private final TaskScheduler debounceScheduler;
    private final ChatTokenEstimator tokenEstimator;
    private final MemoryTokenProperties tokenProperties;
    private final UserPreferenceBatchBuilder preferenceBatchBuilder;
    private final UserPreferenceContract preferenceContract;
    private final UserMemoryOperationParser operationParser;
    private final UserPreferenceMessageFragmentBuilder l2FragmentBuilder;
    private final TransactionOperations transactionOperations;
    private final MemoryCompressionMetricsCollector metricsCollector;
    private final Clock clock;
    private final UserMemoryConsistencyCoordinator consistencyCoordinator;
    private final AppMemoryExtractionCoordinator extractionCoordinator;

    /** 防抖状态仅通过 ConcurrentHashMap.compute 系列方法变更。 */
    private final ConcurrentHashMap<Long, UserDirtyState> pendingByUser =
            new ConcurrentHashMap<>();
    /** appId→userId 反查缓存(归属永不变,与 Caffeine service 缓存隔离)。 */
    private final ConcurrentHashMap<Long, Long> appIdToUserId = new ConcurrentHashMap<>();

    @Autowired
    public UserMemoryServiceImpl(ChatHistoryService chatHistoryService,
                                 VersionedUserMemoryRepository memoryRepository,
                                 AppMemoryExtractCursorMapper cursorMapper,
                                 AppMapper appMapper,
                                 @Qualifier("userMemoryExtractionChatModel")
                                 ChatModel extractionModel,
                                 @Qualifier("userMemoryExtractionExecutor")
                                 ExecutorService executor,
                                 @Qualifier("userMemoryDebounceScheduler") TaskScheduler debounceScheduler,
                                 StringRedisTemplate redisTemplate,
                                 AppDataLifecycleFence lifecycleFence,
                                 ChatTokenEstimator tokenEstimator,
                                 MemoryTokenProperties tokenProperties,
                                 ChatHistoryMemoryResolver memoryResolver,
                                 ObjectProvider<PlatformTransactionManager>
                                         transactionManagerProvider,
                                 MemoryCompressionMetricsCollector
                                         metricsCollector) {
        this(chatHistoryService, memoryRepository, cursorMapper, appMapper,
                extractionModel, executor, debounceScheduler, redisTemplate,
                lifecycleFence, tokenEstimator, tokenProperties,
                resolveTransactionOperations(transactionManagerProvider),
                metricsCollector, memoryResolver,
                Clock.systemDefaultZone());
    }

    public UserMemoryServiceImpl(ChatHistoryService chatHistoryService,
                                 VersionedUserMemoryRepository memoryRepository,
                                 AppMemoryExtractCursorMapper cursorMapper,
                                 AppMapper appMapper,
                                 ChatModel extractionModel,
                                 ExecutorService executor,
                                 TaskScheduler debounceScheduler,
                                 StringRedisTemplate redisTemplate,
                                 AppDataLifecycleFence lifecycleFence,
                                 ChatTokenEstimator tokenEstimator,
                                 MemoryTokenProperties tokenProperties,
                                 TransactionOperations transactionOperations,
                                 MemoryCompressionMetricsCollector
                                         metricsCollector) {
        this(chatHistoryService, memoryRepository, cursorMapper, appMapper,
                extractionModel, executor, debounceScheduler, redisTemplate,
                lifecycleFence,
                tokenEstimator, tokenProperties, transactionOperations,
                metricsCollector, new ChatHistoryMemoryResolver(),
                Clock.systemDefaultZone());
    }

    private static TransactionOperations resolveTransactionOperations(
            ObjectProvider<PlatformTransactionManager> provider) {
        PlatformTransactionManager manager = provider.getIfAvailable();
        return manager == null
                ? MissingTransactionOperations.INSTANCE
                : new TransactionTemplate(manager);
    }

    UserMemoryServiceImpl(ChatHistoryService chatHistoryService,
                          VersionedUserMemoryRepository memoryRepository,
                          AppMemoryExtractCursorMapper cursorMapper,
                          AppMapper appMapper,
                          ChatModel extractionModel,
                          ExecutorService executor,
                          TaskScheduler debounceScheduler,
                          StringRedisTemplate redisTemplate,
                          AppDataLifecycleFence lifecycleFence,
                          ChatTokenEstimator tokenEstimator,
                          MemoryTokenProperties tokenProperties,
                          TransactionOperations transactionOperations,
                          MemoryCompressionMetricsCollector metricsCollector,
                          Clock clock) {
        this(chatHistoryService, memoryRepository, cursorMapper, appMapper,
                extractionModel, executor, debounceScheduler, redisTemplate,
                lifecycleFence, tokenEstimator, tokenProperties,
                transactionOperations, metricsCollector,
                new ChatHistoryMemoryResolver(), clock);
    }

    UserMemoryServiceImpl(ChatHistoryService chatHistoryService,
                          VersionedUserMemoryRepository memoryRepository,
                          AppMemoryExtractCursorMapper cursorMapper,
                          AppMapper appMapper,
                          ChatModel extractionModel,
                          ExecutorService executor,
                          TaskScheduler debounceScheduler,
                          StringRedisTemplate redisTemplate,
                          AppDataLifecycleFence lifecycleFence,
                          ChatTokenEstimator tokenEstimator,
                          MemoryTokenProperties tokenProperties,
                          TransactionOperations transactionOperations,
                          MemoryCompressionMetricsCollector metricsCollector,
                          ChatHistoryMemoryResolver memoryResolver,
                          Clock clock) {
        this.chatHistoryService = Objects.requireNonNull(
                chatHistoryService, "对话历史服务不能为空");
        this.memoryRepository = Objects.requireNonNull(
                memoryRepository, "用户记忆仓储不能为空");
        this.cursorMapper = Objects.requireNonNull(
                cursorMapper, "抽取游标 Mapper 不能为空");
        this.appMapper = Objects.requireNonNull(appMapper, "应用 Mapper 不能为空");
        this.extractionModel = Objects.requireNonNull(
                extractionModel, "偏好抽取模型不能为空");
        this.executor = Objects.requireNonNull(executor, "记忆执行器不能为空");
        this.debounceScheduler = Objects.requireNonNull(
                debounceScheduler, "偏好防抖调度器不能为空");
        this.redisTemplate = Objects.requireNonNull(
                redisTemplate, "Redis 模板不能为空");
        this.lifecycleFence = Objects.requireNonNull(
                lifecycleFence, "应用数据生命周期栅栏不能为空");
        this.tokenEstimator = Objects.requireNonNull(
                tokenEstimator, "Token 估算器不能为空");
        this.tokenProperties = Objects.requireNonNull(
                tokenProperties, "Token 配置不能为空");
        this.preferenceBatchBuilder = new UserPreferenceBatchBuilder(
                this.tokenEstimator, this.tokenProperties, memoryResolver);
        this.preferenceContract = new UserPreferenceContract(
                this.tokenEstimator, this.tokenProperties);
        this.operationParser = new UserMemoryOperationParser(
                this.preferenceContract);
        this.l2FragmentBuilder = new UserPreferenceMessageFragmentBuilder(
                this.tokenEstimator, this.tokenProperties);
        this.transactionOperations = Objects.requireNonNull(
                transactionOperations, "事务执行器不能为空");
        this.metricsCollector = Objects.requireNonNull(
                metricsCollector, "记忆压缩指标收集器不能为空");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
        this.consistencyCoordinator =
                new UserMemoryConsistencyCoordinator();
        this.extractionCoordinator =
                new AppMemoryExtractionCoordinator();
    }

    @Override
    public void triggerPreferenceExtractionAsync(
            Long userId, Long appId, Long stableAiMessageId) {
        if (userId == null || userId <= 0 || appId == null || appId <= 0
                || stableAiMessageId == null || stableAiMessageId <= 0) {
            return;
        }
        AppDataLifecycleFence.WriterPermit writerPermit;
        try {
            writerPermit = lifecycleFence.tryAcquireWriter(appId);
        } catch (RuntimeException exception) {
            log.warn("获取 L2 trigger 写许可失败 userId={} appId={} type={}",
                    userId, appId, exception.getClass().getSimpleName());
            return;
        }
        if (writerPermit == null) {
            return;
        }
        try (writerPermit) {
            registerDirtyApp(userId, appId, stableAiMessageId);
        } catch (RuntimeException exception) {
            log.warn("登记 L2 待处理版本失败 userId={} appId={} type={}",
                    userId, appId, exception.getClass().getSimpleName());
        }
    }

    private void registerDirtyApp(
            Long userId, Long appId, long stableAiMessageId) {
        Instant quietUntil = clock.instant()
                .plus(tokenProperties.getL2Debounce());
        AtomicBoolean rescheduled = new AtomicBoolean();
        pendingByUser.compute(userId, (ignored, current) -> {
            rescheduled.set(current != null);
            UserDirtyState state = current == null
                    ? new UserDirtyState() : current;
            DirtyApp previous = state.apps.get(appId);
            long version = ++state.nextVersion;
            long historyUpperBound = previous == null
                    ? stableAiMessageId
                    : Math.max(previous.historyUpperBound, stableAiMessageId);
            Instant retryAfter = previous == null ? null : previous.retryAfter;
            int failCount = previous == null ? 0 : previous.failCount;
            state.quietUntil = quietUntil;
            state.apps.put(appId, new DirtyApp(
                    version, historyUpperBound, retryAfter, failCount));
            if (!state.workerRunning) {
                scheduleNext(userId, state);
            }
            return state;
        });
        metricsCollector.recordL2Debounce(rescheduled.get()
                ? DebounceOutcome.RESCHEDULED
                : DebounceOutcome.REGISTERED);
    }

    private void scheduleNext(Long userId, UserDirtyState state) {
        cancelScheduled(state);
        if (state.workerRunning || state.apps.isEmpty()) {
            return;
        }
        Instant quietUntil = state.quietUntil;
        Instant nextRunAt = state.apps.values().stream()
                .map(dirty -> dirty.eligibleAt(quietUntil))
                .min(Comparator.naturalOrder())
                .orElseThrow();
        long generation = ++state.scheduleGeneration;
        UserDirtyState expectedState = state;
        state.scheduled = scheduleDebouncedTask(
                userId, expectedState, generation, nextRunAt);
    }

    /**
     * 全局 watchdog 的本地恢复入口。每个 tick 对每个用户至多尝试一次。
     */
    public void recoverUnscheduledPending() {
        pendingByUser.forEach((userId, ignored) ->
                pendingByUser.computeIfPresent(userId, (key, state) -> {
                    if (state.scheduled == null && !state.workerRunning
                            && !state.apps.isEmpty()) {
                        scheduleNextWithoutImmediateRetry(key, state);
                    }
                    return state;
                }));
    }

    private void scheduleNextWithoutImmediateRetry(
            Long userId, UserDirtyState state) {
        cancelScheduled(state);
        if (state.workerRunning || state.apps.isEmpty()) {
            return;
        }
        Instant quietUntil = state.quietUntil;
        Instant nextRunAt = state.apps.values().stream()
                .map(dirty -> dirty.eligibleAt(quietUntil))
                .min(Comparator.naturalOrder())
                .orElseThrow();
        long generation = ++state.scheduleGeneration;
        Runnable task = () -> onDebounceTimer(userId, state, generation);
        try {
            state.scheduled = requireScheduledHandle(
                    debounceScheduler.schedule(task, nextRunAt));
        } catch (RuntimeException exception) {
            metricsCollector.recordL2Debounce(DebounceOutcome.REJECTED);
            log.warn("watchdog 调度 L2 偏好抽取失败 userId={} type={}",
                    userId, exception.getClass().getSimpleName());
            state.scheduled = null;
        }
    }

    private ScheduledFuture<?> scheduleDebouncedTask(
            Long userId,
            UserDirtyState expectedState,
            long generation,
            Instant nextRunAt) {
        Runnable task = () -> onDebounceTimer(
                userId, expectedState, generation);
        try {
            ScheduledFuture<?> scheduled = debounceScheduler.schedule(
                    task, nextRunAt);
            return requireScheduledHandle(scheduled);
        } catch (RuntimeException exception) {
            metricsCollector.recordL2Debounce(DebounceOutcome.REJECTED);
            log.warn("调度 L2 偏好抽取失败 userId={} type={}",
                    userId, exception.getClass().getSimpleName());
            return retryDebouncedTaskOnce(userId, task, nextRunAt);
        }
    }

    private ScheduledFuture<?> retryDebouncedTaskOnce(
            Long userId, Runnable task, Instant nextRunAt) {
        try {
            return requireScheduledHandle(
                    debounceScheduler.schedule(task, nextRunAt));
        } catch (RuntimeException retryException) {
            log.warn("重试调度 L2 偏好抽取失败 userId={} type={}",
                    userId, retryException.getClass().getSimpleName());
            return null;
        }
    }

    private ScheduledFuture<?> requireScheduledHandle(
            ScheduledFuture<?> scheduled) {
        if (scheduled == null) {
            throw new IllegalStateException("防抖调度器未返回任务句柄");
        }
        return scheduled;
    }

    private void cancelScheduled(UserDirtyState state) {
        state.scheduleGeneration++;
        ScheduledFuture<?> scheduled = state.scheduled;
        state.scheduled = null;
        if (scheduled != null) {
            scheduled.cancel(false);
        }
    }

    private void onDebounceTimer(Long userId,
                                 UserDirtyState expectedState,
                                 long generation) {
        List<DirtySnapshot> snapshots = new ArrayList<>();
        pendingByUser.computeIfPresent(userId, (ignored, state) -> {
            if (state != expectedState
                    || state.scheduleGeneration != generation) {
                return state;
            }
            state.scheduled = null;
            if (state.workerRunning) {
                return state;
            }
            Instant now = clock.instant();
            state.apps.forEach((appId, dirty) -> {
                if (!now.isBefore(dirty.eligibleAt(state.quietUntil))) {
                    snapshots.add(new DirtySnapshot(
                            appId, dirty.version, state.nextVersion,
                            dirty.historyUpperBound));
                }
            });
            if (snapshots.isEmpty()) {
                scheduleNext(userId, state);
            } else {
                state.workerRunning = true;
            }
            return state;
        });
        if (snapshots.isEmpty()) {
            return;
        }
        try {
            executor.submit(() -> {
                try {
                    runDirtyRound(
                            userId, expectedState, List.copyOf(snapshots));
                } finally {
                    metricsCollector.recordL2Debounce(
                            DebounceOutcome.COMPLETED);
                }
            });
            metricsCollector.recordL2Debounce(DebounceOutcome.SUBMITTED);
        } catch (RuntimeException exception) {
            metricsCollector.recordL2Debounce(DebounceOutcome.REJECTED);
            log.warn("提交 L2 偏好抽取轮次失败 userId={} type={}",
                    userId, exception.getClass().getSimpleName());
            finishDirtyRound(userId, expectedState, snapshots,
                    Map.of(), true);
        }
    }

    private void runDirtyRound(Long userId,
                               UserDirtyState expectedState,
                               List<DirtySnapshot> snapshots) {
        Map<Long, AppProcessResult> results = new LinkedHashMap<>();
        List<AppExtractionWork> works = new ArrayList<>();
        for (DirtySnapshot snapshot : snapshots) {
            try {
                AppMemoryExtractCursor cursor = cursorMapper.selectOneByQuery(
                        QueryWrapper.create().eq("appId", snapshot.appId));
                AppProcessResult persistedRetry =
                        persistedRetryResult(cursor);
                if (persistedRetry != null) {
                    metricsCollector.recordL2Debounce(
                            DebounceOutcome.DATABASE_BACKOFF_DEFERRED);
                    results.put(snapshot.appId, persistedRetry);
                    continue;
                }
                long currentCursor = cursorValue(cursor);
                works.add(new AppExtractionWork(
                        snapshot, cursor,
                        Math.max(currentCursor,
                                snapshot.historyUpperBound)));
            } catch (RuntimeException exception) {
                log.error("读取 L2 抽取游标失败 userId={} appId={} type={}",
                        userId, snapshot.appId,
                        exception.getClass().getSimpleName());
                results.put(snapshot.appId, AppProcessResult.FAILED);
            }
        }
        works.sort(Comparator.comparingLong(AppExtractionWork::cursorValue));
        for (AppExtractionWork work : works) {
            if (!isCurrentSnapshot(
                    userId, expectedState, work.snapshot)) {
                results.put(work.snapshot.appId,
                        AppProcessResult.DEFERRED);
                continue;
            }
            results.put(work.snapshot.appId,
                    processDirtyAppSafely(userId, work));
        }
        finishDirtyRound(userId, expectedState, snapshots, results, false);
    }

    private boolean isCurrentSnapshot(
            Long userId,
            UserDirtyState expectedState,
            DirtySnapshot snapshot) {
        AtomicBoolean current = new AtomicBoolean();
        pendingByUser.computeIfPresent(userId, (ignored, state) -> {
            DirtyApp dirty = state.apps.get(snapshot.appId);
            current.set(state == expectedState
                    && state.nextVersion == snapshot.userVersion
                    && dirty != null
                    && dirty.version == snapshot.version);
            return state;
        });
        return current.get();
    }

    private AppProcessResult processDirtyAppSafely(
            Long userId, AppExtractionWork work) {
        try {
            return processDirtyApp(userId, work);
        } catch (RuntimeException exception) {
            log.error("处理 L2 dirty app 异常 userId={} appId={} type={}",
                    userId, work.snapshot.appId,
                    exception.getClass().getSimpleName());
            return AppProcessResult.FAILED;
        }
    }

    private AppProcessResult processDirtyApp(
            Long userId, AppExtractionWork work) {
        return extractWithLifecycle(
                userId, work.snapshot.appId, null,
                false, true, work.historyUpperBound);
    }

    private void finishDirtyRound(Long userId,
                                  UserDirtyState expectedState,
                                  List<DirtySnapshot> snapshots,
                                  Map<Long, AppProcessResult> results,
                                  boolean submissionRejected) {
        Instant now = clock.instant();
        pendingByUser.computeIfPresent(userId, (ignored, state) -> {
            if (state != expectedState) {
                return state;
            }
            state.workerRunning = false;
            for (DirtySnapshot snapshot : snapshots) {
                DirtyApp current = state.apps.get(snapshot.appId);
                if (current == null || current.version != snapshot.version) {
                    continue;
                }
                AppProcessResult result = submissionRejected
                        ? AppProcessResult.FAILED
                        : results.getOrDefault(
                                snapshot.appId, AppProcessResult.FAILED);
                applyProcessResult(
                        state, snapshot.appId, current, result, now);
            }
            if (state.apps.isEmpty()) {
                cancelScheduled(state);
                return null;
            }
            scheduleNext(userId, state);
            return state;
        });
    }

    private void applyProcessResult(UserDirtyState state,
                                    long appId,
                                    DirtyApp current,
                                    AppProcessResult result,
                                    Instant now) {
        switch (result.status()) {
            case COMPLETE -> state.apps.remove(appId);
            case MORE_PENDING -> state.apps.put(
                    appId, current.readyForNextBatch());
            case DEFERRED -> {
                if (result.retryAt() != null) {
                    state.apps.put(appId, current.retryAt(
                            result.retryAt(), result.failCount()));
                }
            }
            case FAILED -> state.apps.put(appId, current.failedAt(
                    now, result.retryAt(), result.failCount()));
        }
    }

    /** 同步抽取一次。best-effort,不抛异常。 */
    public void extractNow(Long userId, Long appId) {
        if (userId == null || userId <= 0 || appId == null || appId <= 0) {
            return;
        }
        extractWithLifecycle(
                userId, appId, null, false,
                false, Long.MAX_VALUE);
    }

    private AppProcessResult extractWithLifecycle(
            Long userId,
            Long appId,
            AppMemoryExtractCursor knownCursor,
            boolean cursorLoaded,
            boolean respectPersistentBackoff,
            long historyUpperBound) {
        try (AppMemoryExtractionCoordinator.Permit ignored =
                     extractionCoordinator.acquire(appId)) {
            PreparedExtraction prepared = prepareExtraction(
                    userId, appId, knownCursor,
                    cursorLoaded, respectPersistentBackoff,
                    historyUpperBound);
            if (prepared.immediateResult() != null) {
                return prepared.immediateResult();
            }
            List<UserMemoryOperation> operations =
                    extractOperations(userId, appId, prepared);
            return commitExtractionResult(
                    userId, appId, prepared, operations);
        }
    }

    private PreparedExtraction prepareExtraction(
            Long userId,
            Long appId,
            AppMemoryExtractCursor knownCursor,
            boolean cursorLoaded,
            boolean respectPersistentBackoff,
            long historyUpperBound) {
        AppDataLifecycleFence.WriterPermit writerPermit =
                lifecycleFence.tryAcquireWriter(appId);
        if (writerPermit == null) {
            log.info("L2 偏好抽取被应用数据删除门拒绝 userId={} appId={}",
                    userId, appId);
            return PreparedExtraction.immediate(AppProcessResult.FAILED);
        }
        try (writerPermit) {
            requireAppOwner(userId, appId);
            AppMemoryExtractCursor cursor = cursorLoaded
                    ? knownCursor : loadCursor(appId);
            if (respectPersistentBackoff) {
                AppProcessResult persistedRetry =
                        persistedRetryResult(cursor);
                if (persistedRetry != null) {
                    metricsCollector.recordL2Debounce(
                            DebounceOutcome.DATABASE_BACKOFF_DEFERRED);
                    return PreparedExtraction.immediate(persistedRetry);
                }
            }
            return prepareWithinPermit(
                    userId, appId, cursor, historyUpperBound);
        } catch (MemoryOwnershipException exception) {
            return PreparedExtraction.immediate(AppProcessResult.FAILED);
        } catch (RuntimeException exception) {
            log.error("准备 L2 偏好抽取失败 userId={} appId={} type={}",
                    userId, appId,
                    exception.getClass().getSimpleName());
            return recordPreparationFailure(
                    userId, appId, knownCursor, cursorLoaded);
        }
    }

    private AppMemoryExtractCursor loadCursor(Long appId) {
        return cursorMapper.selectOneByQuery(
                QueryWrapper.create().eq("appId", appId));
    }

    private AppMemoryExtractCursor loadCurrentCursor(Long appId) {
        return cursorMapper.selectOneByQuery(QueryWrapper.create().eq("appId", appId).forUpdate());
    }

    private PreparedExtraction prepareWithinPermit(
            Long userId,
            Long appId,
            AppMemoryExtractCursor cursor,
            long historyUpperBound) {
        long lastId = cursorValue(cursor);
        requireAppOwner(userId, appId);
        Snapshot snapshot = memoryRepository.load(userId);
        int baseTokens = tokenEstimator.estimateText(UserPreferencePromptBuilder.build("", "", List.of()));
        int windowBudget = Math.max(1, Math.min(8_192,
                (tokenProperties.getAsyncCompressionThreshold() - baseTokens) / 2));
        List<Window> windows = snapshot.windows(tokenEstimator, windowBudget);
        String existing = windows.stream().map(Window::text)
                .max(Comparator.comparingInt(tokenEstimator::estimateText)).orElse("");
        UserPreferenceBatchBuilder.Batch batch = buildPreferenceBatch(
                appId, lastId, historyUpperBound, existing);
        if (batch.completedThroughId() != lastId) {
            return PreparedExtraction.ready(cursor, lastId, batch, snapshot, windows);
        }
        if (hasFailureMetadata(cursor)) {
            transactionOperations.execute(status -> {
                memoryRepository.lockUser(userId);
                AppMemoryExtractCursor current = loadCurrentCursor(appId);
                if (cursorValue(current) == lastId && hasFailureMetadata(current)) {
                    advanceCursor(userId, appId, current, lastId);
                }
                return null;
            });
        }
        return PreparedExtraction.immediate(AppProcessResult.COMPLETE);
    }

    private PreparedExtraction recordPreparationFailure(
            Long userId,
            Long appId,
            AppMemoryExtractCursor knownCursor,
            boolean cursorLoaded) {
        AppDataLifecycleFence.WriterPermit writerPermit =
                lifecycleFence.tryAcquireWriter(appId);
        if (writerPermit == null) {
            return PreparedExtraction.immediate(AppProcessResult.FAILED);
        }
        try (writerPermit) {
            AppMemoryExtractCursor current = cursorLoaded
                    ? knownCursor : loadCursor(appId);
            return PreparedExtraction.immediate(
                    recordFailureSafely(userId, appId, current));
        } catch (RuntimeException exception) {
            log.error("记录 L2 准备失败异常 userId={} appId={} type={}",
                    userId, appId,
                    exception.getClass().getSimpleName());
            return PreparedExtraction.immediate(AppProcessResult.FAILED);
        }
    }

    private AppProcessResult commitExtractionResult(
            Long userId,
            Long appId,
            PreparedExtraction prepared,
            List<UserMemoryOperation> operations) {
        AppDataLifecycleFence.WriterPermit writerPermit =
                lifecycleFence.tryAcquireWriter(appId);
        if (writerPermit == null) {
            log.info("丢弃删除期间返回的 L2 模型结果 userId={} appId={}",
                    userId, appId);
            return AppProcessResult.FAILED;
        }
        try (writerPermit;
             UserMemoryConsistencyCoordinator.Permit ignored =
                     consistencyCoordinator.acquire(userId)) {
            if (operations == null) {
                return recordFailureSafely(
                        userId, appId, prepared.cursor());
            }
            return completePreferenceBatchWithinConsistency(
                    userId, appId, prepared, operations);
        } catch (PersistenceCommitUncertainException exception) {
            log.error("L2 事务提交结果不确定 userId={} appId={} type={}",
                    userId, appId,
                    exception.getCause().getClass().getSimpleName());
            return AppProcessResult.FAILED;
        } catch (RuntimeException exception) {
            log.error("提交 L2 偏好抽取失败 userId={} appId={} type={}",
                    userId, appId,
                    exception.getClass().getSimpleName());
            return recordCommitFailure(
                    userId, appId, prepared.cursor());
        }
    }

    private AppProcessResult recordCommitFailure(
            Long userId,
            Long appId,
            AppMemoryExtractCursor cursor) {
        AppDataLifecycleFence.WriterPermit writerPermit =
                lifecycleFence.tryAcquireWriter(appId);
        if (writerPermit == null) {
            return AppProcessResult.FAILED;
        }
        try {
            try (writerPermit) {
                return recordFailureSafely(
                        userId, appId, cursor);
            }
        } catch (RuntimeException exception) {
            log.error("记录 L2 提交失败异常 userId={} appId={} type={}",
                    userId, appId, exception.getClass().getSimpleName());
            return AppProcessResult.FAILED;
        }
    }

    private List<UserMemoryOperation> extractOperations(Long userId, Long appId, PreparedExtraction prepared) {
        if (!prepared.batch().hasTurns()) return List.of();
        try {
            List<UserMemoryOperation> all = new ArrayList<>();
            for (Window window : prepared.windows()) {
                String prompt = UserPreferencePromptBuilder.build(window.text(),
                        prepared.batch().userEvidence(), prepared.batch().turnIds());
                if (tokenEstimator.estimateText(prompt) > tokenProperties.getAsyncCompressionThreshold()) {
                    throw new IllegalStateException("分页抽取输入超过预算");
                }
                List<UserMemoryOperation> page = operationParser.parse(
                        extractionModel.chat(prompt), prepared.batch().turnIds());
                if (page == null) throw new IllegalArgumentException("抽取输出未通过操作契约");
                for (UserMemoryOperation op : page) {
                    if (op.targetId() != null && !window.visibleIds().contains(op.targetId())) {
                        throw new IllegalArgumentException("模型目标未出现在本页输入中");
                    }
                    if (op.targetId() != null && op.content() != null) {
                        Item target = prepared.snapshot().items().get(op.targetId());
                        if (!preferenceContract.isPreferenceWithinBudget(target.category(),
                                target.attributeName(), op.content())) {
                            throw new IllegalArgumentException("替换后的完整偏好片段超过预算");
                        }
                    }
                }
                all.addAll(page);
            }
            return UserMemoryOperationMerger.merge(all, prepared.snapshot());
        } catch (RuntimeException exception) {
            log.warn("L2 操作抽取失败 userId={} appId={} type={}",
                    userId, appId, exception.getClass().getSimpleName());
            return null;
        }
    }

    private AppProcessResult completePreferenceBatchWithinConsistency(
            Long userId, Long appId, PreparedExtraction prepared, List<UserMemoryOperation> operations) {
        boolean formalChanged = persistBatchAtomically(userId, appId, prepared, operations);
        if (formalChanged) invalidateRecallCache(userId);
        log.info("L2 偏好抽取完成 userId={} appId={} cursorFrom={} cursorTo={} operationCount={} hasMore={}",
                userId, appId, prepared.lastId(), prepared.batch().completedThroughId(),
                operations.size(), prepared.batch().hasMore());
        return prepared.batch().hasMore() ? AppProcessResult.MORE_PENDING : AppProcessResult.COMPLETE;
    }

    private boolean persistBatchAtomically(
            Long userId, Long appId, PreparedExtraction prepared, List<UserMemoryOperation> operations) {
        AtomicBoolean callbackCompleted = new AtomicBoolean();
        UserMemoryChangePlanner.Plan persistence;
        try {
            persistence = transactionOperations.execute(status -> {
                memoryRepository.lockUser(userId);
                requireAppOwner(userId, appId);
                AppMemoryExtractCursor currentCursor = loadCurrentCursor(appId);
                if (currentCursor != null && !userId.equals(currentCursor.getUserId())) {
                    throw new MemoryOwnershipException();
                }
                long currentId = cursorValue(currentCursor);
                if (currentId >= prepared.batch().completedThroughId()) {
                    callbackCompleted.set(true);
                    return new UserMemoryChangePlanner.Plan(List.of(), List.of(), LocalDateTime.now(clock));
                }
                if (currentId != prepared.lastId()) throw new IllegalStateException("抽取游标已变化，需重新处理");
                Snapshot current = memoryRepository.loadCurrent(userId);
                UserMemoryChangePlanner.Plan plan = new UserMemoryChangePlanner(
                        memoryRepository, userId, appId, current, LocalDateTime.now(clock))
                        .plan(operations, prepared.snapshot());
                if (plan.formalChanged()) invalidateCurrentRecallCacheStrict(userId);
                plan.apply(memoryRepository);
                advanceCursor(userId, appId, currentCursor, prepared.batch().completedThroughId());
                callbackCompleted.set(true);
                return plan;
            });
        } catch (RuntimeException exception) {
            if (callbackCompleted.get()) throw new PersistenceCommitUncertainException(exception);
            throw exception;
        }
        if (persistence == null) throw new IllegalStateException("L2 批次事务未返回持久化结果");
        if (persistence.formalChanged()) metricsCollector.recordL2Candidate(CandidateStatus.ACTIVE);
        if (persistence.candidateChanged()) metricsCollector.recordL2Candidate(CandidateStatus.CANDIDATE);
        if (persistence.changes().isEmpty() && !operations.isEmpty()) {
            metricsCollector.recordL2Candidate(CandidateStatus.UNCHANGED);
        }
        return persistence.formalChanged();
    }

    private void requireAppOwner(Long userId, Long appId) {
        App app = appMapper.selectOneById(appId);
        if (app == null || !userId.equals(app.getUserId()) || Integer.valueOf(1).equals(app.getIsDelete())) {
            throw new MemoryOwnershipException();
        }
    }

    private static final class MemoryOwnershipException extends IllegalStateException {
        private MemoryOwnershipException() { super("应用或抽取游标不属于当前用户"); }
    }

    private UserPreferenceBatchBuilder.Batch buildPreferenceBatch(
            Long appId,
            long lastId,
            long historyUpperBound,
            String existingPreferences) {
        UserPreferenceBatchBuilder.Session session =
                preferenceBatchBuilder.start(lastId, existingPreferences);
        while (true) {
            List<ChatHistory> rows = chatHistoryService.listMessagesAfterCursor(
                    appId, session.nextCursor(), HISTORY_SCAN_PAGE_SIZE);
            if (rows == null) {
                throw new IllegalStateException("数据库返回了 null 对话批次");
            }
            List<ChatHistory> boundedRows = limitHistoryRows(
                    rows, historyUpperBound);
            UserPreferenceBatchBuilder.PageResult result =
                    session.acceptPage(boundedRows,
                            boundedRows.size() < rows.size()
                                    || rows.size() < HISTORY_SCAN_PAGE_SIZE);
            for (UserPreferenceBatchBuilder.SkippedTurn skipped
                    : result.skippedTurns()) {
                log.warn("跳过超出 L2 抽取 Token 上限的完整回合 "
                                + "appId={} turnId={} completedThroughId={}",
                        appId, skipped.turnId(),
                        skipped.completedThroughId());
            }
            if (result.finished()) {
                return result.batch();
            }
        }
    }

    private List<ChatHistory> limitHistoryRows(
            List<ChatHistory> rows, long historyUpperBound) {
        if (historyUpperBound == Long.MAX_VALUE) {
            return rows;
        }
        List<ChatHistory> bounded = new ArrayList<>(rows.size());
        for (ChatHistory row : rows) {
            Long rowId = row == null ? null : row.getId();
            if (rowId != null && rowId > historyUpperBound) {
                break;
            }
            bounded.add(row);
        }
        return List.copyOf(bounded);
    }

    private static final class UserDirtyState {

        private final Map<Long, DirtyApp> apps = new LinkedHashMap<>();
        private Instant quietUntil = Instant.EPOCH;
        private long nextVersion;
        private boolean workerRunning;
        private long scheduleGeneration;
        private ScheduledFuture<?> scheduled;
    }

    private record DirtyApp(long version,
                            long historyUpperBound,
                            Instant retryAfter,
                            int failCount) {

        private Instant eligibleAt(Instant quietUntil) {
            return retryAfter != null && retryAfter.isAfter(quietUntil)
                    ? retryAfter : quietUntil;
        }

        private DirtyApp failedAt(
                Instant now,
                Instant persistedRetryAt,
                int persistedFailCount) {
            int nextFailCount = Math.max(
                    incrementFailCount(failCount),
                    Math.max(0, persistedFailCount));
            Instant localRetryAt = now.plus(retryDelay(nextFailCount));
            Instant nextRetryAt = persistedRetryAt != null
                    && persistedRetryAt.isAfter(localRetryAt)
                    ? persistedRetryAt : localRetryAt;
            return new DirtyApp(version, historyUpperBound,
                    nextRetryAt, nextFailCount);
        }

        private DirtyApp retryAt(Instant nextRetryAt, int nextFailCount) {
            return new DirtyApp(version, historyUpperBound,
                    nextRetryAt, Math.max(0, nextFailCount));
        }

        private DirtyApp readyForNextBatch() {
            return new DirtyApp(version, historyUpperBound, null, 0);
        }
    }

    private static Duration retryDelay(int failCount) {
        int exponent = Math.min(Math.max(failCount - 1, 0), 6);
        long seconds = RETRY_BASE_DELAY.toSeconds() << exponent;
        return Duration.ofSeconds(Math.min(
                seconds, RETRY_MAX_DELAY.toSeconds()));
    }

    private static int incrementFailCount(int failCount) {
        return failCount >= Integer.MAX_VALUE
                ? Integer.MAX_VALUE : Math.max(0, failCount) + 1;
    }

    private record DirtySnapshot(
            long appId,
            long version,
            long userVersion,
            long historyUpperBound) {
    }

    private record AppExtractionWork(DirtySnapshot snapshot,
                                     AppMemoryExtractCursor cursor,
                                     long historyUpperBound) {

        private long cursorValue() {
            return cursor == null || cursor.getLastExtractedId() == null
                    ? 0L : cursor.getLastExtractedId();
        }
    }

    private enum AppProcessStatus {
        COMPLETE,
        MORE_PENDING,
        DEFERRED,
        FAILED
    }

    private record AppProcessResult(AppProcessStatus status,
                                    Instant retryAt,
                                    int failCount) {

        private static final AppProcessResult COMPLETE = new AppProcessResult(
                AppProcessStatus.COMPLETE, null, 0);
        private static final AppProcessResult MORE_PENDING =
                new AppProcessResult(
                        AppProcessStatus.MORE_PENDING, null, 0);
        private static final AppProcessResult DEFERRED = new AppProcessResult(
                AppProcessStatus.DEFERRED, null, 0);
        private static final AppProcessResult FAILED = new AppProcessResult(
                AppProcessStatus.FAILED, null, 0);

        private static AppProcessResult deferredUntil(
                Instant retryAt, int failCount) {
            return new AppProcessResult(
                    AppProcessStatus.DEFERRED, retryAt, failCount);
        }

        private static AppProcessResult failedAt(
                Instant retryAt, int failCount) {
            return new AppProcessResult(
                    AppProcessStatus.FAILED, retryAt, failCount);
        }
    }

    private static final class PersistenceCommitUncertainException
            extends RuntimeException {

        private PersistenceCommitUncertainException(Throwable cause) {
            super(cause);
        }
    }

    private record PreparedExtraction(
            AppMemoryExtractCursor cursor,
            long lastId,
            UserPreferenceBatchBuilder.Batch batch,
            Snapshot snapshot, List<Window> windows,
            AppProcessResult immediateResult) {

        private static PreparedExtraction ready(
                AppMemoryExtractCursor cursor,
                long lastId,
                UserPreferenceBatchBuilder.Batch batch, Snapshot snapshot, List<Window> windows) {
            return new PreparedExtraction(cursor, lastId, batch, snapshot, windows, null);
        }

        private static PreparedExtraction immediate(
                AppProcessResult result) {
            return new PreparedExtraction(null, 0L, null, null, List.of(), result);
        }
    }

    private enum MissingTransactionOperations
            implements TransactionOperations {
        INSTANCE;

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            throw new IllegalStateException("L2 批次事务管理器不可用");
        }
    }

    @Override
    public String recallByApp(Long appId) {
        String recalled = recallByAppWithoutMetrics(appId);
        recordFinalRecallTokens(recalled);
        return recalled;
    }

    private String recallByAppWithoutMetrics(Long appId) {
        if (appId == null || appId <= 0L) {
            return "";
        }
        AppDataLifecycleFence.WriterPermit writerPermit;
        try {
            writerPermit = lifecycleFence.tryAcquireWriter(appId);
        } catch (RuntimeException exception) {
            log.warn("获取 L2 召回许可失败 appId={} type={}",
                    appId, exception.getClass().getSimpleName());
            return "";
        }
        if (writerPermit == null) {
            return "";
        }
        try (writerPermit) {
            return recallWithinPermit(appId);
        } catch (RuntimeException exception) {
            log.warn("释放 L2 召回许可失败 appId={} type={}",
                    appId, exception.getClass().getSimpleName());
            return "";
        }
    }

    private void recordFinalRecallTokens(String recalled) {
        try {
            metricsCollector.recordL2RecallTokens(
                    l2FragmentBuilder.estimate(recalled));
        } catch (RuntimeException ignored) {
            // 最终 Token 观测不得改变实际注入文本。
        }
    }

    private String recallWithinPermit(Long appId) {
        Long userId = resolveUserId(appId);
        if (userId == null) {
            return "";
        }
        try (UserMemoryConsistencyCoordinator.Permit ignored =
                     consistencyCoordinator.acquire(userId)) {
            return recallUserPreferences(userId);
        }
    }

    private String recallUserPreferences(Long userId) {
        String cacheKey = PREF_CACHE_PREFIX + userId;
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (preferenceContract.isValidRecallCache(cached)) {
                return cached; // 命中(含空串)
            }
            if (cached != null) {
                deleteCacheKey(cacheKey, userId);
            }
        } catch (Exception exception) {
            log.warn("读取偏好缓存失败 userId={} type={}",
                    userId, exception.getClass().getSimpleName());
        }
        String text;
        try {
            List<AppMemory> prefs = new ArrayList<>();
            for (int page = 0; ; page++) {
                List<AppMemory> rows = memoryRepository.activePage(userId, page);
                prefs.addAll(rows);
                if (rows.size() < HISTORY_SCAN_PAGE_SIZE) break;
            }
            text = renderRecallPreferenceLines(userId, prefs);
        } catch (Exception exception) {
            log.warn("查询用户偏好失败 userId={} type={}",
                    userId, exception.getClass().getSimpleName());
            return ""; // 降级:只用 L1+L0
        }
        writeCache(cacheKey, text);
        return text;
    }

    // ---- 内部 ----

    private Long resolveUserId(Long appId) {
        if (appId == null) {
            return null;
        }
        return appIdToUserId.computeIfAbsent(appId, id -> {
            try {
                App app = appMapper.selectOneById(id);
                return app == null ? null : app.getUserId();
            } catch (Exception exception) {
                log.warn("反查 userId 失败 appId={} type={}",
                        id, exception.getClass().getSimpleName());
                return null; // 不缓存 null,下次重试
            }
        });
    }

    private void advanceCursor(
            Long userId,
            Long appId,
            AppMemoryExtractCursor cursor,
            long newCursor) {
        LocalDateTime now = LocalDateTime.now(clock);
        AppMemoryExtractCursor next = AppMemoryExtractCursor.builder()
                .id(cursor == null ? null : cursor.getId())
                .appId(appId)
                .userId(userId)
                .lastExtractedId(newCursor)
                .failCount(0)
                .nextRetryTime(null)
                .createTime(cursor == null ? now : cursor.getCreateTime())
                .updateTime(now)
                .isDelete(cursor == null || cursor.getIsDelete() == null ? 0 : cursor.getIsDelete())
                .build();
        int affectedRows = cursor == null
                ? cursorMapper.insert(next)
                // next 复制了游标行全部字段；false 用于显式写入 nextRetryTime=NULL。
                : cursorMapper.update(next, false);
        requireExactlyOneRow(affectedRows,
                cursor == null ? "新增 L2 抽取游标" : "更新 L2 抽取游标");
    }

    private AppProcessResult recordFailureSafely(
            Long userId,
            Long appId,
            AppMemoryExtractCursor cursor) {
        LocalDateTime now = LocalDateTime.now(clock);
        int failCount = incrementFailCount(cursorFailCount(cursor));
        LocalDateTime nextRetryTime = now.plus(retryDelay(failCount));
        Instant retryAt = nextRetryTime.atZone(clock.getZone()).toInstant();
        try {
            AppProcessResult result = transactionOperations.execute(status -> {
                memoryRepository.lockUser(userId);
                requireAppOwner(userId, appId);
                AppMemoryExtractCursor current = loadCurrentCursor(appId);
                // 失败回调可能晚于另一实例的成功提交，不能把已推进的游标写回旧位置。
                if (cursorValue(current) > cursorValue(cursor)) return AppProcessResult.MORE_PENDING;
                if (current != null && !userId.equals(current.getUserId())) throw new MemoryOwnershipException();
                int currentFailCount = incrementFailCount(cursorFailCount(current));
                LocalDateTime currentRetryTime = now.plus(retryDelay(currentFailCount));
                AppMemoryExtractCursor failed = AppMemoryExtractCursor.builder()
                        .id(current == null ? null : current.getId()).appId(appId).userId(userId)
                        .lastExtractedId(cursorValue(current)).failCount(currentFailCount)
                        .nextRetryTime(currentRetryTime).createTime(current == null ? now : current.getCreateTime())
                        .updateTime(now).isDelete(current == null || current.getIsDelete() == null ? 0 : current.getIsDelete()).build();
                int affectedRows = current == null ? cursorMapper.insert(failed) : cursorMapper.update(failed);
                requireExactlyOneRow(affectedRows, "记录 L2 失败游标");
                return AppProcessResult.failedAt(currentRetryTime.atZone(clock.getZone()).toInstant(), currentFailCount);
            });
            if (result != null) return result;
        } catch (RuntimeException exception) {
            log.error("记录 L2 抽取失败元数据异常 userId={} appId={} type={}",
                    userId, appId, exception.getClass().getSimpleName());
        }
        return AppProcessResult.failedAt(retryAt, failCount);
    }

    private AppProcessResult persistedRetryResult(
            AppMemoryExtractCursor cursor) {
        LocalDateTime nextRetryTime = cursor == null
                ? null : cursor.getNextRetryTime();
        if (nextRetryTime == null
                || !LocalDateTime.now(clock).isBefore(nextRetryTime)) {
            return null;
        }
        Instant retryAt = nextRetryTime.atZone(clock.getZone()).toInstant();
        return AppProcessResult.deferredUntil(
                retryAt, cursorFailCount(cursor));
    }

    private boolean hasFailureMetadata(AppMemoryExtractCursor cursor) {
        return cursor != null
                && (cursorFailCount(cursor) > 0
                || cursor.getNextRetryTime() != null);
    }

    private int cursorFailCount(AppMemoryExtractCursor cursor) {
        return cursor == null || cursor.getFailCount() == null
                ? 0 : Math.max(0, cursor.getFailCount());
    }

    private long cursorValue(AppMemoryExtractCursor cursor) {
        return cursor == null || cursor.getLastExtractedId() == null
                ? 0L : cursor.getLastExtractedId();
    }

    private void requireExactlyOneRow(int affectedRows, String operation) {
        if (affectedRows != 1) {
            throw new IllegalStateException(
                    operation + "影响行数必须为 1，实际为 " + affectedRows);
        }
    }

    private String renderRecallPreferenceLines(
            Long userId, List<AppMemory> prefs) {
        if (CollUtil.isEmpty(prefs)) {
            return "";
        }
        StringBuilder recalled = new StringBuilder();
        Set<Long> seen = new HashSet<>();
        for (AppMemory preference : prefs) {
            if (preference == null || !userId.equals(preference.getUserId())
                    || !STATUS_ACTIVE.equals(preference.getStatus())
                    || Integer.valueOf(1).equals(preference.getIsDelete())
                    || preference.getId() == null || !seen.add(preference.getId())) {
                continue;
            }
            String line = renderPreferenceLine(preference);
            int estimatedTokens = l2FragmentBuilder.estimate(line);
            if (estimatedTokens > tokenProperties.getL2MaxRecallTokens()) {
                log.warn("跳过超过 L2 召回 Token 上限的单条偏好 "
                                + "userId={} memoryId={} estimatedTokens={}",
                        userId, preference.getId(), estimatedTokens);
                continue;
            }
            if (!isSupportedPreference(preference)) {
                continue;
            }
            String candidate = recalled.isEmpty()
                    ? line : recalled + "\n" + line;
            if (isWithinRecallBudget(candidate)) {
                if (!recalled.isEmpty()) {
                    recalled.append('\n');
                }
                recalled.append(line);
            }
        }
        return recalled.toString();
    }

    private String renderPreferenceLine(AppMemory preference) {
        return preferenceContract.renderPreferenceLine(
                Objects.toString(preference.getName(), ""),
                Objects.toString(preference.getAttributeName(), ""),
                Objects.toString(preference.getContent(), ""));
    }

    private boolean isSupportedPreference(AppMemory preference) {
        return preference != null
                && preferenceContract.isPreferenceWithinBudget(
                preference.getName(), preference.getAttributeName(), preference.getContent());
    }

    private boolean isWithinRecallBudget(String text) {
        return l2FragmentBuilder.isWithinBudget(text);
    }

    private void invalidateRecallCache(Long userId) {
        deleteCacheKey(LEGACY_PREF_CACHE_PREFIX + userId, userId);
        deleteCacheKey(PREVIOUS_PREF_CACHE_PREFIX + userId, userId);
        deleteCacheKey(PREF_CACHE_PREFIX + userId, userId);
    }

    private void invalidateCurrentRecallCacheStrict(Long userId) {
        try {
            redisTemplate.delete(PREF_CACHE_PREFIX + userId);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "失效新版偏好缓存失败，userId=" + userId,
                    exception);
        }
    }

    private void deleteCacheKey(String cacheKey, Long userId) {
        try {
            redisTemplate.delete(cacheKey);
        } catch (Exception exception) {
            log.warn("失效偏好缓存失败 userId={} type={}",
                    userId, exception.getClass().getSimpleName());
        }
    }

    private void writeCache(String cacheKey, String text) {
        try {
            redisTemplate.opsForValue().set(cacheKey, text, CACHE_TTL);
        } catch (Exception exception) {
            log.warn("写偏好缓存失败 key={} type={}",
                    cacheKey, exception.getClass().getSimpleName());
        }
    }

    @Override
    public MemoryCacheInvalidationResult invalidateCaches(
            Long appId, Long userId) {
        requirePositiveId(appId, "应用 ID");
        requirePositiveId(userId, "用户 ID");
        MemoryCacheInvalidationResult result =
                MemoryCacheInvalidationResult.success();
        try {
            cancelPendingApp(userId, appId);
        } catch (RuntimeException exception) {
            log.warn("撤销 L2 待处理版本失败 appId={} userId={} type={}",
                    appId, userId, exception.getClass().getSimpleName());
            result = result.merge(MemoryCacheInvalidationResult.failure(
                    "L2_PENDING_LOCAL", exception));
        }
        try {
            appIdToUserId.remove(appId);
        } catch (Exception exception) {
            log.warn("清理 L2 应用归属进程缓存失败 appId={} type={}",
                    appId, exception.getClass().getSimpleName());
            result = result.merge(MemoryCacheInvalidationResult.failure(
                    "L2_APP_USER_LOCAL", exception));
        }
        try (UserMemoryConsistencyCoordinator.Permit ignored =
                     consistencyCoordinator.acquire(userId)) {
            result = invalidatePreferenceCacheForDeletion(
                    appId, userId, LEGACY_PREF_CACHE_PREFIX + userId,
                    "L2_PREFERENCE_REDIS_LEGACY", result);
            result = invalidatePreferenceCacheForDeletion(
                    appId, userId, PREVIOUS_PREF_CACHE_PREFIX + userId,
                    "L2_PREFERENCE_REDIS_V2", result);
            result = invalidatePreferenceCacheForDeletion(
                    appId, userId, PREF_CACHE_PREFIX + userId,
                    "L2_PREFERENCE_REDIS_V3", result);
        }
        return result;
    }

    private MemoryCacheInvalidationResult invalidatePreferenceCacheForDeletion(
            Long appId,
            Long userId,
            String cacheKey,
            String failureTarget,
            MemoryCacheInvalidationResult result) {
        try {
            redisTemplate.delete(cacheKey);
            return result;
        } catch (Exception exception) {
            log.warn("清理 L2 偏好缓存失败 appId={} userId={} type={}",
                    appId, userId,
                    exception.getClass().getSimpleName());
            return result.merge(MemoryCacheInvalidationResult.failure(
                    failureTarget, exception));
        }
    }

    private void cancelPendingApp(Long userId, Long appId) {
        pendingByUser.computeIfPresent(userId, (ignored, state) -> {
            state.apps.remove(appId);
            if (state.apps.isEmpty() && !state.workerRunning) {
                cancelScheduled(state);
                return null;
            }
            if (!state.workerRunning) {
                scheduleNext(userId, state);
            }
            return state;
        });
    }

    private void requirePositiveId(Long id, String name) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException(name + " 必须为正数");
        }
    }
}
