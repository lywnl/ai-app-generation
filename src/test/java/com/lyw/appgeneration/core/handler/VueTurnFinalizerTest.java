package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.ai.AiGeneratorServiceFactory;
import com.lyw.appgeneration.ai.memory.ToolMessageCollapser;
import com.lyw.appgeneration.ai.tools.FileToolBudgetGuard;
import com.lyw.appgeneration.core.builder.VueBuildPhase;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import com.lyw.appgeneration.core.concurrency.VueTurnAdmissionController;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.monitor.ThrowingMeterRegistry;
import com.lyw.appgeneration.monitor.VueBuildRepairMetricsCollector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.lyw.appgeneration.service.MemoryCacheInvalidationResult;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.UserMemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static com.lyw.appgeneration.ai.memory.ToolMessageCollapser.CollapseStatus.COLLAPSED;
import static com.lyw.appgeneration.ai.memory.ToolMessageCollapser.CollapseStatus.STORE_FAILED;
import static com.lyw.appgeneration.core.handler.VueTurnOutcome.TurnOutcomeType.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VueTurnFinalizerTest {

    private static final long APP_ID = 7L;
    private static final long USER_ID = 9L;
    private ChatHistoryService history;
    private ToolMessageCollapser collapser;
    private MemorySummaryService summary;
    private UserMemoryService preference;
    private AiGeneratorServiceFactory factory;
    private SimpleMeterRegistry metricsRegistry;
    private VueBuildRepairMetricsCollector metrics;
    private AppDataLifecycleFence lifecycleFence;
    private VueTurnFinalizer finalizer;

    @BeforeEach
    void setUp() {
        history = mock(ChatHistoryService.class);
        collapser = mock(ToolMessageCollapser.class);
        summary = mock(MemorySummaryService.class);
        preference = mock(UserMemoryService.class);
        factory = mock(AiGeneratorServiceFactory.class);
        metricsRegistry = new SimpleMeterRegistry();
        metrics = new VueBuildRepairMetricsCollector(metricsRegistry);
        lifecycleFence = new AppDataLifecycleFence();
        finalizer = new VueTurnFinalizer(
                history, collapser, summary, preference, factory, lifecycleFence,
                metrics, new FileToolBudgetGuard());
        when(history.addChatMessage(anyLong(), anyString(), eq("ai"), anyLong()))
                .thenReturn(true);
        when(collapser.collapseLastTurn(anyLong(), anyString()))
                .thenReturn(new ToolMessageCollapser.CollapseResult(COLLAPSED, java.util.List.of()));
    }

    @Test
    void stableOutcomesPersistCanonicalTextOnceAndTriggerMemoryOnce() {
        for (VueTurnOutcome outcome : java.util.List.of(
                outcome(VueBuildPhase.SUCCEEDED, SUCCEEDED, "项目已生成并构建成功。", true),
                outcome(VueBuildPhase.FAILED, FAILED, "抱歉，系统遇到了一些问题，请您稍后重试修复", false),
                outcome(VueBuildPhase.GENERATING, SYSTEM_ERROR, "生成过程中遇到系统异常，请稍后重试。", false),
                outcome(VueBuildPhase.FINAL_DIAGNOSIS, TIMED_OUT, "生成与构建超时，请稍后重试。", false),
                outcome(VueBuildPhase.CANCELLED, CANCELLED, "本次生成已取消。", false),
                outcome(VueBuildPhase.GENERATING, PROTOCOL_ERROR, "项目尚未通过真实构建，请重新生成。", false))) {
            VueTurnContext context = VueTurnContext.testing(APP_ID, USER_ID,
                    "turn-" + outcome.outcome(), outcome.phase());

            VueTurnFinalizer.FinalizationResult result = finalizer.finalizeOnce(context, outcome);

            assertEquals(outcome.outcome(), result.outcome().outcome());
            verify(history).addChatMessage(APP_ID, outcome.canonicalAiText(), "ai", USER_ID);
            verify(collapser).collapseLastTurn(APP_ID, outcome.canonicalAiText());
            verify(summary).triggerSummarizationAsync(APP_ID);
            verify(preference).triggerPreferenceExtractionAsync(USER_ID, APP_ID);
            assertEquals(1.0, metricsRegistry.get("vue_turn_outcomes_total")
                    .tags("outcome", outcome.outcome().name().toLowerCase(),
                            "phase", outcome.phase().name().toLowerCase())
                    .counter().count());
            clearInvocations(history, collapser, summary, preference, factory);
        }
    }

    @Test
    void counterIncrementFailureDoesNotChangeStableFinalization() {
        ThrowingMeterRegistry registry = new ThrowingMeterRegistry(
                ThrowingMeterRegistry.FailurePoint.COUNTER_INCREMENT);
        VueTurnFinalizer faultInjectedFinalizer = new VueTurnFinalizer(
                history, collapser, summary, preference, factory, lifecycleFence,
                new VueBuildRepairMetricsCollector(registry),
                new FileToolBudgetGuard());
        VueTurnOutcome requested = outcome(
                VueBuildPhase.SUCCEEDED, SUCCEEDED,
                "项目已生成并构建成功。", true);
        VueTurnContext context = VueTurnContext.testing(
                APP_ID, USER_ID, "turn-metrics-failure", VueBuildPhase.SUCCEEDED);

        VueTurnFinalizer.FinalizationResult result =
                faultInjectedFinalizer.finalizeOnce(context, requested);

        assertEquals(SUCCEEDED, result.outcome().outcome());
        assertEquals(requested.canonicalAiText(),
                result.outcome().canonicalAiText());
        verify(history, times(1)).addChatMessage(
                APP_ID, requested.canonicalAiText(), "ai", USER_ID);
        verify(collapser, times(1)).collapseLastTurn(
                APP_ID, requested.canonicalAiText());
        verify(summary, times(1)).triggerSummarizationAsync(APP_ID);
        verify(preference, times(1))
                .triggerPreferenceExtractionAsync(USER_ID, APP_ID);
        assertTrue(registry.failureTriggered());
    }

    @Test
    void oversizedCanonicalTextIsReplacedBeforeMysqlAndL0() {
        FileToolBudgetGuard guard = new FileToolBudgetGuard();
        guard.setMaxSingleFileCodePoints(8);
        guard.setMaxCumulativeMutationCodePoints(16);
        guard.setMaxCanonicalAiTextCodePoints(64);
        guard.setMaxReadFileCodePoints(8);
        guard.setMaxReadDirCodePoints(8);
        VueTurnContext context = VueTurnContext.testing(
                APP_ID, USER_ID, "turn-oversized-finalizer",
                VueBuildPhase.GENERATING, guard.newSession());
        String oversized = "X".repeat(65);
        VueTurnOutcome requested = outcome(
                VueBuildPhase.GENERATING, SUCCEEDED, oversized, true);

        VueTurnFinalizer.FinalizationResult result =
                finalizer.finalizeOnce(context, requested);

        assertEquals(SYSTEM_ERROR, result.outcome().outcome());
        assertEquals(VueTurnFinalizer.RESOURCE_LIMIT_MESSAGE,
                result.outcome().canonicalAiText());
        verify(history).addChatMessage(
                APP_ID, VueTurnFinalizer.RESOURCE_LIMIT_MESSAGE, "ai", USER_ID);
        verify(collapser).collapseLastTurn(
                APP_ID, VueTurnFinalizer.RESOURCE_LIMIT_MESSAGE);
        verify(history, never()).addChatMessage(
                APP_ID, oversized, "ai", USER_ID);
    }

    @Test
    void terminalReserveUsesLongestFixedTerminalMessage() {
        List<String> expectedMessages = List.of(
                "项目已生成并构建成功。",
                "抱歉，系统遇到了一些问题，请您稍后重试修复",
                "生成过程中遇到系统异常，请稍后重试。",
                "项目尚未通过真实构建，请重新生成。",
                "生成状态异常，系统已停止本次生成，请重新发起。",
                "生成步骤过多，系统已停止本次生成，请稍后重试。",
                "生成与构建超时，请稍后重试。",
                "生成内容过大，系统已停止本次生成，请缩小需求后重试。",
                "本次生成已取消。");

        assertEquals(expectedMessages, VueTurnFinalizer.fixedTerminalMessages());
        int longest = expectedMessages.stream()
                .mapToInt(FileToolBudgetGuard::codePointCount)
                .max()
                .orElseThrow();
        assertEquals(longest, VueTurnFinalizer.maxTerminalMessageCodePoints());
        assertEquals(longest + 2, VueTurnFinalizer.terminalReserveCodePoints());
    }

    @Test
    void collapseFailureRecordsFailedInvalidateWithoutChangingPersistedOutcome() {
        when(collapser.collapseLastTurn(APP_ID, "项目已生成并构建成功。"))
                .thenReturn(new ToolMessageCollapser.CollapseResult(STORE_FAILED, java.util.List.of()));
        when(factory.invalidateAndClearMemory(APP_ID, CodeGenTypeEnum.VUE_PROJECT))
                .thenReturn(MemoryCacheInvalidationResult.success());
        VueTurnOutcome outcome = outcome(VueBuildPhase.SUCCEEDED, SUCCEEDED,
                "项目已生成并构建成功。", true);

        VueTurnFinalizer.FinalizationResult result = finalizer.finalizeOnce(
                VueTurnContext.testing(APP_ID, USER_ID, "turn-metrics",
                        VueBuildPhase.SUCCEEDED), outcome);

        assertEquals(outcome, result.outcome());
        assertEquals(1.0, metricsRegistry.get("vue_memory_l0_sync_total")
                .tags("action", "collapse", "result", "failed")
                .counter().count());
        assertEquals(1.0, metricsRegistry.get("vue_memory_l0_sync_total")
                .tags("action", "invalidate", "result", "succeeded")
                .counter().count());
    }

    @Test
    void duplicateAndConcurrentFinalizationHaveSingleWinner() throws Exception {
        VueTurnContext context = VueTurnContext.testing(
                APP_ID, USER_ID, "turn-race", VueBuildPhase.CANCELLED);
        VueTurnOutcome cancelled = outcome(
                VueBuildPhase.CANCELLED, CANCELLED, "本次生成已取消。", false);
        VueTurnOutcome protocol = outcome(
                VueBuildPhase.GENERATING, PROTOCOL_ERROR,
                "项目尚未通过真实构建，请重新生成。", false);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                start.await();
                return finalizer.finalizeOnce(context, cancelled);
            });
            var second = executor.submit(() -> {
                start.await();
                return finalizer.finalizeOnce(context, protocol);
            });
            start.countDown();
            assertSame(first.get(1, TimeUnit.SECONDS),
                    second.get(1, TimeUnit.SECONDS));
        }

        verify(history, times(1)).addChatMessage(eq(APP_ID), anyString(), eq("ai"), eq(USER_ID));
        verify(collapser, times(1)).collapseLastTurn(eq(APP_ID), anyString());
        verify(summary, times(1)).triggerSummarizationAsync(APP_ID);
        verify(preference, times(1)).triggerPreferenceExtractionAsync(USER_ID, APP_ID);
    }

    @Test
    void redisFailureKeepsBuildOutcomeAndClearsUnsafeL0ForColdRebuild() {
        when(collapser.collapseLastTurn(APP_ID, "项目已生成并构建成功。"))
                .thenReturn(new ToolMessageCollapser.CollapseResult(STORE_FAILED, java.util.List.of()));
        when(factory.invalidateAndClearMemory(APP_ID, CodeGenTypeEnum.VUE_PROJECT))
                .thenReturn(MemoryCacheInvalidationResult.success());
        VueTurnContext context = VueTurnContext.testing(
                APP_ID, USER_ID, "turn-store", VueBuildPhase.SUCCEEDED);

        VueTurnFinalizer.FinalizationResult result = finalizer.finalizeOnce(context,
                outcome(VueBuildPhase.SUCCEEDED, SUCCEEDED, "项目已生成并构建成功。", true));

        assertEquals(SUCCEEDED, result.outcome().outcome());
        verify(factory).invalidateAndClearMemory(APP_ID, CodeGenTypeEnum.VUE_PROJECT);
        verify(factory, never()).invalidateVueService(APP_ID);
        verify(summary).triggerSummarizationAsync(APP_ID);
        verify(preference).triggerPreferenceExtractionAsync(USER_ID, APP_ID);
    }

    @Test
    void mysqlFalseDowngradesClientOutcomeAndSkipsAllMemorySideEffects() {
        when(history.addChatMessage(APP_ID, "项目已生成并构建成功。", "ai", USER_ID))
                .thenReturn(false);
        VueTurnContext context = VueTurnContext.testing(
                APP_ID, USER_ID, "turn-mysql", VueBuildPhase.SUCCEEDED);

        VueTurnFinalizer.FinalizationResult result = finalizer.finalizeOnce(context,
                outcome(VueBuildPhase.SUCCEEDED, SUCCEEDED, "项目已生成并构建成功。", true));

        assertEquals(SYSTEM_ERROR, result.outcome().outcome());
        assertFalse(result.persisted());
        verifyNoInteractions(collapser, summary, preference);
        verify(factory).invalidateAndClearMemory(
                APP_ID, CodeGenTypeEnum.VUE_PROJECT);
    }

    @Test
    void mysqlExceptionDowngradesAndMemoryHookFailureDoesNotRewriteSavedOutcome() {
        VueTurnOutcome succeeded = outcome(
                VueBuildPhase.SUCCEEDED, SUCCEEDED,
                "项目已生成并构建成功。", true);
        when(history.addChatMessage(APP_ID, succeeded.canonicalAiText(), "ai", USER_ID))
                .thenThrow(new IllegalStateException("mysql down"));
        VueTurnContext failedContext = VueTurnContext.testing(
                APP_ID, USER_ID, "turn-mysql-error", VueBuildPhase.SUCCEEDED);

        assertEquals(SYSTEM_ERROR,
                finalizer.finalizeOnce(failedContext, succeeded).outcome().outcome());
        verifyNoInteractions(collapser, summary, preference);

        reset(history, collapser, summary, preference, factory);
        when(history.addChatMessage(anyLong(), anyString(), eq("ai"), anyLong()))
                .thenReturn(true);
        when(collapser.collapseLastTurn(anyLong(), anyString()))
                .thenReturn(new ToolMessageCollapser.CollapseResult(
                        COLLAPSED, java.util.List.of()));
        doThrow(new IllegalStateException("summary queue down"))
                .when(summary).triggerSummarizationAsync(APP_ID);
        VueTurnContext hookContext = VueTurnContext.testing(
                APP_ID, USER_ID, "turn-hook-error", VueBuildPhase.SUCCEEDED);

        VueTurnFinalizer.FinalizationResult result =
                finalizer.finalizeOnce(hookContext, succeeded);

        assertEquals(SUCCEEDED, result.outcome().outcome());
        assertTrue(result.persisted());
        verify(preference).triggerPreferenceExtractionAsync(USER_ID, APP_ID);
    }

    @Test
    void deleteGateRejectsLateFinalizerWithoutAnyDataOrCacheSideEffect() {
        AppDataLifecycleFence.DeletePermit deletion =
                lifecycleFence.beginDelete(APP_ID, Duration.ofSeconds(1));
        assertNotNull(deletion);
        VueTurnContext context = VueTurnContext.testing(
                APP_ID, USER_ID, "turn-after-delete", VueBuildPhase.CANCELLED);
        VueTurnOutcome cancelled = outcome(
                VueBuildPhase.CANCELLED, CANCELLED, "本次生成已取消。", false);

        VueTurnFinalizer.FinalizationResult result =
                finalizer.finalizeOnce(context, cancelled);

        assertEquals(CANCELLED, result.outcome().outcome());
        assertFalse(result.persisted());
        verifyNoInteractions(history, collapser, summary, preference, factory);
        deletion.abortAndReopen();
    }

    @Test
    void deleteWaitsUntilFinalizerCompletesAllStableMemoryHooks() throws Exception {
        CountDownLatch historyEntered = new CountDownLatch(1);
        CountDownLatch releaseHistory = new CountDownLatch(1);
        when(history.addChatMessage(APP_ID, "项目已生成并构建成功。", "ai", USER_ID))
                .thenAnswer(invocation -> {
                    historyEntered.countDown();
                    assertTrue(releaseHistory.await(1, TimeUnit.SECONDS));
                    return true;
                });
        VueTurnContext context = VueTurnContext.testing(
                APP_ID, USER_ID, "turn-delete-race", VueBuildPhase.SUCCEEDED);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var finalization = executor.submit(() -> finalizer.finalizeOnce(context,
                    outcome(VueBuildPhase.SUCCEEDED, SUCCEEDED,
                            "项目已生成并构建成功。", true)));
            assertTrue(historyEntered.await(1, TimeUnit.SECONDS));
            var deletion = executor.submit(() ->
                    lifecycleFence.beginDelete(APP_ID, Duration.ofSeconds(2)));

            assertThrows(TimeoutException.class,
                    () -> deletion.get(100, TimeUnit.MILLISECONDS));
            releaseHistory.countDown();
            assertTrue(finalization.get(1, TimeUnit.SECONDS).persisted());
            AppDataLifecycleFence.DeletePermit deletePermit =
                    deletion.get(1, TimeUnit.SECONDS);
            assertNotNull(deletePermit);
            verify(summary).triggerSummarizationAsync(APP_ID);
            verify(preference).triggerPreferenceExtractionAsync(USER_ID, APP_ID);
            deletePermit.abortAndReopen();
        }
    }

    @Test
    void unexpectedPersistenceFailureKeepsWriterUntilCacheInvalidationCompletes()
            throws Exception {
        CountDownLatch invalidationEntered = new CountDownLatch(1);
        CountDownLatch releaseInvalidation = new CountDownLatch(1);
        when(collapser.collapseLastTurn(APP_ID, "项目已生成并构建成功。"))
                .thenThrow(new IllegalStateException("redis unexpected"));
        doAnswer(invocation -> {
            invalidationEntered.countDown();
            assertTrue(releaseInvalidation.await(1, TimeUnit.SECONDS));
            return null;
        }).when(factory).invalidateAndClearMemory(
                APP_ID, CodeGenTypeEnum.VUE_PROJECT);
        VueTurnContext context = VueTurnContext.testing(
                APP_ID, USER_ID, "turn-invalidation-race", VueBuildPhase.SUCCEEDED);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var finalization = executor.submit(() -> finalizer.finalizeOnce(context,
                    outcome(VueBuildPhase.SUCCEEDED, SUCCEEDED,
                            "项目已生成并构建成功。", true)));
            assertTrue(invalidationEntered.await(1, TimeUnit.SECONDS));
            var deletion = executor.submit(() ->
                    lifecycleFence.beginDelete(APP_ID, Duration.ofSeconds(2)));

            assertThrows(TimeoutException.class,
                    () -> deletion.get(100, TimeUnit.MILLISECONDS));
            releaseInvalidation.countDown();
            assertFalse(finalization.get(1, TimeUnit.SECONDS).persisted());
            AppDataLifecycleFence.DeletePermit deletePermit =
                    deletion.get(1, TimeUnit.SECONDS);
            assertNotNull(deletePermit);
            deletePermit.abortAndReopen();
        }
    }

    @Test
    void persistenceRootFailureSurvivesResourceCloseErrorAndCompletesSharedResult() {
        AssertionError persistenceFailure =
                new AssertionError("persist-root");
        AssertionError closeFailure =
                new AssertionError("close-secondary");
        when(history.addChatMessage(anyLong(), anyString(), eq("ai"), anyLong()))
                .thenThrow(persistenceFailure);
        AppOperationLeaseManager operationManager =
                new AppOperationLeaseManager();
        AppOperationLeaseManager.AppOperationLease operation =
                operationManager.acquire(
                        APP_ID,
                        AppOperationLeaseManager.AppOperationType.GENERATE,
                        "turn-root-close-failure");
        VueBuildSessionManager.VueBuildLease lease =
                new VueBuildSessionManager().open(
                        operation, USER_ID, "turn-root-close-failure");
        operation.registerCancellation(() -> {
            throw closeFailure;
        });
        SimpleMeterRegistry admissionRegistry = new SimpleMeterRegistry();
        VueTurnAdmissionController admissionController =
                new VueTurnAdmissionController(
                        new VueBuildRepairMetricsCollector(admissionRegistry));
        VueTurnAdmissionController.AdmissionPermit admission =
                admissionController.tryAcquire().orElseThrow();
        VueTurnContext context = new VueTurnContext(
                APP_ID, USER_ID, "turn-root-close-failure",
                operation, lease, admission,
                new FileToolBudgetGuard().newSession());
        AtomicReference<Throwable> observedFailure = new AtomicReference<>();
        context.onFinalized(ignored -> { }, observedFailure::set);

        AssertionError thrown = assertThrows(
                AssertionError.class,
                () -> finalizer.finalizeOnce(context, outcome(
                        VueBuildPhase.SUCCEEDED, SUCCEEDED,
                        "项目已生成并构建成功。", true)));

        assertSame(persistenceFailure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(closeFailure, thrown.getSuppressed()[0]);
        CompletionException sharedFailure = assertThrows(
                CompletionException.class, context::awaitFinalization);
        assertSame(persistenceFailure, sharedFailure.getCause());
        assertSame(persistenceFailure, observedFailure.get());
        assertEquals(VueTurnContext.TurnStage.FINALIZED,
                context.turnState().stage());
        operationManager.acquire(
                APP_ID, AppOperationLeaseManager.AppOperationType.GENERATE,
                "after-close-failure").close();
        assertEquals(1.0, admissionRegistry
                .get("vue_turn_admissions_total")
                .tag("result", "released").counter().count(),
                "关闭失败后仍必须释放准入许可");
    }

    @Test
    void fatalObserverErrorPropagatesWithoutRewritingSharedRootFailure() {
        AssertionError persistenceFailure =
                new AssertionError("persist-root-before-fatal-observer");
        OutOfMemoryError observerFailure =
                new OutOfMemoryError("fatal-observer");
        when(history.addChatMessage(anyLong(), anyString(), eq("ai"), anyLong()))
                .thenThrow(persistenceFailure);
        VueTurnContext context = VueTurnContext.testing(
                APP_ID, USER_ID, "turn-fatal-observer",
                VueBuildPhase.SUCCEEDED);
        context.onFinalized(ignored -> { }, ignored -> {
            throw observerFailure;
        });

        OutOfMemoryError thrown = assertThrows(
                OutOfMemoryError.class,
                () -> finalizer.finalizeOnce(context, outcome(
                        VueBuildPhase.SUCCEEDED, SUCCEEDED,
                        "项目已生成并构建成功。", true)));

        assertSame(observerFailure, thrown);
        CompletionException sharedFailure = assertThrows(
                CompletionException.class, context::awaitFinalization);
        assertSame(persistenceFailure, sharedFailure.getCause());
        assertEquals(VueTurnContext.TurnStage.FINALIZED,
                context.turnState().stage());
    }

    private VueTurnOutcome outcome(VueBuildPhase phase,
            VueTurnOutcome.TurnOutcomeType type, String text, boolean refresh) {
        return new VueTurnOutcome(phase, type, text, refresh, text);
    }
}
