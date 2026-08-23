package com.lyw.appgeneration.ai.model.message;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 工具调用消息
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class ToolRequestMessage extends StreamMessage {

    private long generation;

    private String id;

    private String name;

    private String arguments;

    public ToolRequestMessage(ToolExecutionRequest toolExecutionRequest) {
        super(StreamMessageTypeEnum.TOOL_REQUEST.getValue());
        this.id = toolExecutionRequest.id();
        this.name = toolExecutionRequest.name();
        this.arguments = toolExecutionRequest.arguments();
    }

    public ToolRequestMessage(String id, String name, String arguments) {
        super(StreamMessageTypeEnum.TOOL_REQUEST.getValue());
        this.id = id;
        this.name = name;
        this.arguments = arguments;
    }

    public ToolRequestMessage(
            long generation, String id, String name, String arguments) {
        super(StreamMessageTypeEnum.TOOL_REQUEST.getValue());
        this.generation = AiResponseMessage.requirePositiveGeneration(
                generation);
        this.id = requireText(id, "工具请求 ID");
        this.name = requireText(name, "工具名");
        this.arguments = arguments;
    }

    public void setGeneration(long generation) {
        this.generation = AiResponseMessage.requirePositiveGeneration(
                generation);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空白");
        }
        return value;
    }
}
