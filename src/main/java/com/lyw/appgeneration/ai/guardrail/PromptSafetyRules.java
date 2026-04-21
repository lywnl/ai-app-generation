package com.lyw.appgeneration.ai.guardrail;

import cn.hutool.core.util.StrUtil;
import com.lyw.appgeneration.exception.ErrorCode;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Prompt 安全规则：项目唯一的敏感词/注入模式定义源
 * <p>
 * 被两条防线共享读取，避免规则漂移：
 * <ul>
 *   <li>{@link PromptSafetyValidator}（主防线，应用层 AOP 注解驱动）</li>
 *   <li>{@link PromptSafetyInputGuardrail}（深度防御，LangChain4j 代理层兜底）</li>
 * </ul>
 *
 * @author lyw
 */
public final class PromptSafetyRules {

    public static final List<String> SENSITIVE_WORDS = List.of(
            "忽略之前的指令", "ignore previous instructions", "ignore above",
            "破解", "hack", "绕过", "bypass", "越狱", "jailbreak"
    );

    public static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("(?i)ignore\\s+(?:previous|above|all)\\s+(?:instructions?|commands?|prompts?)"),
            Pattern.compile("(?i)(?:forget|disregard)\\s+(?:everything|all)\\s+(?:above|before)"),
            Pattern.compile("(?i)(?:pretend|act|behave)\\s+(?:as|like)\\s+(?:if|you\\s+are)"),
            Pattern.compile("(?i)system\\s*:\\s*you\\s+are"),
            Pattern.compile("(?i)new\\s+(?:instructions?|commands?|prompts?)\\s*:")
    );

    private PromptSafetyRules() {
    }

    /**
     * 校验用户输入是否违规
     *
     * @param input 原始用户输入
     * @return {@code null} 表示通过；非空表示违规（含错误码与提示）
     */
    public static Violation check(String input) {
        if (StrUtil.isBlank(input)) {
            return new Violation(ErrorCode.PARAMS_ERROR, "输入内容不能为空");
        }
        String lower = input.toLowerCase();
        for (String word : SENSITIVE_WORDS) {
            if (lower.contains(word.toLowerCase())) {
                return new Violation(ErrorCode.FORBIDDEN_ERROR, "输入包含不当内容，请修改后重试");
            }
        }
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(input).find()) {
                return new Violation(ErrorCode.FORBIDDEN_ERROR, "检测到恶意输入，请求被拒绝");
            }
        }
        return null;
    }

    public record Violation(ErrorCode code, String message) {
    }
}
