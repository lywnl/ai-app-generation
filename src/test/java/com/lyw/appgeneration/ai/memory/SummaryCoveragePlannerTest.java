package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;
import com.lyw.appgeneration.service.MemorySummarySnapshot;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SummaryCoveragePlannerTest {
    private final SummaryCoveragePlanner planner = new SummaryCoveragePlanner();

    @Test
    void 同一任务依次只处理一至二十和二十一至三十五和三十六至五十批次() {
        L0Snapshot source = source();
        MemorySummarySnapshot summary = MemorySummarySnapshot.empty();
        int[] ends = {20, 35, 50};
        int previousEnd = 0;
        for (int end : ends) {
            var materials = materials(source, summary, false, 10, end * 2L + 1);
            var selected = planner.uncovered(materials).stream().filter(unit -> !unit.raw() || unit.lastSequence() <= end * 2L + 1).toList();
            var toolUnits = selected.stream().filter(unit -> unit.kind() == SummaryUnit.Kind.TOOL_BATCH).toList();
            assertEquals(end - previousEnd, toolUnits.size());
            assertEquals(previousEnd * 2L + 2, toolUnits.getFirst().firstSequence());
            var input = planner.input(materials, new SummaryWindowSelector.Selection(selected, Set.of(), 0));
            summary = input.committed(L0SourceOperationsTest.SUMMARY);
            assertEquals(0, summary.lastSummarizedId());
            assertEquals(end * 2L + 1, summary.coverageState().coveredSequence());
            source = L0SourceOperations.prune(source, summary, 10);
            assertEquals(summary.summaryRevision(), source.appliedSummaryRevision());
            previousEnd = end;
        }
        assertEquals(3, summary.summaryRevision());
        assertEquals(21, source.entries().size(), "保留原用户加最后十个完整批次");
    }

    @Test
    void 已压缩前沿结束后只加入新终态并在剩余来源完成后合并完整游标() {
        L0Snapshot source = source();
        var initial = materials(source, MemorySummarySnapshot.empty(), false, 10, 41);
        var prefix = planner.uncovered(initial).stream().filter(unit -> !unit.raw() || unit.lastSequence() <= 41).toList();
        var summary = planner.input(initial, new SummaryWindowSelector.Selection(prefix, Set.of(), 0)).committed(L0SourceOperationsTest.SUMMARY);
        source = L0SourceOperations.prune(source, summary, 10);
        source = L0SourceOperations.closeTurn(source, 10, "turn", 20, ChatMemoryOutcome.FAILED);
        var nextUser = TurnRequestBoundary.of("分析上轮错误", List.of(), 30, "next");
        source = L0SourceOperations.append(source, nextUser.userMessage(), nextUser);
        var endedSummary = new MemorySummarySnapshot(summary.summary(), 0, 2, 2,
                summary.coverageState().withTerminal(20, ChatMemoryOutcome.FAILED));
        var next = materials(source, endedSummary, true, 30, 121);
        var delta = planner.uncovered(next);
        assertEquals(40, delta.stream().filter(unit -> unit.kind() == SummaryUnit.Kind.TOOL_BATCH).count());
        assertEquals(1, delta.stream().filter(unit -> unit.kind() == SummaryUnit.Kind.TERMINAL).count());
        var input = planner.input(next, new SummaryWindowSelector.Selection(delta.stream().filter(unit -> unit.userHistoryId() == 10).toList(), Set.of(), 0));
        assertEquals(20, input.targetHistoryCursor());
        assertNull(input.targetCoverage());
        assertEquals(30, next.turns().getLast().userHistoryId());
    }

    @Test
    void 证据新旧替代只合并变化条目且不跨过未选中的原文() {
        L0Snapshot source = source();
        var base = materials(source, MemorySummarySnapshot.empty(), false, 10, 41);
        var prefix = planner.uncovered(base).stream().filter(unit -> !unit.raw() || unit.lastSequence() <= 41).toList();
        var summary = planner.input(base, new SummaryWindowSelector.Selection(prefix, Set.of(), 0)).committed(L0SourceOperationsTest.SUMMARY);
        var next = materials(source, summary, false, 10, 71);
        var delta = planner.uncovered(next);
        assertFalse(delta.stream().anyMatch(unit -> unit.sourceId().equals("decision:10:user:10:1")));
        var last = delta.stream().filter(SummaryUnit::raw).reduce((a, b) -> b).orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> planner.input(next,
                new SummaryWindowSelector.Selection(List.of(last), Set.of(), 0)));
    }

    @Test
    void 新决策修订只补入替代说明而不重复已覆盖工具前缀() {
        var source = source();
        var base = materials(source, MemorySummarySnapshot.empty(), false, 10, 41);
        var prefix = planner.uncovered(base).stream().filter(unit -> !unit.raw() || unit.lastSequence() <= 41).toList();
        var first = planner.input(base, new SummaryWindowSelector.Selection(prefix, Set.of(), 0)).committed(L0SourceOperationsTest.SUMMARY);
        var oldTurn = base.turns().getFirst();
        var change = new DecisionEvidence.Entry("plan:new:reason", DecisionEvidence.SourceKind.PLAN_REASON,
                "用列表替代轮播，避免遮挡移动端内容", 2, List.of(), List.of("plan-new"), List.of(), List.of(), 0);
        var revised = oldTurn.evidence().merge(List.of(change), source.memoryEpoch(), 41, 2, 1000);
        var unit = new SummaryUnit("decision:10:plan:new:reason:2", 10, SummaryUnit.Kind.DECISION, null, 0, 0,
                List.of(), null, null, revised.entries().getLast());
        var units = new ArrayList<>(oldTurn.units()); units.add(unit);
        var materials = new SummaryMaterials(7, 9, 10, first, source,
                List.of(new SummaryMaterials.Turn(10, "turn", source.memoryEpoch(), 121, null, null, revised, units)), Map.of());
        var delta = planner.uncovered(materials);
        assertFalse(delta.stream().filter(SummaryUnit::raw).anyMatch(item -> item.lastSequence() <= 41));
        var input = planner.input(materials, new SummaryWindowSelector.Selection(List.of(unit), Set.of(), 0));
        assertEquals(41, input.targetCoverage().coveredSequence());
        assertEquals(2, input.targetCoverage().evidenceRevision());
        assertEquals(List.of(unit), input.selected());
    }

    private L0Snapshot source() {
        var boundary = TurnRequestBoundary.of("不要轮播图", List.of(), 10, "turn");
        var source = L0SourceOperations.append(L0Snapshot.empty("7", 0), boundary.userMessage(), boundary);
        for (int batch = 1; batch <= 60; batch++) {
            var request = ToolExecutionRequest.builder().id("batch-" + batch).name("readFile").arguments("{}").build();
            source = L0SourceOperations.append(source, AiMessage.from(request), null);
            source = L0SourceOperations.append(source, ToolExecutionResultMessage.from(request, "诊断批次" + batch), null);
        }
        return source;
    }

    @Test
    void 冷重建后的已覆盖旧前沿可随下一份增量归并而不重复摘要() {
        var oldEvidence = DecisionEvidence.empty(10, "old").merge(List.of(new DecisionEvidence.Entry("user:10",
                DecisionEvidence.SourceKind.USER, "", 0, List.of(), List.of(), List.of(), List.of(), 0)),
                "old-epoch", 99, 1, 100);
        var previous = new MemorySummarySnapshot(L0SourceOperationsTest.SUMMARY, 0, 5, 2,
                new SummaryCoverage(10, "old", "old-epoch", 99, "a".repeat(64), 1, 20L, ChatMemoryOutcome.FAILED, true));
        var source = L0SourceOperations.importClosedHistory(L0Snapshot.empty("7", 5),
                new ConversationTurn(10, 20, List.of(UserMessage.from("旧要求"), AiMessage.from("真实失败")), 100), "old", ChatMemoryOutcome.FAILED);
        var boundary = TurnRequestBoundary.of("当前要求", List.of(), 30, "current");
        source = L0SourceOperations.append(source, boundary.userMessage(), boundary);
        source = L0SourceOperations.append(source, AiMessage.from("当前增量说明"), null);
        var oldUser = new SummaryUnit("user:10", 10, SummaryUnit.Kind.USER, source.memoryEpoch(), 1, 1,
                List.of(source.messages().getFirst()), null, null, null);
        var terminal = new SummaryUnit("terminal:20", 10, SummaryUnit.Kind.TERMINAL, null, 0, 0,
                List.of(AiMessage.from("真实失败")), 20L, ChatMemoryOutcome.FAILED, null);
        var oldDecision = new SummaryUnit("decision:10:user:10:1", 10, SummaryUnit.Kind.DECISION, null, 0, 0,
                List.of(), null, null, oldEvidence.entries().getFirst());
        var currentUnits = List.of(new SummaryUnit("user:30", 30, SummaryUnit.Kind.USER, source.memoryEpoch(), 3, 3,
                        List.of(boundary.userMessage()), null, null, null),
                new SummaryUnit("l0:new:4", 30, SummaryUnit.Kind.MODEL_TEXT, source.memoryEpoch(), 4, 4,
                        List.of(source.messages().getLast()), null, null, null));
        var materials = new SummaryMaterials(7, 9, 30, previous, source, List.of(
                new SummaryMaterials.Turn(10, "old", source.memoryEpoch(), 2, 20L, ChatMemoryOutcome.FAILED,
                        oldEvidence, List.of(oldUser, terminal, oldDecision)),
                new SummaryMaterials.Turn(30, "current", source.memoryEpoch(), 4, null, null,
                        DecisionEvidence.empty(30, "current"), currentUnits)), Map.of(10L, "old-user", 20L, "old-ai", 30L, "user"));
        assertEquals(currentUnits, planner.uncovered(materials));
        var input = planner.input(materials, new SummaryWindowSelector.Selection(currentUnits, Set.of(), 0));
        assertEquals(20, input.targetHistoryCursor());
        assertEquals(30, input.targetCoverage().userHistoryId());
        assertEquals(currentUnits, input.selected());
        assertEquals(oldEvidence, input.evidenceSnapshots().get(10L), "归并前仍须核对旧证据修订没有新增");
    }

    private SummaryMaterials materials(L0Snapshot source, MemorySummarySnapshot previous, boolean closed, long current, long evidenceThrough) {
        var evidence = DecisionEvidence.empty(10, "turn").merge(List.of(new DecisionEvidence.Entry("user:10",
                DecisionEvidence.SourceKind.USER, "", 0, List.of(), List.of(), List.of(), List.of(), 0)),
                source.memoryEpoch(), evidenceThrough, 1, 1000);
        var turns = new ArrayList<SummaryMaterials.Turn>();
        for (var parsed : new L0SourceParser().parse(source).turns()) {
            var units = new ArrayList<SummaryUnit>();
            long identity = parsed.boundary().userHistoryId();
            for (var unit : parsed.units()) {
                var kind = unit.messages().getFirst() instanceof UserMessage ? SummaryUnit.Kind.USER : SummaryUnit.Kind.TOOL_BATCH;
                units.add(new SummaryUnit(kind == SummaryUnit.Kind.USER ? "user:" + identity : "l0:" + unit.firstSequence(), identity,
                        kind, source.memoryEpoch(), unit.firstSequence(), unit.lastSequence(), unit.messages(), null, null, null));
            }
            if (closed && identity == 10) units.add(new SummaryUnit("terminal:20", 10, SummaryUnit.Kind.TERMINAL, null, 0, 0,
                    List.of(AiMessage.from("真实构建失败")), 20L, ChatMemoryOutcome.FAILED, null));
            if (identity == 10) units.add(new SummaryUnit("decision:10:user:10:1", 10, SummaryUnit.Kind.DECISION, null, 0, 0,
                    List.of(), null, null, evidence.entries().getFirst()));
            turns.add(new SummaryMaterials.Turn(identity, parsed.boundary().turnId(), source.memoryEpoch(), 121,
                    parsed.boundary().terminalHistoryId(), parsed.boundary().terminalOutcome(), identity == 10 ? evidence : null, units));
        }
        return new SummaryMaterials(7, 9, current, previous, source, turns, Map.of());
    }
}
