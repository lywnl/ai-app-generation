package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.mapper.AppMemoryExtractCursorMapper;
import com.lyw.appgeneration.mapper.AppMemoryMapper;
import com.lyw.appgeneration.model.entity.AppMemory;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.mapper.AppMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserMemoryServiceImplTest {

    private ChatHistoryService chatHistoryService;
    private AppMemoryMapper appMemoryMapper;
    private AppMemoryExtractCursorMapper cursorMapper;
    private AppMapper appMapper;
    private ChatModel model;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private UserMemoryServiceImpl service;

    private static final Long USER = 7L;
    private static final Long APP = 100L;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        chatHistoryService = mock(ChatHistoryService.class);
        appMemoryMapper = mock(AppMemoryMapper.class);
        cursorMapper = mock(AppMemoryExtractCursorMapper.class);
        appMapper = mock(AppMapper.class);
        model = mock(ChatModel.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // 同步直调 extractNow,executor 不被触发,仅满足构造器类型(对齐 L1 测试用 newSingleThreadExecutor)
        service = new UserMemoryServiceImpl(chatHistoryService, appMemoryMapper, cursorMapper,
                appMapper, model, Executors.newSingleThreadExecutor(), redisTemplate, 1, 60);
    }

    private ChatHistory msg(long id, String type, String text) {
        return ChatHistory.builder().id(id).messageType(type).message(text).build();
    }

    @Test
    void extractInsertsNewPreferenceAndAdvancesCursor() {
        when(cursorMapper.selectOneByQuery(any())).thenReturn(null); // 无游标
        when(chatHistoryService.listMessagesAfterCursor(eq(APP), eq(0L), anyInt()))
                .thenReturn(List.of(msg(11L, "user", "以后都用简体中文")));
        when(appMemoryMapper.selectOneByQuery(any())).thenReturn(null); // 该 name 不存在
        when(model.chat(anyString()))
                .thenReturn("[{\"name\":\"语言偏好\",\"content\":\"简体中文\"}]");

        service.extractNow(USER, APP);

        ArgumentCaptor<AppMemory> cap = ArgumentCaptor.forClass(AppMemory.class);
        verify(appMemoryMapper).insert(cap.capture());
        assertEquals("语言偏好", cap.getValue().getName());
        assertEquals("简体中文", cap.getValue().getContent());
        assertEquals(USER, cap.getValue().getUserId());
        assertNotNull(cap.getValue().getCreateTime(), "insert 必须带 createTime(否则真实DB NOT NULL 约束崩)");
        verify(cursorMapper).insert(any()); // 游标行新建并推进
    }

    @Test
    void extractUpdatesExistingPreferenceByName() {
        when(cursorMapper.selectOneByQuery(any())).thenReturn(null);
        when(chatHistoryService.listMessagesAfterCursor(eq(APP), eq(0L), anyInt()))
                .thenReturn(List.of(msg(12L, "user", "改成深色")));
        AppMemory existing = AppMemory.builder().id(1L).userId(USER).type("USER_PREFERENCE")
                .name("视觉风格").content("浅色").build();
        when(appMemoryMapper.selectOneByQuery(any())).thenReturn(existing);
        when(model.chat(anyString()))
                .thenReturn("[{\"name\":\"视觉风格\",\"content\":\"深色\"}]");

        service.extractNow(USER, APP);

        verify(appMemoryMapper).update(argThat(m -> "深色".equals(((AppMemory) m).getContent())));
        verify(appMemoryMapper, never()).insert(any());
    }

    @Test
    void belowThresholdSkips() {
        UserMemoryServiceImpl s = new UserMemoryServiceImpl(chatHistoryService, appMemoryMapper,
                cursorMapper, appMapper, model, Executors.newSingleThreadExecutor(), redisTemplate, 5, 60); // 阈值 5
        when(cursorMapper.selectOneByQuery(any())).thenReturn(null);
        when(chatHistoryService.listMessagesAfterCursor(eq(APP), eq(0L), anyInt()))
                .thenReturn(List.of(msg(1L, "user", "a"), msg(2L, "ai", "b"))); // 仅 2 条 < 5

        s.extractNow(USER, APP);

        verify(model, never()).chat(anyString());
        verify(appMemoryMapper, never()).insert(any());
    }

    @Test
    void jsonParseFailureDoesNotThrowNorAdvanceCursor() {
        when(cursorMapper.selectOneByQuery(any())).thenReturn(null);
        when(chatHistoryService.listMessagesAfterCursor(eq(APP), eq(0L), anyInt()))
                .thenReturn(List.of(msg(13L, "user", "x")));
        when(appMemoryMapper.selectOneByQuery(any())).thenReturn(null);
        when(model.chat(anyString())).thenReturn("这不是JSON"); // 解析失败

        assertDoesNotThrow(() -> service.extractNow(USER, APP));
        verify(appMemoryMapper, never()).insert(any());
        // 首次失败:游标行只记 failCount,不推进 lastExtractedId
        verify(cursorMapper).insert(argThat(c ->
                ((com.lyw.appgeneration.model.entity.AppMemoryExtractCursor) c).getFailCount() == 1
                && ((com.lyw.appgeneration.model.entity.AppMemoryExtractCursor) c).getLastExtractedId() == 0L));
    }

    // ---- 召回(recallByApp):反查 userId + top-N + 缓存 cache-aside ----

    @Test
    void recallReversesAppIdToUserIdAndReturnsTopN() {
        when(appMapper.selectOneById(APP)).thenReturn(
                com.lyw.appgeneration.model.entity.App.builder().id(APP).userId(USER).build());
        when(valueOps.get(PREF_CACHE_KEY())).thenReturn(null); // 缓存未命中
        when(appMemoryMapper.selectListByQuery(any())).thenReturn(List.of(
                AppMemory.builder().name("语言偏好").content("简体中文").build(),
                AppMemory.builder().name("视觉风格").content("扁平极简").build()));

        String text = service.recallByApp(APP);

        assertTrue(text.contains("语言偏好:简体中文"));
        assertTrue(text.contains("视觉风格:扁平极简"));
        verify(valueOps).set(eq(PREF_CACHE_KEY()), anyString(), any()); // 回填缓存
    }

    @Test
    void recallHitsCacheWithoutQueryingDb() {
        when(appMapper.selectOneById(APP)).thenReturn(
                com.lyw.appgeneration.model.entity.App.builder().id(APP).userId(USER).build());
        when(valueOps.get(PREF_CACHE_KEY())).thenReturn("- 语言偏好:简体中文"); // 命中

        String text = service.recallByApp(APP);

        assertEquals("- 语言偏好:简体中文", text);
        verify(appMemoryMapper, never()).selectListByQuery(any()); // 不查 DB
    }

    @Test
    void recallReturnsEmptyWhenAppNotFound() {
        when(appMapper.selectOneById(APP)).thenReturn(null); // app 不存在

        assertEquals("", service.recallByApp(APP));
        verify(appMemoryMapper, never()).selectListByQuery(any());
    }

    /** 测试辅助:与实现中 PREF_CACHE_PREFIX 对齐。 */
    private String PREF_CACHE_KEY() {
        return "mem:pref:" + USER;
    }
}
