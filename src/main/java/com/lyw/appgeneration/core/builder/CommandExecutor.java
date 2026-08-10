package com.lyw.appgeneration.core.builder;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Vue 构建器与操作系统进程之间的最小边界。
 */
@FunctionalInterface
interface CommandExecutor {

    CommandResult execute(Path workingDirectory, List<String> command, Duration timeout)
            throws IOException, InterruptedException;
}
