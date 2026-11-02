package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.service.MemorySummarySnapshot;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class SummaryWindowSelectorTest {
    @ParameterizedTest
    @CsvSource({"3000,8192,true", "3001,5192,false"})
    void 八K边界整批保留且当前用户原话不挤占额度(int previousTokens, int retainedTokens, boolean keepPrevious) {
        var user = unit("user:10", 10, SummaryUnit.Kind.USER, 1, 1, List.of(UserMessage.from("保留用户要求")));
        var old = batch("old", 10, 2, true);
        var previous = batch("previous", 10, 5, true);
        var latest = batch("latest", 10, 8, true);
        var estimator = mock(ChatTokenEstimator.class);
        when(estimator.estimateMessages(user.messages())).thenReturn(20_000);
        when(estimator.estimateMessages(old.messages())).thenReturn(1_000);
        when(estimator.estimateMessages(previous.messages())).thenReturn(previousTokens);
        when(estimator.estimateMessages(latest.messages())).thenReturn(5_192);
        var input = materials(10, List.of(user, old, previous, latest));

        var selection = new SummaryWindowSelector(estimator, new MemoryTokenProperties()).select(input, input.units());

        assertEquals(retainedTokens, selection.retainedRawTokens());
        assertTrue(selection.retainedSourceIds().containsAll(List.of(user.sourceId(), latest.sourceId())));
        assertEquals(keepPrevious, selection.retainedSourceIds().contains(previous.sourceId()));
        assertFalse(selection.retainedSourceIds().contains(old.sourceId()));
        assertTrue(selection.selected().contains(old));
        assertEquals(!keepPrevious, selection.selected().contains(previous));
        assertEquals(3, latest.messages().size());
    }

    @Test
    void 并行工具批次保持原序整批保留且较早当前用户原话独立保护() {
        var estimator = estimator(5000);
        var user = unit("user:10", 10, SummaryUnit.Kind.USER, 1, 1, List.of(UserMessage.from("原始要求")));
        var old = unit("old", 10, SummaryUnit.Kind.MODEL_TEXT, 2, 2, List.of(AiMessage.from("较早诊断")));
        var latest = batch("latest", 10, 3, true);
        var input = materials(10, List.of(user, old, latest));
        var selection = new SummaryWindowSelector(estimator, new MemoryTokenProperties()).select(input, input.units());
        assertTrue(selection.retainedSourceIds().containsAll(List.of(user.sourceId(), latest.sourceId())));
        assertEquals(15000, selection.retainedRawTokens());
        assertEquals(List.of(user, old), selection.selected());
        assertEquals(3, latest.messages().size());
        assertTrue(input.source().entries().isEmpty(), "选择不能写入或裁剪 L0");
    }

    @Test
    void 最新超软目标批次和后面的模型说明一起保留() {
        var latest = batch("latest", 10, 3, true);
        var note = unit("note", 10, SummaryUnit.Kind.MODEL_TEXT, 6, 6, List.of(AiMessage.from("继续修复")));
        var input = materials(10, List.of(latest, note));
        var selection = new SummaryWindowSelector(estimator(6000), new MemoryTokenProperties()).select(input, input.units());
        assertEquals(24000, selection.retainedRawTokens());
        assertEquals(2, selection.retainedSourceIds().size());
        assertFalse(selection.hasWork());
    }

    @Test
    void 跨回合保留并且未完成批次不能被裁剪() {
        var previous = batch("previous", 10, 2, true);
        var user = unit("user:30", 30, SummaryUnit.Kind.USER, 5, 5, List.of(UserMessage.from("分析上一轮错误")));
        var pending = batch("pending", 30, 6, false);
        var input = materials(30, List.of(previous, user, pending));
        var selection = new SummaryWindowSelector(estimator(1000), new MemoryTokenProperties()).select(input, input.units());
        assertTrue(selection.retainedSourceIds().containsAll(List.of("previous", "user:30", "pending")));
        assertFalse(selection.hasWork());
    }

    private ChatTokenEstimator estimator(int cost) {
        var estimator = mock(ChatTokenEstimator.class);
        when(estimator.estimateMessages(anyList())).thenAnswer(invocation -> invocation.<List<?>>getArgument(0).size() * cost);
        return estimator;
    }
    private SummaryUnit batch(String id, long user, long start, boolean complete) {
        var a = ToolExecutionRequest.builder().id(id + "a").name("readFile").arguments("{}").build();
        var b = ToolExecutionRequest.builder().id(id + "b").name("readDir").arguments("{}").build();
        var messages = complete ? List.<ChatMessage>of(AiMessage.from(List.of(a, b)), ToolExecutionResultMessage.from(b, "目录"),
                ToolExecutionResultMessage.from(a, "错误日志")) : List.<ChatMessage>of(AiMessage.from(List.of(a, b)));
        return unit(id, user, complete ? SummaryUnit.Kind.TOOL_BATCH : SummaryUnit.Kind.PENDING_TOOL_BATCH,
                start, start + messages.size() - 1, messages);
    }
    private SummaryUnit unit(String id, long user, SummaryUnit.Kind kind, long first, long last, List<ChatMessage> messages) {
        return new SummaryUnit(id, user, kind, "epoch", first, last, messages, null, null, null);
    }
    private SummaryMaterials materials(long current, List<SummaryUnit> units) {
        var turns = units.stream().map(SummaryUnit::userHistoryId).distinct().map(id -> new SummaryMaterials.Turn(id, "turn" + id,
                "epoch", 10, null, null, null, units.stream().filter(unit -> unit.userHistoryId() == id).toList())).toList();
        return new SummaryMaterials(7, 9, current, MemorySummarySnapshot.empty(), L0Snapshot.empty("7", 0), turns, Map.of());
    }
}
