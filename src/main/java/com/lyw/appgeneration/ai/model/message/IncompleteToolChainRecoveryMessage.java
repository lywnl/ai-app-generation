package com.lyw.appgeneration.ai.model.message;

import java.util.Objects;

/** 只承载客户端可见固定文案的未完成工具链续行控制消息。 */
public record IncompleteToolChainRecoveryMessage(
        String protocol, Phase phase, String message) {

    public static final String PROTOCOL =
            "incomplete-tool-chain-recovery/v1";
    private static final String STARTED_MESSAGE =
            "正在继续未完成的构建流程，请稍候…";
    private static final String RECOVERED_MESSAGE =
            "未完成的构建流程已恢复，继续生成…";
    private static final String FAILED_MESSAGE =
            "模型未能继续完成真实工具执行和构建，本轮已安全停止。";

    public IncompleteToolChainRecoveryMessage {
        if (!PROTOCOL.equals(protocol)) {
            throw new IllegalArgumentException("未完成工具链恢复版本不受信");
        }
        Objects.requireNonNull(phase, "未完成工具链恢复阶段不能为空");
        if (!phase.message().equals(message)) {
            throw new IllegalArgumentException("未完成工具链恢复文案必须使用固定安全文案");
        }
    }

    public static IncompleteToolChainRecoveryMessage started() {
        return new IncompleteToolChainRecoveryMessage(
                PROTOCOL, Phase.STARTED, STARTED_MESSAGE);
    }

    public static IncompleteToolChainRecoveryMessage recovered() {
        return new IncompleteToolChainRecoveryMessage(
                PROTOCOL, Phase.RECOVERED, RECOVERED_MESSAGE);
    }

    public static IncompleteToolChainRecoveryMessage failed() {
        return new IncompleteToolChainRecoveryMessage(
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
