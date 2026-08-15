package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.UserMemoryService;
import dev.langchain4j.data.message.ChatMessage;

import java.util.List;
import java.util.Objects;

/** 同时暴露最新分层请求视图与可原子裁剪 L0 的在线记忆。 */
public final class CompressionAwareChatMemory extends LayeredChatMemory {

    private final ConversationTurnSnapshotParser snapshotParser;

    public CompressionAwareChatMemory(
            TokenAwareChatMemory l0Memory,
            MemorySummaryService summaryService,
            UserMemoryService userMemoryService) {
        super(Objects.requireNonNull(l0Memory, "L0 记忆不能为空"),
                summaryService, userMemoryService);
        this.snapshotParser = new ConversationTurnSnapshotParser();
    }

    ConversationTurnSnapshotParser.Snapshot completeTurnSnapshot() {
        return snapshotParser.parse(l0Memory().messages());
    }

    boolean removeCompletedPrefixIfMatches(
            List<ChatMessage> expectedPrefix) {
        return l0Memory().removeCompletedPrefixIfMatches(expectedPrefix);
    }

    private TokenAwareChatMemory l0Memory() {
        return (TokenAwareChatMemory) delegateMemory();
    }
}
