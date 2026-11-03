package com.lyw.appgeneration.config;

import com.lyw.appgeneration.core.concurrency.VueTurnAdmissionController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Vue 回合资料准备：RAG 协调和图片规划并行，外部子调用使用各自受管池。 */
@Configuration
public class VueContextPreparationExecutorConfig {

    @Bean(name = "vueContextPreparationExecutor", destroyMethod = "close")
    public ThreadPoolExecutor vueContextPreparationExecutor(RagProperties properties) {
        properties.getExecution().validate();
        int workerThreads = Math.min(properties.getExecution().getMaxConcurrentRequests(),
                VueTurnAdmissionController.MAX_ACTIVE_TURNS);
        // 每轮最多两个准备分支，保持原有线程和队列上限；饱和由本轮资料策略有界降级。
        // 图片规划只提交子任务即返回，不能在本池等待同池任务；主协调者在调用线程等待。
        return new ThreadPoolExecutor(workerThreads, workerThreads,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(VueTurnAdmissionController.MAX_ACTIVE_TURNS),
                Thread.ofPlatform().name("vue-context-rag-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
