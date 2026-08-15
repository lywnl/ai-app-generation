package com.lyw.appgeneration.config;

import cn.hutool.core.thread.ThreadFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** L1 摘要模型调用的独立有界执行器。 */
@Configuration
public class MemorySummaryModelExecutorConfig {

    private static final int CORE_POOL_SIZE = 2;
    private static final int MAXIMUM_POOL_SIZE = 4;
    private static final int QUEUE_CAPACITY = 20;

    @Bean(name = "memorySummaryModelExecutor", destroyMethod = "shutdown")
    public ExecutorService memorySummaryModelExecutor() {
        return new ThreadPoolExecutor(
                CORE_POOL_SIZE,
                MAXIMUM_POOL_SIZE,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                ThreadFactoryBuilder.create()
                        .setNamePrefix("Memory-Summary-Model-")
                        .build(),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
