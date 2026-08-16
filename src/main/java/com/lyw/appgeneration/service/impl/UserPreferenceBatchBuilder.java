package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.memory.ChatTokenEstimator;
import com.lyw.appgeneration.ai.memory.UserPreferencePromptBuilder;
import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.model.enums.ChatHistoryMessageTypeEnum;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 识别稳定完整回合，并按统一 Token 预算构建 L2 偏好抽取批次。 */
final class UserPreferenceBatchBuilder {

    private final ChatTokenEstimator tokenEstimator;
    private final MemoryTokenProperties tokenProperties;

    UserPreferenceBatchBuilder(ChatTokenEstimator tokenEstimator,
                               MemoryTokenProperties tokenProperties) {
        this.tokenEstimator = Objects.requireNonNull(
                tokenEstimator, "Token 估算器不能为空");
        this.tokenProperties = Objects.requireNonNull(
                tokenProperties, "Token 配置不能为空");
    }

    Session start(long lastId, String existingPreferences) {
        if (lastId < 0L) {
            throw new IllegalArgumentException("L2 抽取游标不能为负数");
        }
        String existing = Objects.toString(existingPreferences, "");
        String basePrompt = UserPreferencePromptBuilder.build(
                existing, "", List.of());
        if (tokenEstimator.estimateText(basePrompt)
                > tokenProperties.getAsyncCompressionThreshold()) {
            throw new IllegalStateException(
                    "L2 偏好基础 Prompt 超出 Token 上限");
        }
        return new Session(lastId, existing);
    }

    final class Session {

        private final String existingPreferences;
        private final List<StableTurn> selectedTurns = new ArrayList<>();
        private ChatHistory pendingUser;
        private boolean invalidUserSequence;
        private long scanCursor;
        private long completedThroughId;
        private boolean finished;

        private Session(long lastId, String existingPreferences) {
            this.scanCursor = lastId;
            this.completedThroughId = lastId;
            this.existingPreferences = existingPreferences;
        }

        long nextCursor() {
            return scanCursor;
        }

        PageResult acceptPage(List<ChatHistory> rows,
                              boolean terminalPage) {
            if (finished) {
                throw new IllegalStateException("L2 偏好批次已经构建完成");
            }
            if (rows == null) {
                throw new IllegalStateException("数据库返回了 null 对话批次");
            }
            List<SkippedTurn> skippedTurns = new ArrayList<>();
            for (ChatHistory row : rows) {
                long rowId = requireNextHistoryId(row);
                scanCursor = rowId;
                if (isUserMessage(row)) {
                    if (pendingUser != null || invalidUserSequence) {
                        pendingUser = null;
                        invalidUserSequence = true;
                        continue;
                    }
                    pendingUser = row;
                    continue;
                }
                if (!isAiMessage(row)) {
                    pendingUser = null;
                    invalidUserSequence = false;
                    continue;
                }
                if (invalidUserSequence) {
                    invalidUserSequence = false;
                    continue;
                }
                if (pendingUser == null) {
                    continue;
                }
                StableTurn turn = new StableTurn(
                        requireHistoryId(pendingUser), rowId,
                        Objects.toString(pendingUser.getMessage(), ""));
                pendingUser = null;
                TurnDecision decision = addTurn(turn);
                if (decision == TurnDecision.BATCH_FULL) {
                    return finish(true, skippedTurns);
                }
                if (decision == TurnDecision.SKIPPED) {
                    skippedTurns.add(new SkippedTurn(
                            turn.turnId(), turn.completedThroughId()));
                }
            }
            if (rows.isEmpty() || terminalPage) {
                return finish(false, skippedTurns);
            }
            return new PageResult(null, List.copyOf(skippedTurns));
        }

        private TurnDecision addTurn(StableTurn turn) {
            List<StableTurn> tentative = new ArrayList<>(selectedTurns);
            tentative.add(turn);
            Batch tentativeBatch = toBatch(tentative,
                    turn.completedThroughId(), false);
            if (tokenEstimator.estimateText(tentativeBatch.prompt())
                    <= tokenProperties.getAsyncCompressionThreshold()) {
                selectedTurns.add(turn);
                completedThroughId = turn.completedThroughId();
                return TurnDecision.SELECTED;
            }
            if (!selectedTurns.isEmpty()) {
                return TurnDecision.BATCH_FULL;
            }
            completedThroughId = turn.completedThroughId();
            return TurnDecision.SKIPPED;
        }

        private PageResult finish(boolean hasMore,
                                  List<SkippedTurn> skippedTurns) {
            finished = true;
            return new PageResult(
                    toBatch(selectedTurns, completedThroughId, hasMore),
                    List.copyOf(skippedTurns));
        }

        private Batch toBatch(List<StableTurn> turns,
                              long completedId,
                              boolean hasMore) {
            List<Long> turnIds = turns.stream()
                    .map(StableTurn::turnId)
                    .toList();
            String prompt = UserPreferencePromptBuilder.build(
                    existingPreferences, renderUserEvidence(turns), turnIds);
            return new Batch(turnIds, prompt, completedId, hasMore);
        }

        private String renderUserEvidence(List<StableTurn> turns) {
            StringBuilder evidence = new StringBuilder();
            for (StableTurn turn : turns) {
                if (!evidence.isEmpty()) {
                    evidence.append("\n\n");
                }
                evidence.append("turnId=").append(turn.turnId())
                        .append("\n用户:").append(turn.userText());
            }
            return evidence.toString();
        }

        private long requireNextHistoryId(ChatHistory row) {
            long rowId = requireHistoryId(row);
            if (rowId <= scanCursor) {
                throw new IllegalStateException("对话历史 ID 未严格递增");
            }
            return rowId;
        }
    }

    record Batch(List<Long> turnIds,
                 String prompt,
                 long completedThroughId,
                 boolean hasMore) {

        Batch {
            turnIds = List.copyOf(turnIds);
        }

        boolean hasTurns() {
            return !turnIds.isEmpty();
        }
    }

    record PageResult(Batch batch, List<SkippedTurn> skippedTurns) {

        PageResult {
            skippedTurns = List.copyOf(skippedTurns);
        }

        boolean finished() {
            return batch != null;
        }
    }

    record SkippedTurn(long turnId, long completedThroughId) {
    }

    private record StableTurn(
            long turnId, long completedThroughId, String userText) {
    }

    private enum TurnDecision {
        SELECTED,
        SKIPPED,
        BATCH_FULL
    }

    private static long requireHistoryId(ChatHistory row) {
        if (row == null || row.getId() == null || row.getId() <= 0L) {
            throw new IllegalStateException("对话历史 ID 必须为正数");
        }
        return row.getId();
    }

    private static boolean isUserMessage(ChatHistory history) {
        return ChatHistoryMessageTypeEnum.USER.getValue()
                .equals(history.getMessageType());
    }

    private static boolean isAiMessage(ChatHistory history) {
        return ChatHistoryMessageTypeEnum.AI.getValue()
                .equals(history.getMessageType());
    }
}
