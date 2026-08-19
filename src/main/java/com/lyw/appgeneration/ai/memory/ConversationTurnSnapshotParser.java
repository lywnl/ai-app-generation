package com.lyw.appgeneration.ai.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 将无数据库 ID 的 L0 消息解析为稳定完整回合与未完成尾部。 */
public final class ConversationTurnSnapshotParser {

    public Snapshot parse(List<ChatMessage> messages) {
        List<ChatMessage> source = List.copyOf(Objects.requireNonNull(
                messages, "L0 消息不能为空"));
        int firstUser = firstUserIndex(source);
        if (firstUser < 0) {
            return new Snapshot(source, List.of(), List.of());
        }
        List<CompletedTurn> completedTurns = new ArrayList<>();
        int cursor = firstUser;
        while (cursor < source.size()) {
            ChatMessage boundary = source.get(cursor);
            if (!(boundary instanceof UserMessage)) {
                return new Snapshot(source.subList(0, firstUser),
                        completedTurns, source.subList(cursor, source.size()));
            }
            int terminalAi = terminalAiIndex(source, cursor + 1);
            if (terminalAi < 0) {
                return new Snapshot(source.subList(0, firstUser),
                        completedTurns, source.subList(cursor, source.size()));
            }
            AiMessage aiMessage = (AiMessage) source.get(terminalAi);
            completedTurns.add(new CompletedTurn(
                    source.subList(cursor, terminalAi + 1),
                    (UserMessage) boundary,
                    Objects.toString(aiMessage.text(), "")));
            cursor = terminalAi + 1;
        }
        return new Snapshot(source.subList(0, firstUser),
                completedTurns, List.of());
    }

    private int firstUserIndex(List<ChatMessage> messages) {
        for (int index = 0; index < messages.size(); index++) {
            if (messages.get(index) instanceof UserMessage) {
                return index;
            }
        }
        return -1;
    }

    private int terminalAiIndex(
            List<ChatMessage> messages, int startIndex) {
        for (int index = startIndex; index < messages.size(); index++) {
            ChatMessage message = messages.get(index);
            if (message instanceof UserMessage) {
                return -1;
            }
            if (message instanceof AiMessage aiMessage
                    && !aiMessage.hasToolExecutionRequests()) {
                return index;
            }
        }
        return -1;
    }

    public record Snapshot(
            List<ChatMessage> leadingMessages,
            List<CompletedTurn> completedTurns,
            List<ChatMessage> unfinishedTail) {

        public Snapshot {
            leadingMessages = List.copyOf(Objects.requireNonNull(
                    leadingMessages, "前导消息不能为空"));
            completedTurns = List.copyOf(Objects.requireNonNull(
                    completedTurns, "完整回合不能为空"));
            unfinishedTail = List.copyOf(Objects.requireNonNull(
                    unfinishedTail, "未完成尾部不能为空"));
        }

        public boolean hasUnfinishedTail() {
            return !unfinishedTail.isEmpty();
        }
    }

    public record CompletedTurn(
            List<ChatMessage> messages,
            UserMessage userMessage,
            String terminalAiText) {

        public CompletedTurn {
            messages = List.copyOf(Objects.requireNonNull(
                    messages, "完整回合消息不能为空"));
            userMessage = Objects.requireNonNull(
                    userMessage, "用户消息不能为空");
            terminalAiText = Objects.requireNonNull(
                    terminalAiText, "终态 AI 文本不能为空");
            if (messages.isEmpty()) {
                throw new IllegalArgumentException("完整回合消息不能为空");
            }
        }

        public String userText() {
            return userMessage.hasSingleText()
                    ? Objects.toString(userMessage.singleText(), "") : "";
        }
    }
}
