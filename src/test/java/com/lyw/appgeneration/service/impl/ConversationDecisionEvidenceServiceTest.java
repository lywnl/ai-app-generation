package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.memory.*;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.mapper.AppMapper;
import com.lyw.appgeneration.mapper.ChatHistoryMapper;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.core.handler.VueTurnContext;
import com.lyw.appgeneration.core.builder.VueBuildPhase;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.service.tool.ToolExecution;
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConversationDecisionEvidenceServiceTest {
    private final ChatHistoryMapper histories = mock(ChatHistoryMapper.class);
    private final AppMapper apps = mock(AppMapper.class);
    private final AppDataLifecycleFence lifecycle = new AppDataLifecycleFence();
    private final ChatHistory row = ChatHistory.builder().id(10L).appId(7L).userId(9L).messageType("user")
            .message("不要轮播图，因为遮挡移动内容").isDelete(0).build();
    private ConversationDecisionEvidenceService service;

    @BeforeEach void setup() {
        TransactionOperations localTransactions = new TransactionOperations() {
            @Override public <T> T execute(TransactionCallback<T> callback) { return callback.doInTransaction(new SimpleTransactionStatus()); }
        };
        service = new ConversationDecisionEvidenceService(histories, apps, lifecycle, localTransactions);
        when(apps.selectOneByQuery(any())).thenReturn(App.builder().id(7L).userId(9L).isDelete(0).build());
        when(histories.selectOneByQuery(any())).thenReturn(row);
        when(histories.updateByQuery(any(ChatHistory.class), eq(true), any(QueryWrapper.class))).thenAnswer(invocation -> {
            ChatHistory update = invocation.getArgument(0);
            assertNull(update.getMessage());
            assertNull(update.getMemoryMessage());
            assertNull(update.getMemoryOutcome());
            row.setDecisionEvidence(update.getDecisionEvidence());
            return 1;
        });
    }

    @Test void 短回合独立存证且重复与迟到采集不覆盖较新证据() {
        var user = new DecisionEvidence.Entry("user:10", DecisionEvidence.SourceKind.USER, "", 0,
                List.of(), List.of(), List.of(), List.of(), 0);
        var first = service.merge(7, 9, 10, "turn", List.of(user), "epoch", 1, 1, 1000);
        assertEquals(1, first.revision());
        assertEquals(first, service.merge(7, 9, 10, "turn", List.of(user), "epoch", 1, 1, 1000));
        var next = service.merge(7, 9, 10, "turn", List.of(user), "epoch", 3, 2, 1000);
        assertEquals(2, next.revision());
        assertEquals(next, service.merge(7, 9, 10, "turn", List.of(user), "epoch", 1, 1, 1000));
        assertEquals(next, service.read(7, 9, 10));
        assertEquals("不要轮播图，因为遮挡移动内容", row.getMessage());
        verify(histories, times(2)).updateByQuery(any(ChatHistory.class), eq(true), any(QueryWrapper.class));
    }

    @Test void 归属错误删除关门和修订更新失败都拒绝写入() {
        when(apps.selectOneByQuery(any())).thenReturn(App.builder().id(7L).userId(88L).isDelete(0).build());
        assertThrows(IllegalStateException.class, () -> service.read(7, 9, 10));
        verify(histories, never()).selectOneByQuery(any());
        try (var deletion = lifecycle.beginDelete(7, Duration.ofSeconds(1))) {
            assertNotNull(deletion);
            deletion.commitTombstone();
        }
        assertThrows(IllegalStateException.class, () -> service.merge(7, 9, 10, "turn", List.of(), "epoch", 1, 1, 1000));
        assertNull(row.getDecisionEvidence());
    }

    @Test void 保存失败不返回已完成存证快照() {
        when(histories.updateByQuery(any(ChatHistory.class), eq(true), any(QueryWrapper.class))).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> service.merge(7, 9, 10, "turn", List.of(), "epoch", 1, 1, 1000));
        assertNull(row.getDecisionEvidence());
    }

    @Test void 删除后的用户记录及其他持久回合不能被迟到采集回填() {
        row.setIsDelete(1);
        assertThrows(IllegalStateException.class,
                () -> service.merge(7, 9, 10, "turn", List.of(), "epoch", 1, 1, 1000));
        row.setIsDelete(0);
        row.setId(30L);
        assertThrows(IllegalStateException.class, () -> service.read(7, 9, 10));
        verify(histories, never()).updateByQuery(any(ChatHistory.class), eq(true), any(QueryWrapper.class));
    }

    @Test void 来源采集落后时不可回收保存成功后同边界重试幂等() {
        var context = realContext();
        context.commitUserAndBindHistory(() -> row);
        var request = ToolExecutionRequest.builder().id("read-1").name("readFile").arguments("{}").build();
        String result = "{\"protocol\":\"file-tool/v1\",\"operation\":\"readFile\",\"status\":\"APPLIED\","
                + "\"relativePath\":\"src/App.vue\",\"changed\":false,\"message\":\"已读取\",\"failureReason\":null,\"content\":\"源码\"}";
        var entries = List.of(new L0Snapshot.Message(1, 10, UserMessage.from(row.getMessage())),
                new L0Snapshot.Message(2, 10, AiMessage.from("读取移动布局问题", List.of(request))),
                new L0Snapshot.Message(3, 10, ToolExecutionResultMessage.from(request, result)));
        var source = new L0Snapshot(1, "7", "epoch", 4, 0L, entries,
                List.of(new L0Snapshot.Turn(10, "turn", 1, null, null, null)));
        context.sourceMessageCommitted(1, "epoch", entries.get(1));
        context.sourceMessageCommitted(1, "epoch", entries.get(2));
        assertThrows(ConversationDecisionEvidenceService.SourceCollectionPendingException.class,
                () -> service.prepare(context, source, 3, AdmissionDeadline.start(Duration.ofSeconds(5))));
        verify(histories, never()).updateByQuery(any(ChatHistory.class), eq(true), any(QueryWrapper.class));
        context.decisionSources().toolCommitted(1, ToolExecution.builder().request(request).result(result).build());
        var first = service.prepare(context, source, 3, AdmissionDeadline.start(Duration.ofSeconds(5)));
        var second = service.prepare(context, source, 3, AdmissionDeadline.start(Duration.ofSeconds(5)));
        assertEquals(first, second);
        assertTrue(first.matches(source, service.read(7, 9, 10)));
        assertEquals(3, source.entries().size(), "证据准备不能提前裁剪原文");
        assertThrows(ConversationDecisionEvidenceService.SourceCollectionPendingException.class,
                () -> service.prepare(context, source, 2, AdmissionDeadline.start(Duration.ofSeconds(5))));
        context.closeResources();
    }

    @Test void 截止或存证失败不能提供可提交覆盖凭据() {
        var context = realContext();
        context.commitUserAndBindHistory(() -> row);
        var source = new L0Snapshot(1, "7", "epoch", 2, 0L,
                List.of(new L0Snapshot.Message(1, 10, UserMessage.from(row.getMessage()))),
                List.of(new L0Snapshot.Turn(10, "turn", 1, null, null, null)));
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> service.prepare(context, source, 1, AdmissionDeadline.start(Duration.ZERO)));
        when(histories.updateByQuery(any(ChatHistory.class), eq(true), any(QueryWrapper.class))).thenReturn(0);
        assertThrows(IllegalStateException.class,
                () -> service.prepare(context, source, 1, AdmissionDeadline.start(Duration.ofSeconds(5))));
        assertNull(row.getDecisionEvidence());
        context.closeResources();
    }

    @Test void 提交复核锁定来源且拒绝新证据修订或原文变化() {
        try (var fixture = new UnifiedSummaryCoordinationTest.Fixture(java.util.concurrent.Executors.newSingleThreadExecutor())) {
            var work = fixture.preparedDraft();
            var input = work.input();
            var expected = input.evidenceSnapshots().get(10L);
            row.setMessage("保留中文约束");
            row.setDecisionEvidence(DecisionEvidenceCodec.encode(expected));
            assertDoesNotThrow(() -> service.validateSummaryInput(input));
            var queries = org.mockito.ArgumentCaptor.forClass(QueryWrapper.class);
            verify(histories).selectOneByQuery(queries.capture());
            assertTrue(queries.getValue().toSQL().toUpperCase().contains("FOR UPDATE"));
            row.setMessage("已修改原始要求");
            assertThrows(IllegalStateException.class, () -> service.validateSummaryInput(input));
            row.setMessage("保留中文约束");
            row.setDecisionEvidence(DecisionEvidenceCodec.encode(expected.merge(List.of(), expected.collectedEpoch(),
                    expected.collectedThroughSequence() + 1, expected.collectionGeneration() + 1, 384000)));
            assertThrows(IllegalStateException.class, () -> service.validateSummaryInput(input));
            row.setDecisionEvidence(null);
            assertThrows(IllegalStateException.class, () -> service.validateSummaryInput(input));
        }
    }
    private VueTurnContext realContext() {
        var operations = new com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager();
        var operation = operations.acquire(7L,
                com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager.AppOperationType.GENERATE, "turn");
        var lease = new com.lyw.appgeneration.core.builder.VueBuildSessionManager().open(operation, 9L, "turn");
        var admission = new com.lyw.appgeneration.core.concurrency.VueTurnAdmissionController(
                new com.lyw.appgeneration.monitor.VueBuildRepairMetricsCollector(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        return new VueTurnContext(7, 9, "turn", operation, lease, admission.tryAcquire().orElseThrow(),
                new com.lyw.appgeneration.ai.tools.FileToolBudgetGuard().newSession());
    }

}
