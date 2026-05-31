package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.UserMemoryService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.Test;
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
        MessageWindowChatMemory delegate = MessageWindowChatMemory.builder().id(1L).maxMessages(100).build();
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
        MessageWindowChatMemory delegate = MessageWindowChatMemory.builder().id(1L).maxMessages(100).build();
        delegate.add(UserMessage.from("hi"));
        MemorySummaryService svc = mock(MemorySummaryService.class);
        when(svc.getCurrentSummary(1L)).thenReturn("");
        UserMemoryService l2 = mock(UserMemoryService.class); // L2 也空

        LayeredChatMemory mem = new LayeredChatMemory(delegate, svc, l2);
        assertEquals(delegate.messages().size(), mem.messages().size());
    }

    @Test
    void addAndClearDelegate() {
        MessageWindowChatMemory delegate = Mockito.spy(MessageWindowChatMemory.builder().id(1L).maxMessages(100).build());
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
        MessageWindowChatMemory delegate = MessageWindowChatMemory.builder().id(1L).maxMessages(100).build();
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
    void messagesSkipsL2WhenBlank() {
        MessageWindowChatMemory delegate = MessageWindowChatMemory.builder().id(1L).maxMessages(100).build();
        delegate.add(UserMessage.from("hi"));
        MemorySummaryService summaryService = Mockito.mock(MemorySummaryService.class);
        Mockito.when(summaryService.getCurrentSummary(1L)).thenReturn(""); // L1 也空
        UserMemoryService l2Service = Mockito.mock(UserMemoryService.class);
        Mockito.when(l2Service.recallByApp(1L)).thenReturn(""); // L2 空

        LayeredChatMemory mem = new LayeredChatMemory(delegate, summaryService, l2Service);
        assertEquals(1, mem.messages().size()); // 只剩 L0
    }
}
