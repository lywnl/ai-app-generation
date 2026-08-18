package com.lyw.appgeneration.model.enums;

/**
 * AI 回合记忆投影的可信结果类型。
 */
public enum ChatMemoryOutcome {
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    SYSTEM_ERROR,
    PROTOCOL_ERROR,
    LEGACY_IMPORTED,
    LEGACY_UNVERIFIED
}
