package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.memory.UserMemoryOperation;
import com.lyw.appgeneration.ai.memory.UserMemoryOperation.Action;
import com.lyw.appgeneration.ai.memory.UserMemoryOperation.Scope;
import com.lyw.appgeneration.mapper.AppMemoryMapper;
import com.lyw.appgeneration.mapper.AppMemoryCandidateMapper;
import com.lyw.appgeneration.mapper.AppMemoryEvidenceMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static com.lyw.appgeneration.service.impl.UserMemoryState.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class UserMemoryChangePlannerTest {
    private final Store store = new Store();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 30, 12, 0);

    @Test void 自由内容和同类多个属性不受三条上限限制() {
        for (int i = 1; i <= 8; i++) apply(add("自定义布局属性" + i, "自定义要求" + i, "EXPLICIT", i));
        assertEquals(8, store.rows.values().stream().filter(Item::active).count());
        apply(add("页面默认语言", "默认使用日语", "EXPLICIT", 20));
        apply(add("代码注释语言", "注释使用中文", "EXPLICIT", 21));
        assertEquals(10, store.rows.values().stream().filter(Item::active).count());
    }

    @Test void 隐式证据先独立候选且重放不重复计数() {
        var first = apply(add("页面默认语言", "日语", "IMPLICIT", 20));
        assertFalse(first.formalChanged());
        assertTrue(first.candidateChanged());
        Item candidate = pending();
        apply(op(Action.REINFORCE, candidate, null, "IMPLICIT", 20));
        assertEquals(1, store.rows.get(candidate.id()).evidenceCount());
        assertEquals(1, store.evidence(candidate).size());
        assertTrue(store.rows.values().stream().noneMatch(Item::active));
    }

    @Test void 两个不同完整回合晋升并保留候选及证据来源() {
        apply(add("页面默认语言", "中文", "IMPLICIT", 100));
        Item candidate = pending();
        apply(op(Action.REINFORCE, candidate, null, "IMPLICIT", 50));
        Item active = active();
        assertEquals(2, active.evidenceCount());
        assertEquals(100, active.lastEvidenceTurnId());
        assertEquals(candidate.id(), active.originCandidateId());
        assertEquals("PROMOTED", store.rows.get(candidate.id()).status());
        assertEquals(active.id(), store.rows.get(candidate.id()).promotedMemoryId());
        assertEquals(store.evidence(candidate), store.evidence(active));
    }

    @Test void 冲突候选积累不会改变正式内容证据状态或时间() {
        apply(add("页面默认语言", "中文", "EXPLICIT", 10));
        Item original = active();
        var plan = apply(op(Action.REPLACE, original, "日语", "IMPLICIT", 20, 30));
        assertFalse(plan.formalChanged());
        assertTrue(plan.candidateChanged());
        Item candidate = pending();
        apply(op(Action.REINFORCE, candidate, null, "IMPLICIT", 40, 50));
        assertEquals(original, store.rows.get(original.id()));
        assertEquals(4, pending().evidenceCount());
    }

    @Test void 明确采用冲突候选创建新正式版本() {
        apply(add("页面默认语言", "中文", "EXPLICIT", 10));
        Item chinese = active();
        apply(op(Action.REPLACE, chinese, "日语", "IMPLICIT", 20));
        Item candidate = pending();
        var plan = apply(op(Action.REINFORCE, candidate, null, "EXPLICIT", 30));
        assertTrue(plan.formalChanged());
        assertEquals("日语", active().content());
        assertEquals(2, active().evidenceCount());
        assertEquals("SUPERSEDED", store.rows.get(chinese.id()).status());
        assertEquals(active().id(), store.rows.get(chinese.id()).supersededBy());
        assertEquals("PROMOTED", store.rows.get(candidate.id()).status());
    }

    @Test void 中文日语中文保留三个正式版本而不复活旧行() {
        apply(add("页面默认语言", "中文", "EXPLICIT", 10));
        Item first = active();
        apply(op(Action.REPLACE, first, "日语", "EXPLICIT", 20));
        Item second = active();
        apply(op(Action.REPLACE, second, "中文", "EXPLICIT", 30));
        Item third = active();
        assertNotEquals(first.id(), third.id());
        assertEquals("中文", third.content());
        assertEquals(3, store.rows.size());
        assertEquals(second.id(), store.rows.get(first.id()).supersededBy());
        assertEquals(third.id(), store.rows.get(second.id()).supersededBy());
        assertEquals(1, third.evidenceCount());
    }

    @Test void 撤销正式后旧候选和新隐式证据都不能自行恢复() {
        apply(add("页面默认语言", "中文", "EXPLICIT", 10));
        Item formal = active();
        apply(op(Action.REPLACE, formal, "日语", "IMPLICIT", 20));
        Item candidate = pending();
        apply(op(Action.RETRACT, formal, null, "EXPLICIT", 30));
        apply(op(Action.REINFORCE, candidate, null, "IMPLICIT", 40, 50));
        assertTrue(store.rows.values().stream().noneMatch(Item::active));
        assertEquals("REVOKED", store.rows.get(formal.id()).status());
        assertEquals(30, store.rows.get(formal.id()).revokedByTurnId());
        assertEquals("CANDIDATE", store.rows.get(candidate.id()).status());
    }

    @Test void 撤销后的新明确要求使用新版本及撤销后的证据() {
        apply(add("页面默认语言", "中文", "EXPLICIT", 10));
        Item formal = active();
        apply(op(Action.REPLACE, formal, "日语", "IMPLICIT", 20));
        Item candidate = pending();
        apply(op(Action.RETRACT, formal, null, "EXPLICIT", 30));
        apply(op(Action.REINFORCE, candidate, null, "EXPLICIT", 40));
        assertEquals("日语", active().content());
        assertEquals(1, active().evidenceCount());
        assertEquals(List.of(40L), new ArrayList<>(store.evidence(active()).keySet()));
        assertEquals("REVOKED", store.rows.get(formal.id()).status());
    }

    @Test void 撤销前后混合到达的证据只累计撤销之后的回合() {
        apply(add("页面默认语言", "中文", "EXPLICIT", 10));
        apply(op(Action.RETRACT, active(), null, "EXPLICIT", 20));
        apply(add("页面默认语言", "中文", "EXPLICIT", 30));
        apply(op(Action.REINFORCE, active(), null, "IMPLICIT", 15, 40));
        assertEquals(2, active().evidenceCount());
        assertEquals(java.util.Set.of(30L, 40L), store.evidence(active()).keySet());
    }

    @Test void 撤销候选不影响正式且不允许旧证据重建() {
        apply(add("页面默认语言", "中文", "EXPLICIT", 10));
        Item formal = active();
        apply(op(Action.REPLACE, formal, "日语", "IMPLICIT", 20));
        Item candidate = pending();
        var plan = apply(op(Action.RETRACT, candidate, null, "EXPLICIT", 30));
        assertFalse(plan.formalChanged());
        apply(op(Action.REPLACE, formal, "日语", "IMPLICIT", 20));
        assertEquals(formal, store.rows.get(formal.id()));
        assertTrue(store.rows.values().stream().noneMatch(Item::pending));
    }

    @Test void 候选撤销后单纯新隐式证据也不得自动激活同一取值() {
        apply(add("页面默认语言", "日语", "IMPLICIT", 10));
        apply(op(Action.RETRACT, pending(), null, "EXPLICIT", 20));
        apply(add("页面默认语言", "日语", "IMPLICIT", 30, 40));
        assertTrue(store.rows.values().stream().noneMatch(Item::active));
        apply(op(Action.REINFORCE, pending(), null, "EXPLICIT", 50));
        assertEquals("日语", active().content());
    }

    @Test void 旧回合的冲突改口不会倒退新偏好() {
        apply(add("页面默认语言", "日语", "EXPLICIT", 100));
        Item original = active();
        var plan = apply(op(Action.REPLACE, original, "中文", "EXPLICIT", 50));
        assertTrue(plan.changes().isEmpty());
        assertEquals(original, active());
    }

    @Test void 同内容新确认只补证据且不会降级() {
        apply(add("页面默认语言", "中文", "EXPLICIT", 10));
        Item original = active();
        apply(op(Action.REINFORCE, original, null, "IMPLICIT", 20));
        assertEquals(original.id(), active().id());
        assertEquals("EXPLICIT", active().evidenceType());
        assertEquals(2, active().evidenceCount());
    }

    @Test void 旧证据基线保留且已知最后回合不重复计入() {
        Item candidate = Item.builder().kind(Kind.CANDIDATE).id(1).userId(7).appId(100L)
                .category("语言偏好").memoryKey("ui.default_language").attributeName("页面默认语言")
                .content("日语").status("CANDIDATE").evidenceType("IMPLICIT").evidenceCount(1)
                .legacyEvidenceCount(1).legacyEvidenceTurnId(20L).lastEvidenceTurnId(20).revision(1)
                .createTime(NOW).updateTime(NOW).build();
        store.rows.put(1L, candidate);
        apply(op(Action.REINFORCE, candidate, null, "IMPLICIT", 20));
        assertEquals(1, pending().evidenceCount());
        apply(op(Action.REINFORCE, candidate, null, "IMPLICIT", 30));
        assertEquals(2, active().evidenceCount());
        assertEquals(1, active().legacyEvidenceCount());
        assertEquals(1, store.evidence(active()).size());
    }

    @Test void 过期快照未知目标及他人目标整批拒绝() {
        apply(add("页面默认语言", "中文", "EXPLICIT", 10));
        Snapshot shown = store.load(7);
        Item original = active();
        apply(op(Action.REPLACE, original, "日语", "EXPLICIT", 20));
        assertThrows(IllegalStateException.class, () -> planner().plan(
                List.of(op(Action.REPLACE, original, "英文", "EXPLICIT", 30)), shown));
        assertThrows(IllegalArgumentException.class, () -> planner().plan(
                List.of(op(Action.RETRACT, original, null, "EXPLICIT", 40)), store.load(7)));
        Item other = original.toBuilder().userId(8).build();
        assertThrows(IllegalArgumentException.class, () -> planner().plan(
                List.of(op(Action.RETRACT, other, null, "EXPLICIT", 40)), Snapshot.of(List.of(other))));
        assertEquals("日语", active().content());
    }

    @Test void 隐藏正式属性不能通过新增绕过冲突检测() {
        apply(add("页面默认语言", "中文", "EXPLICIT", 10));
        assertThrows(IllegalArgumentException.class, () -> apply(add("默认语言", "日语", "EXPLICIT", 20)));
        assertEquals("中文", active().content());
    }

    @Test void 新增弱冲突安全进入候选而不要求模型修改正式() {
        apply(add("页面默认语言", "中文", "EXPLICIT", 10));
        Item original = active();
        var plan = apply(add("默认语言", "日语", "IMPLICIT", 20, 30));
        assertFalse(plan.formalChanged());
        assertTrue(plan.candidateChanged());
        assertEquals(original, active());
        assertEquals("日语", pending().content());
    }

    @Test void 同内容弱候选的新增及强化不改变正式证据() {
        apply(add("页面默认语言", "中文", "EXPLICIT", 10));
        Item original = active();
        var first = apply(add("页面默认语言", "中文", "IMPLICIT", 20));
        assertFalse(first.formalChanged());
        Item candidate = pending();
        var second = apply(op(Action.REINFORCE, candidate, null, "IMPLICIT", 30));
        assertFalse(second.formalChanged());
        assertEquals(original, active());
        assertEquals(2, pending().evidenceCount());
    }

    @Test void 目标操作可以回显正确属性但不能借此更改归属() {
        apply(add("页面默认语言", "中文", "EXPLICIT", 10));
        Item target = active();
        assertThrows(IllegalArgumentException.class, () -> apply(new UserMemoryOperation(Action.RETRACT,
                target.id(), "语言偏好", "代码注释语言", null, Scope.USER, "EXPLICIT", List.of(20L))));
        apply(new UserMemoryOperation(Action.RETRACT, target.id(), "语言偏好", "默认语言", null,
                Scope.USER, "EXPLICIT", List.of(20L)));
        assertEquals("REVOKED", store.rows.get(target.id()).status());
    }

    @Test void 混合新增与撤销不同属性在同一计划内生效() {
        apply(add("页面默认语言", "中文", "EXPLICIT", 10));
        var plan = apply(op(Action.RETRACT, active(), null, "EXPLICIT", 20),
                add("代码注释语言", "日语", "EXPLICIT", 20));
        assertEquals(2, plan.changes().size());
        assertEquals("code.comment_language", active().memoryKey());
    }

    @Test void 矛盾目标整批失败且规划不会提前写入() {
        apply(add("页面默认语言", "中文", "EXPLICIT", 10));
        Map<Long, Item> original = Map.copyOf(store.rows);
        assertThrows(IllegalArgumentException.class, () -> apply(
                add("注释语言", "英文", "EXPLICIT", 20),
                op(Action.REPLACE, active(), "日语", "EXPLICIT", 20),
                op(Action.RETRACT, active(), null, "EXPLICIT", 20)));
        assertEquals(original, store.rows);
    }

    @Test void 同属性两个候选都够次数时不靠数组顺序激活() {
        apply(add("页面默认语言", "中文", "IMPLICIT", 10));
        Item first = pending();
        apply(add("页面默认语言", "日语", "IMPLICIT", 20));
        Item second = store.rows.values().stream().filter(i -> i.content().equals("日语")).findFirst().orElseThrow();
        apply(op(Action.REINFORCE, first, null, "IMPLICIT", 30), op(Action.REINFORCE, second, null, "IMPLICIT", 40));
        assertTrue(store.rows.values().stream().noneMatch(Item::active));
    }

    private UserMemoryChangePlanner planner() { return new UserMemoryChangePlanner(store, 7, 100L, store.load(7), NOW); }
    private UserMemoryChangePlanner.Plan apply(UserMemoryOperation... operations) {
        var plan = planner().plan(List.of(operations), store.load(7));
        plan.apply(store);
        return plan;
    }
    private Item active() { return store.rows.values().stream().filter(Item::active).findFirst().orElseThrow(); }
    private Item pending() { return store.rows.values().stream().filter(Item::pending).findFirst().orElseThrow(); }
    private UserMemoryOperation add(String attribute, String content, String evidence, long... turns) {
        return new UserMemoryOperation(Action.ADD, null, "语言偏好", attribute, content, Scope.USER, evidence,
                java.util.Arrays.stream(turns).boxed().toList());
    }
    private UserMemoryOperation op(Action action, Item target, String content, String evidence, long... turns) {
        return new UserMemoryOperation(action, target.id(), action == Action.REPLACE ? target.category() : null,
                null, content, Scope.USER, evidence, java.util.Arrays.stream(turns).boxed().toList());
    }

    static final class Store extends VersionedUserMemoryRepository {
        final Map<Long, Item> rows = new LinkedHashMap<>();
        final Map<Long, Map<Long, Evidence>> ledgers = new LinkedHashMap<>();
        final AtomicLong ids = new AtomicLong(1000);
        Store() { super(mock(AppMemoryMapper.class), mock(AppMemoryCandidateMapper.class), mock(AppMemoryEvidenceMapper.class)); }
        @Override long nextId() { return ids.incrementAndGet(); }
        @Override Snapshot load(long userId) { return Snapshot.of(rows.values().stream()
                .filter(i -> i.userId() == userId && (i.active() || i.pending() || i.revoked())).toList()); }
        @Override Snapshot loadCurrent(long userId) { return load(userId); }
        @Override Map<Long, Evidence> evidence(Item item) { return Map.copyOf(ledgers.getOrDefault(item.id(), Map.of())); }
        @Override void insert(Item item) { assertNull(rows.putIfAbsent(item.id(), item)); }
        @Override void update(Item before, Item after) { assertEquals(before, rows.get(before.id())); rows.put(after.id(), after); }
        @Override void addEvidence(Item target, Long appId, long turnId, String type, LocalDateTime now) {
            assertNull(ledgers.computeIfAbsent(target.id(), ignored -> new LinkedHashMap<>())
                    .putIfAbsent(turnId, new Evidence(turnId, appId, type)));
        }
    }
}
