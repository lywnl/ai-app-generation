package com.lyw.appgeneration.config;

import com.lyw.appgeneration.monitor.AiModelMonitorListener;
import com.lyw.appgeneration.service.impl.UserMemoryServiceImpl;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserMemoryInfrastructureIsolationTest {

    @Test
    @DisplayName("L2 使用专用无重试模型和独立有界工作池")
    void l2UsesDedicatedModelAndExecutor() {
        Constructor<?> productionConstructor = Arrays.stream(
                        UserMemoryServiceImpl.class.getConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(
                        Autowired.class))
                .findFirst()
                .orElseThrow();

        Qualifier modelQualifier = productionConstructor.getParameters()[4]
                .getAnnotation(Qualifier.class);
        Qualifier executorQualifier = productionConstructor.getParameters()[5]
                .getAnnotation(Qualifier.class);

        assertNotNull(modelQualifier, "L2 模型必须使用显式 Qualifier");
        assertEquals("userMemoryExtractionChatModel",
                modelQualifier.value(),
                "L2 不得复用带自动重试的主模型");
        assertNotNull(executorQualifier, "L2 工作池必须使用显式 Qualifier");
        assertEquals("userMemoryExtractionExecutor",
                executorQualifier.value(),
                "L2 不得占用 L1 后台摘要工作池");
    }

    @Test
    @DisplayName("L2 专用模型显式限制六十秒并禁用 SDK 重试")
    void dedicatedModelHasExplicitTimeoutAndNoRetry() {
        UserMemoryExtractionChatModelConfig config =
                new UserMemoryExtractionChatModelConfig();
        config.setBaseUrl("http://127.0.0.1:19091/v1");
        config.setApiKey("test-key");
        config.setModelName("memory-extraction-test");
        config.setMaxTokens(8_192);
        AiModelMonitorListener listener = mock(AiModelMonitorListener.class);
        ReflectionTestUtils.setField(
                config, "aiModelMonitorListener", listener);
        HttpClientBuilder httpClientBuilder = mock(HttpClientBuilder.class);
        when(httpClientBuilder.connectTimeout(anyDuration()))
                .thenReturn(httpClientBuilder);
        when(httpClientBuilder.readTimeout(anyDuration()))
                .thenReturn(httpClientBuilder);
        when(httpClientBuilder.build()).thenReturn(mock(HttpClient.class));
        @SuppressWarnings("unchecked")
        ObjectProvider<HttpClientBuilder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(httpClientBuilder);

        OpenAiChatModel model = (OpenAiChatModel)
                config.userMemoryExtractionChatModel(provider);

        assertEquals(0, ReflectionTestUtils.getField(model, "maxRetries"));
        assertEquals(List.of(listener), model.listeners());
        verify(httpClientBuilder).connectTimeout(Duration.ofSeconds(60));
        verify(httpClientBuilder).readTimeout(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("L2 专用执行器容量有界且拒绝时抛异常")
    void dedicatedExecutorIsBoundedAndRejectsExplicitly() {
        ExecutorService executor = new UserMemoryExtractionExecutorConfig()
                .userMemoryExtractionExecutor();
        try {
            ThreadPoolExecutor threadPool = assertInstanceOf(
                    ThreadPoolExecutor.class, executor);
            int queueCapacity = threadPool.getQueue().size()
                    + threadPool.getQueue().remainingCapacity();

            assertEquals(2, threadPool.getCorePoolSize());
            assertEquals(2, threadPool.getMaximumPoolSize());
            assertEquals(100, queueCapacity);
            assertInstanceOf(ThreadPoolExecutor.AbortPolicy.class,
                    threadPool.getRejectedExecutionHandler());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("L2 工作池全部阻塞时 L1 后台任务仍可执行")
    void blockedL2WorkersDoNotStarveL1Tasks() throws Exception {
        ExecutorService l1Executor = new MemorySummarizationExecutorConfig()
                .memorySummarizationExecutor();
        ExecutorService l2Executor = new UserMemoryExtractionExecutorConfig()
                .userMemoryExtractionExecutor();
        CountDownLatch l2WorkersEntered = new CountDownLatch(2);
        CountDownLatch releaseL2Workers = new CountDownLatch(1);
        CountDownLatch l1Completed = new CountDownLatch(1);
        try {
            for (int index = 0; index < 2; index++) {
                l2Executor.submit(() -> {
                    l2WorkersEntered.countDown();
                    try {
                        releaseL2Workers.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertEquals(true,
                    l2WorkersEntered.await(1, TimeUnit.SECONDS));

            l1Executor.submit(l1Completed::countDown);

            assertEquals(true, l1Completed.await(1, TimeUnit.SECONDS),
                    "L2 阻塞不得占用 L1 后台摘要 worker");
        } finally {
            releaseL2Workers.countDown();
            l2Executor.shutdownNow();
            l1Executor.shutdownNow();
        }
    }

    private Duration anyDuration() {
        return org.mockito.ArgumentMatchers.any(Duration.class);
    }
}
