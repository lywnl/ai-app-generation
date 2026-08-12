package com.lyw.appgeneration.core.builder;

import java.util.Objects;

/** 在线 Vue 构建的可信日志与取消上下文。 */
public record BuildExecutionContext(
        long appId,
        String turnId,
        int attempt,
        BuildCancellationSignal cancellation,
        BuildLogSink logSink
) {

    public BuildExecutionContext {
        if (appId < 0) {
            throw new IllegalArgumentException("appId 不能为负数");
        }
        Objects.requireNonNull(turnId, "turnId 不能为空");
        if (turnId.isBlank()) {
            throw new IllegalArgumentException("turnId 不能为空白");
        }
        if (attempt <= 0) {
            throw new IllegalArgumentException("attempt 必须大于 0");
        }
        Objects.requireNonNull(cancellation, "cancellation 不能为空");
        Objects.requireNonNull(logSink, "logSink 不能为空");
    }
}
