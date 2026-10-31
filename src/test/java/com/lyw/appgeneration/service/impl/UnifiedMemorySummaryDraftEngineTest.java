package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.memory.*;
import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.service.*;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class UnifiedMemorySummaryDraftEngineTest {
    private static final String SUMMARY = """
            # 应用目标与定位
            页面
            # 用户偏好与硬约束
            不要轮播图
            # 已否决的方案
            轮播遮挡
            # 关键设计决策与理由
            组件复用
            # 当前进度速览
            继续修复类型错误
            """;

    @Test
    void 游标零的旧摘要继续复用并且只有新增来源进入同一草稿() {
        var source = L0Snapshot.empty("7", 0);
        var previous = new MemorySummarySnapshot(SUMMARY, 0, 1, 2,
                new SummaryCoverage(10, "turn", source.memoryEpoch(), 2, "a".repeat(64), 1, null, null, false));
        var input = input(source, previous, List.of(unit("新增失败尝试", 3)));
        var model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn(SUMMARY);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = engine(model, executor).buildDraft(input, Long.MAX_VALUE, () -> true);
            assertNull(result.failureStatus());
            assertTrue(result.changed());
            assertEquals(0, result.summarizedThroughId());
            verify(model).chat(argThat((String prompt) -> prompt.contains("不要轮播图") && prompt.contains("新增失败尝试")));
        }
    }

    @Test
    void 按完整单元分块且中途失败不发布部分成功覆盖() {
        var model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn(SUMMARY, "无效五段摘要");
        try (var executor = Executors.newSingleThreadExecutor()) {
            var source = L0Snapshot.empty("7", 0);
            var result = engine(model, executor).buildDraft(input(source, MemorySummarySnapshot.empty(),
                    List.of(unit("第一块" + "a".repeat(150000), 2), unit("第二块" + "b".repeat(150000), 3))), Long.MAX_VALUE, () -> true);
            assertEquals(MemoryCompressionResult.Status.MODEL_FAILED, result.failureStatus());
            assertFalse(result.changed());
            assertEquals("", result.summary());
            assertEquals(0, result.summarizedThroughId());
            verify(model, times(2)).chat(anyString());
        }
    }

    @Test
    void 单个不可拆来源过大不调用模型且输出超限沿用收敛器() {
        var source = L0Snapshot.empty("7", 0);
        var tooLarge = mock(ChatModel.class);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = engine(tooLarge, executor).buildDraft(input(source, MemorySummarySnapshot.empty(),
                    List.of(unit("a".repeat(320000), 2))), Long.MAX_VALUE, () -> true);
            assertEquals(MemoryCompressionResult.Status.MODEL_FAILED, result.failureStatus());
            assertFalse(result.changed());
            verifyNoInteractions(tooLarge);
        }
        var model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn(SUMMARY + "过长诊断".repeat(2000), SUMMARY);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = engine(model, executor).buildDraft(input(source, MemorySummarySnapshot.empty(),
                    List.of(unit("需要摘要的诊断", 2))), Long.MAX_VALUE, () -> true);
            assertNull(result.failureStatus());
            assertEquals(1, result.reducerRounds());
            assertTrue(result.summaryTokens() <= 3072);
        }
    }

    private MemorySummaryDraftEngine engine(ChatModel model, java.util.concurrent.ExecutorService executor) {
        var properties = new MemoryTokenProperties();
        return new MemorySummaryDraftEngine(mock(ChatHistoryService.class), model, executor,
                new ConservativeChatTokenEstimator(properties), properties);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 等待启动许可后必须复检截止及中断且不虚增调用计数(boolean expired) throws Exception {
        var source = L0Snapshot.empty("7", 0);
        var model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn(SUMMARY);
        var clock = new AtomicLong();
        long deadline = TimeUnit.MILLISECONDS.toNanos(500);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var properties = new MemoryTokenProperties();
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try (var executor = Executors.newSingleThreadExecutor();
             var caller = Executors.newSingleThreadExecutor()) {
            var engine = new MemorySummaryDraftEngine(mock(ChatHistoryService.class), model, executor,
                    new ConservativeChatTokenEstimator(properties), properties, clock::get);
            engine.configureMetrics(new com.lyw.appgeneration.monitor.MemoryCompressionMetricsCollector(registry));
            var pending = caller.submit(() -> engine.buildDraft(input(source, MemorySummarySnapshot.empty(),
                    List.of(unit("来源已经就绪，等待回合锁", 2))), deadline, () -> {
                entered.countDown();
                boolean interrupted = false;
                while (true) {
                    try { release.await(); break; }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
                // 模拟不可中断 monitor 等待，或依赖清除中断后迟到返回许可的两种情况。
                if (!expired && interrupted) Thread.currentThread().interrupt();
                return true;
            }));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                var outcome = pending.get(3, TimeUnit.SECONDS);
                assertEquals(MemoryCompressionResult.Status.TIMED_OUT, outcome.failureStatus());
                assertFalse(outcome.changed());
                if (expired) clock.set(deadline + 1);
                release.countDown();
                executor.submit(() -> { }).get(3, TimeUnit.SECONDS);
                assertAll(() -> verifyNoInteractions(model), () -> {
                    var counter = registry.find("memory_summary_model_calls_total").counter();
                    assertEquals(0, counter == null ? 0 : counter.count());
                });
            } finally {
                release.countDown();
            }
        } finally { registry.close(); }
    }
    private SummaryUnit unit(String text, long sequence) {
        return new SummaryUnit("source-" + sequence, 10, SummaryUnit.Kind.MODEL_TEXT, "epoch", sequence, sequence,
                List.of(AiMessage.from(text)), null, null, null);
    }
    private SummaryInput input(L0Snapshot source, MemorySummarySnapshot previous, List<SummaryUnit> selected) {
        return new SummaryInput(7, 9, previous, source, selected, 0,
                new SummaryCoverage(10, "turn", source.memoryEpoch(), selected.getLast().lastSequence(), "a".repeat(64), 1, null, null, false),
                Map.of(), Map.of());
    }
}
