package dev.langchain4j.service;

import java.util.Objects;

/** 保留模型请求门禁拒绝发生的阶段和类型，供上层选择安全终态协议。 */
public final class ModelRequestGateException extends RuntimeException {

    private final Stage stage;
    private final ModelRequestGate.Status status;

    public ModelRequestGateException(
            Stage stage,
            ModelRequestGate.Status status,
            String safeMessage) {
        super(requireSafeMessage(safeMessage));
        this.stage = Objects.requireNonNull(stage, "门禁拒绝阶段不能为空");
        this.status = requireRejectedStatus(status);
    }

    public Stage stage() {
        return stage;
    }

    public ModelRequestGate.Status status() {
        return status;
    }

    private static String requireSafeMessage(String safeMessage) {
        if (safeMessage == null || safeMessage.isBlank()) {
            throw new IllegalArgumentException("门禁拒绝安全文案不能为空");
        }
        return safeMessage;
    }

    private static ModelRequestGate.Status requireRejectedStatus(
            ModelRequestGate.Status status) {
        Objects.requireNonNull(status, "门禁拒绝状态不能为空");
        if (status != ModelRequestGate.Status.COMPRESSION_FAILED
                && status != ModelRequestGate.Status.HARD_LIMIT_REJECTED) {
            throw new IllegalArgumentException("门禁异常只能表达拒绝状态");
        }
        return status;
    }

    public enum Stage {
        INITIAL,
        CONTINUATION
    }
}
