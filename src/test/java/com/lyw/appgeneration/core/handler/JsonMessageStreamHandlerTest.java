package com.lyw.appgeneration.core.handler;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.lyw.appgeneration.ai.tools.BaseTool;
import com.lyw.appgeneration.ai.tools.FileToolBudgetGuard;
import com.lyw.appgeneration.core.AiCodeGeneratorFacade;
import com.lyw.appgeneration.core.builder.VueBuildPhase;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import com.lyw.appgeneration.manger.ToolManager;
import dev.langchain4j.service.ToolLoopTerminationProtocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JsonMessageStreamHandlerTest {

    private static final long APP_ID = 123L;
    private static final long USER_ID = 99L;

    @Mock private ToolManager toolManager;
    @Mock private VueTurnFinalizer finalizer;
    @Mock private VueTurnCancellationCoordinator cancellationCoordinator;

    private JsonMessageStreamHandler handler;

    @BeforeEach
    void setUp() {
        handler = new JsonMessageStreamHandler(
                toolManager, finalizer, cancellationCoordinator);
    }

    @Test
    void ordinaryCompleteWithoutBuildBecomesProtocolErrorAndOutcomeIsLast() {
        VueTurnContext context = context("turn-no-build", VueBuildPhase.GENERATING);
        when(finalizer.finalizeOnce(eq(context), any())).thenAnswer(invocation -> {
            VueTurnOutcome requested = invocation.getArgument(1);
            assertEquals(VueTurnOutcome.TurnOutcomeType.PROTOCOL_ERROR,
                    requested.outcome());
            assertEquals("正文\n\n项目尚未通过真实构建，请重新生成。",
                    requested.canonicalAiText());
            return new VueTurnFinalizer.FinalizationResult(requested, true);
        });

        List<GenerationStreamEvent> output = handler.handle(Flux.just(
                "{\"type\":\"ai_response\",\"data\":\"正文\"}"), context)
                .collectList().block();

        assertEquals("正文", contentText(output.getFirst()));
        VueTurnOutcome outcome = outcomeOf(output.getLast());
        assertEquals(VueTurnOutcome.TurnOutcomeType.PROTOCOL_ERROR,
                outcome.outcome());
        assertFalse(outcome.shouldRefreshPreview());
    }

    @Test
    void canonicalLimitStopsBroadcastAndPersistsOnlyResourceLimitOutcome() {
        FileToolBudgetGuard guard = new FileToolBudgetGuard();
        guard.setMaxSingleFileCodePoints(8);
        guard.setMaxCumulativeMutationCodePoints(16);
        guard.setMaxCanonicalAiTextCodePoints(64);
        guard.setMaxReadFileCodePoints(8);
        guard.setMaxReadDirCodePoints(8);
        VueTurnContext context = VueTurnContext.testing(
                APP_ID, USER_ID, "turn-canonical-limit",
                VueBuildPhase.GENERATING, guard.newSession());
        context.commitUser(() -> true);
        when(finalizer.finalizeOnce(eq(context), any())).thenAnswer(invocation -> {
            VueTurnOutcome requested = invocation.getArgument(1);
            assertEquals(VueTurnOutcome.TurnOutcomeType.SYSTEM_ERROR,
                    requested.outcome());
            assertTrue(requested.canonicalAiText().endsWith(
                    JsonMessageStreamHandler.RESOURCE_LIMIT_MESSAGE));
            assertTrue(FileToolBudgetGuard.codePointCount(
                    requested.canonicalAiText()) <= 64);
            return new VueTurnFinalizer.FinalizationResult(requested, true);
        });

        String oversized = "A".repeat(80);
        List<GenerationStreamEvent> output = handler.handle(Flux.just(
                        "{\"type\":\"ai_response\",\"data\":\""
                                + oversized + "\"}"), context)
                .collectList().block();

        String visible = output.stream()
                .filter(GenerationStreamEvent.Content.class::isInstance)
                .map(JsonMessageStreamHandlerTest::contentText)
                .reduce("", String::concat);
        assertFalse(visible.contains(oversized));
        assertTrue(FileToolBudgetGuard.codePointCount(visible) < 64);
        assertEquals(ToolLoopTerminationProtocol.ControlledTerminationReason
                        .RESOURCE_LIMIT_EXCEEDED,
                context.controlledTermination().orElseThrow().reason());
    }

    @Test
    void toolExecutedKeepsRawResultInRealtimeEventButCanonicalUsesStableMarkdown() {
        VueTurnContext context = context("turn-tool", VueBuildPhase.GENERATING);
        BaseTool tool = mock(BaseTool.class);
        when(toolManager.getTool("buildProject")).thenReturn(tool);
        when(tool.generateToolExecutedResult(any(JSONObject.class),
                eq("{\"success\":false,\"secretLog\":\"raw\"}")))
                .thenReturn("第 1 次构建失败，正在修复");
        when(finalizer.finalizeOnce(eq(context), any())).thenAnswer(invocation -> {
            VueTurnOutcome requested = invocation.getArgument(1);
            assertFalse(requested.canonicalAiText().contains("secretLog"));
            assertEquals("\n\n第 1 次构建失败，正在修复\n\n\n\n"
                            + "项目尚未通过真实构建，请重新生成。",
                    requested.canonicalAiText());
            return new VueTurnFinalizer.FinalizationResult(requested, true);
        });
        String event = "{\"type\":\"tool_executed\",\"id\":\"tool-1\","
                + "\"name\":\"buildProject\",\"arguments\":\"{}\","
                + "\"result\":\"{\\\"success\\\":false,"
                + "\\\"secretLog\\\":\\\"raw\\\"}\"}";

        List<GenerationStreamEvent> output = handler.handle(Flux.just(event), context)
                .collectList().block();

        assertEquals(event, contentText(output.get(0)));
        assertEquals("\n\n第 1 次构建失败，正在修复\n\n",
                contentText(output.get(1)));
        verify(tool).generateToolExecutedResult(any(JSONObject.class),
                eq("{\"success\":false,\"secretLog\":\"raw\"}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"readFile", "readDir"})
    void readToolContentIsVisibleToModelButRedactedFromRealtimeSse(
            String toolName) {
        VueTurnContext context = context("turn-read", VueBuildPhase.GENERATING);
        BaseTool tool = mock(BaseTool.class);
        when(toolManager.getTool(toolName)).thenReturn(tool);
        String pathArgument = "readDir".equals(toolName)
                ? "relativeDirPath" : "relativeFilePath";
        String rawResult = "{\"protocol\":\"file-tool/v1\","
                + "\"operation\":\"" + toolName + "\",\"status\":\"APPLIED\","
                + "\"relativePath\":\"src/App.vue\",\"changed\":false,"
                + "\"message\":\"已读取\",\"failureReason\":null,"
                + "\"content\":\"绝密读取正文\"}";
        when(tool.generateToolExecutedResult(any(JSONObject.class), eq(rawResult)))
                .thenReturn("[工具调用] 读取文件 src/App.vue（已应用）");
        when(finalizer.finalizeOnce(eq(context), any())).thenAnswer(invocation -> {
            VueTurnOutcome requested = invocation.getArgument(1);
            assertFalse(requested.canonicalAiText().contains("绝密读取正文"));
            return new VueTurnFinalizer.FinalizationResult(requested, true);
        });
        String event = JSONUtil.toJsonStr(new JSONObject()
                .set("type", "tool_executed")
                .set("id", "tool-read")
                .set("name", toolName)
                .set("arguments", "{\"" + pathArgument
                        + "\":\"src/App.vue\"}")
                .set("result", rawResult));

        List<GenerationStreamEvent> output = handler.handle(Flux.just(event), context)
                .collectList().block();

        String realtimeEvent = contentText(output.getFirst());
        assertFalse(realtimeEvent.contains("绝密读取正文"), realtimeEvent);
        JSONObject clientEvent = JSONUtil.parseObj(realtimeEvent);
        JSONObject clientResult = JSONUtil.parseObj(clientEvent.getStr("result"));
        assertEquals("file-tool/v1", clientResult.getStr("protocol"));
        assertEquals(toolName, clientResult.getStr("operation"));
        assertEquals("APPLIED", clientResult.getStr("status"));
        assertEquals("src/App.vue", clientResult.getStr("relativePath"));
        assertTrue(clientResult.containsKey("content"));
        assertEquals(cn.hutool.json.JSONNull.NULL, clientResult.get("content"));
        verify(tool).generateToolExecutedResult(any(JSONObject.class), eq(rawResult));
    }

    @Test
    void loopLimitUsesFixedMessageExactlyOnce() {
        VueTurnContext context = context("turn-loop", VueBuildPhase.GENERATING);
        context.recordControlledTermination(new ToolLoopTerminationProtocol
                .ControlledTermination(ToolLoopTerminationProtocol
                .ControlledTerminationReason.LOOP_LIMIT_EXCEEDED, null));
        when(finalizer.finalizeOnce(eq(context), any())).thenAnswer(invocation -> {
            VueTurnOutcome requested = invocation.getArgument(1);
            assertEquals(JsonMessageStreamHandler.LOOP_LIMIT_MESSAGE,
                    requested.canonicalAiText());
            return new VueTurnFinalizer.FinalizationResult(requested, true);
        });

        List<GenerationStreamEvent> output = handler.handle(
                Flux.error(new AiCodeGeneratorFacade
                        .OnlineControlledTerminationException(
                        ToolLoopTerminationProtocol.ControlledTerminationReason
                                .LOOP_LIMIT_EXCEEDED)), context)
                .collectList().block();

        assertEquals(1, output.size());
        VueTurnOutcome outcome = outcomeOf(output.getFirst());
        assertEquals(JsonMessageStreamHandler.LOOP_LIMIT_MESSAGE,
                outcome.clientMessage());
    }

    @Test
    void terminalBuildTimeoutUsesTimedOutOutcomeAndFixedMessage() {
        VueTurnContext context = VueTurnContext.testing(
                APP_ID, USER_ID, "turn-timeout", VueBuildPhase.FAILED, true);
        context.commitUser(() -> true);
        context.recordControlledTermination(new ToolLoopTerminationProtocol
                .ControlledTermination(ToolLoopTerminationProtocol
                .ControlledTerminationReason.BUILD_FAILED,
                JsonMessageStreamHandler.BUILD_FAILED_MESSAGE));
        when(finalizer.finalizeOnce(eq(context), any())).thenAnswer(invocation -> {
            VueTurnOutcome requested = invocation.getArgument(1);
            assertEquals(VueTurnOutcome.TurnOutcomeType.TIMED_OUT,
                    requested.outcome());
            assertEquals(JsonMessageStreamHandler.TIMEOUT_MESSAGE,
                    requested.canonicalAiText());
            return new VueTurnFinalizer.FinalizationResult(requested, true);
        });

        VueTurnOutcome outcome = outcomeOf(handler.handle(
                        Flux.just("{\"type\":\"ai_response\",\"data\":\""
                                + JsonMessageStreamHandler.BUILD_FAILED_MESSAGE
                                + "\"}"), context).blockLast());

        assertEquals(VueTurnOutcome.TurnOutcomeType.TIMED_OUT,
                outcome.outcome());
        assertEquals(JsonMessageStreamHandler.TIMEOUT_MESSAGE,
                outcome.clientMessage());
    }

    @Test
    void protocolTerminationOwnsFinalMessageAndStripsAnyLegacyTerminalText() {
        VueTurnContext context = context("turn-protocol", VueBuildPhase.GENERATING);
        context.recordControlledTermination(new ToolLoopTerminationProtocol
                .ControlledTermination(ToolLoopTerminationProtocol
                .ControlledTerminationReason.PROTOCOL_ERROR, null));
        when(finalizer.finalizeOnce(eq(context), any())).thenAnswer(invocation -> {
            VueTurnOutcome requested = invocation.getArgument(1);
            assertEquals(JsonMessageStreamHandler.SCOPE_PROTOCOL_MESSAGE,
                    requested.canonicalAiText());
            return new VueTurnFinalizer.FinalizationResult(requested, true);
        });

        List<GenerationStreamEvent> output = handler.handle(Flux.concat(
                Flux.just("{\"type\":\"ai_response\",\"data\":\""
                        + JsonMessageStreamHandler.BUILD_FAILED_MESSAGE + "\"}"),
                Flux.error(new AiCodeGeneratorFacade
                        .OnlineControlledTerminationException(
                        ToolLoopTerminationProtocol.ControlledTerminationReason
                                .PROTOCOL_ERROR))), context).collectList().block();

        VueTurnOutcome outcome = outcomeOf(output.getLast());
        assertEquals(JsonMessageStreamHandler.SCOPE_PROTOCOL_MESSAGE,
                outcome.clientMessage());
    }

    @Test
    void continuousTokensStillReachAbsoluteDeadlineAtThirtyMinutes() {
        VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
        VueTurnContext context = VueTurnContext.testing(
                APP_ID, USER_ID, "turn-absolute-timeout",
                VueBuildPhase.GENERATING, Duration.ofMinutes(30),
                () -> scheduler.now(TimeUnit.NANOSECONDS));
        context.commitUser(() -> true);
        VueTurnOutcome timedOut = new VueTurnOutcome(
                VueBuildPhase.GENERATING,
                VueTurnOutcome.TurnOutcomeType.TIMED_OUT,
                JsonMessageStreamHandler.TIMEOUT_MESSAGE,
                false, JsonMessageStreamHandler.TIMEOUT_MESSAGE);
        var finalization = new VueTurnFinalizer.FinalizationResult(
                timedOut, true);
        when(cancellationCoordinator.requestTimeout(eq(context), any()))
                .thenReturn(Optional.of(Mono.just(finalization)));
        JsonMessageStreamHandler timedHandler = new JsonMessageStreamHandler(
                toolManager, finalizer, cancellationCoordinator, scheduler);
        Flux<String> continuous = Flux.interval(
                        Duration.ofMinutes(1), scheduler)
                .map(index -> "{\"type\":\"ai_response\",\"data\":\"x\"}");

        StepVerifier.withVirtualTime(
                        () -> timedHandler.handle(continuous, context),
                        () -> scheduler, Long.MAX_VALUE)
                .thenAwait(Duration.ofMinutes(29))
                .expectNextCount(29)
                .expectNoEvent(Duration.ofSeconds(59))
                .thenAwait(Duration.ofSeconds(1))
                .assertNext(event -> {
                    VueTurnOutcome outcome = outcomeOf(event);
                    assertEquals(VueTurnOutcome.TurnOutcomeType.TIMED_OUT,
                            outcome.outcome());
                })
                .verifyComplete();
        verify(cancellationCoordinator).requestTimeout(eq(context), any());
    }

    @Test
    void rejectedTimeoutTaskMustNotReleaseLeaseBeforeCoordinatorOwnsCleanup()
            throws Exception {
        AppOperationLeaseManager manager = new AppOperationLeaseManager();
        var operation = manager.acquire(APP_ID,
                AppOperationLeaseManager.AppOperationType.GENERATE,
                "turn-timeout-rejected");
        var lease = new VueBuildSessionManager().open(
                operation, USER_ID, "turn-timeout-rejected");
        VueTurnContext context = new VueTurnContext(
                APP_ID, USER_ID, "turn-timeout-rejected", operation, lease,
                new FileToolBudgetGuard().newSession());
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        Thread callback = Thread.startVirtualThread(() ->
                context.tryRunCallback(() -> {
                    callbackEntered.countDown();
                    try {
                        releaseCallback.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }));
        assertTrue(callbackEntered.await(1, TimeUnit.SECONDS));

        java.util.concurrent.Executor rejecting = task -> {
            throw new RejectedExecutionException("executor closed");
        };
        VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
        try (var coordinator = new VueTurnCancellationCoordinator(
                finalizer, rejecting, Duration.ofMillis(20))) {
            JsonMessageStreamHandler timedHandler = new JsonMessageStreamHandler(
                    toolManager, finalizer, coordinator, scheduler);
            var result = timedHandler.handle(Flux.never(), context)
                    .collectList().toFuture();
            scheduler.advanceTimeBy(Duration.ofMinutes(30));
            boolean leaseStillOwned;
            AppOperationLeaseManager.AppOperationLease unexpected = null;
            try {
                unexpected = manager.acquire(APP_ID,
                        AppOperationLeaseManager.AppOperationType.GENERATE,
                        "turn-must-remain-owned");
                leaseStillOwned = false;
            } catch (AppOperationLeaseManager.ActiveAppOperationException expected) {
                leaseStillOwned = true;
            } finally {
                if (unexpected != null) {
                    unexpected.close();
                }
            }
            assertTrue(leaseStillOwned,
                    "协调器拒绝后台任务后，Handler 不得越权释放其待处理租约");
            releaseCallback.countDown();
            callback.join();
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> result.get(2, TimeUnit.SECONDS));
        } finally {
            releaseCallback.countDown();
            callback.join();
            context.closeResources();
        }
    }

    @Test
    void rejectedTimeoutTaskAfterUserCommitFinalizesSynchronouslyWhenQuiescent()
            throws Exception {
        AppOperationLeaseManager manager = new AppOperationLeaseManager();
        var operation = manager.acquire(APP_ID,
                AppOperationLeaseManager.AppOperationType.GENERATE,
                "turn-timeout-rejected-post-user");
        var lease = new VueBuildSessionManager().open(
                operation, USER_ID, "turn-timeout-rejected-post-user");
        VueTurnContext context = new VueTurnContext(
                APP_ID, USER_ID, "turn-timeout-rejected-post-user",
                operation, lease, new FileToolBudgetGuard().newSession());
        context.commitUser(() -> true);
        when(finalizer.finalizeOnce(eq(context), any())).thenAnswer(invocation -> {
            VueTurnOutcome requested = invocation.getArgument(1);
            context.closeResources();
            return new VueTurnFinalizer.FinalizationResult(requested, true);
        });
        java.util.concurrent.Executor rejecting = task -> {
            throw new RejectedExecutionException("executor saturated");
        };
        VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();

        try (var coordinator = new VueTurnCancellationCoordinator(
                finalizer, rejecting, Duration.ofMillis(20))) {
            JsonMessageStreamHandler timedHandler = new JsonMessageStreamHandler(
                    toolManager, finalizer, coordinator, scheduler);
            var result = timedHandler.handle(Flux.never(), context)
                    .collectList().toFuture();
            scheduler.advanceTimeBy(Duration.ofMinutes(30));
            List<GenerationStreamEvent> output = result.get(2, TimeUnit.SECONDS);
            assertEquals(1, output.size());
            assertEquals(VueTurnOutcome.TurnOutcomeType.TIMED_OUT,
                    outcomeOf(output.getFirst()).outcome());
            verify(finalizer).finalizeOnce(eq(context), any());
        } finally {
            context.closeResources();
        }
    }

    @Test
    void claimedTimeoutFinalizationErrorMustPropagateInsteadOfCompleting() {
        VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
        VueTurnContext context = VueTurnContext.testing(
                APP_ID, USER_ID, "turn-timeout-finalization-error",
                VueBuildPhase.GENERATING, Duration.ofMinutes(30),
                () -> scheduler.now(TimeUnit.NANOSECONDS));
        context.commitUser(() -> true);
        assertTrue(context.tryStartFinalization(
                VueTurnContext.TerminalTrigger.TIMED_OUT));
        when(cancellationCoordinator.requestTimeout(eq(context), any()))
                .thenReturn(Optional.of(Mono.error(
                        new IllegalStateException("超时收尾失败"))));
        JsonMessageStreamHandler timedHandler = new JsonMessageStreamHandler(
                toolManager, finalizer, cancellationCoordinator, scheduler);

        StepVerifier.withVirtualTime(
                        () -> timedHandler.handle(Flux.never(), context),
                        () -> scheduler, Long.MAX_VALUE)
                .thenAwait(Duration.ofMinutes(30))
                .expectErrorMessage("超时收尾失败")
                .verify();
    }

    @Test
    void deleteTakeoverBeforeBodyOutputCancelsOriginAndPublishesSingleOutcome()
            throws Exception {
        DeleteTakeoverFixture fixture = deleteTakeoverFixture("turn-delete-before");
        AtomicBoolean originCancelled = new AtomicBoolean();
        JsonMessageStreamHandler takeoverHandler = new JsonMessageStreamHandler(
                toolManager, finalizer, fixture.coordinator());
        var output = takeoverHandler.handle(
                        Flux.<String>never()
                                .doOnCancel(() -> originCancelled.set(true)),
                        fixture.context())
                .collectList().toFuture();

        try (var background = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<AppOperationLeaseManager.AppOperationLease> deletion =
                    background.submit(() -> fixture.manager().cancelAndAcquireDelete(
                            APP_ID, "delete-before", Duration.ofSeconds(1)));

            List<GenerationStreamEvent> events = output.get(1, TimeUnit.SECONDS);
            assertEquals(1, events.size());
            assertEquals(VueTurnOutcome.TurnOutcomeType.CANCELLED,
                    outcomeOf(events.getFirst()).outcome());
            assertTrue(originCancelled.get());
            try (var deleteLease = deletion.get(1, TimeUnit.SECONDS)) {
                assertEquals(AppOperationLeaseManager.AppOperationType.DELETE,
                        deleteLease.operationType());
            }
        } finally {
            fixture.close();
        }
    }

    @Test
    void deleteTakeoverAfterBodyOutputPreservesBodyThenPublishesOutcome()
            throws Exception {
        DeleteTakeoverFixture fixture = deleteTakeoverFixture("turn-delete-after");
        CountDownLatch bodyPublished = new CountDownLatch(1);
        JsonMessageStreamHandler takeoverHandler = new JsonMessageStreamHandler(
                toolManager, finalizer, fixture.coordinator());
        var output = takeoverHandler.handle(Flux.concat(
                        Flux.just("{\"type\":\"ai_response\",\"data\":\"正文\"}")
                                .doOnNext(ignored -> bodyPublished.countDown()),
                        Flux.never()), fixture.context())
                .collectList().toFuture();
        assertTrue(bodyPublished.await(1, TimeUnit.SECONDS));

        try (var background = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<AppOperationLeaseManager.AppOperationLease> deletion =
                    background.submit(() -> fixture.manager().cancelAndAcquireDelete(
                            APP_ID, "delete-after", Duration.ofSeconds(1)));

            List<GenerationStreamEvent> events = output.get(1, TimeUnit.SECONDS);
            assertEquals(2, events.size());
            assertEquals("正文", contentText(events.getFirst()));
            assertEquals(VueTurnOutcome.TurnOutcomeType.CANCELLED,
                    outcomeOf(events.getLast()).outcome());
            try (var deleteLease = deletion.get(1, TimeUnit.SECONDS)) {
                assertEquals(AppOperationLeaseManager.AppOperationType.DELETE,
                        deleteLease.operationType());
            }
        } finally {
            fixture.close();
        }
    }

    @Test
    void 正常完成先赢时删除必须等待同一共享终态且不得重复收尾()
            throws Exception {
        AppOperationLeaseManager manager = new AppOperationLeaseManager();
        String turnId = "turn-complete-delete-race";
        var operation = manager.acquire(APP_ID,
                AppOperationLeaseManager.AppOperationType.GENERATE, turnId);
        var lease = new VueBuildSessionManager().open(
                operation, USER_ID, turnId);
        VueTurnContext context = new VueTurnContext(
                APP_ID, USER_ID, turnId, operation, lease,
                new FileToolBudgetGuard().newSession());
        context.commitUser(() -> true);
        context.registerDeleteTakeoverParticipant();
        CountDownLatch finalizerEntered = new CountDownLatch(1);
        CountDownLatch releaseFinalizer = new CountDownLatch(1);
        AtomicInteger finalizations = new AtomicInteger();
        when(finalizer.finalizeOnce(eq(context), any())).thenAnswer(invocation -> {
            finalizations.incrementAndGet();
            finalizerEntered.countDown();
            assertTrue(releaseFinalizer.await(1, TimeUnit.SECONDS));
            VueTurnOutcome requested = invocation.getArgument(1);
            var result = new VueTurnFinalizer.FinalizationResult(
                    requested, true);
            context.closeResources();
            context.completeFinalization(result);
            return result;
        });
        try (var background = Executors.newVirtualThreadPerTaskExecutor();
             var coordinator = new VueTurnCancellationCoordinator(
                     finalizer, background, Duration.ofSeconds(1))) {
            JsonMessageStreamHandler racingHandler =
                    new JsonMessageStreamHandler(
                            toolManager, finalizer, coordinator);
            Future<List<GenerationStreamEvent>> output = background.submit(() ->
                    racingHandler.handle(Flux.empty(), context)
                            .collectList().block());
            assertTrue(finalizerEntered.await(1, TimeUnit.SECONDS));
            assertEquals(VueTurnContext.TerminalTrigger.COMPLETED,
                    context.terminalWinner().orElseThrow());

            Future<AppOperationLeaseManager.AppOperationLease> deletion =
                    background.submit(() -> manager.cancelAndAcquireDelete(
                            APP_ID, "delete-after-complete-claim",
                            Duration.ofSeconds(1)));
            long deleteStartedDeadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(1);
            while (!operation.isCancellationRequested()
                    && System.nanoTime() < deleteStartedDeadline) {
                Thread.onSpinWait();
            }
            assertTrue(operation.isCancellationRequested(),
                    "删除必须先捕获已注册参与者再释放正常收尾屏障");
            assertFalse(deletion.isDone(),
                    "正常收尾完成前 DELETE 不得替换生成租约");
            releaseFinalizer.countDown();

            List<GenerationStreamEvent> events =
                    output.get(1, TimeUnit.SECONDS);
            assertEquals(1, events.size());
            assertEquals(VueTurnOutcome.TurnOutcomeType.PROTOCOL_ERROR,
                    outcomeOf(events.getFirst()).outcome());
            try (var deleteLease = deletion.get(1, TimeUnit.SECONDS)) {
                assertEquals(AppOperationLeaseManager.AppOperationType.DELETE,
                        deleteLease.operationType());
            }
            assertEquals(1, finalizations.get());
            verify(finalizer, times(1)).finalizeOnce(eq(context), any());
        } finally {
            releaseFinalizer.countDown();
            context.closeResources();
        }
    }

    @Test
    void 客户端取消先赢时删除必须等待后台共享收尾且不得重复持久化()
            throws Exception {
        AppOperationLeaseManager manager = new AppOperationLeaseManager();
        String turnId = "turn-cancel-delete-race";
        var operation = manager.acquire(APP_ID,
                AppOperationLeaseManager.AppOperationType.GENERATE, turnId);
        var lease = new VueBuildSessionManager().open(
                operation, USER_ID, turnId);
        VueTurnContext context = new VueTurnContext(
                APP_ID, USER_ID, turnId, operation, lease,
                new FileToolBudgetGuard().newSession());
        context.commitUser(() -> true);
        context.registerDeleteTakeoverParticipant();
        CountDownLatch finalizerEntered = new CountDownLatch(1);
        CountDownLatch releaseFinalizer = new CountDownLatch(1);
        AtomicInteger finalizations = new AtomicInteger();
        when(finalizer.finalizeOnce(eq(context), any())).thenAnswer(invocation -> {
            finalizations.incrementAndGet();
            finalizerEntered.countDown();
            assertTrue(releaseFinalizer.await(1, TimeUnit.SECONDS));
            VueTurnOutcome requested = invocation.getArgument(1);
            var result = new VueTurnFinalizer.FinalizationResult(
                    requested, true);
            context.closeResources();
            context.completeFinalization(result);
            return result;
        });
        try (var background = Executors.newVirtualThreadPerTaskExecutor();
             var coordinator = new VueTurnCancellationCoordinator(
                     finalizer, background, Duration.ofSeconds(1))) {
            JsonMessageStreamHandler racingHandler =
                    new JsonMessageStreamHandler(
                            toolManager, finalizer, coordinator);
            reactor.core.Disposable subscription = racingHandler
                    .handle(Flux.never(), context).subscribe();
            subscription.dispose();
            assertTrue(finalizerEntered.await(1, TimeUnit.SECONDS));
            assertEquals(VueTurnContext.TerminalTrigger.CANCELLED,
                    context.terminalWinner().orElseThrow());

            Future<AppOperationLeaseManager.AppOperationLease> deletion =
                    background.submit(() -> manager.cancelAndAcquireDelete(
                            APP_ID, "delete-during-cancel-finalization",
                            Duration.ofSeconds(1)));
            assertFalse(deletion.isDone(),
                    "取消后台收尾完成前 DELETE 不得替换生成租约");
            releaseFinalizer.countDown();

            try (var deleteLease = deletion.get(1, TimeUnit.SECONDS)) {
                assertEquals(AppOperationLeaseManager.AppOperationType.DELETE,
                        deleteLease.operationType());
            }
            assertEquals(1, finalizations.get());
            verify(finalizer, times(1)).finalizeOnce(eq(context), any());
        } finally {
            releaseFinalizer.countDown();
            context.closeResources();
        }
    }

    private DeleteTakeoverFixture deleteTakeoverFixture(String turnId) {
        AppOperationLeaseManager manager = new AppOperationLeaseManager();
        var operation = manager.acquire(APP_ID,
                AppOperationLeaseManager.AppOperationType.GENERATE, turnId);
        var lease = new VueBuildSessionManager().open(
                operation, USER_ID, turnId);
        VueTurnContext context = new VueTurnContext(
                APP_ID, USER_ID, turnId, operation, lease,
                new FileToolBudgetGuard().newSession());
        context.commitUser(() -> true);
        context.registerDeleteTakeoverParticipant();
        when(finalizer.finalizeOnce(eq(context), any())).thenAnswer(invocation -> {
            VueTurnOutcome requested = invocation.getArgument(1);
            var result = new VueTurnFinalizer.FinalizationResult(requested, true);
            context.closeResources();
            context.completeFinalization(result);
            return result;
        });
        VueTurnCancellationCoordinator coordinator =
                new VueTurnCancellationCoordinator(
                        finalizer, Runnable::run, Duration.ofSeconds(1));
        return new DeleteTakeoverFixture(manager, context, coordinator);
    }

    private VueTurnContext context(String turnId, VueBuildPhase phase) {
        VueTurnContext context = VueTurnContext.testing(
                APP_ID, USER_ID, turnId, phase);
        context.commitUser(() -> true);
        return context;
    }

    private static String contentText(GenerationStreamEvent event) {
        return ((GenerationStreamEvent.Content) event).text();
    }

    private static VueTurnOutcome outcomeOf(GenerationStreamEvent event) {
        return new VueTurnOutcome(((GenerationStreamEvent.TurnOutcome) event).message().getPhase(), ((GenerationStreamEvent.TurnOutcome) event).message().getOutcome(), ((GenerationStreamEvent.TurnOutcome) event).message().getMessage(), ((GenerationStreamEvent.TurnOutcome) event).message().isShouldRefreshPreview(), ((GenerationStreamEvent.TurnOutcome) event).message().getMessage());
    }

    private record DeleteTakeoverFixture(
            AppOperationLeaseManager manager,
            VueTurnContext context,
            VueTurnCancellationCoordinator coordinator) implements AutoCloseable {

        @Override
        public void close() {
            coordinator.close();
            context.closeResources();
        }
    }
}
