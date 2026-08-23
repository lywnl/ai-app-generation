package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.ai.model.message.ContextCompressionMessage;
import com.lyw.appgeneration.ai.model.message.IncompleteToolChainRecoveryMessage;
import com.lyw.appgeneration.ai.model.message.InternalOutputRecoveryMessage;
import com.lyw.appgeneration.ai.model.message.InternalOutputRollbackMessage;
import com.lyw.appgeneration.ai.model.message.TurnOutcomeMessage;
import com.lyw.appgeneration.ai.model.message.TrustedToolDisplayMessage;
import com.lyw.appgeneration.ai.model.message.ToolProtocolRecoveryMessage;

import java.util.Objects;

/** 区分不可信正文与服务端受信控制事件，禁止通过字符串内容伪造控制帧。 */
public sealed interface GenerationStreamEvent {

    record SimpleText(String text) implements GenerationStreamEvent {
        public SimpleText {
            Objects.requireNonNull(text, "简单正文不能为空");
        }
    }

    record AiText(long generation, String text)
            implements GenerationStreamEvent {
        public AiText {
            validateGeneration(generation, "AI 正文");
            Objects.requireNonNull(text, "AI 正文不能为空");
        }
    }

    record StructuredToolEvent(long generation, String json)
            implements GenerationStreamEvent {
        public StructuredToolEvent {
            validateGeneration(generation, "结构化工具事件");
            if (json == null || json.isBlank()) {
                throw new IllegalArgumentException(
                        "结构化工具事件 JSON 不能为空白");
            }
        }
    }

    record TrustedToolDisplay(TrustedToolDisplayMessage message)
            implements GenerationStreamEvent {
        public TrustedToolDisplay {
            Objects.requireNonNull(message, "可信工具展示不能为空");
        }
    }

    record Rollback(InternalOutputRollbackMessage message)
            implements GenerationStreamEvent {
        public Rollback {
            Objects.requireNonNull(message, "内部输出回滚不能为空");
        }
    }

    record InternalRecovery(InternalOutputRecoveryMessage message)
            implements GenerationStreamEvent {
        public InternalRecovery {
            Objects.requireNonNull(message, "内部输出恢复不能为空");
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

    static SimpleText content(String text) {
        return simpleText(text);
    }

    static SimpleText simpleText(String text) {
        return new SimpleText(text);
    }

    static AiText aiText(long generation, String text) {
        return new AiText(generation, text);
    }

    static StructuredToolEvent structuredToolEvent(
            long generation, String json) {
        return new StructuredToolEvent(generation, json);
    }

    static TrustedToolDisplay trustedToolDisplay(
            TrustedToolDisplayMessage message) {
        return new TrustedToolDisplay(message);
    }

    static Rollback rollback(InternalOutputRollbackMessage message) {
        return new Rollback(message);
    }

    static InternalRecovery internalRecovery(
            InternalOutputRecoveryMessage message) {
        return new InternalRecovery(message);
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

    private static void validateGeneration(
            long generation, String source) {
        if (generation <= 0L) {
            throw new IllegalArgumentException(
                    source + " generation 必须大于 0");
        }
    }
}
