package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemorySummarySnapshot;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 按持久化用户 ID 合并历史、L0 和证据；数据库分页缺口不能被跳过。 */
@Component
public class SummaryMaterialReader {
    private final ChatHistoryService histories;
    private final int pageSize;

    @Autowired
    public SummaryMaterialReader(ChatHistoryService histories) { this(histories, 100); }
    SummaryMaterialReader(ChatHistoryService histories, int pageSize) { this.histories = histories; this.pageSize = pageSize; }

    public SummaryMaterials read(long appId, long userId, long currentUserHistoryId, MemorySummarySnapshot previous,
                                  L0Snapshot source, AdmissionDeadline deadline) {
        if (!source.hasKnownSource() || !source.memoryId().equals(Long.toString(appId))
                || currentUserHistoryId <= previous.lastSummarizedId()) throw new IllegalArgumentException("摘要材料读取边界无效");
        var database = new LinkedHashMap<Long, HistoricalTurn>();
        var fingerprints = new LinkedHashMap<Long, String>();
        long cursor = previous.lastSummarizedId();
        HistoricalTurn pending = null;
        boolean reached = false;
        while (!reached) {
            requireTime(deadline);
            List<ChatHistory> page = histories.listMessagesAfterCursor(appId, cursor, pageSize);
            if (page == null) throw new IllegalStateException("历史分页读取失败");
            if (page.isEmpty()) break;
            for (ChatHistory row : page) {
                if (row == null || row.getId() == null || row.getId() <= cursor) throw new IllegalStateException("历史分页游标没有前进");
                if (row.getId() > currentUserHistoryId) { reached = true; break; }
                if (!Objects.equals(row.getAppId(), appId) || !Objects.equals(row.getUserId(), userId)
                        || (row.getIsDelete() != null && row.getIsDelete() != 0)) throw new IllegalStateException("历史来源归属无效");
                cursor = row.getId();
                fingerprints.put(cursor, fingerprint(row));
                if ("user".equals(row.getMessageType())) {
                    if (pending != null) throw new IllegalStateException("存在尚未闭合的历史缺口，不能跨过用户记录");
                    pending = new HistoricalTurn(row);
                    database.put(cursor, pending);
                } else if ("ai".equals(row.getMessageType())) {
                    if (pending == null || row.getMemoryOutcome() == null || row.getMemoryMessage() == null || row.getMemoryMessage().isBlank()) {
                        throw new IllegalStateException("历史缺少可信终态，不能推进摘要游标");
                    }
                    pending.terminal = row;
                    pending = null;
                } else throw new IllegalStateException("历史中存在不支持的来源角色");
                if (cursor == currentUserHistoryId) { reached = true; break; }
            }
            if (page.size() < pageSize) break;
        }
        if (!database.containsKey(currentUserHistoryId)) throw new IllegalStateException("未读取到当前持久用户记录");
        var parsed = new L0SourceParser().parse(source);
        var l0 = new LinkedHashMap<Long, L0SourceParser.TurnSources>();
        parsed.turns().forEach(turn -> l0.put(turn.boundary().userHistoryId(), turn));
        for (var turn : parsed.turns()) {
            if (turn.boundary().userHistoryId() > previous.lastSummarizedId()
                    && !database.containsKey(turn.boundary().userHistoryId())) throw new IllegalStateException("L0 来源缺少对应数据库历史");
        }
        var turns = new ArrayList<SummaryMaterials.Turn>();
        for (var historical : database.values()) {
            requireTime(deadline);
            long identity = historical.user.getId();
            DecisionEvidence evidence = DecisionEvidenceCodec.decode(historical.user.getDecisionEvidence(), identity);
            var original = l0.get(identity);
            var units = new ArrayList<SummaryUnit>();
            String epoch = original == null ? null : source.memoryEpoch();
            String turnId = original != null ? original.boundary().turnId()
                    : evidence != null ? evidence.turnId() : "history-" + identity;
            long endSequence = original == null ? 0 : original.units().stream()
                    .mapToLong(L0SourceParser.Unit::lastSequence).max().orElse(0);
            if (original == null || original.units().isEmpty()) {
                units.add(userUnit(historical.user, null, 0));
            } else {
                for (var unit : original.units()) {
                    if (unit.kind() == L0SourceParser.Kind.BACKEND_TERMINAL) continue;
                    SummaryUnit.Kind kind;
                    String id;
                    if (unit.messages().getFirst() instanceof UserMessage user) {
                        if (!user.hasSingleText() || !Objects.equals(user.singleText(), historical.user.getMessage())) {
                            throw new IllegalStateException("L0 用户原话与持久来源不一致");
                        }
                        kind = SummaryUnit.Kind.USER;
                        id = "user:" + identity;
                    } else {
                        kind = switch (unit.kind()) {
                            case TOOL_BATCH -> SummaryUnit.Kind.TOOL_BATCH;
                            case PENDING_TOOL_BATCH -> SummaryUnit.Kind.PENDING_TOOL_BATCH;
                            case MESSAGE -> SummaryUnit.Kind.MODEL_TEXT;
                            case BACKEND_TERMINAL -> throw new IllegalStateException("后端终态必须使用权威历史来源");
                        };
                        id = "l0:" + epoch + ":" + unit.firstSequence() + ":" + unit.lastSequence();
                    }
                    units.add(new SummaryUnit(id, identity, kind, epoch, unit.firstSequence(), unit.lastSequence(),
                            unit.messages(), null, null, null));
                }
            }
            Long terminalId = historical.terminal == null ? null : historical.terminal.getId();
            ChatMemoryOutcome outcome = historical.terminal == null ? null : historical.terminal.getMemoryOutcome();
            if (original != null && original.closed()
                    && (!Objects.equals(original.boundary().terminalHistoryId(), terminalId)
                    || original.boundary().terminalOutcome() != outcome)) throw new IllegalStateException("L0 与数据库终态来源不一致");
            if (terminalId != null) {
                units.add(new SummaryUnit("terminal:" + terminalId, identity, SummaryUnit.Kind.TERMINAL, null, 0, 0,
                        List.of(AiMessage.from(historical.terminal.getMemoryMessage())), terminalId, outcome, null));
            }
            if (evidence != null) {
                for (var entry : evidence.entries()) units.add(new SummaryUnit("decision:" + identity + ":" + entry.sourceId()
                        + ":" + entry.evidenceRevision(), identity, SummaryUnit.Kind.DECISION, null, 0, 0, List.of(), null, null, entry));
            }
            turns.add(new SummaryMaterials.Turn(identity, turnId, epoch, endSequence, terminalId, outcome, evidence, units));
        }
        return new SummaryMaterials(appId, userId, currentUserHistoryId, previous, source, turns, fingerprints);
    }

    private SummaryUnit userUnit(ChatHistory user, String epoch, long sequence) {
        if (user.getMessage() == null || user.getMessage().isBlank()) throw new IllegalStateException("用户历史原文为空");
        return new SummaryUnit("user:" + user.getId(), user.getId(), SummaryUnit.Kind.USER, epoch, sequence, sequence,
                List.of(UserMessage.from(user.getMessage())), null, null, null);
    }

    public static String fingerprint(ChatHistory history) {
        String input = history.getId() + "\n" + history.getAppId() + "\n" + history.getUserId() + "\n"
                + history.getMessageType() + "\n" + ("user".equals(history.getMessageType()) ? history.getMessage() : history.getMemoryMessage())
                + "\n" + history.getMemoryOutcome();
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("缺少 SHA-256", exception); }
    }

    private void requireTime(AdmissionDeadline deadline) {
        if (deadline.remainingNanos() <= 0) throw new java.util.concurrent.CancellationException("读取摘要材料超过原截止");
    }
    private static final class HistoricalTurn {
        final ChatHistory user;
        ChatHistory terminal;
        HistoricalTurn(ChatHistory user) { this.user = user; }
    }
}
