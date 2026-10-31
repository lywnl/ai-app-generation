package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;
import com.lyw.appgeneration.service.MemorySummarySnapshot;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 来源操作只构造候选；调用方必须在共享锁及存储 CAS 内提交，不能先改读取下界。 */
public final class L0SourceOperations {
    private L0SourceOperations() {
    }

    /** 冷重建只导入持久化完整历史表示；旧用户原话不重新套用新请求长度限制。 */
    public static L0Snapshot importClosedHistory(L0Snapshot source, ConversationTurn history,
            String turnId, ChatMemoryOutcome outcome) {
        requireSource(source);
        if (history.messages().size() != 2 || !(history.messages().getFirst() instanceof UserMessage user)
                || !(history.messages().getLast() instanceof dev.langchain4j.data.message.AiMessage ai)
                || ai.hasToolExecutionRequests() || outcome == null || outcome == ChatMemoryOutcome.INCOMPLETE_TOOL_CHAIN
                || (!source.turns().isEmpty() && !source.turns().getLast().closed())) {
            throw new IllegalArgumentException("冷重建只能导入完整用户原话与持久后端结果");
        }
        long sequence = source.nextSequence();
        var entries = new ArrayList<>(source.entries());
        entries.add(new L0Snapshot.Message(sequence, history.turnId(), user, history.turnId()));
        entries.add(new L0Snapshot.Message(sequence + 1, history.turnId(), ai, history.completedThroughId()));
        var turns = new ArrayList<>(source.turns());
        turns.add(new L0Snapshot.Turn(history.turnId(), turnId, sequence, history.completedThroughId(), outcome, sequence + 1));
        return copy(source, source.memoryEpoch(), Math.addExact(sequence, 2), source.appliedSummaryRevision(), entries, turns);
    }

    public static L0Snapshot append(L0Snapshot source, ChatMessage message, TurnRequestBoundary boundary) {
        requireSource(source);
        if (message instanceof SystemMessage system) return replaceSystem(source, system);
        var turns = new ArrayList<>(source.turns());
        L0Snapshot.Turn turn;
        if (message instanceof UserMessage user) {
            if (boundary == null || boundary.userHistoryId() <= 0
                    || !user.equals(boundary.userMessage()) || source.turn(boundary.userHistoryId()) != null) {
                throw new IllegalStateException("追加用户消息必须具有唯一的持久回合来源");
            }
            if (!turns.isEmpty() && !turns.getLast().closed()) {
                throw new IllegalStateException("上一回合尚未可靠结束");
            }
            turn = new L0Snapshot.Turn(boundary.userHistoryId(), boundary.turnId(), source.nextSequence(),
                    null, null, null);
            turns.add(turn);
        } else {
            if (turns.isEmpty() || turns.getLast().closed()) {
                throw new IllegalStateException("模型或工具消息缺少活动回合");
            }
            turn = turns.getLast();
        }
        var entries = new ArrayList<>(source.entries());
        entries.add(new L0Snapshot.Message(source.nextSequence(), turn.userHistoryId(), message));
        return copy(source, source.memoryEpoch(), Math.addExact(source.nextSequence(), 1),
                source.appliedSummaryRevision(), entries, turns);
    }

    public static L0Snapshot replaceSystem(L0Snapshot source, SystemMessage message) {
        requireSource(source);
        var entries = new ArrayList<L0Snapshot.Message>();
        entries.add(new L0Snapshot.Message(0L, 0L, message));
        source.entries().stream().filter(entry -> !(entry.payload() instanceof SystemMessage)).forEach(entries::add);
        return copy(source, source.memoryEpoch(), source.nextSequence(), source.appliedSummaryRevision(), entries, source.turns());
    }

    /** 库层整列表只能重放同一前缀后的追加或替换系统规则；删除／恢复须走专用来源操作。 */
    public static L0Snapshot reconcile(L0Snapshot source, List<ChatMessage> messages, TurnRequestBoundary boundary) {
        requireSource(source);
        L0Snapshot result = source;
        List<ChatMessage> before = source.messages();
        List<ChatMessage> after = List.copyOf(messages);
        if (!after.isEmpty() && after.getFirst() instanceof SystemMessage system) {
            result = replaceSystem(result, system);
            before = result.messages();
        }
        if (after.size() < before.size() || !after.subList(0, before.size()).equals(before)) {
            throw new IllegalStateException("整窗口更新将破坏 L0 来源，请使用显式恢复或覆盖裁剪");
        }
        for (int index = before.size(); index < after.size(); index++) result = append(result, after.get(index), boundary);
        return result;
    }

    public static L0Snapshot closeTurn(L0Snapshot source, long userHistoryId, String turnId,
                                       long terminalHistoryId, ChatMemoryOutcome outcome) {
        requireSource(source);
        L0Snapshot.Turn selected = source.turn(userHistoryId);
        if (selected == null || !selected.turnId().equals(turnId)) {
            throw new IllegalStateException("关闭回合与来源身份不符");
        }
        if (selected.closed()) {
            if (selected.terminalHistoryId() != terminalHistoryId || selected.terminalOutcome() != outcome) {
                throw new IllegalStateException("不能覆盖已经登记的终态");
            }
            return source;
        }
        var turns = new ArrayList<>(source.turns());
        turns.set(turns.indexOf(selected), new L0Snapshot.Turn(userHistoryId, turnId, selected.userSequence(),
                terminalHistoryId, outcome, source.nextSequence() - 1));
        return copy(source, source.memoryEpoch(), source.nextSequence(), source.appliedSummaryRevision(), source.entries(), turns);
    }

    /**
     * 在可信 AI 记录已经保存后关闭来源。合法原文保留，终态单独带持久记录身份；
     * 未完成链仍整轮丢弃，协议失败仍只保留用户与可信结果，破坏性恢复同时更换 epoch。
     */
    public static L0Snapshot finishTurn(L0Snapshot source, long userHistoryId, String turnId,
                                        long historyId, ChatMemoryOutcome outcome, String trustedText,
                                        long committedSummaryRevision) {
        requireSource(source);
        var active = source.turn(userHistoryId);
        if (active == null || !active.turnId().equals(turnId) || source.turns().getLast().userHistoryId() != userHistoryId
                || historyId <= userHistoryId || outcome == null || trustedText == null || trustedText.isBlank()
                || committedSummaryRevision < source.appliedSummaryRevision()) {
            throw new IllegalStateException("关闭来源的持久回合、结果或摘要依赖无效");
        }
        if (active.closed()) {
            if (active.terminalHistoryId() != historyId || active.terminalOutcome() != outcome) {
                throw new IllegalStateException("不能覆盖已经关闭的来源回合");
            }
            return source;
        }
        if (outcome == ChatMemoryOutcome.INCOMPLETE_TOOL_CHAIN) {
            return copy(source, UUID.randomUUID().toString(), source.nextSequence(), committedSummaryRevision,
                    source.entries().stream().filter(entry -> entry.userHistoryId() != userHistoryId).toList(),
                    source.turns().stream().filter(turn -> turn.userHistoryId() != userHistoryId).toList());
        }
        L0Snapshot working = source;
        if (outcome == ChatMemoryOutcome.PROTOCOL_ERROR) {
            var retained = new ArrayList<ChatMessage>();
            source.entries().stream().filter(entry -> entry.userHistoryId() != userHistoryId
                    || entry.payload() instanceof UserMessage).map(L0Snapshot.Message::payload).forEach(retained::add);
            working = resetActive(source, retained);
            working = copy(working, working.memoryEpoch(), working.nextSequence(), committedSummaryRevision,
                    working.entries(), working.turns());
        } else {
            var parsed = new L0SourceParser().parse(source);
            if (parsed.units().stream().anyMatch(unit -> !unit.complete())) {
                throw new IllegalStateException("未完成工具批次不能被正常关闭为完整原文");
            }
        }
        var entries = new ArrayList<>(working.entries());
        long sequence = working.nextSequence();
        entries.add(new L0Snapshot.Message(sequence, userHistoryId,
                dev.langchain4j.data.message.AiMessage.from(trustedText), historyId));
        var turns = new ArrayList<>(working.turns());
        turns.set(turns.size() - 1, new L0Snapshot.Turn(userHistoryId, turnId, active.userSequence(), historyId, outcome, sequence));
        return copy(working, working.memoryEpoch(), Math.addExact(sequence, 1), working.appliedSummaryRevision(), entries, turns);
    }

    /** 只替换已知活动回合的原文；此前回合必须保持原样，旧草稿因新 epoch 失效。 */
    public static L0Snapshot resetActive(L0Snapshot source, List<ChatMessage> replacement) {
        requireSource(source);
        if (source.turns().isEmpty() || source.turns().getLast().closed()) {
            throw new IllegalStateException("恢复缺少活动回合");
        }
        var active = source.turns().getLast();
        var preceding = source.entries().stream().filter(entry -> entry.userHistoryId() != active.userHistoryId()).toList();
        var prefix = preceding.stream().map(L0Snapshot.Message::payload).toList();
        var user = source.entries().stream().filter(entry -> entry.sequence() == active.userSequence())
                .findFirst().orElseThrow(() -> new IllegalStateException("恢复缺少原用户消息"));
        if (replacement.size() <= prefix.size() || !replacement.subList(0, prefix.size()).equals(prefix)
                || !replacement.get(prefix.size()).equals(user.payload())) {
            throw new IllegalStateException("恢复不得改写较早来源或当前用户原话");
        }
        var entries = new ArrayList<>(preceding);
        long sequence = active.userSequence();
        for (ChatMessage message : replacement.subList(prefix.size(), replacement.size())) {
            entries.add(new L0Snapshot.Message(sequence++, active.userHistoryId(), message));
        }
        return copy(source, UUID.randomUUID().toString(), sequence, source.appliedSummaryRevision(), entries, source.turns());
    }

    /**
     * 可靠覆盖的实际回收与不倒退的读取下界一起返回。相同修订重试不重新编号。
     * 部分前沿必须匹配 epoch 及来源指纹；完整历史只能回收有后端结束身份的回合。
     */
    public static L0Snapshot prune(L0Snapshot source, MemorySummarySnapshot summary, long protectedUserHistoryId) {
        requireSource(source);
        if (summary.summaryRevision() <= 0 || !MemorySummaryFormat.isValid(summary.summary())) {
            throw new IllegalArgumentException("未确认的摘要不能回收来源");
        }
        if (summary.summaryRevision() < source.appliedSummaryRevision()) return source;
        var retained = new ArrayList<L0Snapshot.Message>();
        boolean partialRemoved = false;
        SummaryCoverage coverage = summary.coverageState();
        for (var entry : source.entries()) {
            boolean protectedUser = entry.userHistoryId() == protectedUserHistoryId && entry.payload() instanceof UserMessage;
            var turn = source.turn(entry.userHistoryId());
            boolean complete = turn != null && turn.closed() && turn.terminalHistoryId() <= summary.lastSummarizedId();
            boolean partial = coverage != null && source.memoryEpoch().equals(coverage.memoryEpoch())
                    && entry.userHistoryId() == coverage.userHistoryId() && entry.sequence() <= coverage.coveredSequence();
            if (entry.payload() instanceof SystemMessage || protectedUser || (!complete && !partial)) {
                retained.add(entry);
            } else {
                partialRemoved |= partial && !complete;
            }
        }
        if (retained.equals(source.entries())) return source;
        if (partialRemoved && hasWholePrefix(source, coverage)
                && !fingerprint(source, coverage.userHistoryId(), coverage.coveredSequence(),
                coverage.evidenceRevision()).equals(coverage.sourceFingerprint())) {
            throw new IllegalStateException("摘要来源前缀已变化，拒绝裁剪");
        }
        var last = source.turns().isEmpty() ? null : source.turns().getLast();
        var turns = source.turns().stream().filter(turn -> turn.equals(last)
                || (coverage != null && turn.userHistoryId() == coverage.userHistoryId())
                || retained.stream().anyMatch(entry -> entry.userHistoryId() == turn.userHistoryId())).toList();
        return copy(source, source.memoryEpoch(), source.nextSequence(),
                Math.max(source.appliedSummaryRevision(), summary.summaryRevision()), retained, turns);
    }

    private static boolean hasWholePrefix(L0Snapshot source, SummaryCoverage coverage) {
        var turn = source.turn(coverage.userHistoryId());
        if (turn == null || !turn.turnId().equals(coverage.turnId())) {
            throw new IllegalStateException("部分覆盖与持久回合来源不符");
        }
        long count = source.entries().stream().filter(entry -> entry.userHistoryId() == coverage.userHistoryId()
                && entry.sequence() <= coverage.coveredSequence()).count();
        // 已发生可靠裁剪时，不能要求已删除的原文仍参与指纹重算。
        // 同 epoch 的保留原文不可改写；其来源由原 CAS、修订下界和提交时的来源复核承接。
        return source.appliedSummaryRevision() == 0L
                || count == coverage.coveredSequence() - turn.userSequence() + 1;
    }

    /** 每个来源存储实现都必须校验此转换，防止绕过操作接口降级或复用旧序号。 */
    public static void validateTransition(L0Snapshot before, L0Snapshot after) {
        requireSource(after);
        if (!before.memoryId().equals(after.memoryId())) throw new IllegalArgumentException("L0 应用身份不能变化");
        if (!before.hasKnownSource()) return;
        if (after.appliedSummaryRevision() < before.appliedSummaryRevision()) {
            throw new IllegalArgumentException("重置 epoch 也不能降低摘要读取下界");
        }
        if (!before.memoryEpoch().equals(after.memoryEpoch())) return;
        if (after.nextSequence() < before.nextSequence()) throw new IllegalArgumentException("同 epoch 不能重新编号");
        var old = new HashMap<Long, L0Snapshot.Message>();
        for (var entry : before.entries()) old.put(entry.sequence(), entry);
        for (var entry : after.entries()) {
            if (entry.sequence() == 0L) continue;
            var previous = old.get(entry.sequence());
            if ((previous != null && !previous.equals(entry))
                    || (previous == null && entry.sequence() < before.nextSequence())) {
                throw new IllegalArgumentException("已有来源不能在同 epoch 改写或复用");
            }
        }
        if (after.appliedSummaryRevision() == 0L && before.entries().stream().anyMatch(entry ->
                entry.sequence() > 0L && !after.entries().contains(entry))) {
            throw new IllegalArgumentException("原文回收必须同时记录有效摘要依赖");
        }
    }

    public static String fingerprint(L0Snapshot source, long userHistoryId, long throughSequence, long evidenceRevision) {
        requireSource(source);
        StringBuilder input = new StringBuilder(source.memoryId()).append('\n').append(source.memoryEpoch())
                .append('\n').append(userHistoryId).append('\n').append(evidenceRevision).append('\n');
        var messages = new ArrayList<ChatMessage>();
        for (var entry : source.entries()) {
            if (entry.userHistoryId() == userHistoryId && entry.sequence() <= throughSequence) {
                input.append(entry.sequence()).append(':').append(entry.userHistoryId()).append(';');
                messages.add(entry.payload());
            }
        }
        input.append('\n').append(ChatMessageSerializer.messagesToJson(messages));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", exception);
        }
    }

    public static boolean containsPrefix(L0Snapshot current, L0Snapshot captured, long throughSequence) {
        if (!current.hasKnownSource() || !captured.hasKnownSource()
                || !current.memoryId().equals(captured.memoryId())
                || !current.memoryEpoch().equals(captured.memoryEpoch())) return false;
        var expected = captured.entries().stream().filter(entry -> entry.sequence() > 0 && entry.sequence() <= throughSequence).toList();
        var actual = current.entries().stream().filter(entry -> entry.sequence() > 0 && entry.sequence() <= throughSequence).toList();
        return expected.equals(actual);
    }

    private static void requireSource(L0Snapshot source) {
        Objects.requireNonNull(source, "来源窗口不能为空");
        if (!source.hasKnownSource()) throw new IllegalStateException("旧 L0 来源及读取下界尚未对齐");
    }

    private static L0Snapshot copy(L0Snapshot source, String epoch, long next, long revision,
                                    List<L0Snapshot.Message> entries, List<L0Snapshot.Turn> turns) {
        return new L0Snapshot(L0Snapshot.CURRENT_VERSION, source.memoryId(), epoch, next, revision, entries, turns);
    }
}
