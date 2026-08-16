package com.lyw.appgeneration.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.lyw.appgeneration.ai.memory.ChatTokenEstimator;
import com.lyw.appgeneration.ai.memory.UserPreferenceCandidate;
import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.mapper.AppMapper;
import com.lyw.appgeneration.mapper.AppMemoryExtractCursorMapper;
import com.lyw.appgeneration.mapper.AppMemoryMapper;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.entity.AppMemory;
import com.lyw.appgeneration.model.entity.AppMemoryExtractCursor;
import com.lyw.appgeneration.model.entity.ChatHistory;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * L2 跨 app 用户长期记忆服务实现。
 *
 * <p>抽取由稳定回合钩子触发，按 userId 防抖且同一用户单轮执行；每个 app 独立维护游标，
 * 模型只接收完整回合的用户文本，并按 (userId,type,name) 合并结构化证据。召回只拼接 ACTIVE 偏好，
 * EXPLICIT 优先且严格控制在 1K Token 内，结果使用版本化 Redis 键缓存。失败时游标不推进并记录
 * failCount，调度按 5 秒至 5 分钟指数退避恢复，不会因连续失败永久停更。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
@Slf4j
@Service
public class UserMemoryServiceImpl implements UserMemoryService {

    private static final String TYPE_USER_PREFERENCE = "USER_PREFERENCE";
    private static final int HISTORY_SCAN_PAGE_SIZE = 100;
    private static final String EVIDENCE_EXPLICIT =
            UserPreferenceCandidateParser.EXPLICIT;
    private static final String EVIDENCE_IMPLICIT =
            UserPreferenceCandidateParser.IMPLICIT;
    private static final String STATUS_CANDIDATE = "CANDIDATE";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String LEGACY_PREF_CACHE_PREFIX = "mem:pref:";
    private static final String PREF_CACHE_PREFIX = "mem:pref:v2:";
    private static final Duration CACHE_TTL = Duration.ofHours(1);
    private static final Duration RETRY_BASE_DELAY = Duration.ofSeconds(5);
    private static final Duration RETRY_MAX_DELAY = Duration.ofMinutes(5);

    private final ChatHistoryService chatHistoryService;
    private final AppMemoryMapper appMemoryMapper;
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
    private final UserPreferenceCandidateParser preferenceCandidateParser;
    private final TransactionOperations transactionOperations;
    private final Clock clock;

    /** 防抖状态仅通过 ConcurrentHashMap.compute 系列方法变更。 */
    private final ConcurrentHashMap<Long, UserDirtyState> pendingByUser =
            new ConcurrentHashMap<>();
    /** appId→userId 反查缓存(归属永不变,与 Caffeine service 缓存隔离)。 */
    private final ConcurrentHashMap<Long, Long> appIdToUserId = new ConcurrentHashMap<>();

    @Autowired
    public UserMemoryServiceImpl(ChatHistoryService chatHistoryService,
                                 AppMemoryMapper appMemoryMapper,
                                 AppMemoryExtractCursorMapper cursorMapper,
                                 AppMapper appMapper,
                                 @Qualifier("openAiChatModel") ChatModel extractionModel,
                                 @Qualifier("memorySummarizationExecutor") ExecutorService executor,
                                 @Qualifier("userMemoryDebounceScheduler") TaskScheduler debounceScheduler,
                                 StringRedisTemplate redisTemplate,
                                 AppDataLifecycleFence lifecycleFence,
                                 ChatTokenEstimator tokenEstimator,
                                 MemoryTokenProperties tokenProperties,
                                 ObjectProvider<PlatformTransactionManager>
                                         transactionManagerProvider) {
        this(chatHistoryService, appMemoryMapper, cursorMapper, appMapper,
                extractionModel, executor, debounceScheduler, redisTemplate,
                lifecycleFence, tokenEstimator, tokenProperties,
                resolveTransactionOperations(transactionManagerProvider));
    }

    public UserMemoryServiceImpl(ChatHistoryService chatHistoryService,
                                 AppMemoryMapper appMemoryMapper,
                                 AppMemoryExtractCursorMapper cursorMapper,
                                 AppMapper appMapper,
                                 ChatModel extractionModel,
                                 ExecutorService executor,
                                 TaskScheduler debounceScheduler,
                                 StringRedisTemplate redisTemplate,
                                 AppDataLifecycleFence lifecycleFence,
                                 ChatTokenEstimator tokenEstimator,
                                 MemoryTokenProperties tokenProperties,
                                 TransactionOperations transactionOperations) {
        this(chatHistoryService, appMemoryMapper, cursorMapper, appMapper, extractionModel, executor,
                debounceScheduler, redisTemplate, lifecycleFence,
                tokenEstimator, tokenProperties, transactionOperations,
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
                          AppMemoryMapper appMemoryMapper,
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
                          Clock clock) {
        this.chatHistoryService = Objects.requireNonNull(
                chatHistoryService, "对话历史服务不能为空");
        this.appMemoryMapper = Objects.requireNonNull(
                appMemoryMapper, "用户记忆 Mapper 不能为空");
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
                this.tokenEstimator, this.tokenProperties);
        this.preferenceCandidateParser =
                new UserPreferenceCandidateParser();
        this.transactionOperations = Objects.requireNonNull(
                transactionOperations, "事务执行器不能为空");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
    }

    @Override
    public void triggerPreferenceExtractionAsync(
            Long userId, Long appId, Long stableAiMessageId) {
        if (userId == null || userId <= 0 || appId == null || appId <= 0
                || stableAiMessageId == null || stableAiMessageId <= 0) {
            return;
        }
        registerDirtyApp(userId, appId, stableAiMessageId);
    }

    private void registerDirtyApp(
            Long userId, Long appId, long stableAiMessageId) {
        Instant quietUntil = clock.instant().plus(tokenProperties.getL2Debounce());
        pendingByUser.compute(userId, (ignored, current) -> {
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
        try {
            ScheduledFuture<?> scheduled = debounceScheduler.schedule(
                    () -> onDebounceTimer(
                            userId, expectedState, generation), nextRunAt);
            if (scheduled == null) {
                throw new IllegalStateException("防抖调度器未返回任务句柄");
            }
            state.scheduled = scheduled;
        } catch (RuntimeException exception) {
            state.scheduled = null;
            log.warn("调度 L2 偏好抽取失败 userId={} type={}",
                    userId, exception.getClass().getSimpleName());
        }
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
            executor.submit(() -> runDirtyRound(
                    userId, expectedState, List.copyOf(snapshots)));
        } catch (RuntimeException exception) {
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
        AppDataLifecycleFence.WriterPermit writerPermit =
                lifecycleFence.tryAcquireWriter(work.snapshot.appId);
        if (writerPermit == null) {
            return AppProcessResult.FAILED;
        }
        try (writerPermit) {
            return extractWithinPermit(
                    userId, work.snapshot.appId, work.cursor,
                    work.historyUpperBound);
        }
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
                if (result == AppProcessResult.COMPLETE) {
                    state.apps.remove(snapshot.appId);
                } else if (result == AppProcessResult.MORE_PENDING) {
                    state.apps.put(snapshot.appId,
                            current.readyForNextBatch());
                } else if (result == AppProcessResult.DEFERRED) {
                    continue;
                } else {
                    state.apps.put(snapshot.appId,
                            current.failedAt(now));
                }
            }
            if (state.apps.isEmpty()) {
                cancelScheduled(state);
                return null;
            }
            scheduleNext(userId, state);
            return state;
        });
    }

    /** 同步抽取一次。best-effort,不抛异常。 */
    public void extractNow(Long userId, Long appId) {
        if (userId == null || userId <= 0 || appId == null || appId <= 0) {
            return;
        }
        AppDataLifecycleFence.WriterPermit writerPermit =
                lifecycleFence.tryAcquireWriter(appId);
        if (writerPermit == null) {
            log.info("同步偏好抽取被应用数据删除门拒绝 userId={} appId={}",
                    userId, appId);
            return;
        }
        try (writerPermit) {
            extractWithinPermit(userId, appId);
        }
    }

    private void extractWithinPermit(Long userId, Long appId) {
        AppMemoryExtractCursor cursor;
        try {
            cursor = cursorMapper.selectOneByQuery(
                    QueryWrapper.create().eq("appId", appId));
        } catch (RuntimeException exception) {
            log.error("读取 L2 抽取游标失败 userId={} appId={} type={}",
                    userId, appId, exception.getClass().getSimpleName());
            return;
        }
        extractWithinPermit(userId, appId, cursor);
    }

    private AppProcessResult extractWithinPermit(
            Long userId,
            Long appId,
            AppMemoryExtractCursor cursor) {
        return extractWithinPermit(
                userId, appId, cursor, Long.MAX_VALUE);
    }

    private AppProcessResult extractWithinPermit(
            Long userId,
            Long appId,
            AppMemoryExtractCursor cursor,
            long historyUpperBound) {
        try {
            long lastId = cursorValue(cursor);
            String existing = renderExistingPreferences(userId);
            UserPreferenceBatchBuilder.Batch batch = buildPreferenceBatch(
                    appId, lastId, historyUpperBound, existing);
            if (batch.completedThroughId() == lastId) {
                return AppProcessResult.COMPLETE;
            }
            List<UserPreferenceCandidate> candidates =
                    extractPreferenceCandidates(userId, appId, batch);
            if (candidates == null) {
                recordFailureSafely(userId, appId, cursor);
                return AppProcessResult.FAILED;
            }
            return completePreferenceBatch(userId, appId, cursor, lastId,
                    batch, candidates);
        } catch (RuntimeException exception) {
            log.error("L2 偏好抽取失败 userId={} appId={} type={}",
                    userId, appId, exception.getClass().getSimpleName());
            recordFailureSafely(userId, appId, cursor);
            return AppProcessResult.FAILED;
        }
    }

    private List<UserPreferenceCandidate> extractPreferenceCandidates(
            Long userId,
            Long appId,
            UserPreferenceBatchBuilder.Batch batch) {
        if (!batch.hasTurns()) {
            return List.of();
        }
        String raw;
        try {
            raw = extractionModel.chat(batch.prompt());
        } catch (RuntimeException modelException) {
            log.error("L2 抽取模型调用失败 userId={} appId={} type={}",
                    userId, appId,
                    modelException.getClass().getSimpleName());
            return null;
        }
        List<UserPreferenceCandidate> candidates =
                preferenceCandidateParser.parse(raw, batch.turnIds());
        if (candidates == null) {
            log.warn("L2 抽取输出未通过 JSON 契约 userId={} appId={}",
                    userId, appId);
        }
        return candidates;
    }

    private boolean persistPreferenceCandidates(
            Long userId,
            Long appId,
            List<UserPreferenceCandidate> candidates) {
        boolean preferenceChanged = false;
        for (UserPreferenceCandidate candidate : candidates) {
            preferenceChanged |= upsertPreference(
                    userId, appId, candidate);
        }
        return preferenceChanged;
    }

    private AppProcessResult completePreferenceBatch(
            Long userId,
            Long appId,
            AppMemoryExtractCursor cursor,
            long lastId,
            UserPreferenceBatchBuilder.Batch batch,
            List<UserPreferenceCandidate> candidates) {
        boolean preferenceChanged = persistBatchAtomically(
                userId, appId, cursor, batch, candidates);
        if (preferenceChanged) {
            invalidateRecallCache(userId);
        }
        log.info("L2 偏好抽取完成 userId={} appId={} cursorFrom={} "
                        + "cursorTo={} candidateCount={} hasMore={}",
                userId, appId, lastId, batch.completedThroughId(),
                candidates.size(), batch.hasMore());
        return batch.hasMore()
                ? AppProcessResult.MORE_PENDING
                : AppProcessResult.COMPLETE;
    }

    private boolean persistBatchAtomically(
            Long userId,
            Long appId,
            AppMemoryExtractCursor cursor,
            UserPreferenceBatchBuilder.Batch batch,
            List<UserPreferenceCandidate> candidates) {
        Boolean preferenceChanged = transactionOperations.execute(status -> {
            boolean changed = persistPreferenceCandidates(
                    userId, appId, candidates);
            advanceCursor(
                    userId, appId, cursor, batch.completedThroughId());
            return changed;
        });
        if (preferenceChanged == null) {
            throw new IllegalStateException("L2 批次事务未返回持久化结果");
        }
        return preferenceChanged;
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

        private DirtyApp failedAt(Instant now) {
            int nextFailCount = failCount + 1;
            return new DirtyApp(version, historyUpperBound,
                    now.plus(retryDelay(nextFailCount)), nextFailCount);
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

    private enum AppProcessResult {
        COMPLETE,
        MORE_PENDING,
        DEFERRED,
        FAILED
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

    private String recallWithinPermit(Long appId) {
        Long userId = resolveUserId(appId);
        if (userId == null) {
            return "";
        }
        String cacheKey = PREF_CACHE_PREFIX + userId;
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null && isWithinRecallBudget(cached)) {
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
            List<AppMemory> prefs = appMemoryMapper.selectListByQuery(
                    QueryWrapper.create().eq("userId", userId).eq("type", TYPE_USER_PREFERENCE)
                            .eq("status", STATUS_ACTIVE)
                            .orderBy("evidenceType", true)
                            .orderBy("updateTime", false));
            text = renderRecallPreferenceLines(prefs);
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

    private boolean upsertPreference(
            Long userId,
            Long appId,
            UserPreferenceCandidate candidate) {
        AppMemory existing = appMemoryMapper.selectOneByQuery(
                QueryWrapper.create()
                        .eq("userId", userId)
                        .eq("type", TYPE_USER_PREFERENCE)
                        .eq("name", candidate.name()));
        AppMemory next = existing == null
                ? newPreference(userId, appId, candidate)
                : mergePreference(existing, candidate);
        if (next == null) {
            return false;
        }
        int affectedRows = existing == null
                ? appMemoryMapper.insert(next)
                : appMemoryMapper.update(next);
        requireExactlyOneRow(affectedRows,
                existing == null ? "新增用户偏好" : "更新用户偏好");
        return true;
    }

    private AppMemory newPreference(
            Long userId,
            Long appId,
            UserPreferenceCandidate candidate) {
        int evidenceCount = candidate.turnIds().size();
        LocalDateTime now = LocalDateTime.now(clock);
        return AppMemory.builder()
                .userId(userId)
                .appId(appId)
                .type(TYPE_USER_PREFERENCE)
                .name(candidate.name())
                .content(candidate.content())
                .evidenceType(candidate.evidenceType())
                .status(resolveStatus(
                        candidate.evidenceType(), evidenceCount))
                .evidenceCount(evidenceCount)
                .lastEvidenceTurnId(candidate.turnIds().getLast())
                .createTime(now)
                .updateTime(now)
                .build();
    }

    private AppMemory mergePreference(
            AppMemory existing,
            UserPreferenceCandidate candidate) {
        boolean sameContent = normalizeContent(existing.getContent())
                .equals(candidate.content());
        PreferenceEvidenceState nextEvidence = sameContent
                ? mergeSameContentEvidence(existing, candidate)
                : resetChangedContentEvidence(candidate);
        String status = resolveStatus(
                nextEvidence.evidenceType(), nextEvidence.evidenceCount());
        if (sameContent && isUnchangedPreference(
                existing, nextEvidence, status)) {
            return null;
        }
        return AppMemory.builder()
                .id(existing.getId())
                .userId(existing.getUserId())
                .appId(existing.getAppId())
                .type(existing.getType())
                .name(existing.getName())
                .content(nextEvidence.content())
                .evidenceType(nextEvidence.evidenceType())
                .status(status)
                .evidenceCount(nextEvidence.evidenceCount())
                .lastEvidenceTurnId(nextEvidence.lastEvidenceTurnId())
                .createTime(existing.getCreateTime())
                .updateTime(LocalDateTime.now(clock))
                .isDelete(existing.getIsDelete())
                .build();
    }

    private PreferenceEvidenceState mergeSameContentEvidence(
            AppMemory existing,
            UserPreferenceCandidate candidate) {
        long previousLast = safeLastEvidenceTurnId(existing);
        // 偏好与 app 游标同事务提交，已提交批次不会重放；
        // 较小 ID 可能来自后处理 app，不能按大小丢弃。
        int newEvidenceCount = (int) candidate.turnIds().stream()
                .filter(turnId -> turnId != previousLast)
                .count();
        String evidenceType = EVIDENCE_EXPLICIT.equals(
                existing.getEvidenceType())
                || EVIDENCE_EXPLICIT.equals(candidate.evidenceType())
                ? EVIDENCE_EXPLICIT : EVIDENCE_IMPLICIT;
        return new PreferenceEvidenceState(
                existing.getContent(), evidenceType,
                safeEvidenceCount(existing) + newEvidenceCount,
                Math.max(previousLast, candidate.turnIds().getLast()));
    }

    private PreferenceEvidenceState resetChangedContentEvidence(
            UserPreferenceCandidate candidate) {
        return new PreferenceEvidenceState(
                candidate.content(), candidate.evidenceType(),
                candidate.turnIds().size(), candidate.turnIds().getLast());
    }

    private boolean isUnchangedPreference(
            AppMemory existing,
            PreferenceEvidenceState nextEvidence,
            String status) {
        return nextEvidence.evidenceCount() == safeEvidenceCount(existing)
                && nextEvidence.lastEvidenceTurnId()
                == safeLastEvidenceTurnId(existing)
                && Objects.equals(nextEvidence.evidenceType(),
                        existing.getEvidenceType())
                && Objects.equals(status, existing.getStatus());
    }

    private record PreferenceEvidenceState(
            String content,
            String evidenceType,
            int evidenceCount,
            long lastEvidenceTurnId) {
    }

    private int safeEvidenceCount(AppMemory memory) {
        return memory.getEvidenceCount() == null
                ? 0 : Math.max(0, memory.getEvidenceCount());
    }

    private long safeLastEvidenceTurnId(AppMemory memory) {
        return memory.getLastEvidenceTurnId() == null
                ? 0L : memory.getLastEvidenceTurnId();
    }

    private String resolveStatus(String evidenceType, int evidenceCount) {
        return EVIDENCE_EXPLICIT.equals(evidenceType) || evidenceCount >= 2
                ? STATUS_ACTIVE : STATUS_CANDIDATE;
    }

    private String normalizeContent(String content) {
        return UserPreferenceCandidateParser.normalizeContent(content);
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
                .createTime(cursor == null ? now : cursor.getCreateTime())
                .updateTime(now)
                .isDelete(cursor == null ? null : cursor.getIsDelete())
                .build();
        int affectedRows = cursor == null
                ? cursorMapper.insert(next)
                : cursorMapper.update(next);
        requireExactlyOneRow(affectedRows,
                cursor == null ? "新增 L2 抽取游标" : "更新 L2 抽取游标");
    }

    private void recordFailureSafely(
            Long userId,
            Long appId,
            AppMemoryExtractCursor cursor) {
        try {
            LocalDateTime now = LocalDateTime.now(clock);
            int failCount = cursor == null || cursor.getFailCount() == null
                    ? 1 : cursor.getFailCount() + 1;
            AppMemoryExtractCursor failed = AppMemoryExtractCursor.builder()
                    .id(cursor == null ? null : cursor.getId())
                    .appId(appId)
                    .userId(userId)
                    .lastExtractedId(cursorValue(cursor))
                    .failCount(failCount)
                    .createTime(cursor == null ? now : cursor.getCreateTime())
                    .updateTime(now)
                    .isDelete(cursor == null ? null : cursor.getIsDelete())
                    .build();
            int affectedRows = cursor == null
                    ? cursorMapper.insert(failed)
                    : cursorMapper.update(failed);
            requireExactlyOneRow(affectedRows,
                    cursor == null ? "新增 L2 失败游标" : "更新 L2 失败游标");
        } catch (RuntimeException exception) {
            log.error("记录 L2 抽取失败元数据异常 userId={} appId={} type={}",
                    userId, appId, exception.getClass().getSimpleName());
        }
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

    private String renderExistingPreferences(Long userId) {
        List<AppMemory> preferences = appMemoryMapper.selectListByQuery(
                QueryWrapper.create()
                        .eq("userId", userId)
                        .eq("type", TYPE_USER_PREFERENCE)
                        .orderBy("updateTime", false));
        if (preferences == null) {
            throw new IllegalStateException("数据库返回了 null 已有偏好列表");
        }
        return renderExistingPreferenceStates(preferences);
    }

    private String renderExistingPreferenceStates(List<AppMemory> prefs) {
        if (CollUtil.isEmpty(prefs)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (AppMemory preference : prefs) {
            sb.append("- name=").append(preference.getName())
                    .append("; status=").append(preference.getStatus())
                    .append("; evidenceType=")
                    .append(preference.getEvidenceType())
                    .append("; content=").append(preference.getContent())
                    .append('\n');
        }
        return sb.toString().trim();
    }

    private String renderRecallPreferenceLines(List<AppMemory> prefs) {
        if (CollUtil.isEmpty(prefs)) {
            return "";
        }
        StringBuilder recalled = new StringBuilder();
        for (AppMemory preference : prefs) {
            String line = renderPreferenceLine(preference);
            if (!isWithinRecallBudget(line)) {
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
        return "- " + Objects.toString(preference.getName(), "")
                + ":" + Objects.toString(preference.getContent(), "");
    }

    private boolean isWithinRecallBudget(String text) {
        return tokenEstimator.estimateText(text)
                <= tokenProperties.getL2MaxRecallTokens();
    }

    private void invalidateRecallCache(Long userId) {
        deleteCacheKey(LEGACY_PREF_CACHE_PREFIX + userId, userId);
        deleteCacheKey(PREF_CACHE_PREFIX + userId, userId);
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
        result = invalidatePreferenceCacheForDeletion(
                appId, userId, LEGACY_PREF_CACHE_PREFIX + userId,
                "L2_PREFERENCE_REDIS_LEGACY", result);
        result = invalidatePreferenceCacheForDeletion(
                appId, userId, PREF_CACHE_PREFIX + userId,
                "L2_PREFERENCE_REDIS_V2", result);
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
