package com.lyw.appgeneration.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 模型请求门禁等待同步协调器时使用的独立受管虚拟线程执行器。 */
@Configuration
public class ModelRequestGateExecutorConfig {

    @Bean(name = "modelRequestGateExecutor", destroyMethod = "shutdown")
    public ExecutorService modelRequestGateExecutor() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual()
                        .name("Model-Request-Gate-", 0)
                        .factory());
    }
}
