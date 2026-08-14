package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.ai.model.message.TurnOutcomeMessage;

import java.util.Objects;

/** 区分不可信正文与服务端生成的 Vue 终态，禁止通过字符串内容伪造控制帧。 */
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

    static Content content(String text) {
        return new Content(text);
    }

    static TurnOutcome turnOutcome(VueTurnOutcome outcome) {
        return new TurnOutcome(new TurnOutcomeMessage(
                Objects.requireNonNull(outcome, "Vue 终态不能为空")));
    }
}
