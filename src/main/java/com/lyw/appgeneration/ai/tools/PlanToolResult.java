package com.lyw.appgeneration.ai.tools;

import com.lyw.appgeneration.ai.plan.PlanFile;

import java.util.List;
import java.util.Objects;

/** makePlan/updatePlan 返回给模型的独立受信协议。 */
public record PlanToolResult(
        String protocol,
        String operation,
        Status status,
        String planId,
        Integer version,
        String message,
        String summary,
        List<PlanFile> files) {

    public static final String PROTOCOL = "plan-tool/v1";

    public PlanToolResult {
        if (!PROTOCOL.equals(protocol)
                || !("makePlan".equals(operation) || "updatePlan".equals(operation))) {
            throw new IllegalArgumentException("计划工具协议版本或操作不受支持");
        }
        Objects.requireNonNull(status, "计划工具状态不能为空");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("计划工具状态说明不能为空");
        }
        if (status == Status.APPLIED) {
            if (planId == null || planId.isBlank() || version == null || version < 1
                    || summary == null || summary.isBlank()) {
                throw new IllegalArgumentException("成功计划结果缺少计划字段");
            }
        }
        if (version != null && version < 1) {
            throw new IllegalArgumentException("计划版本必须为正数");
        }
        files = List.copyOf(files == null ? List.of() : files);
    }

    public static PlanToolResult applied(
            String operation, String planId, int version,
            String summary, List<PlanFile> files) {
        return applied(operation, planId, version, summary, files, "计划已保存");
    }

    public static PlanToolResult applied(
            String operation, String planId, int version,
            String summary, List<PlanFile> files, String message) {
        return new PlanToolResult(PROTOCOL, operation, Status.APPLIED,
                planId, version, message, summary, files);
    }

    public static PlanToolResult rejected(String operation, String message) {
        return new PlanToolResult(PROTOCOL, operation, Status.REJECTED,
                null, null, message, null, List.of());
    }

    public static PlanToolResult conflict(String operation, String message) {
        return new PlanToolResult(PROTOCOL, operation, Status.CONFLICT,
                null, null, message, null, List.of());
    }

    public static PlanToolResult failed(String operation, String message) {
        return new PlanToolResult(PROTOCOL, operation, Status.FAILED,
                null, null, message, null, List.of());
    }

    public enum Status {
        APPLIED,
        REJECTED,
        CONFLICT,
        FAILED
    }
}
