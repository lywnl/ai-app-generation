package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;
import com.lyw.appgeneration.service.MemorySummarySnapshot;
import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class L0SourceOperationsTest {
    static final String SUMMARY = """
            # 应用目标与定位
            生成页面
            # 用户偏好与硬约束
            简体中文
            # 已否决的方案
            不要轮播图
            # 关键设计决策与理由
            组件复用
            # 当前进度速览
            仍需检查报错
            """;

    @Test
    void 原子追加保留持久身份并防止整窗口回写覆盖已保存来源() {
        var fixture = fixture();
        var boundary = TurnRequestBoundary.of("需求", List.of(), 10, "turn");
        CanonicalUserMessageScope.call(boundary, () -> { fixture.memory.add(UserMessage.from("需求")); return null; });
        fixture.memory.add(AiMessage.from("说明"));
        var before = fixture.memory.sourceSnapshot();
        fixture.memory.add(SystemMessage.from("新系统规则"));
        var after = fixture.memory.sourceSnapshot();
        assertEquals(before.entries(), after.entries().subList(1, after.entries().size()));
        assertEquals(before.nextSequence(), after.nextSequence());
        assertEquals(10, after.entries().get(1).userHistoryId());
        assertThrows(IllegalStateException.class,
                () -> L0SourceOperations.reconcile(after, List.of(UserMessage.from("偷偷改写")), boundary));
        assertThrows(IllegalStateException.class, fixture.memory::clear);
    }

    @Test
    void 来源裁剪与下界同次提交并保留晚到追加及原始序号() {
        var fixture = populated();
        L0Snapshot captured = fixture.memory.sourceSnapshot();
        var coverage = new SummaryCoverage(10, "turn", captured.memoryEpoch(), 2,
                L0SourceOperations.fingerprint(captured, 10, 2, 1), 1, null, null, false);
        var summary = new MemorySummarySnapshot(SUMMARY, 0, 1, 2, coverage);
        fixture.memory.add(AiMessage.from("晚到追加"));
        var latest = fixture.memory.sourceSnapshot();
        assertTrue(L0SourceOperations.containsPrefix(latest, captured, 2));
        var candidate = L0SourceOperations.prune(latest, summary, 10);
        fixture.store.rejectWrite = true;
        assertEquals(DeadlineAwareReplaceResult.DEPENDENCY_FAILED,
                fixture.memory.replaceSourceSnapshot(latest, candidate, deadline()));
        assertEquals(0L, fixture.memory.sourceSnapshot().appliedSummaryRevision());
        assertEquals(latest.messages(), fixture.memory.messages());
        fixture.store.rejectWrite = false;
        assertEquals(DeadlineAwareReplaceResult.REPLACED,
                fixture.memory.replaceSourceSnapshot(latest, candidate, deadline()));
        var saved = fixture.memory.sourceSnapshot();
        assertEquals(1L, saved.appliedSummaryRevision());
        assertEquals(List.of(1L, 3L, 4L), saved.entries().stream().map(L0Snapshot.Message::sequence).toList());
        assertEquals(latest.nextSequence(), saved.nextSequence());
        assertEquals(AiMessage.from("晚到追加"), saved.messages().getLast());
        assertEquals(saved, L0SourceOperations.prune(saved, summary, 10));
        assertEquals(DeadlineAwareReplaceResult.PREFIX_CHANGED,
                fixture.memory.replaceSourceSnapshot(captured, L0SourceOperations.prune(captured, summary, 10), deadline()));
    }

    @Test
    void 恢复新epoch保留摘要依赖而旧epoch不能覆盖新窗口() {
        var fixture = populated();
        var before = fixture.memory.sourceSnapshot();
        var dependent = new L0Snapshot(1, "7", before.memoryEpoch(), before.nextSequence(), 4L, before.entries(), before.turns());
        fixture.store.value.set(dependent);
        var reset = L0SourceOperations.resetActive(dependent, List.of(dependent.messages().getFirst(), AiMessage.from("恢复说明")));
        assertNotEquals(dependent.memoryEpoch(), reset.memoryEpoch());
        assertEquals(4L, reset.appliedSummaryRevision());
        assertEquals(DeadlineAwareReplaceResult.REPLACED, fixture.memory.replaceSourceSnapshot(dependent, reset, deadline()));
        assertEquals(DeadlineAwareReplaceResult.PREFIX_CHANGED,
                fixture.memory.replaceSourceSnapshot(dependent, dependent, deadline()));
        assertFalse(L0SourceOperations.containsPrefix(reset, dependent, 2));
        var invalid = new L0Snapshot(1, "7", reset.memoryEpoch(), reset.nextSequence(), 0L, reset.entries(), reset.turns());
        assertThrows(IllegalArgumentException.class, () -> fixture.memory.replaceSourceSnapshot(reset, invalid, deadline()));
    }

    @Test
    void 收尾只登记真实终态并允许下一条相同原文绑定新记录() {
        var fixture = populated();
        var before = fixture.memory.sourceSnapshot();
        fixture.memory.closeSourceTurn(10, "turn", 20, ChatMemoryOutcome.FAILED);
        fixture.memory.closeSourceTurn(10, "turn", 20, ChatMemoryOutcome.FAILED);
        assertEquals(before.messages(), fixture.memory.messages());
        assertEquals(0L, fixture.memory.sourceSnapshot().appliedSummaryRevision());
        assertThrows(IllegalStateException.class,
                () -> fixture.memory.closeSourceTurn(10, "turn", 21, ChatMemoryOutcome.SUCCEEDED));
        var boundary = TurnRequestBoundary.of("需求", List.of(), 30, "next");
        CanonicalUserMessageScope.call(boundary, () -> { fixture.memory.add(UserMessage.from("需求")); return null; });
        assertEquals(List.of(10L, 30L), fixture.memory.sourceSnapshot().turns().stream()
                .map(L0Snapshot.Turn::userHistoryId).toList());
    }

    @Test
    void 共享锁下多个追加不互相覆盖且截止后不提交() throws Exception {
        var fixture = populated();
        var second = new TokenAwareChatMemory(MessageWindowChatMemory.builder().id(7L)
                .chatMemoryStore(fixture.atomic).maxMessages(Integer.MAX_VALUE).build(), fixture.atomic);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> { start.await(); fixture.memory.add(AiMessage.from("并发甲")); return true; });
            var other = executor.submit(() -> { start.await(); second.add(AiMessage.from("并发乙")); return true; });
            start.countDown();
            assertTrue(first.get(2, TimeUnit.SECONDS));
            assertTrue(other.get(2, TimeUnit.SECONDS));
        }
        assertTrue(fixture.memory.messages().containsAll(List.of(AiMessage.from("并发甲"), AiMessage.from("并发乙"))));
        var snapshot = fixture.memory.sourceSnapshot();
        assertEquals(DeadlineAwareReplaceResult.TIMED_OUT,
                fixture.memory.replaceSourceSnapshot(snapshot, snapshot, AdmissionDeadline.start(Duration.ZERO)));
    }

    private Fixture populated() {
        var fixture = fixture();
        CanonicalUserMessageScope.call(TurnRequestBoundary.of("需求", List.of(), 10, "turn"), () -> {
            fixture.memory.add(UserMessage.from("需求")); return null;
        });
        fixture.memory.add(AiMessage.from("较早说明"));
        fixture.memory.add(AiMessage.from("最近说明"));
        return fixture;
    }

    private Fixture fixture() {
        var store = new SourceMemoryTestStore(L0Snapshot.empty("7", 0));
        var atomic = new AtomicChatMemoryStore(store);
        var memory = new TokenAwareChatMemory(MessageWindowChatMemory.builder().id(7L)
                .chatMemoryStore(atomic).maxMessages(Integer.MAX_VALUE).build(), atomic);
        return new Fixture(store, atomic, memory);
    }

    private AdmissionDeadline deadline() { return AdmissionDeadline.start(Duration.ofSeconds(5)); }
    private record Fixture(SourceMemoryTestStore store, AtomicChatMemoryStore atomic, TokenAwareChatMemory memory) { }
}
