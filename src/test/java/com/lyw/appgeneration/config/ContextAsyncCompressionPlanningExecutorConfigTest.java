package com.lyw.appgeneration.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextAsyncCompressionPlanningExecutorConfigTest {

    @Test
    void usesFixedBoundedPoolAndAbortPolicy() {
        ExecutorService executor =
                new ContextAsyncCompressionPlanningExecutorConfig()
                        .contextAsyncCompressionPlanningExecutor();
        try {
            ThreadPoolExecutor threadPool = assertInstanceOf(
                    ThreadPoolExecutor.class, executor);
            int capacity = threadPool.getQueue().size()
                    + threadPool.getQueue().remainingCapacity();

            assertEquals(2, threadPool.getCorePoolSize());
            assertEquals(2, threadPool.getMaximumPoolSize());
            assertEquals(32, capacity);
            assertInstanceOf(ThreadPoolExecutor.AbortPolicy.class,
                    threadPool.getRejectedExecutionHandler());
            assertTrue(threadPool.getThreadFactory()
                    .newThread(() -> { }).getName()
                    .startsWith("Context-Async-Planning-"));
        } finally {
            executor.shutdownNow();
        }
    }
}
