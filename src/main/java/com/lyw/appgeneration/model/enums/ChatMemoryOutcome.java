package com.lyw.appgeneration.model.enums;

/**
 * AI 回合记忆投影的可信结果类型。
 */
public enum ChatMemoryOutcome {
    ANSWERED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    SYSTEM_ERROR,
    PROTOCOL_ERROR,
    INCOMPLETE_TOOL_CHAIN,
    LEGACY_IMPORTED,
    LEGACY_UNVERIFIED
}
