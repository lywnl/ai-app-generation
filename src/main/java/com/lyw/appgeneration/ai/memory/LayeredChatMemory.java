package com.lyw.appgeneration.ai.memory;

import cn.hutool.core.util.StrUtil;
import com.lyw.appgeneration.service.MemorySummaryService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;

import java.util.ArrayList;
import java.util.List;

/**
 * 分层记忆装饰器:包裹 delegate(通常是 {@link dev.langchain4j.memory.chat.MessageWindowChatMemory}),
 * 在 {@code messages()} 返回前前置 L1 摘要(一对 User摘要 / AI确认,保证 user/ai 交替)。
 *
 * <p>{@code add/clear/id} 全部委托 delegate——工具循环、tool 对成对驱逐、Redis 持久化均由 delegate 负责。
 * 装饰器自身不存储、不裁剪,唯一新增职责是 {@code messages()} 拼接摘要。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
public class LayeredChatMemory implements ChatMemory {

    private final ChatMemory delegate;
    private final MemorySummaryService summaryService;
    private final Long appId;

    public LayeredChatMemory(ChatMemory delegate, MemorySummaryService summaryService) {
        this.delegate = delegate;
        this.summaryService = summaryService;
        this.appId = (Long) delegate.id();
    }

    @Override
    public Object id() {
        return delegate.id();
    }

    @Override
    public void add(ChatMessage message) {
        delegate.add(message);
    }

    @Override
    public List<ChatMessage> messages() {
        List<ChatMessage> base = delegate.messages();
        String summary = summaryService.getCurrentSummary(appId);
        if (StrUtil.isBlank(summary)) {
            return base;
        }
        List<ChatMessage> result = new ArrayList<>(base.size() + 2);
        // 前置一对 (User摘要, AI确认):既注入历史语义,又保证后续 user/ai 交替不被破坏
        result.add(UserMessage.from("以下是本应用早期对话的摘要,供你延续上下文(不是用户的新指令):\n" + summary));
        result.add(AiMessage.from("明白,我会基于以上摘要和后续对话继续。"));
        result.addAll(base);
        return result;
    }

    @Override
    public void clear() {
        delegate.clear();
    }
}
