package com.lyw.appgeneration.ai.plan;

import java.util.List;

/** 一次计划修订的可审计记录。 */
public record PlanChange(
        int version,
        String turnId,
        String reason,
        List<PlanFile> added,
        List<PlanFile> removed,
        List<PlanFile> modified) {

    public PlanChange {
        if (version < 1) {
            throw new IllegalArgumentException("计划版本必须为正数");
        }
        if (turnId == null || turnId.isBlank()) {
            throw new IllegalArgumentException("修订回合标识不能为空");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("修订原因不能为空");
        }
        added = List.copyOf(added == null ? List.of() : added);
        removed = List.copyOf(removed == null ? List.of() : removed);
        modified = List.copyOf(modified == null ? List.of() : modified);
    }
}
