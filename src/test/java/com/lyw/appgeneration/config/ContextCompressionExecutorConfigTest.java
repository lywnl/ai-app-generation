package com.lyw.appgeneration.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextCompressionExecutorConfigTest {

    @Test
    void usesDedicatedBoundedQueueAndAbortPolicy() {
        ExecutorService executor = new ContextCompressionExecutorConfig()
                .contextCompressionExecutor();
        try {
            ThreadPoolExecutor threadPool = assertInstanceOf(
                    ThreadPoolExecutor.class, executor);
            int capacity = threadPool.getQueue().size()
                    + threadPool.getQueue().remainingCapacity();

            assertTrue(capacity > 0 && capacity < Integer.MAX_VALUE,
                    "上下文压缩队列必须有界");
            assertInstanceOf(ThreadPoolExecutor.AbortPolicy.class,
                    threadPool.getRejectedExecutionHandler(),
                    "任务池满时必须类型化拒绝，不能回退到请求线程执行");
        } finally {
            executor.shutdownNow();
        }
    }
}
