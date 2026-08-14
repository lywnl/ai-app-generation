package com.lyw.appgeneration.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

/** Vue 取消后台收尾的受管虚拟线程执行器。 */
@Configuration
public class VueTurnExecutorConfig {

    private static final long TERMINATION_TIMEOUT_MILLIS = 10_000L;

    @Bean(name = "vueTurnCancellationExecutor", destroyMethod = "close")
    public SimpleAsyncTaskExecutor vueTurnCancellationExecutor() {
        SimpleAsyncTaskExecutor executor =
                new SimpleAsyncTaskExecutor("vue-turn-cancel-");
        executor.setVirtualThreads(true);
        executor.setTaskTerminationTimeout(TERMINATION_TIMEOUT_MILLIS);
        return executor;
    }
}
