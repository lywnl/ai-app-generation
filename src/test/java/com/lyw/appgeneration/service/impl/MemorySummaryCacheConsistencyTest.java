package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.memory.ChatTokenEstimator;
import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.mapper.AppMemorySummaryMapper;
import com.lyw.appgeneration.model.entity.AppMemorySummary;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;
import com.lyw.appgeneration.monitor.MemoryCompressionMetricsCollector;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemoryCompressionResult;
import dev.langchain4j.model.chat.ChatModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

class MemorySummaryCacheConsistencyTest {

    private static final String NEW_SUMMARY = """
            # 应用目标与定位
            新摘要
            # 用户偏好与硬约束
            无
            # 已否决的方案
            无
            # 关键设计决策与理由
            无
            # 当前进度速览
            无
            """.strip();

    private AppMemorySummaryMapper summaryMapper;
    private ChatHistoryService chatHistoryService;
    private ChatModel model;
    private ChatTokenEstimator tokenEstimator;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private ExecutorService modelExecutor;
    private SimpleMeterRegistry meterRegistry;
    private AtomicReference<AppMemorySummary> database;
    private MemorySummaryServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        summaryMapper = mock(AppMemorySummaryMapper.class);
        chatHistoryService = mock(ChatHistoryService.class);
        model = mock(ChatModel.class);
        tokenEstimator = mock(ChatTokenEstimator.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        modelExecutor = java.util.concurrent.Executors
                .newVirtualThreadPerTaskExecutor();
        meterRegistry = new SimpleMeterRegistry();
        database = new AtomicReference<>(summary(0L, "旧摘要"));

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(summaryMapper.selectOneByQuery(any()))
                .thenAnswer(invocation -> database.get());
        when(summaryMapper.commitSnapshot(any(AppMemorySummary.class), anyLong()))
                .thenAnswer(invocation -> {
                    AppMemorySummary updated = invocation.getArgument(0);
                    database.set(updated);
                    return 1;
                });
        when(summaryMapper.updateFailureMetadata(any(AppMemorySummary.class), anyLong())).thenReturn(1);
        when(chatHistoryService.listMessagesAfterCursor(1L, 0L, 100))
                .thenReturn(List.of(
                        history(1L, "user", "问题"),
                        history(2L, "ai", "回复")));
        when(model.chat(anyString())).thenReturn(NEW_SUMMARY);
        when(tokenEstimator.estimateText(anyString())).thenReturn(100);

        MemoryTokenProperties properties = new MemoryTokenProperties();
        MemorySummaryDraftEngine draftEngine = new MemorySummaryDraftEngine(
                chatHistoryService, model, modelExecutor,
                tokenEstimator, properties);
        service = new MemorySummaryServiceImpl(
                summaryMapper,
                draftEngine,
                mock(ExecutorService.class),
                redisTemplate,
                new AppDataLifecycleFence(),
                tokenEstimator,
                properties,
                new MemoryCompressionMetricsCollector(meterRegistry),
                Clock.fixed(Instant.parse("2026-08-16T08:00:00Z"),
                        ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        modelExecutor.shutdownNow();
        meterRegistry.close();
    }

    @Test
    void 旧摘要缓存删除失败时数据库游标不推进() {
        doThrow(new IllegalStateException("redis delete down"))
                .when(redisTemplate).delete("mem:summary:1");

        MemoryCompressionResult result = service.compressNow(
                1L, 2L, Duration.ofSeconds(60));

        assertEquals(MemoryCompressionResult.Status.MODEL_FAILED,
                result.status());
        assertEquals(0L, database.get().getLastSummarizedId());
        assertEquals("旧摘要", database.get().getSummary());
        org.junit.jupiter.api.Assertions.assertTrue(meterRegistry.find("memory_summary_tokens").summaries().isEmpty(),
                "持久化前缓存失效失败，草稿不得计为摘要提交成功");
        verify(redisTemplate).delete("mem:summary:1");
        verify(valueOperations, never()).set(
                anyString(), anyString(), any(Duration.class));
    }

    @Test
    void 删除成功但新缓存写失败时读取从数据库回源新摘要() {
        AtomicReference<String> cache = new AtomicReference<>("旧摘要");
        when(valueOperations.get("mem:summary:1"))
                .thenAnswer(invocation -> cache.get());
        doAnswer(invocation -> {
            cache.set(null);
            return true;
        }).when(redisTemplate).delete("mem:summary:1");
        doThrow(new IllegalStateException("redis set down"))
                .when(valueOperations).set(
                        anyString(), anyString(), any(Duration.class));

        MemoryCompressionResult result = service.compressNow(
                1L, 2L, Duration.ofSeconds(60));
        String recalled = service.getCurrentSummary(1L);

        assertEquals(MemoryCompressionResult.Status.COMPRESSED,
                result.status());
        assertEquals(2L, database.get().getLastSummarizedId());
        assertEquals(NEW_SUMMARY, database.get().getSummary());
        assertEquals(NEW_SUMMARY, recalled);
        assertNull(cache.get());
    }

    @Test
    void 完整快照缓存保留零历史游标及部分前沿旧正文键不参与读取() {
        var coverage = new com.lyw.appgeneration.ai.memory.SummaryCoverage(10, "turn", "epoch", 3,
                "a".repeat(64), 1, null, null, false);
        var snapshot = new com.lyw.appgeneration.service.MemorySummarySnapshot(NEW_SUMMARY, 0, 1, 2, coverage);
        database.set(AppMemorySummary.builder().id(1L).appId(1L).summary(NEW_SUMMARY).lastSummarizedId(0L)
                .summaryRevision(1L).coverageVersion(2)
                .coverageState(com.lyw.appgeneration.ai.memory.SummaryCoverageCodec.encode(coverage)).build());
        when(valueOperations.get("mem:summary:1")).thenReturn("旧正文不得参与覆盖判断");
        assertEquals(NEW_SUMMARY, service.getCurrentSummary(1L));
        var payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("mem:summary:v2:1"), payload.capture(), any(Duration.class));
        assertEquals(snapshot, com.lyw.appgeneration.ai.memory.MemorySummarySnapshotCodec.decode(1, payload.getValue()));
        assertEquals(snapshot, service.readSnapshot(1L));
        verify(valueOperations, never()).get("mem:summary:1");
        clearInvocations(summaryMapper);
        when(valueOperations.get("mem:summary:v2:1")).thenReturn(payload.getValue());
        assertEquals(NEW_SUMMARY, service.getCurrentSummary(1L));
        verifyNoInteractions(summaryMapper);
    }

    @Test
    void 新版缓存失效失败同样阻止数据库提交且清理尝试覆盖两个键() {
        doThrow(new IllegalStateException("完整快照缓存删除失败"))
                .when(redisTemplate).delete("mem:summary:v2:1");
        assertEquals(MemoryCompressionResult.Status.MODEL_FAILED, service.compressNow(1L, 2L, Duration.ofSeconds(5)).status());
        assertEquals(0L, database.get().getLastSummarizedId());
        verify(summaryMapper, never()).commitSnapshot(any(), anyLong());
        clearInvocations(redisTemplate);
        assertFalse(service.invalidateCache(1L).failedTargets().isEmpty());
        verify(redisTemplate).delete("mem:summary:1");
        verify(redisTemplate).delete("mem:summary:v2:1");
    }

    private AppMemorySummary summary(long cursor, String text) {
        LocalDateTime now = LocalDateTime.of(
                2026, 8, 16, 16, 0);
        return AppMemorySummary.builder()
                .id(1L)
                .appId(1L)
                .summary(text)
                .lastSummarizedId(cursor)
                .summaryTokens(100)
                .failCount(0)
                .createTime(now)
                .updateTime(now)
                .isDelete(0)
                .build();
    }

    private ChatHistory history(long id, String type, String text) {
        ChatHistory.ChatHistoryBuilder builder = ChatHistory.builder()
                .id(id)
                .appId(1L)
                .userId(7L)
                .messageType(type)
                .message(text);
        if ("ai".equals(type)) {
            builder.memoryMessage(text)
                    .memoryOutcome(ChatMemoryOutcome.SUCCEEDED);
        }
        return builder.build();
    }
}
