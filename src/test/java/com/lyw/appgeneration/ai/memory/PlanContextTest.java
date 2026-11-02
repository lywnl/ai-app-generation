package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.ai.plan.*;
import com.lyw.appgeneration.ai.tools.PlanToolProtocolSupport;
import com.lyw.appgeneration.ai.tools.PlanToolResult;
import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;
import com.lyw.appgeneration.service.CompatibleMemorySnapshot;
import com.lyw.appgeneration.service.MemorySummarySnapshot;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanContextTest {
    @ParameterizedTest
    @ValueSource(strings = {"makePlan", "updatePlan"})
    void 完整成功结果匹配时保留原始批次并撤下恢复附件(String operation) {
        var fixture = new Fixture();
        var restored = fixture.select(null);
        var call = call("plan-call", operation);
        var result = ToolExecutionResultMessage.from(call, success(operation, fixture.plan));
        fixture.add(AiMessage.from(call));
        fixture.add(result);
        var source = fixture.source;
        assertNull(fixture.select(restored));
        var view = new TrustedTurnRequestViewProjector().project(fixture.snapshot());
        assertTrue(view.conversationMessages().contains(AiMessage.from(call)));
        assertTrue(view.conversationMessages().contains(result));
        assertEquals(source.entries(), fixture.source.entries());
    }

    @ParameterizedTest
    @EnumSource(value = PlanToolResult.Status.class, names = {"FAILED", "REJECTED", "CONFLICT"})
    void 失败拒绝和冲突即使携带完整字段也不算成功计划(PlanToolResult.Status status) {
        var fixture = new Fixture();
        var call = call("failed-plan", "makePlan");
        var plan = fixture.plan;
        var result = new PlanToolResult(PlanToolResult.PROTOCOL, "makePlan", status,
                plan.planId(), plan.version(), "实际未应用", plan.summary(), plan.files());
        fixture.add(AiMessage.from(call));
        fixture.add(ToolExecutionResultMessage.from(call, PlanToolProtocolSupport.json(result)));
        assertNotNull(fixture.select(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"普通说明", "仅调用参数", "错误操作", "非计划工具", "无效协议", "空文件", "重复路径"})
    void 非成功完整计划的材料不能免除恢复(String kind) {
        var fixture = new Fixture();
        String json = success("makePlan", fixture.plan);
        if (kind.equals("普通说明")) {
            fixture.add(AiMessage.from(json));
        } else if (kind.equals("仅调用参数")) {
            fixture.add(AiMessage.from(ToolExecutionRequest.builder().id("parameters")
                    .name("makePlan").arguments(json).build()));
        } else {
            var call = call("non-plan", kind.equals("非计划工具") ? "readFile" : "makePlan");
            fixture.add(AiMessage.from(call));
            String output = switch (kind) {
                case "错误操作" -> success("updatePlan", fixture.plan);
                case "无效协议" -> "{\"status\":\"APPLIED\"}";
                case "空文件" -> PlanToolProtocolSupport.json(PlanToolResult.applied("makePlan",
                        fixture.plan.planId(), 1, fixture.plan.summary(), List.of()));
                case "重复路径" -> PlanToolProtocolSupport.json(PlanToolResult.applied("makePlan",
                        fixture.plan.planId(), 1, fixture.plan.summary(), List.of(fixture.plan.files().getFirst(), fixture.plan.files().getFirst())));
                default -> json;
            };
            fixture.add(ToolExecutionResultMessage.from(call, output));
        }
        assertNotNull(fixture.select(null));
    }

    @Test
    void 并行批次必须全部返回才可提供完整计划且附件不插入半批次() {
        var fixture = new Fixture();
        var planCall = call("create", "makePlan");
        var readCall = call("read", "readFile");
        var ai = AiMessage.from(planCall, readCall);
        fixture.add(ai);
        fixture.add(ToolExecutionResultMessage.from(planCall, success("makePlan", fixture.plan)));
        var attachment = fixture.select(null);
        assertNotNull(attachment);
        assertEquals(1, fixture.source.window().indexOf(attachment));
        var view = new TrustedTurnRequestViewProjector().project(fixture.snapshot(), "当前执行中");
        assertTrue(view.conversationMessages().indexOf(AiMessage.from(SyntheticMemoryMessageProtocol.PLAN_SNAPSHOT_ACK))
                < view.conversationMessages().indexOf(ai));
        fixture.add(ToolExecutionResultMessage.from(readCall, "读取成功"));
        assertNull(fixture.select(attachment));
    }

    @ParameterizedTest
    @ValueSource(strings = {"调用名称", "结果标识"})
    void 非法工具配对保持原有失败通道(String kind) {
        var fixture = new Fixture();
        var call = call("create", "makePlan");
        fixture.add(AiMessage.from(call));
        var mismatched = call(kind.equals("结果标识") ? "another" : "create",
                kind.equals("调用名称") ? "updatePlan" : "makePlan");
        fixture.add(ToolExecutionResultMessage.from(mismatched, success(mismatched.name(), fixture.plan)));
        assertThrows(IllegalArgumentException.class, () -> fixture.select(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"标识", "版本", "目标", "路径", "用途", "动作", "依赖", "文件集合"})
    void 定义变化不能只凭相同版本或旧工具结果复用(String field) {
        var fixture = new Fixture();
        var oldPlan = fixture.plan;
        var restored = fixture.select(null);
        var call = call("old", "makePlan");
        fixture.add(AiMessage.from(call));
        fixture.add(ToolExecutionResultMessage.from(call, success("makePlan", oldPlan)));
        var files = new ArrayList<>(oldPlan.files());
        var old = files.getLast();
        files.set(files.size() - 1, new PlanFile(field.equals("路径") ? "src/New.vue" : old.path(),
                field.equals("用途") ? "新用途" : old.purpose(),
                field.equals("动作") ? PlanFileAction.CREATE : old.action(),
                field.equals("依赖") ? List.of() : old.dependsOn(), old.state()));
        if (field.equals("文件集合")) files.add(new PlanFile("src/Extra.vue", "新增", PlanFileAction.CREATE, List.of(), PlanFileState.PENDING));
        fixture.plan = new AppPlan(field.equals("标识") ? "another-plan" : oldPlan.planId(), "turn", "turn",
                field.equals("版本") ? 2 : 1, 1, oldPlan.mode(), field.equals("目标") ? "新的目标" : oldPlan.summary(),
                files, List.of(), oldPlan.status());
        var replacement = fixture.select(restored);
        assertNotNull(replacement);
        assertNotSame(restored, replacement);
        assertEquals(3, fixture.source.window().indexOf(replacement));
    }

    @Test
    void 文件和依赖顺序不改变定义而状态与回合绑定不导致重建() {
        var fixture = new Fixture();
        fixture.plan = plan(1, PlanFileState.PENDING);
        var attachment = fixture.select(null);
        var updated = plan(1, PlanFileState.TOUCHED).rebindActiveTurn("next");
        var files = new ArrayList<>(updated.files());
        java.util.Collections.reverse(files);
        fixture.plan = new AppPlan(updated.planId(), "next", "next", 1, 2, updated.mode(), updated.summary(),
                files, updated.history(), PlanStatus.BUILT);
        assertSame(attachment, fixture.select(attachment));
        assertTrue(attachment.body().contains("状态=PENDING"));
        var call = call("create", "makePlan");
        fixture.add(AiMessage.from(call));
        fixture.add(ToolExecutionResultMessage.from(call, success("makePlan", updated)));
        assertNull(fixture.select(attachment));
        var left = PlanDefinition.of("id", 1, "目标", List.of(new PlanFile("src/App.vue", "页面",
                PlanFileAction.MODIFY, List.of("src/a.ts", "src/b.ts"), PlanFileState.PENDING)));
        var right = PlanDefinition.of("id", 1, "目标", List.of(new PlanFile("src/App.vue", "页面",
                PlanFileAction.MODIFY, List.of("src/b.ts", "src/a.ts"), PlanFileState.TOUCHED)));
        assertEquals(left, right);
    }

    @Test
    void 后续文件工具结果在固定附件之后且原始批次保持完整() {
        var fixture = new Fixture();
        var attachment = fixture.select(null);
        var call = call("write", "writeFile");
        var result = ToolExecutionResultMessage.from(call, "真实写入完成");
        fixture.add(AiMessage.from(call));
        fixture.add(result);
        fixture.plan = plan(1, PlanFileState.TOUCHED);
        assertSame(attachment, fixture.select(attachment));
        var messages = new TrustedTurnRequestViewProjector().project(fixture.snapshot(), "当前状态").conversationMessages();
        assertEquals(List.of(fixture.boundary.userMessage(), attachment.messages().getFirst(), attachment.messages().getLast(),
                AiMessage.from(call), result), messages);
        assertFalse(fixture.source.messages().contains(attachment.messages().getFirst()));
        assertFalse(L0SnapshotCodec.encode(fixture.source).contains(SyntheticMemoryMessageProtocol.PLAN_SNAPSHOT_ACK));
    }

    @Test
    void 已可靠覆盖但尚未回收的成功结果不能免除恢复且摘要概述不能冒充计划() {
        var fixture = new Fixture();
        var call = call("create", "makePlan");
        fixture.add(AiMessage.from(call));
        fixture.add(ToolExecutionResultMessage.from(call, success("makePlan", fixture.plan)));
        fixture.add(AiMessage.from("最近一条说明"));
        fixture.coverThrough(3, 1);
        var original = fixture.source;
        var attachment = fixture.select(null);
        assertNotNull(attachment);
        assertEquals(4, fixture.source.window().indexOf(attachment));
        assertEquals(original.entries(), fixture.source.entries());
        var messages = new TrustedTurnRequestViewProjector().project(fixture.snapshot(), "状态").conversationMessages();
        assertTrue(messages.contains(AiMessage.from("最近一条说明")));
        assertFalse(messages.stream().anyMatch(ToolExecutionResultMessage.class::isInstance));
    }

    @Test
    void 旧版零修订摘要隐藏的成功计划也必须按实际可见历史恢复() {
        var fixture = new Fixture();
        var call = call("old-create", "makePlan");
        fixture.add(AiMessage.from(call));
        fixture.add(ToolExecutionResultMessage.from(call, success("makePlan", fixture.plan)));
        fixture.source = L0SourceOperations.finishTurn(fixture.source, 10, "turn", 20, ChatMemoryOutcome.ANSWERED, "上一轮完成", 0);
        fixture.boundary = TurnRequestBoundary.of("继续上一轮计划", List.of(), 30, "next");
        fixture.source = L0SourceOperations.append(fixture.source, fixture.boundary.userMessage(), fixture.boundary);
        fixture.summary = new MemorySummarySnapshot(UnifiedContextRequestGateTest.summaryText(), 20);
        assertEquals(0, fixture.summary.summaryRevision());
        var attachment = fixture.select(null);
        assertNotNull(attachment, "完整旧结果虽仍在 L0，但已被旧摘要游标隐藏");
        var projected = new TrustedTurnRequestViewProjector().project(fixture.snapshot(), "当前状态");
        assertFalse(projected.conversationMessages().stream().anyMatch(ToolExecutionResultMessage.class::isInstance));
        assertEquals(List.of(fixture.boundary.userMessage(), attachment.messages().getFirst(), attachment.messages().getLast()),
                projected.conversationMessages());
    }

    @Test
    void 覆盖推进会恢复最新快照而物理回收本身不改变附件() {
        var fixture = new Fixture();
        fixture.add(AiMessage.from("早期说明"));
        fixture.add(AiMessage.from("保留说明"));
        var old = fixture.select(null);
        fixture.plan = plan(1, PlanFileState.TOUCHED);
        fixture.coverThrough(2, 1);
        var replacement = fixture.select(old);
        assertNotSame(old, replacement);
        assertTrue(replacement.body().contains("状态=TOUCHED"));
        fixture.source = L0SourceOperations.prune(fixture.source, fixture.summary, 10);
        assertSame(replacement, fixture.select(replacement));
        fixture.add(AiMessage.from("下一批说明"));
        fixture.coverThrough(3, 2);
        assertNotSame(replacement, fixture.select(replacement));
    }

    @Test
    void 仅终态登记增加摘要修订号时继续复用原附件() {
        var fixture = new Fixture();
        fixture.add(AiMessage.from("已覆盖说明"));
        fixture.source = L0SourceOperations.finishTurn(fixture.source, 10, "turn", 20, ChatMemoryOutcome.ANSWERED, "回答完成", 0);
        fixture.boundary = TurnRequestBoundary.of("下一轮继续", List.of(), 30, "next");
        fixture.source = L0SourceOperations.append(fixture.source, fixture.boundary.userMessage(), fixture.boundary);
        var coverage = new SummaryCoverage(10, "turn", fixture.source.memoryEpoch(), 2,
                L0SourceOperations.fingerprint(fixture.source, 10, 2, 0), 0, null, null, false);
        fixture.summary = new MemorySummarySnapshot(UnifiedContextRequestGateTest.summaryText(), 0, 1, 2, coverage);
        var attachment = fixture.select(null);
        fixture.summary = new MemorySummarySnapshot(fixture.summary.summary(), 0, 2, 2,
                coverage.withTerminal(20, ChatMemoryOutcome.ANSWERED));
        assertSame(attachment, fixture.select(attachment));
    }

    @Test
    void 跨回合复用仍保留真实用户归属与冷历史确认() {
        var fixture = new Fixture();
        var attachment = fixture.select(null);
        fixture.source = L0SourceOperations.finishTurn(fixture.source, 10, "turn", 20, ChatMemoryOutcome.ANSWERED, "回答完成", 0);
        fixture.boundary = TurnRequestBoundary.of("下一轮继续", List.of(), 30, "next");
        fixture.source = L0SourceOperations.append(fixture.source, fixture.boundary.userMessage(), fixture.boundary);
        assertSame(attachment, fixture.select(attachment));
        var projected = new TrustedTurnRequestViewProjector().project(fixture.snapshot(), "状态");
        assertEquals(AiMessage.from(SyntheticMemoryMessageProtocol.TRUSTED_TURN_ACK), projected.conversationMessages().get(1));
        assertEquals(attachment.messages().getFirst(), projected.conversationMessages().get(2));
        assertEquals(fixture.boundary.userMessage(), projected.conversationMessages().getLast());
        assertTrue(projected.trustedState().text().contains("userHistoryId=10 → userHistoryId=30"));
    }

    @Test
    void 新epoch冷缓存与其他应用都不能复用旧附件() {
        var fixture = new Fixture();
        var attachment = fixture.select(null);
        var old = fixture.source;
        fixture.source = new L0Snapshot(old.formatVersion(), old.memoryId(), "rebuilt-epoch", old.nextSequence(),
                old.appliedSummaryRevision(), old.entries(), old.turns());
        assertNotSame(attachment, fixture.select(attachment));
        assertNotSame(attachment, fixture.select(null));
        var other = new Fixture(8);
        var otherAttachment = other.select(attachment);
        assertNotSame(attachment, otherAttachment);
        assertEquals("8", other.source.memoryId());
        assertNotEquals(attachment.restoreId(), otherAttachment.restoreId());
        assertThrows(IllegalArgumentException.class, () -> L0SnapshotCodec.decode("7", L0SnapshotCodec.encode(other.source)));
    }

    @Test
    void 无计划时清除附件且正文每个文件仅一行并保留修订说明() {
        var fixture = new Fixture();
        var plan = fixture.plan;
        fixture.plan = new AppPlan(plan.planId(), "turn", "turn", 2, 1, plan.mode(), plan.summary(), plan.files(),
                List.of(new PlanChange(2, "turn", "保留现有入口\n增加展示", List.of(), List.of(), List.of())), PlanStatus.PLANNED);
        var attachment = fixture.select(null);
        assertTrue(attachment.body().contains("恢复时的计划快照 version=2"));
        assertTrue(attachment.body().contains("保留现有入口 增加展示"));
        assertEquals(2, attachment.body().lines().filter(line -> line.startsWith("- ")).count());
        assertTrue(attachment.body().contains("TOUCHED 仅表示发生过修改"));
        assertFalse(attachment.body().contains("已触达文件："));
        assertFalse(attachment.body().contains("待处理文件："));
        fixture.plan = null;
        assertNull(fixture.select(attachment));
    }

    static ToolExecutionRequest call(String id, String name) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments("{\"原始参数\":\"保持完整\"}").build();
    }

    static String success(String operation, AppPlan plan) {
        return PlanToolProtocolSupport.json(PlanToolResult.applied(operation, plan.planId(), plan.version(), plan.summary(), plan.files()));
    }

    static AppPlan plan(int version, PlanFileState state) {
        return new AppPlan("plan", "turn", "turn", version, 1, PlanMode.CHANGESET, "保留中文页面",
                List.of(new PlanFile("src/main.ts", "入口", PlanFileAction.KEEP, List.of(), PlanFileState.TOUCHED),
                        new PlanFile("src/App.vue", "主页面", PlanFileAction.MODIFY, List.of("src/main.ts"), state)),
                List.of(), state == PlanFileState.TOUCHED ? PlanStatus.READY_TO_BUILD : PlanStatus.PLANNED);
    }

    static final class Fixture {
        final long appId;
        L0Snapshot source;
        TurnRequestBoundary boundary = TurnRequestBoundary.of("保留用户原话", List.of(), 10, "turn");
        MemorySummarySnapshot summary = MemorySummarySnapshot.empty();
        AppPlan plan = plan(1, PlanFileState.PENDING);

        Fixture() { this(7); }
        Fixture(long appId) {
            this.appId = appId;
            source = L0SourceOperations.append(L0Snapshot.empty(Long.toString(appId), 0), boundary.userMessage(), boundary);
        }

        void add(ChatMessage message) { source = L0SourceOperations.append(source, message, null); }
        CompatibleMemorySnapshot snapshot() {
            return new CompatibleMemorySnapshot(appId, boundary.userHistoryId(), boundary.turnId(), summary, source);
        }
        L0Snapshot.PlanRestore select(L0Snapshot.PlanRestore previous) {
            source = PlanContext.select(snapshot(), plan, previous != null);
            return source.planRestore();
        }
        void coverThrough(long sequence, long revision) {
            summary = new MemorySummarySnapshot(UnifiedContextRequestGateTest.summaryText(), 0, revision, 2,
                    new SummaryCoverage(10, "turn", source.memoryEpoch(), sequence,
                            L0SourceOperations.fingerprint(source, 10, sequence, 0), 0, null, null, false));
        }
    }
}
