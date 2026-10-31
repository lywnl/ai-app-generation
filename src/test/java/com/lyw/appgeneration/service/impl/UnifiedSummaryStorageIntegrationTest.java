package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.memory.*;
import com.lyw.appgeneration.core.handler.VueTurnContext;
import com.lyw.appgeneration.model.entity.AppMemorySummary;
import com.lyw.appgeneration.service.MemoryCompressionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "UNIFIED_MEMORY_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/(\\?.*)?")
@EnabledIfEnvironmentVariable(named = "UNIFIED_MEMORY_REDIS_PORT", matches = "[0-9]+")
class UnifiedSummaryStorageIntegrationTest extends UnifiedMemoryStorageFixture {
    @Test
    void 重启修复以旧用户记录原子登记终态并用真实摘要依赖冷重建() {
        service.commitUnifiedDraft(prepared());
        var before = service.readSnapshot(appId);
        String evidenceBefore = historiesMapper.selectOneById(10L).getDecisionEvidence();
        var oldSource = memory.sourceSnapshot();
        context.closeResources();
        ReflectionTestUtils.setField(histories, "memorySummaryService", service);
        assertTrue(histories.repairOrphanUserTurn(appId, 9L, "生成中断，请继续。"));
        var terminal = histories.getLastMessage(appId);
        var after = service.readRebuildSnapshot(appId, deadline());
        assertEquals(before.summaryRevision() + 1, after.summaryRevision());
        assertEquals(before.summary(), after.summary());
        assertEquals(before.lastSummarizedId(), after.lastSummarizedId());
        assertEquals(before.coverageState().coveredSequence(), after.coverageState().coveredSequence());
        assertEquals(10, after.coverageState().userHistoryId());
        assertEquals(terminal.getId(), after.coverageState().terminalHistoryId());
        assertEquals(com.lyw.appgeneration.model.enums.ChatMemoryOutcome.SYSTEM_ERROR, after.coverageState().terminalOutcome());
        assertFalse(after.coverageState().terminalIncluded());
        assertEquals(evidenceBefore, historiesMapper.selectOneById(10L).getDecisionEvidence());
        assertEquals(com.lyw.appgeneration.service.ChatHistoryService.HistoryLoadStatus.LOADED,
                histories.loadSourceHistoryToMemory(appId, after, memory, 57_344, estimator, deadline()).status());
        var rebuilt = memory.sourceSnapshot();
        assertNotEquals(oldSource.memoryEpoch(), rebuilt.memoryEpoch());
        assertEquals(after.summaryRevision(), rebuilt.appliedSummaryRevision());
        assertEquals(2, rebuilt.entries().size(), "仅恢复用户和持久后端结果，不补造中断工具轨迹");
        assertEquals(terminal.getId(), rebuilt.entries().getLast().historyRecordId());
        assertEquals(rebuilt, L0SourceOperations.prune(rebuilt, after, 99));
        verifyNoInteractions(model);
    }

    @Test
    void 重启补写终态的数据库更新失败时AI与前沿一起回滚() throws Exception {
        service.commitUnifiedDraft(prepared());
        var before = service.readSnapshot(appId);
        var source = memory.sourceSnapshot();
        var failed = createService(afterMapperCall((name, result) -> {
            if (name.equals("commitSnapshot")) throw new IllegalStateException("真实更新后注入故障");
        }), transactions);
        ReflectionTestUtils.setField(histories, "memorySummaryService", failed);
        assertThrows(IllegalStateException.class, () -> histories.repairOrphanUserTurn(appId, 9L, "生成中断"));
        assertEquals(before, service.readSnapshot(appId));
        assertEquals(10L, histories.getLastMessage(appId).getId());
        assertEquals(0, count("SELECT COUNT(*) FROM chat_history WHERE messageType='ai'"));
        assertEquals(source, memory.sourceSnapshot());
        verifyNoInteractions(model);
    }

    @Test
    void 读取r1后真实提交r2并回收必须重读无缺口组合() throws Exception {
        service.commitUnifiedDraft(prepared());
        append(11, 20);
        var next = prepared();
        var selected = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var once = new AtomicBoolean(true);
        var observed = createService(afterMapperCall((name, result) -> {
            if (name.equals("selectOneByQuery") && once.compareAndSet(true, false)) {
                selected.countDown();
                try { assertTrue(resume.await(8, TimeUnit.SECONDS)); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
            }
        }), transactions);
        var reading = workers.submit(() -> observed.readCompatible(request, deadline()));
        try {
            assertTrue(selected.await(5, TimeUnit.SECONDS));
            assertEquals(MemoryCompressionResult.Status.COMPRESSED, service.commitUnifiedDraft(next).status());
            assertEquals(DeadlineAwareReplaceResult.REPLACED, service.reclaimUnified(request, deadline()));
        } finally { resume.countDown(); }
        var pair = reading.get(10, TimeUnit.SECONDS);
        assertEquals(2L, pair.summary().summaryRevision());
        assertEquals(2L, pair.source().appliedSummaryRevision());
        long covered = pair.summary().coverageState().coveredSequence();
        assertEquals(covered + 1, pair.source().entries().stream().filter(entry -> entry.sequence() > 1)
                .mapToLong(L0Snapshot.Message::sequence).min().orElseThrow());
        assertEquals(pair.summary(), service.readSnapshot(appId));
        assertEquals(pair.source(), memory.sourceSnapshot());
        var user = historiesMapper.selectOneById(10L);
        assertTrue(user.getMessage().contains("保留中文"));
        assertTrue(DecisionEvidenceCodec.decode(user.getDecisionEvidence(), 10).entries().stream()
                .anyMatch(entry -> entry.kind() == DecisionEvidence.SourceKind.USER));
        verifyNoInteractions(model);
        System.out.println("读取交错：r1 被拒绝，r2 与 Redis 下界 2 配对；保留原文从覆盖之后连续承接");
    }

    @Test
    void 外层可重复读快照不会污染兼容读取的新事务() {
        service.commitUnifiedDraft(prepared());
        append(11, 20);
        var next = prepared();
        var outer = new TransactionTemplate(transactions);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.executeWithoutResult(status -> {
            var jdbc = new JdbcTemplate(dataSource);
            String query = "SELECT summaryRevision FROM app_memory_summary WHERE appId=" + appId;
            assertEquals(1L, jdbc.queryForObject(query, Long.class));
            try {
                workers.submit(() -> {
                    service.commitUnifiedDraft(next);
                    assertEquals(DeadlineAwareReplaceResult.REPLACED, service.reclaimUnified(request, deadline()));
                }).get(8, TimeUnit.SECONDS);
            } catch (Exception exception) { throw new IllegalStateException(exception); }
            assertEquals(1L, jdbc.queryForObject(query, Long.class), "外层真实 RR 仍看到旧版本");
            assertEquals(2L, service.readCompatible(request, deadline()).summary().summaryRevision());
        });
    }

    @Test
    void 真实COMMIT确认丢失后重读相同修订才裁剪且不重复模型调用() {
        var writeTransactions = new java.util.concurrent.ConcurrentHashMap<org.springframework.transaction.TransactionStatus, Boolean>();
        var failOnce = new AtomicBoolean(true);
        org.springframework.transaction.PlatformTransactionManager uncertain = new org.springframework.transaction.PlatformTransactionManager() {
            @Override public org.springframework.transaction.TransactionStatus getTransaction(TransactionDefinition definition) {
                var status = transactions.getTransaction(definition);
                writeTransactions.put(status, definition != null && "l1-summary-persistence".equals(definition.getName()));
                return status;
            }
            @Override public void commit(org.springframework.transaction.TransactionStatus status) {
                boolean write = Boolean.TRUE.equals(writeTransactions.remove(status));
                transactions.commit(status);
                if (write && failOnce.compareAndSet(true, false)) throw new IllegalStateException("真实 COMMIT 已完成，模拟客户端确认丢失");
            }
            @Override public void rollback(org.springframework.transaction.TransactionStatus status) {
                writeTransactions.remove(status); transactions.rollback(status);
            }
        };
        var observed = createService(summaries, uncertain);
        var result = observed.compressUnified(request, deadline());
        assertEquals(MemoryCompressionResult.Status.COMPRESSED, result.status());
        assertEquals(1L, service.readSnapshot(appId).summaryRevision());
        assertEquals(1L, memory.sourceSnapshot().appliedSummaryRevision());
        verify(model, times(1)).chat(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 真实事务回滚不留下正文覆盖半状态旧失败回调不能覆盖新修订() {
        service.commitUnifiedDraft(prepared());
        var original = service.readSnapshot(appId);
        append(11, 20);
        var next = prepared();
        var source = memory.sourceSnapshot();
        var failing = createService(afterMapperCall((name, result) -> {
            if (name.equals("commitSnapshot")) throw new IllegalStateException("真实 UPDATE 后、提交前注入失败");
        }), transactions);
        assertThrows(IllegalStateException.class, () -> failing.commitUnifiedDraft(next));
        assertEquals(original, service.readSnapshot(appId));
        assertEquals(source, memory.sourceSnapshot());
        service.commitUnifiedDraft(next);
        var newer = service.readSnapshot(appId);
        assertEquals(0, summaries.updateFailureMetadata(AppMemorySummary.builder().appId(appId).failCount(99)
                .nextRetryTime(java.time.LocalDateTime.now().plusHours(1)).updateTime(java.time.LocalDateTime.now()).build(), 1));
        assertEquals(newer, service.readSnapshot(appId));
        assertEquals(0, summaries.selectOneByQuery(com.mybatisflex.core.query.QueryWrapper.create().eq("appId", appId)).getFailCount());
    }

    @Test
    void 证据修订变更及首次插入唯一键冲突不能覆盖已有快照() {
        var draft = prepared();
        var old = evidence.read(appId, 9, 10);
        evidence.merge(appId, 9, 10, context.turnId(), java.util.List.of(), old.collectedEpoch(),
                old.collectedThroughSequence() + 1, old.collectionGeneration() + 1, 384000);
        assertThrows(IllegalStateException.class, () -> service.commitUnifiedDraft(draft));
        assertTrue(service.readSnapshot(appId).summary().isEmpty());
        service.commitUnifiedDraft(prepared());
        var winner = service.readSnapshot(appId);
        AppMemorySummary duplicate = summaries.selectOneByQuery(com.mybatisflex.core.query.QueryWrapper.create().eq("appId", appId));
        duplicate.setId(duplicate.getId() + 1);
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> summaries.insert(duplicate));
        assertEquals(winner, service.readSnapshot(appId));
    }

    @Test
    void 真实Redis比较失败及服务端截止不推进下界后续可幂等回收() {
        service.commitUnifiedDraft(prepared());
        var summary = service.readSnapshot(appId);
        var expected = memory.sourceSnapshot();
        var replacement = L0SourceOperations.prune(expected, summary, 10);
        append(11, 11);
        assertEquals(DeadlineAwareReplaceResult.PREFIX_CHANGED, memory.replaceSourceSnapshot(expected, replacement, deadline()));
        var latest = memory.sourceSnapshot();
        assertEquals(0L, latest.appliedSummaryRevision());
        var expiredAtServer = deadline();
        ReflectionTestUtils.setField(expiredAtServer, "serverDeadlineEpochMillis", System.currentTimeMillis() - 1);
        assertTrue(expiredAtServer.remainingNanos() > 0);
        assertEquals(DeadlineAwareReplaceResult.TIMED_OUT, memory.replaceSourceSnapshot(latest,
                L0SourceOperations.prune(latest, summary, 10), expiredAtServer));
        assertEquals(latest, memory.sourceSnapshot());
        // 停止的是本测试的客户端连接池，实际 Redis 服务不受影响；恢复同一客户端后只重试回收。
        redisFactory.stop();
        try {
            assertEquals(DeadlineAwareReplaceResult.DEPENDENCY_FAILED, service.reclaimUnified(request, deadline()));
            assertEquals(summary, service.readSnapshot(appId));
        } finally { redisFactory.start(); }
        assertEquals(latest, memory.sourceSnapshot());
        assertEquals(DeadlineAwareReplaceResult.REPLACED, service.reclaimUnified(request, deadline()));
        var pruned = memory.sourceSnapshot();
        assertEquals(1L, pruned.appliedSummaryRevision());
        assertEquals(latest.entries().getLast(), pruned.entries().getLast());
        assertEquals(DeadlineAwareReplaceResult.REPLACED, service.reclaimUnified(request, deadline()));
        assertEquals(pruned, memory.sourceSnapshot());
        verifyNoInteractions(model);
    }

    @Test
    void epoch切换和取消删除竞争不会让旧草稿裁剪新窗口() {
        var draft = prepared();
        var source = memory.sourceSnapshot();
        var replacement = new L0Snapshot(1, Long.toString(appId), "recovered-epoch", source.nextSequence(),
                source.appliedSummaryRevision(), source.entries(), source.turns());
        assertEquals(DeadlineAwareReplaceResult.REPLACED, memory.replaceSourceSnapshot(source, replacement, deadline()));
        assertThrows(IllegalStateException.class, () -> service.commitUnifiedDraft(draft));
        assertEquals(replacement, memory.sourceSnapshot());
        assertTrue(context.tryStartCancellation(VueTurnContext.TerminalTrigger.CANCELLED));
        assertEquals(MemoryCompressionResult.Status.DELETE_REJECTED, service.commitUnifiedDraft(draft).status());
        redis.opsForValue().set("mem:summary:" + appId, "旧缓存");
        redis.opsForValue().set("mem:summary:v2:" + appId, "新缓存");
        try (var deletion = lifecycle.beginDelete(appId, Duration.ofSeconds(1))) {
            assertNotNull(deletion); deletion.commitTombstone();
        }
        assertEquals(DeadlineAwareReplaceResult.PREFIX_CHANGED, service.reclaimUnified(request, deadline()));
        assertThrows(IllegalStateException.class, () -> service.readRebuildSnapshot(appId, deadline()));
        assertThrows(IllegalStateException.class, () -> service.persistRecoveredTurnOutcome(appId, 9, 10,
                () -> { fail("删除后不得补写历史"); return null; }));
        assertTrue(service.invalidateCache(appId).failedTargets().isEmpty());
        assertFalse(Boolean.TRUE.equals(redis.hasKey("mem:summary:" + appId)));
        assertFalse(Boolean.TRUE.equals(redis.hasKey("mem:summary:v2:" + appId)));
        assertEquals(replacement, memory.sourceSnapshot());
    }

    @Test
    void 最终证据AI结果与前沿结束在真实事务中一同回滚() throws Exception {
        service.commitUnifiedDraft(prepared());
        var previous = service.readSnapshot(appId);
        String evidenceBefore = historiesMapper.selectOneById(10L).getDecisionEvidence();
        append(11, 20);
        var source = memory.sourceSnapshot();
        var failing = createService(afterMapperCall((name, result) -> {
            if (name.equals("commitSnapshot")) throw new IllegalStateException("终态登记 UPDATE 后注入失败");
        }), transactions);
        context.tryStartFinalization(VueTurnContext.TerminalTrigger.FAILED);
        assertThrows(IllegalStateException.class, () -> failing.persistTurnOutcome(context, () ->
                histories.addAiMessageAndReturn(appId, "模型声称成功", "实际构建失败", com.lyw.appgeneration.model.enums.ChatMemoryOutcome.FAILED, 9L)));
        assertEquals(previous, service.readSnapshot(appId));
        assertEquals(evidenceBefore, historiesMapper.selectOneById(10L).getDecisionEvidence());
        assertEquals(0L, count("SELECT COUNT(*) FROM chat_history WHERE messageType='ai'"));
        assertEquals(source, memory.sourceSnapshot());
        verifyNoInteractions(model);
    }

    @Test
    void 未触发摘要的回合只保存证据与真实结果不创建摘要() throws Exception {
        var source = memory.sourceSnapshot();
        var shortWindow = L0SourceOperations.resetActive(source, java.util.List.of(source.messages().getFirst()));
        assertEquals(DeadlineAwareReplaceResult.REPLACED, memory.replaceSourceSnapshot(source, shortWindow, deadline()));
        context.decisionSources().transcript().appendAiText(21, "建议保持中文，因为用户已经明确要求。");
        context.tryStartFinalization(VueTurnContext.TerminalTrigger.FAILED);
        var saved = service.persistTurnOutcome(context, () -> histories.addAiMessageAndReturn(appId,
                "模型声称成功", "实际构建失败", com.lyw.appgeneration.model.enums.ChatMemoryOutcome.FAILED, 9L));
        assertEquals(com.lyw.appgeneration.model.enums.ChatMemoryOutcome.FAILED, historiesMapper.selectOneById(saved.getId()).getMemoryOutcome());
        var decision = DecisionEvidenceCodec.decode(historiesMapper.selectOneById(10L).getDecisionEvidence(), 10);
        assertTrue(decision.entries().stream().anyMatch(entry -> entry.text().contains("因为用户已经明确要求")));
        assertEquals(0L, count("SELECT COUNT(*) FROM app_memory_summary"));
        assertEquals(shortWindow, memory.sourceSnapshot());
        verifyNoInteractions(model);
    }

    @Test
    void 真实收尾关闭保留原文与后端身份并可追加下一回合() {
        var original = memory.sourceSnapshot();
        context.tryStartFinalization(VueTurnContext.TerminalTrigger.FAILED);
        var saved = service.persistTurnOutcome(context, () -> histories.addAiMessageAndReturn(appId,
                "展示回答", "真实失败，需要继续修复", com.lyw.appgeneration.model.enums.ChatMemoryOutcome.FAILED, 9L));
        var closer = new ToolMessageCollapser(sourceStore);
        var result = closer.finishLastTurn(context, saved, service.readSnapshot(appId));
        assertEquals(ToolMessageCollapser.CollapseStatus.COLLAPSED, result.status());
        var closed = memory.sourceSnapshot();
        assertEquals(original.entries(), closed.entries().subList(0, original.entries().size()));
        assertEquals(saved.getId(), closed.entries().getLast().historyRecordId());
        assertTrue(closed.turn(10L).closed());
        var nextUser = histories.addChatMessageAndReturn(appId, "继续修复上一轮问题", "user", 9L);
        assertTrue(nextUser.getId() > saved.getId());
        CanonicalUserMessageScope.call(TurnRequestBoundary.of(nextUser.getMessage(), java.util.List.of(), nextUser.getId(), "next"), () -> {
            memory.add(dev.langchain4j.data.message.UserMessage.from(nextUser.getMessage())); return null;
        });
        assertEquals(closed.entries(), memory.sourceSnapshot().entries().subList(0, closed.entries().size()));
        assertEquals(0, memory.sourceSnapshot().appliedSummaryRevision());
        verifyNoInteractions(model);
    }
}
