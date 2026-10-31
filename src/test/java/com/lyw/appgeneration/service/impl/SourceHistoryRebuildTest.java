package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.memory.*;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;
import com.lyw.appgeneration.service.*;
import com.mybatisflex.core.query.QueryWrapper;
import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SourceHistoryRebuildTest {
    @Test
    void 旧数组经持久历史对齐后建立新epoch并保留游标零摘要依赖() {
        var f = new Fixture(L0Snapshot.legacy("7", List.of(UserMessage.from("旧缓存"), AiMessage.from("旧文字"))));
        var user = user("旧用户原话".repeat(2_000));
        user.setDecisionEvidence(DecisionEvidenceCodec.encode(DecisionEvidence.empty(11, "original-turn")));
        doReturn(List.of(ai(), user)).when(f.histories).list(any(QueryWrapper.class));
        var coverage = new SummaryCoverage(11, "original-turn", "old-epoch", 50, "a".repeat(64), 0, 12L, ChatMemoryOutcome.FAILED, false);
        var summary = new MemorySummarySnapshot(UnifiedSummaryCoordinationTest.SUMMARY, 0, 3, 2, coverage);
        var result = f.load(summary);
        assertEquals(ChatHistoryService.HistoryLoadStatus.LOADED, result.status());
        var rebuilt = f.memory.sourceSnapshot();
        assertTrue(rebuilt.hasKnownSource());
        assertNotEquals("old-epoch", rebuilt.memoryEpoch());
        assertEquals(3L, rebuilt.appliedSummaryRevision());
        assertEquals(11, rebuilt.turns().getFirst().userHistoryId());
        assertEquals("original-turn", rebuilt.turns().getFirst().turnId());
        assertEquals(12, rebuilt.turns().getFirst().terminalHistoryId());
        assertEquals(2, rebuilt.entries().size());
        assertEquals(user.getMessage(), ((UserMessage) rebuilt.messages().getFirst()).singleText());
        assertEquals(12L, rebuilt.entries().getLast().historyRecordId());
        assertEquals(rebuilt, L0SourceOperations.prune(rebuilt, summary, 99), "旧 epoch 的序号不能裁剪新原文");
        assertFalse(rebuilt.messages().toString().contains("模型声称成功"));
        assertTrue(rebuilt.messages().toString().contains("真实构建失败"));
    }

    @Test
    void 已知窗口依赖更高摘要时受控拒绝而不清零() {
        var source = L0Snapshot.empty("7", 5);
        var f = new Fixture(source);
        assertEquals(ChatHistoryService.HistoryLoadStatus.FAILED, f.load(MemorySummarySnapshot.empty()).status());
        assertSame(source, f.store.value.get());
        verify(f.histories, never()).list(any(QueryWrapper.class));
    }

    @Test
    void 无法证明旧数组来源时不能按空历史补造零下界() {
        var source = L0Snapshot.legacy("7", List.of(UserMessage.from("未知旧用户")));
        var f = new Fixture(source);
        doReturn(List.of()).when(f.histories).list(any(QueryWrapper.class));
        assertEquals(ChatHistoryService.HistoryLoadStatus.FAILED, f.load(MemorySummarySnapshot.empty()).status());
        assertSame(source, f.store.value.get());
        assertNull(source.appliedSummaryRevision());
    }

    @Test
    void 空库空窗口可以确认无摘要依赖而创建新来源窗口() {
        var f = new Fixture(L0Snapshot.legacy("7", List.of()));
        doReturn(List.of()).when(f.histories).list(any(QueryWrapper.class));
        assertEquals(ChatHistoryService.HistoryLoadStatus.EMPTY, f.load(MemorySummarySnapshot.empty()).status());
        assertTrue(f.memory.sourceSnapshot().hasKnownSource());
        assertEquals(0L, f.memory.sourceSnapshot().appliedSummaryRevision());
    }

    @Test
    void 删除先取得边界时拒绝历史回填且不影响其他应用() {
        var original = L0Snapshot.empty("7", 2);
        var f = new Fixture(original);
        try (var deletion = f.lifecycle.beginDelete(7, Duration.ofSeconds(1))) {
            assertNotNull(deletion);
            deletion.commitTombstone();
        }
        var summary = new MemorySummarySnapshot(UnifiedSummaryCoordinationTest.SUMMARY, 4, 2, 2, null);
        assertEquals(ChatHistoryService.HistoryLoadStatus.FAILED, f.load(summary).status());
        assertSame(original, f.store.value.get());
        verify(f.histories, never()).list(any(QueryWrapper.class));
        try (var other = f.lifecycle.tryAcquireWriter(8)) { assertNotNull(other); }
    }

    @Test
    void 数据库读取失败或来源并发变化不覆盖原窗口() {
        var original = L0Snapshot.empty("7", 0);
        var f = new Fixture(original);
        doThrow(new IllegalStateException("数据库不可读")).when(f.histories).list(any(QueryWrapper.class));
        assertEquals(ChatHistoryService.HistoryLoadStatus.FAILED, f.load(MemorySummarySnapshot.empty()).status());
        assertSame(original, f.store.value.get());
        var late = L0Snapshot.empty("7", 1);
        doAnswer(invocation -> { f.store.value.set(late); return List.of(ai(), user("用户")); })
                .when(f.histories).list(any(QueryWrapper.class));
        assertEquals(ChatHistoryService.HistoryLoadStatus.FAILED, f.load(MemorySummarySnapshot.empty()).status());
        assertSame(late, f.store.value.get());
    }

    @Test
    void 跨应用记录与损坏证据均不能进入重建窗口() {
        var f = new Fixture(L0Snapshot.empty("7", 0));
        var foreign = ai(); foreign.setAppId(8L);
        doReturn(List.of(foreign, user("用户"))).when(f.histories).list(any(QueryWrapper.class));
        assertEquals(ChatHistoryService.HistoryLoadStatus.FAILED, f.load(MemorySummarySnapshot.empty()).status());
        var corrupt = user("用户"); corrupt.setDecisionEvidence("{}");
        doReturn(List.of(ai(), corrupt)).when(f.histories).list(any(QueryWrapper.class));
        assertEquals(ChatHistoryService.HistoryLoadStatus.FAILED, f.load(MemorySummarySnapshot.empty()).status());
        assertTrue(f.memory.sourceSnapshot().entries().isEmpty());
    }

    @Test
    void 重建所用摘要游标与修订原样交付且重复重建更换epoch() {
        var f = new Fixture(L0Snapshot.legacy("7", List.of()));
        doReturn(List.of(ai(), user("用户"))).when(f.histories).list(any(QueryWrapper.class));
        var summary = new MemorySummarySnapshot(UnifiedSummaryCoordinationTest.SUMMARY, 4, 2, 2, null);
        assertEquals(ChatHistoryService.HistoryLoadStatus.LOADED, f.load(summary).status());
        var first = f.memory.sourceSnapshot();
        assertEquals(2L, first.appliedSummaryRevision());
        assertEquals(ChatHistoryService.HistoryLoadStatus.LOADED, f.load(summary).status());
        assertNotEquals(first.memoryEpoch(), f.memory.sourceSnapshot().memoryEpoch());
        assertEquals(first.messages(), f.memory.sourceSnapshot().messages());
        assertEquals(2L, f.memory.sourceSnapshot().appliedSummaryRevision());
    }

    private static ChatHistory user(String text) {
        return ChatHistory.builder().id(11L).appId(7L).userId(9L).messageType("user").message(text).build();
    }
    private static ChatHistory ai() {
        return ChatHistory.builder().id(12L).appId(7L).userId(9L).messageType("ai").message("模型声称成功")
                .memoryMessage("真实构建失败").memoryOutcome(ChatMemoryOutcome.FAILED).build();
    }
    private static final class Fixture {
        final ChatHistoryServiceImpl histories = spy(new ChatHistoryServiceImpl());
        final com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence lifecycle = new com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence();
        final SourceMemoryTestStore store;
        final TokenAwareChatMemory memory;
        final ChatTokenEstimator estimator = mock(ChatTokenEstimator.class);
        Fixture(L0Snapshot initial) {
            org.springframework.test.util.ReflectionTestUtils.setField(histories, "lifecycleFence", lifecycle);
            store = new SourceMemoryTestStore(initial);
            var atomic = new AtomicChatMemoryStore(store);
            memory = new TokenAwareChatMemory(MessageWindowChatMemory.builder().id(7L).chatMemoryStore(atomic)
                    .maxMessages(Integer.MAX_VALUE).build(), atomic);
            when(estimator.estimateMessages(anyList())).thenReturn(100);
        }
        ChatHistoryService.HistoryLoadResult load(MemorySummarySnapshot summary) {
            return histories.loadSourceHistoryToMemory(7L, summary, memory, 57_344, estimator,
                    AdmissionDeadline.start(Duration.ofSeconds(5)));
        }
    }
}
