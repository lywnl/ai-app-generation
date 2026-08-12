package com.lyw.appgeneration.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VueTurnExecutorConfigTest {

    @Test
    void cancellationExecutorUsesManagedVirtualThreadsAndBoundedConcurrency()
            throws Exception {
        SimpleAsyncTaskExecutor executor = new VueTurnExecutorConfig()
                .vueTurnCancellationExecutor();
        CountDownLatch ran = new CountDownLatch(1);
        AtomicBoolean virtual = new AtomicBoolean();
        try (executor) {
            executor.execute(() -> {
                virtual.set(Thread.currentThread().isVirtual());
                ran.countDown();
            });
            assertTrue(ran.await(1, TimeUnit.SECONDS));
            assertTrue(virtual.get());
            assertEquals(VueTurnExecutorConfig.MAX_CONCURRENCY,
                    executor.getConcurrencyLimit());
        }
    }

    @Test
    void saturationAppliesBackpressureInsteadOfRejectingOrEscapingManagement()
            throws Exception {
        SimpleAsyncTaskExecutor executor = new VueTurnExecutorConfig()
                .vueTurnCancellationExecutor();
        CountDownLatch allRunning = new CountDownLatch(
                VueTurnExecutorConfig.MAX_CONCURRENCY);
        CountDownLatch release = new CountDownLatch(1);
        try (executor; var submitter = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0;
                    index < VueTurnExecutorConfig.MAX_CONCURRENCY; index++) {
                executor.execute(() -> {
                    allRunning.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertTrue(allRunning.await(2, TimeUnit.SECONDS));

            CountDownLatch overflowRan = new CountDownLatch(1);
            CountDownLatch overflowSubmitting = new CountDownLatch(1);
            Future<?> overflowSubmission = submitter.submit(() -> {
                overflowSubmitting.countDown();
                executor.execute(overflowRan::countDown);
            });
            assertTrue(overflowSubmitting.await(1, TimeUnit.SECONDS));
            assertFalse(overflowSubmission.isDone(),
                    "达到并发上限时应背压提交者，不能拒绝后创建脱管线程");

            release.countDown();
            overflowSubmission.get(2, TimeUnit.SECONDS);
            assertTrue(overflowRan.await(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    void closedExecutorRejectsNewFinalizationTasks() {
        SimpleAsyncTaskExecutor executor = new VueTurnExecutorConfig()
                .vueTurnCancellationExecutor();
        executor.close();

        assertThrows(TaskRejectedException.class,
                () -> executor.execute(() -> { }));
    }
}
