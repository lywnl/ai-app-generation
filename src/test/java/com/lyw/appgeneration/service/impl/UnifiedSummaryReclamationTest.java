package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.memory.*;
import com.lyw.appgeneration.core.handler.VueTurnContext;
import com.lyw.appgeneration.service.MemoryCompressionResult;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UnifiedSummaryReclamationTest {
    @Test
    void Redis失败不撤销摘要重试同时裁剪并推进读取下界且不再调模型() {
        try (var f = fixture(TransactionOperations.withoutTransaction())) {
            var work = f.preparedDraft();
            f.service.commitUnifiedDraft(work);
            var committed = f.service.readUnifiedSnapshot(7);
            var original = f.store.value.get();
            f.store.rejectWrite = true;
            assertEquals(DeadlineAwareReplaceResult.DEPENDENCY_FAILED, f.service.reclaimUnified(f.request, deadline()));
            assertEquals(original, f.store.value.get());
            assertEquals(committed, f.service.readUnifiedSnapshot(7));
            f.store.rejectWrite = false;
            SourceCommitScope.run(11, f.request.context(), () -> f.request.memory().add(AiMessage.from("晚到原文必须留下")));
            assertEquals(DeadlineAwareReplaceResult.REPLACED, f.service.reclaimUnified(f.request, deadline()));
            var pruned = f.store.value.get();
            assertTrue(pruned.entries().size() < original.entries().size());
            assertEquals(committed.summaryRevision(), pruned.appliedSummaryRevision());
            assertEquals("晚到原文必须留下", ((AiMessage) pruned.messages().getLast()).text());
            assertTrue(pruned.messages().getFirst() instanceof UserMessage);
            assertEquals(DeadlineAwareReplaceResult.REPLACED, f.service.reclaimUnified(f.request, deadline()));
            assertEquals(pruned, f.store.value.get());
            verifyNoInteractions(f.model);
        }
    }

    @Test
    void 新epoch及截止取消不能使旧覆盖越界删除原文() {
        try (var f = fixture(TransactionOperations.withoutTransaction())) {
            f.service.commitUnifiedDraft(f.preparedDraft());
            var old = f.store.value.get();
            var rebuilt = new L0Snapshot(1, "7", "new-epoch", old.nextSequence(), old.appliedSummaryRevision(), old.entries(), old.turns());
            f.store.value.set(rebuilt);
            assertEquals(DeadlineAwareReplaceResult.REPLACED, f.service.reclaimUnified(f.request, deadline()));
            assertEquals(rebuilt, f.store.value.get());
            assertEquals(DeadlineAwareReplaceResult.TIMED_OUT, f.service.reclaimUnified(f.request, AdmissionDeadline.start(Duration.ZERO)));
            f.request.context().tryStartCancellation(VueTurnContext.TerminalTrigger.CANCELLED);
            assertEquals(DeadlineAwareReplaceResult.PREFIX_CHANGED, f.service.reclaimUnified(f.request, deadline()));
            assertEquals(rebuilt, f.store.value.get());
        }
    }

    @Test
    void 数据库提交确认丢失只有重读相同快照后才回收() {
        TransactionOperations lostAcknowledgement = new TransactionOperations() {
            @Override public <T> T execute(TransactionCallback<T> callback) {
                callback.doInTransaction(new SimpleTransactionStatus());
                throw new IllegalStateException("仅提交确认丢失");
            }
        };
        try (var f = fixture(lostAcknowledgement)) {
            when(f.model.chat(anyString())).thenReturn(UnifiedSummaryCoordinationTest.SUMMARY);
            var result = f.service.compressUnified(f.request, deadline());
            assertEquals(MemoryCompressionResult.Status.COMPRESSED, result.status());
            assertEquals(1L, f.summary.get().getSummaryRevision());
            assertEquals(1L, f.store.value.get().appliedSummaryRevision());
            verify(f.model, times(1)).chat(anyString());
        }
    }

    @Test
    void 无法确认数据库提交时保留原文恢复后仅重试回收() {
        var unavailable = new AtomicBoolean();
        TransactionOperations uncertain = new TransactionOperations() {
            @Override public <T> T execute(TransactionCallback<T> callback) {
                callback.doInTransaction(new SimpleTransactionStatus());
                unavailable.set(true);
                throw new IllegalStateException("提交状态暂时不可读取");
            }
        };
        try (var f = fixture(uncertain)) {
            when(f.model.chat(anyString())).thenReturn(UnifiedSummaryCoordinationTest.SUMMARY);
            when(f.mapper.selectOneByQuery(any())).thenAnswer(invocation -> {
                if (unavailable.get()) throw new IllegalStateException("数据库读取暂不可用");
                return f.summary.get();
            });
            var before = f.store.value.get();
            assertEquals(MemoryCompressionResult.Status.MODEL_FAILED, f.service.compressUnified(f.request, deadline()).status());
            assertEquals(before, f.store.value.get());
            assertEquals(1L, f.summary.get().getSummaryRevision());
            unavailable.set(false);
            assertEquals(DeadlineAwareReplaceResult.REPLACED, f.service.reclaimUnified(f.request, deadline()));
            assertEquals(1L, f.store.value.get().appliedSummaryRevision());
            verify(f.model, times(1)).chat(anyString());
        }
    }

    private UnifiedSummaryCoordinationTest.Fixture fixture(TransactionOperations transactions) {
        var fixture = new UnifiedSummaryCoordinationTest.Fixture(Executors.newSingleThreadExecutor(), transactions);
        fixture.useSnapshotPersistence();
        return fixture;
    }

    private AdmissionDeadline deadline() { return AdmissionDeadline.start(Duration.ofSeconds(5)); }
}
