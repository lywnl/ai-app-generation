package com.lyw.appgeneration.config;

import cn.hutool.core.thread.ExecutorBuilder;
import cn.hutool.core.thread.ThreadFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 后台记忆摘要线程池配置。
 *
 * <p>L1 滚动摘要为 best-effort:队列满时直接丢弃本次提炼(下一轮对话结束钩子会再触发),
 * 绝不阻塞或拖慢主对话流。对齐 {@code ImageCollectionExecutorConfig} 的写法。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
@Configuration
public class MemorySummarizationExecutorConfig {

    @Bean(name = "memorySummarizationExecutor", destroyMethod = "shutdown")
    public ExecutorService memorySummarizationExecutor() {
        return ExecutorBuilder.create()
                .setCorePoolSize(5)
                .setMaxPoolSize(10)
                .setWorkQueue(new LinkedBlockingQueue<>(200))
                .setThreadFactory(ThreadFactoryBuilder.create()
                        .setNamePrefix("Memory-Sum-")
                        .build())
                // 队列满直接丢弃:摘要是 best-effort,下轮会再触发
                .setHandler(new ThreadPoolExecutor.DiscardPolicy())
                .build();
    }
}
