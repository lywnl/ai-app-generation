package com.lyw.appgeneration.ai.memory;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;
import java.util.Objects;

/** MySQL 中已稳定闭合的一个 User/AI 完整回合。 */
public record ConversationTurn(long turnId,
                               long completedThroughId,
                               List<ChatMessage> messages,
                               int tokens) {

    public ConversationTurn {
        if (turnId <= 0L || completedThroughId < turnId) {
            throw new IllegalArgumentException("完整回合 ID 边界无效");
        }
        messages = List.copyOf(Objects.requireNonNull(
                messages, "完整回合消息不能为空"));
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("完整回合消息不能为空");
        }
        if (tokens <= 0) {
            throw new IllegalArgumentException("完整回合 Token 必须大于 0");
        }
    }
}
