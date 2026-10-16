package com.lyw.appgeneration.config;

import com.lyw.appgeneration.core.concurrency.VueTurnAdmissionController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Vue 首轮上下文准备：RAG 入口与图片收集在不同线程执行。 */
@Configuration
public class VueContextPreparationExecutorConfig {

    @Bean(name = "vueContextPreparationExecutor", destroyMethod = "close")
    public ThreadPoolExecutor vueContextPreparationExecutor(RagProperties properties) {
        properties.getExecution().validate();
        int workerThreads = Math.min(properties.getExecution().getMaxConcurrentRequests(),
                VueTurnAdmissionController.MAX_ACTIVE_TURNS);
        // 每个已准入回合最多提交一个任务；队列足以容纳所有已准入回合。
        // 此处只运行 RAG 的阻塞入口，不使用图片池、RAG 外部调用池或取消收尾池。
        return new ThreadPoolExecutor(workerThreads, workerThreads,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(VueTurnAdmissionController.MAX_ACTIVE_TURNS),
                Thread.ofPlatform().name("vue-context-rag-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
