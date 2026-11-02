package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.service.CompatibleMemorySnapshot;
import com.lyw.appgeneration.service.MemorySummarySnapshot;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

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
            进度必须与所属用户回合对应；摘要里的“当前”或“上一轮”不能改变该进度的来源归属。
            内部回合 ID 用于来源对齐，无需在面向用户的复盘中复述。

            """;

    ProjectedView project(CompatibleMemorySnapshot compatible) {
        return project(compatible, "当前用户回合进行中，尚未登记最终结果。");
    }

    /** 计划判定和最终消息投影共享同一可见来源规则，尚未物理回收的原文也不能越过可靠覆盖。 */
    static L0SourceParser.Parsed visibleSource(CompatibleMemorySnapshot compatible) {
        Objects.requireNonNull(compatible);
        MemorySummarySnapshot summary = compatible.summary();
        L0Snapshot source = summary.summaryRevision() > 0
                ? L0SourceOperations.prune(compatible.source(), summary, compatible.userHistoryId()) : compatible.source();
        var parsed = new L0SourceParser().parse(source);
        // 旧版摘要没有 summaryRevision，仍会在请求中隐藏游标已覆盖的完整回合。
        // 该过滤也必须共享，否则计划检查会误把最终不发送的旧工具结果当作可见依据。
        return new L0SourceParser.Parsed(parsed.leadingMessages(), parsed.turns().stream()
                .filter(turn -> !turn.closed() || turn.boundary().terminalHistoryId() > summary.lastSummarizedId()).toList());
    }

    ProjectedView project(CompatibleMemorySnapshot compatible, String currentTrustedState) {
        var parsed = visibleSource(compatible);
        MemorySummarySnapshot summary = compatible.summary();
        var projectedOriginals = new java.util.HashMap<Long, List<ChatMessage>>();
        var state = new StringBuilder(Objects.requireNonNull(currentTrustedState)).append('\n');
        appendSummaryIdentity(state, compatible);
        var previous = compatible.source().turns().stream()
                .filter(turn -> turn.userHistoryId() < compatible.userHistoryId())
                .reduce((earlier, later) -> later).orElse(null);
        var visibleUsers = new ArrayList<Long>();
        var representedTerminals = new HashSet<Long>();
        for (var turn : parsed.turns()) {
            var boundary = turn.boundary();
            var originals = turn.units().stream().filter(unit -> unit.kind() != L0SourceParser.Kind.BACKEND_TERMINAL)
                    .flatMap(unit -> unit.entries().stream()).toList();
            for (var unit : turn.units()) {
                if (unit.kind() != L0SourceParser.Kind.BACKEND_TERMINAL) {
                    for (var entry : unit.entries()) projectedOriginals.put(entry.sequence(), List.of(entry.payload()));
                    if (unit.messages().getFirst() instanceof UserMessage) visibleUsers.add(boundary.userHistoryId());
                    continue;
                }
                Long historyId = unit.entries().getFirst().historyRecordId();
                representedTerminals.add(historyId);
                if (terminalCovered(summary, boundary.userHistoryId(), historyId)) continue;
                String text = ((AiMessage) unit.messages().getFirst()).text();
                if (text == null || text.isBlank()) throw new IllegalStateException("持久后端终态缺少可信正文");
                state.append(previous != null && boundary.userHistoryId() == previous.userHistoryId()
                                ? "上一用户回合 userHistoryId=" : "历史用户回合 userHistoryId=")
                        .append(boundary.userHistoryId()).append("，终态=").append(boundary.terminalOutcome()).append("。\n");
                state.append("已结束回合，终态=").append(boundary.terminalOutcome()).append("：\n")
                        .append(text).append("\n\n");
                if (boundary.terminalOutcome() == com.lyw.appgeneration.model.enums.ChatMemoryOutcome.SYSTEM_ERROR
                        && turn.units().stream().noneMatch(sourceUnit -> sourceUnit.kind() == L0SourceParser.Kind.TOOL_BATCH)) {
                    state.append("本请求未保留该回合的原始工具轨迹；只能依据该回合已保存的结果或归属于它的摘要判断进度。")
                            .append("无法确认时说明未知，不得借用其他用户回合的完成批次或把缺失轨迹说成未执行工具。\n\n");
                }
            }
            // 仅冷重建的“用户＋后端结果”需要轻量确认；已有模型原文或工具批次时不替换它们。
            if (boundary.closed() && originals.size() == 1 && originals.getFirst().payload() instanceof UserMessage user) {
                projectedOriginals.put(originals.getFirst().sequence(), List.of(user, AiMessage.from(HISTORICAL_ACK)));
            }
        }
        // 窗口本身保存位置；这里按顺序展开有类型资料，不再按 afterSequence 查找、插入附件。
        var conversation = new ArrayList<ChatMessage>();
        for (var entry : compatible.source().window()) {
            if (entry instanceof L0Snapshot.Message original) {
                conversation.addAll(projectedOriginals.getOrDefault(original.sequence(), List.of()));
            } else if (entry instanceof L0Snapshot.PlanRestore restore
                    && restore.coverage().equals(PlanCoverageBoundary.of(summary))) {
                conversation.addAll(restore.messages());
            }
        }
        var frontier = summary.coverageState();
        if (frontier != null && frontier.terminalHistoryId() != null && !frontier.terminalIncluded()
                && !representedTerminals.contains(frontier.terminalHistoryId())) {
            state.append("摘要中的历史任务已经结束，服务端终态=").append(frontier.terminalOutcome())
                    .append("；不是当前回合的待办。\n");
        }
        state.append("后续原始用户消息的回合顺序（不含摘要、偏好和计划资料）：")
                .append(visibleUsers.stream().map(id -> "userHistoryId=" + id).collect(java.util.stream.Collectors.joining(" → ")))
                .append("。\n");
        return new ProjectedView(parsed.leadingMessages().stream().map(L0Snapshot.Message::payload).toList(),
                state.isEmpty() ? null : SystemMessage.from(STATE_HEADER + state.toString().stripTrailing()), conversation);
    }

    private void appendSummaryIdentity(StringBuilder state, CompatibleMemorySnapshot compatible) {
        state.append("当前用户回合 userHistoryId=").append(compatible.userHistoryId()).append("。\n");
        var coverage = compatible.summary().coverageState();
        if (coverage != null) {
            state.append("L1 进度所属用户回合 userHistoryId=").append(coverage.userHistoryId())
                    .append("；只反映该回合已纳入摘要的材料，不能用作其他回合的完成位置。\n");
        } else if (!compatible.summary().summary().isBlank()) {
            state.append("L1 仅概括截至历史记录 ID=").append(compatible.summary().lastSummarizedId())
                    .append(" 的早期回合，不表示后续用户回合的执行进度。\n");
        }
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
