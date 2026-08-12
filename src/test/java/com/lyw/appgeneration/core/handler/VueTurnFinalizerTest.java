package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.ai.AiGeneratorServiceFactory;
import com.lyw.appgeneration.ai.memory.ToolMessageCollapser;
import com.lyw.appgeneration.core.builder.VueBuildPhase;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.UserMemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

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
    private VueTurnFinalizer finalizer;

    @BeforeEach
    void setUp() {
        history = mock(ChatHistoryService.class);
        collapser = mock(ToolMessageCollapser.class);
        summary = mock(MemorySummaryService.class);
        preference = mock(UserMemoryService.class);
        factory = mock(AiGeneratorServiceFactory.class);
        finalizer = new VueTurnFinalizer(history, collapser, summary, preference, factory);
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
            clearInvocations(history, collapser, summary, preference, factory);
        }
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
            assertSame(first.get(), second.get());
        }

        verify(history, times(1)).addChatMessage(eq(APP_ID), anyString(), eq("ai"), eq(USER_ID));
        verify(collapser, times(1)).collapseLastTurn(eq(APP_ID), anyString());
        verify(summary, times(1)).triggerSummarizationAsync(APP_ID);
        verify(preference, times(1)).triggerPreferenceExtractionAsync(USER_ID, APP_ID);
    }

    @Test
    void redisFailureKeepsBuildOutcomeButInvalidatesVueService() {
        when(collapser.collapseLastTurn(APP_ID, "项目已生成并构建成功。"))
                .thenReturn(new ToolMessageCollapser.CollapseResult(STORE_FAILED, java.util.List.of()));
        VueTurnContext context = VueTurnContext.testing(
                APP_ID, USER_ID, "turn-store", VueBuildPhase.SUCCEEDED);

        VueTurnFinalizer.FinalizationResult result = finalizer.finalizeOnce(context,
                outcome(VueBuildPhase.SUCCEEDED, SUCCEEDED, "项目已生成并构建成功。", true));

        assertEquals(SUCCEEDED, result.outcome().outcome());
        verify(factory).invalidateVueService(APP_ID);
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
        verify(factory).invalidateVueService(APP_ID);
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

    private VueTurnOutcome outcome(VueBuildPhase phase,
            VueTurnOutcome.TurnOutcomeType type, String text, boolean refresh) {
        return new VueTurnOutcome(phase, type, text, refresh, text);
    }
}
