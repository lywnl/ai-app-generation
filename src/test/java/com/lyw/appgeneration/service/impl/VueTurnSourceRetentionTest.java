package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.memory.*;
import com.lyw.appgeneration.core.handler.VueTurnContext;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;
import com.lyw.appgeneration.service.MemorySummarySnapshot;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class VueTurnSourceRetentionTest {
    @Test
    void 成功与业务失败都保留完整批次原文且可继续下一用户回合() {
        for (var outcome : List.of(ChatMemoryOutcome.SUCCEEDED, ChatMemoryOutcome.FAILED)) {
            try (var f = fixture()) {
                var first = ToolExecutionRequest.builder().id("read-1").name("readFile").arguments("{}").build();
                var second = ToolExecutionRequest.builder().id("read-2").name("readFile").arguments("{}").build();
                f.request.memory().add(AiMessage.from("读取原始报错", List.of(first, second)));
                f.request.memory().add(ToolExecutionResultMessage.from(first, "原始诊断日志"));
                f.request.memory().add(ToolExecutionResultMessage.from(second, "第二个文件内容"));
                var before = f.store.value.get();
                var saved = ai(outcome);
                f.request.context().tryStartFinalization(VueTurnContext.TerminalTrigger.COMPLETED);
                var closer = new ToolMessageCollapser(f.store);
                assertEquals(ToolMessageCollapser.CollapseStatus.COLLAPSED,
                        closer.finishLastTurn(f.request.context(), saved, MemorySummarySnapshot.empty()).status());
                var closed = f.store.value.get();
                assertEquals(before.entries(), closed.entries().subList(0, before.entries().size()));
                assertEquals(20L, closed.entries().getLast().historyRecordId());
                assertTrue(closed.turn(10).closed());
                assertEquals(L0SourceParser.Kind.BACKEND_TERMINAL, new L0SourceParser().parse(closed).units().getLast().kind());
                closer.finishLastTurn(f.request.context(), saved, MemorySummarySnapshot.empty());
                assertEquals(closed, f.store.value.get(), "重复关闭不追加第二份终态");
                CanonicalUserMessageScope.call(TurnRequestBoundary.of("继续分析原始报错", List.of(), 30, "next"), () -> {
                    f.request.memory().add(UserMessage.from("继续分析原始报错")); return null;
                });
                assertTrue(f.request.memory().messages().contains(ToolExecutionResultMessage.from(first, "原始诊断日志")));
            }
        }
    }

    @Test
    void 非法与未完成工具链保留原有恢复语义并使旧epoch失效() {
        for (var outcome : List.of(ChatMemoryOutcome.PROTOCOL_ERROR, ChatMemoryOutcome.INCOMPLETE_TOOL_CHAIN)) {
            try (var f = fixture()) {
                var pending = ToolExecutionRequest.builder().id("missing-result").name("readFile").arguments("{}").build();
                f.request.memory().add(AiMessage.from(pending));
                var before = f.store.value.get();
                var after = L0SourceOperations.finishTurn(before, 10, "turn", 20, outcome, "可信失败说明", 3);
                assertNotEquals(before.memoryEpoch(), after.memoryEpoch());
                assertEquals(3L, after.appliedSummaryRevision());
                if (outcome == ChatMemoryOutcome.INCOMPLETE_TOOL_CHAIN) {
                    assertTrue(after.entries().isEmpty());
                    assertTrue(after.turns().isEmpty());
                } else {
                    assertEquals(2, after.entries().size());
                    assertTrue(after.messages().getFirst() instanceof UserMessage);
                    assertEquals(20L, after.entries().getLast().historyRecordId());
                    assertTrue(after.turn(10).closed());
                }
                assertThrows(IllegalStateException.class, () -> L0SourceOperations.finishTurn(before,
                        10, "turn", 20, ChatMemoryOutcome.SUCCEEDED, "不能伪造完整链", 0));
            }
        }
    }

    @Test
    void 存储比较失败保留原文和开放状态供原失效路径处理() {
        try (var f = fixture()) {
            var before = f.store.value.get();
            f.store.rejectWrite = true;
            f.request.context().tryStartFinalization(VueTurnContext.TerminalTrigger.COMPLETED);
            var result = new ToolMessageCollapser(f.store).finishLastTurn(f.request.context(), ai(ChatMemoryOutcome.SUCCEEDED), MemorySummarySnapshot.empty());
            assertEquals(ToolMessageCollapser.CollapseStatus.STORE_FAILED, result.status());
            assertEquals(before, f.store.value.get());
            assertFalse(before.turn(10).closed());
        }
    }

    private UnifiedSummaryCoordinationTest.Fixture fixture() { return new UnifiedSummaryCoordinationTest.Fixture(Executors.newSingleThreadExecutor()); }
    private ChatHistory ai(ChatMemoryOutcome outcome) {
        return ChatHistory.builder().id(20L).appId(7L).userId(9L).messageType("ai")
                .message("展示文本").memoryMessage("真实结果：" + outcome).memoryOutcome(outcome).isDelete(0).build();
    }
}
