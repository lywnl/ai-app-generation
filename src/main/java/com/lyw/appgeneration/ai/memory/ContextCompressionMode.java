package com.lyw.appgeneration.ai.memory;

/** 上下文门禁对当前模型调用作出的状态决策。 */
public enum ContextCompressionMode {
    NORMAL,
    ASYNC_SCHEDULED,
    BLOCKING_STARTED,
    BLOCKING_COMPLETED,
    TOOL_CHAIN_CHECKPOINT_COMPLETED,
    BLOCKING_FAILED,
    HARD_LIMIT_REJECTED,
    ADMISSION_FAILED
}
