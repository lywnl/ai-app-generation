package com.lyw.appgeneration.ai.model.message;

import java.util.Objects;

/** 只承载客户端可见固定文案的上下文压缩控制消息。 */
public record ContextCompressionMessage(
        String protocol, Phase phase, String message) {

    public static final String PROTOCOL = "context-compression/v1";
    private static final String STARTED_MESSAGE =
            "正在压缩上下文，请稍候…";
    private static final String COMPLETED_MESSAGE =
            "上下文压缩完成，继续生成…";

    public ContextCompressionMessage {
        if (!PROTOCOL.equals(protocol)) {
            throw new IllegalArgumentException("上下文压缩协议版本不受信");
        }
        Objects.requireNonNull(phase, "上下文压缩阶段不能为空");
        if (!phase.message().equals(message)) {
            throw new IllegalArgumentException("上下文压缩文案必须使用固定安全文案");
        }
    }

    public static ContextCompressionMessage started() {
        return new ContextCompressionMessage(
                PROTOCOL, Phase.STARTED, STARTED_MESSAGE);
    }

    public static ContextCompressionMessage completed() {
        return new ContextCompressionMessage(
                PROTOCOL, Phase.COMPLETED, COMPLETED_MESSAGE);
    }

    public enum Phase {
        STARTED(STARTED_MESSAGE),
        COMPLETED(COMPLETED_MESSAGE);

        private final String message;

        Phase(String message) {
            this.message = message;
        }

        private String message() {
            return message;
        }
    }
}
