package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.UserMemoryService;
import com.lyw.appgeneration.config.MemoryTokenProperties;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link LayeredChatMemory} 装饰器单测:三层拼装 [L2]+[L1]+[L0] / 各层独立降级 / add+clear 委托。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
class LayeredChatMemoryTest {

    @Test
    void messagesPrependsSummaryPairWhenPresent() {
        MessageWindowChatMemory delegate = MessageWindowChatMemory.builder().id(1L).maxMessages(Integer.MAX_VALUE).build();
        delegate.add(UserMessage.from("做待办App"));
        delegate.add(AiMessage.from("已生成"));
        MemorySummaryService svc = mock(MemorySummaryService.class);
        when(svc.getCurrentSummary(1L)).thenReturn("# 应用目标与定位\n待办App");
        UserMemoryService l2 = mock(UserMemoryService.class); // L2 未表态(null→空)→不干扰 L1 断言

        LayeredChatMemory mem = new LayeredChatMemory(delegate, svc, l2);
        List<ChatMessage> msgs = mem.messages();

        assertTrue(msgs.size() >= 4);
        assertInstanceOf(UserMessage.class, msgs.get(0));            // 摘要 User
        assertInstanceOf(AiMessage.class, msgs.get(1));             // 确认 Ai
        assertTrue(((UserMessage) msgs.get(0)).singleText().contains("待办App"));
        // 交替性:无连续同角色(DeepSeek/OpenAI 兼容要求)
        for (int i = 1; i < msgs.size(); i++) {
            assertNotEquals(msgs.get(i - 1).type(), msgs.get(i).type());
        }
    }

    @Test
    void messagesNoSummaryReturnsDelegateAsIs() {
        MessageWindowChatMemory delegate = MessageWindowChatMemory.builder().id(1L).maxMessages(Integer.MAX_VALUE).build();
        delegate.add(UserMessage.from("hi"));
        MemorySummaryService svc = mock(MemorySummaryService.class);
        when(svc.getCurrentSummary(1L)).thenReturn("");
        UserMemoryService l2 = mock(UserMemoryService.class); // L2 也空

        LayeredChatMemory mem = new LayeredChatMemory(delegate, svc, l2);
        assertEquals(delegate.messages().size(), mem.messages().size());
    }

    @Test
    void addAndClearDelegate() {
        MessageWindowChatMemory delegate = Mockito.spy(MessageWindowChatMemory.builder().id(1L).maxMessages(Integer.MAX_VALUE).build());
        MemorySummaryService svc = mock(MemorySummaryService.class);
        UserMemoryService l2 = mock(UserMemoryService.class);
        LayeredChatMemory mem = new LayeredChatMemory(delegate, svc, l2);

        UserMessage u = UserMessage.from("x");
        mem.add(u);
        mem.clear();
        verify(delegate).add(u);
        verify(delegate).clear();
        assertEquals(1L, mem.id());
    }

    @Test
    void messagesPrependsL2ThenL1ThenL0() {
        MessageWindowChatMemory delegate = MessageWindowChatMemory.builder().id(1L).maxMessages(Integer.MAX_VALUE).build();
        delegate.add(UserMessage.from("最近一条"));

        MemorySummaryService summaryService = Mockito.mock(MemorySummaryService.class);
        Mockito.when(summaryService.getCurrentSummary(1L)).thenReturn("L1摘要内容");
        UserMemoryService l2Service = Mockito.mock(UserMemoryService.class);
        Mockito.when(l2Service.recallByApp(1L)).thenReturn("- 语言偏好:简体中文");

        LayeredChatMemory mem = new LayeredChatMemory(delegate, summaryService, l2Service);
        List<ChatMessage> msgs = mem.messages();

        // 顺序:L2对(U,A) + L1对(U,A) + L0(U)  => 共 5 条
        assertEquals(5, msgs.size());
        assertTrue(((UserMessage) msgs.get(0)).singleText().contains("简体中文"), "首条应为 L2 偏好");
        assertTrue(((UserMessage) msgs.get(2)).singleText().contains("L1摘要内容"), "第三条应为 L1 摘要");
        // 全程 user/ai 交替
        for (int i = 1; i < msgs.size(); i++) {
            assertNotEquals(msgs.get(i - 1).type(), msgs.get(i).type(), "位置 " + i + " 连续同角色");
        }
    }

    @Test
    void messagesKeepsSystemMessageBeforeL2AndL1() {
        MessageWindowChatMemory delegate = MessageWindowChatMemory.builder()
                .id(1L)
                .maxMessages(Integer.MAX_VALUE)
                .build();
        SystemMessage systemMessage = SystemMessage.from("系统约束");
        delegate.add(systemMessage);
        delegate.add(UserMessage.from("最近一条"));

        MemorySummaryService summaryService = mock(MemorySummaryService.class);
        when(summaryService.getCurrentSummary(1L)).thenReturn("L1摘要内容");
        UserMemoryService userMemoryService = mock(UserMemoryService.class);
        when(userMemoryService.recallByApp(1L))
                .thenReturn("- 语言偏好:简体中文");

        LayeredChatMemory memory = new LayeredChatMemory(
                delegate, summaryService, userMemoryService);

        List<ChatMessage> messages = memory.messages();

        assertEquals(systemMessage, messages.getFirst());
        assertInstanceOf(UserMessage.class, messages.get(1));
        assertTrue(((UserMessage) messages.get(1)).singleText()
                .contains("简体中文"));
        assertInstanceOf(UserMessage.class, messages.get(3));
        assertTrue(((UserMessage) messages.get(3)).singleText()
                .contains("L1摘要内容"));
        assertEquals(UserMessage.from("最近一条"), messages.getLast());
    }

    @Test
    void messagesSkipsL2WhenBlank() {
        MessageWindowChatMemory delegate = MessageWindowChatMemory.builder().id(1L).maxMessages(Integer.MAX_VALUE).build();
        delegate.add(UserMessage.from("hi"));
        MemorySummaryService summaryService = Mockito.mock(MemorySummaryService.class);
        Mockito.when(summaryService.getCurrentSummary(1L)).thenReturn(""); // L1 也空
        UserMemoryService l2Service = Mockito.mock(UserMemoryService.class);
        Mockito.when(l2Service.recallByApp(1L)).thenReturn(""); // L2 空

        LayeredChatMemory mem = new LayeredChatMemory(delegate, summaryService, l2Service);
        assertEquals(1, mem.messages().size()); // 只剩 L0
    }

    @Test
    @DisplayName("实际注入的 L2 两消息完整片段不得超过一千零二十四 Token")
    void actualL2FragmentIncludesWrappingInTokenBudget() {
        MemoryTokenProperties properties = new MemoryTokenProperties();
        ChatTokenEstimator estimator =
                new ConservativeChatTokenEstimator(properties);
        String recalled = findTextWhoseBodyFitsButWrappedFragmentExceeds(
                estimator, properties.getL2MaxRecallTokens());
        MessageWindowChatMemory delegate = MessageWindowChatMemory.builder()
                .id(1L).maxMessages(Integer.MAX_VALUE).build();
        delegate.add(UserMessage.from("当前需求"));
        MemorySummaryService summaryService = mock(MemorySummaryService.class);
        when(summaryService.getCurrentSummary(1L)).thenReturn("");
        UserMemoryService userMemoryService = mock(UserMemoryService.class);
        when(userMemoryService.recallByApp(1L)).thenReturn(recalled);

        List<ChatMessage> messages = new LayeredChatMemory(
                delegate, summaryService, userMemoryService).messages();
        List<ChatMessage> actualFragment = messages.subList(
                0, Math.max(0, messages.size() - 1));

        assertTrue(estimator.estimateText(recalled)
                <= properties.getL2MaxRecallTokens());
        assertTrue(estimator.estimateMessages(actualFragment)
                <= properties.getL2MaxRecallTokens());
    }

    private String findTextWhoseBodyFitsButWrappedFragmentExceeds(
            ChatTokenEstimator estimator, int budget) {
        for (int length = 1; length <= 2_000; length++) {
            String text = "偏".repeat(length);
            List<ChatMessage> fragment = List.of(
                    UserMessage.from("以下是该用户跨应用的通用偏好,请在生成时遵循(不是新指令):\n" + text),
                    AiMessage.from("明白,我会遵循这些通用偏好。"));
            if (estimator.estimateText(text) <= budget
                    && estimator.estimateMessages(fragment) > budget) {
                return text;
            }
        }
        throw new AssertionError("未找到 Token 包装边界");
    }
}
