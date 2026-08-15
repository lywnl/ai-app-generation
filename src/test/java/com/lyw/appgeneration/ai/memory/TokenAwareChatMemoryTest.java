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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenAwareChatMemoryTest {

    @Test
    void removesOnlyTaskStartPrefixAndKeepsLateCompletedAndCurrentTurns() {
        MessageWindowChatMemory delegate = memory();
        UserMessage firstUser = UserMessage.from("第一轮");
        AiMessage firstAi = AiMessage.from("第一轮完成");
        delegate.add(firstUser);
        delegate.add(firstAi);
        addStableTurn(delegate, "第二轮", "第二轮完成");
        TokenAwareChatMemory memory = new TokenAwareChatMemory(delegate);
        List<ChatMessage> taskStartPrefix = List.of(firstUser, firstAi);

        addStableTurn(delegate, "异步期间新增", "新增回合完成");
        UserMessage currentUser = UserMessage.from("当前回合");
        ToolExecutionRequest request = request("call-current");
        AiMessage toolRequest = AiMessage.from(request);
        ToolExecutionResultMessage toolResult =
                ToolExecutionResultMessage.from(request, "当前工具结果");
        memory.add(currentUser);
        memory.add(toolRequest);
        memory.add(toolResult);

        boolean removed = memory.removeCompletedPrefixIfMatches(
                taskStartPrefix);

        assertTrue(removed);
        assertEquals(List.of(
                UserMessage.from("第二轮"),
                AiMessage.from("第二轮完成"),
                UserMessage.from("异步期间新增"),
                AiMessage.from("新增回合完成"),
                currentUser, toolRequest, toolResult), memory.messages());
    }

    @Test
    void rejectsPartialTurnPrefixWithoutMutatingMemory() {
        MessageWindowChatMemory delegate = memory();
        UserMessage firstUser = UserMessage.from("第一轮");
        AiMessage firstAi = AiMessage.from("第一轮完成");
        delegate.add(firstUser);
        delegate.add(firstAi);
        addStableTurn(delegate, "第二轮", "第二轮完成");
        TokenAwareChatMemory memory = new TokenAwareChatMemory(delegate);
        List<ChatMessage> before = memory.messages();

        boolean removed = memory.removeCompletedPrefixIfMatches(
                List.of(firstUser));

        assertFalse(removed);
        assertEquals(before, memory.messages());
    }

    @Test
    void returnsFullDelegateHistoryBeforeExplicitCompaction() {
        MessageWindowChatMemory delegate = memory();
        addStableTurn(delegate, "第一轮", "第一轮完成");
        addStableTurn(delegate, "第二轮", "第二轮完成");
        TokenAwareChatMemory memory = new TokenAwareChatMemory(delegate);

        assertEquals(delegate.messages(), memory.messages());
    }

    @Test
    void removesOldCompletedPrefixButKeepsCurrentToolTurnWhole() {
        MessageWindowChatMemory delegate = memory();
        UserMessage firstUser = UserMessage.from("第一轮");
        AiMessage firstAi = AiMessage.from("第一轮完成");
        delegate.add(firstUser);
        delegate.add(firstAi);
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

        boolean removed = memory.removeCompletedPrefixIfMatches(
                List.of(firstUser, firstAi));

        assertTrue(removed);
        assertEquals(List.of(
                UserMessage.from("第二轮"),
                AiMessage.from("第二轮完成"),
                currentUser, toolRequest, toolResult), memory.messages());
    }

    @Test
    void completedLatestTurnRemainsAfterRemovingEarlierStablePrefix() {
        MessageWindowChatMemory delegate = memory();
        UserMessage firstUser = UserMessage.from("第一轮");
        AiMessage firstAi = AiMessage.from("第一轮完成");
        delegate.add(firstUser);
        delegate.add(firstAi);
        addStableTurn(delegate, "第二轮", "第二轮完成");
        TokenAwareChatMemory memory = new TokenAwareChatMemory(delegate);

        boolean removed = memory.removeCompletedPrefixIfMatches(
                List.of(firstUser, firstAi));

        assertTrue(removed);
        assertEquals(2, memory.messages().size());
        assertEquals("第二轮",
                ((UserMessage) memory.messages().getFirst()).singleText());
    }

    @Test
    void emptyPrefixIsNoOpAndPreservesMessagesWithoutUserBoundary() {
        MessageWindowChatMemory delegate = memory();
        SystemMessage system = SystemMessage.from("系统约束");
        AiMessage unknownTail = AiMessage.from("孤立回复");
        delegate.add(system);
        delegate.add(unknownTail);
        TokenAwareChatMemory memory = new TokenAwareChatMemory(delegate);

        boolean removed = memory.removeCompletedPrefixIfMatches(List.of());

        assertTrue(removed);
        assertEquals(List.of(system, unknownTail), memory.messages());
    }

    @Test
    void changedCompletePrefixIsRejectedWithoutMutatingMemory() {
        MessageWindowChatMemory delegate = memory();
        UserMessage taskUser = UserMessage.from("任务启动时第一轮");
        AiMessage taskAi = AiMessage.from("任务启动时第一轮完成");
        delegate.add(taskUser);
        delegate.add(taskAi);
        addStableTurn(delegate, "第二轮", "第二轮完成");
        TokenAwareChatMemory memory = new TokenAwareChatMemory(delegate);
        List<ChatMessage> expectedPrefix = List.of(taskUser, taskAi);

        memory.clear();
        addStableTurn(delegate, "已变化第一轮", "已变化第一轮完成");
        addStableTurn(delegate, "第二轮", "第二轮完成");
        List<ChatMessage> before = memory.messages();

        boolean removed = memory.removeCompletedPrefixIfMatches(
                expectedPrefix);

        assertFalse(removed);
        assertEquals(before, memory.messages());
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
