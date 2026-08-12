package com.lyw.appgeneration.core.builder;

/**
 * 单条系统命令的有界结果。
 */
record CommandResult(Integer exitCode, boolean timedOut, boolean cancelled, String outputTail) {

    CommandResult {
        if (timedOut && cancelled) {
            throw new IllegalArgumentException("命令不能同时超时和取消");
        }
        if ((timedOut || cancelled) && exitCode != null) {
            throw new IllegalArgumentException("超时或取消命令不能包含退出码");
        }
        if (!timedOut && !cancelled && exitCode == null) {
            throw new IllegalArgumentException("正常结束命令必须包含退出码");
        }
        outputTail = BuildResult.boundedTail(outputTail);
    }

    CommandResult(Integer exitCode, boolean timedOut, String outputTail) {
        this(exitCode, timedOut, false, outputTail);
    }
}
