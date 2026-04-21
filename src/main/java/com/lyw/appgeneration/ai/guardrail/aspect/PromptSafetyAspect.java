package com.lyw.appgeneration.ai.guardrail.aspect;

import com.lyw.appgeneration.ai.guardrail.PromptSafetyValidator;
import com.lyw.appgeneration.ai.guardrail.annotation.PromptSafetyCheck;
import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.exception.ErrorCode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

/**
 * Prompt 安全校验切面：拦截 {@link PromptSafetyCheck} 标注的方法，在执行前做输入安全扫描
 *
 * @author lyw
 */
@Aspect
@Component
@Slf4j
public class PromptSafetyAspect {

    @Resource
    private PromptSafetyValidator promptSafetyValidator;

    @Before("@annotation(promptSafetyCheck)")
    public void doBefore(JoinPoint joinPoint, PromptSafetyCheck promptSafetyCheck) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] paramNames = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();
        String targetName = promptSafetyCheck.argName();

        for (int i = 0; i < paramNames.length; i++) {
            if (targetName.equals(paramNames[i])) {
                Object val = args[i];
                if (!(val instanceof String s)) {
                    throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                            "@PromptSafetyCheck 目标参数 " + targetName + " 必须是 String");
                }
                promptSafetyValidator.validate(s);
                return;
            }
        }
        // 找不到参数名 = 注解配错，立即暴露，避免静默失效
        throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                "@PromptSafetyCheck 找不到参数 " + targetName);
    }
}
