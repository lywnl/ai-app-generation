package com.lyw.appgeneration.service;

import com.lyw.appgeneration.ai.memory.TokenAwareChatMemory;
import com.lyw.appgeneration.core.handler.VueTurnContext;

import java.util.Objects;

/** 仅运行期使用的统一摘要工作范围，不作为持久化格式或对外请求参数。 */
public record UnifiedSummaryRequest(TokenAwareChatMemory memory, VueTurnContext context) {
    public UnifiedSummaryRequest {
        Objects.requireNonNull(memory);
        Objects.requireNonNull(context);
        if (!memory.id().toString().equals(Long.toString(context.appId()))) throw new IllegalArgumentException("摘要工作与应用不一致");
        context.userHistoryId();
    }
    public long appId() { return context.appId(); }
    public long userId() { return context.userId(); }
    public long userHistoryId() { return context.userHistoryId(); }
}
