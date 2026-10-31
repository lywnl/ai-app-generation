package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class L0SourceParserTest {
    private final L0SourceParser parser = new L0SourceParser();

    @Test
    void 多工具批次原序配对而中途AI说明不关闭回合() {
        var a = call("a", "readFile");
        var b = call("b", "readDir");
        var messages = List.<ChatMessage>of(UserMessage.from("需求"), AiMessage.from("先查看报错"),
                AiMessage.from(List.of(a, b)), result("b", "readDir"), result("a", "readFile"), AiMessage.from("继续修复"));
        var parsed = parser.parse(snapshot(messages, false), Set.of("readFile", "readDir"));
        assertFalse(parsed.turns().getFirst().closed());
        assertEquals(4, parsed.units().size());
        assertEquals(messages.subList(2, 5), parsed.units().get(2).messages());
        assertEquals(messages, parsed.units().stream().flatMap(unit -> unit.messages().stream()).toList());
        assertTrue(parser.parse(snapshot(messages, true)).turns().getFirst().closed());
        assertEquals(ChatMemoryOutcome.FAILED, parser.parse(snapshot(messages, true)).turns().getFirst().boundary().terminalOutcome());
    }

    @Test
    void 缺失结果只允许作为活动尾部且不得变成完整单元() {
        var messages = List.<ChatMessage>of(UserMessage.from("需求"),
                AiMessage.from(List.of(call("a", "readFile"), call("b", "readDir"))), result("a", "readFile"));
        assertFalse(parser.parse(snapshot(messages, false)).units().getLast().complete());
        assertThrows(IllegalArgumentException.class, () -> parser.parse(snapshot(messages, true)));
        var interrupted = new java.util.ArrayList<>(messages);
        interrupted.add(AiMessage.from("模型自称已经成功"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(snapshot(interrupted, false)));
    }

    @Test
    void 重复孤立名称不符及未注册工具都被拒绝() {
        for (var messages : List.of(
                List.<ChatMessage>of(UserMessage.from("需求"), result("a", "readFile")),
                List.<ChatMessage>of(UserMessage.from("需求"), AiMessage.from(call("a", "readFile")), result("a", "readDir")),
                List.<ChatMessage>of(UserMessage.from("需求"), AiMessage.from(call("a", "readFile")), result("a", "readFile"), result("a", "readFile")),
                List.<ChatMessage>of(UserMessage.from("需求"), AiMessage.from(List.of(call("a", "readFile"), call("a", "readFile")))))) {
            assertThrows(IllegalArgumentException.class, () -> parser.parse(snapshot(messages, false)));
        }
        assertThrows(IllegalArgumentException.class, () -> parser.parse(snapshot(List.of(UserMessage.from("需求"),
                AiMessage.from(call("a", "unknown")), result("a", "unknown")), false), Set.of("readFile")));
    }

    @Test
    void 旧完整历史按持久ID适配并保留缺失轨迹的边界() {
        var messages = List.<ChatMessage>of(UserMessage.from("旧需求"), AiMessage.from("旧可信结果"));
        var turn = parser.parseLegacy(new ConversationTurn(10, 20, messages, 12), ChatMemoryOutcome.LEGACY_UNVERIFIED);
        assertTrue(turn.closed());
        assertEquals(10L, turn.boundary().userHistoryId());
        assertEquals(20L, turn.boundary().terminalHistoryId());
        assertEquals(messages, turn.units().stream().flatMap(unit -> unit.messages().stream()).toList());
    }

    private L0Snapshot snapshot(List<ChatMessage> messages, boolean closed) {
        var entries = new java.util.ArrayList<L0Snapshot.Message>();
        long sequence = 1L;
        for (var message : messages) entries.add(new L0Snapshot.Message(sequence++, 10, message));
        var turn = new L0Snapshot.Turn(10, "turn", 1,
                closed ? 20L : null, closed ? ChatMemoryOutcome.FAILED : null, closed ? sequence - 1 : null);
        return new L0Snapshot(1, "7", "epoch", sequence, 0L, entries, List.of(turn));
    }
    private ToolExecutionRequest call(String id, String name) { return ToolExecutionRequest.builder().id(id).name(name).arguments("{}").build(); }
    private ToolExecutionResultMessage result(String id, String name) { return ToolExecutionResultMessage.from(id, name, "真实结果"); }
}
