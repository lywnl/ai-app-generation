package com.lyw.appgeneration.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelRequestGateExecutorConfigTest {

    @Test
    void 使用独立受管虚拟线程执行器() throws Exception {
        ExecutorService executor = new ModelRequestGateExecutorConfig()
                .modelRequestGateExecutor();
        try {
            Thread worker = executor.submit(Thread::currentThread)
                    .get(2, TimeUnit.SECONDS);

            assertTrue(worker.isVirtual());
            assertTrue(worker.getName().startsWith("Model-Request-Gate-"));
        } finally {
            executor.close();
        }

        assertThrows(RejectedExecutionException.class,
                () -> executor.execute(() -> { }));
    }
}
