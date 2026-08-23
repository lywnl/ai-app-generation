package com.lyw.appgeneration.ai.model.message;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 工具参数增量:streaming 字段(如 writeFile.content)的实时片段。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class ToolArgumentDeltaMessage extends StreamMessage {

    private long generation;

    private String id;
    private String name;
    private String key;
    /** 本次新增的文本片段(已做 JSON 转义还原) */
    private String delta;

    public ToolArgumentDeltaMessage(String id, String name, String key, String delta) {
        super(StreamMessageTypeEnum.TOOL_ARGUMENT_DELTA.getValue());
        this.id = id;
        this.name = name;
        this.key = key;
        this.delta = delta;
    }

    public ToolArgumentDeltaMessage(
            long generation, String id, String name,
            String key, String delta) {
        super(StreamMessageTypeEnum.TOOL_ARGUMENT_DELTA.getValue());
        this.generation = AiResponseMessage.requirePositiveGeneration(
                generation);
        this.id = requireText(id, "工具请求 ID");
        this.name = requireText(name, "工具名");
        this.key = requireText(key, "工具参数名");
        this.delta = java.util.Objects.requireNonNull(
                delta, "工具参数增量不能为空");
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
