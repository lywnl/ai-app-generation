package com.lyw.appgeneration.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ThreadPoolExecutor;

/** L2 用户偏好防抖调度器配置。 */
@Configuration
public class UserMemoryDebounceExecutorConfig {

    @Bean(name = "userMemoryDebounceScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler userMemoryDebounceScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("User-Memory-Debounce-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setRejectedExecutionHandler(
                new ThreadPoolExecutor.AbortPolicy());
        return scheduler;
    }
}
