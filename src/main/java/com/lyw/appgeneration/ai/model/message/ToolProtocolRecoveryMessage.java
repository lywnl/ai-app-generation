package com.lyw.appgeneration.ai.model.message;

import java.util.Objects;

/** 只承载客户端可见固定文案的工具协议恢复控制消息。 */
public record ToolProtocolRecoveryMessage(
        String protocol, Phase phase, String message) {

    public static final String PROTOCOL = "tool-protocol-recovery/v1";
    private static final String STARTED_MESSAGE =
            "正在校正工具调用，请稍候…";
    private static final String RECOVERED_MESSAGE =
            "工具调用已校正，继续生成…";
    private static final String FAILED_MESSAGE =
            "工具调用格式异常，系统自动校正后仍未恢复。"
                    + "本轮没有执行相关工具，请重新发送请求。";

    public ToolProtocolRecoveryMessage {
        if (!PROTOCOL.equals(protocol)) {
            throw new IllegalArgumentException("工具协议恢复版本不受信");
        }
        Objects.requireNonNull(phase, "工具协议恢复阶段不能为空");
        if (!phase.message().equals(message)) {
            throw new IllegalArgumentException("工具协议恢复文案必须使用固定安全文案");
        }
    }

    public static ToolProtocolRecoveryMessage started() {
        return new ToolProtocolRecoveryMessage(
                PROTOCOL, Phase.STARTED, STARTED_MESSAGE);
    }

    public static ToolProtocolRecoveryMessage recovered() {
        return new ToolProtocolRecoveryMessage(
                PROTOCOL, Phase.RECOVERED, RECOVERED_MESSAGE);
    }

    public static ToolProtocolRecoveryMessage failed() {
        return new ToolProtocolRecoveryMessage(
                PROTOCOL, Phase.FAILED, FAILED_MESSAGE);
    }

    public enum Phase {
        STARTED(STARTED_MESSAGE),
        RECOVERED(RECOVERED_MESSAGE),
        FAILED(FAILED_MESSAGE);

        private final String message;

        Phase(String message) {
            this.message = message;
        }

        private String message() {
            return message;
        }
    }
}
