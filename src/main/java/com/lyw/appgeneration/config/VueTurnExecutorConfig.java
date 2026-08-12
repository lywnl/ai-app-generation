package com.lyw.appgeneration.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

/** Vue 取消后台收尾的受管虚拟线程执行器。 */
@Configuration
public class VueTurnExecutorConfig {

    public static final int MAX_CONCURRENCY = 64;
    private static final long TERMINATION_TIMEOUT_MILLIS = 10_000L;

    @Bean(name = "vueTurnCancellationExecutor", destroyMethod = "close")
    public SimpleAsyncTaskExecutor vueTurnCancellationExecutor() {
        SimpleAsyncTaskExecutor executor =
                new SimpleAsyncTaskExecutor("vue-turn-cancel-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(MAX_CONCURRENCY);
        executor.setRejectTasksWhenLimitReached(true);
        executor.setTaskTerminationTimeout(TERMINATION_TIMEOUT_MILLIS);
        return executor;
    }
}
