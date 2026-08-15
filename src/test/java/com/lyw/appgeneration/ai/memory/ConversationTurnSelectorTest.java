package com.lyw.appgeneration.ai.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationTurnSelectorTest {

    private final ConversationTurnSelector selector = new ConversationTurnSelector();

    @Test
    void keepsNewestWholeTurnsWithinTargetAndCompressesOlderPrefix() {
        ConversationTurn first = turn(1L, 2L, 4_000);
        ConversationTurn second = turn(3L, 4L, 5_000);
        ConversationTurn third = turn(5L, 6L, 6_000);

        RetentionSelection selection = selector.select(
                List.of(first, second, third), 12_288);

        assertEquals(List.of(second, third), selection.retained());
        assertEquals(List.of(first), selection.compressible());
        assertEquals(2L, selection.summarizeThroughId());
        assertEquals(11_000, selection.retainedTokens());
    }

    @Test
    void acceptsExactBoundaryWithoutSplittingTurn() {
        ConversationTurn first = turn(1L, 2L, 2_000);
        ConversationTurn second = turn(3L, 4L, 6_000);
        ConversationTurn third = turn(5L, 6L, 6_288);

        RetentionSelection selection = selector.select(
                List.of(first, second, third), 12_288);

        assertEquals(List.of(second, third), selection.retained());
        assertEquals(List.of(first), selection.compressible());
        assertEquals(12_288, selection.retainedTokens());
    }

    @Test
    void oversizedLatestCompletedTurnIsEntirelyCompressible() {
        ConversationTurn oversized = turn(7L, 8L, 12_289);

        RetentionSelection selection = selector.select(
                List.of(oversized), 12_288);

        assertTrue(selection.retained().isEmpty());
        assertEquals(List.of(oversized), selection.compressible());
        assertEquals(8L, selection.summarizeThroughId());
        assertEquals(0, selection.retainedTokens());
    }

    @Test
    void emptyHistoryProducesNoSummaryBoundary() {
        RetentionSelection selection = selector.select(List.of(), 12_288);

        assertTrue(selection.retained().isEmpty());
        assertTrue(selection.compressible().isEmpty());
        assertEquals(0L, selection.summarizeThroughId());
        assertEquals(0, selection.retainedTokens());
    }

    private ConversationTurn turn(long userId, long aiId, int tokens) {
        return new ConversationTurn(userId, aiId, List.of(
                UserMessage.from("用户-" + userId),
                AiMessage.from("回复-" + aiId)), tokens);
    }
}
