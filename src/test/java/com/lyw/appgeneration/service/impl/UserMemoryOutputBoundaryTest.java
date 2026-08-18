package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.memory.ChatTokenEstimator;
import com.lyw.appgeneration.ai.memory.ConservativeChatTokenEstimator;
import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.mapper.AppMapper;
import com.lyw.appgeneration.mapper.AppMemoryExtractCursorMapper;
import com.lyw.appgeneration.mapper.AppMemoryMapper;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.entity.AppMemory;
import com.lyw.appgeneration.model.entity.AppMemoryExtractCursor;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;
import com.lyw.appgeneration.monitor.MemoryCompressionMetricsCollector;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.mybatisflex.core.query.QueryWrapper;
import dev.langchain4j.model.chat.ChatModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserMemoryOutputBoundaryTest {

    private static final long USER_ID = 7L;
    private static final long APP_ID = 100L;
    private static final String CACHE_KEY = "mem:pref:v2:" + USER_ID;
    private static final Set<String> ALLOWED_NAMES = Set.of(
            "语言偏好", "视觉风格", "技术栈倾向", "交互习惯");

    private ChatHistoryService chatHistoryService;
    private AppMemoryMapper memoryMapper;
    private AppMemoryExtractCursorMapper cursorMapper;
    private AppMapper appMapper;
    private ChatModel model;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private MemoryTokenProperties properties;
    private ChatTokenEstimator tokenEstimator;
    private SimpleMeterRegistry meterRegistry;
    private RecordingTransactionOperations transactions;
    private UserMemoryServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        chatHistoryService = mock(ChatHistoryService.class);
        memoryMapper = mock(AppMemoryMapper.class);
        cursorMapper = mock(AppMemoryExtractCursorMapper.class);
        appMapper = mock(AppMapper.class);
        model = mock(ChatModel.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        properties = new MemoryTokenProperties();
        tokenEstimator = new ConservativeChatTokenEstimator(properties);
        meterRegistry = new SimpleMeterRegistry();
        transactions = new RecordingTransactionOperations();
        service = new UserMemoryServiceImpl(
                chatHistoryService, memoryMapper, cursorMapper, appMapper,
                model, mock(ExecutorService.class), mock(TaskScheduler.class),
                redisTemplate, new AppDataLifecycleFence(), tokenEstimator,
                properties, transactions,
                new MemoryCompressionMetricsCollector(meterRegistry),
                Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"),
                        ZoneOffset.UTC));

        when(cursorMapper.selectOneByQuery(any())).thenReturn(null);
        when(memoryMapper.selectListByQuery(any())).thenReturn(List.of());
        when(memoryMapper.selectOneByQuery(any())).thenReturn(null);
        when(memoryMapper.insert(any())).thenReturn(1);
        when(memoryMapper.update(any())).thenReturn(1);
        when(cursorMapper.insert(any())).thenReturn(1);
        when(cursorMapper.update(any())).thenReturn(1);
        when(cursorMapper.update(any(AppMemoryExtractCursor.class), eq(false)))
                .thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        meterRegistry.close();
    }

    @Test
    @DisplayName("合法非空数组被固定类别过滤后成功推进游标")
    void 合法非空数组被固定类别过滤后成功推进游标() {
        provideStableTurn();
        when(model.chat(any(String.class))).thenReturn("""
                [{"name":"未允许类别","content":"任意内容",
                  "evidenceType":"EXPLICIT","turnIds":[11]}]
                """);

        service.extractNow(USER_ID, APP_ID);

        assertSuccessfulCursorAdvancedTo(12L);
        assertEquals(1, transactions.executionCount());
        verify(memoryMapper, never()).insert(any());
        verify(memoryMapper, never()).update(any());
    }

    @Test
    @DisplayName("模型整批原始输出超过八千一百九十二 Token 时拒绝")
    void rawModelOutputOverMaxOutputTokensFailsBatch() {
        provideStableTurn();
        String raw = """
                [{"name":"语言偏好","content":"%s",
                  "evidenceType":"EXPLICIT","turnIds":[11]}]
                """.formatted("超".repeat(20_000));
        assertTrue(tokenEstimator.estimateText(raw)
                > properties.getMaxOutputTokens());
        when(model.chat(any(String.class))).thenReturn(raw);

        service.extractNow(USER_ID, APP_ID);

        assertFailedCursorRemainsAtZero();
        assertEquals(0, transactions.executionCount());
        verify(memoryMapper, never()).insert(any());
    }

    @Test
    @DisplayName("单条偏好超过一千零二十四 Token 时过滤并推进游标")
    void 单条偏好超过一千零二十四Token时过滤并推进游标() {
        provideStableTurn();
        String content = "超".repeat(2_000);
        String raw = """
                [{"name":"语言偏好","content":"%s",
                  "evidenceType":"EXPLICIT","turnIds":[11]}]
                """.formatted(content);
        assertTrue(tokenEstimator.estimateText("- 语言偏好:" + content)
                > properties.getL2MaxRecallTokens());
        assertTrue(tokenEstimator.estimateText(raw)
                <= properties.getMaxOutputTokens());
        when(model.chat(any(String.class))).thenReturn(raw);

        service.extractNow(USER_ID, APP_ID);

        assertSuccessfulCursorAdvancedTo(12L);
        assertEquals(1, transactions.executionCount());
        verify(memoryMapper, never()).insert(any());
    }

    @Test
    @DisplayName("合法与非法候选混合时只保留固定五类")
    void mixedCandidatesPersistOnlyFiveAllowedNames() {
        provideStableTurn();
        when(model.chat(any(String.class))).thenReturn("""
                [
                  {"name":"语言偏好","valueCodes":["ZH_CN"],"evidenceType":"EXPLICIT","turnIds":[11]},
                  {"name":"视觉风格","valueCodes":["DARK","MINIMAL"],"evidenceType":"EXPLICIT","turnIds":[11]},
                  {"name":"技术栈倾向","valueCodes":["VUE3"],"evidenceType":"EXPLICIT","turnIds":[11]},
                  {"name":"交互习惯","valueCodes":["KEYBOARD_FIRST"],"evidenceType":"EXPLICIT","turnIds":[11]},
                  {"name":"其他","content":"减少动画","evidenceType":"EXPLICIT","turnIds":[11]},
                  {"name":"第六类别","content":"不得持久化","evidenceType":"EXPLICIT","turnIds":[11]}
                ]
                """);

        service.extractNow(USER_ID, APP_ID);

        ArgumentCaptor<AppMemory> inserted =
                ArgumentCaptor.forClass(AppMemory.class);
        verify(memoryMapper, times(4)).insert(inserted.capture());
        assertEquals(ALLOWED_NAMES, inserted.getAllValues().stream()
                .map(AppMemory::getName)
                .collect(java.util.stream.Collectors.toSet()));
        assertSuccessfulCursorAdvancedTo(12L);
        assertEquals(1, transactions.executionCount());
    }

    @Test
    @DisplayName("已有偏好 Prompt 过滤旧异常类别和超限条目并保持有界")
    void existingPreferencesPromptFiltersLegacyAnomaliesAndStaysBounded() {
        provideStableTurn();
        List<AppMemory> legacyRows = new ArrayList<>();
        for (int index = 0; index < 24; index++) {
            legacyRows.add(preference(index + 1L, "旧异常类别" + index,
                    "异常旧值".repeat(500)));
        }
        legacyRows.add(preference(100L, "技术栈倾向", "超限旧值".repeat(600)));
        legacyRows.add(preference(101L, "语言偏好", "简体中文"));
        when(memoryMapper.selectListByQuery(any())).thenReturn(legacyRows);
        when(model.chat(any(String.class))).thenReturn("[]");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<QueryWrapper> query =
                ArgumentCaptor.forClass(QueryWrapper.class);

        service.extractNow(USER_ID, APP_ID);

        verify(model).chat(prompt.capture());
        verify(memoryMapper).selectListByQuery(query.capture());
        assertTrue(prompt.getValue().contains(
                "name 字段只能使用以下固定类别"));
        assertTrue(prompt.getValue().contains("name=语言偏好"));
        assertFalse(prompt.getValue().contains("旧异常类别"));
        assertFalse(prompt.getValue().contains("超限旧值"));
        assertTrue(tokenEstimator.estimateText(prompt.getValue())
                <= properties.getAsyncCompressionThreshold());
        assertTrue(hasNameWhitelistCondition(query.getValue()));
        assertSuccessfulCursorAdvancedTo(12L);
    }

    @Test
    @DisplayName("召回拒绝旧异常缓存并只回填固定类别完整条目")
    void recallRejectsLegacyCacheAndFiltersDatabaseRows() {
        when(appMapper.selectOneById(APP_ID)).thenReturn(
                App.builder().id(APP_ID).userId(USER_ID).build());
        when(valueOperations.get(CACHE_KEY))
                .thenReturn("- 旧异常类别:不应继续注入");
        when(memoryMapper.selectListByQuery(any())).thenReturn(List.of(
                preference(1L, "旧异常类别", "不应继续注入"),
                preference(2L, "视觉风格", "超".repeat(2_000)),
                preference(3L, "语言偏好", "简体中文")));
        ArgumentCaptor<QueryWrapper> query =
                ArgumentCaptor.forClass(QueryWrapper.class);

        String recalled = service.recallByApp(APP_ID);

        assertEquals("- 语言偏好:简体中文", recalled);
        verify(redisTemplate).delete(CACHE_KEY);
        verify(memoryMapper).selectListByQuery(query.capture());
        assertTrue(hasNameWhitelistCondition(query.getValue()));
        verify(valueOperations).set(eq(CACHE_KEY), eq(recalled), any());
    }

    @Test
    @DisplayName("空数组仍是合法输出并正常推进游标")
    void emptyArrayRemainsValid() {
        provideStableTurn();
        when(model.chat(any(String.class))).thenReturn("[]");

        service.extractNow(USER_ID, APP_ID);

        assertSuccessfulCursorAdvancedTo(12L);
        assertEquals(1, transactions.executionCount());
        verify(memoryMapper, never()).insert(any());
    }

    @Test
    @DisplayName("雪花 turnId 带小数时不得截断后命中白名单")
    void fractionalSnowflakeTurnIdMustNotMatchWhitelist() {
        long userTurnId = 446_663_972_690_808_832L;
        when(chatHistoryService.listMessagesAfterCursor(
                eq(APP_ID), anyLong(), anyInt())).thenReturn(List.of(
                ChatHistory.builder()
                        .id(userTurnId).appId(APP_ID).userId(USER_ID)
                        .messageType("user").message("以后都使用中文")
                        .build(),
                ChatHistory.builder()
                        .id(userTurnId + 1).appId(APP_ID).userId(USER_ID)
                        .messageType("ai").message("已收到")
                        .memoryMessage("已收到")
                        .memoryOutcome(ChatMemoryOutcome.SUCCEEDED)
                        .build()));
        when(model.chat(any(String.class))).thenReturn("""
                [{"name":"语言偏好","valueCodes":["ZH_CN"],
                  "evidenceType":"EXPLICIT",
                  "turnIds":[446663972690808832.1]}]
                """);

        service.extractNow(USER_ID, APP_ID);

        verify(memoryMapper, never()).insert(any());
        assertSuccessfulCursorAdvancedTo(userTurnId + 1);
    }

    private void provideStableTurn() {
        when(chatHistoryService.listMessagesAfterCursor(
                eq(APP_ID), anyLong(), anyInt())).thenReturn(List.of(
                ChatHistory.builder()
                        .id(11L).appId(APP_ID).userId(USER_ID)
                        .messageType("user").message("以后都使用中文")
                        .build(),
                ChatHistory.builder()
                        .id(12L).appId(APP_ID).userId(USER_ID)
                        .messageType("ai").message("已收到")
                        .memoryMessage("已收到")
                        .memoryOutcome(ChatMemoryOutcome.SUCCEEDED)
                        .build()));
    }

    private AppMemory preference(long id, String name, String content) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 16, 8, 0);
        return AppMemory.builder()
                .id(id).userId(USER_ID).appId(APP_ID)
                .type("USER_PREFERENCE").name(name).content(content)
                .evidenceType("EXPLICIT").status("ACTIVE")
                .evidenceCount(1).lastEvidenceTurnId(11L)
                .createTime(now).updateTime(now).build();
    }

    private void assertFailedCursorRemainsAtZero() {
        AppMemoryExtractCursor cursor = captureInsertedCursor();
        assertEquals(0L, cursor.getLastExtractedId());
        assertEquals(1, cursor.getFailCount());
    }

    private void assertSuccessfulCursorAdvancedTo(long expectedId) {
        AppMemoryExtractCursor cursor = captureInsertedCursor();
        assertEquals(expectedId, cursor.getLastExtractedId());
        assertEquals(0, cursor.getFailCount());
    }

    private AppMemoryExtractCursor captureInsertedCursor() {
        ArgumentCaptor<AppMemoryExtractCursor> cursor =
                ArgumentCaptor.forClass(AppMemoryExtractCursor.class);
        verify(cursorMapper).insert(cursor.capture());
        return cursor.getValue();
    }

    private boolean hasNameWhitelistCondition(QueryWrapper query) {
        try {
            java.lang.reflect.Field whereField = query.getClass()
                    .getSuperclass().getDeclaredField("whereQueryCondition");
            whereField.setAccessible(true);
            Object condition = whereField.get(query);
            while (condition != null) {
                java.lang.reflect.Field columnField = condition.getClass()
                        .getDeclaredField("column");
                java.lang.reflect.Field logicField = condition.getClass()
                        .getDeclaredField("logic");
                java.lang.reflect.Field nextField = condition.getClass()
                        .getDeclaredField("next");
                columnField.setAccessible(true);
                logicField.setAccessible(true);
                nextField.setAccessible(true);
                Object column = columnField.get(condition);
                String logic = String.valueOf(logicField.get(condition));
                if ("name".equals(readColumnName(column))
                        && logic.trim().equalsIgnoreCase("IN")) {
                    return true;
                }
                condition = nextField.get(condition);
            }
            return false;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private String readColumnName(Object queryColumn)
            throws ReflectiveOperationException {
        java.lang.reflect.Field contentField = queryColumn.getClass()
                .getDeclaredField("content");
        contentField.setAccessible(true);
        return String.valueOf(contentField.get(queryColumn));
    }

    private static final class RecordingTransactionOperations
            implements TransactionOperations {

        private final AtomicInteger executionCount = new AtomicInteger();

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            executionCount.incrementAndGet();
            return action.doInTransaction(new SimpleTransactionStatus());
        }

        private int executionCount() {
            return executionCount.get();
        }
    }
}
