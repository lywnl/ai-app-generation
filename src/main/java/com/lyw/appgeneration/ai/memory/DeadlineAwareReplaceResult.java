package com.lyw.appgeneration.ai.memory;

/** L0 截止感知比较替换的类型化结果。 */
public enum DeadlineAwareReplaceResult {
    REPLACED,
    PREFIX_CHANGED,
    TIMED_OUT,
    INTERRUPTED,
    DEPENDENCY_FAILED
}
