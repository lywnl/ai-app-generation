package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.ai.memory.*;
import com.lyw.appgeneration.ai.plan.*;
import com.lyw.appgeneration.ai.tools.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.tool.ToolExecution;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VueDecisionSourceCollectorTest {
    @Test
    void 采集绑定实际来源序号与generation且不等待展示消费() {
        var budget = new FileToolBudgetGuard().newSession();
        var transcript = new VueTurnTranscriptAccumulator(budget, 0);
        var collector = new VueDecisionSourceCollector(budget, transcript);
        var store = new SourceMemoryTestStore(L0Snapshot.empty("7", 0));
        var atomic = new AtomicChatMemoryStore(store);
        var memory = new TokenAwareChatMemory(MessageWindowChatMemory.builder().id(7L)
                .chatMemoryStore(atomic).maxMessages(Integer.MAX_VALUE).build(), atomic);
        CanonicalUserMessageScope.call(TurnRequestBoundary.of("不要轮播图", List.of(), 10, "turn"), () -> {
            memory.add(UserMessage.from("不要轮播图")); return null;
        });
        ContextContinuationGate gate = new ContextContinuationGate() {
            @Override public boolean tryRun(Runnable action) { action.run(); return true; }
            @Override public void sourceMessageCommitted(long generation, String epoch, L0Snapshot.Message message) {
                collector.sourceCommitted(generation, epoch, message);
            }
        };
        var request = request("write-1", "writeFile", "{}");
        String result = fileResult();
        SourceCommitScope.run(8, gate, () -> memory.add(AiMessage.from("拆分组件便于复用；另一个方案尚待决定", List.of(request))));
        SourceCommitScope.run(8, gate, () -> memory.add(ToolExecutionResultMessage.from(request, result)));
        var snapshot = memory.sourceSnapshot();
        assertFalse(collector.collectedThrough(snapshot, 10, 3));
        assertEquals("", transcript.displayText());
        collector.toolCommitted(8, ToolExecution.builder().request(request).result(result).build());
        assertTrue(collector.collectedThrough(snapshot, 10, 3));
        assertEquals(8, collector.snapshot().sources().get(snapshot.memoryEpoch() + ":2").generation());
        assertEquals(List.of("write-1"), collector.snapshot().sources().get(snapshot.memoryEpoch() + ":2").declaredToolIds());
        assertEquals(1, collector.facts().size());
        collector.toolCommitted(8, ToolExecution.builder().request(request).result(result).build());
        assertEquals(1, collector.facts().size());
        assertSame(transcript, collector.transcript());
    }

    @Test
    void 成功计划原始理由按工具来源保留且不依赖最新历史长度() {
        var budget = new FileToolBudgetGuard().newSession();
        var collector = new VueDecisionSourceCollector(budget, new VueTurnTranscriptAccumulator(budget, 0));
        for (int version = 1; version <= 20; version++) {
            var files = List.of(new PlanFile("src/App.vue", "减少移动端遮挡", PlanFileAction.MODIFY, List.of(), PlanFileState.PENDING));
            var request = request("plan-" + version, "updatePlan", "{\"reason\":\"原始否决原因" + version + "\"}");
            var result = PlanToolProtocolSupport.json(PlanToolResult.applied("updatePlan", "plan", version, "移动端布局", files));
            collector.toolCommitted(version, ToolExecution.builder().request(request).result(result).build());
        }
        assertEquals("原始否决原因1", collector.snapshot().tools().get("plan-1").plan().reason());
        assertEquals("减少移动端遮挡", collector.snapshot().tools().get("plan-1").plan().files().getFirst().purpose());
        assertEquals(20, collector.snapshot().tools().size());
    }

    @Test
    void 仅记录本条AI声明的工具关联且元数据超限不会假装采集成功() {
        var guard = new FileToolBudgetGuard();
        guard.setMaxSingleFileCodePoints(64);
        guard.setMaxCumulativeMutationCodePoints(64);
        guard.setMaxCanonicalAiTextCodePoints(128);
        var budget = guard.newSession();
        var collector = new VueDecisionSourceCollector(budget, new VueTurnTranscriptAccumulator(budget, 0));
        collector.sourceCommitted(1, "epoch", new L0Snapshot.Message(2, 10,
                AiMessage.from("未执行的建议", List.of(request("not-run", "writeFile", "{}")))));
        assertEquals(List.of("not-run"), collector.snapshot().sources().get("epoch:2").declaredToolIds());
        assertTrue(collector.snapshot().tools().isEmpty());
        var request = request("plan", "updatePlan", "{\"reason\":\"" + "原因".repeat(200) + "\"}");
        String result = PlanToolProtocolSupport.json(PlanToolResult.applied("updatePlan", "plan", 2, "布局", List.of()));
        assertThrows(VueDecisionSourceCollector.SourceLimitExceededException.class,
                () -> collector.toolCommitted(2, ToolExecution.builder().request(request).result(result).build()));
        assertTrue(collector.snapshot().tools().isEmpty());
    }

    private ToolExecutionRequest request(String id, String name, String arguments) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments(arguments).build();
    }
    private String fileResult() {
        return "{\"protocol\":\"file-tool/v1\",\"operation\":\"writeFile\",\"status\":\"APPLIED\","
                + "\"relativePath\":\"src/App.vue\",\"changed\":true,\"message\":\"已执行\",\"failureReason\":null,\"content\":null}";
    }
}
