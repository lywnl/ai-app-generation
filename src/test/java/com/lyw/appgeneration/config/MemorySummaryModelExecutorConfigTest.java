package com.lyw.appgeneration.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class MemorySummaryModelExecutorConfigTest {

    @Test
    @DisplayName("摘要模型执行器使用有界队列和显式拒绝策略")
    void usesBoundedQueueAndAbortPolicy() {
        ExecutorService executor = new MemorySummaryModelExecutorConfig()
                .memorySummaryModelExecutor();
        try {
            ThreadPoolExecutor threadPool = assertInstanceOf(
                    ThreadPoolExecutor.class, executor);
            int queueCapacity = threadPool.getQueue().size()
                    + threadPool.getQueue().remainingCapacity();

            assertEquals(2, threadPool.getCorePoolSize());
            assertEquals(4, threadPool.getMaximumPoolSize());
            assertEquals(20, queueCapacity);
            assertInstanceOf(ThreadPoolExecutor.AbortPolicy.class,
                    threadPool.getRejectedExecutionHandler(),
                    "队列满时必须抛出拒绝异常，不能回退到调用线程或静默丢弃");
        } finally {
            executor.shutdownNow();
        }
    }
}
