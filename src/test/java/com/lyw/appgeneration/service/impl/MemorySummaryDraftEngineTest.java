package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.memory.ChatTokenEstimator;
import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemoryCompressionResult;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemorySummaryDraftEngineTest {

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
}
