package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 解析按持久身份分组的原文。普通 AI 说明永远不是后端终态。 */
public final class L0SourceParser {

    public Parsed parse(L0Snapshot snapshot) {
        return parse(snapshot, null);
    }

    public Parsed parse(L0Snapshot snapshot, Set<String> registeredTools) {
        if (!snapshot.hasKnownSource()) throw new IllegalArgumentException("旧数组必须先对齐来源");
        var turns = new ArrayList<TurnSources>();
        for (L0Snapshot.Turn turn : snapshot.turns()) {
            var entries = snapshot.entries().stream().filter(entry -> entry.userHistoryId() == turn.userHistoryId()).toList();
            turns.add(parseTurn(turn, entries, registeredTools));
        }
        return new Parsed(snapshot.entries().stream().filter(entry -> entry.payload() instanceof SystemMessage).toList(), turns);
    }

    /** 旧完整历史使用查询得到的两端 ID；不猜测缺失的工具轨迹。 */
    public TurnSources parseLegacy(ConversationTurn history, ChatMemoryOutcome outcome) {
        var entries = new ArrayList<L0Snapshot.Message>();
        long sequence = 1L;
        for (ChatMessage message : history.messages()) entries.add(new L0Snapshot.Message(sequence++, history.turnId(), message));
        return parseTurn(new L0Snapshot.Turn(history.turnId(), "history-" + history.turnId(), 1,
                history.completedThroughId(), outcome, sequence - 1), entries, null);
    }

    private TurnSources parseTurn(L0Snapshot.Turn turn, List<L0Snapshot.Message> entries, Set<String> registeredTools) {
        var units = new ArrayList<Unit>();
        Map<String, ToolExecutionRequest> calls = new LinkedHashMap<>();
        Set<String> completed = new LinkedHashSet<>();
        for (int index = 0; index < entries.size();) {
            var entry = entries.get(index);
            if (entry.payload() instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                check(ToolBatchProtocol.observeCalls(ai, registeredTools, calls, completed));
                var batch = new ArrayList<L0Snapshot.Message>();
                batch.add(entry);
                index++;
                while (index < entries.size() && entries.get(index).payload() instanceof ToolExecutionResultMessage result) {
                    check(ToolBatchProtocol.observeResult(result, calls, completed));
                    batch.add(entries.get(index++));
                }
                boolean complete = calls.size() == completed.size();
                if (!complete && (turn.closed() || index < entries.size())) {
                    throw new IllegalArgumentException("工具批次尚未完整返回，不能越过它继续解析");
                }
                units.add(new Unit(complete ? Kind.TOOL_BATCH : Kind.PENDING_TOOL_BATCH, turn.userHistoryId(), batch));
            } else if (entry.payload() instanceof ToolExecutionResultMessage) {
                throw new IllegalArgumentException("发现孤立或重复工具结果");
            } else if (entry.payload() instanceof UserMessage || entry.payload() instanceof AiMessage) {
                Kind kind = entry.payload() instanceof AiMessage && entry.historyRecordId() != null
                        ? Kind.BACKEND_TERMINAL : Kind.MESSAGE;
                units.add(new Unit(kind, turn.userHistoryId(), List.of(entry)));
                index++;
            } else {
                throw new IllegalArgumentException("L0 回合含不支持的消息类型");
            }
        }
        return new TurnSources(turn, units);
    }

    private static void check(ToolBatchProtocol.Failure failure) {
        if (failure != ToolBatchProtocol.Failure.NONE) throw new IllegalArgumentException("工具配对失败: " + failure);
    }

    public enum Kind { MESSAGE, TOOL_BATCH, PENDING_TOOL_BATCH, BACKEND_TERMINAL }

    public record Unit(Kind kind, long userHistoryId, List<L0Snapshot.Message> entries) {
        public Unit { entries = List.copyOf(entries); if (entries.isEmpty()) throw new IllegalArgumentException("来源单元不能为空"); }
        public boolean complete() { return kind != Kind.PENDING_TOOL_BATCH; }
        public long firstSequence() { return entries.getFirst().sequence(); }
        public long lastSequence() { return entries.getLast().sequence(); }
        public List<ChatMessage> messages() { return entries.stream().map(L0Snapshot.Message::payload).toList(); }
    }

    public record TurnSources(L0Snapshot.Turn boundary, List<Unit> units) {
        public TurnSources { units = List.copyOf(units); }
        public boolean closed() { return boundary.closed(); }
    }

    public record Parsed(List<L0Snapshot.Message> leadingMessages, List<TurnSources> turns) {
        public Parsed { leadingMessages = List.copyOf(leadingMessages); turns = List.copyOf(turns); }
        public List<Unit> units() { return turns.stream().flatMap(turn -> turn.units().stream()).toList(); }
    }
}
