package com.lyw.appgeneration.config;

import cn.hutool.core.thread.ThreadFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 30K 以上上下文门禁使用的独立有界压缩执行器。 */
@Configuration
public class ContextCompressionExecutorConfig {

    private static final int CORE_POOL_SIZE = 2;
    private static final int MAXIMUM_POOL_SIZE = 4;
    private static final int QUEUE_CAPACITY = 32;

    @Bean(name = "contextCompressionExecutor", destroyMethod = "shutdown")
    public ExecutorService contextCompressionExecutor() {
        return new ThreadPoolExecutor(
                CORE_POOL_SIZE,
                MAXIMUM_POOL_SIZE,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                ThreadFactoryBuilder.create()
                        .setNamePrefix("Context-Compression-")
                        .build(),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
