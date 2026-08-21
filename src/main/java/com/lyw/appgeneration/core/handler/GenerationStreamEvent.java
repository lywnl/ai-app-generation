package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.ai.model.message.ContextCompressionMessage;
import com.lyw.appgeneration.ai.model.message.IncompleteToolChainRecoveryMessage;
import com.lyw.appgeneration.ai.model.message.TurnOutcomeMessage;
import com.lyw.appgeneration.ai.model.message.ToolProtocolRecoveryMessage;

import java.util.Objects;

/** 区分不可信正文与服务端受信控制事件，禁止通过字符串内容伪造控制帧。 */
public sealed interface GenerationStreamEvent {

    record Content(String text) implements GenerationStreamEvent {
        public Content {
            Objects.requireNonNull(text, "正文不能为空");
        }
    }

    record TurnOutcome(TurnOutcomeMessage message) implements GenerationStreamEvent {
        public TurnOutcome {
            Objects.requireNonNull(message, "Vue 终态控制消息不能为空");
        }
    }

    record ContextCompression(ContextCompressionMessage message)
            implements GenerationStreamEvent {
        public ContextCompression {
            Objects.requireNonNull(message, "上下文压缩控制消息不能为空");
        }
    }

    record ToolProtocolRecovery(ToolProtocolRecoveryMessage message)
            implements GenerationStreamEvent {
        public ToolProtocolRecovery {
            Objects.requireNonNull(message, "工具协议恢复控制消息不能为空");
        }
    }

    record IncompleteToolChainRecovery(
            IncompleteToolChainRecoveryMessage message)
            implements GenerationStreamEvent {
        public IncompleteToolChainRecovery {
            Objects.requireNonNull(message,
                    "未完成工具链恢复控制消息不能为空");
        }
    }

    static Content content(String text) {
        return new Content(text);
    }

    static TurnOutcome turnOutcome(VueTurnOutcome outcome) {
        return new TurnOutcome(new TurnOutcomeMessage(
                Objects.requireNonNull(outcome, "Vue 终态不能为空")));
    }

    static ContextCompression contextCompression(
            ContextCompressionMessage message) {
        return new ContextCompression(message);
    }

    static ToolProtocolRecovery toolProtocolRecovery(
            ToolProtocolRecoveryMessage message) {
        return new ToolProtocolRecovery(message);
    }

    static IncompleteToolChainRecovery incompleteToolChainRecovery(
            IncompleteToolChainRecoveryMessage message) {
        return new IncompleteToolChainRecovery(message);
    }
}
