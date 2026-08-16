package com.lyw.appgeneration.service.impl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lyw.appgeneration.ai.memory.ChatTokenEstimator;
import com.lyw.appgeneration.ai.memory.ConservativeChatTokenEstimator;
import com.lyw.appgeneration.ai.memory.UserPreferencePromptBuilder;
import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.mapper.AppMapper;
import com.lyw.appgeneration.mapper.AppMemoryExtractCursorMapper;
import com.lyw.appgeneration.mapper.AppMemoryMapper;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.entity.AppMemory;
import com.lyw.appgeneration.model.entity.AppMemoryExtractCursor;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemoryCacheInvalidationResult;
import com.mybatisflex.core.query.QueryWrapper;
import dev.langchain4j.model.chat.ChatModel;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    private UserMemoryServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void 初始化依赖() {
        chatHistoryService = mock(ChatHistoryService.class);
        memoryMapper = mock(AppMemoryMapper.class);
        cursorMapper = mock(AppMemoryExtractCursorMapper.class);
        appMapper = mock(AppMapper.class);
        model = mock(ChatModel.class);
        ExecutorService worker = mock(ExecutorService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        properties = new MemoryTokenProperties();
        properties.setEstimationSafetyFactor(1D);
        tokenEstimator = new ConservativeChatTokenEstimator(properties);
        lifecycleFence = new AppDataLifecycleFence();
        transactions = new RecordingTransactionOperations();
        service = new UserMemoryServiceImpl(
                chatHistoryService, memoryMapper, cursorMapper, appMapper,
                model, worker, scheduler, redisTemplate,
                lifecycleFence, tokenEstimator, properties,
                transactions,
                Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"),
                        ZoneOffset.UTC));

        when(cursorMapper.selectOneByQuery(any())).thenReturn(null);
        when(memoryMapper.selectListByQuery(any())).thenReturn(List.of());
        when(memoryMapper.insert(any())).thenReturn(1);
        when(memoryMapper.update(any())).thenReturn(1);
        when(cursorMapper.insert(any())).thenReturn(1);
        when(cursorMapper.update(any())).thenReturn(1);
        when(cursorMapper.update(
                any(AppMemoryExtractCursor.class), eq(false))).thenReturn(1);
    }

    @Test
    @DisplayName("显式偏好由一个完整回合直接激活")
    void 显式一次证据直接激活() {
        提供历史(完整回合(11L, 12L,
                "以后所有应用都使用简体中文", "已收到"));
        when(memoryMapper.selectOneByQuery(any())).thenReturn(null);
        when(model.chat(any(String.class))).thenReturn("""
                [{"name":"语言偏好","content":"简体中文",
                "evidenceType":"EXPLICIT","turnIds":[11]}]
                """);

        service.extractNow(USER_ID, APP_ID);

        AppMemory inserted = 捕获新增偏好();
        assertEquals("ACTIVE", inserted.getStatus());
        assertEquals("EXPLICIT", inserted.getEvidenceType());
        assertEquals(1, inserted.getEvidenceCount());
        assertEquals(11L, inserted.getLastEvidenceTurnId());
        断言游标新增到(12L);
    }

    @Test
    @DisplayName("生产构造写入时间沿用 JVM 默认时区")
    void 生产构造不会把本地数据库时间写成Utc时间() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            UserMemoryServiceImpl defaultClockService =
                    new UserMemoryServiceImpl(
                            chatHistoryService, memoryMapper, cursorMapper,
                            appMapper, model, mock(ExecutorService.class),
                            mock(TaskScheduler.class), redisTemplate,
                            lifecycleFence, tokenEstimator, properties,
                            transactions);
            提供历史(完整回合(13L, 14L,
                    "以后所有应用都使用简体中文", "已收到"));
            when(model.chat(any(String.class))).thenReturn("""
                    [{"name":"语言偏好","content":"简体中文",
                    "evidenceType":"EXPLICIT","turnIds":[13]}]
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
    @DisplayName("隐式偏好只有一个不同完整回合时保持候选")
    void 隐式一次证据保持候选() {
        提供历史(完整回合(21L, 22L,
                "这个页面看起来偏冷色", "已生成"));
        when(memoryMapper.selectOneByQuery(any())).thenReturn(null);
        when(model.chat(any(String.class))).thenReturn("""
                [{"name":"视觉风格","content":"偏好冷色界面",
                "evidenceType":"IMPLICIT","turnIds":[21]}]
                """);

        service.extractNow(USER_ID, APP_ID);

        AppMemory inserted = 捕获新增偏好();
        assertEquals("CANDIDATE", inserted.getStatus());
        assertEquals("IMPLICIT", inserted.getEvidenceType());
        assertEquals(1, inserted.getEvidenceCount());
    }

    @Test
    @DisplayName("同一 turnId 重试时不得重复累计证据")
    void 相同回合重试不重复累计() {
        提供历史(完整回合(31L, 32L,
                "这个页面看起来偏冷色", "已生成"));
        when(memoryMapper.selectOneByQuery(any())).thenReturn(
                偏好("视觉风格", "偏好冷色界面",
                        "IMPLICIT", "CANDIDATE", 1, 31L));
        when(model.chat(any(String.class))).thenReturn("""
                [{"name":"视觉风格","content":"偏好冷色界面",
                "evidenceType":"IMPLICIT","turnIds":[31,31]}]
                """);

        service.extractNow(USER_ID, APP_ID);

        verify(memoryMapper, never()).update(any());
        断言游标新增到(32L);
    }

    @Test
    @DisplayName("第二个不同隐式回合把候选升级为激活")
    void 第二个不同隐式回合完成激活() {
        提供历史(完整回合(41L, 42L,
                "还是更喜欢冷色界面", "已调整"));
        when(memoryMapper.selectOneByQuery(any())).thenReturn(
                偏好("视觉风格", "偏好冷色界面",
                        "IMPLICIT", "CANDIDATE", 1, 31L));
        when(model.chat(any(String.class))).thenReturn("""
                [{"name":"视觉风格","content":"偏好冷色界面",
                "evidenceType":"IMPLICIT","turnIds":[41]}]
                """);

        service.extractNow(USER_ID, APP_ID);

        AppMemory updated = 捕获更新偏好();
        assertEquals("ACTIVE", updated.getStatus());
        assertEquals(2, updated.getEvidenceCount());
        assertEquals(41L, updated.getLastEvidenceTurnId());
    }

    @Test
    @DisplayName("跨 app 后处理的较小 turnId 仍属于新的隐式证据")
    void 跨应用乱序TurnId仍累计不同证据() {
        long appB = 200L;
        when(chatHistoryService.listMessagesAfterCursor(
                eq(APP_ID), anyLong(), anyInt()))
                .thenReturn(完整回合(2001L, 2002L,
                        "应用甲偏好冷色", "已调整"));
        when(chatHistoryService.listMessagesAfterCursor(
                eq(appB), anyLong(), anyInt()))
                .thenReturn(List.of(
                        ChatHistory.builder().id(1801L).appId(appB)
                                .userId(USER_ID).messageType("user")
                                .message("应用乙仍偏好冷色").build(),
                        ChatHistory.builder().id(1802L).appId(appB)
                                .userId(USER_ID).messageType("ai")
                                .message("已调整").build()));
        AtomicReference<AppMemory> stored = new AtomicReference<>();
        when(memoryMapper.selectOneByQuery(any()))
                .thenAnswer(invocation -> stored.get());
        when(memoryMapper.insert(any())).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        });
        when(memoryMapper.update(any())).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        });
        when(model.chat(any(String.class))).thenReturn(
                """
                [{"name":"视觉风格","content":"偏好冷色界面",
                "evidenceType":"IMPLICIT","turnIds":[2001]}]
                """,
                """
                [{"name":"视觉风格","content":"偏好冷色界面",
                "evidenceType":"IMPLICIT","turnIds":[1801]}]
                """);

        service.extractNow(USER_ID, APP_ID);
        service.extractNow(USER_ID, appB);

        assertEquals(2, stored.get().getEvidenceCount());
        assertEquals("ACTIVE", stored.get().getStatus());
        assertEquals(2001L, stored.get().getLastEvidenceTurnId());
    }

    @Test
    @DisplayName("偏好 upsert 与游标推进必须处于同一事务边界")
    void 偏好与游标在同一事务内提交() {
        提供历史(完整回合(45L, 46L,
                "以后都使用中文", "已收到"));
        when(memoryMapper.selectOneByQuery(any())).thenReturn(null);
        when(model.chat(any(String.class))).thenReturn("""
                [{"name":"语言偏好","content":"简体中文",
                "evidenceType":"EXPLICIT","turnIds":[45]}]
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
    @DisplayName("非白名单和字段非法候选全部丢弃但合法数组可推进")
    void 非法候选被丢弃且批次仍推进() {
        提供历史(完整回合(51L, 52L,
                "以后都用中文", "已收到"));
        when(model.chat(any(String.class))).thenReturn("""
                [
                  {"name":"语言偏好","content":"中文","evidenceType":"EXPLICIT","turnIds":[999]},
                  {"name":"视觉风格","content":"极简","evidenceType":"UNKNOWN","turnIds":[51]},
                  {"name":"交互习惯","content":"键盘优先","evidenceType":"IMPLICIT","turnIds":[]},
                  {"name":"其他","content":"","evidenceType":"EXPLICIT","turnIds":[51]}
                ]
                """);

        service.extractNow(USER_ID, APP_ID);

        verify(memoryMapper, never()).insert(any());
        verify(memoryMapper, never()).update(any());
        断言游标新增到(52L);
    }

    @Test
    @DisplayName("内容实质变化后证据重新累计")
    void 内容变化重置证据状态() {
        提供历史(完整回合(61L, 62L,
                "最近更喜欢深色页面", "已调整"));
        when(memoryMapper.selectOneByQuery(any())).thenReturn(
                偏好("视觉风格", "偏好浅色界面",
                        "EXPLICIT", "ACTIVE", 5, 55L));
        when(model.chat(any(String.class))).thenReturn("""
                [{"name":"视觉风格","content":"偏好深色界面",
                "evidenceType":"IMPLICIT","turnIds":[61]}]
                """);

        service.extractNow(USER_ID, APP_ID);

        AppMemory updated = 捕获更新偏好();
        assertEquals("偏好深色界面", updated.getContent());
        assertEquals("IMPLICIT", updated.getEvidenceType());
        assertEquals("CANDIDATE", updated.getStatus());
        assertEquals(1, updated.getEvidenceCount());
        assertEquals(61L, updated.getLastEvidenceTurnId());
    }

    @Test
    @DisplayName("同内容显式激活偏好不得被后续隐式输出降级")
    void 同内容显式激活状态不会降级() {
        提供历史(完整回合(71L, 72L,
                "这个风格也可以", "已生成"));
        when(memoryMapper.selectOneByQuery(any())).thenReturn(
                偏好("视觉风格", "偏好深色界面",
                        "EXPLICIT", "ACTIVE", 1, 61L));
        when(model.chat(any(String.class))).thenReturn("""
                [{"name":"视觉风格","content":"偏好深色界面",
                "evidenceType":"IMPLICIT","turnIds":[71]}]
                """);

        service.extractNow(USER_ID, APP_ID);

        AppMemory updated = 捕获更新偏好();
        assertEquals("EXPLICIT", updated.getEvidenceType());
        assertEquals("ACTIVE", updated.getStatus());
        assertEquals(2, updated.getEvidenceCount());
    }

    @Test
    @DisplayName("内容比较只归一化空白")
    void 空白差异不会触发内容重置() {
        提供历史(完整回合(81L, 82L,
                "依旧偏好深色极简", "已生成"));
        when(memoryMapper.selectOneByQuery(any())).thenReturn(
                偏好("视觉风格", "偏好  深色极简",
                        "IMPLICIT", "CANDIDATE", 1, 71L));
        when(model.chat(any(String.class))).thenReturn("""
                [{"name":"视觉风格","content":"偏好 深色极简",
                "evidenceType":"IMPLICIT","turnIds":[81]}]
                """);

        service.extractNow(USER_ID, APP_ID);

        AppMemory updated = 捕获更新偏好();
        assertEquals(2, updated.getEvidenceCount());
        assertEquals("ACTIVE", updated.getStatus());
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
        when(model.chat(any(String.class))).thenReturn("[]");

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
        when(model.chat(any(String.class))).thenReturn("[]");

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
        when(model.chat(any(String.class))).thenReturn("[]");
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
                .thenReturn(null, secondCursor);
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
        when(model.chat(any(String.class))).thenReturn("[]");
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
    @DisplayName("偏好 upsert 成功而游标失败后重试多 turnId 不重复累计")
    void 多回合批次游标失败重试不重复累计证据() {
        List<ChatHistory> history = new ArrayList<>();
        history.addAll(完整回合(141L, 142L,
                "第一次倾向冷色", "已完成"));
        history.addAll(完整回合(151L, 152L,
                "第二次仍倾向冷色", "已完成"));
        提供历史(history);
        AtomicReference<AppMemory> stored = new AtomicReference<>();
        when(memoryMapper.selectOneByQuery(any()))
                .thenAnswer(invocation -> stored.get());
        AtomicInteger insertAttempts = new AtomicInteger();
        when(memoryMapper.insert(any())).thenAnswer(invocation -> {
            if (insertAttempts.incrementAndGet() == 2) {
                stored.set(invocation.getArgument(0));
            }
            return 1;
        });
        when(cursorMapper.insert(any())).thenReturn(0, 1, 1);
        when(model.chat(any(String.class))).thenReturn("""
                [{"name":"视觉风格","content":"偏好冷色界面",
                "evidenceType":"IMPLICIT","turnIds":[151,141]}]
                """);

        service.extractNow(USER_ID, APP_ID);
        service.extractNow(USER_ID, APP_ID);

        assertEquals(2, stored.get().getEvidenceCount());
        assertEquals(151L, stored.get().getLastEvidenceTurnId());
        verify(memoryMapper, times(2)).insert(any());
        verify(memoryMapper, never()).update(any());
        verify(cursorMapper, atLeastOnce()).insert(any());
    }

    @Test
    @DisplayName("Mapper 影响行数异常时批次不得成功推进游标")
    void 偏好写入影响行数异常时只记录失败游标() {
        提供历史(完整回合(161L, 162L,
                "以后都使用中文", "已收到"));
        when(memoryMapper.selectOneByQuery(any())).thenReturn(null);
        when(memoryMapper.insert(any())).thenReturn(0);
        when(model.chat(any(String.class))).thenReturn("""
                [{"name":"语言偏好","content":"简体中文",
                "evidenceType":"EXPLICIT","turnIds":[161]}]
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
        when(model.chat(any(String.class))).thenReturn("[]");

        service.extractNow(USER_ID, APP_ID);

        verify(model, never()).chat(any(String.class));
        verify(chatHistoryService, never()).listMessagesAfterCursor(
                anyLong(), anyLong(), anyInt());
        断言失败游标保留在(0L);
    }

    @Test
    @DisplayName("基础 Prompt 已超限时批次失败且不得误跳过正常回合")
    void 基础Prompt超限保留处理游标() {
        String oversizedExisting = "既有偏好".repeat(2_000);
        String renderedExisting = "- name=视觉风格; status=ACTIVE; "
                + "evidenceType=EXPLICIT; content=" + oversizedExisting;
        int basePromptTokens = tokenEstimator.estimateText(
                UserPreferencePromptBuilder.build(
                        renderedExisting, "", List.of()));
        properties.setAsyncCompressionThreshold(basePromptTokens - 1);
        提供历史(完整回合(191L, 192L,
                "以后都使用中文", "已收到"));
        when(memoryMapper.selectListByQuery(any())).thenReturn(List.of(
                偏好("视觉风格", oversizedExisting,
                        "EXPLICIT", "ACTIVE", 1, 171L)));

        service.extractNow(USER_ID, APP_ID);

        verify(model, never()).chat(any(String.class));
        断言失败游标保留在(0L);
    }

    @Test
    @DisplayName("合法 JSON 只剩同名冲突候选时按空列表正常推进")
    void 只剩同名冲突候选时正常推进游标() {
        List<ChatHistory> history = new ArrayList<>();
        history.addAll(完整回合(201L, 202L,
                "偏好深色界面", "已完成"));
        history.addAll(完整回合(211L, 212L,
                "偏好浅色界面", "已完成"));
        提供历史(history);
        when(model.chat(any(String.class))).thenReturn("""
                [
                  {"name":"视觉风格","content":"偏好深色界面",
                   "evidenceType":"EXPLICIT","turnIds":[201]},
                  {"name":"视觉风格","content":"偏好浅色界面",
                   "evidenceType":"EXPLICIT","turnIds":[211]}
                ]
                """);

        service.extractNow(USER_ID, APP_ID);

        verify(memoryMapper, never()).insert(any());
        verify(memoryMapper, never()).update(any());
        断言游标新增到(212L);
    }

    @Test
    @DisplayName("同名冲突只丢弃该名称且后续同名不得重新进入结果")
    void 同名冲突保留其他合法候选() {
        List<ChatHistory> history = new ArrayList<>();
        history.addAll(完整回合(201L, 202L,
                "偏好深色界面", "已完成"));
        history.addAll(完整回合(211L, 212L,
                "偏好浅色界面", "已完成"));
        history.addAll(完整回合(221L, 222L,
                "以后都使用中文", "已收到"));
        提供历史(history);
        when(model.chat(any(String.class))).thenReturn("""
                [
                  {"name":"视觉风格","content":"偏好深色界面",
                   "evidenceType":"EXPLICIT","turnIds":[201]},
                  {"name":"视觉风格","content":"偏好浅色界面",
                   "evidenceType":"EXPLICIT","turnIds":[211]},
                  {"name":"视觉风格","content":"偏好深色界面",
                   "evidenceType":"EXPLICIT","turnIds":[201]},
                  {"name":"语言偏好","content":"简体中文",
                   "evidenceType":"EXPLICIT","turnIds":[221]}
                ]
                """);

        service.extractNow(USER_ID, APP_ID);

        AppMemory inserted = 捕获新增偏好();
        assertEquals("语言偏好", inserted.getName());
        assertEquals("简体中文", inserted.getContent());
        verify(memoryMapper, never()).update(any());
        断言游标新增到(222L);
    }

    @Test
    @DisplayName("Prompt 中已有偏好明确携带状态证据类型和内容")
    void 已有偏好以证据状态契约注入Prompt() {
        提供历史(完整回合(171L, 172L,
                "这次仍然选择冷色界面", "已完成"));
        when(memoryMapper.selectListByQuery(any())).thenReturn(List.of(
                偏好("视觉风格", "偏好冷色界面",
                        "IMPLICIT", "CANDIDATE", 1, 161L)));
        when(model.chat(any(String.class))).thenReturn("[]");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);

        service.extractNow(USER_ID, APP_ID);

        verify(model).chat(prompt.capture());
        assertTrue(prompt.getValue().contains("status=CANDIDATE"));
        assertTrue(prompt.getValue().contains("evidenceType=IMPLICIT"));
        assertTrue(prompt.getValue().contains("content=偏好冷色界面"));
        ArgumentCaptor<QueryWrapper> existingQuery =
                ArgumentCaptor.forClass(QueryWrapper.class);
        verify(memoryMapper).selectListByQuery(existingQuery.capture());
        assertNull(读取限制(existingQuery.getValue()));
    }

    @Test
    @DisplayName("召回只查询激活偏好并按证据类型和更新时间排序")
    void 召回查询限定Active并显式优先() {
        提供应用归属();
        when(memoryMapper.selectListByQuery(any())).thenReturn(List.of(
                偏好("语言偏好", "简体中文",
                        "EXPLICIT", "ACTIVE", 1, 201L)));

        String recalled = service.recallByApp(APP_ID);

        assertEquals("- 语言偏好:简体中文", recalled);
        ArgumentCaptor<QueryWrapper> query =
                ArgumentCaptor.forClass(QueryWrapper.class);
        verify(memoryMapper).selectListByQuery(query.capture());
        assertEquals(Map.of(
                        "userId", USER_ID,
                        "type", "USER_PREFERENCE",
                        "status", "ACTIVE"),
                读取等值条件(query.getValue()));
        assertEquals(List.of(
                        "evidenceType ASC",
                        "updateTime DESC"),
                读取排序(query.getValue()));
    }

    @Test
    @DisplayName("召回结果按完整条目拼接且严格不超过一千零二十四 Token")
    void 召回结果严格受一千零二十四Token限制() {
        提供应用归属();
        when(memoryMapper.selectListByQuery(any())).thenReturn(List.of(
                偏好("第一项", "甲".repeat(400),
                        "EXPLICIT", "ACTIVE", 1, 211L),
                偏好("第二项", "乙".repeat(400),
                        "EXPLICIT", "ACTIVE", 1, 212L),
                偏好("第三项", "丙".repeat(400),
                        "IMPLICIT", "ACTIVE", 2, 213L)));

        String recalled = service.recallByApp(APP_ID);

        assertTrue(tokenEstimator.estimateText(recalled) <= 1_024);
        assertTrue(recalled.contains("第一项"));
        assertTrue(recalled.contains("第二项"));
        assertFalse(recalled.contains("第三项"));
    }

    @Test
    @DisplayName("单条偏好自身超过一千零二十四 Token 时整条跳过且不截断")
    void 单条超限时跳过并继续召回后续完整条目() {
        提供应用归属();
        String sensitiveContent = "敏感偏好正文-" + "超".repeat(1_100);
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
        String oversizedLine = "- 敏感偏好名称:" + sensitiveContent;
        int estimatedTokens = tokenEstimator.estimateText(oversizedLine);
        Logger logger = (Logger) LoggerFactory.getLogger(
                UserMemoryServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            String recalled = service.recallByApp(APP_ID);

            assertEquals("- 语言偏好:简体中文", recalled);
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

        assertEquals("- 语言偏好:简体中文", recalled);
        verify(valueOperations, never()).get("mem:pref:" + USER_ID);
        verify(valueOperations).get("mem:pref:v2:" + USER_ID);
    }

    @Test
    @DisplayName("新版缓存超限时删除并从数据库重建")
    void 新缓存超限后失效并回源重建() {
        提供应用归属();
        String newCacheKey = "mem:pref:v2:" + USER_ID;
        when(valueOperations.get(newCacheKey))
                .thenReturn("- 超长偏好:" + "超".repeat(1_100));
        when(memoryMapper.selectListByQuery(any())).thenReturn(List.of(
                偏好("语言偏好", "简体中文",
                        "EXPLICIT", "ACTIVE", 1, 241L)));

        String recalled = service.recallByApp(APP_ID);

        assertEquals("- 语言偏好:简体中文", recalled);
        verify(redisTemplate).delete(newCacheKey);
        verify(memoryMapper).selectListByQuery(any());
        verify(valueOperations).set(eq(newCacheKey), eq(recalled), any());
    }

    @Test
    @DisplayName("偏好缓存回源回填期间必须持有应用写许可")
    void 缓存回源期间删除门不得越过并导致缓存复活() throws Exception {
        提供应用归属();
        when(valueOperations.get("mem:pref:v2:" + USER_ID))
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
            assertEquals("- 语言偏好:简体中文",
                    recall.get(1, TimeUnit.SECONDS));
        }
        verify(valueOperations).set(eq("mem:pref:v2:" + USER_ID),
                eq("- 语言偏好:简体中文"), any());
    }

    @Test
    @DisplayName("同用户其他应用回填旧快照后删除失效必须最终清空缓存")
    void 同用户跨应用删除失效不得被迟到回填覆盖() throws Exception {
        提供应用归属(APP_B);
        String legacyCacheKey = "mem:pref:" + USER_ID;
        String cacheKey = "mem:pref:v2:" + USER_ID;
        String staleRecall = "- 语言偏好:简体中文";
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
                [{"name":"视觉风格","content":"偏好深色界面",
                "evidenceType":"EXPLICIT","turnIds":[271]}]
                """);
        String legacyCacheKey = "mem:pref:" + USER_ID;
        String cacheKey = "mem:pref:v2:" + USER_ID;
        String staleRecall = "- 语言偏好:简体中文";
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
        verify(redisTemplate).delete("mem:pref:v2:" + USER_ID);
    }

    @Test
    @DisplayName("tombstone 后 L2 召回不得重建归属或 Redis 缓存")
    void 删除接管后迟到召回不复活本地映射或缓存() {
        提供应用归属();
        String cacheKey = "mem:pref:v2:" + USER_ID;
        when(valueOperations.get(cacheKey))
                .thenReturn("- 语言偏好:简体中文");
        assertEquals("- 语言偏好:简体中文",
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
        return ChatHistory.builder()
                .id(id).appId(APP_ID).userId(USER_ID)
                .messageType(type).message(text).build();
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
                assertEquals("=", ((String) logicField.get(condition)).trim());
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
                .id(1L).userId(USER_ID).appId(APP_ID)
                .type("USER_PREFERENCE").name(name).content(content)
                .evidenceType(evidenceType).status(status)
                .evidenceCount(evidenceCount)
                .lastEvidenceTurnId(lastEvidenceTurnId)
                .createTime(now).updateTime(now).build();
    }

    private static final class RecordingTransactionOperations
            implements TransactionOperations {

        private int executionCount;
        private boolean active;

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            executionCount++;
            active = true;
            try {
                return action.doInTransaction(
                        new SimpleTransactionStatus());
            } finally {
                active = false;
            }
        }

        private int executionCount() {
            return executionCount;
        }

        private boolean isActive() {
            return active;
        }
    }
}
