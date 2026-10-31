package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.memory.*;
import com.lyw.appgeneration.core.handler.VueTurnContext;
import com.lyw.appgeneration.model.entity.AppMemorySummary;
import com.lyw.appgeneration.service.MemoryCompressionResult;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 事务与来源的本地故障注入；真实存储组合另有显式启用的集成验证。 */
class UnifiedSummaryPersistenceTest {
    @Test
    void 新增原文不使已选前缀失效而完整快照只提交一次() {
        try (var f = fixture()) {
            var work = f.preparedDraft();
            SourceCommitScope.run(11, f.request.context(), () -> f.request.memory().add(AiMessage.from("后追加，不能被本次覆盖")));
            var source = f.store.value.get();
            var result = f.service.commitUnifiedDraft(work);
            assertEquals(MemoryCompressionResult.Status.COMPRESSED, result.status());
            assertEquals(work.input().committed(work.draft().summary()), f.service.readUnifiedSnapshot(7));
            assertEquals(1, f.summary.get().getSummaryRevision());
            assertEquals(source, f.store.value.get(), "可靠提交尚未执行第二阶段回收");
            assertThrows(IllegalStateException.class, () -> f.service.commitUnifiedDraft(work));
            verify(f.mapper, times(1)).insert(any(AppMemorySummary.class));
            verify(f.evidence, times(2)).validateSummaryInput(work.input());
        }
    }

    @Test
    void 来源前缀变化或epoch重置均拒绝旧草稿() {
        for (boolean resetEpoch : new boolean[]{false, true}) {
            try (var f = fixture()) {
                var work = f.preparedDraft();
                var before = f.store.value.get();
                var entries = new ArrayList<>(before.entries());
                var changed = entries.get(1);
                entries.set(1, new L0Snapshot.Message(changed.sequence(), changed.userHistoryId(), AiMessage.from("修改已选原文")));
                f.store.value.set(new L0Snapshot(1, "7", resetEpoch ? "recovered-epoch" : before.memoryEpoch(),
                        before.nextSequence(), before.appliedSummaryRevision(), entries, before.turns()));
                assertThrows(IllegalStateException.class, () -> f.service.commitUnifiedDraft(work));
                assertNull(f.summary.get());
                verify(f.mapper, never()).insert(any(AppMemorySummary.class));
            }
        }
    }

    @Test
    void 证据修订变化与条件更新竞争均保留原文和赢家快照() {
        try (var f = fixture()) {
            var work = f.preparedDraft();
            var source = f.store.value.get();
            doThrow(new IllegalStateException("证据修订变化")).when(f.evidence).validateSummaryInput(work.input());
            assertThrows(IllegalStateException.class, () -> f.service.commitUnifiedDraft(work));
            assertNull(f.summary.get());
            assertEquals(source, f.store.value.get());
        }
        try (var f = fixture()) {
            var work = f.preparedDraft();
            f.service.commitUnifiedDraft(work);
            for (int n = 11; n <= 20; n++) {
                int generation = n;
                SourceCommitScope.run(n, f.request.context(), () -> f.request.memory()
                        .add(AiMessage.from("后续" + generation + "原文".repeat(700))));
            }
            var next = f.preparedDraft();
            var source = f.store.value.get();
            var winner = f.summary.get();
            when(f.mapper.commitSnapshot(any(), anyLong())).thenReturn(0);
            assertThrows(IllegalStateException.class, () -> f.service.commitUnifiedDraft(next));
            assertSame(winner, f.summary.get());
            assertEquals(source, f.store.value.get());
        }
    }

    @Test
    void 取消删除或终态先取得边界时不能提交草稿() {
        for (String event : new String[]{"cancel", "delete", "finalize"}) {
            try (var f = fixture()) {
                var work = f.preparedDraft();
                if (event.equals("cancel")) f.request.context().tryStartCancellation(VueTurnContext.TerminalTrigger.CANCELLED);
                else if (event.equals("finalize")) f.request.context().tryStartFinalization(VueTurnContext.TerminalTrigger.COMPLETED);
                else try (var permit = f.lifecycle.beginDelete(7, Duration.ZERO)) { assertNotNull(permit); permit.commitTombstone(); }
                var result = f.service.commitUnifiedDraft(work);
                assertEquals(MemoryCompressionResult.Status.DELETE_REJECTED, result.status());
                assertNull(f.summary.get());
                verify(f.mapper, never()).insert(any(AppMemorySummary.class));
            }
        }
    }

    @Test
    void 首次插入冲突只重读不覆盖另一个赢家() {
        try (var f = fixture()) {
            var work = f.preparedDraft();
            doAnswer(invocation -> {
                AppMemorySummary winner = invocation.getArgument(0);
                winner.setId(3L);
                f.summary.set(winner);
                throw new org.springframework.dao.DuplicateKeyException("其他事务已写入");
            }).when(f.mapper).insert(any(AppMemorySummary.class));
            assertThrows(IllegalStateException.class, () -> f.service.commitUnifiedDraft(work));
            assertEquals(3L, f.summary.get().getId());
            verify(f.mapper, never()).commitSnapshot(any(), anyLong());
            assertEquals(0, f.store.value.get().appliedSummaryRevision());
        }
    }

    private UnifiedSummaryCoordinationTest.Fixture fixture() {
        var fixture = new UnifiedSummaryCoordinationTest.Fixture(Executors.newSingleThreadExecutor());
        fixture.useSnapshotPersistence();
        return fixture;
    }
}
