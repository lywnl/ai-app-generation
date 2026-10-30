package com.lyw.appgeneration.service.impl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lyw.appgeneration.ai.memory.ChatTokenEstimator;
import com.lyw.appgeneration.ai.memory.ConservativeChatTokenEstimator;
import com.lyw.appgeneration.ai.memory.UserPreferencePromptBuilder;
import com.lyw.appgeneration.ai.memory.UserPreferenceMessageFragmentBuilder;
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
import com.lyw.appgeneration.monitor.ThrowingMeterRegistry;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemoryCacheInvalidationResult;
import com.mybatisflex.core.query.QueryWrapper;
import dev.langchain4j.model.chat.ChatModel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserMemoryServiceImplTest {

    private static final long USER_ID = 7L;
    private static final long APP_ID = 100L;
    private static final long APP_B = 200L;

    private ChatHistoryService chatHistoryService;
    private AppMemoryMapper memoryMapper;
    private AppMemoryExtractCursorMapper cursorMapper;
    private AppMapper appMapper;
    private ChatModel model;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private MemoryTokenProperties properties;
    private ChatTokenEstimator tokenEstimator;
    private AppDataLifecycleFence lifecycleFence;
    private RecordingTransactionOperations transactions;
    private SimpleMeterRegistry metricsRegistry;
    private MemoryCompressionMetricsCollector metricsCollector;
    private UserMemoryServiceImpl service;

    @Test
    void exposesMetricsAwareProductionConstructor() {
        assertDoesNotThrow(() -> UserMemoryServiceImpl.class.getConstructor(
                ChatHistoryService.class,
                VersionedUserMemoryRepository.class,
                AppMemoryExtractCursorMapper.class,
                AppMapper.class,
                ChatModel.class,
                ExecutorService.class,
                TaskScheduler.class,
                StringRedisTemplate.class,
                AppDataLifecycleFence.class,
                ChatTokenEstimator.class,
                MemoryTokenProperties.class,
                TransactionOperations.class,
                MemoryCompressionMetricsCollector.class));
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void 初始化依赖() {
        chatHistoryService = mock(ChatHistoryService.class);
        memoryMapper = mock(AppMemoryMapper.class);
        cursorMapper = mock(AppMemoryExtractCursorMapper.class);
        appMapper = mock(AppMapper.class);
        when(appMapper.selectOneById(anyLong())).thenAnswer(inv -> App.builder().id(inv.getArgument(0)).userId(USER_ID).build());
        model = mock(ChatModel.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        properties = new MemoryTokenProperties();
        properties.setEstimationSafetyFactor(1D);
        tokenEstimator = new ConservativeChatTokenEstimator(properties);
        lifecycleFence = new AppDataLifecycleFence();
        transactions = new RecordingTransactionOperations();
        metricsRegistry = new SimpleMeterRegistry();
        metricsCollector = new MemoryCompressionMetricsCollector(
                metricsRegistry);
        service = newService(metricsCollector);

        when(cursorMapper.selectOneByQuery(any())).thenReturn(null);
        when(memoryMapper.selectListByQuery(any())).thenReturn(List.of());
        when(memoryMapper.insert(any())).thenReturn(1);
        when(memoryMapper.update(any())).thenReturn(1);
        when(cursorMapper.insert(any())).thenReturn(1);
        when(cursorMapper.update(any())).thenReturn(1);
        when(cursorMapper.update(
                any(AppMemoryExtractCursor.class), eq(false))).thenReturn(1);
    }

    @AfterEach
    void closeMetricsRegistry() {
        metricsRegistry.close();
    }

    @Test
    @DisplayName("显式偏好由一个完整回合直接激活")
    void 显式一次证据直接激活() {
        提供历史(完整回合(11L, 12L,
                "以后所有应用都使用简体中文", "已收到"));
        when(memoryMapper.selectOneByQuery(any())).thenReturn(null);
        when(model.chat(any(String.class))).thenReturn("""
                {"operations": [{"action": "ADD", "category": "语言偏好", "attribute": "页面默认语言", "content": "简体中文", "scope": "USER", "evidenceType": "EXPLICIT", "turnIds": ["11"]}]}
                """);

        service.extractNow(USER_ID, APP_ID);

        AppMemory inserted = 捕获新增偏好();
        assertEquals("ACTIVE", inserted.getStatus());
        assertEquals("EXPLICIT", inserted.getEvidenceType());
        assertEquals(1, inserted.getEvidenceCount());
        assertEquals(11L, inserted.getLastEvidenceTurnId());
        断言游标新增到(12L);
        assertEquals(1D, counter(metricsRegistry,
                "memory_l2_candidate_total",
                "status", "active").count());
    }

    @Test
    @DisplayName("生产构造写入时间沿用 JVM 默认时区")
    void 生产构造不会把本地数据库时间写成Utc时间() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            UserMemoryServiceImpl defaultClockService =
                    new UserMemoryServiceImpl(
                            chatHistoryService, MemoryTestSupport.repository(memoryMapper), cursorMapper,
                            appMapper, model, mock(ExecutorService.class),
                            mock(TaskScheduler.class), redisTemplate,
                            lifecycleFence, tokenEstimator, properties,
                            transactions,
                            metricsCollector);
            提供历史(完整回合(13L, 14L,
                    "以后所有应用都使用简体中文", "已收到"));
            when(model.chat(any(String.class))).thenReturn("""
                {"operations": [{"action": "ADD", "category": "语言偏好", "attribute": "页面默认语言", "content": "简体中文", "scope": "USER", "evidenceType": "EXPLICIT", "turnIds": ["13"]}]}
                """);
            LocalDateTime before = LocalDateTime.now();

            defaultClockService.extractNow(USER_ID, APP_ID);

            LocalDateTime after = LocalDateTime.now();
            AppMemory inserted = 捕获新增偏好();
            assertFalse(inserted.getCreateTime().isBefore(
                    before.minusSeconds(1)));
            assertFalse(inserted.getCreateTime().isAfter(
                    after.plusSeconds(1)));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    @DisplayName("偏好 upsert 与游标推进必须处于同一事务边界")
    void 偏好与游标在同一事务内提交() {
        提供历史(完整回合(45L, 46L,
                "以后都使用中文", "已收到"));
        when(memoryMapper.selectOneByQuery(any())).thenReturn(null);
        when(model.chat(any(String.class))).thenReturn("""
                {"operations": [{"action": "ADD", "category": "语言偏好", "attribute": "页面默认语言", "content": "简体中文", "scope": "USER", "evidenceType": "EXPLICIT", "turnIds": ["45"]}]}
                """);
        when(memoryMapper.insert(any())).thenAnswer(invocation -> {
            assertTrue(transactions.isActive());
            return 1;
        });
        when(cursorMapper.insert(any())).thenAnswer(invocation -> {
            assertTrue(transactions.isActive());
            return 1;
        });

        service.extractNow(USER_ID, APP_ID);

        assertEquals(1, transactions.executionCount());
    }

    @Test
    @DisplayName("候选指标只在事务执行器成功返回后记录")
    void 候选指标不会在事务Callback内提前记录() {
        提供历史(完整回合(47L, 48L,
                "以后都使用中文", "已收到"));
        when(memoryMapper.selectOneByQuery(any())).thenReturn(null);
        when(model.chat(any(String.class))).thenReturn("""
                {"operations": [{"action": "ADD", "category": "语言偏好", "attribute": "页面默认语言", "content": "简体中文", "scope": "USER", "evidenceType": "EXPLICIT", "turnIds": ["47"]}]}
                """);
        transactions.afterCallback(() -> assertTrue(metricsRegistry
                .find("memory_l2_candidate_total").counters().isEmpty()));

        service.extractNow(USER_ID, APP_ID);

        assertEquals(1D, counter(metricsRegistry,
                "memory_l2_candidate_total",
                "status", "active").count());
    }

    @Test
    @DisplayName("事务 callback 完成后提交失败不得记录候选指标")
    void 候选指标不会在事务提交失败时记录() {
        提供历史(完整回合(49L, 50L,
                "以后都使用中文", "已收到"));
        when(memoryMapper.selectOneByQuery(any())).thenReturn(null);
        when(model.chat(any(String.class))).thenReturn("""
                {"operations": [{"action": "ADD", "category": "语言偏好", "attribute": "页面默认语言", "content": "简体中文", "scope": "USER", "evidenceType": "EXPLICIT", "turnIds": ["49"]}]}
                """);
        transactions.failAfterCallback(
                new IllegalStateException("模拟事务提交失败"));

        service.extractNow(USER_ID, APP_ID);

        assertTrue(metricsRegistry.find("memory_l2_candidate_total")
                .counters().isEmpty());
    }

    @Test
    @DisplayName("事务提交确认丢失后不得用旧快照倒退可能已提交的游标")
    void 提交结果不确定时不补写旧失败游标() {
        AppMemoryExtractCursor original = AppMemoryExtractCursor.builder()
                .id(1L)
                .appId(APP_ID)
                .userId(USER_ID)
                .lastExtractedId(40L)
                .failCount(0)
                .createTime(LocalDateTime.of(2026, 8, 16, 8, 0))
                .updateTime(LocalDateTime.of(2026, 8, 16, 8, 0))
                .isDelete(0)
                .build();
        AtomicReference<AppMemoryExtractCursor> store =
                new AtomicReference<>(original);
        when(cursorMapper.selectOneByQuery(any())).thenAnswer(invocation ->
                store.get());
        when(cursorMapper.update(
                any(AppMemoryExtractCursor.class), eq(false)))
                .thenAnswer(invocation -> {
                    store.set(invocation.getArgument(0));
                    return 1;
                });
        when(cursorMapper.update(any(AppMemoryExtractCursor.class)))
                .thenAnswer(invocation -> {
                    store.set(invocation.getArgument(0));
                    return 1;
                });
        提供历史(完整回合(41L, 42L,
                "这轮没有稳定偏好", "已收到"));
        when(model.chat(any(String.class))).thenReturn("{\"operations\":[]}");
        transactions.failAfterCallback(
                new IllegalStateException("commit acknowledgement lost"));

        service.extractNow(USER_ID, APP_ID);

        assertEquals(42L, store.get().getLastExtractedId());
        assertEquals(0, store.get().getFailCount());
        assertNull(store.get().getNextRetryTime());
        verify(cursorMapper).update(
                any(AppMemoryExtractCursor.class), eq(false));
        verify(cursorMapper, never()).update(
                any(AppMemoryExtractCursor.class));
    }

    @Test
    @DisplayName("非法 JSON 不推进游标且记录可恢复失败")
    void 非法Json不推进处理游标() {
        提供历史(完整回合(91L, 92L,
                "以后都用中文", "已收到"));
        when(model.chat(any(String.class))).thenReturn("不是 JSON 数组");

        service.extractNow(USER_ID, APP_ID);

        ArgumentCaptor<AppMemoryExtractCursor> cursor =
                ArgumentCaptor.forClass(AppMemoryExtractCursor.class);
        verify(cursorMapper).insert(cursor.capture());
        assertEquals(0L, cursor.getValue().getLastExtractedId());
        assertEquals(1, cursor.getValue().getFailCount());
        assertEquals(LocalDateTime.of(2026, 8, 16, 0, 0, 5),
                cursor.getValue().getNextRetryTime());
    }

    @Test
    @DisplayName("已有失败游标再次失败时同次更新精确指数退避时间")
    void 已有失败游标更新精确下一次重试时间() {
        AppMemoryExtractCursor current = AppMemoryExtractCursor.builder()
                .id(1L)
                .appId(APP_ID)
                .userId(USER_ID)
                .lastExtractedId(0L)
                .failCount(2)
                .build();
        when(cursorMapper.selectOneByQuery(any())).thenReturn(current);
        提供历史(完整回合(93L, 94L,
                "以后都用中文", "已收到"));
        when(model.chat(any(String.class))).thenReturn("不是 JSON 数组");

        service.extractNow(USER_ID, APP_ID);

        ArgumentCaptor<AppMemoryExtractCursor> cursor =
                ArgumentCaptor.forClass(AppMemoryExtractCursor.class);
        verify(cursorMapper).update(cursor.capture());
        assertEquals(0L, cursor.getValue().getLastExtractedId());
        assertEquals(3, cursor.getValue().getFailCount());
        assertEquals(LocalDateTime.of(2026, 8, 16, 0, 0, 20),
                cursor.getValue().getNextRetryTime());
    }

    @Test
    @DisplayName("空数组正常推进到最后稳定 AI 边界")
    void 空数组正常推进游标() {
        提供历史(完整回合(101L, 102L,
                "帮我做个页面", "已完成"));
        when(model.chat(any(String.class))).thenReturn("{\"operations\":[]}");

        service.extractNow(USER_ID, APP_ID);

        断言游标新增到(102L);
    }

    @Test
    @DisplayName("成功推进已有游标时清除持久化失败元数据")
    void 成功推进已有游标清零失败元数据() {
        AppMemoryExtractCursor current = AppMemoryExtractCursor.builder()
                .id(2L)
                .appId(APP_ID)
                .userId(USER_ID)
                .lastExtractedId(0L)
                .failCount(3)
                .nextRetryTime(LocalDateTime.of(
                        2026, 8, 16, 0, 1))
                .createTime(LocalDateTime.of(2026, 8, 15, 23, 0))
                .updateTime(LocalDateTime.of(2026, 8, 15, 23, 30))
                .isDelete(0)
                .build();
        when(cursorMapper.selectOneByQuery(any())).thenReturn(current);
        提供历史(完整回合(103L, 104L,
                "帮我做个页面", "已完成"));
        when(model.chat(any(String.class))).thenReturn("{\"operations\":[]}");

        service.extractNow(USER_ID, APP_ID);

        ArgumentCaptor<AppMemoryExtractCursor> cursor =
                ArgumentCaptor.forClass(AppMemoryExtractCursor.class);
        verify(cursorMapper).update(cursor.capture(), eq(false));
        assertEquals(104L, cursor.getValue().getLastExtractedId());
        assertEquals(0, cursor.getValue().getFailCount());
        assertNull(cursor.getValue().getNextRetryTime());
        assertEquals(current.getCreateTime(),
                cursor.getValue().getCreateTime());
        assertEquals(current.getIsDelete(), cursor.getValue().getIsDelete());
    }

    @Test
    @DisplayName("没有待处理稳定回合时也清除已有失败元数据")
    void 无待处理稳定回合清零失败元数据() {
        AppMemoryExtractCursor current = AppMemoryExtractCursor.builder()
                .id(3L)
                .appId(APP_ID)
                .userId(USER_ID)
                .lastExtractedId(10L)
                .failCount(2)
                .nextRetryTime(LocalDateTime.of(
                        2026, 8, 16, 0, 1))
                .createTime(LocalDateTime.of(2026, 8, 15, 23, 0))
                .updateTime(LocalDateTime.of(2026, 8, 15, 23, 30))
                .isDelete(0)
                .build();
        when(cursorMapper.selectOneByQuery(any())).thenReturn(current);
        when(chatHistoryService.listMessagesAfterCursor(
                eq(APP_ID), eq(10L), anyInt())).thenReturn(List.of());

        service.extractNow(USER_ID, APP_ID);

        ArgumentCaptor<AppMemoryExtractCursor> cursor =
                ArgumentCaptor.forClass(AppMemoryExtractCursor.class);
        verify(cursorMapper).update(cursor.capture(), eq(false));
        assertEquals(10L, cursor.getValue().getLastExtractedId());
        assertEquals(0, cursor.getValue().getFailCount());
        assertNull(cursor.getValue().getNextRetryTime());
        assertEquals(current.getCreateTime(),
                cursor.getValue().getCreateTime());
        assertEquals(current.getIsDelete(), cursor.getValue().getIsDelete());
        verify(model, never()).chat(any(String.class));
    }

    @Test
    @DisplayName("Prompt 只携带稳定回合的 turnId 与用户文本")
    void 只处理完整回合且不发送Ai正文() {
        String aiCode = "<template><script>完整生成代码</script></template>";
        提供历史(List.of(
                消息(1L, "ai", "孤立回复"),
                消息(11L, "user", "所有应用都使用深色极简"),
                消息(12L, "ai", aiCode),
                消息(13L, "user", "未完成尾部不得处理")));
        when(model.chat(any(String.class))).thenReturn("{\"operations\":[]}");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);

        service.extractNow(USER_ID, APP_ID);

        verify(model).chat(prompt.capture());
        assertTrue(prompt.getValue().contains("turnId=11"));
        assertTrue(prompt.getValue().contains("所有应用都使用深色极简"));
        assertFalse(prompt.getValue().contains(aiCode));
        assertFalse(prompt.getValue().contains("未完成尾部不得处理"));
        断言游标新增到(12L);
    }

    @Test
    @DisplayName("下一个完整回合越过 Token 阈值时留到下一批")
    void 完整回合按Token预算分批且不拆分() {
        String firstUser = "甲".repeat(120);
        String secondUser = "乙".repeat(120);
        String firstEvidence = 用户证据(111L, firstUser);
        String bothEvidence = firstEvidence + "\n\n"
                + 用户证据(121L, secondUser);
        int oneTurnTokens = tokenEstimator.estimateText(
                UserPreferencePromptBuilder.build(
                        "", firstEvidence, List.of(111L)));
        int twoTurnTokens = tokenEstimator.estimateText(
                UserPreferencePromptBuilder.build(
                        "", bothEvidence, List.of(111L, 121L)));
        assertTrue(twoTurnTokens > oneTurnTokens);
        properties.setAsyncCompressionThreshold(oneTurnTokens);
        AppMemoryExtractCursor secondCursor = AppMemoryExtractCursor.builder()
                .id(4L).appId(APP_ID).userId(USER_ID)
                .lastExtractedId(112L).failCount(0)
                .createTime(LocalDateTime.of(2026, 8, 15, 23, 0))
                .updateTime(LocalDateTime.of(2026, 8, 15, 23, 30))
                .isDelete(0)
                .build();
        when(cursorMapper.selectOneByQuery(any()))
                .thenReturn(null, null, secondCursor, secondCursor);
        when(chatHistoryService.listMessagesAfterCursor(
                eq(APP_ID), eq(0L), anyInt())).thenReturn(List.of(
                消息(111L, "user", firstUser),
                消息(112L, "ai", "第一轮闭合"),
                消息(121L, "user", secondUser),
                消息(122L, "ai", "第二轮闭合")));
        when(chatHistoryService.listMessagesAfterCursor(
                eq(APP_ID), eq(112L), anyInt())).thenReturn(List.of(
                消息(121L, "user", secondUser),
                消息(122L, "ai", "第二轮闭合")));
        when(model.chat(any(String.class))).thenReturn("{\"operations\":[]}");
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);

        service.extractNow(USER_ID, APP_ID);
        service.extractNow(USER_ID, APP_ID);

        verify(model, times(2)).chat(prompts.capture());
        assertTrue(prompts.getAllValues().get(0).contains(firstUser));
        assertFalse(prompts.getAllValues().get(0).contains(secondUser));
        assertTrue(prompts.getAllValues().get(1).contains(secondUser));
        ArgumentCaptor<AppMemoryExtractCursor> inserted =
                ArgumentCaptor.forClass(AppMemoryExtractCursor.class);
        verify(cursorMapper).insert(inserted.capture());
        assertEquals(112L, inserted.getValue().getLastExtractedId());
        ArgumentCaptor<AppMemoryExtractCursor> updated =
                ArgumentCaptor.forClass(AppMemoryExtractCursor.class);
        verify(cursorMapper).update(updated.capture(), eq(false));
        assertEquals(122L, updated.getValue().getLastExtractedId());
    }

    @Test
    @DisplayName("单个完整回合超限时跳过并推进其 AI 边界")
    void 单回合超限跳过且不字符截断() {
        String oversizedUser = "超长用户证据".repeat(2_000);
        int oversizedTokens = tokenEstimator.estimateText(
                UserPreferencePromptBuilder.build(
                        "", 用户证据(131L, oversizedUser),
                        List.of(131L)));
        properties.setAsyncCompressionThreshold(oversizedTokens - 1);
        提供历史(完整回合(131L, 132L,
                oversizedUser, "闭合回复"));

        service.extractNow(USER_ID, APP_ID);

        verify(model, never()).chat(any(String.class));
        断言游标新增到(132L);
    }

    @Test
    @DisplayName("Mapper 影响行数异常时批次不得成功推进游标")
    void 偏好写入影响行数异常时只记录失败游标() {
        提供历史(完整回合(161L, 162L,
                "以后都使用中文", "已收到"));
        when(memoryMapper.selectOneByQuery(any())).thenReturn(null);
        when(memoryMapper.insert(any())).thenReturn(0);
        when(model.chat(any(String.class))).thenReturn("""
                {"operations": [{"action": "ADD", "category": "语言偏好", "attribute": "页面默认语言", "content": "简体中文", "scope": "USER", "evidenceType": "EXPLICIT", "turnIds": ["161"]}]}
                """);

        service.extractNow(USER_ID, APP_ID);

        ArgumentCaptor<AppMemoryExtractCursor> cursor =
                ArgumentCaptor.forClass(AppMemoryExtractCursor.class);
        verify(cursorMapper).insert(cursor.capture());
        assertEquals(0L, cursor.getValue().getLastExtractedId());
        assertEquals(1, cursor.getValue().getFailCount());
    }

    @Test
    @DisplayName("读取已有偏好失败时不得调用模型或推进游标")
    void 已有偏好查询失败保留处理游标() {
        提供历史(完整回合(181L, 182L,
                "以后都使用中文", "已收到"));
        when(memoryMapper.selectListByQuery(any()))
                .thenThrow(new IllegalStateException("数据库不可用"));
        when(model.chat(any(String.class))).thenReturn("{\"operations\":[]}");

        service.extractNow(USER_ID, APP_ID);

        verify(model, never()).chat(any(String.class));
        verify(chatHistoryService, never()).listMessagesAfterCursor(
                anyLong(), anyLong(), anyInt());
        断言失败游标保留在(0L);
    }

    @Test
    @DisplayName("召回只查询激活偏好并按证据类型和更新时间排序")
    void 召回查询限定Active并显式优先() {
        提供应用归属();
        when(memoryMapper.selectListByQuery(any())).thenReturn(List.of(
                偏好("语言偏好", "简体中文",
                        "EXPLICIT", "ACTIVE", 1, 201L)));

        String recalled = service.recallByApp(APP_ID);

        assertEquals("- 语言偏好:页面默认语言：简体中文", recalled);
        DistributionSummary recallTokens = summary(
                metricsRegistry, "memory_l2_recall_tokens");
        assertEquals(1L, recallTokens.count());
        assertEquals(new UserPreferenceMessageFragmentBuilder(
                        tokenEstimator, properties).estimate(recalled),
                recallTokens.totalAmount());
        ArgumentCaptor<QueryWrapper> query =
                ArgumentCaptor.forClass(QueryWrapper.class);
        verify(memoryMapper).selectListByQuery(query.capture());
        assertEquals(Map.of(
                        "userId", USER_ID,
                        "type", "USER_PREFERENCE",
                        "status", "ACTIVE", "isDelete", 0),
                读取等值条件(query.getValue()));
        assertEquals(List.of(
                        "evidenceType ASC",
                        "updateTime DESC", "id DESC"),
                读取排序(query.getValue()));
    }

    @Test
    @DisplayName("候选指标故障不改变偏好落库与游标推进")
    void 候选指标故障不改变抽取结果() {
        ThrowingMeterRegistry registry = new ThrowingMeterRegistry(
                ThrowingMeterRegistry.FailurePoint.COUNTER_INCREMENT);
        try {
            提供历史(完整回合(301L, 302L,
                    "以后所有应用都使用简体中文", "已收到"));
            when(memoryMapper.selectOneByQuery(any())).thenReturn(null);
            when(model.chat(any(String.class))).thenReturn("""
                {"operations": [{"action": "ADD", "category": "语言偏好", "attribute": "页面默认语言", "content": "简体中文", "scope": "USER", "evidenceType": "EXPLICIT", "turnIds": ["301"]}]}
                """);
            UserMemoryServiceImpl observed = newService(
                    new MemoryCompressionMetricsCollector(registry));

            observed.extractNow(USER_ID, APP_ID);

            assertEquals("ACTIVE", 捕获新增偏好().getStatus());
            断言游标新增到(302L);
            assertTrue(registry.failureTriggered());
        } finally {
            registry.close();
        }
    }

    @Test
    @DisplayName("召回指标故障不改变最终注入文本")
    void 召回指标故障不改变召回结果() {
        ThrowingMeterRegistry registry = new ThrowingMeterRegistry(
                ThrowingMeterRegistry.FailurePoint.SUMMARY_RECORD);
        try {
            提供应用归属();
            when(memoryMapper.selectListByQuery(any())).thenReturn(List.of(
                    偏好("语言偏好", "简体中文",
                            "EXPLICIT", "ACTIVE", 1, 311L)));
            UserMemoryServiceImpl observed = newService(
                    new MemoryCompressionMetricsCollector(registry));

            String recalled = observed.recallByApp(APP_ID);

            assertEquals("- 语言偏好:页面默认语言：简体中文", recalled);
            assertTrue(registry.failureTriggered());
        } finally {
            registry.close();
        }
    }

    @Test
    @DisplayName("召回结果按完整条目拼接且严格不超过 512 Token")
    void 召回结果严格受512Token限制() {
        提供应用归属();
        when(memoryMapper.selectListByQuery(any())).thenReturn(List.of(
                偏好("语言偏好", "简体中文",
                        "EXPLICIT", "ACTIVE", 1, 211L),
                偏好("视觉风格", "深色、极简",
                        "EXPLICIT", "ACTIVE", 1, 212L),
                偏好("技术栈倾向", "Vue 3、TypeScript、Tailwind CSS",
                        "IMPLICIT", "ACTIVE", 2, 213L)));

        String recalled = service.recallByApp(APP_ID);

        assertTrue(new UserPreferenceMessageFragmentBuilder(
                tokenEstimator, properties).estimate(recalled) <= 512);
        assertTrue(recalled.contains("语言偏好"));
        assertTrue(recalled.contains("视觉风格"));
        assertTrue(recalled.contains("技术栈倾向"));
    }

    @Test
    @DisplayName("单条偏好自身超过 512 Token 时整条跳过且不截断")
    void 单条超限时跳过并继续召回后续完整条目() {
        提供应用归属();
        String sensitiveContent = "敏感偏好正文-" + "超".repeat(600);
        AppMemory oversizedPreference = 偏好(
                "敏感偏好名称", sensitiveContent,
                "EXPLICIT", "ACTIVE", 1, 221L);
        oversizedPreference.setId(221L);
        AppMemory validPreference = 偏好(
                "语言偏好", "简体中文",
                "EXPLICIT", "ACTIVE", 1, 222L);
        validPreference.setId(222L);
        when(memoryMapper.selectListByQuery(any())).thenReturn(List.of(
                oversizedPreference, validPreference));
        String oversizedLine = "- 敏感偏好名称:" + oversizedPreference.getAttributeName() + "：" + sensitiveContent;
        int estimatedTokens = new UserPreferenceMessageFragmentBuilder(
                tokenEstimator, properties).estimate(oversizedLine);
        Logger logger = (Logger) LoggerFactory.getLogger(
                UserMemoryServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            String recalled = service.recallByApp(APP_ID);

            assertEquals("- 语言偏好:页面默认语言：简体中文", recalled);
            assertFalse(recalled.contains("敏感偏好正文"));
            List<String> logs = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertEquals(List.of(
                    "跳过超过 L2 召回 Token 上限的单条偏好 "
                            + "userId=7 memoryId=221 estimatedTokens="
                            + estimatedTokens), logs);
            assertFalse(logs.getFirst().contains("敏感偏好名称"));
            assertFalse(logs.getFirst().contains("敏感偏好正文"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("旧版偏好缓存不再命中")
    void 旧缓存键不读取并从数据库重建新版缓存() {
        提供应用归属();
        when(valueOperations.get("mem:pref:" + USER_ID))
                .thenReturn("旧缓存内容");
        when(memoryMapper.selectListByQuery(any())).thenReturn(List.of(
                偏好("语言偏好", "简体中文",
                        "EXPLICIT", "ACTIVE", 1, 231L)));

        String recalled = service.recallByApp(APP_ID);

        assertEquals("- 语言偏好:页面默认语言：简体中文", recalled);
        verify(valueOperations, never()).get("mem:pref:" + USER_ID);
        verify(valueOperations).get("mem:pref:v3:" + USER_ID);
    }

    @Test
    @DisplayName("符合旧预算的缓存超过 512 Token 时删除并从数据库重建")
    void 新缓存超限后失效并回源重建() {
        提供应用归属();
        String newCacheKey = "mem:pref:v3:" + USER_ID;
        String oversizedCache = "- 语言偏好:页面默认语言：" + "超".repeat(600);
        int cachedTokens = new UserPreferenceMessageFragmentBuilder(
                tokenEstimator, properties).estimate(oversizedCache);
        assertTrue(cachedTokens > 512 && cachedTokens <= 1_024);
        when(valueOperations.get(newCacheKey)).thenReturn(oversizedCache);
        when(memoryMapper.selectListByQuery(any())).thenReturn(List.of(
                偏好("语言偏好", "简体中文",
                        "EXPLICIT", "ACTIVE", 1, 241L)));

        String recalled = service.recallByApp(APP_ID);

        assertEquals("- 语言偏好:页面默认语言：简体中文", recalled);
        verify(redisTemplate).delete(newCacheKey);
        verify(memoryMapper).selectListByQuery(any());
        verify(valueOperations).set(eq(newCacheKey), eq(recalled), any());
    }

    @Test
    @DisplayName("偏好缓存回源回填期间必须持有应用写许可")
    void 缓存回源期间删除门不得越过并导致缓存复活() throws Exception {
        提供应用归属();
        when(valueOperations.get("mem:pref:v3:" + USER_ID))
                .thenReturn(null);
        CountDownLatch databaseReadEntered = new CountDownLatch(1);
        CountDownLatch allowDatabaseRead = new CountDownLatch(1);
        when(memoryMapper.selectListByQuery(any())).thenAnswer(invocation -> {
            databaseReadEntered.countDown();
            assertTrue(allowDatabaseRead.await(1, TimeUnit.SECONDS));
            return List.of(偏好("语言偏好", "简体中文",
                    "EXPLICIT", "ACTIVE", 1, 251L));
        });

        try (ExecutorService threads = Executors
                .newVirtualThreadPerTaskExecutor()) {
            Future<String> recall = threads.submit(
                    () -> service.recallByApp(APP_ID));
            assertTrue(databaseReadEntered.await(1, TimeUnit.SECONDS));

            AppDataLifecycleFence.DeletePermit deletion =
                    lifecycleFence.beginDelete(APP_ID, Duration.ZERO);
            if (deletion != null) {
                deletion.abortAndReopen();
            }
            assertNull(deletion,
                    "read-through 完成前删除门不得越过并执行缓存失效");

            allowDatabaseRead.countDown();
            assertEquals("- 语言偏好:页面默认语言：简体中文",
                    recall.get(1, TimeUnit.SECONDS));
        }
        verify(valueOperations).set(eq("mem:pref:v3:" + USER_ID),
                eq("- 语言偏好:页面默认语言：简体中文"), any());
    }

    @Test
    @DisplayName("同用户其他应用回填旧快照后删除失效必须最终清空缓存")
    void 同用户跨应用删除失效不得被迟到回填覆盖() throws Exception {
        提供应用归属(APP_B);
        String legacyCacheKey = "mem:pref:" + USER_ID;
        String cacheKey = "mem:pref:v3:" + USER_ID;
        String staleRecall = "- 语言偏好:页面默认语言：简体中文";
        when(valueOperations.get(cacheKey)).thenReturn(null);
        CountDownLatch snapshotRead = new CountDownLatch(1);
        CountDownLatch allowSnapshotReturn = new CountDownLatch(1);
        when(memoryMapper.selectListByQuery(any())).thenAnswer(invocation -> {
            List<AppMemory> staleSnapshot = List.of(
                    偏好("语言偏好", "简体中文",
                            "EXPLICIT", "ACTIVE", 1, 261L));
            snapshotRead.countDown();
            assertTrue(allowSnapshotReturn.await(3, TimeUnit.SECONDS));
            return staleSnapshot;
        });
        AtomicReference<Thread> invalidationThread = new AtomicReference<>();
        CountDownLatch invalidationStarted = new CountDownLatch(1);

        try (ExecutorService threads = Executors
                .newVirtualThreadPerTaskExecutor()) {
            Future<String> recall = threads.submit(
                    () -> service.recallByApp(APP_B));
            assertTrue(snapshotRead.await(3, TimeUnit.SECONDS));

            Future<MemoryCacheInvalidationResult> invalidation =
                    threads.submit(() -> {
                        invalidationThread.set(Thread.currentThread());
                        invalidationStarted.countDown();
                        return service.invalidateCaches(APP_ID, USER_ID);
                    });
            assertTrue(invalidationStarted.await(3, TimeUnit.SECONDS));
            等待任务完成或线程进入锁等待(invalidation, invalidationThread);

            allowSnapshotReturn.countDown();
            assertEquals(staleRecall, recall.get(3, TimeUnit.SECONDS));
            assertTrue(invalidation.get(3, TimeUnit.SECONDS)
                    .failures().isEmpty());
        }

        InOrder cacheOrder = inOrder(valueOperations, redisTemplate);
        cacheOrder.verify(valueOperations).set(
                eq(cacheKey), eq(staleRecall), any());
        cacheOrder.verify(redisTemplate).delete(legacyCacheKey);
        cacheOrder.verify(redisTemplate).delete(cacheKey);
    }

    @Test
    @DisplayName("同用户其他应用回填旧快照后偏好更新必须最终清空缓存")
    void 同用户跨应用偏好更新失效不得被迟到回填覆盖() throws Exception {
        提供应用归属(APP_B);
        提供历史(完整回合(271L, 272L,
                "以后都使用深色界面", "已调整"));
        when(model.chat(any(String.class))).thenReturn("""
                {"operations": [{"action": "ADD", "category": "视觉风格", "attribute": "视觉样式", "content": "深色", "scope": "USER", "evidenceType": "EXPLICIT", "turnIds": ["271"]}]}
                """);
        String legacyCacheKey = "mem:pref:" + USER_ID;
        String cacheKey = "mem:pref:v3:" + USER_ID;
        String staleRecall = "- 语言偏好:页面默认语言：简体中文";
        when(valueOperations.get(cacheKey)).thenReturn(null);
        CountDownLatch snapshotRead = new CountDownLatch(1);
        CountDownLatch allowSnapshotReturn = new CountDownLatch(1);
        AtomicReference<Thread> recallThread = new AtomicReference<>();
        when(memoryMapper.selectListByQuery(any())).thenAnswer(invocation -> {
            if (Thread.currentThread() != recallThread.get()) {
                return List.of();
            }
            List<AppMemory> staleSnapshot = List.of(
                    偏好("语言偏好", "简体中文",
                            "EXPLICIT", "ACTIVE", 1, 273L));
            snapshotRead.countDown();
            assertTrue(allowSnapshotReturn.await(3, TimeUnit.SECONDS));
            return staleSnapshot;
        });
        AtomicReference<Thread> extractionThread = new AtomicReference<>();
        CountDownLatch extractionStarted = new CountDownLatch(1);

        try (ExecutorService threads = Executors
                .newVirtualThreadPerTaskExecutor()) {
            Future<String> recall = threads.submit(() -> {
                recallThread.set(Thread.currentThread());
                return service.recallByApp(APP_B);
            });
            assertTrue(snapshotRead.await(3, TimeUnit.SECONDS));

            Future<?> extraction = threads.submit(() -> {
                extractionThread.set(Thread.currentThread());
                extractionStarted.countDown();
                service.extractNow(USER_ID, APP_ID);
            });
            assertTrue(extractionStarted.await(3, TimeUnit.SECONDS));
            等待任务完成或线程进入锁等待(extraction, extractionThread);

            allowSnapshotReturn.countDown();
            assertEquals(staleRecall, recall.get(3, TimeUnit.SECONDS));
            extraction.get(3, TimeUnit.SECONDS);
        }

        verify(memoryMapper).insert(any());
        InOrder cacheOrder = inOrder(valueOperations, redisTemplate);
        cacheOrder.verify(valueOperations).set(
                eq(cacheKey), eq(staleRecall), any());
        cacheOrder.verify(redisTemplate).delete(legacyCacheKey);
        cacheOrder.verify(redisTemplate).delete(cacheKey);
    }

    @Test
    @DisplayName("删除应用时同时清理新旧偏好缓存")
    void 删除失效同时清理新旧缓存键() {
        MemoryCacheInvalidationResult result =
                service.invalidateCaches(APP_ID, USER_ID);

        assertTrue(result.failures().isEmpty());
        verify(redisTemplate).delete("mem:pref:" + USER_ID);
        verify(redisTemplate).delete("mem:pref:v3:" + USER_ID);
    }

    @Test
    @DisplayName("tombstone 后 L2 召回不得重建归属或 Redis 缓存")
    void 删除接管后迟到召回不复活本地映射或缓存() {
        提供应用归属();
        String cacheKey = "mem:pref:v3:" + USER_ID;
        when(valueOperations.get(cacheKey))
                .thenReturn("- 语言偏好:页面默认语言：简体中文");
        assertEquals("- 语言偏好:页面默认语言：简体中文",
                service.recallByApp(APP_ID));

        AppDataLifecycleFence.DeletePermit deletion =
                lifecycleFence.beginDelete(APP_ID, Duration.ZERO);
        assertTrue(deletion != null);
        deletion.commitTombstone();
        service.invalidateCaches(APP_ID, USER_ID);

        assertEquals("", service.recallByApp(APP_ID));
        verify(appMapper, times(1)).selectOneById(APP_ID);
        verify(valueOperations, times(1)).get(cacheKey);
        verify(valueOperations, never()).set(any(), any(), any());
        verify(memoryMapper, never()).selectListByQuery(any());
    }

    private AppMemory 捕获新增偏好() {
        ArgumentCaptor<AppMemory> memory =
                ArgumentCaptor.forClass(AppMemory.class);
        verify(memoryMapper).insert(memory.capture());
        return memory.getValue();
    }

    private UserMemoryServiceImpl newService(
            MemoryCompressionMetricsCollector collector) {
        return new UserMemoryServiceImpl(
                chatHistoryService, MemoryTestSupport.repository(memoryMapper), cursorMapper, appMapper,
                model, mock(ExecutorService.class),
                mock(TaskScheduler.class), redisTemplate,
                lifecycleFence, tokenEstimator, properties,
                transactions, collector,
                Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"),
                        ZoneOffset.UTC));
    }

    private Counter counter(
            io.micrometer.core.instrument.MeterRegistry registry,
            String name,
            String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        assertNotNull(counter, () -> "缺少 Counter：" + name);
        return counter;
    }

    private DistributionSummary summary(
            io.micrometer.core.instrument.MeterRegistry registry,
            String name,
            String... tags) {
        DistributionSummary summary = registry.find(name)
                .tags(tags)
                .summary();
        assertNotNull(summary, () -> "缺少 DistributionSummary：" + name);
        return summary;
    }

    private AppMemory 捕获更新偏好() {
        ArgumentCaptor<AppMemory> memory =
                ArgumentCaptor.forClass(AppMemory.class);
        verify(memoryMapper).update(memory.capture());
        return memory.getValue();
    }

    private void 断言游标新增到(long expected) {
        ArgumentCaptor<AppMemoryExtractCursor> cursor =
                ArgumentCaptor.forClass(AppMemoryExtractCursor.class);
        verify(cursorMapper).insert(cursor.capture());
        assertEquals(expected, cursor.getValue().getLastExtractedId());
        assertEquals(0, cursor.getValue().getFailCount());
        assertNull(cursor.getValue().getNextRetryTime());
    }

    private void 断言失败游标保留在(long expected) {
        ArgumentCaptor<AppMemoryExtractCursor> cursor =
                ArgumentCaptor.forClass(AppMemoryExtractCursor.class);
        verify(cursorMapper).insert(cursor.capture());
        assertEquals(expected, cursor.getValue().getLastExtractedId());
        assertEquals(1, cursor.getValue().getFailCount());
    }

    private void 提供历史(List<ChatHistory> history) {
        when(chatHistoryService.listMessagesAfterCursor(
                eq(APP_ID), anyLong(), anyInt())).thenReturn(history);
    }

    private List<ChatHistory> 完整回合(
            long userId, long aiId, String userText, String aiText) {
        return List.of(
                消息(userId, "user", userText),
                消息(aiId, "ai", aiText));
    }

    private ChatHistory 消息(long id, String type, String text) {
        ChatHistory.ChatHistoryBuilder builder = ChatHistory.builder()
                .id(id).appId(APP_ID).userId(USER_ID)
                .messageType(type).message(text);
        if ("ai".equals(type)) {
            builder.memoryMessage(text)
                    .memoryOutcome(ChatMemoryOutcome.LEGACY_IMPORTED);
        }
        return builder.build();
    }

    private String 用户证据(long turnId, String userText) {
        return "turnId=" + turnId + "\n用户:" + userText;
    }

    private void 提供应用归属() {
        提供应用归属(APP_ID);
    }

    private void 提供应用归属(long appId) {
        when(appMapper.selectOneById(appId)).thenReturn(
                App.builder().id(appId).userId(USER_ID).build());
    }

    private void 等待任务完成或线程进入锁等待(
            Future<?> task, AtomicReference<Thread> threadReference) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!task.isDone()) {
            Thread thread = threadReference.get();
            if (thread != null && isLockWaiting(thread.getState())) {
                return;
            }
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("任务既未完成，也未进入锁等待状态");
            }
            Thread.onSpinWait();
        }
    }

    private boolean isLockWaiting(Thread.State state) {
        return state == Thread.State.WAITING
                || state == Thread.State.BLOCKED;
    }

    private Map<String, Object> 读取等值条件(QueryWrapper query) {
        try {
            java.lang.reflect.Field whereField = query.getClass()
                    .getSuperclass().getDeclaredField("whereQueryCondition");
            whereField.setAccessible(true);
            Object condition = whereField.get(query);
            Map<String, Object> conditions = new LinkedHashMap<>();
            while (condition != null) {
                java.lang.reflect.Field columnField = condition.getClass()
                        .getDeclaredField("column");
                java.lang.reflect.Field logicField = condition.getClass()
                        .getDeclaredField("logic");
                java.lang.reflect.Field valueField = condition.getClass()
                        .getDeclaredField("value");
                java.lang.reflect.Field nextField = condition.getClass()
                        .getDeclaredField("next");
                columnField.setAccessible(true);
                logicField.setAccessible(true);
                valueField.setAccessible(true);
                nextField.setAccessible(true);
                String logic = ((String) logicField.get(condition)).trim();
                if (!"=".equals(logic)) {
                    condition = nextField.get(condition);
                    continue;
                }
                String column = 读取原始列名(columnField.get(condition));
                conditions.put(column, valueField.get(condition));
                condition = nextField.get(condition);
            }
            return conditions;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> 读取排序(QueryWrapper query) {
        try {
            java.lang.reflect.Field orderField = query.getClass()
                    .getSuperclass().getDeclaredField("orderBys");
            orderField.setAccessible(true);
            List<Object> orderBys = (List<Object>) orderField.get(query);
            List<String> orders = new ArrayList<>();
            for (Object orderBy : orderBys) {
                java.lang.reflect.Field columnField = orderBy.getClass()
                        .getDeclaredField("queryColumn");
                java.lang.reflect.Field typeField = orderBy.getClass()
                        .getDeclaredField("orderType");
                columnField.setAccessible(true);
                typeField.setAccessible(true);
                orders.add(读取原始列名(columnField.get(orderBy))
                        + " " + ((String) typeField.get(orderBy)).trim());
            }
            return orders;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private String 读取原始列名(Object queryColumn)
            throws ReflectiveOperationException {
        java.lang.reflect.Field contentField = queryColumn.getClass()
                .getDeclaredField("content");
        contentField.setAccessible(true);
        return (String) contentField.get(queryColumn);
    }

    private Long 读取限制(QueryWrapper query) {
        try {
            java.lang.reflect.Field limitField = query.getClass()
                    .getSuperclass().getDeclaredField("limitRows");
            limitField.setAccessible(true);
            return (Long) limitField.get(query);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private AppMemory 偏好(String name,
                         String content,
                         String evidenceType,
                         String status,
                         int evidenceCount,
                         long lastEvidenceTurnId) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 16, 8, 0);
        return AppMemory.builder()
                .id(lastEvidenceTurnId).userId(USER_ID).appId(APP_ID)
                .type("USER_PREFERENCE").name(name).content(content)
                .memoryKey("test." + lastEvidenceTurnId).attributeName(switch (name) {
                    case "语言偏好" -> "页面默认语言"; case "视觉风格" -> "视觉样式"; default -> "前端框架"; })
                .revision(1L).legacyEvidenceCount(evidenceCount).legacyEvidenceTurnId(lastEvidenceTurnId)
                .evidenceType(evidenceType).status(status)
                .evidenceCount(evidenceCount)
                .lastEvidenceTurnId(lastEvidenceTurnId)
                .createTime(now).updateTime(now).build();
    }

    private static final class RecordingTransactionOperations
            implements TransactionOperations {

        private int executionCount;
        private boolean active;
        private Runnable afterCallback = () -> { };
        private RuntimeException completionFailure;

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            executionCount++;
            active = true;
            try {
                T result = action.doInTransaction(
                        new SimpleTransactionStatus());
                afterCallback.run();
                if (completionFailure != null) {
                    throw completionFailure;
                }
                return result;
            } finally {
                active = false;
            }
        }

        private void afterCallback(Runnable action) {
            afterCallback = action;
        }

        private void failAfterCallback(RuntimeException failure) {
            completionFailure = failure;
        }

        private int executionCount() {
            return executionCount;
        }

        private boolean isActive() {
            return active;
        }
    }
}
