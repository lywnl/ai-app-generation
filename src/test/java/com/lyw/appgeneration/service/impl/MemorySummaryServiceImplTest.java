package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.mapper.AppMemorySummaryMapper;
import com.lyw.appgeneration.model.entity.AppMemorySummary;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.service.ChatHistoryService;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link MemorySummaryServiceImpl} 单测:滚动合并 / 游标推进 / 模型失败降级 / 跳过空增量。
 *
 * <p>用 6 参测试构造器把「最小新增条数阈值」设为 1,以聚焦核心逻辑(生产默认 8)。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
class MemorySummaryServiceImplTest {

    @Mock
    ChatHistoryService chatHistoryService;
    @Mock
    AppMemorySummaryMapper summaryMapper;
    @Mock
    ChatModel summarizationModel;
    MemorySummaryServiceImpl service;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        // 测试构造器:minNewMessages=1、maxPerRun=60;同步单线程池便于断言
        service = new MemorySummaryServiceImpl(chatHistoryService, summaryMapper, summarizationModel,
                Executors.newSingleThreadExecutor(), 1, 60);
    }

    private ChatHistory msg(long id, String type, String text) {
        return ChatHistory.builder().id(id).messageType(type).message(text).build();
    }

    @Test
    void summarizeRollsUpAndAdvancesCursor() {
        when(summaryMapper.selectOneByQuery(any())).thenReturn(null); // 首次无摘要
        when(chatHistoryService.listMessagesAfterCursor(eq(1L), any(), anyInt()))
                .thenReturn(List.of(msg(10, "user", "做个待办App"), msg(11, "ai", "已生成")));
        when(summarizationModel.chat(anyString())).thenReturn("# 应用目标与定位\n待办App");

        service.summarizeNow(1L);

        ArgumentCaptor<AppMemorySummary> cap = ArgumentCaptor.forClass(AppMemorySummary.class);
        verify(summaryMapper).insert(cap.capture());
        assertEquals(11L, cap.getValue().getLastSummarizedId(), "游标应推进到最新消息id");
        assertTrue(cap.getValue().getSummary().contains("待办App"));
    }

    @Test
    void noNewMessagesSkips() {
        when(summaryMapper.selectOneByQuery(any())).thenReturn(
                AppMemorySummary.builder().appId(1L).lastSummarizedId(11L).summary("old").build());
        when(chatHistoryService.listMessagesAfterCursor(eq(1L), eq(11L), anyInt())).thenReturn(List.of());

        service.summarizeNow(1L);

        verify(summarizationModel, never()).chat(anyString());
        verify(summaryMapper, never()).update(any());
    }

    @Test
    void modelFailureDoesNotAdvanceCursorAndNotThrow() {
        when(summaryMapper.selectOneByQuery(any())).thenReturn(
                AppMemorySummary.builder().appId(1L).lastSummarizedId(5L).summary("old").failCount(0).build());
        when(chatHistoryService.listMessagesAfterCursor(eq(1L), eq(5L), anyInt()))
                .thenReturn(List.of(msg(6, "user", "x")));
        when(summarizationModel.chat(anyString())).thenThrow(new RuntimeException("model down"));

        assertDoesNotThrow(() -> service.summarizeNow(1L));
        // 游标未推进:update 时 lastSummarizedId 仍为 5,且 failCount++
        ArgumentCaptor<AppMemorySummary> cap = ArgumentCaptor.forClass(AppMemorySummary.class);
        verify(summaryMapper).update(cap.capture());
        assertEquals(5L, cap.getValue().getLastSummarizedId());
        assertEquals(1, cap.getValue().getFailCount());
    }

    @Test
    void getCurrentSummaryReturnsEmptyWhenAbsent() {
        when(summaryMapper.selectOneByQuery(any())).thenReturn(null);
        assertEquals("", service.getCurrentSummary(1L));
    }
}
