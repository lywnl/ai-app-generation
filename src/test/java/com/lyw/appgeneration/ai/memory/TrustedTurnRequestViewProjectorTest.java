package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;
import com.lyw.appgeneration.service.CompatibleMemorySnapshot;
import com.lyw.appgeneration.service.MemorySummarySnapshot;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.UserMemoryService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.ChatMemory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrustedTurnRequestViewProjectorTest {
    @Test
    void 跨回合保留工具原文且普通AI说明不能进入可信结果通道() {
        var original = source();
        var view = new TrustedTurnRequestViewProjector().project(pair(MemorySummarySnapshot.empty(), original));
        assertTrue(view.trustedState().text().contains("真实失败结果"));
        assertTrue(view.trustedState().text().contains("真实成功结果"));
        assertFalse(view.trustedState().text().contains("模型声称全部成功"));
        assertTrue(view.conversationMessages().contains(AiMessage.from("模型声称全部成功")));
        assertTrue(view.conversationMessages().stream().anyMatch(message -> message instanceof ToolExecutionResultMessage result
                && result.text().equals("原始错误日志")));
        assertFalse(view.conversationMessages().contains(AiMessage.from("真实失败结果")));
        assertEquals(original.messages().getLast(), view.conversationMessages().getLast());
    }

    @Test
    void 已覆盖旧结果去重但保留近期批次中同事实的结果() {
        var source = source();
        var first = source.turn(10);
        var coverage = new SummaryCoverage(10, "first", source.memoryEpoch(), first.userSequence(),
                L0SourceOperations.fingerprint(source, 10, first.userSequence(), 0), 0, 20L, ChatMemoryOutcome.FAILED, true);
        var partial = new MemorySummarySnapshot(summary(0).summary(), 0, 1, 2, coverage);
        var view = new TrustedTurnRequestViewProjector().project(pair(partial, source));
        assertFalse(view.trustedState().text().contains("真实失败结果"));
        assertTrue(view.trustedState().text().contains("真实成功结果"));
        assertTrue(view.conversationMessages().stream().anyMatch(message -> message instanceof ToolExecutionResultMessage result
                && result.text().equals("原始错误日志")));
        assertTrue(view.conversationMessages().stream().anyMatch(message -> message instanceof AiMessage ai && ai.hasToolExecutionRequests()));
        var fullyCovered = new TrustedTurnRequestViewProjector().project(pair(summary(40), source));
        assertFalse(fullyCovered.trustedState().text().contains("真实失败结果"));
        assertFalse(fullyCovered.trustedState().text().contains("真实成功结果"));
        assertTrue(fullyCovered.trustedState().text().contains("当前用户回合"));
        assertEquals(List.of(source.messages().getLast()), fullyCovered.conversationMessages());
    }

    @Test
    void 分层组装只消费固定快照不重读活动窗口或摘要() {
        var captured = pair(MemorySummarySnapshot.empty(), source());
        ChatMemory delegate = mock(ChatMemory.class);
        when(delegate.id()).thenReturn(1L);
        when(delegate.messages()).thenThrow(new AssertionError("不得重新读取已回收的活动窗口"));
        var summaries = mock(MemorySummaryService.class);
        var preferences = mock(UserMemoryService.class);
        var memory = new LayeredChatMemory(delegate, summaries, preferences);
        // 后台已经形成新窗口；当前调用仍持有此前完整旧副本。
        var latest = L0SourceOperations.prune(captured.source(), summary(40), 50);
        assertTrue(latest.entries().size() < captured.source().entries().size());
        var messages = memory.prepareCompatible(captured);
        assertTrue(messages.toString().contains("原始错误日志"));
        verify(delegate, never()).messages();
        verifyNoInteractions(summaries);
        verify(preferences, times(1)).recallByApp(1L);
    }

    static CompatibleMemorySnapshot pair(MemorySummarySnapshot summary, L0Snapshot source) {
        return new CompatibleMemorySnapshot(1, 50, "current", summary, source);
    }

    static L0Snapshot source() {
        var source = L0Snapshot.empty("1", 0);
        source = L0SourceOperations.replaceSystem(source, SystemMessage.from("系统约束"));
        var first = TurnRequestBoundary.of("历史需求", List.of(), 10, "first");
        source = L0SourceOperations.append(source, first.userMessage(), first);
        source = L0SourceOperations.append(source, AiMessage.from("模型声称全部成功"), null);
        var call = ToolExecutionRequest.builder().id("call-1").name("readFile").arguments("{}").build();
        source = L0SourceOperations.append(source, AiMessage.from(call), null);
        source = L0SourceOperations.append(source, ToolExecutionResultMessage.from(call, "原始错误日志"), null);
        source = L0SourceOperations.finishTurn(source, 10, "first", 20, ChatMemoryOutcome.FAILED, "真实失败结果", 0);
        var second = TurnRequestBoundary.of("再尝试", List.of(), 30, "second");
        source = L0SourceOperations.append(source, second.userMessage(), second);
        source = L0SourceOperations.append(source, AiMessage.from("修复说明"), null);
        source = L0SourceOperations.finishTurn(source, 30, "second", 40, ChatMemoryOutcome.SUCCEEDED, "真实成功结果", 0);
        var current = TurnRequestBoundary.of("当前需求", List.of(), 50, "current");
        return L0SourceOperations.append(source, current.userMessage(), current);
    }

    static MemorySummarySnapshot summary(long cursor) {
        String text = "# 应用目标与定位\nL1 摘要\n# 用户偏好与硬约束\n保留中文\n# 已否决的方案\n无\n"
                + "# 关键设计决策与理由\n按原始说明\n# 当前进度速览\n旧回合已结束";
        // 为部分覆盖测试提供固定正文；游标 0 本身使用旧有完整历史前置夹具，不作为最终快照传出。
        return cursor == 0 ? new MemorySummarySnapshot(text, 1) : new MemorySummarySnapshot(text, cursor, 1, 2, null);
    }
}
