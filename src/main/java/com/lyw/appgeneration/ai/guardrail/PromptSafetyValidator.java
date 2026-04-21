package com.lyw.appgeneration.ai.guardrail;

import com.lyw.appgeneration.exception.BusinessException;
import org.springframework.stereotype.Component;

/**
 * Prompt 安全校验器：把 {@link PromptSafetyRules#check(String)} 的返回值翻译成 {@link BusinessException}
 * <p>
 * 供 {@code PromptSafetyAspect} 调用；也可在任何非 AOP 场景下手工调用。
 *
 * @author lyw
 */
@Component
public class PromptSafetyValidator {

    public void validate(String userPrompt) {
        PromptSafetyRules.Violation violation = PromptSafetyRules.check(userPrompt);
        if (violation != null) {
            throw new BusinessException(violation.code().getCode(), violation.message());
        }
    }
}
