package com.lyw.appgeneration.core.builder;

/**
 * 单条系统命令的有界结果。
 */
record CommandResult(Integer exitCode, boolean timedOut, String outputTail) {

    CommandResult {
        outputTail = BuildResult.boundedTail(outputTail);
    }
}
