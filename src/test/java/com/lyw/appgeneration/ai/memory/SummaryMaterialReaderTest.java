package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemorySummarySnapshot;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SummaryMaterialReaderTest {
    @Test
    void 后端历史投影按记录ID去重而相同模型文字仍作为原文保留() {
        var histories = mock(ChatHistoryService.class);
        when(histories.listMessagesAfterCursor(7L, 0L, 100)).thenReturn(List.of(
                row(10, "user", "旧要求"), row(20, "ai", "真实结果"), row(30, "user", "需求")));
        var turns = List.of(new L0Snapshot.Turn(10, "old", 1, 20L, ChatMemoryOutcome.FAILED, 2L),
                new L0Snapshot.Turn(30, "turn", 3, null, null, null));
        var source = new L0Snapshot(1, "7", "epoch", 4, 0L, List.of(
                new L0Snapshot.Message(1, 10, UserMessage.from("旧要求"), 10L),
                new L0Snapshot.Message(2, 10, AiMessage.from("真实结果"), 20L),
                new L0Snapshot.Message(3, 30, UserMessage.from("需求"), 30L)), turns);
        var reader = new SummaryMaterialReader(histories);
        assertEquals(3, reader.read(7, 9, 30, MemorySummarySnapshot.empty(), source, deadline()).units().size());
        assertEquals(source, L0SnapshotCodec.decode("7", L0SnapshotCodec.encode(source)));
        var modelSource = new L0Snapshot(1, "7", "epoch", 4, 0L, List.of(source.entries().getFirst(),
                new L0Snapshot.Message(2, 10, AiMessage.from("真实结果")), source.entries().getLast()), turns);
        assertEquals(4, reader.read(7, 9, 30, MemorySummarySnapshot.empty(), modelSource, deadline()).units().size());
    }
    @Test
    void 首轮尚未结束也能构造当前完整工具批次且模型说明不伪造终态() {
        var histories = mock(ChatHistoryService.class);
        when(histories.listMessagesAfterCursor(7L, 0L, 100)).thenReturn(List.of(row(10, "user", "需求")));
        var source = source(10, true);
        var materials = new SummaryMaterialReader(histories).read(7, 9, 10, MemorySummarySnapshot.empty(), source, deadline());
        assertEquals(3, materials.units().size());
        assertEquals(1, materials.units().stream().filter(unit -> unit.kind() == SummaryUnit.Kind.USER).count());
        assertEquals(1, materials.units().stream().filter(unit -> unit.kind() == SummaryUnit.Kind.TOOL_BATCH).count());
        assertFalse(materials.turns().getFirst().closed());
    }

    @Test
    void 分页补齐窗口外历史并按用户ID合并同一回合的多种表示() {
        var histories = mock(ChatHistoryService.class);
        when(histories.listMessagesAfterCursor(7L, 0L, 2)).thenReturn(List.of(row(10, "user", "旧要求"), row(20, "ai", "旧真实结果")));
        when(histories.listMessagesAfterCursor(7L, 20L, 2)).thenReturn(List.of(row(30, "user", "需求")));
        var materials = new SummaryMaterialReader(histories, 2).read(7, 9, 30, MemorySummarySnapshot.empty(), source(30, false), deadline());
        assertEquals(List.of(10L, 30L), materials.turns().stream().map(SummaryMaterials.Turn::userHistoryId).toList());
        assertEquals(20L, materials.turns().getFirst().terminalHistoryId());
        assertEquals(2, materials.units().stream().filter(unit -> unit.kind() == SummaryUnit.Kind.USER).count());
        verify(histories).listMessagesAfterCursor(7L, 20L, 2);
    }

    @Test
    void 未闭合历史缺口错误归属及缺失当前用户都不会形成可提交输入() {
        var histories = mock(ChatHistoryService.class);
        when(histories.listMessagesAfterCursor(7L, 0L, 100)).thenReturn(List.of(row(10, "user", "孤立用户"), row(30, "user", "需求")));
        var reader = new SummaryMaterialReader(histories);
        assertThrows(IllegalStateException.class, () -> reader.read(7, 9, 30, MemorySummarySnapshot.empty(), source(30, false), deadline()));
        var wrong = row(30, "user", "需求"); wrong.setUserId(88L);
        when(histories.listMessagesAfterCursor(7L, 0L, 100)).thenReturn(List.of(wrong));
        assertThrows(IllegalStateException.class, () -> reader.read(7, 9, 30, MemorySummarySnapshot.empty(), source(30, false), deadline()));
        when(histories.listMessagesAfterCursor(7L, 0L, 100)).thenReturn(List.of());
        assertThrows(IllegalStateException.class, () -> reader.read(7, 9, 30, MemorySummarySnapshot.empty(), source(30, false), deadline()));
    }

    private ChatHistory row(long id, String role, String text) {
        return ChatHistory.builder().id(id).appId(7L).userId(9L).isDelete(0).messageType(role).message(text)
                .memoryMessage("ai".equals(role) ? text : null).memoryOutcome("ai".equals(role) ? ChatMemoryOutcome.FAILED : null).build();
    }
    private L0Snapshot source(long user, boolean tools) {
        var boundary = TurnRequestBoundary.of("需求", List.of(), user, "turn");
        var source = L0SourceOperations.append(L0Snapshot.empty("7", 0), boundary.userMessage(), boundary);
        if (tools) {
            var request = ToolExecutionRequest.builder().id("read").name("readFile").arguments("{}").build();
            source = L0SourceOperations.append(source, AiMessage.from(request), null);
            source = L0SourceOperations.append(source, ToolExecutionResultMessage.from(request, "原始诊断"), null);
            source = L0SourceOperations.append(source, AiMessage.from("继续修复"), null);
        }
        return source;
    }
    private AdmissionDeadline deadline() { return AdmissionDeadline.start(Duration.ofSeconds(5)); }
}
