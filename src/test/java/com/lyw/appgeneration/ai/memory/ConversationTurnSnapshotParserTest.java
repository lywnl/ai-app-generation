package com.lyw.appgeneration.ai.memory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConversationTurnSnapshotParserTest {

    private final ConversationTurnSnapshotParser parser =
            new ConversationTurnSnapshotParser();

    @Test
    void parsesToolLoopAsOneCompletedTurnAndKeepsCurrentUserOutsideCandidates() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-1")
                .name("writeFile")
                .arguments("{\"path\":\"src/App.vue\"}")
                .build();
        List<ChatMessage> completed = List.of(
                UserMessage.from("生成首页"),
                AiMessage.from(request),
                ToolExecutionResultMessage.from(request, "写入成功"),
                AiMessage.from("首页已生成"));
        UserMessage currentUser = UserMessage.from("再加一个搜索框");
        ToolExecutionRequest currentRequest = ToolExecutionRequest.builder()
                .id("call-current")
                .name("modifyFile")
                .arguments("{\"path\":\"src/App.vue\"}")
                .build();
        AiMessage currentToolRequest = AiMessage.from(currentRequest);
        ToolExecutionResultMessage currentToolResult =
                ToolExecutionResultMessage.from(currentRequest, "修改成功");

        ConversationTurnSnapshotParser.Snapshot snapshot = parser.parse(
                List.of(completed.get(0), completed.get(1), completed.get(2),
                        completed.get(3), currentUser, currentToolRequest,
                        currentToolResult));

        assertEquals(1, snapshot.completedTurns().size());
        assertEquals(completed, snapshot.completedTurns().getFirst().messages());
        assertEquals("首页已生成",
                snapshot.completedTurns().getFirst().terminalAiText());
        assertEquals(List.of(currentUser, currentToolRequest, currentToolResult),
                snapshot.unfinishedTail());
    }

    @Test
    void acceptsAugmentedMultiContentUserWithoutUsingItForAlignment() {
        UserMessage augmentedUser = UserMessage.from(
                TextContent.from("原始问题"),
                TextContent.from("图片与 RAG 增强上下文"));
        AiMessage terminalAi = AiMessage.from("稳定终态回复");

        ConversationTurnSnapshotParser.Snapshot snapshot = parser.parse(
                List.of(augmentedUser, terminalAi));

        assertEquals(1, snapshot.completedTurns().size());
        assertEquals(List.of(augmentedUser, terminalAi),
                snapshot.completedTurns().getFirst().messages());
        assertEquals("稳定终态回复",
                snapshot.completedTurns().getFirst().terminalAiText());
    }
}
