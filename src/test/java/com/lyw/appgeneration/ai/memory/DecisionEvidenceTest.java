package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.ai.tools.FileToolBudgetGuard;
import com.lyw.appgeneration.core.handler.VueDecisionSourceCollector;
import com.lyw.appgeneration.core.handler.VueTurnTranscriptAccumulator;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.service.tool.ToolExecution;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.lyw.appgeneration.ai.memory.DecisionEvidence.*;
import static org.junit.jupiter.api.Assertions.*;

class DecisionEvidenceTest {
    @Test
    void 用户否决引用原始记录且修改与构建失败分别保留() {
        var user = new Entry("user:10", SourceKind.USER, "", 0, List.of(), List.of(), List.of(), List.of(), 0);
        var reason = explanation(List.of(new ChangeFact("write", 1, 3, "src/App.vue", "writeFile")), List.of());
        var first = DecisionEvidence.empty(10, "turn").merge(List.of(user, reason), "epoch", 3, 1, 1000);
        var withFailure = explanation(reason.implementations(), List.of(new BuildFact("build", 2, 5, 1, ValidationStatus.FAILED)));
        var second = first.merge(List.of(withFailure), "epoch", 5, 2, 1000);
        assertEquals(2L, second.revision());
        assertTrue(second.entries().get(1).hasLinkedChanges());
        assertEquals(ValidationStatus.FAILED, second.entries().get(1).validationStatus());
        assertEquals("拆分 src/App.vue 为了复用", second.entries().get(1).text());
        assertEquals("", second.entries().getFirst().text(), "用户理由读取同一记录的原话，不重复复制");
        assertEquals(second, second.merge(List.of(user, reason), "epoch", 3, 1, 1000));
        assertEquals(second, DecisionEvidenceCodec.decode(DecisionEvidenceCodec.encode(second), 10, "turn"));
    }

    @Test
    void 未执行建议和构建后的新修改均不能被视为已验证() {
        assertFalse(explanation(List.of(), List.of()).hasLinkedChanges());
        var entry = explanation(List.of(new ChangeFact("write", 3, 8, "src/App.vue", "modifyFile")),
                List.of(new BuildFact("build", 2, 6, 1, ValidationStatus.SUCCEEDED)));
        assertTrue(entry.hasLinkedChanges());
        assertEquals(ValidationStatus.UNVERIFIED, entry.validationStatus());
    }

    @Test
    void 来源原文不可改写且旧证据不能串到其他用户回合() {
        var evidence = DecisionEvidence.empty(10, "turn").merge(List.of(explanation(List.of(), List.of())), "epoch", 2, 1, 1000);
        var invented = new Entry("ai:epoch:2", SourceKind.MODEL_TEXT, "事后编造的理由", 1,
                List.of("src/App.vue"), List.of("write"), List.of(), List.of(), 0);
        assertThrows(IllegalStateException.class, () -> evidence.merge(List.of(invented), "epoch", 2, 1, 1000));
        String json = DecisionEvidenceCodec.encode(evidence);
        assertThrows(IllegalArgumentException.class, () -> DecisionEvidenceCodec.decode(json, 20, "turn"));
        assertThrows(IllegalArgumentException.class, () -> DecisionEvidenceCodec.decode(json, 10, "other"));
        assertThrows(IllegalArgumentException.class, () -> DecisionEvidenceCodec.decode(json.replace("\"formatVersion\":1", "\"formatVersion\":2"), 10));
    }

    @Test
    void 采集仅关联本条表达声明的实际工具并且只有代码结果时不造理由() {
        var budget = new FileToolBudgetGuard().newSession();
        var collector = new VueDecisionSourceCollector(budget, new VueTurnTranscriptAccumulator(budget, 0));
        var declared = ToolExecutionRequest.builder().id("write").name("writeFile")
                .arguments("{\"relativePath\":\"src/App.vue\"}").build();
        var unexecuted = ToolExecutionRequest.builder().id("not-run").name("writeFile").arguments("{}").build();
        var ai = AiMessage.from("拆分 src/App.vue 为了复用；另一个建议尚未执行", List.of(declared, unexecuted));
        String result = "{\"protocol\":\"file-tool/v1\",\"operation\":\"writeFile\",\"status\":\"APPLIED\","
                + "\"relativePath\":\"src/App.vue\",\"changed\":true,\"message\":\"已执行\",\"failureReason\":null,\"content\":null}";
        var entries = List.of(new L0Snapshot.Message(1, 10, UserMessage.from("不要轮播图，因为遮挡内容")),
                new L0Snapshot.Message(2, 10, ai), new L0Snapshot.Message(3, 10, ToolExecutionResultMessage.from(declared, result)));
        var source = new L0Snapshot(1, "7", "epoch", 4, 0L, entries,
                List.of(new L0Snapshot.Turn(10, "turn", 1, null, null, null)));
        collector.sourceCommitted(1, "epoch", entries.get(1));
        collector.sourceCommitted(1, "epoch", entries.get(2));
        collector.toolCommitted(1, ToolExecution.builder().request(declared).result(result).build());
        var assembled = new DecisionEvidenceAssembler().collect(10, source, 3, collector.snapshot(), null);
        assertEquals(2, assembled.size());
        assertEquals(List.of("write"), assembled.get(1).implementations().stream().map(ChangeFact::toolRequestId).toList());
        assertEquals(List.of("write", "not-run"), assembled.get(1).sourceToolIds());
        var withoutExplanation = new L0Snapshot(1, "7", "epoch", 4, 0L,
                List.of(entries.getFirst(), new L0Snapshot.Message(2, 10, AiMessage.from(declared)), entries.getLast()), source.turns());
        assertEquals(1, new DecisionEvidenceAssembler().collect(10, withoutExplanation, 3, collector.snapshot(), null).size());
    }

    private Entry explanation(List<ChangeFact> changes, List<BuildFact> builds) {
        return new Entry("ai:epoch:2", SourceKind.MODEL_TEXT, "拆分 src/App.vue 为了复用", 1,
                List.of("src/App.vue"), List.of("write"), changes, builds, 0);
    }

    @Test
    void 计划理由遇到读取计划及构建事实仍可采集且只关联真实文件变更() throws Exception {
        var budget = new FileToolBudgetGuard().newSession();
        var collector = new VueDecisionSourceCollector(budget, new VueTurnTranscriptAccumulator(budget, 0));
        var file = new com.lyw.appgeneration.ai.plan.PlanFile("src/App.vue", "保留移动端品牌入口",
                com.lyw.appgeneration.ai.plan.PlanFileAction.MODIFY, List.of(), com.lyw.appgeneration.ai.plan.PlanFileState.PENDING);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var outputs = List.of(
                com.lyw.appgeneration.ai.tools.PlanToolProtocolSupport.json(com.lyw.appgeneration.ai.tools.PlanToolResult.applied(
                        "makePlan", "plan", 1, "修复品牌入口并保留中文", List.of(file))),
                json.writeValueAsString(com.lyw.appgeneration.ai.tools.FileToolResult.readApplied(
                        "readFile", "src/App.vue", "读取成功", "组件原文")),
                json.writeValueAsString(com.lyw.appgeneration.ai.tools.FileToolResult.applied(
                        "modifyFile", "src/App.vue", true, "标题已修改")),
                json.writeValueAsString(com.lyw.appgeneration.ai.tools.BuildProjectToolResult.completedFailure(
                        1, com.lyw.appgeneration.core.builder.BuildStage.NPM_BUILD,
                        com.lyw.appgeneration.core.builder.VueBuildFailureKind.CODE, false, "MissingBanner.vue 不存在")));
        var names = List.of("makePlan", "readFile", "modifyFile", "buildProject");
        var messages = new java.util.ArrayList<L0Snapshot.Message>();
        messages.add(new L0Snapshot.Message(1, 10, UserMessage.from("不要轮播图")));
        for (int index = 0; index < outputs.size(); index++) {
            var tool = ToolExecutionRequest.builder().id("tool-" + index).name(names.get(index)).arguments("{}").build();
            var ai = new L0Snapshot.Message(2L + index * 2, 10, AiMessage.from(tool));
            var result = new L0Snapshot.Message(3L + index * 2, 10, ToolExecutionResultMessage.from(tool, outputs.get(index)));
            messages.add(ai);
            messages.add(result);
            collector.sourceCommitted(index + 1, "epoch", ai);
            collector.sourceCommitted(index + 1, "epoch", result);
            collector.toolCommitted(index + 1, ToolExecution.builder().request(tool).result(outputs.get(index)).build());
        }
        var source = new L0Snapshot(1, "7", "epoch", 10, 0L, messages,
                List.of(new L0Snapshot.Turn(10, "turn", 1, null, null, null)));
        var assembled = new DecisionEvidenceAssembler().collect(10, source, 9, collector.snapshot(), null);
        var planned = assembled.stream().filter(entry -> entry.kind() == SourceKind.PLAN_SUMMARY).findFirst().orElseThrow();
        assertEquals("修复品牌入口并保留中文", planned.text());
        assertEquals(List.of("tool-2"), planned.implementations().stream().map(ChangeFact::toolRequestId).toList());
        assertEquals(List.of("tool-3"), planned.verifications().stream().map(BuildFact::toolRequestId).toList());
        assertEquals(ValidationStatus.FAILED, planned.validationStatus());
    }
}
