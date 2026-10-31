package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.ai.plan.*;
import com.lyw.appgeneration.ai.tools.FileToolBudgetGuard;
import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager;
import com.lyw.appgeneration.core.concurrency.*;
import com.lyw.appgeneration.core.handler.VueTurnContext;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.monitor.*;
import com.lyw.appgeneration.service.*;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用真实请求门禁和流式编排，存储及摘要模型为本地替身。 */
class UnifiedContextRequestGateTest {
    @Test
    void 观测保留原文与去重旧结果但不记录正文或新增模型调用() {
        try (var fixture = new Fixture(true)) {
            var source = L0SourceOperations.importClosedHistory(L0Snapshot.empty("7", 0),
                    new ConversationTurn(1, 2, List.of(UserMessage.from("旧私密要求"), AiMessage.from("旧私密结果")), 100),
                    "old", com.lyw.appgeneration.model.enums.ChatMemoryOutcome.FAILED);
            source = L0SourceOperations.append(source, fixture.boundary.userMessage(), fixture.boundary);
            fixture.store.value.set(source);
            fixture.summary.set(new MemorySummarySnapshot(summaryText(), 2, 1, 2, null));
            assertTrue(fixture.admit().canProceed());
            assertEquals(1D, fixture.registry.get("memory_context_retained_messages").summary().totalAmount());
            assertEquals(1D, fixture.registry.get("memory_context_deduplicated_results").summary().totalAmount());
            assertTrue(fixture.registry.find("memory_summary_model_calls_total").counters().isEmpty());
            assertEquals(1D, fixture.registry.get("memory_summary_reuse_total").tag("kind", "committed_snapshot").counter().count());
            assertFalse(fixture.registry.getMeters().toString().contains("私密"));
        }
    }
    @ParameterizedTest
    @CsvSource({"49151,NORMAL,false,false", "49152,ASYNC_SCHEDULED,true,false",
            "57343,ASYNC_SCHEDULED,true,false", "57344,BLOCKING_COMPLETED,false,true",
            "65535,BLOCKING_COMPLETED,false,true", "65536,HARD_LIMIT_REJECTED,false,true"})
    void 完整请求按生产阈值进入异步同步和硬拒绝(int tokens, ContextCompressionMode expected,
            boolean async, boolean blocking) {
        try (var fixture = new Fixture(true)) {
            when(fixture.estimator.estimateRequest(anyList(), anyList())).thenReturn(tokens);
            var result = fixture.admit();
            assertEquals(expected, result.mode(), result.detail());
            assertEquals(tokens, result.finalTokens());
            verify(fixture.summaries, times(async ? 1 : 0)).triggerUnifiedAsync(any(), same(result.deadline()));
            verify(fixture.summaries, times(blocking ? 1 : 0)).compressUnified(any(), same(result.deadline()));
        }
    }

    @Test
    void 原始窗口很大但可靠覆盖后的实际请求较小时不重复摘要() {
        try (var fixture = new Fixture(true)) {
            fixture.l0.add(AiMessage.from("已合并旧诊断".repeat(30_000)));
            fixture.l0.add(AiMessage.from("最新说明"));
            var source = fixture.store.value.get();
            var coverage = new SummaryCoverage(10, "turn", source.memoryEpoch(), 2,
                    L0SourceOperations.fingerprint(source, 10, 2, 0), 0, null, null, false);
            fixture.summary.set(new MemorySummarySnapshot(summaryText(), 0, 1, 2, coverage));
            doReturn(DeadlineAwareReplaceResult.DEPENDENCY_FAILED).when(fixture.summaries).reclaimUnified(any(), any());
            assertTrue(fixture.realEstimator.estimateMessages(source.messages()) > 65_536);
            for (int request = 0; request < 3; request++) {
                var result = fixture.admit();
                assertEquals(ContextCompressionMode.NORMAL, result.mode(), result.detail());
                assertTrue(result.finalTokens() < 49_152);
                assertFalse(result.requestMessages().toString().contains("已合并旧诊断"));
                assertTrue(result.requestMessages().toString().contains("最新说明"));
            }
            assertTrue(source.equals(fixture.store.value.get()), "回收失败仍保留原文及来源元数据");
            verify(fixture.summaries, never()).compressUnified(any(), any());
            verify(fixture.summaries, never()).triggerUnifiedAsync(any(), any());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 最新大批次或固定资料超限时保留必要内容并受控拒绝(boolean oversizedTool) {
        try (var fixture = new Fixture(true)) {
            var before = fixture.store.value.get();
            var transientMessages = fixture.transientMessages;
            String content = "超限诊断".repeat(25_000);
            if (oversizedTool) {
                var call = ToolExecutionRequest.builder().id("large-read").name("readFile").arguments("{}").build();
                fixture.l0.add(AiMessage.from(call));
                fixture.l0.add(ToolExecutionResultMessage.from(call, content));
                before = fixture.store.value.get();
            } else {
                transientMessages = List.of(dev.langchain4j.data.message.SystemMessage.from(content));
            }
            var result = fixture.coordinator.admit(fixture.memory, fixture.tools, transientMessages,
                    ignored -> { }, fixture.context, fixture.boundary);
            assertEquals(ContextCompressionMode.HARD_LIMIT_REJECTED, result.mode());
            assertTrue(result.finalTokens() >= 65_536);
            assertEquals(before, fixture.store.value.get());
            assertTrue(result.requestMessages().contains(fixture.boundary.userMessage()));
            if (oversizedTool) {
                assertEquals(1, result.requestMessages().stream().filter(ToolExecutionResultMessage.class::isInstance).count());
                assertTrue(result.requestMessages().toString().contains(content));
            }
        }
    }

    @Test
    void 真实摘要失败不回退文字检查点也不改变原文() {
        try (var fixture = new Fixture(true)) {
            when(fixture.estimator.estimateRequest(anyList(), anyList())).thenReturn(60_000);
            when(fixture.summaries.compressUnified(any(), any())).thenReturn(new MemoryCompressionResult(
                    MemoryCompressionResult.Status.MODEL_FAILED, 0, 0, "模型失败"));
            var before = fixture.store.value.get();
            var result = fixture.admit();
            assertFalse(result.canProceed());
            assertEquals(ContextAdmissionResult.FailureReason.MODEL_FAILED, result.failureReason());
            assertEquals(before, fixture.store.value.get());
            assertFalse(result.requestMessages().toString().contains("本轮可信执行检查点"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 同步重组或准备失败不消费未送达的构建反馈(boolean failed) {
        try (var fixture = new Fixture(true)) {
            var guard = fixture.context.buildProgressGuard();
            var diagnostic = BuildBlockDiagnostic.single(BuildBlockDiagnostic.Reason.NO_PLAN);
            guard.observe(1, "first", BuildProgressGuard.Observation.rejected("turn", diagnostic, 0));
            guard.observe(1, "second", BuildProgressGuard.Observation.rejected("turn", diagnostic, 0));
            var feedback = guard.pendingFeedback();
            assertNotNull(feedback);
            var messages = new java.util.ArrayList<>(fixture.transientMessages);
            messages.add(feedback.message());
            when(fixture.estimator.estimateRequest(anyList(), anyList())).thenReturn(58_000, 1_500);
            if (failed) doReturn(new MemoryCompressionResult(MemoryCompressionResult.Status.MODEL_FAILED, 0, 0, "测试失败"))
                    .when(fixture.summaries).compressUnified(any(), any());
            var result = fixture.coordinator.admit(fixture.memory, fixture.tools, messages, ignored -> { },
                    fixture.context, fixture.boundary);
            assertEquals(!failed, result.canProceed());
            assertEquals(1, result.requestMessages().stream().filter(feedback.message()::equals).count());
            assertSame(feedback, guard.pendingFeedback());
            assertFalse(guard.correctionDelivered());
            assertFalse(fixture.l0.messages().contains(feedback.message()));
            for (var material : fixture.transientMessages) {
                assertEquals(1, result.requestMessages().stream().filter(material::equals).count());
            }
        }
    }

    @Test
    void 下一用户回合复用原文但不继承上轮临时资料() {
        try (var fixture = new Fixture(true)) {
            fixture.l0.add(AiMessage.from("本轮工具使用后的必要说明"));
            fixture.store.value.updateAndGet(source -> L0SourceOperations.finishTurn(source, 10, "turn", 20,
                    com.lyw.appgeneration.model.enums.ChatMemoryOutcome.ANSWERED, "后端已回答", 0));
            fixture.context.closeResources();
            var operation = new AppOperationLeaseManager().acquire(7, AppOperationLeaseManager.AppOperationType.GENERATE, "next");
            var lease = new VueBuildSessionManager().open(operation, 9, "next");
            var admission = new VueTurnAdmissionController(new VueBuildRepairMetricsCollector(new SimpleMeterRegistry()));
            var next = new VueTurnContext(7, 9, "next", operation, lease, admission.tryAcquire().orElseThrow(), new FileToolBudgetGuard().newSession());
            try {
                next.commitUserAndBindHistory(() -> ChatHistory.builder().id(30L).appId(7L).userId(9L)
                        .messageType("user").message("继续分析").build());
                var boundary = TurnRequestBoundary.of("继续分析", List.of(), 30, "next");
                CanonicalUserMessageScope.call(boundary, () -> { fixture.l0.add(boundary.userMessage()); return null; });
                doAnswer(invocation -> new CompatibleMemorySnapshot(7, 30, "next", MemorySummarySnapshot.empty(), fixture.store.value.get()))
                        .when(fixture.summaries).readCompatible(any(), any());
                var result = fixture.coordinator.admit(fixture.memory, fixture.tools, List.of(), ignored -> { }, next, boundary);
                assertTrue(result.canProceed(), result.detail());
                assertTrue(result.requestMessages().toString().contains("本轮工具使用后的必要说明"));
                for (var material : fixture.transientMessages) assertFalse(result.requestMessages().contains(material));
                assertEquals(0, fixture.planReads.get());
            } finally { next.closeResources(); }
        }
    }

    @Test
    void 同版本计划状态每次重读且完整消息与工具统一估算() {
        try (var fixture = new Fixture(true)) {
            var first = fixture.admit();
            assertTrue(first.canProceed(), first.detail());
            assertTrue(first.requestMessages().toString().contains("状态=PENDING"));
            fixture.plan.set(plan(1, PlanFileState.TOUCHED));
            var next = fixture.admit();
            assertTrue(next.canProceed(), next.detail());
            assertTrue(next.requestMessages().toString().contains("状态=TOUCHED"));
            assertFalse(next.requestMessages().toString().contains("状态=PENDING"));
            assertEquals(2, fixture.planReads.get());
            assertCompleteSnapshot(fixture, next.requestMessages());
            assertEquals(fixture.lastEstimated.get(), next.requestMessages());
            assertEquals(fixture.tools, fixture.lastTools.get());
            assertEquals(fixture.realEstimator.estimateRequest(next.requestMessages(), fixture.tools), next.finalTokens());
            assertThrows(UnsupportedOperationException.class, () -> next.requestMessages().clear());
            verify(fixture.summaries, never()).readSnapshot(anyLong());
            verify(fixture.summaries, never()).getCurrentSummary(anyLong());
        }
    }

    @Test
    void 同步完成后重取相容记忆与计划并重新计量全部资料() {
        try (var fixture = new Fixture(true)) {
            fixture.l0.add(AiMessage.from("待合并的长原文"));
            fixture.l0.add(AiMessage.from("最近保留的说明"));
            when(fixture.estimator.estimateRequest(anyList(), anyList())).thenAnswer(invocation -> {
                List<ChatMessage> messages = invocation.getArgument(0);
                fixture.lastEstimated.set(List.copyOf(messages));
                return messages.toString().contains("待合并的长原文") ? 58_000 : 1_500;
            });
            when(fixture.summaries.compressUnified(any(), any())).thenAnswer(invocation -> {
                var source = fixture.store.value.get();
                var coverage = new SummaryCoverage(10, "turn", source.memoryEpoch(), 2,
                        L0SourceOperations.fingerprint(source, 10, 2, 0), 0, null, null, false);
                fixture.summary.set(new MemorySummarySnapshot(summaryText(), 0, 1, 2, coverage));
                fixture.plan.set(plan(2, PlanFileState.TOUCHED));
                return new MemoryCompressionResult(MemoryCompressionResult.Status.COMPRESSED, 0, 100, "测试摘要");
            });
            var result = fixture.admit();
            assertEquals(ContextCompressionMode.BLOCKING_COMPLETED, result.mode(), result.detail());
            assertEquals(58_000, result.initialTokens());
            assertEquals(1_500, result.finalTokens());
            assertFalse(result.requestMessages().toString().contains("待合并的长原文"));
            assertTrue(result.requestMessages().toString().contains("最近保留的说明"));
            assertTrue(result.requestMessages().toString().contains("当前计划 version=2"));
            assertEquals(2, fixture.planReads.get());
            assertEquals(fixture.lastEstimated.get(), result.requestMessages());
            assertCompleteSnapshot(fixture, result.requestMessages());
            verify(fixture.summaries, times(2)).readCompatible(any(), same(result.deadline()));
            verify(fixture.summaries).compressUnified(any(), same(result.deadline()));
        }
    }

    @Test
    void 已捕获的完整旧副本不受之后后台普通回收影响() {
        try (var fixture = new Fixture(true)) {
            fixture.l0.add(AiMessage.from("已经捕获的原始日志"));
            var old = fixture.store.value.get();
            when(fixture.summaries.readCompatible(any(), any())).thenAnswer(invocation -> {
                var pair = new CompatibleMemorySnapshot(7, 10, "turn", MemorySummarySnapshot.empty(), old);
                var coverage = new SummaryCoverage(10, "turn", old.memoryEpoch(), 2,
                        L0SourceOperations.fingerprint(old, 10, 2, 0), 0, null, null, false);
                var latest = new MemorySummarySnapshot(summaryText(), 0, 1, 2, coverage);
                fixture.store.value.set(L0SourceOperations.prune(old, latest, 10));
                return pair;
            });
            var result = fixture.admit();
            assertTrue(result.canProceed(), result.detail());
            assertTrue(result.requestMessages().toString().contains("已经捕获的原始日志"));
            assertFalse(fixture.store.value.get().messages().toString().contains("已经捕获的原始日志"));
        }
    }

    @Test
    void 计划不存在与读取失败明确区分() {
        try (var fixture = new Fixture(true)) {
            fixture.plan.set(null);
            var absent = fixture.admit();
            assertTrue(absent.canProceed());
            assertFalse(absent.requestMessages().contains(AiMessage.from(SyntheticMemoryMessageProtocol.PLAN_SNAPSHOT_ACK)));
            fixture.boundary = fixture.boundary.withPlanReader(() -> { throw new IllegalStateException("计划读取故障"); });
            var failed = fixture.admit();
            assertFalse(failed.canProceed());
            assertEquals(ContextAdmissionResult.FailureReason.PLAN_READ_FAILED, failed.failureReason());
        }
    }

    @Test
    void 旧摘要与已回收新窗口组合失败时不估算或发送() {
        try (var fixture = new Fixture(true)) {
            var old = fixture.store.value.get();
            when(fixture.summaries.readCompatible(any(), any())).thenAnswer(invocation -> {
                var source = new L0Snapshot(old.formatVersion(), old.memoryId(), old.memoryEpoch(), old.nextSequence(),
                        2L, old.entries(), old.turns());
                return new CompatibleMemorySnapshot(7, 10, "turn", MemorySummarySnapshot.empty(), source);
            });
            var result = fixture.admit();
            assertFalse(result.canProceed());
            verify(fixture.estimator, never()).estimateRequest(anyList(), anyList());
            assertEquals(0, fixture.planReads.get());
        }
    }

    @Test
    void 计划读取跨过原截止后丢弃结果且不重置六十秒() {
        try (var fixture = new Fixture(true)) {
            var ticker = new AtomicLong();
            fixture.boundary = fixture.boundary.withPlanReader(() -> {
                ticker.set(Duration.ofSeconds(60).toNanos());
                return Optional.of(fixture.plan.get());
            });
            var coordinator = new ContextCompressionCoordinator(fixture.estimator, mock(ChatHistoryService.class),
                    fixture.summaries, fixture.properties, fixture.compression, fixture.reads,
                    fixture.lifecycle, fixture.metrics, ticker::get);
            var result = coordinator.admit(fixture.memory, fixture.tools, fixture.transientMessages, ignored -> { },
                    fixture.context, fixture.boundary);
            assertFalse(result.canProceed());
            assertEquals(ContextAdmissionResult.FailureReason.TIMED_OUT, result.failureReason());
            assertEquals(0, result.deadline().remainingNanos());
        }
    }

    @Test
    void 等待计划时取消不被读取阻塞且迟到结果不能放行() throws Exception {
        try (var fixture = new Fixture(true); var caller = Executors.newSingleThreadExecutor()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            fixture.boundary = fixture.boundary.withPlanReader(() -> {
                entered.countDown();
                try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { throw new CancellationException(); }
                return Optional.of(fixture.plan.get());
            });
            var pending = caller.submit(fixture::admit);
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertTrue(fixture.context.tryStartFinalization(VueTurnContext.TerminalTrigger.CANCELLED));
            } finally { release.countDown(); }
            var result = pending.get(3, TimeUnit.SECONDS);
            assertFalse(result.canProceed());
            assertEquals(ContextAdmissionResult.FailureReason.TURN_TERMINATED, result.failureReason());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"工具续调", "协议恢复", "未完成工具链恢复"})
    void 初始续调和两类恢复都发送最新计划及同一份已计量资料(String path) throws Exception {
        try (var fixture = new Fixture(false)) {
            var model = new RecordingModel();
            var service = AiServices.builder(TestService.class).streamingChatModel(model)
                    .chatMemoryProvider(ignored -> fixture.memory).tools(new ReadTools()).build();
            var errors = new LinkedBlockingQueue<Throwable>();
            var stream = CanonicalUserMessageScope.call(fixture.boundary, () -> service.chat(7L, fixture.rawUser));
            stream.turnRequestBoundary(fixture.boundary).requestBoundaryRequired(true)
                    .turnTransientMessages(fixture.transientMessages).modelRequestGate(fixture.gate, fixture.context)
                    .onPartialResponse(ignored -> { }).onError(errors::add);
            if (path.equals("协议恢复")) {
                stream.toolProtocolRecoveryPolicy(new ToolProtocolRecoveryPolicy(Set.of("readFile"), ignored -> { }));
            } else if (path.equals("未完成工具链恢复")) {
                stream.incompleteToolChainRecoveryPolicy(new IncompleteToolChainRecoveryPolicy(
                        () -> IncompleteToolChainRecoveryPolicy.BuildState.GENERATING, ignored -> { }));
            }
            try {
                stream.start();
                var initial = model.calls.poll(3, TimeUnit.SECONDS);
                assertNotNull(initial, errors.toString());
                assertTrue(initial.request.messages().toString().contains("当前计划 version=1"));
                assertCompleteSnapshot(fixture, initial.request.messages());
                assertEquals(fixture.lastEstimated.get(), initial.request.messages());
                fixture.plan.set(plan(2, PlanFileState.TOUCHED));
                if (path.equals("工具续调")) {
                    var call = ToolExecutionRequest.builder().id("read-1").name("readFile").arguments("{}").build();
                    initial.handler.onCompleteResponse(response(AiMessage.from(call)));
                } else if (path.equals("协议恢复")) {
                    initial.handler.onPartialResponse("[工具调用]\nreadFile\n{}\n[工具调用]\nreadFile\n{}");
                } else {
                    initial.handler.onCompleteResponse(response(AiMessage.from("先说明下一步")));
                }
                var next = model.calls.poll(3, TimeUnit.SECONDS);
                assertNotNull(next, errors.toString());
                assertTrue(next.request.messages().toString().contains("当前计划 version=2"));
                assertFalse(next.request.messages().toString().contains("当前计划 version=1"));
                assertCompleteSnapshot(fixture, next.request.messages());
                assertEquals(fixture.lastEstimated.get(), next.request.messages());
                assertEquals(next.request.toolSpecifications(), fixture.lastTools.get());
                assertEquals(2, fixture.planReads.get());
                assertTrue(errors.isEmpty(), errors.toString());
                assertFalse(fixture.l0.messages().toString().contains("当前计划 version="));
                assertFalse(fixture.l0.messages().toString().contains("临时模板资料"));
            } finally {
                stream.cancel();
            }
        }
    }

    private static void assertCompleteSnapshot(Fixture fixture, List<ChatMessage> messages) {
        assertEquals(1, messages.stream().filter(fixture.boundary.userMessage()::equals).count());
        for (var material : fixture.transientMessages) {
            assertEquals(1, messages.stream().filter(material::equals).count());
        }
        assertEquals(1, messages.stream().filter(AiMessage.from(SyntheticMemoryMessageProtocol.PLAN_SNAPSHOT_ACK)::equals).count());
        assertEquals(1, messages.stream().filter(AiMessage.from(SyntheticMemoryMessageProtocol.L2_PREFERENCE_ACK)::equals).count());
        assertTrue(messages.toString().contains("后端阶段="));
    }

    @Test
    void 软Replan反馈与同版本计划状态一起刷新且修订后下一请求不重复偏差() throws Exception {
        try (var fixture = new Fixture(false)) {
            var model = new RecordingModel();
            var service = AiServices.builder(TestService.class).streamingChatModel(model)
                    .chatMemoryProvider(ignored -> fixture.memory).tools(new ReadTools()).build();
            var errors = new LinkedBlockingQueue<Throwable>();
            var stream = CanonicalUserMessageScope.call(fixture.boundary, () -> service.chat(7L, fixture.rawUser));
            try {
                stream.turnRequestBoundary(fixture.boundary).turnTransientMessages(fixture.transientMessages)
                        .modelRequestGate(fixture.gate, fixture.context).onPartialResponse(ignored -> { })
                        .onError(errors::add).start();
                var initial = model.calls.poll(3, TimeUnit.SECONDS);
                assertNotNull(initial, errors.toString());
                var previous = fixture.plan.get();
                fixture.plan.set(new AppPlan(previous.planId(), previous.lastModifiedTurnId(), previous.activeTurnId(),
                        previous.version(), previous.round(), previous.mode(), previous.summary(), previous.files(),
                        previous.history(), PlanStatus.REPLAN_PENDING));
                fixture.context.replanContext().observe(new ReplanContext.PlanDeviation("path-change", "需要修订计划", "新增关联文件"));
                initial.handler.onCompleteResponse(response(AiMessage.from(ToolExecutionRequest.builder()
                        .id("replan-read").name("readFile").arguments("{}").build())));
                var pending = model.calls.poll(3, TimeUnit.SECONDS);
                assertNotNull(pending, errors.toString());
                assertTrue(pending.request.messages().toString().contains("当前计划 version=1"));
                assertTrue(pending.request.messages().toString().contains("状态=REPLAN_PENDING"));
                assertEquals(1, pending.request.messages().stream().filter(message -> message.toString().contains("【内部计划校正】")).count());
                fixture.plan.set(plan(2, PlanFileState.TOUCHED));
                fixture.context.replanContext().acknowledgePlanUpdate();
                pending.handler.onCompleteResponse(response(AiMessage.from(ToolExecutionRequest.builder()
                        .id("after-replan-read").name("readFile").arguments("{}").build())));
                var revised = model.calls.poll(3, TimeUnit.SECONDS);
                assertNotNull(revised, errors.toString());
                assertTrue(revised.request.messages().toString().contains("当前计划 version=2"));
                assertFalse(revised.request.messages().toString().contains("【内部计划校正】"));
                assertFalse(revised.request.messages().toString().contains("状态=REPLAN_PENDING"));
                assertCompleteSnapshot(fixture, revised.request.messages());
                assertFalse(fixture.l0.messages().toString().contains("【内部计划校正】"));
                assertEquals(3, fixture.planReads.get());
                assertTrue(errors.isEmpty(), errors.toString());
            } finally { stream.cancel(); }
        }
    }

    static AppPlan plan(int version, PlanFileState state) {
        return new AppPlan("plan", "turn", "turn", version, 1, PlanMode.CHANGESET, "保留已有中文页面",
                List.of(new PlanFile("src/App.vue", "调整页面", PlanFileAction.MODIFY, List.of("src/main.ts"), state)),
                List.of(), state == PlanFileState.TOUCHED ? PlanStatus.READY_TO_BUILD : PlanStatus.PLANNED);
    }

    static String summaryText() {
        return "# 应用目标与定位\n页面\n# 用户偏好与硬约束\n保留中文\n# 已否决的方案\n无\n"
                + "# 关键设计决策与理由\n沿用原方案\n# 当前进度速览\n已整理早期说明";
    }

    static ChatResponse response(AiMessage ai) {
        return ChatResponse.builder().aiMessage(ai).metadata(ChatResponseMetadata.builder().tokenUsage(new TokenUsage(1, 1, 2)).build()).build();
    }

    interface TestService {
        @dev.langchain4j.service.UserMessage("{{request}}")
        TokenStream chat(@MemoryId long appId, @V("request") String request);
    }

    static final class ReadTools {
        @Tool("读取测试文件") public String readFile() { return "原始工具结果"; }
    }

    static final class RecordingModel implements StreamingChatModel {
        final LinkedBlockingQueue<Call> calls = new LinkedBlockingQueue<>();
        @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) { calls.add(new Call(request, handler)); }
        record Call(ChatRequest request, StreamingChatResponseHandler handler) { }
    }

    static final class Fixture implements AutoCloseable {
        final String rawUser = "保留中文原话";
        final List<ChatMessage> transientMessages = List.of(
                dev.langchain4j.data.message.SystemMessage.from("临时模板资料：RAG 页面骨架"),
                dev.langchain4j.data.message.SystemMessage.from("图片资料 https://example.test/image.png 路径 public/images/原图.png"),
                dev.langchain4j.data.message.SystemMessage.from("Skill 目录：vue-frontend-design"));
        final List<ToolSpecification> tools = List.of(ToolSpecification.builder().name("readFile").description("读取文件").build());
        final AtomicReference<AppPlan> plan = new AtomicReference<>(plan(1, PlanFileState.PENDING));
        final AtomicInteger planReads = new AtomicInteger();
        final AtomicReference<MemorySummarySnapshot> summary = new AtomicReference<>(MemorySummarySnapshot.empty());
        final MemoryTokenProperties properties = new MemoryTokenProperties();
        final ChatTokenEstimator realEstimator = new ConservativeChatTokenEstimator(properties);
        final ChatTokenEstimator estimator = mock(ChatTokenEstimator.class);
        final AtomicReference<List<ChatMessage>> lastEstimated = new AtomicReference<>();
        final AtomicReference<List<ToolSpecification>> lastTools = new AtomicReference<>();
        final MemorySummaryService summaries = mock(MemorySummaryService.class);
        final SourceMemoryTestStore store = new SourceMemoryTestStore(L0Snapshot.empty("7", 0));
        final AtomicChatMemoryStore atomic = new AtomicChatMemoryStore(store);
        final TokenAwareChatMemory l0 = new TokenAwareChatMemory(MessageWindowChatMemory.builder().id(7L)
                .chatMemoryStore(atomic).maxMessages(Integer.MAX_VALUE).build(), atomic);
        final ExecutorService reads = Executors.newFixedThreadPool(2);
        final ExecutorService compression = Executors.newSingleThreadExecutor();
        final ExecutorService gateExecutor = Executors.newSingleThreadExecutor();
        final AppDataLifecycleFence lifecycle = new AppDataLifecycleFence();
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final MemoryCompressionMetricsCollector metrics = new MemoryCompressionMetricsCollector(registry);
        final VueTurnContext context;
        final CompressionAwareChatMemory memory;
        final ContextCompressionCoordinator coordinator;
        final ContextCompressionModelRequestGate gate;
        TurnRequestBoundary boundary;

        Fixture(boolean appendUser) {
            var operation = new AppOperationLeaseManager().acquire(7, AppOperationLeaseManager.AppOperationType.GENERATE, "turn");
            var lease = new VueBuildSessionManager().open(operation, 9, "turn");
            var admission = new VueTurnAdmissionController(new VueBuildRepairMetricsCollector(new SimpleMeterRegistry()));
            context = new VueTurnContext(7, 9, "turn", operation, lease, admission.tryAcquire().orElseThrow(), new FileToolBudgetGuard().newSession());
            context.commitUserAndBindHistory(() -> ChatHistory.builder().id(10L).appId(7L).userId(9L).messageType("user").message(rawUser).build());
            boundary = TurnRequestBoundary.of(rawUser, transientMessages, 10, "turn")
                    .withPlanReader(() -> { planReads.incrementAndGet(); return Optional.ofNullable(plan.get()); });
            var prefs = mock(UserMemoryService.class);
            when(prefs.recallByApp(7L)).thenReturn("偏好：简体中文");
            memory = new CompressionAwareChatMemory(l0, summaries, prefs, new UserPreferenceMessageFragmentBuilder(realEstimator, properties), true);
            l0.add(dev.langchain4j.data.message.SystemMessage.from("系统约束"));
            if (appendUser) CanonicalUserMessageScope.call(boundary, () -> { memory.add(boundary.userMessage()); return null; });
            when(estimator.estimateRequest(anyList(), anyList())).thenAnswer(invocation -> {
                List<ChatMessage> messages = invocation.getArgument(0);
                List<ToolSpecification> specifications = invocation.getArgument(1);
                lastEstimated.set(List.copyOf(messages)); lastTools.set(List.copyOf(specifications));
                return realEstimator.estimateRequest(messages, specifications);
            });
            when(estimator.estimateMessages(anyList())).thenAnswer(invocation -> realEstimator.estimateMessages(invocation.getArgument(0)));
            when(summaries.readCompatible(any(), any())).thenAnswer(invocation ->
                    new CompatibleMemorySnapshot(7, 10, "turn", summary.get(), store.value.get()));
            when(summaries.reclaimUnified(any(), any())).thenAnswer(invocation -> {
                store.value.updateAndGet(source -> L0SourceOperations.prune(source, summary.get(), 10));
                return DeadlineAwareReplaceResult.REPLACED;
            });
            when(summaries.compressUnified(any(), any())).thenReturn(new MemoryCompressionResult(
                    MemoryCompressionResult.Status.NOTHING_TO_COMPRESS, 0, 0, "没有新增来源"));
            coordinator = new ContextCompressionCoordinator(estimator, mock(ChatHistoryService.class), summaries, properties,
                    compression, reads, lifecycle, metrics);
            gate = new ContextCompressionModelRequestGate(coordinator, gateExecutor);
        }

        ContextAdmissionResult admit() { return coordinator.admit(memory, tools, transientMessages, ignored -> { }, context, boundary); }
        @Override public void close() {
            context.closeResources();
            gateExecutor.shutdownNow(); reads.shutdownNow(); compression.shutdownNow();
        }
    }
}
