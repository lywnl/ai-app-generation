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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
                verify(fixture.summaryService())
                        .triggerSummarizationAsync(7L, 2L);
            } else {
                verify(fixture.summaryService(), never())
                        .triggerSummarizationAsync(any(), eq(2L));
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

            assertEquals(ContextCompressionMode.NORMAL, result.mode());
            assertEquals(ContextAdmissionResult.FailureReason.NONE,
                    result.failureReason());
            assertTrue(result.canProceed());
            verify(fixture.summaryService(), never())
                    .triggerSummarizationAsync(any(), any(Long.class));
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
    void augmentedL0UserTextCanAlignByStableTerminalAiOrder() {
        try (Fixture fixture = fixture(30_720, 27_000)) {
            when(fixture.historyService().listRecentCompleteTurnBoundaries(
                    7L, 2)).thenReturn(List.of(
                    new ChatHistoryService.StableTurnBoundary(
                            1L, 2L, "原始旧问题", "旧回复"),
                    new ChatHistoryService.StableTurnBoundary(
                            3L, 4L, "原始新问题", "新回复")));

            ContextAdmissionResult result = fixture.coordinator().admit(
                    fixture.memory(), List.of());

            assertEquals(ContextCompressionMode.BLOCKING_COMPLETED,
                    result.mode());
            assertTrue(result.canProceed());
            verify(fixture.summaryService()).compressNow(
                    eq(7L), eq(2L), any(Duration.class));
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
                    .triggerSummarizationAsync(any(), any(Long.class));
            verify(fixture.summaryService(), never()).compressNow(
                    any(), any(Long.class), any(Duration.class));
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
        MemoryCompressionMetricsCollector metricsCollector =
                new MemoryCompressionMetricsCollector(registry);
        ContextCompressionCoordinator coordinator =
                new ContextCompressionCoordinator(
                estimator, historyService, summaryService,
                properties, executor, new AppDataLifecycleFence(),
                metricsCollector);
        return new Fixture(coordinator, memory, summaryService,
                historyService, estimator, properties, executor,
                metricsCollector, registry);
    }

    private CompressionAwareChatMemory memory(
            MemorySummaryService summaryService,
            UserMemoryService userMemoryService) {
        MessageWindowChatMemory delegate = MessageWindowChatMemory.builder()
                .id(7L)
                .maxMessages(Integer.MAX_VALUE)
                .build();
        delegate.add(UserMessage.from("旧问题"));
        delegate.add(AiMessage.from("旧回复"));
        delegate.add(UserMessage.from("新问题"));
        delegate.add(AiMessage.from("新回复"));
        return new CompressionAwareChatMemory(
                new TokenAwareChatMemory(delegate),
                summaryService,
                userMemoryService);
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

    private record Fixture(
            ContextCompressionCoordinator coordinator,
            CompressionAwareChatMemory memory,
            MemorySummaryService summaryService,
            ChatHistoryService historyService,
            ChatTokenEstimator estimator,
            MemoryTokenProperties properties,
            ExecutorService executor,
            MemoryCompressionMetricsCollector metricsCollector,
            PrometheusMeterRegistry registry) implements AutoCloseable {

        @Override
        public void close() {
            executor.shutdownNow();
            registry.close();
        }
    }
}
