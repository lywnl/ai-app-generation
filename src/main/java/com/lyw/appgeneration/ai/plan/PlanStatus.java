package com.lyw.appgeneration.ai.plan;

/** 计划级执行状态，不代表单个文件已通过构建验证。 */
public enum PlanStatus {
    PLANNED,
    READY_TO_BUILD,
    BUILT,
    REPLAN_PENDING
}
