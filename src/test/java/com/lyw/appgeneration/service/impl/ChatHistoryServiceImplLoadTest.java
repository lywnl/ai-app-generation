package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.service.ChatHistoryService.HistoryLoadStatus;
import com.mybatisflex.core.query.QueryWrapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class ChatHistoryServiceImplLoadTest {

    @Test
    void emptyHistoryIsAValidEmptyResult() {
        ChatHistoryServiceImpl service = spy(new ChatHistoryServiceImpl());
        doReturn(List.of()).when(service).list(any(QueryWrapper.class));
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
