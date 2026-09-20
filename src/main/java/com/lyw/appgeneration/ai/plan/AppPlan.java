package com.lyw.appgeneration.ai.plan;

import java.util.List;
import java.util.Objects;

/** Vue 在线生成回合使用的不可变计划快照。 */
public record AppPlan(
        String planId,
        String lastModifiedTurnId,
        String activeTurnId,
        int version,
        int round,
        PlanMode mode,
        String summary,
        List<PlanFile> files,
        List<PlanChange> history,
        PlanStatus status) {

    public AppPlan {
        requireText(planId, "计划标识");
        requireText(lastModifiedTurnId, "最后修改回合标识");
        requireText(activeTurnId, "活动回合标识");
        if (version < 1) {
            throw new IllegalArgumentException("计划版本必须为正数");
        }
        if (round < 1) {
            throw new IllegalArgumentException("计划轮次必须为正数");
        }
        mode = Objects.requireNonNull(mode, "计划模式不能为空");
        requireText(summary, "计划目标");
        files = List.copyOf(files == null ? List.of() : files);
        history = List.copyOf(history == null ? List.of() : history);
        status = Objects.requireNonNull(status, "计划状态不能为空");
    }

    public AppPlan rebindActiveTurn(String turnId) {
        requireText(turnId, "活动回合标识");
        return new AppPlan(
                planId, lastModifiedTurnId, turnId, version, round, mode,
                summary, files, history, status);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }
}
