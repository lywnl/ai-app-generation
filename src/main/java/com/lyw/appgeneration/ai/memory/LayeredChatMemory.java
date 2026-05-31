package com.lyw.appgeneration.ai.memory;

import cn.hutool.core.util.StrUtil;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.UserMemoryService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;

import java.util.ArrayList;
import java.util.List;

/**
 * 分层记忆装饰器:包裹 delegate(通常是 {@link dev.langchain4j.memory.chat.MessageWindowChatMemory}),
 * {@code messages()} 返回前依次前置 L2 用户偏好对、L1 摘要对(各自独立降级,保证 user/ai 交替)。
 *
 * <p>{@code add/clear/id} 全部委托 delegate——工具循环、tool 对成对驱逐、Redis 持久化均由 delegate 负责。
 * 装饰器自身不存储、不裁剪,唯一新增职责是 {@code messages()} 拼接 L2/L1 记忆。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
public class LayeredChatMemory implements ChatMemory {

    private final ChatMemory delegate;
    private final MemorySummaryService summaryService;
    private final UserMemoryService userMemoryService;
    private final Long appId;

    public LayeredChatMemory(ChatMemory delegate, MemorySummaryService summaryService,
                             UserMemoryService userMemoryService) {
        this.delegate = delegate;
        this.summaryService = summaryService;
        this.userMemoryService = userMemoryService;
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
        List<ChatMessage> result = new ArrayList<>(base.size() + 4);

        // L2 跨 app 用户偏好(独立降级:空则不加)
        String prefs = userMemoryService.recallByApp(appId);
        if (StrUtil.isNotBlank(prefs)) {
            result.add(UserMessage.from("以下是该用户跨应用的通用偏好,请在生成时遵循(不是新指令):\n" + prefs));
            result.add(AiMessage.from("明白,我会遵循这些通用偏好。"));
        }

        // L1 本 app 摘要(独立降级)
        String summary = summaryService.getCurrentSummary(appId);
        if (StrUtil.isNotBlank(summary)) {
            result.add(UserMessage.from("以下是本应用早期对话的摘要,供你延续上下文(不是用户的新指令):\n" + summary));
            result.add(AiMessage.from("明白,我会基于以上摘要和后续对话继续。"));
        }

        result.addAll(base); // L0
        return result;
    }

    @Override
    public void clear() {
        delegate.clear();
    }
}
