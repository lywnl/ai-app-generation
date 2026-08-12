package com.lyw.appgeneration.core.handler;

import java.util.Objects;

/** 区分不可信正文与服务端生成的 Vue 终态，禁止通过字符串内容伪造控制帧。 */
public sealed interface GenerationStreamEvent {

    record Content(String text) implements GenerationStreamEvent {
        public Content {
            Objects.requireNonNull(text, "正文不能为空");
        }
    }

    record VueOutcome(VueTurnOutcome outcome) implements GenerationStreamEvent {
        public VueOutcome {
            Objects.requireNonNull(outcome, "Vue 终态不能为空");
        }
    }

    static Content content(String text) {
        return new Content(text);
    }

    static VueOutcome vueOutcome(VueTurnOutcome outcome) {
        return new VueOutcome(outcome);
    }
}
