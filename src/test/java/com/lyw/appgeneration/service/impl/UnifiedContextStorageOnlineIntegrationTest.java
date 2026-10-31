package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.AiGeneratorServiceFactory;
import com.lyw.appgeneration.ai.memory.*;
import com.lyw.appgeneration.ai.tools.FileToolBudgetGuard;
import com.lyw.appgeneration.ai.tools.FileToolResult;
import com.lyw.appgeneration.core.builder.*;
import com.lyw.appgeneration.core.concurrency.*;
import com.lyw.appgeneration.core.handler.*;
import com.lyw.appgeneration.monitor.*;
import com.lyw.appgeneration.service.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.*;
import dev.langchain4j.service.tool.ToolExecution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 MySQL／Redis 经收尾、下一回合 TokenStream 和实际请求门禁；主模型仅捕获请求。 */
@EnabledIfEnvironmentVariable(named = "UNIFIED_MEMORY_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/(\\?.*)?")
@EnabledIfEnvironmentVariable(named = "UNIFIED_MEMORY_REDIS_PORT", matches = "[0-9]+")
class UnifiedContextStorageOnlineIntegrationTest extends UnifiedMemoryStorageFixture {
    private static final String ERROR_LOG = "组件 ProfileCard.vue 的 props.user 为空，原始堆栈行 37";

    @Test
    void 合法失败收尾后下一回合实际请求保留工具错误和可信终态() throws Exception {
        appendDiagnostic();
        var before = memory.sourceSnapshot();
        var preferences = finishOldTurn();
        var terminal = histories.getLastMessage(appId);
        assertTrue(memory.sourceSnapshot().entries().containsAll(before.entries()));
        assertTrue(memory.sourceSnapshot().turns().getFirst().closed());
        assertEquals(com.lyw.appgeneration.model.enums.ChatMemoryOutcome.FAILED, terminal.getMemoryOutcome());
        verify(preferences).triggerPreferenceExtractionAsync(9L, appId, terminal.getId());
        verifyNoInteractions(model);
        try (var running = beginNext(service, null, null)) {
            var sent = running.awaitRequest();
            assertTrue(sent.messages().stream().anyMatch(message -> message instanceof ToolExecutionResultMessage result
                    && result.text().contains(ERROR_LOG)), "原始错误必须仍在发送给模型的工具结果中");
            assertTrue(sent.messages().contains(running.boundary.userMessage()));
            assertFalse(sent.messages().contains(AiMessage.from(terminal.getMemoryMessage())));
            assertEquals(1, sent.messages().stream().filter(message -> message instanceof dev.langchain4j.data.message.SystemMessage system
                    && system.text().contains(terminal.getMemoryMessage())).count());
            running.assertEstimate(sent);
            assertTrue(memory.sourceSnapshot().messages().toString().contains(ERROR_LOG));
            verifyNoInteractions(model);
        }
    }

    @Test
    void 请求先读旧摘要再遇真实回收时整对重读并发送新估算() throws Exception {
        prepareOldSummaryAndFinish();
        var old = service.readSnapshot(appId);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var once = new AtomicBoolean(true);
        var observed = createService(afterMapperCall((name, value) -> {
            if (name.equals("selectOneByQuery") && once.compareAndSet(true, false)) {
                entered.countDown(); await(release);
            }
        }), transactions);
        try (var running = beginNext(observed, null, null)) {
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTrue(running.requests.isEmpty());
                var next = taggedDraft("中间批次由新摘要承接");
                assertEquals(MemoryCompressionResult.Status.COMPRESSED, service.commitUnifiedDraft(next).status());
                assertEquals(DeadlineAwareReplaceResult.REPLACED, service.reclaimUnified(request, deadline()));
            } finally { release.countDown(); }
            var sent = running.awaitRequest();
            assertTrue(sent.messages().toString().contains("中间批次由新摘要承接"));
            assertEquals(old.summaryRevision() + 1, memory.sourceSnapshot().appliedSummaryRevision());
            assertTrue(registry.get("memory_compatible_read_total").tag("outcome", "retry_new_pair").counter().count() >= 1);
            assertTrue(sent.messages().toString().contains("诊断批次20"));
            running.assertEstimate(sent);
            verifyNoInteractions(model);
        }
    }

    @Test
    void 捕获完整旧组合后普通回收不改变已计量发送副本() throws Exception {
        prepareOldSummaryAndFinish();
        var old = service.readSnapshot(appId);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var running = beginNext(service, () -> {
            entered.countDown(); await(release); return Optional.empty();
        }, null)) {
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var next = taggedDraft("后台更新不会改写旧副本");
                service.commitUnifiedDraft(next);
                assertEquals(DeadlineAwareReplaceResult.REPLACED, service.reclaimUnified(request, deadline()));
            } finally { release.countDown(); }
            var sent = running.awaitRequest();
            assertTrue(sent.messages().toString().contains(old.summary()));
            assertFalse(sent.messages().toString().contains("后台更新不会改写旧副本"));
            assertTrue(sent.messages().toString().contains("诊断批次10"), "新摘要回收的中间来源仍在已冻结旧副本中");
            assertFalse(memory.sourceSnapshot().messages().toString().contains("诊断批次10"));
            running.assertEstimate(sent);
        }
    }

    @Test
    void 新摘要和尚未回收旧原文可以组装且旧结果只去重一次() throws Exception {
        prepareOldSummaryAndFinish();
        // 初始请求先等待应用测试侧提交，再由真正门禁读取新 S 与原 L0。
        var ready = new CountDownLatch(1);
        var observed = spy(service);
        doAnswer(invocation -> {
            await(ready);
            return service.readCompatible(invocation.getArgument(0), invocation.getArgument(1));
        }).when(observed).readCompatible(any(), any());
        try (var running = beginNext(observed, null, null)) {
            try {
                var next = taggedDraft("已提交但未回收的覆盖");
                service.commitUnifiedDraft(next);
                assertTrue(memory.sourceSnapshot().appliedSummaryRevision() < service.readSnapshot(appId).summaryRevision());
            } finally { ready.countDown(); }
            var sent = running.awaitRequest();
            assertTrue(sent.messages().toString().contains("已提交但未回收的覆盖"));
            assertFalse(sent.messages().contains(AiMessage.from("真实构建失败，保留已修改文件")));
            assertTrue(sent.messages().toString().contains("诊断批次20"));
            running.assertEstimate(sent);
            verifyNoInteractions(model);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"取消", "截止", "epoch依赖未知", "epoch已回收"})
    void 存储读取期间失效不沿用旧候选或迟到发送(String kind) throws Exception {
        prepareOldSummaryAndFinish();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var clock = new AtomicLong();
        var once = new AtomicBoolean(true);
        var observed = createService(afterMapperCall((name, value) -> {
            if (name.equals("selectOneByQuery") && once.compareAndSet(true, false)) {
                entered.countDown(); await(release);
            }
        }), transactions);
        try (var running = beginNext(observed, null, kind.equals("截止") ? clock::get : null)) {
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                if (kind.equals("取消")) {
                    context.tryStartCancellation(VueTurnContext.TerminalTrigger.CANCELLED);
                    running.stream.cancel();
                } else if (kind.equals("截止")) {
                    clock.set(Duration.ofSeconds(60).toNanos());
                } else {
                    if (kind.equals("epoch已回收")) {
                        assertEquals(DeadlineAwareReplaceResult.REPLACED, service.reclaimUnified(request, deadline()));
                    }
                    var source = memory.sourceSnapshot();
                    var reset = L0SourceOperations.resetActive(source, source.messages());
                    assertNotEquals(source.memoryEpoch(), reset.memoryEpoch());
                    assertEquals(source.appliedSummaryRevision(), reset.appliedSummaryRevision());
                    assertEquals(DeadlineAwareReplaceResult.REPLACED, memory.replaceSourceSnapshot(source, reset, deadline()));
                }
            } finally { release.countDown(); }
            var decision = running.decided.get(6, TimeUnit.SECONDS);
            if (kind.equals("epoch已回收")) {
                var sent = running.awaitRequest();
                assertTrue(memory.sourceSnapshot().appliedSummaryRevision() > 0);
                assertTrue(sent.messages().toString().contains("诊断批次20原文"));
                assertTrue(sent.messages().toString().contains(ERROR_LOG));
                var summary = service.readSnapshot(appId);
                assertNotEquals(summary.coverageState().memoryEpoch(), memory.sourceSnapshot().memoryEpoch());
                assertEquals(memory.sourceSnapshot(), L0SourceOperations.prune(memory.sourceSnapshot(), summary,
                        running.boundary.userHistoryId()), "新 epoch 不能按旧部分前沿重复裁剪原文");
                running.assertEstimate(sent);
            } else {
                assertNotEquals(ModelRequestGate.Status.ALLOWED, decision.status());
                assertTrue(running.requests.isEmpty());
                if (kind.equals("epoch依赖未知")) {
                    assertEquals(ModelRequestGate.Status.COMPRESSION_FAILED, decision.status());
                    assertEquals(0L, memory.sourceSnapshot().appliedSummaryRevision());
                    assertTrue(memory.sourceSnapshot().messages().toString().contains("诊断批次1原文"));
                    assertTrue(service.readSnapshot(appId).summaryRevision() > 0,
                            "缺少相容性证明时保留已提交摘要和原文，不能伪造读取下界放行");
                }
            }
        }
    }

    private void prepareOldSummaryAndFinish() throws Exception {
        service.commitUnifiedDraft(taggedDraft("较早已覆盖的材料"));
        append(11, 20);
        appendDiagnostic();
        finishOldTurn();
    }

    private UserMemoryService finishOldTurn() {
        var preferences = mock(UserMemoryService.class);
        var finalizer = new VueTurnFinalizer(histories, new ToolMessageCollapser(sourceStore), service, preferences,
                mock(AiGeneratorServiceFactory.class), lifecycle, new VueBuildRepairMetricsCollector(registry), new FileToolBudgetGuard());
        var outcome = new VueTurnOutcome(VueBuildPhase.FAILED, VueTurnOutcome.TurnOutcomeType.FAILED,
                "执行已结束，构建失败", "真实构建失败，保留已修改文件", false, "构建失败");
        assertEquals(outcome, finalizer.finalizeOnce(context, outcome).outcome());
        assertEquals(outcome, finalizer.finalizeOnce(context, outcome).outcome());
        return preferences;
    }

    private void appendDiagnostic() throws Exception {
        var call = ToolExecutionRequest.builder().id("error-read").name("readFile").arguments("{\"relativeFilePath\":\"src/ProfileCard.vue\"}").build();
        // Jackson 支持 record，完整保留文件工具协议字段；通用 Bean 序列化会把该 record 写成空对象。
        String result = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                FileToolResult.readApplied("readFile", "src/ProfileCard.vue", "读取完成", ERROR_LOG));
        assertTrue(result.contains(ERROR_LOG));
        SourceCommitScope.run(21, context, () -> {
            memory.add(AiMessage.from(call));
            memory.add(ToolExecutionResultMessage.from(call, result));
            context.toolExecutionCommitted(21, ToolExecution.builder().request(call).result(result).build());
        });
    }

    private MemorySummaryServiceImpl.UnifiedDraft taggedDraft(String marker) {
        var prepared = prepared();
        String summary = UnifiedSummaryCoordinationTest.SUMMARY + "\n" + marker + "；新增来源="
                + prepared.input().selected().stream().map(SummaryUnit::sourceId).toList();
        return new MemorySummaryServiceImpl.UnifiedDraft(request, prepared.input(),
                new MemorySummaryDraftEngine.DraftResult(summary, prepared.input().targetHistoryCursor(),
                        estimator.estimateText(summary), 0, true, null, "可控摘要内容"), prepared.deadline());
    }

    private Running beginNext(MemorySummaryService selected, TurnRequestBoundary.PlanSnapshotReader reader,
            LongSupplier clock) throws Exception {
        var user = histories.addChatMessageAndReturn(appId, "继续分析上轮错误，保持中文，不要轮播图。", "user", 9L);
        var operation = new AppOperationLeaseManager().acquire(appId, AppOperationLeaseManager.AppOperationType.GENERATE, "next-turn");
        var lease = new VueBuildSessionManager().open(operation, 9L, "next-turn");
        var admission = new VueTurnAdmissionController(new VueBuildRepairMetricsCollector(registry));
        context = new VueTurnContext(appId, 9, "next-turn", operation, lease, admission.tryAcquire().orElseThrow(), new FileToolBudgetGuard().newSession());
        context.commitUserAndBindHistory(() -> user);
        request = new UnifiedSummaryRequest(memory, context);
        var boundary = TurnRequestBoundary.of(user.getMessage(), List.of(), user.getId(), context.turnId());
        if (reader != null) boundary = boundary.withPlanReader(reader);
        var metrics = new MemoryCompressionMetricsCollector(registry);
        ContextCompressionCoordinator coordinator;
        if (clock == null) {
            coordinator = new ContextCompressionCoordinator(estimator, histories, selected, properties, workers,
                    workers, workers, lifecycle, metrics);
        } else {
            var constructor = ContextCompressionCoordinator.class.getDeclaredConstructor(ChatTokenEstimator.class,
                    ChatHistoryService.class, MemorySummaryService.class, com.lyw.appgeneration.config.MemoryTokenProperties.class,
                    ExecutorService.class, AppDataLifecycleFence.class, MemoryCompressionMetricsCollector.class, LongSupplier.class);
            constructor.setAccessible(true);
            coordinator = constructor.newInstance(estimator, histories, selected, properties, workers, lifecycle, metrics, clock);
        }
        var running = new Running(boundary);
        var gate = new ContextCompressionModelRequestGate(coordinator, running.executor) {
            @Override public CompletionStage<Decision> prepare(Request request) {
                return super.prepare(request).thenApply(decision -> { running.decided.complete(decision); return decision; });
            }
        };
        var layered = new CompressionAwareChatMemory(memory, selected, mock(UserMemoryService.class),
                new UserPreferenceMessageFragmentBuilder(estimator, properties), true);
        StreamingChatModel capture = new StreamingChatModel() {
            @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) { running.requests.add(request); }
        };
        var generator = AiServices.builder(OnlineService.class).streamingChatModel(capture).chatMemoryProvider(id -> layered).build();
        running.stream = CanonicalUserMessageScope.call(boundary, () -> generator.chat(appId, user.getMessage()));
        running.stream.turnRequestBoundary(boundary).requestBoundaryRequired(true).modelRequestGate(gate, context)
                .onPartialResponse(ignored -> { }).onError(running.errors::add).start();
        return running;
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS), "等待测试交错释放超时"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new CancellationException(); }
    }

    interface OnlineService {
        @dev.langchain4j.service.UserMessage("{{prompt}}")
        TokenStream chat(@MemoryId long appId, @V("prompt") String prompt);
    }

    private final class Running implements AutoCloseable {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final TurnRequestBoundary boundary;
        final LinkedBlockingQueue<ChatRequest> requests = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
        final CompletableFuture<ModelRequestGate.Decision> decided = new CompletableFuture<>();
        TokenStream stream;
        Running(TurnRequestBoundary boundary) { this.boundary = boundary; }
        ChatRequest awaitRequest() throws Exception {
            var sent = requests.poll(6, TimeUnit.SECONDS);
            assertNotNull(sent, errors.toString());
            return sent;
        }
        void assertEstimate(ChatRequest sent) throws Exception {
            var decision = decided.get(5, TimeUnit.SECONDS);
            assertEquals(decision.messages(), sent.messages());
            assertEquals(estimator.estimateRequest(sent.messages(), List.<ToolSpecification>of()), decision.estimatedInputTokens());
            assertTrue(decision.estimatedInputTokens() < properties.getHardInputLimit());
        }
        @Override public void close() { if (stream != null) stream.cancel(); executor.shutdownNow(); }
    }
}
