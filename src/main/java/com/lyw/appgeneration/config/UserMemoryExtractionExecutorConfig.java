package com.lyw.appgeneration.config;

import cn.hutool.core.thread.ThreadFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** L2 偏好抽取专用有界工作池，避免阻塞 L1 后台摘要。 */
@Configuration
public class UserMemoryExtractionExecutorConfig {

    private static final int POOL_SIZE = 2;
    private static final int QUEUE_CAPACITY = 100;

    @Bean(name = "userMemoryExtractionExecutor", destroyMethod = "shutdown")
    public ExecutorService userMemoryExtractionExecutor() {
        return new ThreadPoolExecutor(
                POOL_SIZE,
                POOL_SIZE,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                ThreadFactoryBuilder.create()
                        .setNamePrefix("User-Memory-Extraction-")
                        .build(),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
