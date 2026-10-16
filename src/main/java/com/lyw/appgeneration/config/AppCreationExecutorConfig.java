package com.lyw.appgeneration.config;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

import java.util.Map;

/** 创建接口专用的命名执行器，与图片、RAG 和生成回合的执行资源隔离。 */
@Configuration
public class AppCreationExecutorConfig {

    @Bean(name = "appCreationNamingExecutor", destroyMethod = "close")
    @DependsOn("appNameService")
    public SimpleAsyncTaskExecutor appCreationNamingExecutor(
            @Value("${app.creation.naming-concurrency:4}") int concurrency) {
        if (concurrency <= 0) {
            throw new IllegalArgumentException("创建应用的命名并发上限必须大于 0");
        }
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("app-create-name-");
        executor.setVirtualThreads(true);
        // 超过上限时提交方等待资源，不使用无限队列或额外的调用方执行策略。
        executor.setConcurrencyLimit(concurrency);
        executor.setTaskTerminationTimeout(10_000L);
        executor.setTaskDecorator(task -> {
            Map<String, String> context = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                try {
                    restoreContext(context);
                    task.run();
                } finally {
                    restoreContext(previous);
                }
            };
        });
        return executor;
    }

    private static void restoreContext(Map<String, String> context) {
        if (context == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }
}
