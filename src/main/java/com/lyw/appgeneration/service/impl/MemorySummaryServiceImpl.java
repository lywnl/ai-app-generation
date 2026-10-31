package com.lyw.appgeneration.service.impl;

import cn.hutool.core.util.StrUtil;
import com.lyw.appgeneration.ai.memory.ChatTokenEstimator;
import com.lyw.appgeneration.ai.memory.MemorySummaryContract;
import com.lyw.appgeneration.ai.memory.MemorySummaryFormat;
import com.lyw.appgeneration.ai.memory.AdmissionDeadline;
import com.lyw.appgeneration.ai.memory.L0Snapshot;
import com.lyw.appgeneration.ai.memory.SummaryInput;
import com.lyw.appgeneration.ai.memory.SummaryMaterialReader;
import com.lyw.appgeneration.ai.memory.SummaryMaterials;
import com.lyw.appgeneration.ai.memory.SummaryWindowSelector;
import com.lyw.appgeneration.ai.memory.SummaryCoveragePlanner;
import com.lyw.appgeneration.ai.memory.SummaryCoverageCodec;
import com.lyw.appgeneration.ai.memory.L0SourceOperations;
import com.lyw.appgeneration.ai.memory.SummaryUnit;
import com.lyw.appgeneration.ai.memory.DeadlineAwareReplaceResult;
import com.lyw.appgeneration.ai.memory.MemorySummarySnapshotCodec;
import com.lyw.appgeneration.service.CompatibleMemorySnapshot;
import com.lyw.appgeneration.ai.memory.AtomicChatMemoryStore;
import com.lyw.appgeneration.core.handler.VueTurnContext;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.mapper.AppMemorySummaryMapper;
import com.lyw.appgeneration.monitor.MemoryCompressionMetricsCollector;
import com.lyw.appgeneration.model.entity.AppMemorySummary;
import com.lyw.appgeneration.service.MemoryCacheInvalidationResult;
import com.lyw.appgeneration.service.MemoryCompressionResult;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.MemorySummarySnapshot;
import com.lyw.appgeneration.service.UnifiedSummaryRequest;
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
import java.util.function.Function;

/** L1 唯一 3K 硬上限滚动摘要服务。 */
@Slf4j
@Service
public class MemorySummaryServiceImpl implements MemorySummaryService {

    private static final String CACHE_KEY_PREFIX = "mem:summary:";
    private static final String SNAPSHOT_CACHE_KEY_PREFIX = "mem:summary:v2:";
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
    private SummaryMaterialReader unifiedMaterialReader;
    private SummaryWindowSelector unifiedWindowSelector;
    private SummaryCoveragePlanner unifiedCoveragePlanner;
    private ConversationDecisionEvidenceService decisionEvidenceService;
    private AtomicChatMemoryStore terminalStore;
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

    @Autowired
    void configureUnifiedInputs(SummaryMaterialReader reader, SummaryWindowSelector selector,
                                SummaryCoveragePlanner coverage, ConversationDecisionEvidenceService evidence) {
        unifiedMaterialReader = reader;
        unifiedWindowSelector = selector;
        unifiedCoveragePlanner = coverage;
        decisionEvidenceService = evidence;
    }

    @Autowired
    void configureTerminalStore(AtomicChatMemoryStore store) {
        terminalStore = Objects.requireNonNull(store);
    }

    @Override
    public ChatHistory persistTurnOutcome(VueTurnContext context, java.util.function.Supplier<ChatHistory> saveAi) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(saveAi);
        Objects.requireNonNull(terminalStore, "终态来源存储未装配");
        if (context.turnState().stage() != VueTurnContext.TurnStage.FINALIZING) {
            throw new IllegalStateException("只有已取得终态的分支可以保存回合");
        }
        long userHistoryId = context.userHistoryId();
        AdmissionDeadline deadline = AdmissionDeadline.start(properties.getBlockingTimeout());
        try (var writer = lifecycleFence.tryAcquireWriter(context.appId())) {
            if (writer == null) throw new IllegalStateException("应用删除流程已接管终态保存");
            return terminalStore.withMemoryLock(context.appId(), deadline, () -> {
                L0Snapshot source = terminalStore.supportsSourceSnapshots() ? terminalStore.getSnapshot(context.appId())
                        : L0Snapshot.legacy(Long.toString(context.appId()), terminalStore.getMessages(context.appId()));
                long end = monotonicDeadline(deadline);
                try (var consistency = consistencyCoordinator.tryAcquireUntil(context.appId(), end)) {
                    if (consistency == null) throw new java.util.concurrent.CancellationException("终态摘要登记等待截止");
                    var saved = new java.util.concurrent.atomic.AtomicReference<ChatHistory>();
                    PersistenceOutcome outcome = executePersistenceTransaction(end, status -> {
                        var finalEvidence = Objects.requireNonNull(decisionEvidenceService.mergeFinalAtCommit(context, source),
                                "终态证据没有可靠保存");
                        if (finalEvidence.revision() <= 0 || finalEvidence.userHistoryId() != userHistoryId) {
                            throw new IllegalStateException("终态证据的持久身份或修订无效");
                        }
                        ChatHistory ai = Objects.requireNonNull(saveAi.get(), "AI 终态没有成功保存");
                        if (ai.getId() == null || ai.getId() <= userHistoryId || !Objects.equals(ai.getAppId(), context.appId())
                                || !Objects.equals(ai.getUserId(), context.userId()) || !"ai".equals(ai.getMessageType())
                                || ai.getMemoryOutcome() == null || StrUtil.isBlank(ai.getMemoryMessage())) {
                            throw new IllegalStateException("AI 终态的持久身份或可信结果无效");
                        }
                        AppMemorySummary current = summaryMapper.selectOneByQuery(QueryWrapper.create()
                                .eq("appId", context.appId()).eq("isDelete", 0).forUpdate());
                        MemorySummarySnapshot previous = snapshotOf(current);
                        var frontier = previous.coverageState();
                        if (frontier != null && frontier.userHistoryId() == userHistoryId) {
                            if (!frontier.turnId().equals(context.turnId())) throw new IllegalStateException("部分前沿不属于终态回合");
                            var ended = frontier.withTerminal(ai.getId(), ai.getMemoryOutcome());
                            if (!ended.equals(frontier)) {
                                deleteCacheStrict(CACHE_KEY_PREFIX + context.appId(), context.appId());
                                var next = new MemorySummarySnapshot(previous.summary(), previous.lastSummarizedId(),
                                        Math.addExact(previous.summaryRevision(), 1), previous.coverageVersion(), ended);
                                persistSnapshot(context.appId(), current, next, tokenEstimator.estimateText(next.summary()));
                            }
                        }
                        if (deadline.remainingNanos() <= 0) {
                            status.setRollbackOnly();
                            return PersistenceOutcome.TIMED_OUT;
                        }
                        saved.set(ai);
                        return PersistenceOutcome.PERSISTED;
                    });
                    if (outcome != PersistenceOutcome.PERSISTED) throw new java.util.concurrent.CancellationException("终态保存截止");
                    return saved.get();
                }
            });
        }
    }

    @Override
    public MemoryCompressionResult compressUnified(UnifiedSummaryRequest request, AdmissionDeadline deadline) {
        reclaimUnified(request, deadline);
        return compressUnified(request, deadline, this::commitAndReclaim);
    }

    @Override
    public void triggerUnifiedAsync(UnifiedSummaryRequest request, AdmissionDeadline deadline) {
        triggerUnified(request, deadline, this::commitAndReclaim);
    }

    private MemoryCompressionResult commitAndReclaim(UnifiedDraft work) {
        MemoryCompressionResult outcome;
        try {
            outcome = commitUnifiedDraft(work);
        } catch (PersistenceCommitUncertainException exception) {
            // 提交确认丢失时只重读数据库事实；无法确认则保持 L0，不能回写旧窗口。
            if (work.deadline().remainingNanos() <= 0 || !unifiedAllowed(work.request())) throw exception;
            MemorySummarySnapshot committed = readFreshSnapshot(work.request().appId(), work.deadline());
            if (!committed.equals(work.input().committed(work.draft().summary()))) throw exception;
            clearFallbackRetryDelay(work.request().appId());
            metricsCollector.recordSummaryDraftSuccess(work.draft().summaryTokens(), work.draft().reducerRounds());
            outcome = result(MemoryCompressionResult.Status.COMPRESSED, committed.lastSummarizedId(),
                    work.draft().summaryTokens(), "摘要提交已重新确认");
        }
        if (outcome.status() == MemoryCompressionResult.Status.COMPRESSED) {
            DeadlineAwareReplaceResult reclaimed = reclaimUnified(work.request(), work.deadline());
            if (reclaimed != DeadlineAwareReplaceResult.REPLACED) {
                log.info("摘要已提交，原文回收待重试 appId={} reason={}", work.request().appId(), reclaimed);
            }
        }
        return outcome;
    }

    @Override
    public DeadlineAwareReplaceResult reclaimUnified(UnifiedSummaryRequest request, AdmissionDeadline deadline) {
        if (deadline.remainingNanos() <= 0) return DeadlineAwareReplaceResult.TIMED_OUT;
        if (!unifiedAllowed(request)) return DeadlineAwareReplaceResult.PREFIX_CHANGED;
        try {
            // 数据库读取结束后才取得 L0 锁；此接口不反向持摘要一致性锁读取 L0。
            MemorySummarySnapshot summary = readFreshSnapshot(request.appId(), deadline);
            if (summary.summaryRevision() == 0) return DeadlineAwareReplaceResult.REPLACED;
            return request.context().commitSummaryWhileActive(deadline, () -> {
                try (var writer = lifecycleFence.tryAcquireWriter(request.appId())) {
                    if (writer == null) return DeadlineAwareReplaceResult.PREFIX_CHANGED;
                    return request.memory().withSourceLock(deadline, source -> {
                        var turn = source.turn(request.userHistoryId());
                        if (!source.hasKnownSource() || turn == null || !turn.turnId().equals(request.context().turnId())
                                || turn.closed() || summary.summaryRevision() < source.appliedSummaryRevision()) {
                            return DeadlineAwareReplaceResult.PREFIX_CHANGED;
                        }
                        L0Snapshot replacement = L0SourceOperations.prune(source, summary, request.userHistoryId());
                        if (replacement.equals(source)) return DeadlineAwareReplaceResult.REPLACED;
                        return request.memory().replaceSourceSnapshot(source, replacement, deadline);
                    });
                }
            });
        } catch (java.util.concurrent.CancellationException exception) {
            return Thread.currentThread().isInterrupted() ? DeadlineAwareReplaceResult.INTERRUPTED : DeadlineAwareReplaceResult.TIMED_OUT;
        } catch (RuntimeException exception) {
            log.warn("原文回收未确认 appId={} type={}", request.appId(), exception.getClass().getSimpleName());
            return DeadlineAwareReplaceResult.DEPENDENCY_FAILED;
        }
    }

    /** 与旧完整历史入口共享同一应用 single-flight；加入较早任务后重新选择剩余来源。 */
    MemoryCompressionResult compressUnified(UnifiedSummaryRequest request, AdmissionDeadline deadline,
                                            Function<UnifiedDraft, MemoryCompressionResult> commit) {
        while (true) {
            if (deadline.remainingNanos() <= 0) return result(MemoryCompressionResult.Status.TIMED_OUT, 0, 0, "统一摘要截止已耗尽");
            if (!unifiedAllowed(request)) return result(MemoryCompressionResult.Status.DELETE_REJECTED, 0, 0, "原回合或应用已失效");
            var flight = new CompletableFuture<MemoryCompressionResult>();
            var existing = inFlight.putIfAbsent(request.appId(), flight);
            if (existing != null) {
                var joined = awaitExistingFlight(existing, monotonicDeadline(deadline));
                if (joined.status() != MemoryCompressionResult.Status.COMPRESSED
                        && joined.status() != MemoryCompressionResult.Status.NOTHING_TO_COMPRESS) return joined;
                inFlight.remove(request.appId(), existing);
                continue;
            }
            MemoryCompressionResult outcome = null;
            try {
                outcome = executeUnified(request, deadline, commit, MemoryCompressionMetricsCollector.CompressionMode.BLOCKING);
                return outcome;
            } finally {
                finishFlight(request.appId(), flight, outcome);
            }
        }
    }

    boolean triggerUnified(UnifiedSummaryRequest request, AdmissionDeadline deadline,
                            Function<UnifiedDraft, MemoryCompressionResult> commit) {
        if (!unifiedAllowed(request) || deadline.remainingNanos() <= 0 || !isFallbackRetryReady(request.appId())) return false;
        var flight = new CompletableFuture<MemoryCompressionResult>();
        if (inFlight.putIfAbsent(request.appId(), flight) != null) return false;
        try {
            executor.submit(() -> {
                MemoryCompressionResult outcome = null;
                try {
                    if (!unifiedAllowed(request) || deadline.remainingNanos() <= 0) {
                        outcome = result(MemoryCompressionResult.Status.NOTHING_TO_COMPRESS, 0, 0, "后台任务启动前回合已失效");
                    } else {
                        outcome = checkBackgroundCompressionReady(request.appId());
                        if (outcome == null) outcome = executeUnified(request, deadline, commit, MemoryCompressionMetricsCollector.CompressionMode.ASYNC);
                    }
                } finally {
                    finishFlight(request.appId(), flight, outcome);
                }
            });
            return true;
        } catch (RuntimeException exception) {
            metricsCollector.recordCompressionExecutorRejected(MemoryCompressionMetricsCollector.CompressionMode.ASYNC);
            ensureFallbackRetryDelayIfWritable(request.appId());
            finishFlight(request.appId(), flight, result(MemoryCompressionResult.Status.MODEL_FAILED, 0, 0, "统一摘要任务提交失败"));
            return false;
        }
    }

    private MemoryCompressionResult executeUnified(UnifiedSummaryRequest request, AdmissionDeadline deadline,
                                                    Function<UnifiedDraft, MemoryCompressionResult> commit,
                                                    MemoryCompressionMetricsCollector.CompressionMode mode) {
        MemoryCompressionResult outcome = null;
        MemorySummarySnapshot attempted = null;
        var observation = metricsCollector.startCompression(mode);
        try {
            SummaryInput input = prepareUnifiedInput(request, deadline);
            if (input == null) {
                var current = readFreshSnapshot(request.appId(), deadline);
                outcome = result(MemoryCompressionResult.Status.NOTHING_TO_COMPRESS, current.lastSummarizedId(),
                        tokenEstimator.estimateText(current.summary()), "没有新增可压缩来源");
            } else {
                attempted = input.previous();
                var draft = draftEngine.buildDraft(input, monotonicDeadline(deadline), () ->
                        deadline.remainingNanos() > 0 && unifiedAllowed(request));
                if (draft.failureStatus() != null) {
                    metricsCollector.recordSummaryDraftFailure(draft.reducerRounds());
                    outcome = recordUnifiedFailure(request, input.previous(), deadline, draft.failureStatus(), draft.detail());
                } else if (!unifiedAllowed(request) || deadline.remainingNanos() <= 0) {
                    outcome = result(MemoryCompressionResult.Status.TIMED_OUT, input.previous().lastSummarizedId(), 0, "草稿返回时回合或截止已失效");
                } else {
                    outcome = commit.apply(new UnifiedDraft(request, input, draft, deadline));
                }
            }
            return outcome;
        } catch (ConversationDecisionEvidenceService.SourceCollectionPendingException exception) {
            outcome = result(MemoryCompressionResult.Status.NOTHING_TO_COMPRESS, 0, 0, "来源采集尚未齐备，保留原文");
            return outcome;
        } catch (java.util.concurrent.CancellationException exception) {
            outcome = result(MemoryCompressionResult.Status.TIMED_OUT, 0, 0, "统一摘要准备已终止");
            return outcome;
        } catch (RuntimeException exception) {
            log.warn("统一摘要失败 appId={} type={}", request.appId(), exception.getClass().getSimpleName());
            outcome = recordUnifiedFailure(request, attempted, deadline,
                    MemoryCompressionResult.Status.MODEL_FAILED, "统一摘要内部失败");
            return outcome;
        } finally {
            completeObservation(observation, outcome);
        }
    }

    private MemoryCompressionResult recordUnifiedFailure(UnifiedSummaryRequest request, MemorySummarySnapshot previous,
                                                          AdmissionDeadline deadline, MemoryCompressionResult.Status status,
                                                          String detail) {
        MemoryCompressionResult failed = result(status, previous == null ? 0 : previous.lastSummarizedId(),
                previous == null ? 0 : tokenEstimator.estimateText(previous.summary()), detail);
        if (deadline.remainingNanos() <= 0 || !unifiedAllowed(request)) return failed;
        if (previous == null) {
            ensureFallbackRetryDelayIfWritable(request.appId());
            return failed;
        }
        try {
            return request.context().commitSummaryWhileActive(deadline, () -> {
                try (var writer = lifecycleFence.tryAcquireWriter(request.appId())) {
                    if (writer == null) return failed;
                    AppMemorySummary current = selectCurrentSummary(request.appId());
                    if (revisionOf(current) != previous.summaryRevision()) return failed;
                    return recordFailureWithinDeadline(request.appId(), current, status, detail, monotonicDeadline(deadline));
                }
            });
        } catch (RuntimeException ignored) {
            if (unifiedAllowed(request)) ensureFallbackRetryDelayIfWritable(request.appId());
            return failed;
        }
    }

    SummaryInput prepareUnifiedInput(UnifiedSummaryRequest request, AdmissionDeadline deadline) {
        if (unifiedMaterialReader == null) throw new IllegalStateException("统一摘要材料接口未装配");
        var compatible = readCompatible(request, deadline);
        MemorySummarySnapshot previous = compatible.summary();
        L0Snapshot source = compatible.source();
        SummaryMaterials materials = unifiedMaterialReader.read(request.appId(), request.userId(), request.userHistoryId(), previous, source, deadline);
        var selection = unifiedWindowSelector.select(materials, unifiedCoveragePlanner.uncovered(materials));
        if (!selection.hasWork()) return null;
        long through = selection.selected().stream().filter(unit -> unit.userHistoryId() == request.userHistoryId() && unit.raw())
                .mapToLong(unit -> unit.lastSequence()).max().orElse(0);
        if (through > 0) {
            Objects.requireNonNull(decisionEvidenceService.prepare(request.context(), source, through, deadline), "存证未返回可靠依据");
            materials = unifiedMaterialReader.read(request.appId(), request.userId(), request.userHistoryId(), previous, source, deadline);
            selection = unifiedWindowSelector.select(materials, unifiedCoveragePlanner.uncovered(materials));
        }
        return selection.hasWork() ? unifiedCoveragePlanner.input(materials, selection) : null;
    }

    MemorySummarySnapshot readUnifiedSnapshot(long appId) {
        return readFreshSnapshot(appId, AdmissionDeadline.start(properties.getBlockingTimeout()));
    }

    @Override
    public CompatibleMemorySnapshot readCompatible(UnifiedSummaryRequest request, AdmissionDeadline deadline) {
        while (true) {
            requireReadable(request, deadline);
            MemorySummarySnapshot summary = readFreshSnapshot(request.appId(), deadline);
            requireReadable(request, deadline);
            L0Snapshot source = request.memory().withSourceLock(deadline, Function.identity());
            requireReadable(request, deadline);
            if (!source.hasKnownSource()) throw new IllegalStateException("旧原文窗口的来源和摘要依赖尚未对齐");
            if (summary.summaryRevision() < source.appliedSummaryRevision()) continue;
            return new CompatibleMemorySnapshot(request.appId(), request.userHistoryId(), request.context().turnId(), summary, source);
        }
    }

    private void requireReadable(UnifiedSummaryRequest request, AdmissionDeadline deadline) {
        if (Thread.currentThread().isInterrupted() || deadline.remainingNanos() <= 0 || !unifiedAllowed(request)) {
            throw new java.util.concurrent.CancellationException("相容快照读取的截止或回合已失效");
        }
    }

    private MemorySummarySnapshot readFreshSnapshot(long appId, AdmissionDeadline deadline) {
        if (deadline.remainingNanos() <= 0) throw new java.util.concurrent.CancellationException("摘要读取截止已耗尽");
        if (transactionManager == null) return snapshotOf(selectCurrentSummary(appId));
        long seconds = TimeUnit.NANOSECONDS.toSeconds(deadline.remainingNanos());
        if (seconds <= 0) throw new java.util.concurrent.CancellationException("剩余时间不足以开始数据库读取");
        var definition = new DefaultTransactionDefinition();
        definition.setName("l1-summary-fresh-read");
        definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        definition.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        definition.setReadOnly(true);
        definition.setTimeout((int) Math.min(Integer.MAX_VALUE, seconds));
        MemorySummarySnapshot snapshot = new TransactionTemplate(transactionManager, definition)
                .execute(status -> snapshotOf(selectCurrentSummary(appId)));
        if (deadline.remainingNanos() <= 0) throw new java.util.concurrent.CancellationException("丢弃截止后的摘要读取结果");
        return Objects.requireNonNull(snapshot, "数据库未返回摘要快照");
    }

    private MemorySummarySnapshot snapshotOf(AppMemorySummary current) {
        if (current == null) return MemorySummarySnapshot.empty();
        long revision = current.getSummaryRevision() == null ? 0 : current.getSummaryRevision();
        int version = current.getCoverageVersion() == null ? 1 : current.getCoverageVersion();
        long cursor = current.getLastSummarizedId() == null ? 0 : current.getLastSummarizedId();
        String summary = StrUtil.nullToEmpty(current.getSummary());
        if (version == 1 && revision == 0 && !MemorySummaryContract.isUsablePersistedState(summary, cursor, tokenEstimator)) {
            return MemorySummarySnapshot.empty();
        }
        var snapshot = new MemorySummarySnapshot(summary, cursor, revision, version,
                SummaryCoverageCodec.decode(version, current.getCoverageState()));
        if (!MemorySummaryContract.isUsablePersistedState(snapshot, tokenEstimator)) throw new IllegalStateException("持久摘要不符合完整快照契约");
        return snapshot;
    }

    private boolean unifiedAllowed(UnifiedSummaryRequest request) {
        return request.context().terminalWinner().isEmpty() && hasLifecycleWriterAccess(request.appId())
                && request.context().tryRun(() -> { });
    }

    private long monotonicDeadline(AdmissionDeadline deadline) {
        long now = System.nanoTime();
        long remaining = deadline.remainingNanos();
        return remaining > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + remaining;
    }

    record UnifiedDraft(UnifiedSummaryRequest request, SummaryInput input,
                         MemorySummaryDraftEngine.DraftResult draft, AdmissionDeadline deadline) { }

    /** 模型已经返回后才进入短提交区间：回合、删除栅栏、L0、摘要锁、数据库事务。 */
    MemoryCompressionResult commitUnifiedDraft(UnifiedDraft work) {
        var input = work.input();
        var next = input.committed(work.draft().summary());
        int tokens = tokenEstimator.estimateText(next.summary());
        if (!work.draft().changed() || work.draft().failureStatus() != null
                || !MemorySummaryFormat.isValid(next.summary())
                || tokens != work.draft().summaryTokens() || tokens > MemoryTokenProperties.L1_MAX_SUMMARY_TOKENS) {
            throw new IllegalStateException("统一草稿未通过完整提交门禁");
        }
        if (!unifiedAllowed(work.request())) return result(MemoryCompressionResult.Status.DELETE_REJECTED,
                input.previous().lastSummarizedId(), 0, "原回合或应用已失效");
        return work.request().context().commitSummaryWhileActive(work.deadline(), () -> {
            try (var writer = lifecycleFence.tryAcquireWriter(input.appId())) {
                if (writer == null) return result(MemoryCompressionResult.Status.DELETE_REJECTED,
                        input.previous().lastSummarizedId(), 0, "应用删除流程已接管");
                return work.request().memory().withSourceLock(work.deadline(), currentSource -> {
                    validateUnifiedSource(input, currentSource);
                    long end = monotonicDeadline(work.deadline());
                    try (var consistency = consistencyCoordinator.tryAcquireUntil(input.appId(), end)) {
                        if (consistency == null) return result(MemoryCompressionResult.Status.TIMED_OUT,
                                input.previous().lastSummarizedId(), 0, "等待摘要提交锁截止");
                        deleteCacheStrict(CACHE_KEY_PREFIX + input.appId(), input.appId());
                        PersistenceOutcome outcome = executePersistenceTransaction(end, status -> {
                            decisionEvidenceService.validateSummaryInput(input);
                            AppMemorySummary current = summaryMapper.selectOneByQuery(QueryWrapper.create()
                                    .eq("appId", input.appId()).eq("isDelete", 0).forUpdate());
                            if (!snapshotOf(current).equals(input.previous())) {
                                throw new IllegalStateException("摘要修订已变化，拒绝提交旧草稿");
                            }
                            persistSnapshot(input.appId(), current, next, tokens);
                            if (work.deadline().remainingNanos() <= 0) {
                                status.setRollbackOnly();
                                return PersistenceOutcome.TIMED_OUT;
                            }
                            return PersistenceOutcome.PERSISTED;
                        });
                        if (outcome != PersistenceOutcome.PERSISTED) return result(MemoryCompressionResult.Status.TIMED_OUT,
                                input.previous().lastSummarizedId(), 0, "统一摘要提交截止");
                        clearFallbackRetryDelay(input.appId());
                        metricsCollector.recordSummaryDraftSuccess(tokens, work.draft().reducerRounds());
                        return result(MemoryCompressionResult.Status.COMPRESSED, next.lastSummarizedId(), tokens, "统一摘要已可靠提交");
                    }
                });
            }
        });
    }

    private void validateUnifiedSource(SummaryInput input, L0Snapshot current) {
        long through = input.selected().stream().filter(SummaryUnit::raw)
                .mapToLong(SummaryUnit::lastSequence).max().orElse(0);
        if (!L0SourceOperations.containsPrefix(current, input.source(), through)) {
            throw new IllegalStateException("统一摘要的来源前缀或 epoch 已变化");
        }
        for (long id : input.selected().stream().mapToLong(SummaryUnit::userHistoryId).distinct().toArray()) {
            if (!Objects.equals(input.source().turn(id), current.turn(id))) {
                throw new IllegalStateException("统一摘要所属回合或终态已变化");
            }
        }
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
                .failCount(failureMetadata.failCount())
                .nextRetryTime(nextRetryTime)
                .createTime(current.getCreateTime())
                .updateTime(failureMetadata.createdAt())
                .isDelete(current.getIsDelete())
                .build();
        int affectedRows = summaryMapper.updateFailureMetadata(failed, revisionOf(current));
        requireMetadataUpdate(affectedRows);
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
                .failCount(0)
                .nextRetryTime(null)
                .createTime(current.getCreateTime())
                .updateTime(now)
                .isDelete(current.getIsDelete())
                .build();
        requireMetadataUpdate(summaryMapper.updateFailureMetadata(cleared, revisionOf(current)));
    }

    private void requireMetadataUpdate(int affectedRows) {
        // 0 表示旧修订失效；旧失败回调不能覆盖新快照，且不将其改名为存储失败。
        if (affectedRows < 0 || affectedRows > 1) throw new IllegalStateException("失败元数据更新行数异常");
    }

    private long revisionOf(AppMemorySummary current) {
        return current == null || current.getSummaryRevision() == null ? 0 : current.getSummaryRevision();
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
        if (current != null && current.getCoverageState() != null) {
            throw new IllegalStateException("完整历史兼容入口不能覆盖部分回合来源，请使用统一入口");
        }
        persistSnapshot(appId, current, new MemorySummarySnapshot(summary, summarizedThroughId,
                Math.addExact(revisionOf(current), 1), SummaryCoverageCodec.CURRENT_VERSION, null), summaryTokens);
    }

    private void persistSnapshot(long appId, AppMemorySummary current, MemorySummarySnapshot next, int tokens) {
        if (next.summaryRevision() != Math.addExact(revisionOf(current), 1)
                || next.lastSummarizedId() < currentCursor(current)) {
            throw new IllegalStateException("摘要提交必须推进修订且保留已覆盖历史");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        AppMemorySummary row = AppMemorySummary.builder()
                .id(current == null ? null : current.getId()).appId(appId).summary(next.summary())
                .summaryTokens(tokens).lastSummarizedId(next.lastSummarizedId())
                .summaryRevision(next.summaryRevision()).coverageVersion(next.coverageVersion())
                .coverageState(SummaryCoverageCodec.encode(next.coverageState()))
                .failCount(0).nextRetryTime(null).createTime(current == null ? now : current.getCreateTime())
                .updateTime(now).isDelete(current == null ? 0 : current.getIsDelete()).build();
        if (current == null) {
            try {
                requireExactlyOneRow(summaryMapper.insert(row), "新增完整摘要快照");
            } catch (org.springframework.dao.DuplicateKeyException exception) {
                // 首次插入冲突只重读事实，当前草稿不能盲目覆盖赢家。
                selectCurrentSummary(appId);
                throw new IllegalStateException("摘要首次插入发生竞争，保留已提交快照", exception);
            }
        } else {
            requireExactlyOneRow(summaryMapper.commitSnapshot(row, revisionOf(current)), "按修订提交完整摘要快照");
        }
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
        String cacheKey = SNAPSHOT_CACHE_KEY_PREFIX + appId;
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                try {
                    MemorySummarySnapshot snapshot = MemorySummarySnapshotCodec.decode(appId, cached);
                    if (MemorySummaryContract.isUsablePersistedState(snapshot, tokenEstimator)) return snapshot.summary();
                } catch (IllegalArgumentException ignored) {
                    // 缺失覆盖字段、错应用或纯正文均不能作为可召回的新快照。
                }
                deleteInvalidCache(cacheKey, appId);
            }
        } catch (RuntimeException exception) {
            log.warn("读取摘要缓存失败 appId={} type={}", appId,
                    exception.getClass().getSimpleName());
        }
        MemorySummarySnapshot snapshot;
        try {
            snapshot = readFreshSnapshot(appId, AdmissionDeadline.start(properties.getBlockingTimeout()));
        } catch (RuntimeException exception) {
            log.warn("读取摘要失败 appId={} type={}", appId,
                    exception.getClass().getSimpleName());
            return "";
        }
        writeCache(appId, snapshot);
        return snapshot.summary();
    }

    @Override
    public MemorySummarySnapshot readSnapshot(Long appId) {
        requirePositiveId(appId, "应用 ID");
        AppDataLifecycleFence.WriterPermit writerPermit =
                lifecycleFence.tryAcquireWriter(appId);
        if (writerPermit == null) {
            throw new IllegalStateException("应用删除流程已接管，无法读取摘要");
        }
        try (writerPermit;
             AppMemorySummaryConsistencyCoordinator.Permit ignored =
                     consistencyCoordinator.acquire(appId)) {
            return readFreshSnapshot(appId, AdmissionDeadline.start(properties.getBlockingTimeout()));
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
        try {
            return readSnapshot(appId).lastSummarizedId();
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "读取 L1 摘要游标失败，appId=" + appId, exception);
        }
    }

    private void writeCache(long appId, MemorySummarySnapshot snapshot) {
        String cacheKey = SNAPSHOT_CACHE_KEY_PREFIX + appId;
        try {
            redisTemplate.opsForValue().set(cacheKey, MemorySummarySnapshotCodec.encode(appId, snapshot), CACHE_TTL);
        } catch (RuntimeException exception) {
            log.warn("写摘要缓存失败 key={} type={}", cacheKey,
                    exception.getClass().getSimpleName());
        }
    }

    private void deleteCacheStrict(String cacheKey, Long appId) {
        try {
            redisTemplate.delete(cacheKey);
            redisTemplate.delete(SNAPSHOT_CACHE_KEY_PREFIX + appId);
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
        RuntimeException failure = null;
        for (String key : java.util.List.of(CACHE_KEY_PREFIX + appId, SNAPSHOT_CACHE_KEY_PREFIX + appId)) {
            try {
                redisTemplate.delete(key);
            } catch (RuntimeException exception) {
                if (failure == null) failure = exception;
                else if (failure != exception) failure.addSuppressed(exception);
            }
        }
        if (failure == null) return MemoryCacheInvalidationResult.success();
        log.warn("清理 L1 摘要缓存失败 appId={} type={}", appId, failure.getClass().getSimpleName());
        return MemoryCacheInvalidationResult.failure("L1_SUMMARY_REDIS", failure);
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
