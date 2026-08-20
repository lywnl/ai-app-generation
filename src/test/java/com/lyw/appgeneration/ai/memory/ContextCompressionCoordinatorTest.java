package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.monitor.MemoryCompressionMetricsCollector;
import com.lyw.appgeneration.monitor.ThrowingMeterRegistry;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemoryCompressionResult;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.UserMemoryService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
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
import java.util.Arrays;
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

    @org.junit.jupiter.api.Test
    void 阻塞压缩只以真实记忆准备和写回临时消息仅留在最终请求快照() {
        try (Fixture fixture = fixture(30_720, 27_000)) {
            CompressionAwareChatMemory memory = org.mockito.Mockito.mock(
                    CompressionAwareChatMemory.class,
                    org.mockito.Mockito.withSettings()
                            .spiedInstance(fixture.memory())
                            .defaultAnswer(org.mockito.Mockito
                                    .CALLS_REAL_METHODS)
                            .mockMaker(org.mockito.MockMakers.INLINE));
            SystemMessage transientMessage =
                    SystemMessage.from("仅本次请求可见的临时系统消息");
            org.mockito.ArgumentCaptor<List<ChatMessage>> prefixCaptor =
                    org.mockito.ArgumentCaptor.forClass(List.class);
            org.mockito.ArgumentCaptor<LayeredChatMemory.PreparedLayeredMessages>
                    preparedCaptor = org.mockito.ArgumentCaptor.forClass(
                    LayeredChatMemory.PreparedLayeredMessages.class);

            ContextAdmissionResult result = fixture.coordinator().admit(
                    memory, List.of(), List.of(transientMessage),
                    ignored -> { }, ContextContinuationGate.alwaysOpen());

            verify(memory).prepareAfterCompletedPrefix(
                    prefixCaptor.capture(), eq(VALID_SUMMARY));
            verify(memory).applyPreparedPrefix(
                    preparedCaptor.capture(), any(AdmissionDeadline.class));
            assertFalse(prefixCaptor.getValue().contains(transientMessage));
            assertFalse(preparedCaptor.getValue().l0Snapshot()
                    .contains(transientMessage));
            assertFalse(preparedCaptor.getValue().retainedL0()
                    .contains(transientMessage));
            assertFalse(preparedCaptor.getValue().requestMessages()
                    .contains(transientMessage));
            assertEquals(ContextCompressionMode.BLOCKING_COMPLETED,
                    result.mode());
            assertEquals(transientMessage, result.requestMessages().getLast());
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
    @MethodSource("transientThresholds")
    void 临时消息参与三段精确阈值且不进入真实记忆(
            int completeRequestTokens,
            ContextCompressionMode expectedMode) {
        try (Fixture fixture = fixture(1_000, 27_000)) {
            SystemMessage transientMessage =
                    SystemMessage.from("仅用于本次模型请求");
            AtomicLong estimates = new AtomicLong();
            when(fixture.estimator().estimateRequest(anyList(), anyList()))
                    .thenAnswer(invocation -> {
                        List<ChatMessage> messages = invocation.getArgument(0);
                        assertEquals(transientMessage, messages.getLast());
                        return estimates.getAndIncrement() == 0
                                ? completeRequestTokens : 27_000;
                    });

            ContextAdmissionResult result = fixture.coordinator().admit(
                    fixture.memory(), List.of(), List.of(transientMessage),
                    ignored -> { }, ContextContinuationGate.alwaysOpen());

            assertEquals(expectedMode, result.mode());
            assertEquals(completeRequestTokens, result.initialTokens());
            assertEquals(transientMessage, result.requestMessages().getLast());
            assertFalse(fixture.memory().messages().contains(transientMessage),
                    "临时消息不得进入真实 ChatMemory");
        }
    }

    @org.junit.jupiter.api.Test
    void 压缩后完整快照仍超硬上限时拒绝并保留临时消息尾部() {
        try (Fixture fixture = fixture(30_720, 32_768)) {
            SystemMessage transientMessage =
                    SystemMessage.from("压缩后仍参与复检");
            org.mockito.ArgumentCaptor<List<ChatMessage>> messagesCaptor =
                    org.mockito.ArgumentCaptor.forClass(List.class);

            ContextAdmissionResult result = fixture.coordinator().admit(
                    fixture.memory(), List.of(), List.of(transientMessage),
                    ignored -> { }, ContextContinuationGate.alwaysOpen());

            assertEquals(ContextCompressionMode.HARD_LIMIT_REJECTED,
                    result.mode());
            assertEquals(32_768, result.finalTokens());
            assertEquals(transientMessage, result.requestMessages().getLast());
            verify(fixture.estimator(), org.mockito.Mockito.atLeast(2))
                    .estimateRequest(messagesCaptor.capture(), eq(List.of()));
            assertTrue(messagesCaptor.getAllValues().stream()
                    .allMatch(messages -> messages.getLast()
                            .equals(transientMessage)));
            assertFalse(fixture.memory().messages().contains(transientMessage));
        }
    }

    @org.junit.jupiter.api.Test
    void 初始回合门关闭时不再读取真实记忆或临时消息() {
        try (Fixture fixture = fixture(1_000, 1_000)) {
            SystemMessage transientMessage =
                    SystemMessage.from("终止时仍需保留");
            TestContinuationGate continuationGate =
                    new TestContinuationGate();
            continuationGate.revoke();

            ContextAdmissionResult result = fixture.coordinator().admit(
                    fixture.memory(), List.of(), List.of(transientMessage),
                    ignored -> { }, continuationGate);

            assertEquals(
                    ContextAdmissionResult.FailureReason.TURN_TERMINATED,
                    result.failureReason());
            assertTrue(result.requestMessages().isEmpty());
            verify(fixture.estimator(), never())
                    .estimateRequest(anyList(), anyList());
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
    void 阻塞阈值没有可压缩旧回合时允许当前请求继续() {
        try (Fixture fixture = fixture(30_720, 27_000)) {
            doReturn(6_000).when(fixture.estimator())
                    .estimateMessages(anyList());

            ContextAdmissionResult result = fixture.coordinator().admit(
                    fixture.memory(), List.of());

            assertEquals(ContextCompressionMode.NORMAL, result.mode());
            assertEquals(ContextAdmissionResult.FailureReason.NONE,
                    result.failureReason());
            assertTrue(result.canProceed());
            assertEquals(30_720, result.finalTokens());
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
            assertCheckpointMetrics(fixture,
                    "no_unfinished_tail", 32_768, 32_768);
            verify(fixture.summaryService(), never()).compressNow(
                    any(), any(Long.class), any(Duration.class));
        }
    }

    @org.junit.jupiter.api.Test
    void 输入硬上限完整工具链生成临时检查点且不改写Redis原始轨迹() {
        try (Fixture fixture = fixture(32_768, 32_768)) {
            CompressionAwareChatMemory toolMemory = toolChainMemory(
                    fixture.summaryService(), "<template>绝密源码</template>");
            List<ChatMessage> original = toolMemory.completeTurnSnapshot()
                    .unfinishedTail();
            when(fixture.estimator().estimateRequest(anyList(), anyList()))
                    .thenAnswer(invocation -> containsCheckpoint(
                            invocation.getArgument(0)) ? 18_000 : 32_768);
            List<ContextAdmissionResult> transitions = new ArrayList<>();
            ContextCompressionAttemptState state =
                    new ContextCompressionAttemptState();

            ContextAdmissionResult result = fixture.coordinator().admit(
                    toolMemory, vueTools(), List.of(), transitions::add,
                    ContextContinuationGate.alwaysOpen(), state);

            assertEquals(ContextCompressionMode.TOOL_CHAIN_CHECKPOINT_COMPLETED,
                    result.mode());
            assertEquals(List.of("TOOL_CHAIN_CHECKPOINT_STARTED"),
                    transitions.stream()
                            .map(transition -> transition.mode().name())
                            .toList());
            assertTrue(result.canProceed());
            assertEquals(18_000, result.finalTokens());
            assertCheckpointMetrics(
                    fixture, "success", 32_768, result.finalTokens());
            assertTrue(result.requestMessages().stream()
                    .anyMatch(this::isCheckpoint));
            assertTrue(result.requestMessages().stream()
                    .anyMatch(ToolExecutionResultMessage.class::isInstance));
            assertTrue(result.requestMessages().stream()
                    .filter(AiMessage.class::isInstance)
                    .map(AiMessage.class::cast)
                    .anyMatch(AiMessage::hasToolExecutionRequests));
            assertTrue(result.requestMessages().toString().contains("绝密源码"));
            assertEquals(original, toolMemory.completeTurnSnapshot()
                    .unfinishedTail(), "请求级检查点不得改写底层L0/Redis轨迹");

            transitions.clear();
            ContextAdmissionResult rebuilt = fixture.coordinator().admit(
                    toolMemory, vueTools(), List.of(), transitions::add,
                    ContextContinuationGate.alwaysOpen(), state);
            assertEquals("TOOL_CHAIN_CHECKPOINT_REBUILT",
                    rebuilt.mode().name());
            assertTrue(transitions.isEmpty(),
                    "ACTIVE 后续重建不得重复发布检查点进度");
            assertEquals(2D, counter(fixture.registry(),
                    "memory_tool_chain_checkpoint_total",
                    "outcome", "success").count());
        }
    }

    @org.junit.jupiter.api.Test
    void 输入硬上限检查点只存在于请求视图且L0完整快照逐项不变() {
        try (Fixture fixture = fixture(32_768, 32_768)) {
            CompressionAwareChatMemory toolMemory = toolChainMemory(
                    fixture.summaryService(), "不得进入持久层的源码");
            List<ChatMessage> l0Before = List.copyOf(toolMemory.messages());
            when(fixture.estimator().estimateRequest(anyList(), anyList()))
                    .thenAnswer(invocation -> containsCheckpoint(
                            invocation.getArgument(0)) ? 18_000 : 32_768);

            ContextAdmissionResult result = fixture.coordinator().admit(
                    toolMemory, vueTools());

            assertEquals(ContextCompressionMode.TOOL_CHAIN_CHECKPOINT_COMPLETED,
                    result.mode());
            assertEquals(l0Before, toolMemory.messages(),
                    "检查点不得改变 L0 活动消息的任何元素");
            assertEquals(l0Before, toolMemory.completeTurnSnapshot()
                    .unfinishedTail(), "检查点不得替换 Redis/L0 未完成工具链");
            assertTrue(result.requestMessages().stream()
                    .anyMatch(this::isCheckpoint));
            assertFalse(toolMemory.messages().stream()
                    .anyMatch(this::isCheckpoint));
            verify(fixture.summaryService(), never()).compressNow(
                    any(), any(Long.class), any(Duration.class));
            verify(fixture.historyService(), never())
                    .listRecentCompleteTurnBoundaries(any(), any(Integer.class));
        }
    }

    @org.junit.jupiter.api.Test
    void 输入硬上限检查点后仍超限时安全失败且同状态不得递归重试() {
        try (Fixture fixture = fixture(32_768, 32_768)) {
            CompressionAwareChatMemory toolMemory = toolChainMemory(
                    fixture.summaryService(), "超大源码");
            ContextCompressionAttemptState state =
                    new ContextCompressionAttemptState();
            when(fixture.estimator().estimateRequest(anyList(), anyList()))
                    .thenReturn(32_768);

            ContextAdmissionResult first = fixture.coordinator().admit(
                    toolMemory, vueTools(), state);
            ContextAdmissionResult second = fixture.coordinator().admit(
                    toolMemory, vueTools(), state);

            assertEquals(ContextCompressionMode.HARD_LIMIT_REJECTED,
                    first.mode());
            assertEquals(ContextAdmissionResult.FailureReason
                    .STILL_OVER_HARD_LIMIT, first.failureReason());
            assertEquals(ContextAdmissionResult.FailureReason
                    .CHECKPOINT_ALREADY_ATTEMPTED, second.failureReason());
            assertFalse(first.canProceed());
            assertFalse(second.canProceed());
            assertEquals(1D, counter(fixture.registry(),
                    "memory_tool_chain_checkpoint_total",
                    "outcome", "failed").count());
            assertEquals(1D, counter(fixture.registry(),
                    "memory_tool_chain_checkpoint_total",
                    "outcome", "already_attempted").count());
            assertEquals(2L, summary(fixture.registry(),
                    "memory_tool_chain_checkpoint_tokens",
                    "stage", "before").count());
            assertEquals(65_536D, summary(fixture.registry(),
                    "memory_tool_chain_checkpoint_tokens",
                    "stage", "before").totalAmount());
            assertEquals(2L, summary(fixture.registry(),
                    "memory_tool_chain_checkpoint_tokens",
                    "stage", "after").count());
            assertEquals(65_536D, summary(fixture.registry(),
                    "memory_tool_chain_checkpoint_tokens",
                    "stage", "after").totalAmount());
            verify(fixture.summaryService(), never()).compressNow(
                    any(), any(Long.class), any(Duration.class));
        }
    }

    @org.junit.jupiter.api.Test
    void 最新readFile超预算时整批删除且不会产生孤立工具消息() {
        try (Fixture fixture = fixture(32_768, 30_000)) {
            CompressionAwareChatMemory toolMemory = toolChainMemory(
                    fixture.summaryService(), "最新源码");
            when(fixture.estimator().estimateRequest(anyList(), anyList()))
                    .thenAnswer(invocation -> {
                        List<ChatMessage> messages = invocation.getArgument(0);
                        if (!containsCheckpoint(messages)) {
                            return 32_768;
                        }
                        return messages.stream().anyMatch(
                                ToolExecutionResultMessage.class::isInstance)
                                ? 32_768 : 30_000;
                    });

            ContextAdmissionResult result = fixture.coordinator().admit(
                    toolMemory, vueTools(), new ContextCompressionAttemptState());

            assertEquals(ContextCompressionMode.TOOL_CHAIN_CHECKPOINT_COMPLETED,
                    result.mode());
            assertFalse(result.requestMessages().stream()
                    .anyMatch(ToolExecutionResultMessage.class::isInstance));
            assertTrue(result.requestMessages().stream()
                    .filter(AiMessage.class::isInstance)
                    .map(AiMessage.class::cast)
                    .noneMatch(AiMessage::hasToolExecutionRequests));
            assertEquals(30_000, result.finalTokens());
        }
    }

    @org.junit.jupiter.api.Test
    void 最新readDir超预算时类型化拒绝而不静默删除结果() {
        try (Fixture fixture = fixture(32_768, 30_000)) {
            CompressionAwareChatMemory toolMemory = readToolChainMemory(
                    fixture.summaryService(), "readDir", "[\"App.vue\"]");
            when(fixture.estimator().estimateRequest(anyList(), anyList()))
                    .thenAnswer(invocation -> {
                        List<ChatMessage> messages = invocation.getArgument(0);
                        if (!containsCheckpoint(messages)) {
                            return 32_768;
                        }
                        return messages.stream().anyMatch(
                                ToolExecutionResultMessage.class::isInstance)
                                ? 32_768 : 30_000;
                    });

            ContextAdmissionResult result = fixture.coordinator().admit(
                    toolMemory, vueTools("readDir"),
                    new ContextCompressionAttemptState());

            assertEquals(ContextCompressionMode.HARD_LIMIT_REJECTED,
                    result.mode());
            assertEquals(ContextAdmissionResult.FailureReason
                    .STILL_OVER_HARD_LIMIT, result.failureReason());
            assertFalse(result.canProceed());
        }
    }

    @org.junit.jupiter.api.Test
    void 旧回合阻塞压缩后仍达输入硬上限则继续生成检查点视图() {
        try (Fixture fixture = fixture(32_768, 32_768)) {
            CompressionAwareChatMemory mixedMemory = completedAndToolChainMemory(
                    fixture.summaryService(), "绝密源码");
            when(fixture.historyService().listRecentCompleteTurnBoundaries(7L, 1))
                    .thenReturn(List.of(new ChatHistoryService.StableTurnBoundary(
                            1L, 2L, "旧问题", "旧回复")));
            doReturn(13_000).when(fixture.estimator()).estimateMessages(anyList());
            when(fixture.estimator().estimateRequest(anyList(), anyList()))
                    .thenAnswer(invocation -> containsCheckpoint(
                            invocation.getArgument(0)) ? 19_000 : 32_768);

            ContextAdmissionResult result = fixture.coordinator().admit(
                    mixedMemory, vueTools(), new ContextCompressionAttemptState());

            verify(fixture.summaryService()).compressNow(
                    eq(7L), eq(2L), any(Duration.class));
            assertEquals(ContextCompressionMode.TOOL_CHAIN_CHECKPOINT_COMPLETED,
                    result.mode());
            assertEquals(19_000, result.finalTokens());
            assertTrue(result.canProceed());
            assertTrue(result.requestMessages().stream()
                    .filter(UserMessage.class::isInstance)
                    .map(UserMessage.class::cast)
                    .filter(UserMessage::hasSingleText)
                    .map(UserMessage::singleText)
                    .anyMatch(text -> text.contains(VALID_SUMMARY)),
                    "检查点请求必须保留同次阻塞压缩产生的可靠L1");
            assertTrue(mixedMemory.completeTurnSnapshot()
                    .completedTurns().isEmpty(),
                    "已被可靠L1覆盖的旧完整回合必须按既有CAS流程裁剪");
        }
    }

    @org.junit.jupiter.api.Test
    void 检查点估算后活动记忆变化则拒绝过期请求视图() {
        try (Fixture fixture = fixture(32_768, 18_000)) {
            CompressionAwareChatMemory toolMemory = toolChainMemory(
                    fixture.summaryService(), "绝密源码");
            AtomicLong estimates = new AtomicLong();
            when(fixture.estimator().estimateRequest(anyList(), anyList()))
                    .thenAnswer(invocation -> {
                        if (estimates.incrementAndGet() == 2L) {
                            toolMemory.add(UserMessage.from("并发追加的新指令"));
                            return 18_000;
                        }
                        return 32_768;
                    });

            ContextAdmissionResult result = fixture.coordinator().admit(
                    toolMemory, vueTools(), new ContextCompressionAttemptState());

            assertEquals(ContextCompressionMode.HARD_LIMIT_REJECTED,
                    result.mode());
            assertEquals(ContextAdmissionResult.FailureReason.PREFIX_CHANGED,
                    result.failureReason());
            assertFalse(result.canProceed());
            assertCheckpointMetrics(
                    fixture, "failed", 32_768, result.finalTokens());
            assertEquals(32_768, result.finalTokens(),
                    "失败指标只能使用未提交投影之外的最终安全请求视图");
            assertTrue(result.requestMessages().stream()
                    .noneMatch(this::isCheckpoint),
                    "过期检查点视图不得泄漏给主模型");
        }
    }

    @ParameterizedTest
    @EnumSource(ThrowingMeterRegistry.FailurePoint.class)
    void 检查点指标注册记录或计时故障不改变最终安全结果(
            ThrowingMeterRegistry.FailurePoint failurePoint) {
        try (Fixture fixture = fixture(32_768, 18_000)) {
            CompressionAwareChatMemory baselineMemory = toolChainMemory(
                    fixture.summaryService(), "<template>绝密源码</template>");
            when(fixture.estimator().estimateRequest(anyList(), anyList()))
                    .thenAnswer(invocation -> containsCheckpoint(
                            invocation.getArgument(0)) ? 18_000 : 32_768);
            ContextAdmissionResult baseline = fixture.coordinator().admit(
                    baselineMemory, vueTools(),
                    new ContextCompressionAttemptState());
            ThrowingMeterRegistry registry =
                    new ThrowingMeterRegistry(failurePoint);
            try {
                CompressionAwareChatMemory toolMemory = toolChainMemory(
                        fixture.summaryService(),
                        "<template>绝密源码</template>");
                ContextCompressionCoordinator coordinator =
                        new ContextCompressionCoordinator(
                                fixture.estimator(), fixture.historyService(),
                                fixture.summaryService(), fixture.properties(),
                                fixture.executor(), new AppDataLifecycleFence(),
                                new MemoryCompressionMetricsCollector(registry));

                ContextAdmissionResult result = coordinator.admit(
                        toolMemory, vueTools(),
                        new ContextCompressionAttemptState());

                assertEquals(baseline.mode(), result.mode());
                assertEquals(baseline.failureReason(), result.failureReason());
                assertEquals(baseline.canProceed(), result.canProceed());
                assertEquals(baseline.requestMessages(),
                        result.requestMessages());
                assertEquals(baseline.finalTokens(), result.finalTokens());
                assertTrue(registry.failureTriggered());
            } finally {
                registry.close();
            }
        }
    }

    @org.junit.jupiter.api.Test
    void 检查点准备期间删除接管则不得放行() {
        try (Fixture fixture = fixture(32_768, 18_000)) {
            CompressionAwareChatMemory toolMemory = toolChainMemory(
                    fixture.summaryService(), "绝密源码");
            AppDataLifecycleFence lifecycleFence = new AppDataLifecycleFence();
            ContextCompressionCoordinator coordinator =
                    new ContextCompressionCoordinator(
                            fixture.estimator(), fixture.historyService(),
                            fixture.summaryService(), fixture.properties(),
                            fixture.executor(), lifecycleFence,
                            fixture.metricsCollector());
            AtomicLong estimates = new AtomicLong();
            AtomicReference<AppDataLifecycleFence.DeletePermit> deletePermit =
                    new AtomicReference<>();
            when(fixture.estimator().estimateRequest(anyList(), anyList()))
                    .thenAnswer(invocation -> {
                        if (estimates.incrementAndGet() == 2L) {
                            deletePermit.set(lifecycleFence.beginDelete(
                                    7L, Duration.ZERO));
                            return 18_000;
                        }
                        return 32_768;
                    });

            ContextAdmissionResult result;
            try {
                result = coordinator.admit(
                        toolMemory, vueTools(),
                        new ContextCompressionAttemptState());
            } finally {
                if (deletePermit.get() != null) {
                    deletePermit.get().close();
                }
            }

            assertNotNull(deletePermit.get(), "测试必须在准备期间成功接管删除");
            assertEquals(ContextAdmissionResult.FailureReason.DELETE_REJECTED,
                    result.failureReason());
            assertFalse(result.canProceed());
            assertTrue(result.requestMessages().stream()
                    .noneMatch(this::isCheckpoint));
        }
    }

    @org.junit.jupiter.api.Test
    void 首次达到输入硬上限后即使原始尾部低于阈值仍重建检查点() {
        try (Fixture fixture = fixture(18_000, 18_000)) {
            CompressionAwareChatMemory toolMemory = toolChainMemory(
                    fixture.summaryService(), "首批源码");
            ContextCompressionAttemptState state =
                    new ContextCompressionAttemptState();
            AtomicLong rawEstimates = new AtomicLong();
            when(fixture.estimator().estimateRequest(anyList(), anyList()))
                    .thenAnswer(invocation -> {
                        List<ChatMessage> messages = invocation.getArgument(0);
                        if (containsCheckpoint(messages)) {
                            return 18_000;
                        }
                        return rawEstimates.getAndIncrement() == 0L
                                ? 32_768 : 30_000;
                    });

            ContextAdmissionResult first = fixture.coordinator().admit(
                    toolMemory, vueTools(), state);
            assertEquals(ContextCompressionMode.TOOL_CHAIN_CHECKPOINT_COMPLETED,
                    first.mode());
            ToolExecutionRequest latestRead = toolRequest(
                    "call-read-latest", "readFile",
                    "{\"path\":\"src/latest.vue\"}");
            toolMemory.add(AiMessage.from(latestRead));
            toolMemory.add(ToolExecutionResultMessage.from(
                    latestRead, fileResult(
                            "readFile", "src/latest.vue", false, "最新源码")));

            ContextAdmissionResult result = fixture.coordinator().admit(
                    toolMemory, vueTools(), state);

            assertEquals(ContextCompressionMode.TOOL_CHAIN_CHECKPOINT_REBUILT,
                    result.mode());
            assertTrue(result.requestMessages().toString()
                    .contains("src/latest.vue"));
            assertTrue(result.requestMessages().toString()
                    .contains("最新源码"));
            assertTrue(result.requestMessages().stream()
                    .anyMatch(ToolExecutionResultMessage.class::isInstance));
            assertTrue(result.requestMessages().stream()
                    .filter(AiMessage.class::isInstance)
                    .map(AiMessage.class::cast)
                    .anyMatch(AiMessage::hasToolExecutionRequests));
            verify(fixture.summaryService(), never()).compressNow(
                    any(), any(Long.class), any(Duration.class));
            verify(fixture.summaryService(), never())
                    .triggerSummarizationAsync(
                            any(), anyLong(), any(BooleanSupplier.class));
        }
    }

    @org.junit.jupiter.api.Test
    void 活动检查点并发重建只有一个owner() throws Exception {
        ContextCompressionAttemptState state =
                new ContextCompressionAttemptState();
        ContextCompressionAttemptState.CheckpointClaim firstEntry =
                state.tryEnterCheckpointMode();
        assertEquals(ContextCompressionAttemptState.EnterDecision.FIRST_ENTRY,
                firstEntry.decision());
        assertTrue(state.markCheckpointReady(firstEntry));
        try (ExecutorService executor = java.util.concurrent.Executors
                .newVirtualThreadPerTaskExecutor()) {
            CountDownLatch start = new CountDownLatch(1);
            Callable<ContextCompressionAttemptState.EnterDecision> enter = () -> {
                start.await();
                return state.tryEnterCheckpointMode().decision();
            };
            Future<ContextCompressionAttemptState.EnterDecision> first =
                    executor.submit(enter);
            Future<ContextCompressionAttemptState.EnterDecision> second =
                    executor.submit(enter);

            start.countDown();
            List<ContextCompressionAttemptState.EnterDecision> decisions =
                    List.of(first.get(1L, TimeUnit.SECONDS),
                            second.get(1L, TimeUnit.SECONDS));

            assertEquals(1L, decisions.stream().filter(decision -> decision
                    == ContextCompressionAttemptState.EnterDecision.REBUILD)
                    .count());
            assertEquals(1L, decisions.stream().filter(decision -> decision
                    == ContextCompressionAttemptState.EnterDecision.IN_PROGRESS)
                    .count());
        }
    }

    @org.junit.jupiter.api.Test
    void 过期owner失败不得覆盖后续成功状态() {
        ContextCompressionAttemptState state =
                new ContextCompressionAttemptState();
        ContextCompressionAttemptState.CheckpointClaim first =
                state.tryEnterCheckpointMode();

        assertTrue(state.markCheckpointReady(first));
        assertFalse(state.markCheckpointFailed(first));
        ContextCompressionAttemptState.CheckpointClaim rebuild =
                state.tryEnterCheckpointMode();
        assertEquals(ContextCompressionAttemptState.EnterDecision.REBUILD,
                rebuild.decision());
        assertTrue(state.markCheckpointReady(rebuild));
    }

    @org.junit.jupiter.api.Test
    void 输入硬上限孤立工具消息拒绝检查点且不返回部分请求视图() {
        try (Fixture fixture = fixture(32_768, 32_768)) {
            CompressionAwareChatMemory incomplete = incompleteToolChainMemory(
                    fixture.summaryService());

            ContextAdmissionResult result = fixture.coordinator().admit(
                    incomplete, vueTools(), new ContextCompressionAttemptState());

            assertEquals(ContextCompressionMode.HARD_LIMIT_REJECTED,
                    result.mode());
            assertEquals(ContextAdmissionResult.FailureReason
                    .INVALID_TOOL_CHAIN_CHECKPOINT, result.failureReason());
            assertFalse(result.canProceed());
        }
    }

    @org.junit.jupiter.api.Test
    void 检查点提交前回合终止则不泄露临时视图() {
        try (Fixture fixture = fixture(32_768, 18_000)) {
            CompressionAwareChatMemory toolMemory = toolChainMemory(
                    fixture.summaryService(), "绝密源码");
            TestContinuationGate gate = new TestContinuationGate();
            AtomicLong estimates = new AtomicLong();
            when(fixture.estimator().estimateRequest(anyList(), anyList()))
                    .thenAnswer(invocation -> {
                        if (estimates.getAndIncrement() > 0) {
                            gate.revoke();
                            return 18_000;
                        }
                        return 32_768;
                    });

            ContextAdmissionResult result = fixture.coordinator().admit(
                    toolMemory, vueTools(), List.of(), ignored -> { }, gate,
                    new ContextCompressionAttemptState());

            assertEquals(ContextAdmissionResult.FailureReason.TURN_TERMINATED,
                    result.failureReason());
            assertFalse(result.canProceed());
            assertTrue(result.requestMessages().stream()
                    .noneMatch(this::isCheckpoint));
        }
    }

    @org.junit.jupiter.api.Test
    void 检查点请求保留临时消息但不写入活动记忆() {
        try (Fixture fixture = fixture(32_768, 18_000)) {
            CompressionAwareChatMemory toolMemory = toolChainMemory(
                    fixture.summaryService(), "绝密源码");
            SystemMessage transientMessage =
                    SystemMessage.from("仅本次请求可见的临时约束");
            when(fixture.estimator().estimateRequest(anyList(), anyList()))
                    .thenAnswer(invocation -> containsCheckpoint(
                            invocation.getArgument(0)) ? 18_000 : 32_768);

            ContextAdmissionResult result = fixture.coordinator().admit(
                    toolMemory, vueTools(), List.of(transientMessage),
                    ignored -> { }, ContextContinuationGate.alwaysOpen(),
                    new ContextCompressionAttemptState());

            assertEquals(ContextCompressionMode.TOOL_CHAIN_CHECKPOINT_COMPLETED,
                    result.mode());
            assertEquals(transientMessage, result.requestMessages().getLast());
            assertFalse(toolMemory.messages().contains(transientMessage));
            assertEquals(1L, result.requestMessages().stream()
                    .filter(transientMessage::equals).count());
        }
    }

    @org.junit.jupiter.api.Test
    void 检查点开始监听器异常必须释放owner并返回类型化依赖失败() {
        try (Fixture fixture = fixture(32_768, 18_000)) {
            CompressionAwareChatMemory toolMemory = toolChainMemory(
                    fixture.summaryService(), "绝密源码");
            ContextCompressionAttemptState state =
                    new ContextCompressionAttemptState();

            ContextAdmissionResult result = fixture.coordinator().admit(
                    toolMemory, vueTools(), List.of(), ignored -> {
                        throw new IllegalStateException("listener down");
                    }, ContextContinuationGate.alwaysOpen(), state);

            assertEquals(ContextCompressionMode.HARD_LIMIT_REJECTED,
                    result.mode());
            assertEquals(ContextAdmissionResult.FailureReason.DEPENDENCY_FAILED,
                    result.failureReason());
            assertFalse(result.canProceed());
            assertTrue(result.requestMessages().stream()
                    .noneMatch(this::isCheckpoint));
            assertEquals(ContextCompressionAttemptState.EnterDecision
                            .ALREADY_FAILED,
                    state.tryEnterCheckpointMode().decision());
        }
    }

    @org.junit.jupiter.api.Test
    void ACTIVE检查点最终提交门异常必须释放owner且不泄漏投影() {
        try (Fixture fixture = fixture(32_768, 18_000)) {
            CompressionAwareChatMemory toolMemory = toolChainMemory(
                    fixture.summaryService(), "绝密源码");
            ContextCompressionAttemptState state =
                    new ContextCompressionAttemptState();
            ContextCompressionAttemptState.CheckpointClaim first =
                    state.tryEnterCheckpointMode();
            assertTrue(state.markCheckpointReady(first));
            AtomicLong gateInvocations = new AtomicLong();
            ContextContinuationGate gate = action -> {
                if (gateInvocations.incrementAndGet() == 5L) {
                    throw new IllegalStateException("gate down");
                }
                action.run();
                return true;
            };

            ContextAdmissionResult result = fixture.coordinator().admit(
                    toolMemory, vueTools(), List.of(), ignored -> { }, gate,
                    state);

            assertEquals(ContextCompressionMode.HARD_LIMIT_REJECTED,
                    result.mode());
            assertEquals(ContextAdmissionResult.FailureReason.DEPENDENCY_FAILED,
                    result.failureReason());
            assertFalse(result.canProceed());
            assertTrue(result.requestMessages().stream()
                    .noneMatch(this::isCheckpoint));
            assertEquals(ContextCompressionAttemptState.EnterDecision
                            .ALREADY_FAILED,
                    state.tryEnterCheckpointMode().decision());
        }
    }

    @org.junit.jupiter.api.Test
    void 检查点估算器异常必须释放owner且不泄漏投影() {
        try (Fixture fixture = fixture(32_768, 18_000)) {
            CompressionAwareChatMemory toolMemory = toolChainMemory(
                    fixture.summaryService(), "绝密源码");
            ContextCompressionAttemptState state =
                    new ContextCompressionAttemptState();
            when(fixture.estimator().estimateRequest(anyList(), anyList()))
                    .thenReturn(32_768)
                    .thenThrow(new IllegalStateException("tokenizer down"));

            ContextAdmissionResult result = fixture.coordinator().admit(
                    toolMemory, vueTools(), state);

            assertEquals(ContextCompressionMode.HARD_LIMIT_REJECTED,
                    result.mode());
            assertEquals(ContextAdmissionResult.FailureReason.DEPENDENCY_FAILED,
                    result.failureReason());
            assertFalse(result.canProceed());
            assertTrue(result.requestMessages().stream()
                    .noneMatch(this::isCheckpoint));
            assertEquals(ContextCompressionAttemptState.EnterDecision
                            .ALREADY_FAILED,
                    state.tryEnterCheckpointMode().decision());
        }
    }

    @org.junit.jupiter.api.Test
    void 检查点投影估算越过绝对截止则超时且不泄漏投影() {
        try (Fixture fixture = fixture(32_768, 18_000)) {
            CompressionAwareChatMemory toolMemory = toolChainMemory(
                    fixture.summaryService(), "绝密源码");
            AtomicLong nanoTime = new AtomicLong();
            AtomicLong estimates = new AtomicLong();
            when(fixture.estimator().estimateRequest(anyList(), anyList()))
                    .thenAnswer(invocation -> {
                        if (estimates.incrementAndGet() == 2L) {
                            nanoTime.set(Duration.ofSeconds(61L).toNanos());
                            return 18_000;
                        }
                        return 32_768;
                    });
            ContextCompressionCoordinator coordinator =
                    new ContextCompressionCoordinator(
                            fixture.estimator(), fixture.historyService(),
                            fixture.summaryService(), fixture.properties(),
                            fixture.executor(), fixture.memoryReadExecutor(),
                            new AppDataLifecycleFence(),
                            fixture.metricsCollector(), nanoTime::get);
            ContextCompressionAttemptState state =
                    new ContextCompressionAttemptState();

            ContextAdmissionResult result = coordinator.admit(
                    toolMemory, vueTools(), state);

            assertEquals(ContextCompressionMode.HARD_LIMIT_REJECTED,
                    result.mode());
            assertEquals(ContextAdmissionResult.FailureReason.TIMED_OUT,
                    result.failureReason());
            assertFalse(result.canProceed());
            assertEquals(32_768, result.finalTokens());
            assertTrue(result.requestMessages().stream()
                    .noneMatch(this::isCheckpoint));
            assertEquals(ContextCompressionAttemptState.EnterDecision
                            .ALREADY_FAILED,
                    state.tryEnterCheckpointMode().decision());
        }
    }

    @org.junit.jupiter.api.Test
    void 检查点最终提交门内越过截止则超时且不泄漏投影() {
        try (Fixture fixture = fixture(32_768, 18_000)) {
            CompressionAwareChatMemory toolMemory = toolChainMemory(
                    fixture.summaryService(), "绝密源码");
            AtomicLong nanoTime = new AtomicLong();
            ContextContinuationGate gate = new DeadlineAdvancingGate(
                    nanoTime, 5, Duration.ofSeconds(61L).toNanos());
            ContextCompressionCoordinator coordinator =
                    new ContextCompressionCoordinator(
                            fixture.estimator(), fixture.historyService(),
                            fixture.summaryService(), fixture.properties(),
                            fixture.executor(), fixture.memoryReadExecutor(),
                            new AppDataLifecycleFence(),
                            fixture.metricsCollector(), nanoTime::get);
            ContextCompressionAttemptState state =
                    new ContextCompressionAttemptState();
            ContextCompressionAttemptState.CheckpointClaim first =
                    state.tryEnterCheckpointMode();
            assertTrue(state.markCheckpointReady(first));

            ContextAdmissionResult result = coordinator.admit(
                    toolMemory, vueTools(), List.of(), ignored -> { }, gate,
                    state);

            assertEquals(ContextCompressionMode.HARD_LIMIT_REJECTED,
                    result.mode());
            assertEquals(ContextAdmissionResult.FailureReason.TIMED_OUT,
                    result.failureReason());
            assertFalse(result.canProceed());
            assertEquals(32_768, result.finalTokens());
            assertTrue(result.requestMessages().stream()
                    .noneMatch(this::isCheckpoint));
            assertEquals(ContextCompressionAttemptState.EnterDecision
                            .ALREADY_FAILED,
                    state.tryEnterCheckpointMode().decision());
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
                    "异步压缩区间的后台准备失败不得阻断仍低于阻塞阈值 的当前请求");
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

    private static Stream<Arguments> transientThresholds() {
        return Stream.of(
                Arguments.of(28_672,
                        ContextCompressionMode.ASYNC_SCHEDULED),
                Arguments.of(30_720,
                        ContextCompressionMode.BLOCKING_COMPLETED),
                Arguments.of(32_768,
                        ContextCompressionMode.BLOCKING_COMPLETED));
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

    private CompressionAwareChatMemory toolChainMemory(
            MemorySummaryService summaryService, String source) {
        ToolExecutionRequest read = toolRequest(
                "call-read", "readFile", "{\"path\":\"src/App.vue\"}");
        MessageWindowChatMemory delegate = MessageWindowChatMemory.builder()
                .id(7L).maxMessages(Integer.MAX_VALUE).build();
        delegate.add(UserMessage.from("调整首页并完成构建"));
        delegate.add(AiMessage.from(read));
        delegate.add(ToolExecutionResultMessage.from(read,
                fileResult("readFile", "src/App.vue", false, source)));
        return compressionMemory(delegate, summaryService);
    }

    private CompressionAwareChatMemory readToolChainMemory(
            MemorySummaryService summaryService,
            String toolName,
            String content) {
        ToolExecutionRequest read = toolRequest(
                "call-read", toolName, "{\"path\":\"src\"}");
        MessageWindowChatMemory delegate = MessageWindowChatMemory.builder()
                .id(7L).maxMessages(Integer.MAX_VALUE).build();
        delegate.add(UserMessage.from("调整首页并完成构建"));
        delegate.add(AiMessage.from(read));
        delegate.add(ToolExecutionResultMessage.from(read,
                fileResult(toolName, "src", false, content)));
        return compressionMemory(delegate, summaryService);
    }

    private CompressionAwareChatMemory completedAndToolChainMemory(
            MemorySummaryService summaryService, String source) {
        ToolExecutionRequest read = toolRequest(
                "call-read", "readFile", "{\"path\":\"src/App.vue\"}");
        MessageWindowChatMemory delegate = MessageWindowChatMemory.builder()
                .id(7L).maxMessages(Integer.MAX_VALUE).build();
        delegate.add(UserMessage.from("旧问题"));
        delegate.add(AiMessage.from("旧回复"));
        delegate.add(UserMessage.from("继续调整首页"));
        delegate.add(AiMessage.from(read));
        delegate.add(ToolExecutionResultMessage.from(read,
                fileResult("readFile", "src/App.vue", false, source)));
        return compressionMemory(delegate, summaryService);
    }

    private CompressionAwareChatMemory incompleteToolChainMemory(
            MemorySummaryService summaryService) {
        ToolExecutionRequest read = toolRequest(
                "call-read", "readFile", "{\"path\":\"src/App.vue\"}");
        MessageWindowChatMemory delegate = MessageWindowChatMemory.builder()
                .id(7L).maxMessages(Integer.MAX_VALUE).build();
        delegate.add(UserMessage.from("读取首页"));
        delegate.add(AiMessage.from(read));
        return compressionMemory(delegate, summaryService);
    }

    private CompressionAwareChatMemory compressionMemory(
            MessageWindowChatMemory delegate,
            MemorySummaryService summaryService) {
        return new CompressionAwareChatMemory(
                new TokenAwareChatMemory(delegate), summaryService,
                mock(UserMemoryService.class));
    }

    private ToolExecutionRequest toolRequest(
            String id, String name, String arguments) {
        return ToolExecutionRequest.builder()
                .id(id).name(name).arguments(arguments).build();
    }

    private List<ToolSpecification> vueTools() {
        return vueTools("readFile");
    }

    private List<ToolSpecification> vueTools(String... toolNames) {
        return Arrays.stream(toolNames)
                .map(toolName -> ToolSpecification.builder().name(toolName).build())
                .toList();
    }

    private String fileResult(
            String operation, String path, boolean changed, String content) {
        return "{\"protocol\":\"file-tool/v1\","
                + "\"operation\":\"" + operation + "\","
                + "\"status\":\"APPLIED\","
                + "\"relativePath\":\"" + path + "\","
                + "\"changed\":" + changed + ","
                + "\"message\":\"已执行\",\"failureReason\":null,"
                + "\"content\":\"" + content
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n") + "\"}";
    }

    private boolean containsCheckpoint(List<ChatMessage> messages) {
        return messages.stream().anyMatch(this::isCheckpoint);
    }

    private boolean isCheckpoint(ChatMessage message) {
        return message instanceof SystemMessage systemMessage
                && systemMessage.text().startsWith("本轮可信执行检查点");
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

    private void assertCheckpointMetrics(
            Fixture fixture,
            String outcome,
            int beforeTokens,
            int afterTokens) {
        assertEquals(1D, counter(fixture.registry(),
                "memory_tool_chain_checkpoint_total",
                "outcome", outcome).count());
        DistributionSummary before = summary(fixture.registry(),
                "memory_tool_chain_checkpoint_tokens", "stage", "before");
        DistributionSummary after = summary(fixture.registry(),
                "memory_tool_chain_checkpoint_tokens", "stage", "after");
        assertEquals(1L, before.count());
        assertEquals(beforeTokens, before.totalAmount());
        assertEquals(1L, after.count());
        assertEquals(afterTokens, after.totalAmount());
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
