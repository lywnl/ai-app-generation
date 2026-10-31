package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 同一次 Redis 读取的原文及来源边界。旧数组的摘要读取下界为未知，不能默认当作 0。
 * 普通消息序号在 epoch 内单调且裁剪后不重排；系统消息保留序号 0，不属于用户来源。
 */
public record L0Snapshot(int formatVersion, String memoryId, String memoryEpoch,
                         long nextSequence, Long appliedSummaryRevision,
                         List<Message> entries, List<Turn> turns) {
    public static final int CURRENT_VERSION = 1;

    public L0Snapshot {
        if (memoryId == null || memoryId.isBlank() || nextSequence < 1L) {
            throw new IllegalArgumentException("L0 应用身份或下一序号无效");
        }
        entries = List.copyOf(entries);
        turns = List.copyOf(turns);
        if (formatVersion == 0) {
            if (memoryEpoch != null || appliedSummaryRevision != null || !turns.isEmpty()) {
                throw new IllegalArgumentException("旧 L0 不得伪造来源及摘要读取下界");
            }
        } else if (formatVersion != CURRENT_VERSION || memoryEpoch == null || memoryEpoch.isBlank()
                || memoryEpoch.length() > 128 || appliedSummaryRevision == null || appliedSummaryRevision < 0L) {
            throw new IllegalArgumentException("L0 版本、来源或摘要读取下界无效");
        }
        long previous = -1L;
        var identities = new HashMap<Long, Turn>();
        long previousUser = 0L;
        for (Turn turn : turns) {
            if (turn.userHistoryId() <= previousUser || turn.userSequence() > nextSequence
                    || identities.put(turn.userHistoryId(), turn) != null
                    || (turn.endSequence() != null && turn.endSequence() >= nextSequence)) {
                throw new IllegalArgumentException("L0 回合次序或边界无效");
            }
            previousUser = turn.userHistoryId();
        }
        for (int index = 0; index < turns.size() - 1; index++) {
            if (!turns.get(index).closed()) throw new IllegalArgumentException("L0 较早回合尚未关闭");
        }
        for (Message entry : entries) {
            if (entry.sequence() <= previous || entry.sequence() >= nextSequence) {
                throw new IllegalArgumentException("L0 消息序号必须严格递增且低于下一序号");
            }
            previous = entry.sequence();
            if (formatVersion == CURRENT_VERSION) {
                if (entry.payload() instanceof SystemMessage) {
                    if (entry.sequence() != 0L || entry.userHistoryId() != 0L || entry.historyRecordId() != null) {
                        throw new IllegalArgumentException("系统消息不得冒充回合来源");
                    }
                } else {
                    Turn turn = identities.get(entry.userHistoryId());
                    if (turn == null || entry.sequence() < turn.userSequence()
                            || (turn.endSequence() != null && entry.sequence() > turn.endSequence())
                            || (entry.payload() instanceof UserMessage && entry.sequence() != turn.userSequence())) {
                        throw new IllegalArgumentException("L0 消息缺少一致的持久回合来源");
                    }
                    if (entry.historyRecordId() != null
                            && !(entry.payload() instanceof UserMessage && entry.historyRecordId() == entry.userHistoryId())
                            && !(entry.payload() instanceof dev.langchain4j.data.message.AiMessage ai
                            && !ai.hasToolExecutionRequests() && turn.closed()
                            && entry.historyRecordId().equals(turn.terminalHistoryId())
                            && entry.sequence() == turn.endSequence())) {
                        throw new IllegalArgumentException("L0 持久记录引用与后端回合边界不符");
                    }
                }
            }
        }
    }

    public boolean hasKnownSource() {
        return formatVersion == CURRENT_VERSION;
    }

    /** 返回纯模型消息，不传播来源元数据或读取下界。 */
    public List<ChatMessage> messages() {
        return entries.stream().map(Message::payload).toList();
    }

    public Turn turn(long userHistoryId) {
        return turns.stream().filter(turn -> turn.userHistoryId() == userHistoryId)
                .findFirst().orElse(null);
    }

    public static L0Snapshot empty(String memoryId, long summaryRevision) {
        return new L0Snapshot(CURRENT_VERSION, memoryId, UUID.randomUUID().toString(),
                1L, summaryRevision, List.of(), List.of());
    }

    public static L0Snapshot legacy(String memoryId, List<ChatMessage> messages) {
        var entries = new ArrayList<Message>();
        long sequence = 1L;
        for (ChatMessage message : messages) entries.add(new Message(sequence++, 0L, message));
        return new L0Snapshot(0, memoryId, null, sequence, null, entries, List.of());
    }

    public record Message(long sequence, long userHistoryId, ChatMessage payload, Long historyRecordId) {
        /** 运行期模型／工具原文没有后端历史记录引用，不能凭文字充当可信终态。 */
        public Message(long sequence, long userHistoryId, ChatMessage payload) {
            this(sequence, userHistoryId, payload, null);
        }
        public Message {
            if (sequence < 0 || userHistoryId < 0) throw new IllegalArgumentException("L0 消息身份无效");
            Objects.requireNonNull(payload, "L0 原消息不能为空");
            if (historyRecordId != null && historyRecordId <= 0) throw new IllegalArgumentException("持久记录引用无效");
        }
    }

    /** 结束信息仅由后端可靠持久化后登记，不解析模型正文中的成功字样。 */
    public record Turn(long userHistoryId, String turnId, long userSequence,
                       Long terminalHistoryId, ChatMemoryOutcome terminalOutcome, Long endSequence) {
        public Turn {
            if (userHistoryId <= 0 || userSequence <= 0 || turnId == null || turnId.isBlank()
                    || turnId.length() > 128 || (terminalHistoryId == null) != (terminalOutcome == null)
                    || (terminalHistoryId == null) != (endSequence == null)
                    || (terminalHistoryId != null && (terminalHistoryId <= userHistoryId || endSequence < userSequence))) {
                throw new IllegalArgumentException("L0 回合身份或终态边界无效");
            }
        }

        public boolean closed() {
            return terminalHistoryId != null;
        }
    }
}
