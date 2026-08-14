package com.lyw.appgeneration.core.builder;

/** Vue 构建回合阶段。 */
public enum VueBuildPhase {
    GENERATING,
    REPAIRING,
    RETRYING,
    FINAL_DIAGNOSIS,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
