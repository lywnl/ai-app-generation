package com.lyw.appgeneration.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ThreadPoolExecutor;

/** L2 本地调度恢复与数据库对账共用的唯一全局周期调度器。 */
@Configuration
public class UserMemoryRecoverySchedulerConfig {

    @Bean(name = "userMemoryRecoveryScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler userMemoryRecoveryScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("User-Memory-Recovery-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setRejectedExecutionHandler(
                new ThreadPoolExecutor.AbortPolicy());
        return scheduler;
    }
}
