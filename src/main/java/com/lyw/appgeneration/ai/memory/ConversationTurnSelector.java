package com.lyw.appgeneration.ai.memory;

import java.util.List;
import java.util.Objects;

/** 从最新回合向前选择完整的 L0 保留后缀，绝不拆分单个回合。 */
public class ConversationTurnSelector {

    public RetentionSelection select(
            List<ConversationTurn> turns, int retainedTokenTarget) {
        Objects.requireNonNull(turns, "完整回合列表不能为空");
        if (retainedTokenTarget <= 0) {
            throw new IllegalArgumentException("L0 保留 Token 必须大于 0");
        }
        if (turns.isEmpty()) {
            return new RetentionSelection(List.of(), List.of(), 0L, 0);
        }
        int retainedStart = turns.size();
        long retainedTokens = 0L;
        for (int index = turns.size() - 1; index >= 0; index--) {
            ConversationTurn turn = Objects.requireNonNull(
                    turns.get(index), "完整回合不能为 null");
            long nextTokens = retainedTokens + turn.tokens();
            if (nextTokens > retainedTokenTarget) {
                break;
            }
            retainedTokens = nextTokens;
            retainedStart = index;
        }
        List<ConversationTurn> retained = turns.subList(
                retainedStart, turns.size());
        List<ConversationTurn> compressible = turns.subList(
                0, retainedStart);
        long summarizeThroughId = compressible.isEmpty()
                ? 0L
                : compressible.getLast().completedThroughId();
        return new RetentionSelection(retained, compressible,
                summarizeThroughId, (int) retainedTokens);
    }
}
