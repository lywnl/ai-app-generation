package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.ai.plan.PlanFileState;
import com.lyw.appgeneration.ai.tools.FileToolBudgetGuard;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import com.lyw.appgeneration.core.concurrency.VueTurnAdmissionController;
import com.lyw.appgeneration.core.handler.VueTurnContext;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;
import com.lyw.appgeneration.monitor.VueBuildRepairMetricsCollector;
import com.lyw.appgeneration.service.*;
import dev.langchain4j.data.message.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanContextRequestGateTest {
    @Test
    void 已有完整成功计划时最终请求和预算不再有额外快照() {
        try (var fixture = new UnifiedContextRequestGateTest.Fixture(true)) {
            fixture.plan.set(PlanContextTest.plan(1, PlanFileState.PENDING));
            var call = PlanContextTest.call("create", "makePlan");
            var result = ToolExecutionResultMessage.from(call, PlanContextTest.success("makePlan", fixture.plan.get()));
            fixture.l0.add(AiMessage.from(call));
            fixture.l0.add(result);
            var source = fixture.store.value.get();
            for (int index = 0; index < 3; index++) {
                var admission = fixture.admit();
                assertTrue(admission.canProceed(), admission.detail());
                assertEquals(0, attachmentCount(admission.requestMessages()));
                assertNull(fixture.l0.sourceSnapshot().planRestore());
                assertTrue(admission.requestMessages().contains(result));
                assertEquals(fixture.lastEstimated.get(), admission.requestMessages());
                assertEquals(fixture.realEstimator.estimateRequest(admission.requestMessages(), fixture.tools), admission.finalTokens());
            }
            assertEquals(0, fixture.planReads.get());
            assertEquals(source, fixture.store.value.get());
        }
    }

    @Test
    void 新版完整成功工具结果取代已有恢复附件() {
        try (var fixture = new UnifiedContextRequestGateTest.Fixture(true)) {
            assertEquals(1, attachmentCount(fixture.admit().requestMessages()));
            fixture.plan.set(PlanContextTest.plan(2, PlanFileState.PENDING));
            var call = PlanContextTest.call("update", "updatePlan");
            var result = ToolExecutionResultMessage.from(call, PlanContextTest.success("updatePlan", fixture.plan.get()));
            fixture.l0.add(AiMessage.from(call));
            fixture.l0.add(result);
            var revised = fixture.admit();
            assertTrue(revised.canProceed());
            assertEquals(0, attachmentCount(revised.requestMessages()));
            assertNull(fixture.l0.sourceSnapshot().planRestore());
            assertTrue(revised.requestMessages().contains(result));
            assertFalse(revised.requestMessages().toString().contains("恢复时的计划快照"));
        }
    }

    @Test
    void 异步已提交覆盖在下一请求恢复且连续覆盖只保留一份附件() {
        try (var fixture = new UnifiedContextRequestGateTest.Fixture(true)) {
            var call = PlanContextTest.call("create", "makePlan");
            fixture.l0.add(AiMessage.from(call));
            fixture.l0.add(ToolExecutionResultMessage.from(call, PlanContextTest.success("makePlan", fixture.plan.get())));
            fixture.l0.add(AiMessage.from("第一条近期说明"));
            fixture.l0.add(AiMessage.from("第二条近期说明"));
            when(fixture.estimator.estimateRequest(anyList(), anyList())).thenReturn(50_000, 1_500, 1_500, 1_500);
            doAnswer(invocation -> { cover(fixture, 3, 1); return null; })
                    .when(fixture.summaries).triggerUnifiedAsync(any(), any());
            var initial = fixture.admit();
            assertEquals(ContextCompressionMode.ASYNC_SCHEDULED, initial.mode());
            assertEquals(0, attachmentCount(initial.requestMessages()));
            assertTrue(initial.requestMessages().stream().anyMatch(ToolExecutionResultMessage.class::isInstance));
            fixture.plan.set(PlanContextTest.plan(1, PlanFileState.TOUCHED));
            var next = fixture.admit();
            assertEquals(1, attachmentCount(next.requestMessages()));
            assertFalse(next.requestMessages().stream().anyMatch(ToolExecutionResultMessage.class::isInstance));
            var recovered = fixture.l0.sourceSnapshot().planRestore();
            assertEquals(1, attachmentCount(fixture.admit().requestMessages()));
            assertSame(recovered, fixture.l0.sourceSnapshot().planRestore());
            cover(fixture, 4, 2);
            var third = fixture.admit();
            assertTrue(third.canProceed(), third.detail());
            assertEquals(1, attachmentCount(third.requestMessages()));
            assertNotSame(recovered, fixture.l0.sourceSnapshot().planRestore());
            assertFalse(fixture.l0.messages().toString().contains(SyntheticMemoryMessageProtocol.PLAN_SNAPSHOT_ACK));
        }
    }

    @Test
    void 压缩失败不重建附件也不改变原文或摘要新增来源() {
        try (var fixture = new UnifiedContextRequestGateTest.Fixture(true)) {
            fixture.admit();
            var restored = fixture.l0.sourceSnapshot().planRestore();
            fixture.l0.add(AiMessage.from("未压缩的工具说明"));
            var original = fixture.store.value.get();
            when(fixture.estimator.estimateRequest(anyList(), anyList())).thenReturn(60_000);
            when(fixture.summaries.compressUnified(any(), any())).thenAnswer(invocation -> {
                UnifiedSummaryRequest work = invocation.getArgument(0);
                assertEquals(original, work.memory().sourceSnapshot());
                assertFalse(work.memory().messages().contains(restored.messages().getFirst()));
                return new MemoryCompressionResult(MemoryCompressionResult.Status.MODEL_FAILED, 0, 0, "本地失败替身");
            });
            var failed = fixture.admit();
            assertFalse(failed.canProceed());
            assertEquals(ContextAdmissionResult.FailureReason.MODEL_FAILED, failed.failureReason());
            assertSame(restored, fixture.l0.sourceSnapshot().planRestore());
            assertEquals(original, fixture.store.value.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"附件失效", "原文清空被拒绝"})
    void 读取期间缓存代次失效后迟到候选不能复活(String kind) throws Exception {
        try (var fixture = new UnifiedContextRequestGateTest.Fixture(true); var callers = Executors.newSingleThreadExecutor()) {
            fixture.admit();
            var original = fixture.store.value.get();
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            fixture.boundary = fixture.boundary.withPlanReader(() -> {
                entered.countDown();
                await(release);
                return Optional.of(fixture.plan.get());
            });
            var pending = callers.submit(fixture::admit);
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                if (kind.equals("附件失效")) fixture.memory.invalidatePlanContext();
                else assertThrows(IllegalStateException.class, fixture.memory::clear);
            } finally { release.countDown(); }
            var failed = pending.get(3, TimeUnit.SECONDS);
            assertFalse(failed.canProceed());
            assertEquals(ContextAdmissionResult.FailureReason.ALIGNMENT_FAILED, failed.failureReason());
            assertNull(fixture.memory.capturePlanContext().checked());
            assertEquals(original, fixture.store.value.get(), "检查失效不得绕过 L0 对 clear 的保护");
            var next = fixture.admit();
            assertTrue(next.canProceed(), next.detail());
            assertEquals(1, attachmentCount(next.requestMessages()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"取消", "删除"})
    void 等待计划时终止请求不能提交缓存或继续发送(String kind) throws Exception {
        try (var fixture = new UnifiedContextRequestGateTest.Fixture(true); var callers = Executors.newSingleThreadExecutor()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            fixture.boundary = fixture.boundary.withPlanReader(() -> {
                entered.countDown();
                await(release);
                return Optional.of(fixture.plan.get());
            });
            var pending = callers.submit(fixture::admit);
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                if (kind.equals("取消")) assertTrue(fixture.context.tryStartCancellation(VueTurnContext.TerminalTrigger.CANCELLED));
                else {
                    try (var deletion = fixture.lifecycle.beginDelete(7, Duration.ofMillis(100))) {
                        assertNotNull(deletion, "计划 I/O 期间不能持有阻塞删除的 writer");
                        deletion.commitTombstone();
                    }
                }
            } finally { release.countDown(); }
            var failed = pending.get(3, TimeUnit.SECONDS);
            assertFalse(failed.canProceed());
            assertEquals(kind.equals("取消") ? ContextAdmissionResult.FailureReason.TURN_TERMINATED
                    : ContextAdmissionResult.FailureReason.DELETE_REJECTED, failed.failureReason());
            assertNull(fixture.l0.sourceSnapshot().planRestore());
            verify(fixture.estimator, never()).estimateRequest(anyList(), anyList());
        }
    }

    @Test
    void 冷记忆实例从磁盘恢复最新状态而不继承原实例附件() {
        try (var fixture = new UnifiedContextRequestGateTest.Fixture(true)) {
            fixture.admit();
            var old = fixture.l0.sourceSnapshot().planRestore();
            fixture.plan.set(PlanContextTest.plan(1, PlanFileState.TOUCHED));
            var cold = new CompressionAwareChatMemory(fixture.l0, fixture.summaries, mock(UserMemoryService.class));
            assertNull(cold.capturePlanContext().checked());
            var admission = fixture.coordinator.admit(cold, fixture.tools, List.of(), ignored -> { }, fixture.context, fixture.boundary);
            assertTrue(admission.canProceed(), admission.detail());
            assertNotSame(old, cold.l0Memory().sourceSnapshot().planRestore());
            assertTrue(cold.l0Memory().sourceSnapshot().planRestore().body().contains("状态=TOUCHED"));
            assertSame(cold.l0Memory().sourceSnapshot().planRestore(), fixture.l0.sourceSnapshot().planRestore());
        }
    }

    @Test
    void 跨用户回合复用相同附件且不会携带上一回合临时资料() {
        try (var fixture = new UnifiedContextRequestGateTest.Fixture(true)) {
            fixture.admit();
            var attachment = fixture.l0.sourceSnapshot().planRestore();
            fixture.store.value.updateAndGet(source -> L0SourceOperations.finishTurn(source, 10, "turn", 20,
                    ChatMemoryOutcome.ANSWERED, "上一轮已结束", 0));
            fixture.context.closeResources();
            var operation = new AppOperationLeaseManager().acquire(7, AppOperationLeaseManager.AppOperationType.GENERATE, "next");
            var lease = new VueBuildSessionManager().open(operation, 9, "next");
            var admission = new VueTurnAdmissionController(new VueBuildRepairMetricsCollector(new SimpleMeterRegistry()));
            var next = new VueTurnContext(7, 9, "next", operation, lease, admission.tryAcquire().orElseThrow(), new FileToolBudgetGuard().newSession());
            try {
                next.commitUserAndBindHistory(() -> ChatHistory.builder().id(30L).appId(7L).userId(9L)
                        .messageType("user").message("继续完成").build());
                var boundary = TurnRequestBoundary.of("继续完成", List.of(), 30, "next")
                        .withPlanReader(() -> Optional.of(fixture.plan.get().rebindActiveTurn("next")), fixture::change);
                CanonicalUserMessageScope.call(boundary, () -> { fixture.l0.add(boundary.userMessage()); return null; });
                doAnswer(ignored -> new CompatibleMemorySnapshot(7, 30, "next", MemorySummarySnapshot.empty(), fixture.store.value.get()))
                        .when(fixture.summaries).readCompatible(any(), any());
                var result = fixture.coordinator.admit(fixture.memory, fixture.tools, List.of(), ignored -> { }, next, boundary);
                assertTrue(result.canProceed(), result.detail());
                assertSame(attachment, fixture.l0.sourceSnapshot().planRestore());
                assertEquals(1, attachmentCount(result.requestMessages()));
                assertTrue(result.requestMessages().indexOf(attachment.messages().getLast()) < result.requestMessages().indexOf(boundary.userMessage()));
                fixture.transientMessages.forEach(material -> assertFalse(result.requestMessages().contains(material)));
            } finally { next.closeResources(); }
        }
    }

    @Test
    void 恢复附件本身超限时计入预算并受控拒绝而不删字段() {
        try (var fixture = new UnifiedContextRequestGateTest.Fixture(true)) {
            var original = fixture.plan.get();
            fixture.plan.set(new com.lyw.appgeneration.ai.plan.AppPlan(original.planId(), "turn", "turn", 1, 1,
                    original.mode(), "完整目标".repeat(25_000), original.files(), original.history(), original.status()));
            var result = fixture.admit();
            assertEquals(ContextCompressionMode.HARD_LIMIT_REJECTED, result.mode());
            assertTrue(result.finalTokens() >= 65_536);
            assertEquals(1, attachmentCount(result.requestMessages()));
            assertTrue(result.requestMessages().toString().contains(fixture.plan.get().summary()));
            assertEquals(fixture.lastEstimated.get(), result.requestMessages());
        }
    }

    private static void cover(UnifiedContextRequestGateTest.Fixture fixture, long sequence, long revision) {
        var source = fixture.store.value.get();
        fixture.summary.set(new MemorySummarySnapshot(UnifiedContextRequestGateTest.summaryText(), 0, revision, 2,
                new SummaryCoverage(10, "turn", source.memoryEpoch(), sequence,
                        L0SourceOperations.fingerprint(source, 10, sequence, 0), 0, null, null, false)));
    }

    private static long attachmentCount(List<ChatMessage> messages) {
        return messages.stream().filter(AiMessage.from(SyntheticMemoryMessageProtocol.PLAN_SNAPSHOT_ACK)::equals).count();
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(3, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }
}
