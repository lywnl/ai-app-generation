package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.memory.*;
import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.service.*;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

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
