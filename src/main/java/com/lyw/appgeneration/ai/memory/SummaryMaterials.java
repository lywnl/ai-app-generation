package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;
import com.lyw.appgeneration.service.MemorySummarySnapshot;

import java.util.List;
import java.util.Map;

/** 已对齐而尚未裁剪的全部候选材料。分页读取完成之前不发布半份材料集。 */
public record SummaryMaterials(long appId, long userId, long currentUserHistoryId,
                                MemorySummarySnapshot previous, L0Snapshot source,
                                List<Turn> turns, Map<Long, String> historyFingerprints) {
    public SummaryMaterials { turns = List.copyOf(turns); historyFingerprints = Map.copyOf(historyFingerprints); }
    public List<SummaryUnit> units() { return turns.stream().flatMap(turn -> turn.units().stream()).toList(); }

    public record Turn(long userHistoryId, String turnId, String memoryEpoch, long endSequence,
                        Long terminalHistoryId, ChatMemoryOutcome terminalOutcome,
                        DecisionEvidence evidence, List<SummaryUnit> units) {
        public Turn { units = List.copyOf(units); }
        public boolean closed() { return terminalHistoryId != null; }
    }
}
