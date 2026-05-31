package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.service.MemorySummaryService;
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
 * {@link LayeredChatMemory} 装饰器单测:前置摘要对 / 无摘要透传 / add+clear 委托。
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

        LayeredChatMemory mem = new LayeredChatMemory(delegate, svc);
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

        LayeredChatMemory mem = new LayeredChatMemory(delegate, svc);
        assertEquals(delegate.messages().size(), mem.messages().size());
    }

    @Test
    void addAndClearDelegate() {
        MessageWindowChatMemory delegate = Mockito.spy(MessageWindowChatMemory.builder().id(1L).maxMessages(100).build());
        MemorySummaryService svc = mock(MemorySummaryService.class);
        LayeredChatMemory mem = new LayeredChatMemory(delegate, svc);

        UserMessage u = UserMessage.from("x");
        mem.add(u);
        mem.clear();
        verify(delegate).add(u);
        verify(delegate).clear();
        assertEquals(1L, mem.id());
    }
}
