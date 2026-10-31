package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.config.MemoryTokenProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 从最新完整单元向前选择近期原文，只生成候选，不读写任何存储或启动摘要。 */
@Component
public final class SummaryWindowSelector {
    private final ChatTokenEstimator estimator;
    private final int target;

    public SummaryWindowSelector(ChatTokenEstimator estimator, MemoryTokenProperties properties) {
        this.estimator = estimator;
        this.target = properties.getL0RetainedTokens();
    }

    public Selection select(SummaryMaterials materials, List<SummaryUnit> uncovered) {
        List<SummaryUnit> raw = uncovered.stream().filter(SummaryUnit::raw).toList();
        var retained = new HashSet<String>();
        int newestBatch = -1;
        for (int index = 0; index < raw.size(); index++) {
            if (raw.get(index).kind() == SummaryUnit.Kind.TOOL_BATCH) newestBatch = index;
        }
        long tokens = 0L;
        boolean keptOrdinary = false;
        int firstRetained = raw.size();
        for (int index = raw.size() - 1; index >= 0; index--) {
            SummaryUnit unit = raw.get(index);
            if (protectedUser(unit, materials)) {
                retained.add(unit.sourceId());
                continue;
            }
            int cost = unit.tokens(estimator);
            boolean required = !unit.complete() || !keptOrdinary || (newestBatch >= 0 && index >= newestBatch);
            if (!required && tokens + cost > target) break;
            retained.add(unit.sourceId());
            tokens += cost;
            keptOrdinary = true;
            firstRetained = index;
        }
        // 原话独立保留，即使它比软窗口切分点更早。
        raw.stream().filter(unit -> protectedUser(unit, materials)).forEach(unit -> retained.add(unit.sourceId()));
        var selectedRaw = new HashSet<String>();
        long frontier = 0;
        boolean removesOriginal = false;
        for (int index = 0; index < firstRetained; index++) {
            SummaryUnit unit = raw.get(index);
            if (!unit.complete()) break;
            selectedRaw.add(unit.sourceId());
            frontier = unit.userHistoryId();
            removesOriginal |= !protectedUser(unit, materials);
        }
        long firstRetainedUser = firstRetained < raw.size() ? raw.get(firstRetained).userHistoryId() : Long.MAX_VALUE;
        var selected = new ArrayList<SummaryUnit>();
        boolean hasOlderMetadata = false;
        for (SummaryUnit unit : uncovered) {
            if (unit.raw()) {
                if (selectedRaw.contains(unit.sourceId())) selected.add(unit);
            } else if (unit.userHistoryId() <= frontier || unit.userHistoryId() < firstRetainedUser) {
                selected.add(unit);
                hasOlderMetadata |= unit.userHistoryId() != materials.currentUserHistoryId();
            }
        }
        // 只摘要受保护的当前原话不能释放原文，也不应造成额外固定摘要调用。
        if (!removesOriginal && !hasOlderMetadata) selected.clear();
        return new Selection(selected, Set.copyOf(retained), (int) Math.min(Integer.MAX_VALUE, tokens));
    }

    private boolean protectedUser(SummaryUnit unit, SummaryMaterials materials) {
        return unit.kind() == SummaryUnit.Kind.USER && unit.userHistoryId() == materials.currentUserHistoryId();
    }

    public record Selection(List<SummaryUnit> selected, Set<String> retainedSourceIds, int retainedRawTokens) {
        public Selection { selected = List.copyOf(selected); retainedSourceIds = Set.copyOf(retainedSourceIds); }
        public boolean hasWork() { return !selected.isEmpty(); }
    }
}
