package com.lyw.appgeneration.config;

import cn.hutool.core.thread.ThreadFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 隔离 28K 后台计划读取，避免慢数据库占满当前请求读取池。 */
@Configuration
public class ContextAsyncCompressionPlanningExecutorConfig {

    private static final int POOL_SIZE = 2;
    private static final int QUEUE_CAPACITY = 32;

    @Bean(name = "contextAsyncCompressionPlanningExecutor",
            destroyMethod = "shutdown")
    public ExecutorService contextAsyncCompressionPlanningExecutor() {
        return new ThreadPoolExecutor(
                POOL_SIZE,
                POOL_SIZE,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                ThreadFactoryBuilder.create()
                        .setNamePrefix("Context-Async-Planning-")
                        .build(),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
