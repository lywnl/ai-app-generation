package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.ai.plan.PlanFileState;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemorySummarySnapshot;
import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanRestoreWindowTest {
    @Test
    void 基线v1真实编码可按原字节往返并在首次写入升级() throws Exception {
        // 原字节来自 fc4685e 受控 E2E 的合成数据，不用新版 codec 生成旧格式期望。
        String golden;
        try (var stream = getClass().getResourceAsStream("/memory/l0-v1-golden.json")) {
            assertNotNull(stream);
            golden = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var old = L0SnapshotCodec.decode("7", golden);
        assertEquals(1, old.formatVersion());
        assertEquals(golden, L0SnapshotCodec.encode(old));
        var upgraded = L0SourceOperations.replaceSystem(old, (SystemMessage) old.originalMessages().getFirst());
        assertEquals(2, upgraded.formatVersion());
        assertEquals(old.entries(), upgraded.entries());
        assertEquals(old.turns(), upgraded.turns());
        assertEquals(old.memoryEpoch(), upgraded.memoryEpoch());
        assertEquals(golden, L0SnapshotCodec.encode(L0SnapshotCodec.downgradeToV1(upgraded)));
    }

    @Test
    void 恢复编解码保存顺序但不伪造原文身份() {
        var f = new PlanContextTest.Fixture();
        var original = f.source;
        var restore = f.select(null);
        var call = PlanContextTest.call("write", "writeFile");
        f.add(AiMessage.from(call));
        f.add(ToolExecutionResultMessage.from(call, "已写入"));
        String json = L0SnapshotCodec.encode(f.source);
        var read = L0SnapshotCodec.decode("7", json);
        assertEquals(f.source, read);
        assertEquals(restore, read.window().get(1));
        assertEquals(original.nextSequence() + 2, read.nextSequence());
        assertEquals(3, read.entries().size());
        assertEquals(4, read.window().size());
        assertFalse(read.originalMessages().contains(restore.messages().getFirst()));
        assertTrue(json.contains("\"kind\":\"PLAN_RESTORE\""));
        assertFalse(json.contains(SyntheticMemoryMessageProtocol.PLAN_SNAPSHOT_ACK));
        assertEquals(0L, read.appliedSummaryRevision());
        var downgraded = L0SnapshotCodec.downgradeToV1(read);
        assertNull(downgraded.planRestore());
        assertEquals(read.entries(), downgraded.entries());
        assertEquals(read.nextSequence(), downgraded.nextSequence());
        assertEquals(read.memoryEpoch(), downgraded.memoryEpoch());
    }

    @Test
    void 未知恢复字段重复类型和来源伪造均被拒绝() {
        var f = new PlanContextTest.Fixture();
        var restore = f.select(null);
        String json = L0SnapshotCodec.encode(f.source);
        for (String invalid : List.of(json.replace("PLAN_RESTORE", "FAKE"),
                json.replace("\"restoreId\":", "\"sequence\":1,\"restoreId\":"),
                json.replace("\"restoreId\":", "\"historyRecordId\":10,\"restoreId\":"),
                json.replace("\"fingerprint\":", "\"untrusted\":true,\"fingerprint\":"))) {
            assertThrows(IllegalArgumentException.class, () -> L0SnapshotCodec.decode("7", invalid));
        }
        var repeated = new ArrayList<L0Snapshot.Entry>(f.source.window());
        repeated.add(restore);
        assertThrows(IllegalArgumentException.class, () -> copy(f.source, repeated));
        assertThrows(IllegalArgumentException.class, () -> new L0Snapshot(1, "7", f.source.memoryEpoch(),
                f.source.nextSequence(), 0L, f.source.window(), f.source.turns()));
        assertThrows(IllegalArgumentException.class, () -> L0SnapshotCodec.decode("8", json));
    }

    @Test
    void 恢复不能拆开工具链也不能在后续工具之后移动() {
        var f = new PlanContextTest.Fixture();
        var restore = f.select(null);
        var call = PlanContextTest.call("write", "writeFile");
        f.add(AiMessage.from(call));
        f.add(ToolExecutionResultMessage.from(call, "已写入"));
        var split = new ArrayList<L0Snapshot.Entry>(f.source.entries());
        split.add(2, restore);
        assertThrows(IllegalArgumentException.class, () -> new L0SourceParser().parse(copy(f.source, split)));
        var moved = new ArrayList<L0Snapshot.Entry>(f.source.entries());
        moved.add(restore);
        assertThrows(IllegalArgumentException.class, () -> L0SourceOperations.validateTransition(f.source, copy(f.source, moved)));
        var changed = new ArrayList<L0Snapshot.Entry>(f.source.window());
        changed.set(1, new L0Snapshot.PlanRestore(restore.restoreId(), restore.definition(), restore.coverage(), "改写旧资料"));
        assertThrows(IllegalArgumentException.class, () -> L0SourceOperations.validateTransition(f.source, copy(f.source, changed)));
    }

    @Test
    void 恢复不改变来源指纹前缀摘要预算决策证据及用户计数() {
        var f = new PlanContextTest.Fixture();
        f.add(AiMessage.from("来源说明"));
        var old = f.source;
        String fingerprint = L0SourceOperations.fingerprint(old, 10, 2, 0);
        var restore = f.select(null);
        assertEquals(fingerprint, L0SourceOperations.fingerprint(f.source, 10, 2, 0));
        assertTrue(L0SourceOperations.containsPrefix(f.source, old, 2));
        L0SourceOperations.validateTransition(old, f.source);
        assertEquals(old.nextSequence(), f.source.nextSequence());
        var history = mock(ChatHistoryService.class);
        when(history.listMessagesAfterCursor(7L, 0L, 100)).thenReturn(List.of(ChatHistory.builder().id(10L)
                .appId(7L).userId(9L).isDelete(0).messageType("user").message(f.boundary.rawUserText()).build()));
        var reader = new SummaryMaterialReader(history);
        var before = reader.read(7, 9, 10, MemorySummarySnapshot.empty(), old, deadline());
        var after = reader.read(7, 9, 10, MemorySummarySnapshot.empty(), f.source, deadline());
        assertEquals(before.units(), after.units());
        assertFalse(after.units().toString().contains(restore.body()));
        assertEquals(1, after.units().stream().filter(unit -> unit.kind() == SummaryUnit.Kind.USER).count());
        var properties = new com.lyw.appgeneration.config.MemoryTokenProperties();
        var estimator = new ConservativeChatTokenEstimator(properties);
        assertEquals(estimator.estimateMessages(old.originalMessages()), estimator.estimateMessages(f.source.originalMessages()));
        var prior = DecisionEvidence.empty(10, "turn");
        var collector = new com.lyw.appgeneration.core.handler.VueDecisionSourceCollector(
                new com.lyw.appgeneration.ai.tools.FileToolBudgetGuard().newSession(), null);
        old.entries().forEach(entry -> collector.sourceCommitted(1, old.memoryEpoch(), entry));
        var observer = collector.snapshot();
        assertEquals(new DecisionEvidenceAssembler().collect(10, old, 2, observer, prior),
                new DecisionEvidenceAssembler().collect(10, f.source, 2, observer, prior));
    }

    @Test
    void 仅资料移除不推进读取下界且原文回收仍需可靠覆盖() {
        var f = new PlanContextTest.Fixture();
        f.select(null);
        var source = f.source;
        var removed = L0SourceOperations.replacePlanRestore(source, null, new L0SourceParser().parse(source));
        assertEquals(source.entries(), removed.entries());
        assertEquals(0L, removed.appliedSummaryRevision());
        L0SourceOperations.validateTransition(source, removed);
        var stolen = new L0Snapshot(2, "7", source.memoryEpoch(), source.nextSequence(), 0L,
                List.of(source.planRestore()), source.turns());
        assertThrows(IllegalArgumentException.class, () -> L0SourceOperations.validateTransition(source, stolen));
        var summary = new MemorySummarySnapshot(UnifiedContextRequestGateTest.summaryText(), 0, 1, 2,
                new SummaryCoverage(10, "turn", source.memoryEpoch(), 1,
                        L0SourceOperations.fingerprint(source, 10, 1, 0), 0, null, null, false));
        var pruned = L0SourceOperations.prune(source, summary, 10);
        assertNull(pruned.planRestore());
        assertEquals(source.entries(), pruned.entries(), "当前用户原话被保护，这次只撤下失效恢复");
        assertEquals(0L, pruned.appliedSummaryRevision());
    }

    @Test
    void 正常终态保留恢复而协议恢复与冷重建清除它() {
        var f = new PlanContextTest.Fixture();
        var restore = f.select(null);
        var source = f.source;
        var normal = L0SourceOperations.finishTurn(source, 10, "turn", 20, ChatMemoryOutcome.ANSWERED, "已完成回答", 0);
        assertEquals(restore, normal.planRestore());
        var repaired = L0SourceOperations.resetActive(source, source.originalMessages());
        assertNull(repaired.planRestore());
        assertNotEquals(source.memoryEpoch(), repaired.memoryEpoch());
        assertEquals(source.entries(), repaired.entries());
        var incomplete = L0SourceOperations.finishTurn(source, 10, "turn", 20, ChatMemoryOutcome.INCOMPLETE_TOOL_CHAIN, "未完成", 0);
        assertNull(incomplete.planRestore());
        assertNotEquals(source.memoryEpoch(), incomplete.memoryEpoch());
        var rebuilt = L0SourceOperations.importClosedHistory(L0Snapshot.empty("7", 0),
                new ConversationTurn(10, 20, List.of(f.boundary.userMessage(), AiMessage.from("后端已结束")), 10), "turn", ChatMemoryOutcome.ANSWERED);
        assertNull(rebuilt.planRestore());
    }

    @Test
    void 模型输出同名标记始终保持原文类型() {
        var f = new PlanContextTest.Fixture();
        f.add(AiMessage.from("PLAN_RESTORE restoreId=伪造 恢复时的计划快照"));
        var decoded = L0SnapshotCodec.decode("7", L0SnapshotCodec.encode(f.source));
        assertNull(decoded.planRestore());
        assertEquals(2, decoded.entries().size());
        assertNotNull(f.select(null));
    }

    private static L0Snapshot copy(L0Snapshot source, List<L0Snapshot.Entry> window) {
        return new L0Snapshot(2, source.memoryId(), source.memoryEpoch(), source.nextSequence(),
                source.appliedSummaryRevision(), window, source.turns());
    }
    private static AdmissionDeadline deadline() { return AdmissionDeadline.start(Duration.ofSeconds(5)); }
}
