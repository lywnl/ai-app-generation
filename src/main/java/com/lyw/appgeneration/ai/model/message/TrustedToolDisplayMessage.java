package com.lyw.appgeneration.ai.model.message;

import java.util.Objects;

/** 服务端根据真实工具事件生成的受信展示片段。 */
public record TrustedToolDisplayMessage(
        long generation,
        String toolRequestId,
        Stage stage,
        String text) {

    public TrustedToolDisplayMessage {
        AiResponseMessage.requirePositiveGeneration(generation);
        if (toolRequestId == null || toolRequestId.isBlank()) {
            throw new IllegalArgumentException(
                    "可信工具展示必须携带请求 ID");
        }
        stage = Objects.requireNonNull(stage, "可信工具展示阶段不能为空");
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException(
                    "可信工具展示正文不能为空");
        }
    }

    public enum Stage {
        REQUESTED,
        EXECUTED
    }
}
