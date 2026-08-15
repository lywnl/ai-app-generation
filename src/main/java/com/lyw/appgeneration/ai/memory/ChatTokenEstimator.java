package com.lyw.appgeneration.ai.memory;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/** 统一估算文本、消息和最终模型请求所占 Token。 */
public interface ChatTokenEstimator {

    int estimateText(String text);

    int estimateMessages(List<ChatMessage> messages);

    int estimateToolSpecifications(List<ToolSpecification> tools);

    int estimateRequest(List<ChatMessage> messages,
                        List<ToolSpecification> tools);
}
