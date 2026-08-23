package com.lyw.appgeneration.ai.model.message;

import lombok.Getter;

/**
 * 流式消息类型枚举
 */
@Getter
public enum StreamMessageTypeEnum {

    AI_RESPONSE("ai_response", "AI响应"),
    TOOL_REQUEST("tool_request", "工具请求"),
    TOOL_ARGUMENT("tool_argument", "工具参数完成"),
    TOOL_ARGUMENT_DELTA("tool_argument_delta", "工具参数增量"),
    TOOL_EXECUTED("tool_executed", "工具执行结果"),
    INTERNAL_OUTPUT_ROLLBACK(
            "internal_output_rollback", "内部输出回滚"),
    INTERNAL_OUTPUT_RECOVERY(
            "internal_output_recovery", "内部输出恢复"),
    TURN_OUTCOME("turn_outcome", "回合终态");

    private final String value;
    private final String text;

    StreamMessageTypeEnum(String value, String text) {
        this.value = value;
        this.text = text;
    }

    /**
     * 根据值获取枚举
     */
    public static StreamMessageTypeEnum getEnumByValue(String value) {
        for (StreamMessageTypeEnum typeEnum : values()) {
            if (typeEnum.getValue().equals(value)) {
                return typeEnum;
            }
        }
        return null;
    }
}
