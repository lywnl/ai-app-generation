package com.lyw.appgeneration.ai.memory;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 在在线 AI 代理同步构造首条用户消息期间传递数据库原文身份。
 *
 * <p>该身份不接收 HTTP 字段，也不从增强后的 Prompt 反向解析。作用域结束后立即
 * 清理，避免线程复用时把上一回合身份带入下一回合。</p>
 */
public final class CanonicalUserMessageScope {

    private static final ThreadLocal<String> CANONICAL_TEXT =
            new ThreadLocal<>();

    private CanonicalUserMessageScope() {
    }

    public static <T> T call(
            String canonicalText, Supplier<T> action) {
        Objects.requireNonNull(canonicalText, "用户原文不能为空");
        Objects.requireNonNull(action, "作用域动作不能为空");
        String previous = CANONICAL_TEXT.get();
        CANONICAL_TEXT.set(canonicalText);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CANONICAL_TEXT.remove();
            } else {
                CANONICAL_TEXT.set(previous);
            }
        }
    }

    static String currentCanonicalText() {
        return CANONICAL_TEXT.get();
    }
}
