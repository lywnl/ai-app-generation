package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.memory.ChatTokenEstimator;
import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.mapper.AppMemorySummaryMapper;
import com.lyw.appgeneration.model.entity.AppMemorySummary;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemoryCompressionResult;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** L1 3K 硬上限、完整回合游标、single-flight 与删除栅栏测试。 */
class MemorySummaryServiceImplTest {

    @Mock
    ChatHistoryService chatHistoryService;
    @Mock
    AppMemorySummaryMapper summaryMapper;
    @Mock
    ChatModel summarizationModel;
    @Mock
    ChatTokenEstimator tokenEstimator;
    @Mock
    StringRedisTemplate redisTemplate;
    @Mock
    ValueOperations<String, String> valueOps;

    private final Map<String, Integer> estimatedTokens = new HashMap<>();
    private AppDataLifecycleFence lifecycleFence;
    private MemoryTokenProperties properties;
    private MutableClock clock;
    private MemorySummaryServiceImpl service;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        lifecycleFence = new AppDataLifecycleFence();
        properties = new MemoryTokenProperties();
        clock = new MutableClock(
                Instant.parse("2026-08-15T12:00:00Z"), ZoneOffset.UTC);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(tokenEstimator.estimateText(anyString())).thenAnswer(invocation ->
                estimatedTokens.getOrDefault(invocation.getArgument(0), 1_000));
        service = newService(mock(ExecutorService.class));
    }

    @Test
    void compressesOnceAndPersistsExactEstimatedTokens() {
        String summary = textWithTokens("三千词元摘要", 3_000);
        when(summaryMapper.selectOneByQuery(any())).thenReturn(null);
        when(chatHistoryService.listMessagesAfterCursor(1L, 0L, 100))
                .thenReturn(List.of(
                        message(10L, "user", "做个待办应用"),
                        message(11L, "ai", "已确认目标")));
        when(summarizationModel.chat(anyString())).thenReturn(summary);

        MemoryCompressionResult result = service.compressNow(
                1L, 11L, Duration.ofSeconds(60));

        assertEquals(MemoryCompressionResult.Status.COMPRESSED, result.status());
        assertEquals(11L, result.summarizedThroughId());
        assertEquals(3_000, result.summaryTokens());
        ArgumentCaptor<AppMemorySummary> persisted =
                ArgumentCaptor.forClass(AppMemorySummary.class);
        verify(summaryMapper).insert(persisted.capture());
        assertEquals(summary, persisted.getValue().getSummary());
        assertEquals(11L, persisted.getValue().getLastSummarizedId());
        assertEquals(3_000, persisted.getValue().getSummaryTokens());
        assertNotNull(persisted.getValue().getCreateTime());
        verify(valueOps).set(eq("mem:summary:1"), eq(summary), any(Duration.class));
    }

    @Test
    void oversizedFirstOutputIsReducedUntilItFitsThreeK() {
        String oversized = textWithTokens("四千词元摘要", 4_000);
        String reduced = textWithTokens("三千零七十二词元摘要", 3_072);
        when(summaryMapper.selectOneByQuery(any())).thenReturn(null);
        when(chatHistoryService.listMessagesAfterCursor(1L, 0L, 100))
                .thenReturn(List.of(
                        message(10L, "user", "新增问题"),
                        message(11L, "ai", "新增回复")));
        when(summarizationModel.chat(anyString()))
                .thenReturn(oversized, reduced);

        MemoryCompressionResult result = service.compressNow(
                1L, 11L, Duration.ofSeconds(60));

        assertEquals(MemoryCompressionResult.Status.COMPRESSED, result.status());
        assertEquals(3_072, result.summaryTokens());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(summarizationModel, times(2)).chat(prompts.capture());
        assertTrue(prompts.getAllValues().get(1).contains(oversized));
        assertFalse(prompts.getAllValues().get(1).contains("新增问题"),
                "二次压缩只能处理现有摘要，不能重新引入原始对话");
    }

    @Test
    void keepsReducingWhenSecondOutputIsStillOverThreeKButImproving() {
        String first = textWithTokens("首次四千摘要", 4_000);
        String second = textWithTokens("二次三千五摘要", 3_500);
        String third = textWithTokens("三次达标摘要", 3_000);
        when(summaryMapper.selectOneByQuery(any())).thenReturn(null);
        when(chatHistoryService.listMessagesAfterCursor(1L, 0L, 100))
                .thenReturn(List.of(
                        message(10L, "user", "新增问题"),
                        message(11L, "ai", "新增回复")));
        when(summarizationModel.chat(anyString()))
                .thenReturn(first, second, third);

        MemoryCompressionResult result = service.compressNow(
                1L, 11L, Duration.ofSeconds(60));

        assertEquals(MemoryCompressionResult.Status.COMPRESSED, result.status());
        assertEquals(3_000, result.summaryTokens());
        verify(summarizationModel, times(3)).chat(anyString());
    }

    @Test
    void unchangedOversizedReducerOutputFailsWithoutAdvancingCursorOrCache() {
        String oversized = textWithTokens("始终超限摘要", 4_000);
        AppMemorySummary current = currentSummary(5L, "旧摘要", 100, 0);
        when(summaryMapper.selectOneByQuery(any())).thenReturn(current);
        when(chatHistoryService.listMessagesAfterCursor(1L, 5L, 100))
                .thenReturn(List.of(
                        message(6L, "user", "新约束"),
                        message(7L, "ai", "已记录")));
        when(summarizationModel.chat(anyString()))
                .thenReturn(oversized, oversized);

        MemoryCompressionResult result = service.compressNow(
                1L, 7L, Duration.ofSeconds(60));

        assertEquals(MemoryCompressionResult.Status.OUTPUT_STILL_TOO_LARGE,
                result.status());
        assertEquals(5L, current.getLastSummarizedId());
        assertEquals("旧摘要", current.getSummary());
        assertEquals(1, current.getFailCount());
        verify(summaryMapper).update(same(current));
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void onlyAdjacentCompleteTurnsAtOrBelowBoundaryEnterPrompt() {
        String summary = textWithTokens("边界摘要", 800);
        when(summaryMapper.selectOneByQuery(any())).thenReturn(null);
        when(chatHistoryService.listMessagesAfterCursor(1L, 0L, 100))
                .thenReturn(List.of(
                        message(1L, "ai", "孤立回复"),
                        message(2L, "user", "有效问题"),
                        message(3L, "ai", "有效回复"),
                        message(4L, "user", "边界内未闭合问题"),
                        message(5L, "ai", "边界外回复")));
        when(summarizationModel.chat(anyString())).thenReturn(summary);

        MemoryCompressionResult result = service.compressNow(
                1L, 4L, Duration.ofSeconds(60));

        assertEquals(MemoryCompressionResult.Status.COMPRESSED, result.status());
        assertEquals(3L, result.summarizedThroughId());
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(summarizationModel).chat(prompt.capture());
        assertTrue(prompt.getValue().contains("有效问题"));
        assertTrue(prompt.getValue().contains("有效回复"));
        assertFalse(prompt.getValue().contains("孤立回复"));
        assertFalse(prompt.getValue().contains("边界内未闭合问题"));
        assertFalse(prompt.getValue().contains("边界外回复"));
    }

    @Test
    void requestedCursorRangeExcludesLaterCompleteTurns() {
        String summary = textWithTokens("范围摘要", 900);
        AppMemorySummary current = currentSummary(5L, "旧摘要", 100, 0);
        when(summaryMapper.selectOneByQuery(any())).thenReturn(current);
        when(chatHistoryService.listMessagesAfterCursor(1L, 5L, 100))
                .thenReturn(List.of(
                        message(6L, "user", "范围内问题"),
                        message(7L, "ai", "范围内回复"),
                        message(8L, "user", "范围外问题"),
                        message(9L, "ai", "范围外回复")));
        when(summarizationModel.chat(anyString())).thenReturn(summary);

        MemoryCompressionResult result = service.compressNow(
                1L, 7L, Duration.ofSeconds(60));

        assertEquals(7L, result.summarizedThroughId());
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(summarizationModel).chat(prompt.capture());
        assertTrue(prompt.getValue().contains("范围内问题"));
        assertFalse(prompt.getValue().contains("范围外问题"));
    }

    @Test
    void tokenControlledInputSplitsTurnsIntoMultipleRollingBatches() {
        String firstSummary = textWithTokens("第一批摘要", 900);
        String finalSummary = textWithTokens("最终摘要", 1_000);
        when(summaryMapper.selectOneByQuery(any())).thenReturn(null);
        when(chatHistoryService.listMessagesAfterCursor(1L, 0L, 100))
                .thenReturn(List.of(
                        message(1L, "user", "第一轮问题"),
                        message(2L, "ai", "第一轮回复"),
                        message(3L, "user", "第二轮问题"),
                        message(4L, "ai", "第二轮回复")));
        when(tokenEstimator.estimateText(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            if (text.isEmpty()) {
                return 0;
            }
            Integer exact = estimatedTokens.get(text);
            if (exact != null) {
                return exact;
            }
            return text.contains("第一轮问题") && text.contains("第二轮问题")
                    ? 40_000 : 10_000;
        });
        List<String> observedPrompts = new CopyOnWriteArrayList<>();
        when(summarizationModel.chat(anyString())).thenAnswer(invocation -> {
            observedPrompts.add(invocation.getArgument(0));
            return observedPrompts.size() == 1 ? firstSummary : finalSummary;
        });

        MemoryCompressionResult result = service.compressNow(
                1L, 4L, Duration.ofSeconds(60));

        assertEquals(MemoryCompressionResult.Status.COMPRESSED, result.status());
        assertEquals(4L, result.summarizedThroughId());
        assertEquals(2, observedPrompts.size(), () -> observedPrompts.stream()
                .map(prompt -> prompt.contains("只压缩现有摘要")
                        ? "reducer"
                        : prompt.contains("第一轮问题") && prompt.contains("第二轮问题")
                                ? "双回合摘要"
                                : prompt.contains("第一轮问题")
                                        ? "第一回合摘要" : "第二回合摘要")
                .toList().toString());
    }

    @Test
    void emptyModelOutputFailsAndKeepsOldSummaryAndCursor() {
        AppMemorySummary current = currentSummary(5L, "旧摘要", 100, 0);
        when(summaryMapper.selectOneByQuery(any())).thenReturn(current);
        when(chatHistoryService.listMessagesAfterCursor(1L, 5L, 100))
                .thenReturn(List.of(
                        message(6L, "user", "问题"),
                        message(7L, "ai", "回复")));
        when(summarizationModel.chat(anyString())).thenReturn("  ");

        MemoryCompressionResult result = service.compressNow(
                1L, 7L, Duration.ofSeconds(60));

        assertEquals(MemoryCompressionResult.Status.MODEL_FAILED, result.status());
        assertEquals(5L, current.getLastSummarizedId());
        assertEquals("旧摘要", current.getSummary());
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void modelExceptionDoesNotAdvanceCursorOrEscape() {
        AppMemorySummary current = currentSummary(5L, "旧摘要", 100, 0);
        when(summaryMapper.selectOneByQuery(any())).thenReturn(current);
        when(chatHistoryService.listMessagesAfterCursor(1L, 5L, 100))
                .thenReturn(List.of(
                        message(6L, "user", "问题"),
                        message(7L, "ai", "回复")));
        when(summarizationModel.chat(anyString()))
                .thenThrow(new IllegalStateException("model down"));

        MemoryCompressionResult result = assertDoesNotThrow(() ->
                service.compressNow(1L, 7L, Duration.ofSeconds(60)));

        assertEquals(MemoryCompressionResult.Status.MODEL_FAILED, result.status());
        assertEquals(5L, current.getLastSummarizedId());
        assertEquals(1, current.getFailCount());
    }

    @Test
    void databaseUpdateFailureCannotLeakNewSummaryIntoFailureMetadataWrite() {
        String newSummary = textWithTokens("本次新摘要", 700);
        AppMemorySummary current = currentSummary(5L, "旧摘要", 100, 0);
        when(summaryMapper.selectOneByQuery(any())).thenReturn(current);
        when(chatHistoryService.listMessagesAfterCursor(1L, 5L, 100))
                .thenReturn(List.of(
                        message(6L, "user", "问题"),
                        message(7L, "ai", "回复")));
        when(summarizationModel.chat(anyString())).thenReturn(newSummary);
        AtomicReference<AppMemorySummary> failureMetadata = new AtomicReference<>();
        when(summaryMapper.update(any(AppMemorySummary.class)))
                .thenThrow(new IllegalStateException("database down"))
                .thenAnswer(invocation -> {
                    failureMetadata.set(invocation.getArgument(0));
                    return 1;
                });

        MemoryCompressionResult result = service.compressNow(
                1L, 7L, Duration.ofSeconds(60));

        assertEquals(MemoryCompressionResult.Status.MODEL_FAILED, result.status());
        assertNotNull(failureMetadata.get());
        assertEquals("旧摘要", failureMetadata.get().getSummary());
        assertEquals(5L, failureMetadata.get().getLastSummarizedId());
        assertEquals(1, failureMetadata.get().getFailCount());
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void exhaustedDeadlineReturnsTimedOutBeforeModelCall() {
        MemoryCompressionResult result = service.compressNow(
                1L, 7L, Duration.ZERO);

        assertEquals(MemoryCompressionResult.Status.TIMED_OUT, result.status());
        verifyNoInteractions(summaryMapper, chatHistoryService, summarizationModel);
    }

    @Test
    void noCompleteTurnReturnsNothingWithoutWriting() {
        when(summaryMapper.selectOneByQuery(any())).thenReturn(null);
        when(chatHistoryService.listMessagesAfterCursor(1L, 0L, 100))
                .thenReturn(List.of(message(1L, "user", "孤立问题")));

        MemoryCompressionResult result = service.compressNow(
                1L, 1L, Duration.ofSeconds(60));

        assertEquals(MemoryCompressionResult.Status.NOTHING_TO_COMPRESS,
                result.status());
        verifyNoInteractions(summarizationModel);
        verify(summaryMapper, never()).insert(any());
        verify(summaryMapper, never()).update(any());
    }

    @Test
    void getCurrentSummaryReturnsCachedAndSkipsDb() {
        when(valueOps.get("mem:summary:1")).thenReturn("# 应用目标\n缓存命中");

        assertEquals("# 应用目标\n缓存命中", service.getCurrentSummary(1L));

        verify(summaryMapper, never()).selectOneByQuery(any());
    }

    @Test
    void getCurrentSummaryMissQueriesDbAndPopulatesCache() {
        when(valueOps.get("mem:summary:1")).thenReturn(null);
        when(summaryMapper.selectOneByQuery(any())).thenReturn(
                AppMemorySummary.builder().appId(1L)
                        .summary("# 应用目标\n来自数据库").build());

        assertEquals("# 应用目标\n来自数据库", service.getCurrentSummary(1L));

        verify(valueOps).set(eq("mem:summary:1"),
                eq("# 应用目标\n来自数据库"), any(Duration.class));
    }

    @Test
    void getCurrentSummaryRedisFailureFallsBackToDatabase() {
        when(valueOps.get(anyString()))
                .thenThrow(new IllegalStateException("redis down"));
        when(summaryMapper.selectOneByQuery(any())).thenReturn(
                AppMemorySummary.builder().appId(1L)
                        .summary("# 应用目标\n降级读取").build());

        assertEquals("# 应用目标\n降级读取", service.getCurrentSummary(1L));
    }

    @Test
    void redisWriteFailureDoesNotUndoSuccessfulDatabaseCompression() {
        String summary = textWithTokens("数据库已成功摘要", 700);
        when(summaryMapper.selectOneByQuery(any())).thenReturn(null);
        when(chatHistoryService.listMessagesAfterCursor(1L, 0L, 100))
                .thenReturn(List.of(
                        message(1L, "user", "问题"),
                        message(2L, "ai", "回复")));
        when(summarizationModel.chat(anyString())).thenReturn(summary);
        doThrow(new IllegalStateException("redis down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        MemoryCompressionResult result = assertDoesNotThrow(() ->
                service.compressNow(1L, 2L, Duration.ofSeconds(60)));

        assertEquals(MemoryCompressionResult.Status.COMPRESSED, result.status());
        verify(summaryMapper).insert(any());
    }

    @Test
    void cacheInvalidationReportsRedisFailureAndRejectsInvalidIds() {
        doThrow(new IllegalStateException("redis down"))
                .when(redisTemplate).delete("mem:summary:1");

        assertEquals(java.util.Set.of("L1_SUMMARY_REDIS"),
                service.invalidateCache(1L).failedTargets());
        assertThrows(IllegalArgumentException.class,
                () -> service.invalidateCache(null));
        assertThrows(IllegalArgumentException.class,
                () -> service.invalidateCache(0L));
    }

    @Test
    void backgroundFailureBacksOffThenRetriesAfterDelay() {
        ExecutorService executor = mock(ExecutorService.class);
        when(executor.submit(any(Runnable.class))).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return mock(Future.class);
        });
        AppMemorySummary current = currentSummary(0L, "", 0, 0);
        when(summaryMapper.selectOneByQuery(any())).thenReturn(current);
        when(chatHistoryService.listMessagesAfterCursor(1L, 0L, 100))
                .thenReturn(List.of(
                        message(1L, "user", "问题"),
                        message(2L, "ai", "回复")));
        String recovered = textWithTokens("恢复后的摘要", 600);
        when(summarizationModel.chat(anyString()))
                .thenThrow(new IllegalStateException("model down"))
                .thenReturn(recovered);
        MemorySummaryServiceImpl background = newService(executor);

        background.triggerSummarizationAsync(1L, 2L);
        background.triggerSummarizationAsync(1L, 2L);
        verify(executor, times(1)).submit(any(Runnable.class));

        clock.advance(Duration.ofSeconds(6));
        background.triggerSummarizationAsync(1L, 2L);

        verify(executor, times(2)).submit(any(Runnable.class));
        verify(summarizationModel, times(2)).chat(anyString());
    }

    @Test
    void rejectedSubmissionReleasesFlightAndWriterPermit() {
        ExecutorService executor = mock(ExecutorService.class);
        when(executor.submit(any(Runnable.class)))
                .thenThrow(new RejectedExecutionException("queue full"));
        when(summaryMapper.selectOneByQuery(any())).thenReturn(null);
        MemorySummaryServiceImpl background = newService(executor);

        background.triggerSummarizationAsync(1L, 2L);

        AppDataLifecycleFence.DeletePermit deletion =
                lifecycleFence.beginDelete(1L, Duration.ZERO);
        assertNotNull(deletion);
        deletion.abortAndReopen();
    }

    @Test
    void rejectedSubmissionCleansFlightEvenWhenFailureMetadataLookupFails() {
        ExecutorService executor = mock(ExecutorService.class);
        when(executor.submit(any(Runnable.class)))
                .thenThrow(new RejectedExecutionException("queue full"));
        when(summaryMapper.selectOneByQuery(any()))
                .thenThrow(new IllegalStateException("database down"));
        MemorySummaryServiceImpl background = newService(executor);

        assertDoesNotThrow(() ->
                background.triggerSummarizationAsync(1L, 2L));

        AppDataLifecycleFence.DeletePermit deletion =
                lifecycleFence.beginDelete(1L, Duration.ZERO);
        assertNotNull(deletion);
        deletion.abortAndReopen();
        clock.advance(Duration.ofSeconds(6));
        background.triggerSummarizationAsync(1L, 2L);
        verify(executor, times(2)).submit(any(Runnable.class));
    }

    @Test
    void deleteGateRejectsBackgroundAndSynchronousCompression() {
        ExecutorService executor = mock(ExecutorService.class);
        MemorySummaryServiceImpl gated = newService(executor);
        AppDataLifecycleFence.DeletePermit deletion =
                lifecycleFence.beginDelete(1L, Duration.ofSeconds(1));
        assertNotNull(deletion);

        gated.triggerSummarizationAsync(1L, 2L);
        MemoryCompressionResult result = gated.compressNow(
                1L, 2L, Duration.ofSeconds(1));

        assertEquals(MemoryCompressionResult.Status.DELETE_REJECTED,
                result.status());
        verifyNoInteractions(executor, summaryMapper,
                chatHistoryService, summarizationModel);
        deletion.abortAndReopen();
    }

    @Test
    void queuedBackgroundCompressionHoldsDeleteFenceThroughCacheWrite()
            throws Exception {
        ExecutorService executor = mock(ExecutorService.class);
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        when(executor.submit(any(Runnable.class))).thenAnswer(invocation -> {
            submitted.set(invocation.getArgument(0));
            return mock(Future.class);
        });
        when(summaryMapper.selectOneByQuery(any())).thenReturn(null);
        when(chatHistoryService.listMessagesAfterCursor(1L, 0L, 100))
                .thenReturn(List.of(
                        message(1L, "user", "偏好"),
                        message(2L, "ai", "确认")));
        String summary = textWithTokens("摘要", 500);
        when(summarizationModel.chat(anyString())).thenReturn(summary);
        MemorySummaryServiceImpl background = newService(executor);

        background.triggerSummarizationAsync(1L, 2L);
        try (var threads = java.util.concurrent.Executors
                .newVirtualThreadPerTaskExecutor()) {
            Future<AppDataLifecycleFence.DeletePermit> deletion = threads.submit(() ->
                    lifecycleFence.beginDelete(1L, Duration.ofSeconds(2)));
            assertThrows(TimeoutException.class,
                    () -> deletion.get(100, TimeUnit.MILLISECONDS));

            submitted.get().run();

            AppDataLifecycleFence.DeletePermit permit =
                    deletion.get(1, TimeUnit.SECONDS);
            assertNotNull(permit);
            verify(summaryMapper).insert(any());
            verify(valueOps).set(eq("mem:summary:1"), eq(summary), any(Duration.class));
            permit.abortAndReopen();
        }
    }

    @Test
    void concurrentBackgroundTriggersShareOneFlight() {
        ExecutorService executor = mock(ExecutorService.class);
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        when(executor.submit(any(Runnable.class))).thenAnswer(invocation -> {
            submitted.set(invocation.getArgument(0));
            return mock(Future.class);
        });
        MemorySummaryServiceImpl background = newService(executor);

        background.triggerSummarizationAsync(1L, 2L);
        background.triggerSummarizationAsync(1L, 2L);

        verify(executor, times(1)).submit(any(Runnable.class));
        submitted.get().run();
    }

    @Test
    void synchronousWaitForExistingFlightCountsTowardDeadline() {
        ExecutorService executor = mock(ExecutorService.class);
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        when(executor.submit(any(Runnable.class))).thenAnswer(invocation -> {
            submitted.set(invocation.getArgument(0));
            return mock(Future.class);
        });
        MemorySummaryServiceImpl background = newService(executor);
        background.triggerSummarizationAsync(1L, 2L);

        MemoryCompressionResult result = background.compressNow(
                1L, 2L, Duration.ZERO);

        assertEquals(MemoryCompressionResult.Status.TIMED_OUT, result.status());
        verify(executor, times(1)).submit(any(Runnable.class));
        submitted.get().run();
    }

    @Test
    void synchronousJoinContinuesWhenExistingFlightCoveredEarlierBoundary()
            throws Exception {
        ExecutorService executor = mock(ExecutorService.class);
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        when(executor.submit(any(Runnable.class))).thenAnswer(invocation -> {
            submitted.set(invocation.getArgument(0));
            return mock(Future.class);
        });
        AtomicReference<AppMemorySummary> store = new AtomicReference<>();
        when(summaryMapper.selectOneByQuery(any())).thenAnswer(invocation ->
                store.get());
        when(summaryMapper.insert(any(AppMemorySummary.class))).thenAnswer(invocation -> {
            store.set(invocation.getArgument(0));
            return 1;
        });
        when(summaryMapper.update(any(AppMemorySummary.class))).thenAnswer(invocation -> {
            store.set(invocation.getArgument(0));
            return 1;
        });
        when(chatHistoryService.listMessagesAfterCursor(
                eq(1L), any(Long.class), eq(100))).thenAnswer(invocation -> {
            long cursor = invocation.getArgument(1);
            return cursor == 0L
                    ? List.of(
                            message(1L, "user", "第一轮问题"),
                            message(2L, "ai", "第一轮回复"),
                            message(3L, "user", "第二轮问题"),
                            message(4L, "ai", "第二轮回复"))
                    : List.of(
                            message(3L, "user", "第二轮问题"),
                            message(4L, "ai", "第二轮回复"));
        });
        String first = textWithTokens("第一轮摘要", 500);
        String second = textWithTokens("第二轮摘要", 600);
        when(summarizationModel.chat(anyString())).thenReturn(first, second);
        MemorySummaryServiceImpl background = newService(executor);
        background.triggerSummarizationAsync(1L, 2L);

        try (var threads = java.util.concurrent.Executors
                .newVirtualThreadPerTaskExecutor()) {
            Future<MemoryCompressionResult> synchronous = threads.submit(() ->
                    background.compressNow(
                            1L, 4L, Duration.ofSeconds(2)));

            submitted.get().run();

            MemoryCompressionResult result = synchronous.get(
                    1L, TimeUnit.SECONDS);
            assertEquals(MemoryCompressionResult.Status.COMPRESSED,
                    result.status());
            assertEquals(4L, result.summarizedThroughId());
            verify(summarizationModel, times(2)).chat(anyString());
        }
    }

    private MemorySummaryServiceImpl newService(ExecutorService executor) {
        return new MemorySummaryServiceImpl(
                chatHistoryService,
                summaryMapper,
                summarizationModel,
                executor,
                redisTemplate,
                lifecycleFence,
                tokenEstimator,
                properties,
                clock);
    }

    private String textWithTokens(String text, int tokens) {
        estimatedTokens.put(text, tokens);
        return text;
    }

    private ChatHistory message(long id, String type, String text) {
        return ChatHistory.builder()
                .id(id)
                .messageType(type)
                .message(text)
                .build();
    }

    private AppMemorySummary currentSummary(
            long cursor, String summary, int tokens, int failCount) {
        return AppMemorySummary.builder()
                .appId(1L)
                .lastSummarizedId(cursor)
                .summary(summary)
                .summaryTokens(tokens)
                .failCount(failCount)
                .build();
    }

    private static final class MutableClock extends Clock {

        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId targetZone) {
            return new MutableClock(instant, targetZone);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
