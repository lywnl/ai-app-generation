package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.memory.*;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.core.handler.VueTurnContext;
import com.lyw.appgeneration.service.PreparedDecisionEvidence;
import com.lyw.appgeneration.mapper.AppMapper;
import com.lyw.appgeneration.mapper.ChatHistoryMapper;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

/** 在用户记录短事务中合并独立证据；不写可信结果、展示正文或 L2。 */
@Service
public class ConversationDecisionEvidenceService {
    private final ChatHistoryMapper histories;
    private final AppMapper apps;
    private final AppDataLifecycleFence lifecycle;
    private final TransactionOperations transactions;

    @Autowired
    public ConversationDecisionEvidenceService(ChatHistoryMapper histories, AppMapper apps,
                                               AppDataLifecycleFence lifecycle, PlatformTransactionManager manager) {
        this(histories, apps, lifecycle, new TransactionTemplate(manager));
    }

    ConversationDecisionEvidenceService(ChatHistoryMapper histories, AppMapper apps,
                                         AppDataLifecycleFence lifecycle, TransactionOperations transactions) {
        this.histories = histories; this.apps = apps; this.lifecycle = lifecycle; this.transactions = transactions;
    }

    public DecisionEvidence merge(long appId, long userId, long userHistoryId, String turnId,
                                   List<DecisionEvidence.Entry> entries, String epoch, long through, long generation, int textLimit) {
        try (var writer = lifecycle.tryAcquireWriter(appId)) {
            if (writer == null) throw new IllegalStateException("应用已经停止接收决策证据");
            return transactions.execute(status -> {
                requireOwner(appId, userId);
                ChatHistory row = histories.selectOneByQuery(userQuery(appId, userId, userHistoryId).forUpdate());
                requireUser(row, appId, userId, userHistoryId);
                DecisionEvidence previous = DecisionEvidenceCodec.decode(row.getDecisionEvidence(), userHistoryId, turnId);
                DecisionEvidence next = previous.merge(entries, epoch, through, generation, textLimit);
                persistMerged(row, previous, next);
                return next;
            });
        }
    }

    /** 由终态短事务调用；已有来源可包括未完成批次，它们不因此获得回收或实施权限。 */
    public DecisionEvidence mergeFinalAtCommit(VueTurnContext context, L0Snapshot source) {
        long userHistoryId = context.userHistoryId();
        requireOwner(context.appId(), context.userId());
        ChatHistory row = histories.selectOneByQuery(userQuery(context.appId(), context.userId(), userHistoryId).forUpdate());
        requireUser(row, context.appId(), context.userId(), userHistoryId);
        DecisionEvidence previous = DecisionEvidenceCodec.decode(row.getDecisionEvidence(), userHistoryId, context.turnId());
        var collector = context.decisionSources();
        var observed = collector.snapshot();
        var turn = source.hasKnownSource() ? source.turn(userHistoryId) : null;
        boolean linked = turn != null && turn.turnId().equals(context.turnId());
        long through = linked ? source.entries().stream().filter(entry -> entry.userHistoryId() == userHistoryId)
                .mapToLong(L0Snapshot.Message::sequence).max().orElse(0) : 0;
        if (linked && !collector.collectedThrough(source, userHistoryId, through)) {
            throw new SourceCollectionPendingException("终态保存前的原始来源采集尚未齐备");
        }
        var additions = new java.util.ArrayList<DecisionEvidence.Entry>(linked
                ? new DecisionEvidenceAssembler().collect(userHistoryId, source, through, observed, previous)
                : previous.entries());
        if (additions.stream().noneMatch(entry -> entry.kind() == DecisionEvidence.SourceKind.USER)) {
            additions.add(new DecisionEvidence.Entry("user:" + userHistoryId, DecisionEvidence.SourceKind.USER, "", 0,
                    List.of(), List.of(), List.of(), List.of(), 0));
        }
        // 只读共享转录中的 AI 正文；没有进入 L0 的最后一段仍保留原始 generation，且保持未确认。
        var textByGeneration = new java.util.LinkedHashMap<Long, StringBuilder>();
        var represented = additions.stream().filter(entry -> entry.kind() == DecisionEvidence.SourceKind.MODEL_TEXT)
                .map(DecisionEvidence.Entry::generation).collect(java.util.stream.Collectors.toSet());
        for (var fragment : collector.transcript().snapshot().fragments()) {
            if (!represented.contains(fragment.generation())
                    && fragment.source() == com.lyw.appgeneration.core.handler.VueTurnTranscriptAccumulator.FragmentSource.AI_TEXT) {
                textByGeneration.computeIfAbsent(fragment.generation(), ignored -> new StringBuilder()).append(fragment.text());
            }
        }
        for (var text : textByGeneration.entrySet()) {
            if (!text.getValue().isEmpty()) additions.add(new DecisionEvidence.Entry("transcript:" + context.turnId() + ":" + text.getKey(),
                    DecisionEvidence.SourceKind.MODEL_TEXT, text.getValue().toString(), text.getKey(),
                    List.of(), List.of(), List.of(), List.of(), 0));
        }
        long generation = observed.sources().values().stream().filter(ref -> linked && ref.memoryEpoch().equals(source.memoryEpoch()))
                .mapToLong(ref -> ref.generation()).max().orElse(previous.collectionGeneration());
        DecisionEvidence next = previous.merge(additions, linked ? source.memoryEpoch() : null, through, generation,
                context.budgetSession().canonicalLimitCodePoints());
        persistMerged(row, previous, next);
        return next;
    }

    private void persistMerged(ChatHistory row, DecisionEvidence previous, DecisionEvidence next) {
        if (next.equals(previous)) return;
        QueryWrapper condition = userQuery(row.getAppId(), row.getUserId(), row.getId());
        if (row.getDecisionEvidence() == null) condition.isNull("decisionEvidence");
        else condition.eq("decisionEvidence", row.getDecisionEvidence());
        int changed = histories.updateByQuery(ChatHistory.builder().decisionEvidence(DecisionEvidenceCodec.encode(next)).build(), true, condition);
        if (changed != 1) throw new IllegalStateException("决策证据修订条件已变化");
    }

    public DecisionEvidence read(long appId, long userId, long userHistoryId) {
        if (!lifecycle.isOpen(appId)) throw new IllegalStateException("应用已经关闭");
        requireOwner(appId, userId);
        ChatHistory row = histories.selectOneByQuery(userQuery(appId, userId, userHistoryId));
        requireUser(row, appId, userId, userHistoryId);
        return DecisionEvidenceCodec.decode(row.getDecisionEvidence(), userHistoryId);
    }

    /** 在摘要提交的同一短事务中锁定并核对全部被选历史及证据，锁按持久 ID 排序。 */
    public void validateSummaryInput(SummaryInput input) {
        requireOwner(input.appId(), input.userId());
        var identities = new java.util.TreeSet<>(input.historyFingerprints().keySet());
        identities.addAll(input.evidenceSnapshots().keySet());
        for (long id : identities) {
            ChatHistory row = histories.selectOneByQuery(QueryWrapper.create().eq("id", id)
                    .eq("appId", input.appId()).eq("userId", input.userId()).eq("isDelete", 0).forUpdate());
            if (row == null || !Objects.equals(row.getId(), id)
                    || !Objects.equals(row.getAppId(), input.appId()) || !Objects.equals(row.getUserId(), input.userId())
                    || (row.getIsDelete() != null && row.getIsDelete() != 0)) {
                throw new IllegalStateException("摘要提交时历史来源已失效");
            }
            String expected = input.historyFingerprints().get(id);
            if (expected != null && !expected.equals(SummaryMaterialReader.fingerprint(row))) {
                throw new IllegalStateException("摘要提交时持久原文或可信结果已变化");
            }
            if ("user".equals(row.getMessageType())) {
                DecisionEvidence evidence = DecisionEvidenceCodec.decode(row.getDecisionEvidence(), id);
                if (!Objects.equals(evidence, input.evidenceSnapshots().get(id))) {
                    throw new IllegalStateException("摘要提交时决策证据修订已变化");
                }
            }
        }
    }

    /** 完整批次、精确来源采集及可靠保存全部成立后才返回；不修改 L0 或摘要覆盖。 */
    public PreparedDecisionEvidence prepare(VueTurnContext context, L0Snapshot source, long through,
                                             AdmissionDeadline deadline) {
        if (deadline.remainingNanos() <= 0) throw new CancellationException("证据准备已超过原截止");
        long userHistoryId = context.userHistoryId();
        var turn = source.turn(userHistoryId);
        if (!source.hasKnownSource() || !source.memoryId().equals(Long.toString(context.appId()))
                || turn == null || !turn.turnId().equals(context.turnId()) || through < turn.userSequence()
                || through >= source.nextSequence()) throw new IllegalArgumentException("证据准备来源不属于当前回合");
        for (var unit : new L0SourceParser().parse(source).units()) {
            if (unit.userHistoryId() == userHistoryId && unit.firstSequence() <= through
                    && (!unit.complete() || unit.lastSequence() > through)) {
                throw new SourceCollectionPendingException("拟裁剪边界拆开或包含未完成工具批次");
            }
        }
        var collector = context.decisionSources();
        if (!collector.collectedThrough(source, userHistoryId, through)) {
            throw new SourceCollectionPendingException("执行来源采集尚未到达拟裁剪边界");
        }
        var observed = collector.snapshot();
        AtomicReference<PreparedDecisionEvidence> prepared = new AtomicReference<>();
        boolean accepted = context.tryRun(() -> {
            if (deadline.remainingNanos() <= 0) throw new CancellationException("证据准备已超过原截止");
            DecisionEvidence previous = read(context.appId(), context.userId(), userHistoryId);
            var additions = new DecisionEvidenceAssembler().collect(userHistoryId, source, through, observed, previous);
            long generation = observed.sources().values().stream()
                    .filter(ref -> ref.memoryEpoch().equals(source.memoryEpoch()) && ref.sequence() <= through)
                    .mapToLong(ref -> ref.generation()).max().orElse(0);
            if (deadline.remainingNanos() <= 0) throw new CancellationException("证据准备已超过原截止");
            DecisionEvidence saved = merge(context.appId(), context.userId(), userHistoryId, context.turnId(),
                    additions, source.memoryEpoch(), through, generation, context.budgetSession().canonicalLimitCodePoints());
            if (deadline.remainingNanos() <= 0) throw new CancellationException("存证已完成但准备截止已耗尽");
            prepared.set(new PreparedDecisionEvidence(context.appId(), context.userId(), userHistoryId,
                    context.turnId(), source, through, saved,
                    L0SourceOperations.fingerprint(source, userHistoryId, through, saved.revision())));
        });
        if (!accepted) throw new CancellationException("回合已经结束，不能准备原文回收");
        return prepared.get();
    }

    public static final class SourceCollectionPendingException extends IllegalStateException {
        public SourceCollectionPendingException(String message) { super(message); }
    }

    private void requireOwner(long appId, long userId) {
        var app = apps.selectOneByQuery(QueryWrapper.create().eq("id", appId).eq("userId", userId).eq("isDelete", 0));
        if (app == null || !Objects.equals(app.getUserId(), userId) || !Objects.equals(app.getId(), appId)
                || (app.getIsDelete() != null && app.getIsDelete() != 0)) throw new IllegalStateException("应用不存在或不属于当前用户");
    }

    private QueryWrapper userQuery(long appId, long userId, long historyId) {
        if (appId <= 0 || userId <= 0 || historyId <= 0) throw new IllegalArgumentException("决策证据归属无效");
        return QueryWrapper.create().eq("id", historyId).eq("appId", appId).eq("userId", userId)
                .eq("messageType", "user").eq("isDelete", 0);
    }

    private void requireUser(ChatHistory row, long appId, long userId, long historyId) {
        if (row == null || !Objects.equals(row.getId(), historyId) || !Objects.equals(row.getAppId(), appId)
                || !Objects.equals(row.getUserId(), userId) || !"user".equals(row.getMessageType())
                || (row.getIsDelete() != null && row.getIsDelete() != 0)) throw new IllegalStateException("决策证据的用户记录已失效");
    }
}
