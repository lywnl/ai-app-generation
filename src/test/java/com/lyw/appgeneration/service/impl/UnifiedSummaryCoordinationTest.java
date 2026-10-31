package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.memory.*;
import com.lyw.appgeneration.ai.tools.FileToolBudgetGuard;
import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager;
import com.lyw.appgeneration.core.concurrency.*;
import com.lyw.appgeneration.core.handler.VueTurnContext;
import com.lyw.appgeneration.mapper.AppMemorySummaryMapper;
import com.lyw.appgeneration.model.entity.AppMemorySummary;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.monitor.*;
import com.lyw.appgeneration.service.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UnifiedSummaryCoordinationTest {
    static final String SUMMARY = """
            # 应用目标与定位
            页面
            # 用户偏好与硬约束
            保留中文
            # 已否决的方案
            无
            # 关键设计决策与理由
            原始说明
            # 当前进度速览
            继续处理
            """;

    @Test
    void 同一应用复用异步任务同步加入后只处理剩余材料() throws Exception {
        try (var fixture = new Fixture(Executors.newSingleThreadExecutor()); var waiter = Executors.newSingleThreadExecutor()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            when(fixture.model.chat(anyString())).thenAnswer(invocation -> { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); return SUMMARY; });
            var deadline = AdmissionDeadline.start(Duration.ofSeconds(5));
            assertTrue(fixture.service.triggerUnified(fixture.request, deadline, fixture::commit));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertFalse(fixture.service.triggerUnified(fixture.request, deadline, fixture::commit));
            var joined = waiter.submit(() -> fixture.service.compressUnified(fixture.request, deadline, fixture::commit));
            release.countDown();
            assertEquals(MemoryCompressionResult.Status.NOTHING_TO_COMPRESS, joined.get(4, TimeUnit.SECONDS).status());
            assertEquals(1, fixture.commits.get());
            verify(fixture.model, times(1)).chat(anyString());
            assertEquals(1L, fixture.summary.get().getSummaryRevision());
            assertEquals(11, fixture.request.memory().messages().size(), "协调和草稿不提前回收原文");
        }
    }

    @Test
    void 等待较早任务后新增原文只进入后一份增量草稿() throws Exception {
        try (var fixture = new Fixture(Executors.newSingleThreadExecutor()); var waiter = Executors.newSingleThreadExecutor()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            when(fixture.model.chat(anyString())).thenAnswer(invocation -> {
                entered.countDown();
                assertTrue(release.await(3, TimeUnit.SECONDS));
                return SUMMARY;
            });
            var selected = new CopyOnWriteArrayList<java.util.Set<String>>();
            java.util.function.Function<MemorySummaryServiceImpl.UnifiedDraft, MemoryCompressionResult> commit = work -> {
                selected.add(work.input().selected().stream().filter(SummaryUnit::raw)
                        .map(SummaryUnit::sourceId).collect(java.util.stream.Collectors.toSet()));
                return fixture.commit(work);
            };
            var deadline = AdmissionDeadline.start(Duration.ofSeconds(5));
            assertTrue(fixture.service.triggerUnified(fixture.request, deadline, commit));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            // 旧完整历史入口与统一入口必须共享同一应用任务，不能另启模型。
            fixture.service.triggerSummarizationAsync(7L, 10L);
            for (int generation = 11; generation <= 20; generation++) {
                int current = generation;
                SourceCommitScope.run(current, fixture.request.context(), () ->
                        fixture.request.memory().add(AiMessage.from("新增说明" + current + "新诊断".repeat(500))));
            }
            var joined = waiter.submit(() -> fixture.service.compressUnified(fixture.request, deadline, commit));
            release.countDown();
            assertEquals(MemoryCompressionResult.Status.COMPRESSED, joined.get(4, TimeUnit.SECONDS).status());
            assertEquals(2, fixture.commits.get());
            assertTrue(java.util.Collections.disjoint(selected.get(0), selected.get(1)),
                    "后续任务只能合并尚未覆盖的原文来源");
            assertEquals(2L, fixture.summary.get().getSummaryRevision());
            assertEquals(21, fixture.request.memory().messages().size());
        }
    }

    @Test
    void 加入已有任务不会重新获得完整截止时间() throws Exception {
        try (var fixture = new Fixture(Executors.newSingleThreadExecutor())) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            when(fixture.model.chat(anyString())).thenAnswer(invocation -> { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); return SUMMARY; });
            assertTrue(fixture.service.triggerUnified(fixture.request, AdmissionDeadline.start(Duration.ofSeconds(5)), fixture::commit));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            long start = System.nanoTime();
            var outcome = fixture.service.compressUnified(fixture.request, AdmissionDeadline.start(Duration.ofMillis(30)), fixture::commit);
            assertEquals(MemoryCompressionResult.Status.TIMED_OUT, outcome.status());
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1));
            release.countDown();
            fixture.worker.submit(() -> { }).get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void 执行器拒绝进入退避且排队后回合失效不启动模型() {
        var rejecting = mock(ExecutorService.class);
        when(rejecting.submit(any(Runnable.class))).thenThrow(new RejectedExecutionException("本地测试拒绝"));
        try (var fixture = new Fixture(rejecting)) {
            assertFalse(fixture.service.triggerUnified(fixture.request, AdmissionDeadline.start(Duration.ofSeconds(5)), fixture::commit));
            assertFalse(fixture.service.triggerUnified(fixture.request, AdmissionDeadline.start(Duration.ofSeconds(5)), fixture::commit));
            verify(rejecting, times(1)).submit(any(Runnable.class));
            verifyNoInteractions(fixture.model);
        }
        var queued = mock(ExecutorService.class);
        var task = new AtomicReference<Runnable>();
        when(queued.submit(any(Runnable.class))).thenAnswer(invocation -> { task.set(invocation.getArgument(0)); return CompletableFuture.completedFuture(null); });
        try (var fixture = new Fixture(queued)) {
            assertTrue(fixture.service.triggerUnified(fixture.request, AdmissionDeadline.start(Duration.ofSeconds(5)), fixture::commit));
            fixture.request.context().tryStartCancellation(VueTurnContext.TerminalTrigger.CANCELLED);
            task.get().run();
            verifyNoInteractions(fixture.model);
            assertEquals(0, fixture.commits.get());
        }
    }

    static final class Fixture implements AutoCloseable {
        final ChatModel model = mock(ChatModel.class);
        final ExecutorService worker;
        final ExecutorService modelExecutor = Executors.newSingleThreadExecutor();
        final AtomicReference<AppMemorySummary> summary = new AtomicReference<>();
        final AtomicInteger commits = new AtomicInteger();
        final AppMemorySummaryMapper mapper = mock(AppMemorySummaryMapper.class);
        final AppDataLifecycleFence lifecycle = new AppDataLifecycleFence();
        final ConversationDecisionEvidenceService evidence = mock(ConversationDecisionEvidenceService.class);
        final SourceMemoryTestStore store = new SourceMemoryTestStore(L0Snapshot.empty("7", 0));
        final ChatTokenEstimator estimator = new ConservativeChatTokenEstimator(new MemoryTokenProperties());
        final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        final MemorySummaryServiceImpl service;
        final UnifiedSummaryRequest request;

        Fixture(ExecutorService worker) {
            this(worker, org.springframework.transaction.support.TransactionOperations.withoutTransaction());
        }

        Fixture(ExecutorService worker, org.springframework.transaction.support.TransactionOperations transactions) {
            this.worker = worker;
            var properties = new MemoryTokenProperties();
            var histories = mock(ChatHistoryService.class);
            var row = ChatHistory.builder().id(10L).appId(7L).userId(9L).message("保留中文约束").messageType("user").isDelete(0).build();
            when(histories.listMessagesAfterCursor(7L, 0L, 100)).thenAnswer(ignored -> List.of(row));
            when(mapper.selectOneByQuery(any())).thenAnswer(ignored -> summary.get());
            var engine = new MemorySummaryDraftEngine(histories, model, modelExecutor, estimator, properties);
            engine.configureMetrics(new MemoryCompressionMetricsCollector(metrics));
            service = new MemorySummaryServiceImpl(mapper, engine, worker, mock(StringRedisTemplate.class), lifecycle,
                    estimator, properties, new MemoryCompressionMetricsCollector(metrics), transactions, Clock.systemUTC());
            var operation = new AppOperationLeaseManager().acquire(7L, AppOperationLeaseManager.AppOperationType.GENERATE, "turn");
            var lease = new VueBuildSessionManager().open(operation, 9L, "turn");
            var admission = new VueTurnAdmissionController(new VueBuildRepairMetricsCollector(metrics));
            var context = new VueTurnContext(7, 9, "turn", operation, lease, admission.tryAcquire().orElseThrow(), new FileToolBudgetGuard().newSession());
            context.commitUserAndBindHistory(() -> row);
            var atomic = new AtomicChatMemoryStore(store);
            service.configureTerminalStore(atomic);
            var memory = new TokenAwareChatMemory(MessageWindowChatMemory.builder().id(7L).chatMemoryStore(atomic)
                    .maxMessages(Integer.MAX_VALUE).build(), atomic);
            CanonicalUserMessageScope.call(TurnRequestBoundary.of(row.getMessage(), List.of(), 10, "turn"), () -> {
                memory.add(UserMessage.from(row.getMessage())); return null;
            });
            for (int index = 1; index <= 10; index++) {
                int current = index;
                SourceCommitScope.run(index, context, () -> memory.add(AiMessage.from("原始说明" + current + "诊断".repeat(700))));
            }
            request = new UnifiedSummaryRequest(memory, context);
            when(evidence.prepare(eq(context), any(L0Snapshot.class), anyLong(), any(AdmissionDeadline.class))).thenAnswer(invocation -> {
                L0Snapshot source = invocation.getArgument(1);
                long through = invocation.getArgument(2);
                var old = DecisionEvidenceCodec.decode(row.getDecisionEvidence(), 10, "turn");
                var observed = context.decisionSources().snapshot();
                var additions = new DecisionEvidenceAssembler().collect(10, source, through, observed, old);
                long generation = observed.sources().values().stream().filter(ref -> ref.sequence() <= through)
                        .mapToLong(ref -> ref.generation()).max().orElse(0);
                var next = old.merge(additions, source.memoryEpoch(), through, generation, 384000);
                row.setDecisionEvidence(DecisionEvidenceCodec.encode(next));
                return new PreparedDecisionEvidence(7, 9, 10, "turn", source, through, next,
                        L0SourceOperations.fingerprint(source, 10, through, next.revision()));
            });
            service.configureUnifiedInputs(new SummaryMaterialReader(histories), new SummaryWindowSelector(estimator, properties),
                    new SummaryCoveragePlanner(), evidence);
        }

        MemoryCompressionResult commit(MemorySummaryServiceImpl.UnifiedDraft work) {
            assertTrue(work.deadline().remainingNanos() > 0);
            var snapshot = work.input().committed(work.draft().summary());
            summary.set(AppMemorySummary.builder().id(1L).appId(7L).summary(snapshot.summary())
                    .lastSummarizedId(snapshot.lastSummarizedId()).summaryRevision(snapshot.summaryRevision())
                    .coverageVersion(snapshot.coverageVersion()).coverageState(SummaryCoverageCodec.encode(snapshot.coverageState()))
                    .summaryTokens(work.draft().summaryTokens()).isDelete(0).build());
            commits.incrementAndGet();
            return new MemoryCompressionResult(MemoryCompressionResult.Status.COMPRESSED, snapshot.lastSummarizedId(), work.draft().summaryTokens(), "测试提交替身");
        }

        MemorySummaryServiceImpl.UnifiedDraft preparedDraft() {
            var deadline = AdmissionDeadline.start(Duration.ofSeconds(5));
            var input = service.prepareUnifiedInput(request, deadline);
            assertNotNull(input);
            return new MemorySummaryServiceImpl.UnifiedDraft(request, input,
                    new MemorySummaryDraftEngine.DraftResult(SUMMARY, input.targetHistoryCursor(),
                            estimator.estimateText(SUMMARY), 0, true, null, "测试草稿"), deadline);
        }

        void useSnapshotPersistence() {
            when(mapper.insert(any(AppMemorySummary.class))).thenAnswer(invocation -> {
                if (summary.get() != null) throw new org.springframework.dao.DuplicateKeyException("测试插入冲突");
                AppMemorySummary row = invocation.getArgument(0);
                row.setId(1L);
                summary.set(row);
                return 1;
            });
            when(mapper.commitSnapshot(any(), anyLong())).thenAnswer(invocation -> {
                AppMemorySummary current = summary.get();
                long expected = invocation.getArgument(1);
                if (current == null || !java.util.Objects.equals(current.getSummaryRevision(), expected)) return 0;
                summary.set(invocation.getArgument(0));
                return 1;
            });
        }
        @Override public void close() {
            request.context().closeResources();
            worker.shutdownNow(); modelExecutor.shutdownNow();
        }
    }
}
