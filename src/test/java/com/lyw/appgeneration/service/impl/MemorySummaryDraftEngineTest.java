package com.lyw.appgeneration.service.impl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lyw.appgeneration.ai.memory.ChatTokenEstimator;
import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemoryCompressionResult;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemorySummaryDraftEngineTest {

    @Test
    @DisplayName("DraftResult 显式暴露 reducer 调用轮数")
    void draftResultExposesReducerRounds() {
        assertDoesNotThrow(() -> MemorySummaryDraftEngine.DraftResult.class
                .getDeclaredMethod("reducerRounds"));
    }

    @ParameterizedTest(name = "成功草稿实际调用 reducer {0} 次")
    @MethodSource("successfulReducerCases")
    void reportsActualReducerRoundsForSuccessfulDraft(
            int expectedRounds,
            List<String> modelOutputs,
            Map<String, Integer> outputTokens) {
        MemorySummaryDraftEngine.DraftResult result = buildDraft(
                modelOutputs, outputTokens);

        assertNull(result.failureStatus());
        assertEquals(expectedRounds, result.reducerRounds());
    }

    @Test
    @DisplayName("reducer 未收敛时失败结果保留已调用轮数")
    void failureRetainsReducerRoundsAlreadyInvoked() {
        MemorySummaryDraftEngine.DraftResult result = buildDraft(
                List.of("初稿", "未收敛"),
                Map.of("初稿", 5_000, "未收敛", 5_000));

        assertEquals(MemoryCompressionResult.Status.OUTPUT_STILL_TOO_LARGE,
                result.failureStatus());
        assertEquals(1, result.reducerRounds());
    }

    @Test
    @DisplayName("reducer 任务提交被拒绝时不计入真实调用轮数")
    void rejectedReducerTaskDoesNotIncrementActualRounds() {
        ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);
        ChatModel model = mock(ChatModel.class);
        ExecutorService modelExecutor = mock(ExecutorService.class);
        ChatTokenEstimator tokenEstimator = mock(ChatTokenEstimator.class);
        AtomicInteger submissions = new AtomicInteger();
        when(chatHistoryService.listMessagesAfterCursor(1L, 0L, 100))
                .thenReturn(List.of(
                        message(1L, "user", "问题"),
                        message(2L, "ai", "回复")));
        when(model.chat(anyString())).thenReturn("超限初稿");
        when(tokenEstimator.estimateText(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            if (text.isEmpty()) {
                return 0;
            }
            return "超限初稿".equals(text) ? 5_000 : 100;
        });
        when(modelExecutor.submit(any(Callable.class)))
                .thenAnswer(invocation -> {
                    if (submissions.getAndIncrement() == 0) {
                        Callable<String> task = invocation.getArgument(0);
                        return CompletableFuture.completedFuture(task.call());
                    }
                    throw new RejectedExecutionException("reducer 已满");
                });
        MemorySummaryDraftEngine engine = new MemorySummaryDraftEngine(
                chatHistoryService,
                model,
                modelExecutor,
                tokenEstimator,
                new MemoryTokenProperties());

        MemorySummaryDraftEngine.DraftResult result = engine.buildDraft(
                1L, 2L, null, Long.MAX_VALUE);

        assertEquals(MemoryCompressionResult.Status.MODEL_FAILED,
                result.failureStatus());
        assertEquals(0, result.reducerRounds());
        verify(model).chat(anyString());
    }

    @Test
    @DisplayName("摘要模型异常日志只记录类型，不携带原始异常消息")
    void modelFailureLogDoesNotExposeRawExceptionMessage()
            throws Exception {
        String sensitiveMessage = "敏感摘要正文与模型输出";
        ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);
        ChatModel model = mock(ChatModel.class);
        ExecutorService modelExecutor = mock(ExecutorService.class);
        ChatTokenEstimator tokenEstimator = mock(ChatTokenEstimator.class);
        Future<String> modelCall = mock(Future.class);
        when(chatHistoryService.listMessagesAfterCursor(1L, 0L, 100))
                .thenReturn(List.of(
                        message(1L, "user", "敏感用户正文"),
                        message(2L, "ai", "敏感模型回复")));
        when(tokenEstimator.estimateText(anyString())).thenAnswer(invocation ->
                invocation.<String>getArgument(0).isEmpty() ? 0 : 100);
        doReturn(modelCall).when(modelExecutor).submit(any(Callable.class));
        when(modelCall.get(anyLong(), eq(TimeUnit.NANOSECONDS)))
                .thenThrow(new ExecutionException(
                        new IllegalStateException(sensitiveMessage)));
        MemorySummaryDraftEngine engine = new MemorySummaryDraftEngine(
                chatHistoryService,
                model,
                modelExecutor,
                tokenEstimator,
                new MemoryTokenProperties());
        Logger logger = (Logger) LoggerFactory.getLogger(
                MemorySummaryDraftEngine.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        MemorySummaryDraftEngine.DraftResult result;
        try {
            result = engine.buildDraft(1L, 2L, null, Long.MAX_VALUE);
        } finally {
            logger.detachAppender(appender);
        }

        assertEquals(MemoryCompressionResult.Status.MODEL_FAILED,
                result.failureStatus());
        ILoggingEvent failureLog = appender.list.stream()
                .filter(event -> event.getFormattedMessage()
                        .contains("摘要模型调用失败"))
                .findFirst()
                .orElseThrow();
        assertTrue(failureLog.getFormattedMessage()
                .contains("IllegalStateException"));
        assertTrue(failureLog.getFormattedMessage().contains("appId=1"));
        assertFalse(failureLog.getFormattedMessage()
                .contains(sensitiveMessage));
        assertNull(failureLog.getThrowableProxy(),
                "安全日志不得附带会渲染原始异常消息的 Throwable");
    }

    @Test
    @DisplayName("模型 Future 返回后截止时间已过则丢弃输出")
    void discardsModelOutputWhenDeadlineExpiresAsFutureReturns()
            throws Exception {
        AtomicLong nanoTime = new AtomicLong(100L);
        ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);
        ChatModel model = mock(ChatModel.class);
        ExecutorService modelExecutor = mock(ExecutorService.class);
        ChatTokenEstimator tokenEstimator = mock(ChatTokenEstimator.class);
        Future<String> modelCall = mock(Future.class);
        when(chatHistoryService.listMessagesAfterCursor(1L, 0L, 100))
                .thenReturn(List.of(
                        message(1L, "user", "问题"),
                        message(2L, "ai", "回复")));
        when(tokenEstimator.estimateText(anyString())).thenAnswer(invocation ->
                invocation.<String>getArgument(0).isEmpty() ? 0 : 1_000);
        doReturn(modelCall).when(modelExecutor).submit(any(Callable.class));
        when(modelCall.get(anyLong(), eq(TimeUnit.NANOSECONDS)))
                .thenAnswer(invocation -> {
                    nanoTime.set(201L);
                    return "迟到摘要";
                });
        MemorySummaryDraftEngine engine = new MemorySummaryDraftEngine(
                chatHistoryService,
                model,
                modelExecutor,
                tokenEstimator,
                new MemoryTokenProperties(),
                nanoTime::get);

        MemorySummaryDraftEngine.DraftResult result = engine.buildDraft(
                1L, 2L, null, 200L);

        assertEquals(MemoryCompressionResult.Status.TIMED_OUT,
                result.failureStatus());
        assertEquals(0L, result.summarizedThroughId());
        assertEquals(0, result.summaryTokens());
        assertFalse(result.changed());
    }

    @Test
    @DisplayName("Draft 成功发布前截止时间已过则丢弃草稿")
    void discardsDraftWhenDeadlineExpiresDuringOutputAccounting()
            throws Exception {
        AtomicLong nanoTime = new AtomicLong(100L);
        ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);
        ChatModel model = mock(ChatModel.class);
        ExecutorService modelExecutor = mock(ExecutorService.class);
        ChatTokenEstimator tokenEstimator = mock(ChatTokenEstimator.class);
        Future<String> modelCall = mock(Future.class);
        when(chatHistoryService.listMessagesAfterCursor(1L, 0L, 100))
                .thenReturn(List.of(
                        message(1L, "user", "问题"),
                        message(2L, "ai", "回复")));
        when(tokenEstimator.estimateText(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            if ("摘要".equals(text)) {
                nanoTime.set(201L);
            }
            return text.isEmpty() ? 0 : 1_000;
        });
        doReturn(modelCall).when(modelExecutor).submit(any(Callable.class));
        when(modelCall.get(anyLong(), eq(TimeUnit.NANOSECONDS)))
                .thenReturn("摘要");
        MemorySummaryDraftEngine engine = new MemorySummaryDraftEngine(
                chatHistoryService,
                model,
                modelExecutor,
                tokenEstimator,
                new MemoryTokenProperties(),
                nanoTime::get);

        MemorySummaryDraftEngine.DraftResult result = engine.buildDraft(
                1L, 2L, null, 200L);

        assertEquals(MemoryCompressionResult.Status.TIMED_OUT,
                result.failureStatus());
        assertEquals(0L, result.summarizedThroughId());
        assertEquals(0, result.summaryTokens());
        assertFalse(result.changed());
    }

    private static ChatHistory message(long id, String type, String text) {
        return ChatHistory.builder()
                .id(id)
                .messageType(type)
                .message(text)
                .build();
    }

    private static Stream<Arguments> successfulReducerCases() {
        return Stream.of(
                Arguments.of(
                        0,
                        List.of("短摘要"),
                        Map.of("短摘要", 1_000)),
                Arguments.of(
                        1,
                        List.of("过长摘要", "短摘要"),
                        Map.of("过长摘要", 4_000, "短摘要", 1_000)),
                Arguments.of(
                        2,
                        List.of("初稿", "一次压缩", "二次压缩"),
                        Map.of("初稿", 5_000,
                                "一次压缩", 4_000,
                                "二次压缩", 1_000)));
    }

    private MemorySummaryDraftEngine.DraftResult buildDraft(
            List<String> modelOutputs,
            Map<String, Integer> outputTokens) {
        ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);
        ChatModel model = mock(ChatModel.class);
        ChatTokenEstimator tokenEstimator = mock(ChatTokenEstimator.class);
        AtomicInteger callIndex = new AtomicInteger();
        when(chatHistoryService.listMessagesAfterCursor(1L, 0L, 100))
                .thenReturn(List.of(
                        message(1L, "user", "问题"),
                        message(2L, "ai", "回复")));
        when(model.chat(anyString())).thenAnswer(invocation ->
                modelOutputs.get(callIndex.getAndIncrement()));
        when(tokenEstimator.estimateText(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            if (text.isEmpty()) {
                return 0;
            }
            return outputTokens.getOrDefault(text, 100);
        });
        ExecutorService modelExecutor = Executors.newSingleThreadExecutor();
        try {
            MemorySummaryDraftEngine engine = new MemorySummaryDraftEngine(
                    chatHistoryService,
                    model,
                    modelExecutor,
                    tokenEstimator,
                    new MemoryTokenProperties());
            return engine.buildDraft(1L, 2L, null, Long.MAX_VALUE);
        } finally {
            modelExecutor.shutdownNow();
        }
    }
}
