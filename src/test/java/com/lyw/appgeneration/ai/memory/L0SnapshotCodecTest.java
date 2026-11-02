package com.lyw.appgeneration.ai.memory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class L0SnapshotCodecTest {
    @Test
    void 工具载荷及中文往返保持协议且元数据不进入模型消息() {
        var call = AiMessage.from(ToolExecutionRequest.builder().id("调用一").name("readFile")
                .arguments("{\"path\":\"页面.vue\"}").build());
        var result = ToolExecutionResultMessage.from("调用一", "readFile", "中文结果 😀");
        var snapshot = new L0Snapshot(1, "7", "epoch-一", 5, 3L,
                List.of(new L0Snapshot.Message(0, 0, SystemMessage.from("系统规则")),
                        new L0Snapshot.Message(1, 10, UserMessage.from("用户原话")),
                        new L0Snapshot.Message(3, 10, call), new L0Snapshot.Message(4, 10, result)),
                List.of(new L0Snapshot.Turn(10, "turn-1", 1, null, null, null)));
        assertEquals(snapshot, L0SnapshotCodec.decode("7", L0SnapshotCodec.encode(snapshot)));
        String payload = ChatMessageSerializer.messagesToJson(snapshot.messages());
        assertFalse(payload.contains("memoryEpoch"));
        assertFalse(payload.contains("appliedSummaryRevision"));
        assertEquals(List.of(SystemMessage.from("系统规则"), UserMessage.from("用户原话"), call, result),
                ChatMessageDeserializer.messagesFromJson(payload));
    }

    @Test
    void 旧数组及缺失键不猜摘要下界而新空窗口保留实际依赖() {
        for (String json : new String[]{null, "[]", ChatMessageSerializer.messagesToJson(List.of(UserMessage.from("旧消息")))}) {
            var legacy = L0SnapshotCodec.decode("7", json);
            assertFalse(legacy.hasKnownSource());
            assertNull(legacy.appliedSummaryRevision());
            assertEquals(legacy.messages(), L0SnapshotCodec.decode("7", L0SnapshotCodec.encode(legacy)).messages());
        }
        var empty = L0Snapshot.empty("7", 12L);
        assertEquals(empty, L0SnapshotCodec.decode("7", L0SnapshotCodec.encode(empty)));
        assertEquals(12L, empty.appliedSummaryRevision());
    }

    @Test
    void 损坏封装未知版本和其他应用身份被拒绝() {
        var empty = L0Snapshot.empty("7", 3);
        String json = L0SnapshotCodec.encode(empty);
        assertThrows(IllegalArgumentException.class, () -> L0SnapshotCodec.decode("8", json));
        for (String invalid : new String[]{"{}", "null", json + "{}",
                json.replace("\"formatVersion\":2", "\"formatVersion\":99"),
                json.replace("\"appliedSummaryRevision\":3", "\"appliedSummaryRevision\":null"),
                json.replace("\"nextSequence\":1", "\"nextSequence\":0")}) {
            assertThrows(IllegalArgumentException.class, () -> L0SnapshotCodec.decode("7", invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> new L0Snapshot(1, "7", "epoch", 3, 0L,
                List.of(new L0Snapshot.Message(1, 12, UserMessage.from("没有回合来源"))), List.of()));
    }
}
