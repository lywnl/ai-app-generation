package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.memory.AdmissionDeadline;
import com.lyw.appgeneration.ai.memory.AtomicChatMemoryStore;
import com.lyw.appgeneration.ai.memory.ChatTokenEstimator;
import com.lyw.appgeneration.ai.memory.CompressionAwareChatMemory;
import com.lyw.appgeneration.ai.memory.ContextAdmissionResult;
import com.lyw.appgeneration.ai.memory.ContextCompressionAttemptState;
import com.lyw.appgeneration.ai.memory.ContextCompressionCoordinator;
import com.lyw.appgeneration.ai.memory.ContextCompressionMode;
import com.lyw.appgeneration.ai.memory.DeadlineAwareChatMemoryStore;
import com.lyw.appgeneration.ai.memory.DeadlineAwareReplaceResult;
import com.lyw.appgeneration.ai.memory.TokenAwareChatMemory;
import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.mapper.AppMemorySummaryMapper;
import com.lyw.appgeneration.model.entity.AppMemorySummary;
import com.lyw.appgeneration.monitor.MemoryCompressionMetricsCollector;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemoryCompressionResult;
import com.lyw.appgeneration.service.UserMemoryService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 使用真实摘要读取、分层组装和门禁，只替代数据库与 Redis IO。 */
class MemorySummarySnapshotIntegrationTest {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AtomicReference<AppMemorySummary> database = new AtomicReference<>();
    private AppMemorySummaryMapper mapper;
    private ChatTokenEstimator estimator;
    private CompressionAwareChatMemory memory;
    private ContextCompressionCoordinator coordinator;
    private MemorySummaryServiceImpl summaries;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mapper = mock(AppMemorySummaryMapper.class);
        estimator = mock(ChatTokenEstimator.class);
        when(estimator.estimateText(anyString())).thenReturn(100);
        when(estimator.estimateMessages(anyList())).thenReturn(100);
        when(estimator.estimateRequest(anyList(), anyList())).thenReturn(1_000);
        when(mapper.selectOneByQuery(any())).thenAnswer(ignored -> database.get());
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        MemoryTokenProperties properties = new MemoryTokenProperties();
        AppDataLifecycleFence lifecycle = new AppDataLifecycleFence();
        MemoryCompressionMetricsCollector metrics =
                new MemoryCompressionMetricsCollector(registry);
        summaries = spy(new MemorySummaryServiceImpl(
                mapper, mock(MemorySummaryDraftEngine.class), executor,
                redis, lifecycle, estimator, properties, metrics, Clock.systemUTC()));
        AtomicChatMemoryStore store = new AtomicChatMemoryStore(new TestStore());
        MessageWindowChatMemory delegate = MessageWindowChatMemory.builder()
                .id(7L).maxMessages(Integer.MAX_VALUE).chatMemoryStore(store).build();
        memory = new CompressionAwareChatMemory(
                new TokenAwareChatMemory(delegate, store), summaries,
                mock(UserMemoryService.class));
        memory.add(UserMessage.from("第一轮要求"));
        memory.add(AiMessage.from("第一轮结果"));
        memory.add(UserMessage.from("第二轮要求"));
        memory.add(AiMessage.from("第二轮结果"));
        memory.add(UserMessage.from("当前要求"));
        ChatHistoryService histories = mock(ChatHistoryService.class);
        when(histories.listRecentCompleteTurnBoundaries(7L, 2)).thenReturn(List.of(
                new ChatHistoryService.StableTurnBoundary(1L, 2L, "第一轮要求", "第一轮结果"),
                new ChatHistoryService.StableTurnBoundary(3L, 4L, "第二轮要求", "第二轮结果")));
        when(histories.listRecentCompleteTurnBoundaries(7L, 1)).thenReturn(List.of(
                new ChatHistoryService.StableTurnBoundary(3L, 4L, "第二轮要求", "第二轮结果")));
        coordinator = new ContextCompressionCoordinator(
                estimator, histories, summaries, properties, executor, lifecycle, metrics);
    }

    @AfterEach
    void close() {
        executor.shutdownNow();
        registry.close();
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 2L})
    void 请求读取期间摘要推进仍使用同一版本的正文与边界(long originalCursor)
            throws Exception {
        database.set(summary(originalCursor, "旧版摘要"));
        CountDownLatch firstRead = new CountDownLatch(1);
        CountDownLatch published = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        when(mapper.selectOneByQuery(any())).thenAnswer(ignored -> {
            AppMemorySummary selected = database.get();
            if (first.compareAndSet(true, false)) {
                firstRead.countDown();
                assertTrue(published.await(5, TimeUnit.SECONDS));
            }
            return selected;
        });
        var request = executor.submit(() -> coordinator.admit(memory, List.of()));
        try {
            assertTrue(firstRead.await(5, TimeUnit.SECONDS));
            database.set(summary(4L, "新版摘要已覆盖第二轮"));
        } finally {
            published.countDown();
        }

        ContextAdmissionResult result = request.get(5, TimeUnit.SECONDS);

        assertEquals(ContextCompressionMode.NORMAL, result.mode());
        String input = result.requestMessages().toString();
        assertFalse(input.contains("新版摘要已覆盖第二轮"),
                "本次已经选择旧边界，不能中途注入新版摘要造成重复历史");
        assertTrue(input.contains("第二轮要求"));
        assertEquals(originalCursor == 0L, input.contains("第一轮要求"));
        assertEquals(originalCursor > 0L, input.contains("旧版摘要"));
        verify(summaries, never()).getCurrentSummary(7L);
    }

    @Test
    void 冷启动跳过旧回合后按新快照去除进一步覆盖的历史() {
        database.set(summary(2L, "冷启动时摘要"));
        assertEquals(2L, summaries.readSnapshot(7L).lastSummarizedId());
        memory.clear();
        memory.add(UserMessage.from("第二轮要求"));
        memory.add(AiMessage.from("第二轮结果"));
        memory.add(UserMessage.from("当前要求"));
        database.set(summary(4L, "请求前摘要已推进"));

        ContextAdmissionResult result = coordinator.admit(memory, List.of());

        assertEquals(ContextCompressionMode.NORMAL, result.mode());
        String input = result.requestMessages().toString();
        assertTrue(input.contains("请求前摘要已推进"));
        assertFalse(input.contains("第一轮要求"));
        assertFalse(input.contains("第二轮要求"));
        assertTrue(input.contains("当前要求"));
        verify(summaries, never()).getCurrentSummary(7L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 阻塞压缩返回更大覆盖边界时按实际快照裁剪(boolean checkpoint) {
        database.set(summary(0L, ""));
        if (checkpoint) {
            appendReadResult();
        }
        when(estimator.estimateRequest(anyList(), anyList()))
                .thenReturn(57_344, checkpoint ? 65_536 : 1_000, 1_000);
        when(estimator.estimateMessages(anyList())).thenAnswer(invocation -> {
            List<ChatMessage> messages = invocation.getArgument(0);
            return messages.toString().contains("第一轮要求") ? 13_000 : 12_000;
        });
        doAnswer(ignored -> {
            database.set(summary(4L, "新版摘要已覆盖第二轮"));
            return new MemoryCompressionResult(
                    MemoryCompressionResult.Status.COMPRESSED, 4L, 100, "完成");
        }).when(summaries).compressNow(eq(7L), eq(2L), any(Duration.class));

        ContextAdmissionResult result = coordinator.admit(memory,
                List.of(ToolSpecification.builder().name("readFile").build()));

        assertEquals(checkpoint ? ContextCompressionMode.TOOL_CHAIN_CHECKPOINT_COMPLETED
                : ContextCompressionMode.BLOCKING_COMPLETED, result.mode());
        assertEquals(4L, result.summarizeThroughId());
        String input = result.requestMessages().toString();
        assertTrue(input.contains("新版摘要已覆盖第二轮"));
        assertFalse(input.contains("第二轮要求"), "新摘要已经覆盖的回合不能重复发送");
        assertTrue(input.contains("当前要求"));
        verify(summaries, never()).getCurrentSummary(7L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 检查点准备不得再次读取并混入更新的摘要(boolean active) {
        database.set(summary(2L, "旧版摘要"));
        appendReadResult();
        when(estimator.estimateRequest(anyList(), anyList())).thenAnswer(invocation -> {
            List<ChatMessage> messages = invocation.getArgument(0);
            database.set(summary(4L, "新版摘要已覆盖第二轮"));
            return messages.toString().contains("本轮可信执行检查点") ? 1_000 : 65_536;
        });
        ContextCompressionAttemptState state = new ContextCompressionAttemptState();
        if (active) {
            assertTrue(state.markCheckpointReady(state.tryEnterCheckpointMode()));
        }

        ContextAdmissionResult result = coordinator.admit(memory,
                List.of(ToolSpecification.builder().name("readFile").build()), state);

        assertEquals(active ? ContextCompressionMode.TOOL_CHAIN_CHECKPOINT_REBUILT
                : ContextCompressionMode.TOOL_CHAIN_CHECKPOINT_COMPLETED, result.mode());
        String input = result.requestMessages().toString();
        assertTrue(input.contains("旧版摘要"));
        assertFalse(input.contains("新版摘要已覆盖第二轮"));
        assertTrue(input.contains("第二轮要求"));
        verify(summaries, never()).getCurrentSummary(7L);
    }

    private void appendReadResult() {
        ToolExecutionRequest read = ToolExecutionRequest.builder()
                .id("read-1").name("readFile")
                .arguments("{\"relativeFilePath\":\"src/App.vue\"}").build();
        memory.add(AiMessage.from(read));
        memory.add(ToolExecutionResultMessage.from(read, """
                {"protocol":"file-tool/v1","operation":"readFile","status":"APPLIED",
                 "relativePath":"src/App.vue","changed":false,"message":"读取成功",
                 "failureReason":null,"content":"<template>首页</template>"}
                """));
    }

    private AppMemorySummary summary(long cursor, String label) {
        String text = cursor == 0L ? "" : """
                # 应用目标与定位
                %s
                # 用户偏好与硬约束
                无
                # 已否决的方案
                无
                # 关键设计决策与理由
                无
                # 当前进度速览
                已完成
                """.formatted(label).strip();
        return AppMemorySummary.builder().id(1L).appId(7L)
                .summary(text).lastSummarizedId(cursor).summaryTokens(100).build();
    }

    private static final class TestStore
            implements ChatMemoryStore, DeadlineAwareChatMemoryStore {
        private List<ChatMessage> messages = List.of();

        @Override
        public synchronized List<ChatMessage> getMessages(Object id) {
            return messages;
        }

        @Override
        public synchronized void updateMessages(Object id, List<ChatMessage> replacement) {
            messages = List.copyOf(replacement);
        }

        @Override
        public synchronized void deleteMessages(Object id) {
            messages = List.of();
        }

        @Override
        public Duration worstCaseCommitDuration() {
            return Duration.ZERO;
        }

        @Override
        public synchronized DeadlineAwareReplaceResult replaceMessagesIfMatches(
                Object id, List<ChatMessage> expected, List<ChatMessage> replacement,
                AdmissionDeadline deadline) {
            if (deadline.remainingNanos() <= 0L) {
                return DeadlineAwareReplaceResult.TIMED_OUT;
            }
            if (!messages.equals(expected)) {
                return DeadlineAwareReplaceResult.PREFIX_CHANGED;
            }
            messages = List.copyOf(replacement);
            return DeadlineAwareReplaceResult.REPLACED;
        }
    }
}
