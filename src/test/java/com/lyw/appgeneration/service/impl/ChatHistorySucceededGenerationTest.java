package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.model.entity.ChatHistory;
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatHistorySucceededGenerationTest {
    @Test
    void 只查本应用成功AI未删除记录的ID() {
        var service = spy(new ChatHistoryServiceImpl());
        doReturn(ChatHistory.builder().id(11L).build()).when(service).getOne(any(QueryWrapper.class));
        assertTrue(service.hasSucceededGeneration(7L));
        var captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(service).getOne(captor.capture());
        String sql = captor.getValue().toSQL();
        assertTrue(sql.contains("id"), sql);
        assertFalse(sql.contains("SELECT *"), sql);
        assertTrue(sql.contains("appId") && sql.contains("7"), sql);
        assertTrue(sql.contains("messageType") && sql.contains("ai"), sql);
        assertTrue(sql.contains("memoryOutcome") && sql.contains("SUCCEEDED"), sql);
        assertTrue(sql.contains("isDelete") && sql.contains("0"), sql);
        assertTrue(sql.contains("LIMIT 1"), sql);
        verify(service, never()).count(any(QueryWrapper.class));
    }

    @Test
    void 无成功记录为False且查询异常不能猜测首次() {
        var service = spy(new ChatHistoryServiceImpl());
        doReturn(null).when(service).getOne(any(QueryWrapper.class));
        assertFalse(service.hasSucceededGeneration(7L));
        var failure = new IllegalStateException("database unavailable");
        doThrow(failure).when(service).getOne(any(QueryWrapper.class));
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.hasSucceededGeneration(8L)));
        assertThrows(RuntimeException.class, () -> service.hasSucceededGeneration(null));
    }
}
