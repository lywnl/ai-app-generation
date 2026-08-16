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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.MockMakers.INLINE;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

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
    private ExecutorService modelExecutor;
    private MemorySummaryServiceImpl service;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        lifecycleFence = new AppDataLifecycleFence();
        properties = new MemoryTokenProperties();
        clock = new MutableClock(
                Instant.parse("2026-08-15T12:00:00Z"), ZoneOffset.UTC);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(summaryMapper.insert(any(AppMemorySummary.class))).thenReturn(1);
        when(summaryMapper.update(any(AppMemorySummary.class))).thenReturn(1);
        when(summaryMapper.update(
                any(AppMemorySummary.class), eq(false))).thenReturn(1);
        when(tokenEstimator.estimateText(anyString())).thenAnswer(invocation ->
                estimatedTokens.getOrDefault(invocation.getArgument(0), 1_000));
        modelExecutor = java.util.concurrent.Executors
                .newVirtualThreadPerTaskExecutor();
        service = newService(mock(ExecutorService.class));
    }

    @AfterEach
    void tearDown() {
        modelExecutor.shutdownNow();
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
        verify(summaryMapper).update(any(AppMemorySummary.class));
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
    @DisplayName("完整回合允许跨越恰好一百条的分页边界")
    void completeTurnSpansExactlyFullHistoryPage() {
        List<ChatHistory> firstPage = fullPageEndingWithUser();
        List<ChatHistory> secondPage = List.of(
                message(101L, "ai", "跨页回复"));
        String summary = textWithTokens("跨页摘要", 800);
        when(summaryMapper.selectOneByQuery(any())).thenReturn(null);
        when(chatHistoryService.listMessagesAfterCursor(1L, 0L, 100))
                .thenReturn(firstPage);
        when(chatHistoryService.listMessagesAfterCursor(1L, 100L, 100))
                .thenReturn(secondPage);
        when(summarizationModel.chat(anyString())).thenReturn(summary);

        MemoryCompressionResult result = service.compressNow(
                1L, 101L, Duration.ofSeconds(60));

        assertEquals(100, firstPage.size());
        assertEquals("user", firstPage.getLast().getMessageType());
        assertEquals("ai", secondPage.getFirst().getMessageType());
        assertEquals(MemoryCompressionResult.Status.COMPRESSED,
                result.status());
        assertEquals(101L, result.summarizedThroughId());
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(summarizationModel).chat(prompt.capture());
        assertTrue(prompt.getValue().contains("跨页问题"));
        assertTrue(prompt.getValue().contains("跨页回复"));
        verify(chatHistoryService).listMessagesAfterCursor(1L, 0L, 100);
        verify(chatHistoryService).listMessagesAfterCursor(1L, 100L, 100);
    }

    @Test
    @DisplayName("摘要边界停在第一页末条 USER 时不读取下一页")
    void boundaryAtPageEndingUserDoesNotReadNextPage() {
        List<ChatHistory> firstPage = fullPageEndingWithUser();
        when(summaryMapper.selectOneByQuery(any())).thenReturn(null);
        when(chatHistoryService.listMessagesAfterCursor(1L, 0L, 100))
                .thenReturn(firstPage);

        MemoryCompressionResult result = service.compressNow(
                1L, 100L, Duration.ofSeconds(60));

        assertEquals(100, firstPage.size());
        assertEquals("user", firstPage.getLast().getMessageType());
        assertEquals(MemoryCompressionResult.Status.NOTHING_TO_COMPRESS,
                result.status());
        assertEquals(0L, result.summarizedThroughId());
        verifyNoInteractions(summarizationModel);
        verify(summaryMapper, never()).insert(any());
        verify(summaryMapper, never()).update(any());
        verify(valueOps, never()).set(
                anyString(), anyString(), any(Duration.class));
        verify(chatHistoryService, never())
                .listMessagesAfterCursor(1L, 100L, 100);
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
    @DisplayName("模型调用超时后取消任务且迟到结果不能落库或写缓存")
    void modelTimeoutCancelsLateResultBeforePersistence() throws Exception {
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        CountDownLatch modelFinished = new CountDownLatch(1);
        String lateSummary = textWithTokens("迟到摘要", 600);
        AppMemorySummary current = currentSummary(0L, "旧摘要", 100, 0);
        when(summaryMapper.selectOneByQuery(any())).thenReturn(current);
        when(chatHistoryService.listMessagesAfterCursor(1L, 0L, 100))
                .thenReturn(List.of(
                        message(1L, "user", "问题"),
                        message(2L, "ai", "回复")));
        when(summarizationModel.chat(anyString())).thenAnswer(invocation -> {
            modelStarted.countDown();
            boolean released = false;
            while (!released) {
                try {
                    releaseModel.await();
                    released = true;
                } catch (InterruptedException ignored) {
                    // 模拟底层客户端忽略中断后仍返回迟到结果。
                }
            }
            modelFinished.countDown();
            return lateSummary;
        });

        try (ExecutorService callers = java.util.concurrent.Executors
                .newVirtualThreadPerTaskExecutor()) {
            Future<MemoryCompressionResult> compression = callers.submit(() ->
                    service.compressNow(
                            1L, 2L, Duration.ofMillis(100)));
            assertTrue(modelStarted.await(1L, TimeUnit.SECONDS));
            MemoryCompressionResult result;
            try {
                result = compression.get(1L, TimeUnit.SECONDS);
            } finally {
                releaseModel.countDown();
            }

            assertEquals(MemoryCompressionResult.Status.TIMED_OUT,
                    result.status());
            assertTrue(modelFinished.await(1L, TimeUnit.SECONDS));
            assertEquals("旧摘要", current.getSummary());
            assertEquals(0L, current.getLastSummarizedId());
            verify(summaryMapper, never()).insert(any());
            verify(summaryMapper).update(any(AppMemorySummary.class));
            verify(valueOps, never()).set(
                    anyString(), anyString(), any(Duration.class));
        }
    }

    @Test
    @DisplayName("摘要模型执行器拒绝任务时返回类型化失败且不直接调用模型")
    void rejectedModelTaskReturnsTypedFailure() {
        ExecutorService rejectedModelExecutor = mock(ExecutorService.class);
        when(rejectedModelExecutor.submit(any(Callable.class)))
                .thenThrow(new RejectedExecutionException("queue full"));
        AppMemorySummary current = currentSummary(0L, "旧摘要", 100, 0);
        when(summaryMapper.selectOneByQuery(any())).thenReturn(current);
        when(chatHistoryService.listMessagesAfterCursor(1L, 0L, 100))
                .thenReturn(List.of(
                        message(1L, "user", "问题"),
                        message(2L, "ai", "回复")));
        when(summarizationModel.chat(anyString()))
                .thenReturn(textWithTokens("不应生成的摘要", 600));
        MemorySummaryServiceImpl rejected = newService(
                mock(ExecutorService.class), rejectedModelExecutor);

        MemoryCompressionResult result = rejected.compressNow(
                1L, 2L, Duration.ofSeconds(1));

        assertEquals(MemoryCompressionResult.Status.MODEL_FAILED,
                result.status());
        verifyNoInteractions(summarizationModel);
        verify(summaryMapper, never()).insert(any());
        verify(summaryMapper).update(any(AppMemorySummary.class));
        verify(valueOps, never()).set(
                anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("等待模型时被中断会取消模型任务并保留线程中断标记")
    void interruptedModelWaitCancelsTaskAndRestoresInterruptFlag()
            throws Exception {
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        AtomicReference<Thread> callerThread = new AtomicReference<>();
        AtomicBoolean interruptedAfterReturn = new AtomicBoolean();
        AppMemorySummary current = currentSummary(0L, "旧摘要", 100, 0);
        when(summaryMapper.selectOneByQuery(any())).thenReturn(current);
        when(chatHistoryService.listMessagesAfterCursor(1L, 0L, 100))
                .thenReturn(List.of(
                        message(1L, "user", "问题"),
                        message(2L, "ai", "回复")));
        when(summarizationModel.chat(anyString())).thenAnswer(invocation -> {
            modelStarted.countDown();
            boolean released = false;
            while (!released) {
                try {
                    releaseModel.await();
                    released = true;
                } catch (InterruptedException ignored) {
                    // 模拟底层客户端吞掉取消中断后继续执行。
                }
            }
            return textWithTokens("迟到摘要", 600);
        });

        try (ExecutorService callers = java.util.concurrent.Executors
                .newVirtualThreadPerTaskExecutor()) {
            Future<MemoryCompressionResult> compression = callers.submit(() -> {
                callerThread.set(Thread.currentThread());
                MemoryCompressionResult result = service.compressNow(
                        1L, 2L, Duration.ofSeconds(5));
                interruptedAfterReturn.set(
                        Thread.currentThread().isInterrupted());
                return result;
            });
            assertTrue(modelStarted.await(1L, TimeUnit.SECONDS));
            callerThread.get().interrupt();
            MemoryCompressionResult result;
            try {
                result = compression.get(1L, TimeUnit.SECONDS);
            } finally {
                releaseModel.countDown();
            }

            assertEquals(MemoryCompressionResult.Status.TIMED_OUT,
                    result.status());
            assertTrue(interruptedAfterReturn.get());
            assertEquals("旧摘要", current.getSummary());
            assertEquals(0L, current.getLastSummarizedId());
            verify(valueOps, never()).set(
                    anyString(), anyString(), any(Duration.class));
        }
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
        when(summaryMapper.update(
                any(AppMemorySummary.class), eq(false)))
                .thenThrow(new IllegalStateException("database down"));
        when(summaryMapper.update(any(AppMemorySummary.class)))
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
    @DisplayName("新增摘要写入零行时返回失败且不刷新缓存")
    void zeroRowInsertFailsWithoutRefreshingCache() {
        String newSummary = textWithTokens("本次新摘要", 700);
        when(summaryMapper.selectOneByQuery(any())).thenReturn(null);
        when(summaryMapper.insert(any(AppMemorySummary.class)))
                .thenReturn(0, 1);
        when(chatHistoryService.listMessagesAfterCursor(1L, 0L, 100))
                .thenReturn(List.of(
                        message(1L, "user", "问题"),
                        message(2L, "ai", "回复")));
        when(summarizationModel.chat(anyString())).thenReturn(newSummary);

        MemoryCompressionResult result = service.compressNow(
                1L, 2L, Duration.ofSeconds(60));

        assertEquals(MemoryCompressionResult.Status.MODEL_FAILED,
                result.status());
        verify(summaryMapper, times(2)).insert(any(AppMemorySummary.class));
        verify(valueOps, never()).set(
                anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("更新摘要写入零行时返回失败且不刷新缓存")
    void zeroRowUpdateFailsWithoutRefreshingCache() {
        String newSummary = textWithTokens("本次新摘要", 700);
        AppMemorySummary current = currentSummary(5L, "旧摘要", 100, 0);
        when(summaryMapper.selectOneByQuery(any())).thenReturn(current);
        when(summaryMapper.update(
                any(AppMemorySummary.class), eq(false))).thenReturn(0);
        when(chatHistoryService.listMessagesAfterCursor(1L, 5L, 100))
                .thenReturn(List.of(
                        message(6L, "user", "问题"),
                        message(7L, "ai", "回复")));
        when(summarizationModel.chat(anyString())).thenReturn(newSummary);

        MemoryCompressionResult result = service.compressNow(
                1L, 7L, Duration.ofSeconds(60));

        assertEquals(MemoryCompressionResult.Status.MODEL_FAILED,
                result.status());
        assertEquals("旧摘要", current.getSummary());
        assertEquals(5L, current.getLastSummarizedId());
        verify(summaryMapper).update(any(AppMemorySummary.class), eq(false));
        verify(summaryMapper).update(any(AppMemorySummary.class));
        verify(valueOps, never()).set(
                anyString(), anyString(), any(Duration.class));
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
    void lastSummarizedIdReadsPersistedCursorWithoutUsingSummaryCache() {
        when(summaryMapper.selectOneByQuery(any())).thenReturn(
                currentSummary(42L, "旧摘要", 800, 0));

        long cursor = service.lastSummarizedId(1L);

        assertEquals(42L, cursor);
        verifyNoInteractions(valueOps);
    }

    @Test
    void lastSummarizedIdFailsClosedWhenDeletionHasTombstonedApp() {
        AppDataLifecycleFence.DeletePermit deletion =
                lifecycleFence.beginDelete(1L, Duration.ZERO);
        assertNotNull(deletion);
        deletion.commitTombstone();

        assertThrows(IllegalStateException.class,
                () -> service.lastSummarizedId(1L));
        verifyNoInteractions(summaryMapper, valueOps);
    }

    @Test
    void lastSummarizedIdDatabaseFailureIsPropagatedWithCursorContext() {
        IllegalStateException databaseFailure =
                new IllegalStateException("database down");
        when(summaryMapper.selectOneByQuery(any()))
                .thenThrow(databaseFailure);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> service.lastSummarizedId(1L));

        assertTrue(thrown.getMessage().contains("摘要游标"));
        assertSame(databaseFailure, thrown.getCause());
    }

    @Test
    @DisplayName("应用进入 tombstone 后摘要读取返回空且不访问缓存或数据库")
    void tombstonedAppReturnsEmptyWithoutCacheOrDatabaseRead() {
        when(valueOps.get("mem:summary:1")).thenReturn("已删除应用的旧缓存");
        AppDataLifecycleFence.DeletePermit deletion =
                lifecycleFence.beginDelete(1L, Duration.ZERO);
        assertNotNull(deletion);
        deletion.commitTombstone();

        String summary = service.getCurrentSummary(1L);

        assertEquals("", summary);
        verifyNoInteractions(valueOps, summaryMapper);
    }

    @Test
    @DisplayName("writer 释放后的迟到异常不得在 tombstone 后复活 L1 索引")
    @SuppressWarnings("unchecked")
    void 删除失效后迟到后台异常不恢复Fallback或InFlight() throws Exception {
        AppDataLifecycleFence realFence = new AppDataLifecycleFence();
        AppDataLifecycleFence delegatedFence = mock(
                AppDataLifecycleFence.class,
                withSettings().mockMaker(INLINE));
        CountDownLatch writerReleased = new CountDownLatch(1);
        CountDownLatch allowCloseFailure = new CountDownLatch(1);
        when(delegatedFence.tryAcquireWriter(1L)).thenAnswer(invocation -> {
            AppDataLifecycleFence.WriterPermit realPermit =
                    realFence.tryAcquireWriter(1L);
            if (realPermit == null) {
                return null;
            }
            return (AppDataLifecycleFence.WriterPermit) () -> {
                realPermit.close();
                writerReleased.countDown();
                try {
                    if (!allowCloseFailure.await(1, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("等待删除失效超时");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "等待删除失效时被中断", exception);
                }
                throw new IllegalStateException("writer 关闭迟到异常");
            };
        });
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        ExecutorService backgroundExecutor = mock(ExecutorService.class);
        when(backgroundExecutor.submit(any(Runnable.class)))
                .thenAnswer(invocation -> {
                    submitted.set(invocation.getArgument(0));
                    return mock(Future.class);
                });
        when(summaryMapper.selectOneByQuery(any())).thenReturn(null);
        when(chatHistoryService.listMessagesAfterCursor(1L, 0L, 100))
                .thenReturn(List.of());
        MemorySummaryServiceImpl background = newService(
                backgroundExecutor, modelExecutor, delegatedFence);
        background.triggerSummarizationAsync(1L, 2L);

        try (ExecutorService threads = java.util.concurrent.Executors
                .newVirtualThreadPerTaskExecutor()) {
            Future<?> lateWorker = threads.submit(submitted.get());
            assertTrue(writerReleased.await(1, TimeUnit.SECONDS));
            AppDataLifecycleFence.DeletePermit deletion =
                    realFence.beginDelete(1L, Duration.ofSeconds(1));
            assertNotNull(deletion);
            deletion.commitTombstone();
            background.invalidateCache(1L);
            allowCloseFailure.countDown();
            lateWorker.get(1, TimeUnit.SECONDS);
        }

        Map<Long, Instant> fallbackRetryAfter =
                (Map<Long, Instant>) ReflectionTestUtils.getField(
                        background, "fallbackRetryAfter");
        Map<Long, ?> inFlight = (Map<Long, ?>) ReflectionTestUtils.getField(
                background, "inFlight");
        assertNotNull(fallbackRetryAfter);
        assertNotNull(inFlight);
        assertFalse(fallbackRetryAfter.containsKey(1L));
        assertFalse(inFlight.containsKey(1L));

        background.triggerSummarizationAsync(1L, 2L);
        verify(backgroundExecutor, times(1)).submit(any(Runnable.class));
    }

    @Test
    @DisplayName("缓存未命中的数据库读取与回填全程持有删除栅栏")
    void cacheMissReadThroughHoldsWriterPermitUntilCacheFill()
            throws Exception {
        CountDownLatch databaseReadStarted = new CountDownLatch(1);
        CountDownLatch releaseDatabaseRead = new CountDownLatch(1);
        when(valueOps.get("mem:summary:1")).thenReturn(null);
        when(summaryMapper.selectOneByQuery(any())).thenAnswer(invocation -> {
            databaseReadStarted.countDown();
            releaseDatabaseRead.await();
            return AppMemorySummary.builder()
                    .appId(1L)
                    .summary("# 应用目标\n数据库摘要")
                    .build();
        });

        try (ExecutorService threads = java.util.concurrent.Executors
                .newVirtualThreadPerTaskExecutor()) {
            Future<String> read = threads.submit(() ->
                    service.getCurrentSummary(1L));
            assertTrue(databaseReadStarted.await(1L, TimeUnit.SECONDS));
            Future<AppDataLifecycleFence.DeletePermit> deletion =
                    threads.submit(() -> lifecycleFence.beginDelete(
                            1L, Duration.ofSeconds(2)));
            try {
                assertThrows(TimeoutException.class,
                        () -> deletion.get(100L, TimeUnit.MILLISECONDS));
            } finally {
                releaseDatabaseRead.countDown();
            }

            assertEquals("# 应用目标\n数据库摘要",
                    read.get(1L, TimeUnit.SECONDS));
            AppDataLifecycleFence.DeletePermit deletePermit = deletion.get(
                    1L, TimeUnit.SECONDS);
            assertNotNull(deletePermit);
            verify(valueOps).set(eq("mem:summary:1"),
                    eq("# 应用目标\n数据库摘要"), any(Duration.class));
            deletePermit.abortAndReopen();
        }
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
    void normalFailurePersistsExactExponentialRetryTime() {
        AppMemorySummary current = currentSummary(0L, "旧摘要", 100, 2);
        when(summaryMapper.selectOneByQuery(any())).thenReturn(current);
        when(chatHistoryService.listMessagesAfterCursor(1L, 0L, 100))
                .thenReturn(List.of(
                        message(1L, "user", "问题"),
                        message(2L, "ai", "回复")));
        when(summarizationModel.chat(anyString()))
                .thenThrow(new IllegalStateException("model down"));

        MemoryCompressionResult result = service.compressNow(
                1L, 2L, Duration.ofSeconds(1));

        assertEquals(MemoryCompressionResult.Status.MODEL_FAILED,
                result.status());
        ArgumentCaptor<AppMemorySummary> persisted =
                ArgumentCaptor.forClass(AppMemorySummary.class);
        verify(summaryMapper).update(persisted.capture());
        assertEquals(3, persisted.getValue().getFailCount());
        assertEquals(LocalDateTime.ofInstant(
                        clock.instant().plusSeconds(20), clock.getZone()),
                persisted.getValue().getNextRetryTime());
    }

    @Test
    void successfulCompressionClearsPersistentFailureMetadata() {
        AppMemorySummary current = currentSummary(0L, "旧摘要", 100, 3);
        current.setNextRetryTime(LocalDateTime.ofInstant(
                clock.instant().plusSeconds(40), clock.getZone()));
        when(summaryMapper.selectOneByQuery(any())).thenReturn(current);
        when(chatHistoryService.listMessagesAfterCursor(1L, 0L, 100))
                .thenReturn(List.of(
                        message(1L, "user", "问题"),
                        message(2L, "ai", "回复")));
        when(summarizationModel.chat(anyString()))
                .thenReturn(textWithTokens("新摘要", 600));

        MemoryCompressionResult result = service.compressNow(
                1L, 2L, Duration.ofSeconds(1));

        assertEquals(MemoryCompressionResult.Status.COMPRESSED,
                result.status());
        ArgumentCaptor<AppMemorySummary> persisted =
                ArgumentCaptor.forClass(AppMemorySummary.class);
        verify(summaryMapper).update(persisted.capture(), eq(false));
        assertEquals(0, persisted.getValue().getFailCount());
        assertEquals(null, persisted.getValue().getNextRetryTime());
        assertEquals(current.getId(), persisted.getValue().getId());
        assertEquals(current.getCreateTime(),
                persisted.getValue().getCreateTime());
        assertEquals(current.getIsDelete(), persisted.getValue().getIsDelete());
    }

    @Test
    void noStableTurnsClearPersistentFailureMetadata() {
        AppMemorySummary current = currentSummary(8L, "旧摘要", 100, 3);
        current.setNextRetryTime(LocalDateTime.ofInstant(
                clock.instant().plusSeconds(40), clock.getZone()));
        when(summaryMapper.selectOneByQuery(any())).thenReturn(current);
        when(chatHistoryService.listMessagesAfterCursor(1L, 8L, 100))
                .thenReturn(List.of());

        MemoryCompressionResult result = service.compressNow(
                1L, 10L, Duration.ofSeconds(1));

        assertEquals(MemoryCompressionResult.Status.NOTHING_TO_COMPRESS,
                result.status());
        ArgumentCaptor<AppMemorySummary> persisted =
                ArgumentCaptor.forClass(AppMemorySummary.class);
        verify(summaryMapper).update(persisted.capture(), eq(false));
        assertEquals(0, persisted.getValue().getFailCount());
        assertEquals(null, persisted.getValue().getNextRetryTime());
        assertEquals(current.getId(), persisted.getValue().getId());
        assertEquals(current.getCreateTime(),
                persisted.getValue().getCreateTime());
        assertEquals(current.getIsDelete(), persisted.getValue().getIsDelete());
    }

    @Test
    void synchronousCompressionBypassesPersistentBackgroundBackoff() {
        AppMemorySummary current = currentSummary(0L, "旧摘要", 100, 2);
        current.setNextRetryTime(LocalDateTime.ofInstant(
                clock.instant().plusSeconds(40), clock.getZone()));
        when(summaryMapper.selectOneByQuery(any())).thenReturn(current);
        when(chatHistoryService.listMessagesAfterCursor(1L, 0L, 100))
                .thenReturn(List.of(
                        message(1L, "user", "问题"),
                        message(2L, "ai", "回复")));
        when(summarizationModel.chat(anyString()))
                .thenReturn(textWithTokens("同步摘要", 600));

        MemoryCompressionResult result = service.compressNow(
                1L, 2L, Duration.ofSeconds(1));

        assertEquals(MemoryCompressionResult.Status.COMPRESSED,
                result.status());
        verify(summarizationModel).chat(anyString());
    }

    @Test
    void restartedServiceHonorsDatabaseRetryBeforeSubmittingBackgroundWork() {
        ExecutorService executor = mock(ExecutorService.class);
        when(executor.submit(any(Runnable.class))).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return mock(Future.class);
        });
        AppMemorySummary persisted = currentSummary(8L, "旧摘要", 100, 2);
        persisted.setNextRetryTime(LocalDateTime.ofInstant(
                clock.instant().plusSeconds(20), clock.getZone()));
        when(summaryMapper.selectOneByQuery(any())).thenReturn(persisted);
        when(chatHistoryService.listMessagesAfterCursor(1L, 8L, 100))
                .thenReturn(List.of());
        MemorySummaryServiceImpl restarted = newService(executor);

        restarted.triggerSummarizationAsync(1L, 10L);

        verify(executor, never()).submit(any(Runnable.class));
        verifyNoInteractions(summarizationModel);

        clock.advance(Duration.ofSeconds(20));
        restarted.triggerSummarizationAsync(1L, 10L);

        verify(executor).submit(any(Runnable.class));
        verifyNoInteractions(summarizationModel);
    }

    @Test
    void persistedFailureDoesNotLeaveNormalRetryAsLocalFactSource() {
        ExecutorService executor = mock(ExecutorService.class);
        when(executor.submit(any(Runnable.class))).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return mock(Future.class);
        });
        AppMemorySummary current = currentSummary(0L, "旧摘要", 100, 0);
        when(summaryMapper.selectOneByQuery(any())).thenReturn(current);
        when(chatHistoryService.listMessagesAfterCursor(1L, 0L, 100))
                .thenReturn(List.of(
                        message(1L, "user", "问题"),
                        message(2L, "ai", "回复")));
        when(summarizationModel.chat(anyString()))
                .thenThrow(new IllegalStateException("model down"))
                .thenReturn(textWithTokens("恢复摘要", 600));
        MemorySummaryServiceImpl sameInstance = newService(executor);
        sameInstance.compressNow(1L, 2L, Duration.ofSeconds(1));
        current.setNextRetryTime(null);

        sameInstance.triggerSummarizationAsync(1L, 2L);

        verify(executor).submit(any(Runnable.class));
        verify(summarizationModel, times(2)).chat(anyString());
    }

    @Test
    void productionConstructorUsesSystemDefaultClockZone() {
        MemorySummaryDraftEngine draftEngine = new MemorySummaryDraftEngine(
                chatHistoryService,
                summarizationModel,
                modelExecutor,
                tokenEstimator,
                properties);
        MemorySummaryServiceImpl defaultClockService =
                new MemorySummaryServiceImpl(
                        summaryMapper,
                        draftEngine,
                        mock(ExecutorService.class),
                        redisTemplate,
                        lifecycleFence,
                        tokenEstimator,
                        properties);

        Clock productionClock = (Clock) ReflectionTestUtils.getField(
                defaultClockService, "clock");

        assertNotNull(productionClock);
        assertEquals(ZoneId.systemDefault(), productionClock.getZone());
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
    @DisplayName("提交失败元数据写库异常时后台至少退避五秒")
    void rejectedSubmissionCleansFlightEvenWhenFailureMetadataWriteFails() {
        ExecutorService executor = mock(ExecutorService.class);
        when(executor.submit(any(Runnable.class)))
                .thenThrow(new RejectedExecutionException("queue full"));
        when(summaryMapper.selectOneByQuery(any())).thenReturn(null);
        when(summaryMapper.insert(any(AppMemorySummary.class)))
                .thenThrow(new IllegalStateException("database down"));
        MemorySummaryServiceImpl background = newService(executor);

        assertDoesNotThrow(() ->
                background.triggerSummarizationAsync(1L, 2L));
        background.triggerSummarizationAsync(1L, 2L);

        verify(executor).submit(any(Runnable.class));

        AppDataLifecycleFence.DeletePermit deletion =
                lifecycleFence.beginDelete(1L, Duration.ZERO);
        assertNotNull(deletion);
        deletion.abortAndReopen();
        clock.advance(Duration.ofSeconds(5));
        background.triggerSummarizationAsync(1L, 2L);
        verify(executor, times(2)).submit(any(Runnable.class));
    }

    @Test
    @DisplayName("后台兜底退避期间同步压缩仍可立即执行")
    void synchronousCompressionBypassesBackgroundFallbackBackoff() {
        ExecutorService executor = mock(ExecutorService.class);
        when(executor.submit(any(Runnable.class)))
                .thenThrow(new RejectedExecutionException("queue full"));
        when(summaryMapper.selectOneByQuery(any()))
                .thenThrow(new IllegalStateException("database down"))
                .thenReturn(null);
        when(chatHistoryService.listMessagesAfterCursor(1L, 0L, 100))
                .thenReturn(List.of());
        MemorySummaryServiceImpl background = newService(executor);
        background.triggerSummarizationAsync(1L, 2L);

        MemoryCompressionResult result = background.compressNow(
                1L, 2L, Duration.ofSeconds(1));

        assertEquals(MemoryCompressionResult.Status.NOTHING_TO_COMPRESS,
                result.status());
        verify(summaryMapper, times(2)).selectOneByQuery(any());
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
        when(summaryMapper.update(
                any(AppMemorySummary.class), eq(false)))
                .thenAnswer(invocation -> {
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

    @Test
    @DisplayName("同步 owner 获取写许可异常后必须清理 single-flight")
    void synchronousOwnerCleansFlightWhenWriterAcquireThrows() {
        AppDataLifecycleFence failingFence = mock(
                AppDataLifecycleFence.class,
                withSettings().mockMaker(INLINE));
        AppDataLifecycleFence.WriterPermit writerPermit = mock(
                AppDataLifecycleFence.WriterPermit.class);
        when(failingFence.tryAcquireWriter(1L))
                .thenThrow(new IllegalStateException("fence down"))
                .thenReturn(writerPermit);
        when(summaryMapper.selectOneByQuery(any())).thenReturn(null);
        when(chatHistoryService.listMessagesAfterCursor(1L, 0L, 100))
                .thenReturn(List.of());
        MemorySummaryServiceImpl failing = newService(
                mock(ExecutorService.class), modelExecutor, failingFence);

        MemoryCompressionResult first = assertDoesNotThrow(() ->
                failing.compressNow(1L, 2L, Duration.ofSeconds(1)));
        MemoryCompressionResult second = failing.compressNow(
                1L, 2L, Duration.ofSeconds(1));

        assertEquals(MemoryCompressionResult.Status.MODEL_FAILED,
                first.status());
        assertEquals(MemoryCompressionResult.Status.NOTHING_TO_COMPRESS,
                second.status());
        verify(failingFence, times(2)).tryAcquireWriter(1L);
    }

    @Test
    @DisplayName("同步 owner 关闭写许可异常后必须清理 single-flight")
    void synchronousOwnerCleansFlightWhenWriterPermitCloseThrows() {
        AppDataLifecycleFence failingFence = mock(
                AppDataLifecycleFence.class,
                withSettings().mockMaker(INLINE));
        AppDataLifecycleFence.WriterPermit firstPermit = mock(
                AppDataLifecycleFence.WriterPermit.class);
        AppDataLifecycleFence.WriterPermit secondPermit = mock(
                AppDataLifecycleFence.WriterPermit.class);
        when(failingFence.tryAcquireWriter(1L))
                .thenReturn(firstPermit, secondPermit);
        doThrow(new IllegalStateException("close down"))
                .when(firstPermit).close();
        when(summaryMapper.selectOneByQuery(any())).thenReturn(null);
        when(chatHistoryService.listMessagesAfterCursor(1L, 0L, 100))
                .thenReturn(List.of());
        MemorySummaryServiceImpl failing = newService(
                mock(ExecutorService.class), modelExecutor, failingFence);

        MemoryCompressionResult first = assertDoesNotThrow(() ->
                failing.compressNow(1L, 2L, Duration.ofSeconds(1)));
        MemoryCompressionResult second = failing.compressNow(
                1L, 2L, Duration.ofSeconds(1));

        assertEquals(MemoryCompressionResult.Status.MODEL_FAILED,
                first.status());
        assertEquals(MemoryCompressionResult.Status.NOTHING_TO_COMPRESS,
                second.status());
        verify(failingFence, times(2)).tryAcquireWriter(1L);
    }

    @Test
    @DisplayName("失败元数据记录再次异常时同步 owner 仍清理 single-flight")
    void synchronousOwnerCleansFlightWhenFailureMetadataThrowsAgain() {
        Clock failingClock = mock(Clock.class);
        when(failingClock.getZone()).thenReturn(ZoneOffset.UTC);
        when(failingClock.instant())
                .thenReturn(Instant.parse("2026-08-15T12:00:00Z"))
                .thenThrow(new IllegalStateException("clock down"));
        AppMemorySummary current = currentSummary(0L, "旧摘要", 100, 0);
        when(summaryMapper.selectOneByQuery(any())).thenReturn(current);
        when(chatHistoryService.listMessagesAfterCursor(1L, 0L, 100))
                .thenReturn(List.of(
                        message(1L, "user", "问题"),
                        message(2L, "ai", "回复")));
        when(summarizationModel.chat(anyString()))
                .thenThrow(new IllegalStateException("model down"))
                .thenReturn(textWithTokens("恢复摘要", 600));
        MemorySummaryServiceImpl failing = newService(
                mock(ExecutorService.class),
                modelExecutor,
                lifecycleFence,
                failingClock);

        MemoryCompressionResult first = assertDoesNotThrow(() ->
                failing.compressNow(1L, 2L, Duration.ofSeconds(1)));
        org.mockito.Mockito.doReturn(
                        Instant.parse("2026-08-15T12:00:01Z"))
                .when(failingClock).instant();
        MemoryCompressionResult second = failing.compressNow(
                1L, 2L, Duration.ofSeconds(1));

        assertEquals(MemoryCompressionResult.Status.MODEL_FAILED,
                first.status());
        assertEquals(MemoryCompressionResult.Status.COMPRESSED,
                second.status());
        verify(summarizationModel, times(2)).chat(anyString());
    }

    @Test
    @DisplayName("后台 owner 获取写许可异常后必须清理 single-flight")
    void backgroundOwnerCleansFlightWhenWriterAcquireThrows() {
        AppDataLifecycleFence failingFence = mock(
                AppDataLifecycleFence.class,
                withSettings().mockMaker(INLINE));
        AppDataLifecycleFence.WriterPermit writerPermit = mock(
                AppDataLifecycleFence.WriterPermit.class);
        when(failingFence.tryAcquireWriter(1L))
                .thenThrow(new IllegalStateException("fence down"))
                .thenReturn(writerPermit);
        ExecutorService backgroundExecutor = mock(ExecutorService.class);
        when(backgroundExecutor.submit(any(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation.<Runnable>getArgument(0).run();
                    return mock(Future.class);
                });
        when(summaryMapper.selectOneByQuery(any())).thenReturn(null);
        when(chatHistoryService.listMessagesAfterCursor(1L, 0L, 100))
                .thenReturn(List.of());
        MemorySummaryServiceImpl failing = newService(
                backgroundExecutor, modelExecutor, failingFence);

        assertDoesNotThrow(() ->
                failing.triggerSummarizationAsync(1L, 2L));
        failing.triggerSummarizationAsync(1L, 2L);

        verify(failingFence).tryAcquireWriter(1L);
        verify(backgroundExecutor, never()).submit(any(Runnable.class));

        clock.advance(Duration.ofSeconds(5));
        failing.triggerSummarizationAsync(1L, 2L);

        verify(failingFence, times(2)).tryAcquireWriter(1L);
        verify(backgroundExecutor).submit(any(Runnable.class));
    }

    @Test
    @DisplayName("后台 owner 关闭写许可异常后至少退避五秒")
    void backgroundOwnerCloseFailureBacksOffThenRetriesAfterDelay() {
        AppDataLifecycleFence failingFence = mock(
                AppDataLifecycleFence.class,
                withSettings().mockMaker(INLINE));
        AppDataLifecycleFence.WriterPermit failingPermit = mock(
                AppDataLifecycleFence.WriterPermit.class);
        AppDataLifecycleFence.WriterPermit recoveredPermit = mock(
                AppDataLifecycleFence.WriterPermit.class);
        when(failingFence.tryAcquireWriter(1L))
                .thenReturn(failingPermit, recoveredPermit);
        doThrow(new IllegalStateException("close down"))
                .when(failingPermit).close();
        ExecutorService backgroundExecutor = mock(ExecutorService.class);
        when(backgroundExecutor.submit(any(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation.<Runnable>getArgument(0).run();
                    return mock(Future.class);
                });
        when(summaryMapper.selectOneByQuery(any())).thenReturn(null);
        when(chatHistoryService.listMessagesAfterCursor(1L, 0L, 100))
                .thenReturn(List.of());
        MemorySummaryServiceImpl failing = newService(
                backgroundExecutor, modelExecutor, failingFence);

        failing.triggerSummarizationAsync(1L, 2L);
        failing.triggerSummarizationAsync(1L, 2L);

        verify(backgroundExecutor).submit(any(Runnable.class));
        verify(failingFence, times(2)).tryAcquireWriter(1L);

        clock.advance(Duration.ofSeconds(5));
        failing.triggerSummarizationAsync(1L, 2L);

        verify(backgroundExecutor, times(2)).submit(any(Runnable.class));
        verify(failingFence, times(3)).tryAcquireWriter(1L);
    }

    @Test
    @DisplayName("兜底退避不得缩短已有指数退避")
    void fallbackRetryDelayDoesNotShortenExistingExponentialBackoff() {
        AppDataLifecycleFence failingFence = mock(
                AppDataLifecycleFence.class,
                withSettings().mockMaker(INLINE));
        AppDataLifecycleFence.WriterPermit failingPermit = mock(
                AppDataLifecycleFence.WriterPermit.class);
        AppDataLifecycleFence.WriterPermit recoveredPermit = mock(
                AppDataLifecycleFence.WriterPermit.class);
        when(failingFence.tryAcquireWriter(1L))
                .thenReturn(failingPermit, recoveredPermit);
        doThrow(new IllegalStateException("close down"))
                .when(failingPermit).close();
        ExecutorService backgroundExecutor = mock(ExecutorService.class);
        when(backgroundExecutor.submit(any(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation.<Runnable>getArgument(0).run();
                    return mock(Future.class);
                });
        AppMemorySummary current = currentSummary(0L, "旧摘要", 100, 2);
        when(summaryMapper.selectOneByQuery(any())).thenReturn(current);
        when(chatHistoryService.listMessagesAfterCursor(1L, 0L, 100))
                .thenReturn(List.of(
                        message(1L, "user", "问题"),
                        message(2L, "ai", "回复")));
        when(summarizationModel.chat(anyString()))
                .thenThrow(new IllegalStateException("model down"));
        MemorySummaryServiceImpl failing = newService(
                backgroundExecutor, modelExecutor, failingFence);

        failing.triggerSummarizationAsync(1L, 2L);
        clock.advance(Duration.ofSeconds(5));
        failing.triggerSummarizationAsync(1L, 2L);

        verify(backgroundExecutor).submit(any(Runnable.class));

        clock.advance(Duration.ofSeconds(15));
        failing.triggerSummarizationAsync(1L, 2L);

        verify(backgroundExecutor, times(2)).submit(any(Runnable.class));
    }

    private MemorySummaryServiceImpl newService(ExecutorService executor) {
        return newService(executor, modelExecutor);
    }

    private MemorySummaryServiceImpl newService(
            ExecutorService executor,
            ExecutorService summaryModelExecutor) {
        return newService(executor, summaryModelExecutor, lifecycleFence);
    }

    private MemorySummaryServiceImpl newService(
            ExecutorService executor,
            ExecutorService summaryModelExecutor,
            AppDataLifecycleFence fence) {
        return newService(executor, summaryModelExecutor, fence, clock);
    }

    private MemorySummaryServiceImpl newService(
            ExecutorService executor,
            ExecutorService summaryModelExecutor,
            AppDataLifecycleFence fence,
            Clock serviceClock) {
        MemorySummaryDraftEngine draftEngine = new MemorySummaryDraftEngine(
                chatHistoryService,
                summarizationModel,
                summaryModelExecutor,
                tokenEstimator,
                properties);
        return new MemorySummaryServiceImpl(
                summaryMapper,
                draftEngine,
                executor,
                redisTemplate,
                fence,
                tokenEstimator,
                properties,
                serviceClock);
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

    private List<ChatHistory> fullPageEndingWithUser() {
        List<ChatHistory> page = new ArrayList<>(100);
        for (long id = 1L; id < 100L; id++) {
            page.add(message(id, "ai", "孤立回复" + id));
        }
        page.add(message(100L, "user", "跨页问题"));
        return List.copyOf(page);
    }

    private AppMemorySummary currentSummary(
            long cursor, String summary, int tokens, int failCount) {
        LocalDateTime now = LocalDateTime.ofInstant(
                clock.instant(), clock.getZone());
        return AppMemorySummary.builder()
                .id(1L)
                .appId(1L)
                .lastSummarizedId(cursor)
                .summary(summary)
                .summaryTokens(tokens)
                .failCount(failCount)
                .createTime(now)
                .updateTime(now)
                .isDelete(0)
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
