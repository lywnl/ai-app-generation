package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.service.MemorySummarySnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 完整历史游标加单个部分前沿的差集与推进；覆盖的是来源，不靠摘要正文猜测。 */
@Component
public final class SummaryCoveragePlanner {
    public List<SummaryUnit> uncovered(SummaryMaterials materials) {
        return materials.units().stream().filter(unit -> !covered(materials.previous(), unit, materials)).toList();
    }

    public boolean covered(MemorySummarySnapshot summary, SummaryUnit unit, SummaryMaterials materials) {
        var turn = materials.turns().stream().filter(value -> value.userHistoryId() == unit.userHistoryId()).findFirst().orElseThrow();
        if (turn.terminalHistoryId() != null && turn.terminalHistoryId() <= summary.lastSummarizedId()) return true;
        SummaryCoverage frontier = summary.coverageState();
        if (frontier == null || frontier.userHistoryId() != unit.userHistoryId()) return false;
        if (unit.kind() == SummaryUnit.Kind.DECISION) return unit.decision().evidenceRevision() <= frontier.evidenceRevision();
        if (unit.kind() == SummaryUnit.Kind.TERMINAL) return frontier.terminalIncluded()
                && Objects.equals(frontier.terminalHistoryId(), unit.terminalHistoryId());
        // 用户原话有独立持久身份；是否在实际请求中保护由请求层决定。
        if (unit.kind() == SummaryUnit.Kind.USER && frontier.coveredSequence() > 0) return true;
        return Objects.equals(frontier.memoryEpoch(), unit.memoryEpoch()) && unit.lastSequence() > 0
                && unit.lastSequence() <= frontier.coveredSequence();
    }

    public SummaryInput input(SummaryMaterials materials, SummaryWindowSelector.Selection selection) {
        List<SummaryUnit> remaining = uncovered(materials);
        var remainingIds = remaining.stream().map(SummaryUnit::sourceId).collect(java.util.stream.Collectors.toSet());
        var selectedIds = new HashSet<String>();
        for (var unit : selection.selected()) {
            if (!remainingIds.contains(unit.sourceId()) || !selectedIds.add(unit.sourceId()) || !unit.complete()) {
                throw new IllegalArgumentException("摘要候选含已覆盖、重复或未完成来源");
            }
        }
        long cursor = materials.previous().lastSummarizedId();
        SummaryCoverage frontier = materials.previous().coverageState();
        var evidence = new LinkedHashMap<Long, DecisionEvidence>();
        var history = new LinkedHashMap<Long, String>();
        boolean stopped = false;
        for (var turn : materials.turns()) {
            var all = remaining.stream().filter(unit -> unit.userHistoryId() == turn.userHistoryId()).toList();
            var chosen = all.stream().filter(unit -> selectedIds.contains(unit.sourceId())).toList();
            if (stopped && !chosen.isEmpty()) throw new IllegalArgumentException("不能越过未覆盖回合推进摘要");
            if (chosen.isEmpty()) {
                if (!all.isEmpty()) stopped = true;
                continue;
            }
            if (turn.evidence() != null) evidence.put(turn.userHistoryId(), turn.evidence());
            addHistory(history, materials, turn.userHistoryId());
            boolean rawGap = false;
            for (var unit : all) {
                if (!unit.raw()) continue;
                if (!selectedIds.contains(unit.sourceId())) rawGap = true;
                else if (rawGap) throw new IllegalArgumentException("不能跨越回合内部尚未覆盖的原文");
            }
            if (turn.closed() && chosen.size() == all.size()) {
                addHistory(history, materials, turn.terminalHistoryId());
                cursor = turn.terminalHistoryId();
                if (frontier != null && frontier.userHistoryId() == turn.userHistoryId()) frontier = null;
                continue;
            }
            SummaryCoverage old = frontier != null && frontier.userHistoryId() == turn.userHistoryId() ? frontier : null;
            long newSequence = chosen.stream().filter(SummaryUnit::raw).mapToLong(SummaryUnit::lastSequence).max().orElse(0);
            String epoch = newSequence > 0 ? turn.memoryEpoch() : old == null ? turn.memoryEpoch() : old.memoryEpoch();
            long coveredSequence = old != null && Objects.equals(epoch, old.memoryEpoch())
                    ? Math.max(old.coveredSequence(), newSequence) : newSequence;
            if (epoch == null) throw new IllegalStateException("部分摘要缺少可验证的来源窗口");
            if (turn.userHistoryId() == materials.currentUserHistoryId() && turn.evidence() == null) {
                throw new IllegalStateException("当前回合原文在摘要前必须留存决策证据");
            }
            long evidenceRevision = old == null ? 0 : old.evidenceRevision();
            boolean allEvidenceChosen = all.stream().filter(unit -> unit.kind() == SummaryUnit.Kind.DECISION)
                    .allMatch(unit -> selectedIds.contains(unit.sourceId()));
            if (allEvidenceChosen && turn.evidence() != null) evidenceRevision = Math.max(evidenceRevision, turn.evidence().revision());
            Long terminalId = turn.terminalHistoryId() != null ? turn.terminalHistoryId() : old == null ? null : old.terminalHistoryId();
            var outcome = turn.terminalOutcome() != null ? turn.terminalOutcome() : old == null ? null : old.terminalOutcome();
            boolean terminalIncluded = old != null && old.terminalIncluded();
            terminalIncluded |= chosen.stream().anyMatch(unit -> unit.kind() == SummaryUnit.Kind.TERMINAL);
            if (terminalIncluded && terminalId != null) addHistory(history, materials, terminalId);
            String fingerprint = epoch.equals(materials.source().memoryEpoch())
                    ? L0SourceOperations.fingerprint(materials.source(), turn.userHistoryId(), coveredSequence, evidenceRevision)
                    : old == null ? null : old.sourceFingerprint();
            frontier = new SummaryCoverage(turn.userHistoryId(), turn.turnId(), epoch, coveredSequence,
                    fingerprint, evidenceRevision, terminalId, outcome, terminalIncluded);
            stopped = true;
        }
        var input = new SummaryInput(materials.appId(), materials.userId(), materials.previous(), materials.source(),
                selection.selected(), cursor, frontier, evidence, history);
        requireMonotonic(materials, input);
        return input;
    }

    private void requireMonotonic(SummaryMaterials materials, SummaryInput next) {
        SummaryCoverage old = materials.previous().coverageState();
        if (old == null) return;
        SummaryCoverage current = next.targetCoverage();
        if (current != null && current.userHistoryId() == old.userHistoryId()) {
            if (current.evidenceRevision() < old.evidenceRevision()
                    || (current.memoryEpoch().equals(old.memoryEpoch()) && current.coveredSequence() < old.coveredSequence())
                    || (old.terminalIncluded() && !current.terminalIncluded())
                    || (old.terminalHistoryId() != null && !old.terminalHistoryId().equals(current.terminalHistoryId()))) {
                throw new IllegalArgumentException("后继摘要不能撤销已有覆盖或终态");
            }
            return;
        }
        var priorTurn = materials.turns().stream().filter(turn -> turn.userHistoryId() == old.userHistoryId()).findFirst().orElse(null);
        Long terminal = priorTurn != null ? priorTurn.terminalHistoryId() : old.terminalHistoryId();
        if (terminal == null || next.targetHistoryCursor() < terminal) {
            throw new IllegalArgumentException("旧部分前沿尚未完整覆盖，不能跳到后续回合");
        }
    }

    private void addHistory(java.util.Map<Long, String> selected, SummaryMaterials materials, long id) {
        String fingerprint = materials.historyFingerprints().get(id);
        if (fingerprint != null) selected.put(id, fingerprint);
    }
}
