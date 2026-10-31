package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.ai.plan.AppPlan;
import com.lyw.appgeneration.service.CompatibleMemorySnapshot;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;

import java.util.List;
import java.util.Objects;

/** 同一次准备中冻结的相容记忆、计划、完整消息和计量结果；发送阶段不得再追加资料。 */
record RequestContextSnapshot(CompatibleMemorySnapshot memory, AppPlan plan,
                              List<ChatMessage> messages, List<ToolSpecification> tools,
                              int estimatedTokens) {
    RequestContextSnapshot {
        Objects.requireNonNull(memory, "相容记忆不能为空");
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
        if (estimatedTokens < 0) throw new IllegalArgumentException("请求 Token 不能为负数");
    }
}
