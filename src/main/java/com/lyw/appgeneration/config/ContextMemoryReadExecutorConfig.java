package com.lyw.appgeneration.config;

import cn.hutool.core.thread.ThreadFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 为上下文门禁的数据库、缓存和记忆只读操作提供独立有界执行器。 */
@Configuration
public class ContextMemoryReadExecutorConfig {

    private static final int POOL_SIZE = 4;
    private static final int QUEUE_CAPACITY = 32;

    @Bean(name = "contextMemoryReadExecutor", destroyMethod = "shutdown")
    public ExecutorService contextMemoryReadExecutor() {
        return new ThreadPoolExecutor(
                POOL_SIZE,
                POOL_SIZE,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                ThreadFactoryBuilder.create()
                        .setNamePrefix("Context-Memory-Read-")
                        .build(),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
