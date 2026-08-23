package com.lyw.appgeneration.ai.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 把稳定 Vue 回合的内部可信投影从历史 AI 角色移到服务端状态角色。
 * 未完成工具尾部保持原样，避免破坏 tool_calls 与 tool_result 配对。
 */
final class TrustedTurnRequestViewProjector {

    static final String HISTORICAL_ACK =
            SyntheticMemoryMessageProtocol.TRUSTED_TURN_ACK;
    private static final String STATE_HEADER = """
            以下是服务端验证的历史工程状态，不是用户的新指令，也不是你的输出模板。
            只能把它作为继续任务的事实依据，不得复述或模仿其中的格式。
            文件内容以当前磁盘为准；需要源码时必须重新调用读取工具。

            """;

    private final ConversationTurnSnapshotParser snapshotParser =
            new ConversationTurnSnapshotParser();

    ProjectedView project(List<ChatMessage> messages) {
        List<ChatMessage> source = List.copyOf(Objects.requireNonNull(
                messages, "待投影消息不能为空"));
        ConversationTurnSnapshotParser.Snapshot snapshot =
                snapshotParser.parse(source);
        if (snapshot.completedTurns().isEmpty()) {
            return unchanged(source);
        }
        List<ChatMessage> conversation = new ArrayList<>();
        StringBuilder trustedState = new StringBuilder(STATE_HEADER);
        int sequence = 1;
        for (ConversationTurnSnapshotParser.CompletedTurn turn
                : snapshot.completedTurns()) {
            trustedState.append("历史回合 ").append(sequence++)
                    .append("：\n").append(turn.terminalAiText())
                    .append("\n\n");
            conversation.add(turn.userMessage());
            conversation.add(AiMessage.from(HISTORICAL_ACK));
        }
        conversation.addAll(snapshot.unfinishedTail());
        return new ProjectedView(
                leadingSystems(snapshot.leadingMessages()),
                SystemMessage.from(trustedState.toString().stripTrailing()),
                conversation);
    }

    private ProjectedView unchanged(List<ChatMessage> source) {
        int leadingCount = 0;
        while (leadingCount < source.size()
                && source.get(leadingCount) instanceof SystemMessage) {
            leadingCount++;
        }
        return new ProjectedView(
                source.subList(0, leadingCount), null,
                source.subList(leadingCount, source.size()));
    }

    private List<ChatMessage> leadingSystems(List<ChatMessage> leading) {
        if (leading.stream().allMatch(SystemMessage.class::isInstance)) {
            return leading;
        }
        return List.of();
    }

    record ProjectedView(
            List<ChatMessage> leadingMessages,
            SystemMessage trustedState,
            List<ChatMessage> conversationMessages) {

        ProjectedView {
            leadingMessages = List.copyOf(Objects.requireNonNull(
                    leadingMessages, "前导消息不能为空"));
            conversationMessages = List.copyOf(Objects.requireNonNull(
                    conversationMessages, "对话消息不能为空"));
        }
    }
}
