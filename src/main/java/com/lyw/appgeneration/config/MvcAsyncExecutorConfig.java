package com.lyw.appgeneration.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 为 Spring MVC 异步请求提供受管虚拟线程，避免长 SSE 独占平台线程。 */
@Configuration(proxyBeanMethods = false)
public class MvcAsyncExecutorConfig {

    private static final long TERMINATION_TIMEOUT_MILLIS = 10_000L;

    @Bean(name = "mvcAsyncTaskExecutor", destroyMethod = "close")
    public SimpleAsyncTaskExecutor mvcAsyncTaskExecutor() {
        SimpleAsyncTaskExecutor executor =
                new SimpleAsyncTaskExecutor("mvc-async-");
        executor.setVirtualThreads(true);
        executor.setTaskTerminationTimeout(TERMINATION_TIMEOUT_MILLIS);
        return executor;
    }

    @Bean
    public WebMvcConfigurer mvcAsyncWebMvcConfigurer(
            @Qualifier("mvcAsyncTaskExecutor")
            AsyncTaskExecutor executor) {
        return new MvcAsyncWebMvcConfigurer(executor);
    }

    private record MvcAsyncWebMvcConfigurer(
            AsyncTaskExecutor executor) implements WebMvcConfigurer {

        @Override
        public void configureAsyncSupport(
                AsyncSupportConfigurer configurer) {
            configurer.setTaskExecutor(executor);
        }
    }
}
