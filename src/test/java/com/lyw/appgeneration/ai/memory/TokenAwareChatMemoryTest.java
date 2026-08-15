package com.lyw.appgeneration.ai.memory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TokenAwareChatMemoryTest {

    @Test
    void returnsFullDelegateHistoryBeforeExplicitCompaction() {
        MessageWindowChatMemory delegate = memory();
        addStableTurn(delegate, "第一轮", "第一轮完成");
        addStableTurn(delegate, "第二轮", "第二轮完成");
        TokenAwareChatMemory memory = new TokenAwareChatMemory(delegate);

        assertEquals(delegate.messages(), memory.messages());
    }

    @Test
    void replacesCompletedPrefixButKeepsCurrentToolTurnWhole() {
        MessageWindowChatMemory delegate = memory();
        addStableTurn(delegate, "第一轮", "第一轮完成");
        addStableTurn(delegate, "第二轮", "第二轮完成");
        UserMessage currentUser = UserMessage.from("第三轮");
        ToolExecutionRequest request = request("call-3");
        AiMessage toolRequest = AiMessage.from(request);
        ToolExecutionResultMessage toolResult =
                ToolExecutionResultMessage.from(request, "工具结果");
        delegate.add(currentUser);
        delegate.add(toolRequest);
        delegate.add(toolResult);
        TokenAwareChatMemory memory = new TokenAwareChatMemory(delegate);

        memory.replaceCompletedPrefix(List.of(
                UserMessage.from("第二轮"),
                AiMessage.from("第二轮完成")));

        assertEquals(List.of(
                UserMessage.from("第二轮"),
                AiMessage.from("第二轮完成"),
                currentUser, toolRequest, toolResult), memory.messages());
    }

    @Test
    void completedLatestTurnIsReplaceableInsteadOfBeingTreatedAsActive() {
        MessageWindowChatMemory delegate = memory();
        addStableTurn(delegate, "第一轮", "第一轮完成");
        addStableTurn(delegate, "第二轮", "第二轮完成");
        TokenAwareChatMemory memory = new TokenAwareChatMemory(delegate);

        memory.replaceCompletedPrefix(List.of(
                UserMessage.from("第二轮"),
                AiMessage.from("第二轮完成")));

        assertEquals(2, memory.messages().size());
        assertEquals("第二轮",
                ((UserMessage) memory.messages().getFirst()).singleText());
    }

    @Test
    void preservesSystemPrefixAndUnknownTailWithoutUserBoundary() {
        MessageWindowChatMemory delegate = memory();
        SystemMessage system = SystemMessage.from("系统约束");
        AiMessage unknownTail = AiMessage.from("孤立回复");
        delegate.add(system);
        delegate.add(unknownTail);
        TokenAwareChatMemory memory = new TokenAwareChatMemory(delegate);

        memory.replaceCompletedPrefix(List.of());

        assertEquals(List.of(system, unknownTail), memory.messages());
    }

    @Test
    void delegatesIdAddAndClear() {
        MessageWindowChatMemory delegate = memory();
        TokenAwareChatMemory memory = new TokenAwareChatMemory(delegate);
        UserMessage user = UserMessage.from("新回合");

        memory.add(user);
        assertEquals(99L, memory.id());
        assertEquals(List.of(user), memory.messages());

        memory.clear();
        assertEquals(List.of(), memory.messages());
    }

    private MessageWindowChatMemory memory() {
        return MessageWindowChatMemory.builder()
                .id(99L)
                .maxMessages(Integer.MAX_VALUE)
                .build();
    }

    private void addStableTurn(
            MessageWindowChatMemory memory, String user, String ai) {
        memory.add(UserMessage.from(user));
        memory.add(AiMessage.from(ai));
    }

    private ToolExecutionRequest request(String id) {
        return ToolExecutionRequest.builder()
                .id(id)
                .name("writeFile")
                .arguments("{}")
                .build();
    }
}
