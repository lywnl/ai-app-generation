package com.lyw.appgeneration.ai.image;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ImageCollectionExecutorConfigTest {

    @Test
    void 默认十五个任务能同时开始且多余任务有界排队() throws Exception {
        CountDownLatch started = new CountDownLatch(15);
        CountDownLatch queuedStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        try (ExecutorService executor = new ImageCollectionExecutorConfig().imageCollectionExecutor()) {
            ThreadPoolExecutor pool = (ThreadPoolExecutor) executor;
            try {
                for (int i = 0; i < 15; i++) {
                    executor.submit(() -> {
                        maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                        started.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("测试任务未释放");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        } finally {
                            active.decrementAndGet();
                        }
                    });
                }
                assertTrue(started.await(3, TimeUnit.SECONDS), "不能仍只启动十个核心线程");
                var queued = executor.submit(queuedStarted::countDown);
                assertEquals(1, queuedStarted.getCount(), "第十六个任务必须等待空闲线程");
                assertEquals(1, pool.getQueue().size());
                assertEquals(99, pool.getQueue().remainingCapacity());
                assertEquals(15, maximum.get());
                release.countDown();
                queued.get(3, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void 图片并发配置可以独立绑定且不会改变队列规则() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(ImageCollectionExecutorConfig.class)
                .withPropertyValues("image.collection.worker-threads=3", "image.collection.queue-capacity=8")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    ThreadPoolExecutor pool = (ThreadPoolExecutor) context.getBean("imageCollectionExecutor");
                    assertEquals(3, pool.getCorePoolSize());
                    assertEquals(3, pool.getMaximumPoolSize());
                    assertEquals(8, pool.getQueue().remainingCapacity());
                });
    }

    @Test
    void 非法线程数或队列容量不能启动() {
        ImageCollectionExecutorConfig config = new ImageCollectionExecutorConfig();
        config.setWorkerThreads(0);
        assertThrows(IllegalArgumentException.class, config::imageCollectionExecutor);
        config.setWorkerThreads(15);
        config.setQueueCapacity(0);
        assertThrows(IllegalArgumentException.class, config::imageCollectionExecutor);
    }
}
