package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.memory.*;
import com.lyw.appgeneration.core.handler.VueTurnContext;
import com.lyw.appgeneration.service.CompatibleMemorySnapshot;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CompatibleMemorySnapshotTest {
    @Test
    void 旧摘要读取后后台推进并回收必须整对重读() throws Exception {
        try (var f = fixture(); var readers = Executors.newSingleThreadExecutor()) {
            f.service.commitUnifiedDraft(f.preparedDraft());
            var first = f.summary.get();
            append(f);
            f.service.commitUnifiedDraft(f.preparedDraft());
            var second = f.summary.get();
            f.summary.set(first);
            var firstRead = new CountDownLatch(1);
            var resume = new CountDownLatch(1);
            var pauseOnce = new AtomicBoolean(true);
            when(f.mapper.selectOneByQuery(any())).thenAnswer(invocation -> {
                var selected = f.summary.get();
                if (pauseOnce.compareAndSet(true, false)) {
                    firstRead.countDown();
                    assertTrue(resume.await(3, TimeUnit.SECONDS));
                }
                return selected;
            });
            var result = readers.submit(() -> f.service.readCompatible(f.request, deadline()));
            assertTrue(firstRead.await(2, TimeUnit.SECONDS));
            f.summary.set(second);
            assertEquals(DeadlineAwareReplaceResult.REPLACED, f.service.reclaimUnified(f.request, deadline()));
            resume.countDown();
            CompatibleMemorySnapshot snapshot = result.get(3, TimeUnit.SECONDS);
            assertEquals(2L, snapshot.summary().summaryRevision());
            assertEquals(2L, snapshot.source().appliedSummaryRevision());
            long lastCovered = snapshot.summary().coverageState().coveredSequence();
            assertEquals(lastCovered + 1, snapshot.source().entries().stream()
                    .filter(entry -> !(entry.payload() instanceof dev.langchain4j.data.message.UserMessage))
                    .mapToLong(L0Snapshot.Message::sequence).min().orElseThrow());
            verifyNoInteractions(f.model);
        }
    }

    @Test
    void 新摘要可以配完整旧窗口旧组合副本不受随后普通回收影响() {
        try (var f = fixture()) {
            f.service.commitUnifiedDraft(f.preparedDraft());
            var captured = f.service.readCompatible(f.request, deadline());
            assertEquals(1L, captured.summary().summaryRevision());
            assertEquals(0L, captured.source().appliedSummaryRevision());
            int count = captured.source().entries().size();
            f.service.reclaimUnified(f.request, deadline());
            assertTrue(f.store.value.get().entries().size() < count);
            assertEquals(count, captured.source().entries().size());
            assertEquals(0L, captured.source().appliedSummaryRevision());
        }
    }

    @Test
    void 重建必须携带实际摘要依赖未知旧数组不能伪造为零下界() {
        try (var f = fixture()) {
            f.service.commitUnifiedDraft(f.preparedDraft());
            var original = f.store.value.get();
            f.store.value.set(new L0Snapshot(1, "7", "rebuilt", original.nextSequence(), 1L, original.entries(), original.turns()));
            assertEquals("rebuilt", f.service.readCompatible(f.request, deadline()).source().memoryEpoch());
            f.store.value.set(new L0Snapshot(1, "7", "unproven", original.nextSequence(), 0L, original.entries(), original.turns()));
            assertThrows(IllegalArgumentException.class, () -> f.service.readCompatible(f.request, deadline()));
            f.store.value.set(L0Snapshot.legacy("7", original.messages()));
            assertThrows(IllegalStateException.class, () -> f.service.readCompatible(f.request, deadline()));
        }
    }

    @Test
    void 不相容重读用原截止而取消后不继续读或改写() {
        try (var f = fixture()) {
            var original = f.store.value.get();
            f.store.value.set(new L0Snapshot(1, "7", original.memoryEpoch(), original.nextSequence(), 1L, original.entries(), original.turns()));
            var reads = new AtomicInteger();
            when(f.mapper.selectOneByQuery(any())).thenAnswer(invocation -> {
                reads.incrementAndGet();
                return null;
            });
            long start = System.nanoTime();
            assertThrows(CancellationException.class, () -> f.service.readCompatible(f.request,
                    AdmissionDeadline.start(Duration.ofMillis(20))));
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1));
            assertTrue(reads.get() > 0);
            var stillIntact = f.store.value.get();
            f.request.context().tryStartCancellation(VueTurnContext.TerminalTrigger.CANCELLED);
            clearInvocations(f.mapper);
            assertThrows(CancellationException.class, () -> f.service.readCompatible(f.request, deadline()));
            verifyNoInteractions(f.mapper, f.model);
            assertEquals(stillIntact, f.store.value.get());
        }
    }

    @Test
    void 每次数据库读取使用独立已提交视图且等待沿用原截止() {
        try (var f = fixture()) {
            PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
            when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            ReflectionTestUtils.setField(f.service, "transactionManager", manager);
            var snapshot = f.service.readCompatible(f.request, deadline());
            assertTrue(snapshot.summary().summary().isEmpty());
            var definition = org.mockito.ArgumentCaptor.forClass(TransactionDefinition.class);
            verify(manager).getTransaction(definition.capture());
            assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, definition.getValue().getPropagationBehavior());
            assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED, definition.getValue().getIsolationLevel());
            assertTrue(definition.getValue().isReadOnly());
            assertTrue(definition.getValue().getTimeout() <= 5);
            verifyNoInteractions(f.model);
        }
    }

    private void append(UnifiedSummaryCoordinationTest.Fixture f) {
        for (int n = 11; n <= 20; n++) {
            int generation = n;
            SourceCommitScope.run(n, f.request.context(), () -> f.request.memory().add(AiMessage.from("后续" + generation + "原文".repeat(700))));
        }
    }
    private UnifiedSummaryCoordinationTest.Fixture fixture() {
        var fixture = new UnifiedSummaryCoordinationTest.Fixture(Executors.newSingleThreadExecutor());
        fixture.useSnapshotPersistence();
        return fixture;
    }
    private AdmissionDeadline deadline() { return AdmissionDeadline.start(Duration.ofSeconds(5)); }
}
