package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.memory.*;
import com.lyw.appgeneration.core.handler.VueTurnContext;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UnifiedSummaryTerminalTest {
    @Test
    void 已压缩前沿结束只登记真实失败和新修订不推进语义覆盖() {
        try (var f = new UnifiedSummaryCoordinationTest.Fixture(Executors.newSingleThreadExecutor())) {
            f.useSnapshotPersistence();
            var work = f.preparedDraft();
            f.service.commitUnifiedDraft(work);
            var before = f.service.readSnapshot(7L);
            when(f.evidence.mergeFinalAtCommit(eq(f.request.context()), any())).thenReturn(work.input().evidenceSnapshots().get(10L));
            assertTrue(f.request.context().tryStartFinalization(VueTurnContext.TerminalTrigger.FAILED));
            var ai = failedAi();
            assertSame(ai, f.service.persistTurnOutcome(f.request.context(), () -> ai));
            var after = f.service.readSnapshot(7L);
            assertEquals(before.summary(), after.summary());
            assertEquals(before.lastSummarizedId(), after.lastSummarizedId());
            assertEquals(before.summaryRevision() + 1, after.summaryRevision());
            assertEquals(before.coverageState().coveredSequence(), after.coverageState().coveredSequence());
            assertEquals(before.coverageState().evidenceRevision(), after.coverageState().evidenceRevision());
            assertEquals(ChatMemoryOutcome.FAILED, after.coverageState().terminalOutcome());
            assertEquals(20L, after.coverageState().terminalHistoryId());
            assertFalse(after.coverageState().terminalIncluded());
            assertEquals(11, f.store.value.get().entries().size());
            assertEquals(com.lyw.appgeneration.service.MemoryCompressionResult.Status.DELETE_REJECTED,
                    f.service.commitUnifiedDraft(work).status());
            verifyNoInteractions(f.model);
        }
    }

    @Test
    void 证据保存失败或非终态调用不能抢先保存AI结果() {
        try (var f = new UnifiedSummaryCoordinationTest.Fixture(Executors.newSingleThreadExecutor())) {
            var saved = new AtomicBoolean();
            assertThrows(IllegalStateException.class, () -> f.service.persistTurnOutcome(f.request.context(), () -> {
                saved.set(true); return failedAi();
            }));
            assertFalse(saved.get());
            f.request.context().tryStartFinalization(VueTurnContext.TerminalTrigger.FAILED);
            doThrow(new IllegalStateException("最终存证失败")).when(f.evidence).mergeFinalAtCommit(any(), any());
            assertThrows(IllegalStateException.class, () -> f.service.persistTurnOutcome(f.request.context(), () -> {
                saved.set(true); return failedAi();
            }));
            assertFalse(saved.get());
            assertNull(f.summary.get());
        }
    }

    private ChatHistory failedAi() {
        return ChatHistory.builder().id(20L).appId(7L).userId(9L).messageType("ai")
                .message("模型声称已成功完成").memoryMessage("真实构建失败，保留已修改文件")
                .memoryOutcome(ChatMemoryOutcome.FAILED).isDelete(0).build();
    }

    @Test
    void 删除后不读取冷重建摘要也不补写旧回合终态() {
        try (var f = new UnifiedSummaryCoordinationTest.Fixture(Executors.newSingleThreadExecutor())) {
            try (var deletion = f.lifecycle.beginDelete(7, java.time.Duration.ofSeconds(1))) {
                assertNotNull(deletion); deletion.commitTombstone();
            }
            clearInvocations(f.mapper);
            assertThrows(IllegalStateException.class, () -> f.service.readRebuildSnapshot(7L,
                    AdmissionDeadline.start(java.time.Duration.ofSeconds(1))));
            assertThrows(IllegalStateException.class, () -> f.service.persistRecoveredTurnOutcome(7, 9, 10,
                    () -> { fail("删除后不能保存旧回合"); return null; }));
            verifyNoInteractions(f.mapper, f.model);
        }
    }
}
