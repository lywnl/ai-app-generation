package com.lyw.appgeneration.ai.model.message;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 工具参数:某 key 的完整 value 解析完成时下发(buffered 字段的常规下发形式)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class ToolArgumentMessage extends StreamMessage {

    /** tool call id */
    private String id;
    /** 工具名 */
    private String name;
    /** 参数 key,如 relativeFilePath / content */
    private String key;
    /** 完整 value(已做 JSON 转义还原) */
    private String value;

    public ToolArgumentMessage(String id, String name, String key, String value) {
        super(StreamMessageTypeEnum.TOOL_ARGUMENT.getValue());
        this.id = id;
        this.name = name;
        this.key = key;
        this.value = value;
    }
}
