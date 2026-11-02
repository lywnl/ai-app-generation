package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.ai.plan.PlanFileState;
import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanRestoreEventGateTest {
    @ParameterizedTest
    @ValueSource(strings = {"读盘", "计量"})
    void 准备期间计划定义变化只能放行最新版本(String phase) {
        try (var f = new UnifiedContextRequestGateTest.Fixture(true)) {
            var once = new AtomicBoolean();
            if (phase.equals("读盘")) {
                f.boundary = f.boundary.withPlanReader(() -> {
                    f.planReads.incrementAndGet();
                    var captured = f.plan.get();
                    if (once.compareAndSet(false, true)) f.plan.set(PlanContextTest.plan(2, PlanFileState.PENDING));
                    return Optional.of(captured);
                }, f::change);
            } else {
                when(f.estimator.estimateRequest(anyList(), anyList())).thenAnswer(invocation -> {
                    List<ChatMessage> messages = invocation.getArgument(0);
                    f.lastEstimated.set(List.copyOf(messages));
                    if (once.compareAndSet(false, true)) f.plan.set(PlanContextTest.plan(2, PlanFileState.PENDING));
                    return f.realEstimator.estimateRequest(messages, invocation.getArgument(1));
                });
            }
            var result = f.admit();
            assertTrue(result.canProceed(), result.detail());
            assertEquals(2, f.planReads.get());
            assertEquals(2, f.l0.sourceSnapshot().planRestore().definition().version());
            assertTrue(result.requestMessages().toString().contains("恢复时的计划快照 version=2"));
            assertFalse(result.requestMessages().toString().contains("恢复时的计划快照 version=1"));
            assertEquals(f.lastEstimated.get(), result.requestMessages());
            assertEquals(1, f.l0.sourceSnapshot().window().stream().filter(L0Snapshot.PlanRestore.class::isInstance).count());
        }
    }

    @Test
    void 协议恢复撤销代次后已准备的热窗口快照不得迟到发送() throws Exception {
        try (var f = new UnifiedContextRequestGateTest.Fixture(false)) {
            var model = new UnifiedContextRequestGateTest.RecordingModel();
            var generator = dev.langchain4j.service.AiServices.builder(UnifiedContextRequestGateTest.TestService.class)
                    .streamingChatModel(model).chatMemoryProvider(ignored -> f.memory)
                    .tools(new UnifiedContextRequestGateTest.ReadTools()).build();
            var secondPrepared = new CompletableFuture<dev.langchain4j.service.ModelRequestGate.Decision>();
            var release = new CompletableFuture<dev.langchain4j.service.ModelRequestGate.Decision>();
            var dispatched = new CountDownLatch(1);
            var preparations = new java.util.concurrent.atomic.AtomicInteger();
            var callbacks = new java.util.concurrent.atomic.AtomicInteger();
            var gate = new ContextCompressionModelRequestGate(f.coordinator, f.gateExecutor) {
                @Override public CompletionStage<Decision> prepare(Request request) {
                    var result = super.prepare(request);
                    if (preparations.incrementAndGet() != 2) return result;
                    return result.thenCompose(decision -> { secondPrepared.complete(decision); return release; });
                }
                @Override public CompletionStage<DispatchStatus> onPrepared(CompletionStage<Decision> preparation,
                        java.util.function.BiConsumer<Decision, Throwable> completion) {
                    int index = callbacks.incrementAndGet();
                    return super.onPrepared(preparation, completion).whenComplete((status, error) -> {
                        if (index == 2) dispatched.countDown();
                    });
                }
            };
            var errors = new LinkedBlockingQueue<Throwable>();
            var stream = CanonicalUserMessageScope.call(f.boundary, () -> generator.chat(7L, f.rawUser));
            stream.turnRequestBoundary(f.boundary).requestBoundaryRequired(true).modelRequestGate(gate, f.context)
                    .onPartialResponse(ignored -> { }).onError(errors::add);
            try {
                stream.start();
                var initial = model.calls.poll(4, TimeUnit.SECONDS);
                assertNotNull(initial, errors.toString());
                var call = PlanContextTest.call("read", "readFile");
                initial.handler().onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(AiMessage.from(call)).metadata(dev.langchain4j.model.chat.response.ChatResponseMetadata.builder()
                                .tokenUsage(new dev.langchain4j.model.output.TokenUsage(1, 1, 2)).build()).build());
                var decision = secondPrepared.get(4, TimeUnit.SECONDS);
                assertEquals(dev.langchain4j.service.ModelRequestGate.Status.ALLOWED, decision.status());
                assertEquals(1, f.planReads.get(), "第二次准备复用已检查热窗口，未重读计划");
                var controller = org.springframework.test.util.ReflectionTestUtils.getField(stream, "requestController");
                Long generation = org.springframework.test.util.ReflectionTestUtils.invokeMethod(controller, "latestModelRequestGeneration");
                Object cancelled = org.springframework.test.util.ReflectionTestUtils.invokeMethod(controller,
                        "cancelGenerationForRecovery", generation);
                assertEquals("CANCELLED", cancelled.toString());
                var source = f.l0.sourceSnapshot();
                var rebuilt = L0SourceOperations.resetActive(source, source.originalMessages());
                assertEquals(DeadlineAwareReplaceResult.REPLACED, f.l0.replaceSourceSnapshot(source, rebuilt,
                        AdmissionDeadline.start(java.time.Duration.ofSeconds(5))));
                assertNotEquals(source.memoryEpoch(), rebuilt.memoryEpoch());
                assertNull(rebuilt.planRestore());
                release.complete(decision);
                assertTrue(dispatched.await(4, TimeUnit.SECONDS));
                assertTrue(model.calls.isEmpty(), "真实 GenerationAwareModelRequestOrchestrator 必须拒绝旧来源代次");
            } finally {
                release.completeExceptionally(new CancellationException());
                stream.cancel();
            }
        }
    }
    @Test
    void 连续执行只恢复一次且原位正文与读盘次数不再变化() {
        try (var f = new UnifiedContextRequestGateTest.Fixture(true)) {
            f.plan.set(PlanContextTest.plan(1, PlanFileState.PENDING));
            assertTrue(f.admit().canProceed());
            var restore = f.l0.sourceSnapshot().planRestore();
            int position = f.l0.sourceSnapshot().window().indexOf(restore);
            for (int i = 0; i < 6; i++) {
                var call = PlanContextTest.call("write-" + i, "writeFile");
                f.l0.add(AiMessage.from(call));
                f.l0.add(ToolExecutionResultMessage.from(call, "第 " + i + " 个文件已写入"));
                f.plan.set(PlanContextTest.plan(1, PlanFileState.TOUCHED));
                var sent = f.admit();
                assertTrue(sent.canProceed(), sent.detail());
                assertEquals(restore, f.l0.sourceSnapshot().planRestore());
                assertEquals(position, f.l0.sourceSnapshot().window().indexOf(restore));
                assertEquals(1, sent.requestMessages().stream().filter(restore.messages().getLast()::equals).count());
                assertEquals(f.lastEstimated.get(), sent.requestMessages());
            }
            assertEquals(1, f.planReads.get());
            assertEquals(15, f.l0.sourceSnapshot().window().size());
            assertEquals(14, f.l0.sourceSnapshot().entries().size());
        }
    }

    @Test
    void 无计划只检查一次而定义完整成功后无需额外读盘() {
        try (var f = new UnifiedContextRequestGateTest.Fixture(true)) {
            f.plan.set(null);
            for (int i = 0; i < 4; i++) assertTrue(f.admit().canProceed());
            assertEquals(1, f.planReads.get());
            assertNull(f.l0.sourceSnapshot().planRestore());
            f.plan.set(PlanContextTest.plan(1, PlanFileState.PENDING));
            var call = PlanContextTest.call("make", "makePlan");
            f.l0.add(AiMessage.from(call));
            f.l0.add(ToolExecutionResultMessage.from(call, PlanContextTest.success("makePlan", f.plan.get())));
            assertTrue(f.admit().canProceed());
            assertEquals(1, f.planReads.get());
            assertNull(f.l0.sourceSnapshot().planRestore());
        }
    }

    @Test
    void 并行未完成批次恢复在批次之前且完整后撤下不再读盘() {
        try (var f = new UnifiedContextRequestGateTest.Fixture(true)) {
            f.plan.set(null);
            f.admit();
            f.plan.set(PlanContextTest.plan(1, PlanFileState.PENDING));
            var make = PlanContextTest.call("make", "makePlan");
            var read = PlanContextTest.call("read", "readFile");
            f.l0.add(AiMessage.from(make, read));
            f.l0.add(ToolExecutionResultMessage.from(make, PlanContextTest.success("makePlan", f.plan.get())));
            var pending = f.admit();
            assertTrue(pending.canProceed(), pending.detail());
            var restore = f.l0.sourceSnapshot().planRestore();
            assertNotNull(restore);
            assertTrue(pending.requestMessages().indexOf(restore.messages().getLast())
                    < pending.requestMessages().indexOf(AiMessage.from(make, read)));
            int reads = f.planReads.get();
            f.l0.add(ToolExecutionResultMessage.from(read, "读取完成"));
            var complete = f.admit();
            assertTrue(complete.canProceed());
            assertNull(f.l0.sourceSnapshot().planRestore());
            assertEquals(reads, f.planReads.get());
            assertEquals(2, complete.requestMessages().stream().filter(ToolExecutionResultMessage.class::isInstance).count());
        }
    }

    @Test
    void 读盘期间原文追加造成CAS冲突会重读且保留新增事实() {
        try (var f = new UnifiedContextRequestGateTest.Fixture(true)) {
            var once = new AtomicBoolean();
            f.boundary = f.boundary.withPlanReader(() -> {
                f.planReads.incrementAndGet();
                if (once.compareAndSet(false, true)) f.l0.add(AiMessage.from("读盘期间提交的新事实"));
                return Optional.of(f.plan.get());
            }, f::change);
            var result = f.admit();
            assertTrue(result.canProceed(), result.detail());
            assertEquals(2, f.planReads.get());
            assertTrue(result.requestMessages().contains(AiMessage.from("读盘期间提交的新事实")));
            assertEquals(1, f.l0.sourceSnapshot().window().stream().filter(L0Snapshot.PlanRestore.class::isInstance).count());
            assertEquals(3, f.l0.sourceSnapshot().nextSequence());
        }
    }

    @Test
    void 并发请求不重复追加恢复或覆盖原文() throws Exception {
        try (var f = new UnifiedContextRequestGateTest.Fixture(true); var callers = Executors.newFixedThreadPool(4)) {
            var start = new CountDownLatch(1);
            var tasks = new ArrayList<Future<ContextAdmissionResult>>();
            for (int i = 0; i < 4; i++) tasks.add(callers.submit(() -> { start.await(); return f.admit(); }));
            start.countDown();
            for (var task : tasks) assertTrue(task.get(8, TimeUnit.SECONDS).canProceed());
            var source = f.l0.sourceSnapshot();
            assertEquals(1, source.window().stream().filter(L0Snapshot.PlanRestore.class::isInstance).count());
            assertEquals(2, source.nextSequence());
            assertEquals(2, source.entries().size());
            assertEquals(0L, source.appliedSummaryRevision());
        }
    }

    @Test
    void 持久恢复提交失败时不得发送或登记已检查() {
        try (var f = new UnifiedContextRequestGateTest.Fixture(true)) {
            f.store.rejectWrite = true;
            var original = f.l0.sourceSnapshot();
            var failed = f.admit();
            assertFalse(failed.canProceed());
            assertEquals(ContextAdmissionResult.FailureReason.DEPENDENCY_FAILED, failed.failureReason());
            assertEquals(original, f.l0.sourceSnapshot());
            assertNull(f.memory.capturePlanContext().checked());
            assertNull(f.lastEstimated.get());
        }
    }
}
