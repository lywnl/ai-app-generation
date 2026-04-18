package com.lyw.appgeneration.ai.image;

import cn.hutool.core.thread.ExecutorBuilder;
import cn.hutool.core.thread.ThreadFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 图片并发收集共享线程池配置
 */
@Configuration
public class ImageCollectionExecutorConfig {

    @Bean(name = "imageCollectionExecutor", destroyMethod = "shutdown")
    public ExecutorService imageCollectionExecutor() {
        return ExecutorBuilder.create()
                .setCorePoolSize(10)
                .setMaxPoolSize(20)
                .setWorkQueue(new LinkedBlockingQueue<>(100))
                .setThreadFactory(ThreadFactoryBuilder.create()
                        .setNamePrefix("Image-Collect-")
                        .build())
                .build();
    }
}
