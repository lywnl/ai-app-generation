package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.monitor.MemoryCompressionMetricsCollector;
import com.lyw.appgeneration.monitor.ThrowingMeterRegistry;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemoryCompressionResult;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.UserMemoryService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ContextCompressionCoordinatorTest {

    private static final String VALID_SUMMARY = """
            # 应用目标与定位
            测试应用
            # 用户偏好与硬约束
            无
            # 已否决的方案
            无
            # 关键设计决策与理由
            无
            # 当前进度速览
            已完成早期回合
            """.strip();

    @org.junit.jupiter.api.Test
    void 固定分层Token门禁保持不变() {
        MemoryTokenProperties properties = new MemoryTokenProperties();

        assertEquals(12_288, properties.getL0RetainedTokens());
        assertEquals(3_072, properties.getL1MaxSummaryTokens());
        assertEquals(1_024, properties.getL2MaxRecallTokens());
        assertEquals(28_672, properties.getAsyncCompressionThreshold());
        assertEquals(30_720, properties.getBlockingCompressionThreshold());
        assertEquals(32_768, properties.getHardInputLimit());
        assertEquals(8_192, properties.getMaxOutputTokens());
        assertEquals(1.15D, properties.getEstimationSafetyFactor());
    }

    @org.junit.jupiter.api.Test
    void exposesMetricsAwareConstructor() {
        assertDoesNotThrow(() -> ContextCompressionCoordinator.class
                .getConstructor(
                        ChatTokenEstimator.class,
                        ChatHistoryService.class,
                        MemorySummaryService.class,
                        MemoryTokenProperties.class,
                        ExecutorService.class,
                        AppDataLifecycleFence.class,
                        MemoryCompressionMetricsCollector.class));
    }

    @org.junit.jupiter.api.Test
    void publicAdmitOverloadsRecordOneFinalGateEach() {
        try (Fixture fixture = fixture(1_000, 1_000)) {
            fixture.coordinator().admit(fixture.memory(), List.of());
            fixture.coordinator().admit(
                    fixture.memory(), List.of(), ignored -> { });
            fixture.coordinator().admit(
                    fixture.memory(), List.of(), ignored -> { },
                    ContextContinuationGate.alwaysOpen());

            Counter normal = counter(fixture.registry(),
                    "memory_context_gate_total",
                    "mode", "normal", "outcome", "none");
            assertEquals(3D, normal.count());
            assertEquals(3D, fixture.registry()
                    .find("memory_context_gate_total")
                    .counters().stream()
                    .mapToDouble(Counter::count)
                    .sum());
        }
    }

    @org.junit.jupiter.api.Test
    void blockingAdmissionRecordsFinalGateAndActualTokenStages() {
        try (Fixture fixture = fixture(30_720, 27_000)) {
            ContextAdmissionResult result = fixture.coordinator().admit(
                    fixture.memory(), List.of(), ignored -> { });

            assertEquals(ContextCompressionMode.BLOCKING_COMPLETED,
                    result.mode());
            assertEquals(1D, counter(fixture.registry(),
                    "memory_context_gate_total",
                    "mode", "blocking_completed",
                    "outcome", "none").count());
            assertEquals(1D, fixture.registry()
                    .find("memory_context_gate_total")
                    .counters().stream()
                    .mapToDouble(Counter::count)
                    .sum());
            DistributionSummary before = summary(fixture.registry(),
                    "memory_context_estimated_tokens",
                    "stage", "before");
            DistributionSummary after = summary(fixture.registry(),
                    "memory_context_estimated_tokens",
                    "stage", "after");
            assertEquals(1L, before.count());
            assertEquals(30_720D, before.totalAmount());
            assertEquals(1L, after.count());
            assertEquals(27_000D, after.totalAmount());
        }
    }

    @ParameterizedTest
    @EnumSource(value = ThrowingMeterRegistry.FailurePoint.class,
            names = {
                    "COUNTER_REGISTRATION", "COUNTER_INCREMENT",
                    "SUMMARY_REGISTRATION", "SUMMARY_RECORD"
            })
    void metricFailureDoesNotChangeAdmissionResult(
            ThrowingMeterRegistry.FailurePoint failurePoint) {
        try (Fixture fixture = fixture(1_000, 1_000)) {
            ThrowingMeterRegistry registry =
                    new ThrowingMeterRegistry(failurePoint);
            try {
            ContextCompressionCoordinator coordinator =
                    new ContextCompressionCoordinator(
                            fixture.estimator(), fixture.historyService(),
                            fixture.summaryService(), fixture.properties(),
                            fixture.executor(), new AppDataLifecycleFence(),
                            new MemoryCompressionMetricsCollector(registry));

            ContextAdmissionResult result = coordinator.admit(
                    fixture.memory(), List.of());

            assertEquals(ContextCompressionMode.NORMAL, result.mode());
            assertTrue(result.canProceed());
            assertTrue(registry.failureTriggered());
            } finally {
                registry.close();
            }
        }
    }

    @ParameterizedTest
    @MethodSource("exactThresholds")
    void routesSixExactThresholdBoundaries(
            int initialTokens,
            ContextCompressionMode expectedMode,
            boolean expectsAsync,
            boolean expectsBlocking) {
        try (Fixture fixture = fixture(initialTokens, 27_000)) {
            ContextAdmissionResult result = fixture.coordinator().admit(
                    fixture.memory(), List.of());

            assertEquals(expectedMode, result.mode());
            assertTrue(result.canProceed());
            assertEquals(initialTokens, result.initialTokens());
            if (expectsAsync) {
                org.mockito.ArgumentCaptor<BooleanSupplier> startPermit =
                        org.mockito.ArgumentCaptor.forClass(
                                BooleanSupplier.class);
                verify(fixture.summaryService(), org.mockito.Mockito.timeout(1_000))
                        .triggerSummarizationAsync(
                                eq(7L), eq(2L), startPermit.capture());
                assertTrue(startPermit.getValue().getAsBoolean());
            } else {
                verify(fixture.summaryService(), never())
                        .triggerSummarizationAsync(
                                any(), eq(2L), any(BooleanSupplier.class));
            }
            if (expectsBlocking) {
                verify(fixture.summaryService()).compressNow(
                        eq(7L), eq(2L), any(Duration.class));
                assertEquals(27_000, result.finalTokens());
            } else {
                verify(fixture.summaryService(), never()).compressNow(
                        any(), eq(2L), any(Duration.class));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("postCompressionIntervals")
    void emitsBlockingStartedThenRoutesFourPostCompressionIntervals(
            int compressedTokens,
            ContextCompressionMode expectedFinalMode,
            boolean canProceed) {
        try (Fixture fixture = fixture(30_720, compressedTokens)) {
            List<ContextAdmissionResult> transitions = new ArrayList<>();

            ContextAdmissionResult result = fixture.coordinator().admit(
                    fixture.memory(), List.of(), transitions::add);

            assertEquals(1, transitions.size());
            assertEquals(ContextCompressionMode.BLOCKING_STARTED,
                    transitions.getFirst().mode());
            assertEquals(30_720, transitions.getFirst().initialTokens());
            assertEquals(expectedFinalMode, result.mode());
            assertEquals(compressedTokens, result.finalTokens());
            assertEquals(canProceed, result.canProceed());
        }
    }

    @org.junit.jupiter.api.Test
    void blockingThresholdWithoutCompressibleOldTurnFailsClosed() {
        try (Fixture fixture = fixture(30_720, 27_000)) {
            doReturn(6_000).when(fixture.estimator())
                    .estimateMessages(anyList());

            ContextAdmissionResult result = fixture.coordinator().admit(
                    fixture.memory(), List.of());

            assertEquals(ContextCompressionMode.BLOCKING_FAILED, result.mode());
            assertEquals(
                    ContextAdmissionResult.FailureReason.NO_COMPRESSIBLE_TURN,
                    result.failureReason());
            assertFalse(result.canProceed());
            verify(fixture.summaryService(), never()).compressNow(
                    any(), any(Long.class), any(Duration.class));
        }
    }

    @org.junit.jupiter.api.Test
    void asyncThresholdWithoutCompressibleOldTurnContinuesWithoutScheduling() {
        try (Fixture fixture = fixture(28_672, 28_672)) {
            doReturn(6_000).when(fixture.estimator())
                    .estimateMessages(anyList());

            ContextAdmissionResult result = fixture.coordinator().admit(
                    fixture.memory(), List.of());

            assertEquals(ContextCompressionMode.ASYNC_SCHEDULED, result.mode());
            assertEquals(ContextAdmissionResult.FailureReason.NONE,
                    result.failureReason());
            assertTrue(result.canProceed());
            verify(fixture.summaryService(), org.mockito.Mockito.after(200).never())
                    .triggerSummarizationAsync(
                            any(), anyLong(), any(BooleanSupplier.class));
            verify(fixture.summaryService(), never()).compressNow(
                    any(), any(Long.class), any(Duration.class));
        }
    }

    @org.junit.jupiter.api.Test
    void hardLimitWithoutCompressibleOldTurnIsRejected() {
        try (Fixture fixture = fixture(32_768, 32_768)) {
            doReturn(6_000).when(fixture.estimator())
                    .estimateMessages(anyList());

            ContextAdmissionResult result = fixture.coordinator().admit(
                    fixture.memory(), List.of());

            assertEquals(ContextCompressionMode.HARD_LIMIT_REJECTED,
                    result.mode());
            assertEquals(
                    ContextAdmissionResult.FailureReason.NO_COMPRESSIBLE_TURN,
                    result.failureReason());
            assertFalse(result.canProceed());
            verify(fixture.summaryService(), never()).compressNow(
                    any(), any(Long.class), any(Duration.class));
        }
    }

    @org.junit.jupiter.api.Test
    void l0AndMysqlTerminalAiMismatchReturnsTypedAlignmentFailure() {
        try (Fixture fixture = fixture(30_720, 27_000)) {
            when(fixture.historyService().listRecentCompleteTurnBoundaries(
                    7L, 2)).thenReturn(List.of(
                    new ChatHistoryService.StableTurnBoundary(
                            1L, 2L, "旧问题", "错位回复"),
                    new ChatHistoryService.StableTurnBoundary(
                            3L, 4L, "新问题", "新回复")));

            ContextAdmissionResult result = fixture.coordinator().admit(
                    fixture.memory(), List.of());

            assertEquals(ContextCompressionMode.BLOCKING_FAILED, result.mode());
            assertEquals(ContextAdmissionResult.FailureReason.ALIGNMENT_FAILED,
                    result.failureReason());
            assertFalse(result.canProceed());
            verify(fixture.summaryService(), never()).compressNow(
                    any(), any(Long.class), any(Duration.class));
        }
    }

    @org.junit.jupiter.api.Test
    void blockingPlanMysqlReadFailureIsDependencyFailure() {
        try (Fixture fixture = fixture(30_720, 27_000)) {
            when(fixture.historyService().listRecentCompleteTurnBoundaries(
                    7L, 2)).thenThrow(new IllegalStateException("mysql down"));

            ContextAdmissionResult result = fixture.coordinator().admit(
                    fixture.memory(), List.of());

            assertEquals(ContextCompressionMode.BLOCKING_FAILED, result.mode());
            assertEquals(ContextAdmissionResult.FailureReason.DEPENDENCY_FAILED,
                    result.failureReason());
            assertFalse(result.canProceed());
            verify(fixture.summaryService(), never()).compressNow(
                    any(), any(Long.class), any(Duration.class));
        }
    }

    @org.junit.jupiter.api.Test
    void asyncZoneAlignmentFailureKeepsCurrentRequestAvailable() {
        try (Fixture fixture = fixture(29_000, 29_000)) {
            when(fixture.historyService().listRecentCompleteTurnBoundaries(
                    7L, 2)).thenReturn(List.of(
                    new ChatHistoryService.StableTurnBoundary(
                            1L, 2L, "旧问题", "错位回复"),
                    new ChatHistoryService.StableTurnBoundary(
                            3L, 4L, "新问题", "新回复")));

            ContextAdmissionResult result = fixture.coordinator().admit(
                    fixture.memory(), List.of());

            assertTrue(result.canProceed(),
                    "异步压缩区间的后台准备失败不得阻断仍低于 30K 的当前请求");
            assertEquals(29_000, result.finalTokens());
            verify(fixture.summaryService(), never()).compressNow(
                    any(), any(Long.class), any(Duration.class));
        }
    }

    @org.junit.jupiter.api.Test
    void asyncZoneDoesNotWaitForMysqlCompressionPlanning() throws Exception {
        try (Fixture fixture = fixture(29_000, 27_000);
             ExecutorService admissionExecutor =
                     java.util.concurrent.Executors.newSingleThreadExecutor()) {
            CountDownLatch planningStarted = new CountDownLatch(1);
            CountDownLatch releasePlanning = new CountDownLatch(1);
            when(fixture.historyService().listRecentCompleteTurnBoundaries(
                    7L, 2)).thenAnswer(invocation -> {
                planningStarted.countDown();
                if (!releasePlanning.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("测试未释放 MySQL 计划读取");
                }
                return List.of(
                        new ChatHistoryService.StableTurnBoundary(
                                1L, 2L, "旧问题", "旧回复"),
                        new ChatHistoryService.StableTurnBoundary(
                                3L, 4L, "新问题", "新回复"));
            });
            Future<ContextAdmissionResult> admission = admissionExecutor.submit(
                    () -> fixture.coordinator().admit(
                            fixture.memory(), List.of()));
            try {
                assertTrue(planningStarted.await(1, TimeUnit.SECONDS),
                        "后台压缩计划读取必须实际开始");

                ContextAdmissionResult result = admission.get(
                        1, TimeUnit.SECONDS);

                assertEquals(ContextCompressionMode.ASYNC_SCHEDULED,
                        result.mode());
                assertTrue(result.canProceed());
                verify(fixture.summaryService(), never())
                        .triggerSummarizationAsync(
                                any(), anyLong(), any(BooleanSupplier.class));
            } finally {
                releasePlanning.countDown();
            }
            verify(fixture.summaryService(), org.mockito.Mockito.timeout(1_000))
                    .triggerSummarizationAsync(
                            eq(7L), eq(2L), any(BooleanSupplier.class));
        }
    }

    @org.junit.jupiter.api.Test
    @SuppressWarnings("unchecked")
    void rejectedAsyncPlanningNeverRunsOnAdmissionThread() {
        try (Fixture fixture = fixture(29_000, 27_000)) {
            ExecutorService rejectingPlanningExecutor =
                    mock(ExecutorService.class);
            when(rejectingPlanningExecutor.submit(any(Runnable.class)))
                    .thenThrow(new RejectedExecutionException("full"));
            ContextCompressionCoordinator coordinator =
                    new ContextCompressionCoordinator(
                            fixture.estimator(), fixture.historyService(),
                            fixture.summaryService(), fixture.properties(),
                            fixture.executor(), fixture.memoryReadExecutor(),
                            rejectingPlanningExecutor,
                            new AppDataLifecycleFence(),
                            fixture.metricsCollector(), System::nanoTime);

            ContextAdmissionResult result = coordinator.admit(
                    fixture.memory(), List.of());

            assertEquals(ContextCompressionMode.NORMAL, result.mode());
            assertTrue(result.canProceed());
            assertTrue(result.detail().contains("执行器已满"));
            verifyNoInteractions(fixture.historyService());
            verify(fixture.summaryService(), never())
                    .triggerSummarizationAsync(
                            any(), anyLong(), any(BooleanSupplier.class));
        }
    }

    @org.junit.jupiter.api.Test
    void revokedTurnAfterAsyncPlanningReadNeverTriggersSummary()
            throws Exception {
        try (Fixture fixture = fixture(29_000, 27_000)) {
            CountDownLatch planningStarted = new CountDownLatch(1);
            CountDownLatch releasePlanning = new CountDownLatch(1);
            when(fixture.historyService().listRecentCompleteTurnBoundaries(
                    7L, 2)).thenAnswer(invocation -> {
                planningStarted.countDown();
                if (!releasePlanning.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("测试未释放 MySQL 计划读取");
                }
                return List.of(
                        new ChatHistoryService.StableTurnBoundary(
                                1L, 2L, "旧问题", "旧回复"),
                        new ChatHistoryService.StableTurnBoundary(
                                3L, 4L, "新问题", "新回复"));
            });
            TestContinuationGate continuationGate =
                    new TestContinuationGate();

            ContextAdmissionResult result = fixture.coordinator().admit(
                    fixture.memory(), List.of(), ignored -> { },
                    continuationGate);
            assertEquals(ContextCompressionMode.ASYNC_SCHEDULED,
                    result.mode());
            assertTrue(planningStarted.await(1, TimeUnit.SECONDS));

            continuationGate.revoke();
            releasePlanning.countDown();

            verify(fixture.summaryService(), org.mockito.Mockito.after(200).never())
                    .triggerSummarizationAsync(
                            any(), anyLong(), any(BooleanSupplier.class));
        }
    }

    @org.junit.jupiter.api.Test
    void l0AndMysqlDifferentTurnCountsReturnTypedAlignmentFailure() {
        try (Fixture fixture = fixture(30_720, 27_000)) {
            when(fixture.historyService().listRecentCompleteTurnBoundaries(
                    7L, 2)).thenReturn(List.of(
                    new ChatHistoryService.StableTurnBoundary(
                            1L, 2L, "旧问题", "旧回复")));

            ContextAdmissionResult result = fixture.coordinator().admit(
                    fixture.memory(), List.of());

            assertEquals(ContextCompressionMode.BLOCKING_FAILED, result.mode());
            assertEquals(ContextAdmissionResult.FailureReason.ALIGNMENT_FAILED,
                    result.failureReason());
            assertFalse(result.canProceed());
            verify(fixture.summaryService(), never()).compressNow(
                    any(), any(Long.class), any(Duration.class));
        }
    }

    @org.junit.jupiter.api.Test
    void l0AndMysqlNonIncreasingOrderReturnsTypedAlignmentFailure() {
        try (Fixture fixture = fixture(30_720, 27_000)) {
            when(fixture.historyService().listRecentCompleteTurnBoundaries(
                    7L, 2)).thenReturn(List.of(
                    new ChatHistoryService.StableTurnBoundary(
                            3L, 4L, "旧问题", "旧回复"),
                    new ChatHistoryService.StableTurnBoundary(
                            1L, 2L, "新问题", "新回复")));

            ContextAdmissionResult result = fixture.coordinator().admit(
                    fixture.memory(), List.of());

            assertEquals(ContextCompressionMode.BLOCKING_FAILED, result.mode());
            assertEquals(ContextAdmissionResult.FailureReason.ALIGNMENT_FAILED,
                    result.failureReason());
            assertFalse(result.canProceed());
            verify(fixture.summaryService(), never()).compressNow(
                    any(), any(Long.class), any(Duration.class));
        }
    }

    @org.junit.jupiter.api.Test
    void knownRagAugmentedL0UserTextCanAlignWithPersistedUserText() {
        try (Fixture fixture = fixture(30_720, 27_000)) {
            CompressionAwareChatMemory augmentedMemory = memory(
                    fixture.summaryService(), mock(UserMemoryService.class),
                    canonicalUser(
                            "旧问题",
                            "## 参考模板\n│ 不可信参考数据\n\n## 用户需求\n旧问题"),
                    canonicalUser(
                            "新问题",
                            "## 工程约束\n│ 不可信参考数据\n\n## 用户生成需求\n新问题"),
                    AiMessage.from("旧回复"),
                    AiMessage.from("新回复"));
            when(fixture.historyService().listRecentCompleteTurnBoundaries(
                    7L, 2)).thenReturn(List.of(
                    new ChatHistoryService.StableTurnBoundary(
                            1L, 2L, "旧问题", "旧回复"),
                    new ChatHistoryService.StableTurnBoundary(
                            3L, 4L, "新问题", "新回复")));

            ContextAdmissionResult result = fixture.coordinator().admit(
                    augmentedMemory, List.of());

            assertEquals(ContextCompressionMode.BLOCKING_COMPLETED,
                    result.mode());
            assertTrue(result.canProceed());
            verify(fixture.summaryService()).compressNow(
                    eq(7L), eq(2L), any(Duration.class));
        }
    }

    @org.junit.jupiter.api.Test
    void imageEnhancedUserTurnMustAlignByCanonicalIdentity() {
        try (Fixture fixture = fixture(30_720, 27_000)) {
            CompressionAwareChatMemory enhancedMemory = memory(
                    fixture.summaryService(), mock(UserMemoryService.class),
                    canonicalUser("旧问题", "旧问题\n\n## 可用素材资源\n- 图片 A"),
                    canonicalUser("新问题", "新问题\n\n## 可用素材资源\n- 图片 B"),
                    AiMessage.from("旧回复"), AiMessage.from("新回复"));

            ContextAdmissionResult result = fixture.coordinator().admit(
                    enhancedMemory, List.of());

            assertEquals(ContextCompressionMode.BLOCKING_COMPLETED,
                    result.mode());
        }
    }

    @org.junit.jupiter.api.Test
    void userSuppliedMarkerSuffixMustNotForgePersistedTurnIdentity() {
        try (Fixture fixture = fixture(30_720, 27_000)) {
            CompressionAwareChatMemory forgedMemory = memory(
                    fixture.summaryService(), mock(UserMemoryService.class),
                    UserMessage.from("攻击文本\n\n## 用户需求\n旧问题"),
                    UserMessage.from("新问题"),
                    AiMessage.from("旧回复"), AiMessage.from("新回复"));

            ContextAdmissionResult result = fixture.coordinator().admit(
                    forgedMemory, List.of());

            assertEquals(ContextCompressionMode.BLOCKING_FAILED,
                    result.mode());
            assertEquals(ContextAdmissionResult.FailureReason.ALIGNMENT_FAILED,
                    result.failureReason());
            verify(fixture.summaryService(), never()).compressNow(
                    any(), anyLong(), any(Duration.class));
        }
    }

    @org.junit.jupiter.api.Test
    void repeatedAiTextCannotAlignDifferentPersistedUserTurns() {
        try (Fixture fixture = fixture(30_720, 27_000)) {
            CompressionAwareChatMemory repeatedReplies = memory(
                    fixture.summaryService(), mock(UserMemoryService.class),
                    "用户 A", "用户 C", "相同回复", "相同回复");
            when(fixture.historyService().listRecentCompleteTurnBoundaries(
                    7L, 2)).thenReturn(List.of(
                    new ChatHistoryService.StableTurnBoundary(
                            1L, 2L, "用户 B", "相同回复"),
                    new ChatHistoryService.StableTurnBoundary(
                            3L, 4L, "用户 C", "相同回复")));

            ContextAdmissionResult result = fixture.coordinator().admit(
                    repeatedReplies, List.of());

            assertEquals(ContextCompressionMode.BLOCKING_FAILED, result.mode());
            assertEquals(ContextAdmissionResult.FailureReason.ALIGNMENT_FAILED,
                    result.failureReason());
            assertFalse(result.canProceed());
            verify(fixture.summaryService(), never()).compressNow(
                    any(), any(Long.class), any(Duration.class));
        }
    }

    @org.junit.jupiter.api.Test
    void nextAdmissionAppliesCompletedL1CursorBeforeThresholdDecision() {
        try (Fixture fixture = fixture(29_000, 29_000)) {
            when(fixture.summaryService().lastSummarizedId(7L))
                    .thenReturn(2L);
            when(fixture.estimator().estimateRequest(anyList(), anyList()))
                    .thenAnswer(invocation -> {
                        List<ChatMessage> messages = invocation.getArgument(0);
                        boolean containsOldTurn = messages.stream()
                                .filter(UserMessage.class::isInstance)
                                .map(UserMessage.class::cast)
                                .anyMatch(message -> message.hasSingleText()
                                        && "旧问题".equals(message.singleText()));
                        return containsOldTurn ? 29_000 : 27_000;
                    });

            ContextAdmissionResult result = fixture.coordinator().admit(
                    fixture.memory(), List.of());

            assertEquals(ContextCompressionMode.NORMAL, result.mode());
            assertEquals(27_000, result.initialTokens());
            assertEquals(List.of("新问题", "新回复"),
                    messageTexts(fixture.memory()
                            .completeTurnSnapshot().completedTurns()
                            .getFirst().messages()));
            verify(fixture.summaryService(), never())
                    .triggerSummarizationAsync(
                            any(), anyLong(), any(BooleanSupplier.class));
            verify(fixture.summaryService(), never()).compressNow(
                    any(), any(Long.class), any(Duration.class));
        }
    }

    @org.junit.jupiter.api.Test
    void 初始游标裁剪等待L0锁超时时返回超时且释放锁后不迟到裁剪()
            throws Exception {
        try (Fixture fixture = fixture(29_000, 29_000);
             ExecutorService lockOwner = java.util.concurrent.Executors
                     .newVirtualThreadPerTaskExecutor()) {
            fixture.properties().setBlockingTimeout(Duration.ofMillis(50L));
            DeadlineMemory deadlineMemory = deadlineMemory(
                    fixture.summaryService(), mock(UserMemoryService.class));
            when(fixture.summaryService().lastSummarizedId(7L))
                    .thenReturn(2L);
            LockBeforeInvocationGate gate = new LockBeforeInvocationGate(
                    deadlineMemory.store(), lockOwner);
            org.mockito.Mockito.doAnswer(invocation -> {
                gate.lockBeforeNextInvocation();
                return VALID_SUMMARY;
            }).when(fixture.summaryService()).getRequiredSummary(7L, 2L);
            List<ChatMessage> before = deadlineMemory.delegate().messages();

            ContextAdmissionResult result = fixture.coordinator().admit(
                    deadlineMemory.memory(), List.of(), ignored -> { }, gate);

            assertEquals(ContextCompressionMode.ADMISSION_FAILED, result.mode());
            assertEquals(ContextAdmissionResult.FailureReason.TIMED_OUT,
                    result.failureReason());
            assertEquals(before, deadlineMemory.delegate().messages());
            gate.releaseAndJoin();
            assertEquals(before, deadlineMemory.delegate().messages());
        }
    }

    @org.junit.jupiter.api.Test
    void coldRebuildAfterL1CursorRequiresStrictSummaryWhenL0HasNoCoveredTurn() {
        try (Fixture fixture = fixture(27_000, 27_000)) {
            List<ChatMessage> coveredPrefix = fixture.memory()
                    .completeTurnSnapshot().completedTurns().getFirst()
                    .messages();
            assertTrue(fixture.memory()
                    .removeCompletedPrefixIfMatches(coveredPrefix));
            when(fixture.summaryService().lastSummarizedId(7L))
                    .thenReturn(2L);
            when(fixture.historyService().listRecentCompleteTurnBoundaries(
                    7L, 1)).thenReturn(List.of(
                    new ChatHistoryService.StableTurnBoundary(
                            3L, 4L, "新问题", "新回复")));
            when(fixture.summaryService().getCurrentSummary(7L))
                    .thenReturn("");

            ContextAdmissionResult result = fixture.coordinator().admit(
                    fixture.memory(), List.of());

            assertEquals(ContextCompressionMode.NORMAL, result.mode());
            assertTrue(result.canProceed());
            assertTrue(result.requestMessages().stream()
                    .filter(UserMessage.class::isInstance)
                    .map(UserMessage.class::cast)
                    .anyMatch(message -> message.hasSingleText()
                            && message.singleText().contains(
                            "# 应用目标与定位")));
            assertTrue(result.requestMessages().stream()
                    .filter(UserMessage.class::isInstance)
                    .map(UserMessage.class::cast)
                    .anyMatch(message -> message.hasSingleText()
                            && "新问题".equals(message.singleText())));
            verify(fixture.summaryService()).getRequiredSummary(7L, 2L);
            verify(fixture.summaryService(), never()).getCurrentSummary(7L);
        }
    }

    @org.junit.jupiter.api.Test
    void cursorReadFailureStopsAdmissionBeforeAnyThresholdDecision() {
        try (Fixture fixture = fixture(28_671, 28_671)) {
            when(fixture.summaryService().lastSummarizedId(7L))
                    .thenThrow(new IllegalStateException("database down"));

            ContextAdmissionResult result = fixture.coordinator().admit(
                    fixture.memory(), List.of());

            assertEquals(ContextCompressionMode.ADMISSION_FAILED, result.mode());
            assertEquals(ContextAdmissionResult.FailureReason.CURSOR_READ_FAILED,
                    result.failureReason());
            assertTrue(result.detail().contains("appId=7"));
            assertEquals(0, result.initialTokens());
            verifyNoInteractions(fixture.historyService());
            verify(fixture.estimator(), never())
                    .estimateRequest(anyList(), anyList());
        }
    }

    @org.junit.jupiter.api.Test
    void initialAlignmentMysqlReadFailureIsDependencyFailure() {
        try (Fixture fixture = fixture(27_000, 27_000)) {
            when(fixture.summaryService().lastSummarizedId(7L))
                    .thenReturn(2L);
            when(fixture.historyService().listRecentCompleteTurnBoundaries(
                    7L, 2)).thenThrow(new IllegalStateException("mysql down"));

            ContextAdmissionResult result = fixture.coordinator().admit(
                    fixture.memory(), List.of());

            assertEquals(ContextCompressionMode.ADMISSION_FAILED, result.mode());
            assertEquals(ContextAdmissionResult.FailureReason.DEPENDENCY_FAILED,
                    result.failureReason());
            assertFalse(result.canProceed());
            verify(fixture.summaryService(), never())
                    .getRequiredSummary(anyLong(), anyLong());
        }
    }

    @org.junit.jupiter.api.Test
    void coldRequestEstimateFailureReturnsTypedDependencyFailure() {
        try (Fixture fixture = fixture(27_000, 27_000)) {
            when(fixture.estimator().estimateRequest(anyList(), anyList()))
                    .thenThrow(new IllegalStateException("tokenizer down"));

            ContextAdmissionResult result = fixture.coordinator().admit(
                    fixture.memory(), List.of());

            assertEquals(ContextCompressionMode.ADMISSION_FAILED, result.mode());
            assertEquals(ContextAdmissionResult.FailureReason.DEPENDENCY_FAILED,
                    result.failureReason());
            assertFalse(result.canProceed());
        }
    }

    @org.junit.jupiter.api.Test
    void initialStrictSummaryReadFailureIsDependencyFailure() {
        try (Fixture fixture = fixture(27_000, 27_000)) {
            when(fixture.summaryService().lastSummarizedId(7L))
                    .thenReturn(2L);
            when(fixture.summaryService().getRequiredSummary(7L, 2L))
                    .thenThrow(new IllegalStateException("database down"));

            ContextAdmissionResult result = fixture.coordinator().admit(
                    fixture.memory(), List.of());

            assertEquals(ContextCompressionMode.ADMISSION_FAILED, result.mode());
            assertEquals(ContextAdmissionResult.FailureReason.DEPENDENCY_FAILED,
                    result.failureReason());
            assertFalse(result.canProceed());
        }
    }

    @org.junit.jupiter.api.Test
    void initialRequestEstimateFailureIsDependencyFailure() {
        try (Fixture fixture = fixture(27_000, 27_000)) {
            when(fixture.summaryService().lastSummarizedId(7L))
                    .thenReturn(2L);
            when(fixture.estimator().estimateRequest(anyList(), anyList()))
                    .thenThrow(new IllegalStateException("tokenizer down"));

            ContextAdmissionResult result = fixture.coordinator().admit(
                    fixture.memory(), List.of());

            assertEquals(ContextCompressionMode.ADMISSION_FAILED, result.mode());
            assertEquals(ContextAdmissionResult.FailureReason.DEPENDENCY_FAILED,
                    result.failureReason());
            assertFalse(result.canProceed());
        }
    }

    @org.junit.jupiter.api.Test
    void initialPrefixChangeIsNotMisreportedAsSummaryReadFailure() {
        try (Fixture fixture = fixture(27_000, 27_000)) {
            when(fixture.summaryService().lastSummarizedId(7L))
                    .thenReturn(2L);
            org.mockito.Mockito.doAnswer(invocation -> {
                List<ChatMessage> completedPrefix = fixture.memory()
                        .completeTurnSnapshot().completedTurns().getFirst()
                        .messages();
                assertTrue(fixture.memory()
                        .removeCompletedPrefixIfMatches(completedPrefix));
                return VALID_SUMMARY;
            }).when(fixture.summaryService()).getRequiredSummary(7L, 2L);

            ContextAdmissionResult result = fixture.coordinator().admit(
                    fixture.memory(), List.of());

            assertEquals(ContextCompressionMode.ADMISSION_FAILED, result.mode());
            assertEquals(ContextAdmissionResult.FailureReason.PREFIX_CHANGED,
                    result.failureReason());
            assertFalse(result.canProceed());
        }
    }

    @ParameterizedTest
    @MethodSource("blockingServiceFailures")
    void blockingServiceFailureIsTypedAndNeverDeletesL0(
            MemoryCompressionResult.Status serviceStatus,
            ContextAdmissionResult.FailureReason expectedReason) {
        try (Fixture fixture = fixture(30_720, 27_000)) {
            doReturn(new MemoryCompressionResult(
                    serviceStatus, 0L, 0, "服务失败"))
                    .when(fixture.summaryService()).compressNow(
                            eq(7L), eq(2L), any(Duration.class));
            List<ContextAdmissionResult> transitions = new ArrayList<>();

            ContextAdmissionResult result = fixture.coordinator().admit(
                    fixture.memory(), List.of(), transitions::add);

            assertEquals(ContextCompressionMode.BLOCKING_FAILED, result.mode());
            assertEquals(expectedReason, result.failureReason());
            assertTrue(result.detail().contains("appId=7"));
            assertTrue(result.detail().contains(serviceStatus.name()));
            assertEquals(4, fixture.memory().completeTurnSnapshot()
                    .completedTurns().stream()
                    .mapToInt(turn -> turn.messages().size()).sum());
            assertEquals(List.of(ContextCompressionMode.BLOCKING_STARTED),
                    transitions.stream().map(ContextAdmissionResult::mode)
                            .toList());
        }
    }

    @org.junit.jupiter.api.Test
    @SuppressWarnings("unchecked")
    void rejectedCompressionTaskNeverFallsBackToAdmissionThread() {
        try (Fixture fixture = fixture(30_720, 27_000)) {
            ExecutorService rejectingExecutor = mock(ExecutorService.class);
            when(rejectingExecutor.submit(
                    any(java.util.concurrent.Callable.class)))
                    .thenThrow(new RejectedExecutionException("full"));
            ContextCompressionCoordinator coordinator =
                    new ContextCompressionCoordinator(
                            fixture.estimator(), fixture.historyService(),
                            fixture.summaryService(), fixture.properties(),
                            rejectingExecutor, new AppDataLifecycleFence(),
                            fixture.metricsCollector());

            ContextAdmissionResult result = coordinator.admit(
                    fixture.memory(), List.of());

            assertEquals(ContextCompressionMode.BLOCKING_FAILED, result.mode());
            assertEquals(
                    ContextAdmissionResult.FailureReason.EXECUTOR_REJECTED,
                    result.failureReason());
            assertTrue(result.detail().contains("appId=7"));
            verify(fixture.summaryService(), never()).compressNow(
                    any(), any(Long.class), any(Duration.class));
            assertEquals(1D, counter(fixture.registry(),
                    "memory_compression_total",
                    "mode", "blocking",
                    "outcome", "executor_rejected").count());
            assertTrue(fixture.registry()
                    .find("memory_compression_duration_seconds")
                    .timers().isEmpty());
        }
    }

    @org.junit.jupiter.api.Test
    void deletionAfterSummaryCompletionNeverRewritesL0() {
        try (Fixture fixture = fixture(30_720, 27_000)) {
            AppDataLifecycleFence lifecycleFence =
                    new AppDataLifecycleFence();
            org.mockito.Mockito.doAnswer(invocation -> {
                AppDataLifecycleFence.DeletePermit deletePermit =
                        lifecycleFence.beginDelete(7L, Duration.ZERO);
                assertNotNull(deletePermit);
                deletePermit.commitTombstone();
                return new MemoryCompressionResult(
                        MemoryCompressionResult.Status.COMPRESSED,
                        2L, 800, "完成");
            }).when(fixture.summaryService()).compressNow(
                    eq(7L), eq(2L), any(Duration.class));
            ContextCompressionCoordinator coordinator =
                    new ContextCompressionCoordinator(
                            fixture.estimator(), fixture.historyService(),
                            fixture.summaryService(), fixture.properties(),
                            fixture.executor(), lifecycleFence,
                            fixture.metricsCollector());
            List<ChatMessage> before = fixture.memory()
                    .completeTurnSnapshot().completedTurns().stream()
                    .flatMap(turn -> turn.messages().stream())
                    .toList();

            ContextAdmissionResult result = coordinator.admit(
                    fixture.memory(), List.of());

            assertEquals(ContextCompressionMode.BLOCKING_FAILED, result.mode());
            assertEquals(ContextAdmissionResult.FailureReason.DELETE_REJECTED,
                    result.failureReason());
            assertFalse(result.canProceed());
            assertEquals(before, fixture.memory()
                    .completeTurnSnapshot().completedTurns().stream()
                    .flatMap(turn -> turn.messages().stream())
                    .toList());
        }
    }

    @org.junit.jupiter.api.Test
    void deletionAlreadyTakingOverStopsBeforeBlockingStarted() {
        try (Fixture fixture = fixture(30_720, 27_000)) {
            AppDataLifecycleFence lifecycleFence =
                    new AppDataLifecycleFence();
            AppDataLifecycleFence.DeletePermit deletePermit =
                    lifecycleFence.beginDelete(7L, Duration.ZERO);
            assertNotNull(deletePermit);
            deletePermit.commitTombstone();
            ContextCompressionCoordinator coordinator =
                    new ContextCompressionCoordinator(
                            fixture.estimator(), fixture.historyService(),
                            fixture.summaryService(), fixture.properties(),
                            fixture.executor(), lifecycleFence,
                            fixture.metricsCollector());
            List<ContextAdmissionResult> transitions = new ArrayList<>();

            ContextAdmissionResult result = coordinator.admit(
                    fixture.memory(), List.of(), transitions::add);

            assertEquals(ContextCompressionMode.ADMISSION_FAILED, result.mode());
            assertEquals(ContextAdmissionResult.FailureReason.DELETE_REJECTED,
                    result.failureReason());
            assertFalse(result.canProceed());
            assertTrue(transitions.isEmpty());
            verify(fixture.summaryService(), never()).lastSummarizedId(7L);
            verify(fixture.summaryService(), never()).compressNow(
                    any(), any(Long.class), any(Duration.class));
            verify(fixture.estimator(), never())
                    .estimateRequest(anyList(), anyList());
        }
    }

    @org.junit.jupiter.api.Test
    void terminatedTurnBeforeAdmissionNeverStartsCompression() {
        try (Fixture fixture = fixture(30_720, 27_000)) {
            List<ContextAdmissionResult> transitions = new ArrayList<>();

            ContextAdmissionResult result = fixture.coordinator().admit(
                    fixture.memory(), List.of(), transitions::add,
                    action -> false);

            assertEquals(ContextCompressionMode.ADMISSION_FAILED, result.mode());
            assertEquals(ContextAdmissionResult.FailureReason.TURN_TERMINATED,
                    result.failureReason());
            assertFalse(result.canProceed());
            assertTrue(transitions.isEmpty());
            verify(fixture.summaryService(), never()).lastSummarizedId(7L);
            verify(fixture.summaryService(), never()).compressNow(
                    any(), any(Long.class), any(Duration.class));
            verify(fixture.estimator(), never())
                    .estimateRequest(anyList(), anyList());
        }
    }

    @org.junit.jupiter.api.Test
    void terminationWhileWaitingNeverReturnsLateCompletionOrTrimsL0() {
        try (Fixture fixture = fixture(30_720, 27_000)) {
            TestContinuationGate continuationGate =
                    new TestContinuationGate();
            org.mockito.Mockito.doAnswer(invocation -> {
                continuationGate.revoke();
                return new MemoryCompressionResult(
                        MemoryCompressionResult.Status.COMPRESSED,
                        2L, 800, "完成");
            }).when(fixture.summaryService()).compressNow(
                    eq(7L), eq(2L), any(Duration.class));
            List<ContextAdmissionResult> transitions = new ArrayList<>();
            List<ChatMessage> before = fixture.memory()
                    .completeTurnSnapshot().completedTurns().stream()
                    .flatMap(turn -> turn.messages().stream())
                    .toList();

            ContextAdmissionResult result = fixture.coordinator().admit(
                    fixture.memory(), List.of(), transitions::add,
                    continuationGate);

            assertEquals(ContextCompressionMode.BLOCKING_FAILED, result.mode());
            assertEquals(ContextAdmissionResult.FailureReason.TURN_TERMINATED,
                    result.failureReason());
            assertFalse(result.canProceed());
            assertEquals(List.of(ContextCompressionMode.BLOCKING_STARTED),
                    transitions.stream().map(ContextAdmissionResult::mode)
                            .toList());
            assertEquals(before, fixture.memory()
                    .completeTurnSnapshot().completedTurns().stream()
                    .flatMap(turn -> turn.messages().stream())
                    .toList());
        }
    }

    @org.junit.jupiter.api.Test
    void terminationDuringCursorReadNeverTrimsCompletedPrefix() {
        try (Fixture fixture = fixture(29_000, 29_000)) {
            TestContinuationGate continuationGate =
                    new TestContinuationGate();
            when(fixture.summaryService().lastSummarizedId(7L))
                    .thenAnswer(invocation -> {
                        continuationGate.revoke();
                        return 2L;
                    });
            List<ChatMessage> before = fixture.memory()
                    .completeTurnSnapshot().completedTurns().stream()
                    .flatMap(turn -> turn.messages().stream())
                    .toList();

            ContextAdmissionResult result = fixture.coordinator().admit(
                    fixture.memory(), List.of(), ignored -> { },
                    continuationGate);

            assertEquals(ContextCompressionMode.ADMISSION_FAILED,
                    result.mode());
            assertEquals(ContextAdmissionResult.FailureReason.TURN_TERMINATED,
                    result.failureReason());
            assertEquals(before, fixture.memory()
                    .completeTurnSnapshot().completedTurns().stream()
                    .flatMap(turn -> turn.messages().stream())
                    .toList());
            verify(fixture.estimator(), never())
                    .estimateRequest(anyList(), anyList());
        }
    }

    @org.junit.jupiter.api.Test
    @SuppressWarnings("unchecked")
    void terminationWhileQueuedNeverStartsCompressionModel() throws Exception {
        try (Fixture fixture = fixture(30_720, 27_000)) {
            ExecutorService queuedExecutor = mock(ExecutorService.class);
            Future<Object> future = mock(Future.class);
            AtomicReference<Callable<Object>> queuedTask =
                    new AtomicReference<>();
            TestContinuationGate continuationGate =
                    new TestContinuationGate();
            when(queuedExecutor.submit(any(Callable.class)))
                    .thenAnswer(invocation -> {
                        queuedTask.set(invocation.getArgument(0));
                        continuationGate.revoke();
                        return future;
                    });
            when(future.get(anyLong(), eq(TimeUnit.NANOSECONDS)))
                    .thenAnswer(invocation -> {
                        try {
                            return queuedTask.get().call();
                        } catch (Exception exception) {
                            throw new ExecutionException(exception);
                        }
                    });
            ContextCompressionCoordinator coordinator =
                    new ContextCompressionCoordinator(
                            fixture.estimator(), fixture.historyService(),
                            fixture.summaryService(), fixture.properties(),
                            queuedExecutor, new AppDataLifecycleFence(),
                            fixture.metricsCollector());

            ContextAdmissionResult result = coordinator.admit(
                    fixture.memory(), List.of(), ignored -> { },
                    continuationGate);

            assertEquals(ContextCompressionMode.BLOCKING_FAILED,
                    result.mode());
            assertEquals(ContextAdmissionResult.FailureReason.TURN_TERMINATED,
                    result.failureReason());
            verify(fixture.summaryService(), never()).compressNow(
                    any(), any(Long.class), any(Duration.class));
        }
    }

    @org.junit.jupiter.api.Test
    @SuppressWarnings("unchecked")
    void queueWaitConsumesWorkerAndCallerAbsoluteDeadline() throws Exception {
        try (Fixture fixture = fixture(30_720, 27_000)) {
            ExecutorService queuedExecutor = mock(ExecutorService.class);
            Future<Object> future = mock(Future.class);
            AtomicReference<Callable<Object>> queuedTask =
                    new AtomicReference<>();
            AtomicReference<Duration> workerTimeout = new AtomicReference<>();
            AtomicLong nanoTime = new AtomicLong();
            long fortySeconds = Duration.ofSeconds(40).toNanos();
            long twentySeconds = Duration.ofSeconds(20).toNanos();
            when(queuedExecutor.submit(any(Callable.class)))
                    .thenAnswer(invocation -> {
                        queuedTask.set(invocation.getArgument(0));
                        nanoTime.set(fortySeconds);
                        return future;
                    });
            when(future.get(anyLong(), eq(TimeUnit.NANOSECONDS)))
                    .thenAnswer(invocation -> {
                        assertEquals(twentySeconds,
                                (long) invocation.getArgument(0));
                        return queuedTask.get().call();
                    });
            org.mockito.Mockito.doAnswer(invocation -> {
                workerTimeout.set(invocation.getArgument(2));
                return new MemoryCompressionResult(
                        MemoryCompressionResult.Status.COMPRESSED,
                        2L, 800, "完成");
            }).when(fixture.summaryService()).compressNow(
                    eq(7L), eq(2L), any(Duration.class));
            ContextCompressionCoordinator coordinator =
                    new ContextCompressionCoordinator(
                            fixture.estimator(), fixture.historyService(),
                            fixture.summaryService(), fixture.properties(),
                            queuedExecutor, new AppDataLifecycleFence(),
                            fixture.metricsCollector(),
                            nanoTime::get);

            ContextAdmissionResult result = coordinator.admit(
                    fixture.memory(), List.of());

            assertEquals(ContextCompressionMode.BLOCKING_COMPLETED,
                    result.mode());
            assertEquals(Duration.ofSeconds(20), workerTimeout.get());
        }
    }

    @org.junit.jupiter.api.Test
    @SuppressWarnings("unchecked")
    void exhaustedQueueDeadlineCancelsBeforeWorkerStarts() throws Exception {
        try (Fixture fixture = fixture(30_720, 27_000)) {
            ExecutorService queuedExecutor = mock(ExecutorService.class);
            Future<Object> future = mock(Future.class);
            AtomicLong nanoTime = new AtomicLong();
            when(queuedExecutor.submit(any(Callable.class)))
                    .thenAnswer(invocation -> {
                        nanoTime.set(Duration.ofSeconds(61).toNanos());
                        return future;
                    });
            ContextCompressionCoordinator coordinator =
                    new ContextCompressionCoordinator(
                            fixture.estimator(), fixture.historyService(),
                            fixture.summaryService(), fixture.properties(),
                            queuedExecutor, new AppDataLifecycleFence(),
                            fixture.metricsCollector(),
                            nanoTime::get);

            ContextAdmissionResult result = coordinator.admit(
                    fixture.memory(), List.of());

            assertEquals(ContextCompressionMode.BLOCKING_FAILED, result.mode());
            assertEquals(ContextAdmissionResult.FailureReason.TIMED_OUT,
                    result.failureReason());
            assertFalse(result.canProceed());
            verify(future).cancel(true);
            verify(future, never()).get(anyLong(), any(TimeUnit.class));
            verify(fixture.summaryService(), never()).compressNow(
                    any(), any(Long.class), any(Duration.class));
        }
    }

    @org.junit.jupiter.api.Test
    @SuppressWarnings("unchecked")
    void completionAfterAbsoluteDeadlineNeverReturnsCompletedMode()
            throws Exception {
        try (Fixture fixture = fixture(30_720, 27_000)) {
            ExecutorService queuedExecutor = mock(ExecutorService.class);
            Future<Object> future = mock(Future.class);
            AtomicReference<Callable<Object>> queuedTask =
                    new AtomicReference<>();
            AtomicLong nanoTime = new AtomicLong();
            when(queuedExecutor.submit(any(Callable.class)))
                    .thenAnswer(invocation -> {
                        queuedTask.set(invocation.getArgument(0));
                        return future;
                    });
            when(future.get(anyLong(), eq(TimeUnit.NANOSECONDS)))
                    .thenAnswer(invocation -> {
                        Object result = queuedTask.get().call();
                        nanoTime.set(Duration.ofSeconds(61).toNanos());
                        return result;
                    });
            ContextCompressionCoordinator coordinator =
                    new ContextCompressionCoordinator(
                            fixture.estimator(), fixture.historyService(),
                            fixture.summaryService(), fixture.properties(),
                            queuedExecutor, new AppDataLifecycleFence(),
                            fixture.metricsCollector(),
                            nanoTime::get);
            List<ChatMessage> before = fixture.memory()
                    .completeTurnSnapshot().completedTurns().stream()
                    .flatMap(turn -> turn.messages().stream())
                    .toList();

            ContextAdmissionResult result = coordinator.admit(
                    fixture.memory(), List.of());

            assertEquals(ContextCompressionMode.BLOCKING_FAILED, result.mode());
            assertEquals(ContextAdmissionResult.FailureReason.TIMED_OUT,
                    result.failureReason());
            assertFalse(result.canProceed());
            verify(future).cancel(true);
            assertEquals(before, fixture.memory()
                    .completeTurnSnapshot().completedTurns().stream()
                    .flatMap(turn -> turn.messages().stream())
                    .toList());
        }
    }

    @org.junit.jupiter.api.Test
    void deadlineBeforeFinalPrefixCommitDoesNotTrimL0OrReturnCompletion() {
        try (Fixture fixture = fixture(30_720, 27_000)) {
            AtomicLong nanoTime = new AtomicLong();
            ContextContinuationGate continuationGate =
                    new DeadlineAdvancingGate(
                            nanoTime, 7,
                            Duration.ofSeconds(61).toNanos());
            ContextCompressionCoordinator coordinator =
                    new ContextCompressionCoordinator(
                            fixture.estimator(), fixture.historyService(),
                            fixture.summaryService(), fixture.properties(),
                            fixture.executor(), new AppDataLifecycleFence(),
                            fixture.metricsCollector(),
                            nanoTime::get);
            List<ChatMessage> before = fixture.memory()
                    .completeTurnSnapshot().completedTurns().stream()
                    .flatMap(turn -> turn.messages().stream())
                    .toList();

            ContextAdmissionResult result = coordinator.admit(
                    fixture.memory(), List.of(), ignored -> { },
                    continuationGate);

            assertEquals(ContextCompressionMode.BLOCKING_FAILED,
                    result.mode());
            assertEquals(ContextAdmissionResult.FailureReason.TIMED_OUT,
                    result.failureReason());
            assertFalse(result.canProceed());
            assertEquals(before, fixture.memory()
                    .completeTurnSnapshot().completedTurns().stream()
                    .flatMap(turn -> turn.messages().stream())
                    .toList());
        }
    }

    @org.junit.jupiter.api.Test
    void 阻塞最终裁剪等待L0锁超时时返回超时且删除不能越过writer许可()
            throws Exception {
        try (Fixture fixture = fixture(30_720, 27_000);
             ExecutorService lockOwner = java.util.concurrent.Executors
                     .newVirtualThreadPerTaskExecutor();
             ExecutorService admissionExecutor = java.util.concurrent.Executors
                     .newVirtualThreadPerTaskExecutor()) {
            fixture.properties().setBlockingTimeout(Duration.ofSeconds(1L));
            DeadlineMemory deadlineMemory = deadlineMemory(
                    fixture.summaryService(), mock(UserMemoryService.class));
            AppDataLifecycleFence lifecycleFence = new AppDataLifecycleFence();
            ContextCompressionCoordinator coordinator =
                    new ContextCompressionCoordinator(
                            fixture.estimator(), fixture.historyService(),
                            fixture.summaryService(), fixture.properties(),
                            fixture.executor(), lifecycleFence,
                            fixture.metricsCollector());
            LockBeforeInvocationGate gate = new LockBeforeInvocationGate(
                    deadlineMemory.store(), lockOwner);
            org.mockito.Mockito.doAnswer(invocation -> {
                gate.lockBeforeNextInvocation();
                return VALID_SUMMARY;
            }).when(fixture.summaryService()).getRequiredSummary(7L, 2L);
            List<ChatMessage> before = deadlineMemory.delegate().messages();
            Future<ContextAdmissionResult> admission = admissionExecutor.submit(
                    () -> coordinator.admit(deadlineMemory.memory(), List.of(),
                            ignored -> { }, gate));
            assertTrue(gate.actionEntered().await(1L, TimeUnit.SECONDS));
            assertTrue(awaitCondition(
                    () -> deadlineMemory.store()
                            .registeredReferenceCount(7L) == 2,
                    Duration.ofMillis(500L)),
                    "最终 CAS 必须已持有 writer 许可并进入本地锁等待");

            assertEquals(null, lifecycleFence.beginDelete(7L, Duration.ZERO),
                    "最终 CAS 等待期间 writer 许可必须阻止删除越过");
            ContextAdmissionResult result = admission.get(
                    2L, TimeUnit.SECONDS);

            assertEquals(ContextCompressionMode.BLOCKING_FAILED, result.mode());
            assertEquals(ContextAdmissionResult.FailureReason.TIMED_OUT,
                    result.failureReason());
            assertEquals(before, deadlineMemory.delegate().messages());
            gate.releaseAndJoin();
            assertEquals(before, deadlineMemory.delegate().messages());
        }
    }

    @org.junit.jupiter.api.Test
    void deadlineDuringFinalRequestEstimateNeverReturnsCompletedMode() {
        try (Fixture fixture = fixture(30_720, 27_000)) {
            AtomicLong nanoTime = new AtomicLong();
            AtomicLong estimationCount = new AtomicLong();
            when(fixture.estimator().estimateRequest(anyList(), anyList()))
                    .thenAnswer(invocation -> {
                        if (estimationCount.incrementAndGet() == 2L) {
                            nanoTime.set(Duration.ofSeconds(61).toNanos());
                            return 27_000;
                        }
                        return 30_720;
                    });
            ContextCompressionCoordinator coordinator =
                    new ContextCompressionCoordinator(
                            fixture.estimator(), fixture.historyService(),
                            fixture.summaryService(), fixture.properties(),
                            fixture.executor(), new AppDataLifecycleFence(),
                            fixture.metricsCollector(),
                            nanoTime::get);

            ContextAdmissionResult result = coordinator.admit(
                    fixture.memory(), List.of());

            assertEquals(ContextCompressionMode.BLOCKING_FAILED,
                    result.mode());
            assertEquals(ContextAdmissionResult.FailureReason.TIMED_OUT,
                    result.failureReason());
            assertFalse(result.canProceed());
            assertEquals(2, fixture.memory().completeTurnSnapshot()
                    .completedTurns().size());
        }
    }

    @org.junit.jupiter.api.Test
    void strictSummaryBlankNeverTrimsL0() {
        try (Fixture fixture = fixture(30_720, 27_000)) {
            when(fixture.summaryService().getRequiredSummary(7L, 2L))
                    .thenReturn(" ");
            List<ChatMessage> before = fixture.memory().completeTurnSnapshot()
                    .completedTurns().stream()
                    .flatMap(turn -> turn.messages().stream())
                    .toList();

            ContextAdmissionResult result = fixture.coordinator().admit(
                    fixture.memory(), List.of());

            assertEquals(ContextCompressionMode.BLOCKING_FAILED, result.mode());
            assertFalse(result.canProceed());
            assertEquals(before, fixture.memory().completeTurnSnapshot()
                    .completedTurns().stream()
                    .flatMap(turn -> turn.messages().stream())
                    .toList());
        }
    }

    @org.junit.jupiter.api.Test
    void strictSummaryInvalidFormatNeverTrimsL0() {
        try (Fixture fixture = fixture(30_720, 27_000)) {
            when(fixture.summaryService().getRequiredSummary(7L, 2L))
                    .thenReturn("不是五段式摘要");
            List<ChatMessage> before = fixture.memory().completeTurnSnapshot()
                    .completedTurns().stream()
                    .flatMap(turn -> turn.messages().stream())
                    .toList();

            ContextAdmissionResult result = fixture.coordinator().admit(
                    fixture.memory(), List.of());

            assertEquals(ContextCompressionMode.BLOCKING_FAILED, result.mode());
            assertEquals(before, fixture.memory().completeTurnSnapshot()
                    .completedTurns().stream()
                    .flatMap(turn -> turn.messages().stream())
                    .toList());
        }
    }

    @org.junit.jupiter.api.Test
    void strictSummaryReadExceptionNeverTrimsL0() {
        try (Fixture fixture = fixture(30_720, 27_000)) {
            when(fixture.summaryService().getRequiredSummary(7L, 2L))
                    .thenThrow(new IllegalStateException("database down"));
            List<ChatMessage> before = fixture.memory().completeTurnSnapshot()
                    .completedTurns().stream()
                    .flatMap(turn -> turn.messages().stream())
                    .toList();

            ContextAdmissionResult result = fixture.coordinator().admit(
                    fixture.memory(), List.of());

            assertEquals(ContextCompressionMode.BLOCKING_FAILED, result.mode());
            assertEquals(ContextAdmissionResult.FailureReason.DEPENDENCY_FAILED,
                    result.failureReason());
            assertEquals(before, fixture.memory().completeTurnSnapshot()
                    .completedTurns().stream()
                    .flatMap(turn -> turn.messages().stream())
                    .toList());
        }
    }

    @org.junit.jupiter.api.Test
    void finalRequestEstimateExceptionIsDependencyFailureNotPrefixChange() {
        try (Fixture fixture = fixture(30_720, 27_000)) {
            when(fixture.estimator().estimateRequest(anyList(), anyList()))
                    .thenReturn(30_720)
                    .thenThrow(new IllegalStateException("tokenizer down"));

            ContextAdmissionResult result = fixture.coordinator().admit(
                    fixture.memory(), List.of());

            assertEquals(ContextCompressionMode.BLOCKING_FAILED, result.mode());
            assertEquals(ContextAdmissionResult.FailureReason.DEPENDENCY_FAILED,
                    result.failureReason());
            assertEquals(2, fixture.memory().completeTurnSnapshot()
                    .completedTurns().size());
        }
    }

    @org.junit.jupiter.api.Test
    void concurrentAppendBetweenPreparationAndCasReturnsPrefixChanged() {
        try (Fixture fixture = fixture(30_720, 27_000)) {
            AtomicLong estimates = new AtomicLong();
            when(fixture.estimator().estimateRequest(anyList(), anyList()))
                    .thenAnswer(invocation -> {
                        if (estimates.incrementAndGet() == 2L) {
                            fixture.memory().add(UserMessage.from("并发新问题"));
                            return 27_000;
                        }
                        return 30_720;
                    });

            ContextAdmissionResult result = fixture.coordinator().admit(
                    fixture.memory(), List.of());

            assertEquals(ContextCompressionMode.BLOCKING_FAILED, result.mode());
            assertEquals(ContextAdmissionResult.FailureReason.PREFIX_CHANGED,
                    result.failureReason());
            assertEquals("并发新问题", ((UserMessage) fixture.memory()
                    .completeTurnSnapshot().unfinishedTail().getFirst())
                    .singleText());
            assertEquals(2, fixture.memory().completeTurnSnapshot()
                    .completedTurns().size());
        }
    }

    @org.junit.jupiter.api.Test
    void deadlineCrossingDuringSuccessfulCasMustNotReturnFailureWithTrimmedL0() {
        try (Fixture fixture = fixture(30_720, 27_000)) {
            AtomicLong now = new AtomicLong();
            ContextCompressionCoordinator coordinator =
                    new ContextCompressionCoordinator(
                            fixture.estimator(), fixture.historyService(),
                            fixture.summaryService(), fixture.properties(),
                            fixture.executor(), new AppDataLifecycleFence(),
                            fixture.metricsCollector(), () -> {
                                if (fixture.memory().completeTurnSnapshot()
                                        .completedTurns().size() == 1) {
                                    return Duration.ofSeconds(61).toNanos();
                                }
                                return now.get();
                            });

            ContextAdmissionResult result = coordinator.admit(
                    fixture.memory(), List.of());

            assertEquals(ContextCompressionMode.BLOCKING_COMPLETED,
                    result.mode());
            assertTrue(result.canProceed());
            assertEquals(1, fixture.memory().completeTurnSnapshot()
                    .completedTurns().size());
        }
    }

    @org.junit.jupiter.api.Test
    void strictMemoryReadTimeoutReturnsWithoutTrimmingL0() throws Exception {
        try (Fixture fixture = fixture(30_720, 27_000);
             ExecutorService caller = java.util.concurrent.Executors
                     .newVirtualThreadPerTaskExecutor()) {
            fixture.properties().setBlockingTimeout(Duration.ofMillis(50));
            java.util.concurrent.CountDownLatch readStarted =
                    new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch releaseRead =
                    new java.util.concurrent.CountDownLatch(1);
            when(fixture.summaryService().getRequiredSummary(7L, 2L))
                    .thenAnswer(invocation -> {
                        readStarted.countDown();
                        boolean interrupted = false;
                        while (true) {
                            try {
                                if (releaseRead.await(
                                        1, TimeUnit.SECONDS)) {
                                    break;
                                }
                            } catch (InterruptedException exception) {
                                interrupted = true;
                            }
                        }
                        if (interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        return VALID_SUMMARY;
                    });
            List<ChatMessage> before = fixture.memory().completeTurnSnapshot()
                    .completedTurns().stream()
                    .flatMap(turn -> turn.messages().stream())
                    .toList();
            Future<ContextAdmissionResult> admission = caller.submit(() ->
                    fixture.coordinator().admit(fixture.memory(), List.of()));
            assertTrue(readStarted.await(1, TimeUnit.SECONDS));

            try {
                ContextAdmissionResult result = admission.get(
                        500, TimeUnit.MILLISECONDS);
                assertEquals(ContextCompressionMode.BLOCKING_FAILED,
                        result.mode());
                assertEquals(ContextAdmissionResult.FailureReason.TIMED_OUT,
                        result.failureReason());
                assertEquals(before, fixture.memory().completeTurnSnapshot()
                        .completedTurns().stream()
                        .flatMap(turn -> turn.messages().stream())
                        .toList());
            } finally {
                releaseRead.countDown();
            }
        }
    }

    @org.junit.jupiter.api.Test
    @SuppressWarnings("unchecked")
    void interruptedWaitCancelsFutureAndNeverReturnsCompletion()
            throws Exception {
        try (Fixture fixture = fixture(30_720, 27_000)) {
            ExecutorService executor = mock(ExecutorService.class);
            Future<Object> future = mock(Future.class);
            when(executor.submit(any(Callable.class))).thenReturn(future);
            when(future.get(anyLong(), eq(TimeUnit.NANOSECONDS)))
                    .thenThrow(new InterruptedException("已取消"));
            ContextCompressionCoordinator coordinator =
                    new ContextCompressionCoordinator(
                            fixture.estimator(), fixture.historyService(),
                            fixture.summaryService(), fixture.properties(),
                            executor, new AppDataLifecycleFence(),
                            fixture.metricsCollector());
            List<ContextAdmissionResult> transitions = new ArrayList<>();
            Thread.interrupted();
            try {
                ContextAdmissionResult result = coordinator.admit(
                        fixture.memory(), List.of(), transitions::add);

                assertEquals(ContextCompressionMode.BLOCKING_FAILED,
                        result.mode());
                assertEquals(ContextAdmissionResult.FailureReason.INTERRUPTED,
                        result.failureReason());
                assertFalse(result.canProceed());
                assertTrue(Thread.currentThread().isInterrupted());
                assertEquals(List.of(ContextCompressionMode.BLOCKING_STARTED),
                        transitions.stream().map(ContextAdmissionResult::mode)
                                .toList());
                verify(future).cancel(true);
                verify(fixture.summaryService(), never()).compressNow(
                        any(), any(Long.class), any(Duration.class));
            } finally {
                Thread.interrupted();
            }
        }
    }

    private static Stream<Arguments> exactThresholds() {
        return Stream.of(
                Arguments.of(28_671, ContextCompressionMode.NORMAL,
                        false, false),
                Arguments.of(28_672, ContextCompressionMode.ASYNC_SCHEDULED,
                        true, false),
                Arguments.of(30_719, ContextCompressionMode.ASYNC_SCHEDULED,
                        true, false),
                Arguments.of(30_720, ContextCompressionMode.BLOCKING_COMPLETED,
                        false, true),
                Arguments.of(32_767, ContextCompressionMode.BLOCKING_COMPLETED,
                        false, true),
                Arguments.of(32_768, ContextCompressionMode.BLOCKING_COMPLETED,
                        false, true));
    }

    private static Stream<Arguments> postCompressionIntervals() {
        return Stream.of(
                Arguments.of(27_000,
                        ContextCompressionMode.BLOCKING_COMPLETED, true),
                Arguments.of(29_000,
                        ContextCompressionMode.BLOCKING_COMPLETED, true),
                Arguments.of(31_000,
                        ContextCompressionMode.BLOCKING_COMPLETED, true),
                Arguments.of(32_768,
                        ContextCompressionMode.HARD_LIMIT_REJECTED, false));
    }

    private static Stream<Arguments> blockingServiceFailures() {
        return Stream.of(
                Arguments.of(MemoryCompressionResult.Status.MODEL_FAILED,
                        ContextAdmissionResult.FailureReason.MODEL_FAILED),
                Arguments.of(MemoryCompressionResult.Status.TIMED_OUT,
                        ContextAdmissionResult.FailureReason.TIMED_OUT),
                Arguments.of(MemoryCompressionResult.Status.DELETE_REJECTED,
                        ContextAdmissionResult.FailureReason.DELETE_REJECTED));
    }

    private Fixture fixture(int initialTokens, int compressedTokens) {
        ChatTokenEstimator estimator = mock(ChatTokenEstimator.class);
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        MemorySummaryService summaryService = mock(MemorySummaryService.class);
        UserMemoryService userMemoryService = mock(UserMemoryService.class);
        MemoryTokenProperties properties = new MemoryTokenProperties();
        ExecutorService executor = java.util.concurrent.Executors
                .newSingleThreadExecutor();
        ExecutorService memoryReadExecutor =
                java.util.concurrent.Executors.newFixedThreadPool(2);
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(
                PrometheusConfig.DEFAULT);
        CompressionAwareChatMemory memory = memory(
                summaryService, userMemoryService);
        when(estimator.estimateRequest(anyList(), anyList()))
                .thenReturn(initialTokens, compressedTokens);
        when(estimator.estimateMessages(anyList())).thenAnswer(invocation -> {
            List<ChatMessage> messages = invocation.getArgument(0);
            String userText = ((UserMessage) messages.getFirst()).singleText();
            return "旧问题".equals(userText) ? 13_000 : 12_000;
        });
        when(summaryService.lastSummarizedId(7L)).thenReturn(0L);
        when(historyService.listRecentCompleteTurnBoundaries(7L, 2))
                .thenReturn(List.of(
                        new ChatHistoryService.StableTurnBoundary(
                                1L, 2L, "旧问题", "旧回复"),
                        new ChatHistoryService.StableTurnBoundary(
                                3L, 4L, "新问题", "新回复")));
        when(summaryService.compressNow(
                eq(7L), eq(2L), any(Duration.class)))
                .thenReturn(new MemoryCompressionResult(
                        MemoryCompressionResult.Status.COMPRESSED,
                        2L, 800, "完成"));
        when(summaryService.getRequiredSummary(7L, 2L))
                .thenReturn(VALID_SUMMARY);
        MemoryCompressionMetricsCollector metricsCollector =
                new MemoryCompressionMetricsCollector(registry);
        ContextCompressionCoordinator coordinator =
                new ContextCompressionCoordinator(
                estimator, historyService, summaryService,
                properties, executor, memoryReadExecutor,
                new AppDataLifecycleFence(),
                metricsCollector);
        return new Fixture(coordinator, memory, summaryService,
                historyService, estimator, properties, executor,
                memoryReadExecutor, metricsCollector, registry);
    }

    private CompressionAwareChatMemory memory(
            MemorySummaryService summaryService,
            UserMemoryService userMemoryService) {
        return memory(summaryService, userMemoryService,
                "旧问题", "新问题");
    }

    private CompressionAwareChatMemory memory(
            MemorySummaryService summaryService,
            UserMemoryService userMemoryService,
            String firstUserText,
            String secondUserText) {
        return memory(summaryService, userMemoryService,
                firstUserText, secondUserText, "旧回复", "新回复");
    }

    private CompressionAwareChatMemory memory(
            MemorySummaryService summaryService,
            UserMemoryService userMemoryService,
            String firstUserText,
            String secondUserText,
            String firstAiText,
            String secondAiText) {
        MessageWindowChatMemory delegate = MessageWindowChatMemory.builder()
                .id(7L)
                .maxMessages(Integer.MAX_VALUE)
                .build();
        delegate.add(UserMessage.from(firstUserText));
        delegate.add(AiMessage.from(firstAiText));
        delegate.add(UserMessage.from(secondUserText));
        delegate.add(AiMessage.from(secondAiText));
        return new CompressionAwareChatMemory(
                new TokenAwareChatMemory(delegate),
                summaryService,
                userMemoryService);
    }

    private CompressionAwareChatMemory memory(
            MemorySummaryService summaryService,
            UserMemoryService userMemoryService,
            UserMessage firstUser,
            UserMessage secondUser,
            AiMessage firstAi,
            AiMessage secondAi) {
        MessageWindowChatMemory delegate = MessageWindowChatMemory.builder()
                .id(7L)
                .maxMessages(Integer.MAX_VALUE)
                .build();
        delegate.add(firstUser);
        delegate.add(firstAi);
        delegate.add(secondUser);
        delegate.add(secondAi);
        return new CompressionAwareChatMemory(
                new TokenAwareChatMemory(delegate),
                summaryService,
                userMemoryService);
    }

    private UserMessage canonicalUser(String canonical, String enhanced) {
        return UserMessage.from(
                TokenAwareChatMemory.canonicalUserName(canonical), enhanced);
    }

    private DeadlineMemory deadlineMemory(
            MemorySummaryService summaryService,
            UserMemoryService userMemoryService) {
        List<ChatMessage> messages = List.of(
                UserMessage.from("旧问题"), AiMessage.from("旧回复"),
                UserMessage.from("新问题"), AiMessage.from("新回复"));
        InMemoryDeadlineStore delegate = new InMemoryDeadlineStore(messages);
        AtomicChatMemoryStore store = new AtomicChatMemoryStore(delegate);
        MessageWindowChatMemory window = MessageWindowChatMemory.builder()
                .id(7L).chatMemoryStore(store)
                .maxMessages(Integer.MAX_VALUE).build();
        return new DeadlineMemory(new CompressionAwareChatMemory(
                new TokenAwareChatMemory(window, store),
                summaryService, userMemoryService), store, delegate);
    }

    private List<String> messageTexts(List<ChatMessage> messages) {
        return messages.stream()
                .map(message -> message instanceof UserMessage userMessage
                        ? userMessage.singleText()
                        : ((AiMessage) message).text())
                .toList();
    }

    private Counter counter(
            PrometheusMeterRegistry registry,
            String name,
            String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        assertNotNull(counter, () -> "缺少 Counter：" + name);
        return counter;
    }

    private DistributionSummary summary(
            PrometheusMeterRegistry registry,
            String name,
            String... tags) {
        DistributionSummary summary = registry.find(name)
                .tags(tags)
                .summary();
        assertNotNull(summary, () -> "缺少 DistributionSummary：" + name);
        return summary;
    }

    /** 与回合终态使用同一把监视器，确定性模拟 callback gate 的胜负。 */
    private static final class TestContinuationGate
            implements ContextContinuationGate {

        private boolean open = true;

        @Override
        public synchronized boolean tryRun(Runnable action) {
            if (!open) {
                return false;
            }
            action.run();
            return true;
        }

        private synchronized void revoke() {
            open = false;
        }
    }

    private static final class DeadlineAdvancingGate
            implements ContextContinuationGate {

        private final AtomicLong nanoTime;
        private final int advanceOnInvocation;
        private final long advancedTime;
        private int invocations;

        private DeadlineAdvancingGate(
                AtomicLong nanoTime,
                int advanceOnInvocation,
                long advancedTime) {
            this.nanoTime = nanoTime;
            this.advanceOnInvocation = advanceOnInvocation;
            this.advancedTime = advancedTime;
        }

        @Override
        public synchronized boolean tryRun(Runnable action) {
            invocations++;
            if (invocations == advanceOnInvocation) {
                nanoTime.set(advancedTime);
            }
            action.run();
            return true;
        }
    }

    private static final class LockBeforeInvocationGate
            implements ContextContinuationGate {

        private final AtomicChatMemoryStore store;
        private final ExecutorService executor;
        private final CountDownLatch actionEntered = new CountDownLatch(1);
        private final CountDownLatch lockAcquired = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private Future<?> owner;
        private boolean lockNextInvocation;

        private LockBeforeInvocationGate(
                AtomicChatMemoryStore store,
                ExecutorService executor) {
            this.store = store;
            this.executor = executor;
        }

        @Override
        public synchronized boolean tryRun(Runnable action) {
            if (lockNextInvocation) {
                lockNextInvocation = false;
                owner = executor.submit(() -> store.withMemoryLock(7L, () -> {
                    lockAcquired.countDown();
                    awaitTestLatch(release, Duration.ofSeconds(2L));
                }));
                awaitTestLatch(lockAcquired);
                actionEntered.countDown();
            }
            action.run();
            return true;
        }

        private CountDownLatch actionEntered() {
            return actionEntered;
        }

        private synchronized void lockBeforeNextInvocation() {
            lockNextInvocation = true;
        }

        private void releaseAndJoin() throws Exception {
            release.countDown();
            if (owner != null) {
                owner.get(1L, TimeUnit.SECONDS);
            }
        }
    }

    private static final class InMemoryDeadlineStore
            implements ChatMemoryStore, DeadlineAwareChatMemoryStore {

        private List<ChatMessage> messages;

        private InMemoryDeadlineStore(List<ChatMessage> messages) {
            this.messages = List.copyOf(messages);
        }

        @Override
        public synchronized List<ChatMessage> getMessages(Object memoryId) {
            return messages;
        }

        @Override
        public synchronized void updateMessages(
                Object memoryId, List<ChatMessage> updated) {
            messages = List.copyOf(updated);
        }

        @Override
        public synchronized void deleteMessages(Object memoryId) {
            messages = List.of();
        }

        @Override
        public Duration worstCaseCommitDuration() {
            return Duration.ZERO;
        }

        @Override
        public synchronized DeadlineAwareReplaceResult
                replaceMessagesIfMatches(
                        Object memoryId,
                        List<ChatMessage> expected,
                        List<ChatMessage> replacement,
                        AdmissionDeadline deadline) {
            if (!messages.equals(expected)) {
                return DeadlineAwareReplaceResult.PREFIX_CHANGED;
            }
            messages = List.copyOf(replacement);
            return DeadlineAwareReplaceResult.REPLACED;
        }

        private synchronized List<ChatMessage> messages() {
            return messages;
        }
    }

    private static void awaitTestLatch(CountDownLatch latch) {
        awaitTestLatch(latch, Duration.ofSeconds(1L));
    }

    private static void awaitTestLatch(
            CountDownLatch latch, Duration timeout) {
        try {
            if (!latch.await(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                throw new AssertionError("等待测试闩锁超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待测试闩锁被中断", exception);
        }
    }

    private static boolean awaitCondition(
            BooleanSupplier condition, Duration timeout) {
        long startedAt = System.nanoTime();
        long timeoutNanos = timeout.toNanos();
        while (System.nanoTime() - startedAt < timeoutNanos) {
            if (condition.getAsBoolean()) {
                return true;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(
                    Duration.ofMillis(1L).toNanos());
        }
        return condition.getAsBoolean();
    }

    private record DeadlineMemory(
            CompressionAwareChatMemory memory,
            AtomicChatMemoryStore store,
            InMemoryDeadlineStore delegate) {
    }

    private record Fixture(
            ContextCompressionCoordinator coordinator,
            CompressionAwareChatMemory memory,
            MemorySummaryService summaryService,
            ChatHistoryService historyService,
            ChatTokenEstimator estimator,
            MemoryTokenProperties properties,
            ExecutorService executor,
            ExecutorService memoryReadExecutor,
            MemoryCompressionMetricsCollector metricsCollector,
            PrometheusMeterRegistry registry) implements AutoCloseable {

        @Override
        public void close() {
            executor.shutdownNow();
            memoryReadExecutor.shutdownNow();
            registry.close();
        }
    }
}
