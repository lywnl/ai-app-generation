package com.lyw.appgeneration.exception;

import java.util.Objects;

/** 表达正文或工具语义事件开始前可通过 business-error 安全编码的失败。 */
public final class GenerationPreflightException extends RuntimeException {

    private static final String SYSTEM_SAFE_MESSAGE =
            "生成服务暂时不可用，请稍后重试。";

    private final Kind kind;
    private final int code;
    private final String safeMessage;

    public static GenerationPreflightException business(
            int code, String safeMessage, Throwable cause) {
        return new GenerationPreflightException(
                Kind.BUSINESS, code, safeMessage, cause);
    }

    public static GenerationPreflightException system(Throwable cause) {
        return new GenerationPreflightException(
                Kind.SYSTEM, ErrorCode.SYSTEM_ERROR.getCode(),
                SYSTEM_SAFE_MESSAGE, cause);
    }

    public static GenerationPreflightException system(
            String safeMessage, Throwable cause) {
        return new GenerationPreflightException(
                Kind.SYSTEM, ErrorCode.SYSTEM_ERROR.getCode(),
                safeMessage, cause);
    }

    private GenerationPreflightException(
            Kind kind, int code, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.kind = Objects.requireNonNull(kind, "前置异常类型不能为空");
        if (code <= 0) {
            throw new IllegalArgumentException("前置异常错误码必须大于 0");
        }
        if (safeMessage == null || safeMessage.isBlank()) {
            throw new IllegalArgumentException("前置异常安全文案不能为空");
        }
        this.code = code;
        this.safeMessage = safeMessage;
    }

    public Kind kind() {
        return kind;
    }

    public int code() {
        return code;
    }

    public String safeMessage() {
        return safeMessage;
    }

    public enum Kind {
        BUSINESS,
        SYSTEM
    }
}
