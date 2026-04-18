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
}
