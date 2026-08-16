package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.memory.ChatTokenEstimator;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.service.ChatHistoryService.HistoryLoadStatus;
import com.mybatisflex.core.query.QueryWrapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatHistoryServiceImplLoadTest {

    @Test
    void 保存消息可返回生成后实体且旧Boolean接口保持兼容() {
        ChatHistoryServiceImpl service = spy(new ChatHistoryServiceImpl());
        doAnswer(invocation -> {
            ChatHistory history = invocation.getArgument(0);
            history.setId(99L);
            return true;
        }).when(service).save(any(ChatHistory.class));

        ChatHistory saved = service.addChatMessageAndReturn(
                7L, "已完成", "ai", 9L);

        assertNotNull(saved);
        assertEquals(99L, saved.getId());
        assertTrue(service.addChatMessage(7L, "继续", "user", 9L));
        verify(service, times(2)).save(any(ChatHistory.class));
    }

    @Test
    void 保存失败时返回实体接口为Null且旧接口返回False() {
        ChatHistoryServiceImpl service = spy(new ChatHistoryServiceImpl());
        doReturn(false).when(service).save(any(ChatHistory.class));

        assertNull(service.addChatMessageAndReturn(
                7L, "保存失败", "ai", 9L));
        assertFalse(service.addChatMessage(
                7L, "仍然失败", "user", 9L));
    }

    @Test
    void emptyHistoryIsAValidEmptyResult() {
        ChatHistoryServiceImpl service = spy(new ChatHistoryServiceImpl());
        doReturn(List.of()).when(service).list(any(QueryWrapper.class));
        // 固定 20 仅用于验证保留的旧兼容 API，不代表在线 Token 热窗口策略。
        var memory = MessageWindowChatMemory.withMaxMessages(20);

        var result = service.loadChatHistoryToMemory(7L, memory, 20);

        assertEquals(HistoryLoadStatus.EMPTY, result.status());
        assertEquals(0, result.count());
    }

    @Test
    void queryFailureReturnsFailedInsteadOfMasqueradingAsEmpty() {
        ChatHistoryServiceImpl service = spy(new ChatHistoryServiceImpl());
        doReturn(null).when(service).list(any(QueryWrapper.class));

        var result = service.loadChatHistoryToMemory(
                7L, MessageWindowChatMemory.withMaxMessages(20), 20);

        assertEquals(HistoryLoadStatus.FAILED, result.status());
    }

    @Test
    void loadsLatestRowsByIdDescendingThenRestoresChronologicalOrder() {
        ChatHistoryServiceImpl service = spy(new ChatHistoryServiceImpl());
        doReturn(List.of(
                message(3L, "第三条", "ai"),
                message(2L, "第二条", "user")))
                .when(service).list(any(QueryWrapper.class));
        var memory = MessageWindowChatMemory.withMaxMessages(20);

        var result = service.loadChatHistoryToMemory(7L, memory, 2);

        assertEquals(HistoryLoadStatus.LOADED, result.status());
        assertEquals(2, result.count());
        assertEquals("第二条", ((UserMessage) memory.messages().get(0)).singleText());
        assertEquals("第三条", ((AiMessage) memory.messages().get(1)).text());
        ArgumentCaptor<QueryWrapper> query = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(service).list(query.capture());
        assertEquals("desc", readOrderDirection(query.getValue()));
        assertEquals(2L, readLimit(query.getValue()));
    }

    @Test
    void loadsNewestCompleteTurnsUntilThresholdWithoutSplittingOlderTurn() {
        ChatHistoryServiceImpl service = spy(new ChatHistoryServiceImpl());
        doReturn(List.of(
                message(6L, "新回复", "ai"),
                message(5L, "新问题", "user"),
                message(4L, "中回复", "ai"),
                message(3L, "中问题", "user"),
                message(2L, "旧回复", "ai"),
                message(1L, "旧问题", "user")))
                .when(service).list(any(QueryWrapper.class));
        ChatTokenEstimator estimator = mock(ChatTokenEstimator.class);
        when(estimator.estimateMessages(anyList())).thenAnswer(invocation -> {
            List<ChatMessage> turn = invocation.getArgument(0);
            String userText = ((UserMessage) turn.getFirst()).singleText();
            return "新问题".equals(userText) ? 6_000 : 5_000;
        });
        var memory = MessageWindowChatMemory.withMaxMessages(Integer.MAX_VALUE);

        var result = service.loadRecentCompleteTurnsToMemory(
                7L, memory, 10_000, estimator);

        assertEquals(HistoryLoadStatus.LOADED, result.status());
        assertEquals(4, result.count());
        assertEquals(List.of("中问题", "中回复", "新问题", "新回复"),
                messageTexts(memory.messages()));
        verify(estimator, times(2)).estimateMessages(anyList());
    }

    @Test
    void coldLoadOnlyRestoresCompleteTurnsAfterL1Cursor() {
        ChatHistoryServiceImpl service = spy(new ChatHistoryServiceImpl());
        doReturn(List.of(
                message(6L, "新回复", "ai"),
                message(5L, "新问题", "user"),
                message(4L, "中回复", "ai"),
                message(3L, "中问题", "user")))
                .when(service).list(any(QueryWrapper.class));
        var memory = MessageWindowChatMemory.withMaxMessages(
                Integer.MAX_VALUE);

        var result = service.loadRecentCompleteTurnsToMemory(
                7L, 2L, memory, 30_720, fixedTurnEstimator(100));

        assertEquals(HistoryLoadStatus.LOADED, result.status());
        assertEquals(List.of("中问题", "中回复", "新问题", "新回复"),
                messageTexts(memory.messages()));
    }

    @Test
    void listsNewestStableTurnBoundariesInChronologicalOrder() {
        ChatHistoryServiceImpl service = spy(new ChatHistoryServiceImpl());
        doReturn(List.of(
                message(7L, "当前孤立问题", "user"),
                message(6L, "新回复", "ai"),
                message(5L, "新问题", "user"),
                message(4L, "中回复", "ai"),
                message(3L, "中问题", "user"),
                message(2L, "旧回复", "ai"),
                message(1L, "旧问题", "user")))
                .when(service).list(any(QueryWrapper.class));

        var boundaries = service.listRecentCompleteTurnBoundaries(7L, 2);

        assertEquals(2, boundaries.size());
        assertEquals(3L, boundaries.getFirst().turnId());
        assertEquals(4L, boundaries.getFirst().completedThroughId());
        assertEquals("中问题", boundaries.getFirst().userText());
        assertEquals("中回复", boundaries.getFirst().aiText());
        assertEquals(5L, boundaries.getLast().turnId());
        assertEquals(6L, boundaries.getLast().completedThroughId());
    }

    @Test
    void ignoresUnclosedAndNonAdjacentRowsWhenLoadingAllHistory() {
        ChatHistoryServiceImpl service = spy(new ChatHistoryServiceImpl());
        doReturn(List.of(
                message(7L, "最新孤立问题", "user"),
                message(6L, "有效回复二", "ai"),
                message(5L, "有效问题二", "user"),
                message(4L, "连续孤立问题", "user"),
                message(3L, "孤立回复", "ai"),
                message(2L, "有效回复一", "ai"),
                message(1L, "有效问题一", "user")))
                .when(service).list(any(QueryWrapper.class));
        ChatTokenEstimator estimator = fixedTurnEstimator(100);
        var memory = MessageWindowChatMemory.withMaxMessages(Integer.MAX_VALUE);

        var result = service.loadRecentCompleteTurnsToMemory(
                7L, memory, 30_720, estimator);

        assertEquals(HistoryLoadStatus.LOADED, result.status());
        assertEquals(4, result.count());
        assertEquals(List.of(
                        "有效问题一", "有效回复一", "有效问题二", "有效回复二"),
                messageTexts(memory.messages()));
    }

    @Test
    void keepsCompleteTurnAcrossDescendingQueryBatchBoundary() {
        ChatHistoryServiceImpl service = spy(new ChatHistoryServiceImpl());
        doReturn(firstFullDescendingBatch())
                .doReturn(List.of(message(1L, "问题-1", "user")))
                .when(service).list(any(QueryWrapper.class));
        ChatTokenEstimator estimator = fixedTurnEstimator(10);
        var memory = MessageWindowChatMemory.withMaxMessages(Integer.MAX_VALUE);

        var result = service.loadRecentCompleteTurnsToMemory(
                7L, memory, 30_720, estimator);

        assertEquals(HistoryLoadStatus.LOADED, result.status());
        assertEquals(100, result.count());
        assertEquals("问题-1", ((UserMessage) memory.messages().getFirst()).singleText());
        assertEquals("回复-101", ((AiMessage) memory.messages().getLast()).text());
        verify(service, times(2)).list(any(QueryWrapper.class));
    }

    @Test
    void queryFailureDuringBatchLoadingLeavesExistingMemoryUntouched() {
        ChatHistoryServiceImpl service = spy(new ChatHistoryServiceImpl());
        doReturn(firstFullDescendingBatch())
                .doReturn(null)
                .when(service).list(any(QueryWrapper.class));
        ChatTokenEstimator estimator = fixedTurnEstimator(10);
        var memory = MessageWindowChatMemory.withMaxMessages(Integer.MAX_VALUE);
        memory.add(UserMessage.from("缓存问题"));
        memory.add(AiMessage.from("缓存回复"));

        var result = service.loadRecentCompleteTurnsToMemory(
                7L, memory, 30_720, estimator);

        assertEquals(HistoryLoadStatus.FAILED, result.status());
        assertEquals(List.of("缓存问题", "缓存回复"),
                messageTexts(memory.messages()));
    }

    private ChatTokenEstimator fixedTurnEstimator(int tokens) {
        ChatTokenEstimator estimator = mock(ChatTokenEstimator.class);
        when(estimator.estimateMessages(anyList())).thenReturn(tokens);
        return estimator;
    }

    private List<ChatHistory> firstFullDescendingBatch() {
        List<ChatHistory> batch = new ArrayList<>();
        for (long aiId = 101L; aiId >= 5L; aiId -= 2L) {
            batch.add(message(aiId, "回复-" + aiId, "ai"));
            batch.add(message(aiId - 1L, "问题-" + (aiId - 1L), "user"));
        }
        batch.add(message(3L, "孤立回复-3", "ai"));
        batch.add(message(2L, "回复-2", "ai"));
        return batch;
    }

    private List<String> messageTexts(List<ChatMessage> messages) {
        return messages.stream()
                .map(message -> message instanceof UserMessage userMessage
                        ? userMessage.singleText()
                        : ((AiMessage) message).text())
                .toList();
    }

    private ChatHistory message(long id, String text, String type) {
        return ChatHistory.builder().id(id).appId(7L).userId(9L)
                .message(text).messageType(type).build();
    }

    @SuppressWarnings("unchecked")
    private String readOrderDirection(QueryWrapper query) {
        try {
            java.lang.reflect.Field field = query.getClass().getSuperclass()
                    .getDeclaredField("orderBys");
            field.setAccessible(true);
            List<Object> values = (List<Object>) field.get(query);
            Object orderBy = values.getFirst();
            java.lang.reflect.Field typeField = orderBy.getClass()
                    .getDeclaredField("orderType");
            typeField.setAccessible(true);
            return ((String) typeField.get(orderBy)).trim().toLowerCase();
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private long readLimit(QueryWrapper query) {
        try {
            java.lang.reflect.Field field = query.getClass().getSuperclass()
                    .getDeclaredField("limitRows");
            field.setAccessible(true);
            return (Long) field.get(query);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
