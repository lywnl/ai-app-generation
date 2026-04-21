package com.lyw.appgeneration.ai.guardrail.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Prompt 安全前置校验注解
 * <p>
 * 标注在方法上后，由 {@code PromptSafetyAspect} 在方法执行前拦截；
 * 切面按 {@link #argName()} 从方法参数中定位待校验的 String 值，命中规则则抛 BusinessException。
 *
 * @author lyw
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PromptSafetyCheck {

    /**
     * 要校验的参数名，默认 {@code "message"}
     */
    String argName() default "message";
}
