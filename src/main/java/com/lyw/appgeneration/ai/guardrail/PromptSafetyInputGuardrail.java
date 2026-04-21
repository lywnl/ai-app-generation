package com.lyw.appgeneration.ai.guardrail;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailResult;

/**
 * LangChain4j 代理层的输入护轨，作为深度防御的第二层
 * <p>
 * 规则源来自 {@link PromptSafetyRules}——与应用层 AOP 主防线共享同一份规则定义，
 * 永远不会两边漂移。
 *
 * @author lyw
 */
public class PromptSafetyInputGuardrail implements InputGuardrail {

    @Override
    public InputGuardrailResult validate(UserMessage userMessage) {
        PromptSafetyRules.Violation violation = PromptSafetyRules.check(userMessage.singleText());
        return violation != null ? fatal(violation.message()) : success();
    }
}
