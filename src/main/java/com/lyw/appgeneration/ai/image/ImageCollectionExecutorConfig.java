package com.lyw.appgeneration.ai.image;

import cn.hutool.core.thread.ExecutorBuilder;
import cn.hutool.core.thread.ThreadFactoryBuilder;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 图片并发收集共享线程池配置
 */
@Configuration
@ConfigurationProperties(prefix = "image.collection")
@Data
public class ImageCollectionExecutorConfig {

    private int workerThreads = 15;
    private int queueCapacity = 100;

    @Bean(name = "imageCollectionExecutor", destroyMethod = "shutdown")
    public ExecutorService imageCollectionExecutor() {
        if (workerThreads <= 0 || queueCapacity <= 0) {
            throw new IllegalArgumentException("图片工作线程数和队列容量必须大于 0");
        }
        return ExecutorBuilder.create()
                .setCorePoolSize(workerThreads)
                .setMaxPoolSize(workerThreads)
                .setWorkQueue(new LinkedBlockingQueue<>(queueCapacity))
                .setThreadFactory(ThreadFactoryBuilder.create()
                        .setNamePrefix("Image-Collect-")
                        .build())
                .build();
    }
}
