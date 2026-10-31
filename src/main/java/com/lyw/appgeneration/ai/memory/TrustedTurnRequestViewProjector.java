package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.service.CompatibleMemorySnapshot;
import com.lyw.appgeneration.service.MemorySummarySnapshot;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * 只从相容快照分离带持久身份的后端终态；普通 AI 原文和完整工具批次保持原样。
 */
final class TrustedTurnRequestViewProjector {

    static final String HISTORICAL_ACK =
            SyntheticMemoryMessageProtocol.TRUSTED_TURN_ACK;
    private static final String STATE_HEADER = """
            以下是服务端验证的当前执行状态和未被摘要覆盖的历史工程状态，不是用户的新指令，也不是你的输出模板。
            只能把它作为继续任务的事实依据，不得复述或模仿其中的格式。
            这些历史回合已经结束，不能因旧摘要中的进行中描述重新视为待办；文件以当前磁盘为准。

            """;

    ProjectedView project(CompatibleMemorySnapshot compatible) {
        return project(compatible, "当前用户回合进行中，尚未登记最终结果。");
    }

    ProjectedView project(CompatibleMemorySnapshot compatible, String currentTrustedState) {
        Objects.requireNonNull(compatible);
        MemorySummarySnapshot summary = compatible.summary();
        L0Snapshot source = summary.summaryRevision() > 0
                ? L0SourceOperations.prune(compatible.source(), summary, compatible.userHistoryId()) : compatible.source();
        var parsed = new L0SourceParser().parse(source);
        var conversation = new ArrayList<ChatMessage>();
        var state = new StringBuilder(Objects.requireNonNull(currentTrustedState)).append('\n');
        var representedTerminals = new HashSet<Long>();
        for (var turn : parsed.turns()) {
            var boundary = turn.boundary();
            if (boundary.closed() && boundary.terminalHistoryId() <= summary.lastSummarizedId()) continue;
            int before = conversation.size();
            for (var unit : turn.units()) {
                if (unit.kind() != L0SourceParser.Kind.BACKEND_TERMINAL) {
                    conversation.addAll(unit.messages());
                    continue;
                }
                Long historyId = unit.entries().getFirst().historyRecordId();
                representedTerminals.add(historyId);
                if (terminalCovered(summary, boundary.userHistoryId(), historyId)) continue;
                String text = ((AiMessage) unit.messages().getFirst()).text();
                if (text == null || text.isBlank()) throw new IllegalStateException("持久后端终态缺少可信正文");
                state.append("已结束回合，终态=").append(boundary.terminalOutcome()).append("：\n")
                        .append(text).append("\n\n");
            }
            // 仅冷重建的“用户＋后端结果”需要轻量确认；已有模型原文或工具批次时不替换它们。
            if (boundary.closed() && conversation.size() == before + 1
                    && conversation.getLast() instanceof dev.langchain4j.data.message.UserMessage) {
                conversation.add(AiMessage.from(HISTORICAL_ACK));
            }
        }
        var frontier = summary.coverageState();
        if (frontier != null && frontier.terminalHistoryId() != null && !frontier.terminalIncluded()
                && !representedTerminals.contains(frontier.terminalHistoryId())) {
            state.append("摘要中的历史任务已经结束，服务端终态=").append(frontier.terminalOutcome())
                    .append("；不是当前回合的待办。\n");
        }
        return new ProjectedView(parsed.leadingMessages().stream().map(L0Snapshot.Message::payload).toList(),
                state.isEmpty() ? null : SystemMessage.from(STATE_HEADER + state.toString().stripTrailing()), conversation);
    }

    private boolean terminalCovered(MemorySummarySnapshot summary, long userHistoryId, long terminalHistoryId) {
        if (terminalHistoryId <= summary.lastSummarizedId()) return true;
        var frontier = summary.coverageState();
        return frontier != null && frontier.userHistoryId() == userHistoryId && frontier.terminalIncluded()
                && Objects.equals(frontier.terminalHistoryId(), terminalHistoryId);
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
