package com.lyw.appgeneration.ai.image.tools;

import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisParam;
import com.lyw.appgeneration.ai.image.ImageCollectionPlanService;
import com.lyw.appgeneration.ai.image.ImageCollectionPromptBuilder;
import com.lyw.appgeneration.ai.image.ImageCollectionService;
import com.lyw.appgeneration.ai.image.model.ImageCategoryEnum;
import com.lyw.appgeneration.ai.image.model.ImageCollectionPlan;
import com.lyw.appgeneration.ai.image.model.ImageResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.lyw.appgeneration.ai.image.tools.LogoGeneratorToolTest.configured;
import static com.lyw.appgeneration.ai.image.tools.LogoGeneratorToolTest.result;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LogoPollingIntegrationTest {
    private LogoImageClient client;
    private ImageCollectionPlanService planner;
    private ImageSearchTool photos;
    private ExecutorService imageExecutor;

    @BeforeEach
    void setUp() throws Exception {
        client = mock(LogoImageClient.class);
        planner = mock(ImageCollectionPlanService.class);
        photos = mock(ImageSearchTool.class);
        imageExecutor = Executors.newFixedThreadPool(2);
        ImageCollectionPlan plan = new ImageCollectionPlan();
        plan.setLogoTasks(List.of(new ImageCollectionPlan.LogoTask("品牌")));
        plan.setContentImageTasks(List.of(new ImageCollectionPlan.ImageSearchTask("照片")));
        when(planner.planImageCollection("网站")).thenReturn(plan);
        when(client.asyncCall(any(ImageSynthesisParam.class))).thenReturn(result("PENDING"));
        when(photos.searchContentImages("照片")).thenReturn(List.of(ImageResource.builder()
                .category(ImageCategoryEnum.CONTENT).description("照片")
                .url("https://example.com/photo.jpg").build()));
    }

    @AfterEach
    void closeExecutor() throws Exception {
        imageExecutor.shutdownNow();
        assertTrue(imageExecutor.awaitTermination(3, TimeUnit.SECONDS), "图片工作线程应能收尾");
    }

    @Test
    void 外层超时中断实际Logo任务且仍返回照片() throws Exception {
        CountDownLatch queryEnded = new CountDownLatch(1);
        when(client.fetch(anyString(), anyString())).thenAnswer(ignored -> {
            try {
                new CountDownLatch(1).await();
                return result("RUNNING");
            } finally {
                queryEnded.countDown();
            }
        });

        String context = service(1000, 1).collectPromptContext("网站");

        assertTrue(context.contains("photo.jpg"));
        assertFalse(context.contains("logo.png"));
        assertTrue(queryEnded.await(2, TimeUnit.SECONDS), "取消必须传给正在运行的工作线程");
        imageExecutor.shutdown();
        assertTrue(imageExecutor.awaitTermination(2, TimeUnit.SECONDS));
        verify(client, times(1)).asyncCall(any(ImageSynthesisParam.class));
        verify(client, times(1)).fetch(anyString(), anyString());
    }

    @Test
    void 请求中断时停止Logo查询并保留调用线程的中断状态() throws Exception {
        CountDownLatch queryStarted = new CountDownLatch(1);
        CountDownLatch queryEnded = new CountDownLatch(1);
        CountDownLatch callerEnded = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        when(client.fetch(anyString(), anyString())).thenAnswer(ignored -> {
            queryStarted.countDown();
            try {
                new CountDownLatch(1).await();
                return result("RUNNING");
            } finally {
                queryEnded.countDown();
            }
        });
        try (var callers = Executors.newSingleThreadExecutor()) {
            var call = callers.submit(() -> {
                try {
                    service(1000, 30).collectPromptContext("网站");
                    interrupted.set(Thread.currentThread().isInterrupted());
                } finally {
                    callerEnded.countDown();
                }
            });
            try {
                assertTrue(queryStarted.await(2, TimeUnit.SECONDS));
                call.cancel(true);
                assertTrue(callerEnded.await(2, TimeUnit.SECONDS));
                assertTrue(queryEnded.await(2, TimeUnit.SECONDS));
                assertTrue(interrupted.get());
                verify(client, times(1)).fetch(anyString(), anyString());
            } finally {
                call.cancel(true);
                callers.shutdownNow();
            }
        }
    }

    @Test
    void 队列中的Logo被取消后不会提交云端任务() throws Exception {
        imageExecutor.shutdownNow();
        CountDownLatch submitted = new CountDownLatch(2);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        imageExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>()) {
            @Override
            public void execute(Runnable command) {
                super.execute(command);
                if (command instanceof FutureTask<?>) {
                    submitted.countDown();
                }
            }
        };
        imageExecutor.execute(() -> {
            try {
                releaseWorker.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        CountDownLatch callerEnded = new CountDownLatch(1);
        try (var callers = Executors.newSingleThreadExecutor()) {
            var call = callers.submit(() -> {
                try {
                    service(1000, 30).collectPromptContext("网站");
                } finally {
                    callerEnded.countDown();
                }
            });
            try {
                assertTrue(submitted.await(2, TimeUnit.SECONDS));
                call.cancel(true);
                assertTrue(callerEnded.await(2, TimeUnit.SECONDS));
                assertEquals(1, ((ThreadPoolExecutor) imageExecutor).getQueue().size(), "只移除已取消的 Logo");
                releaseWorker.countDown();
                imageExecutor.shutdown();
                assertTrue(imageExecutor.awaitTermination(2, TimeUnit.SECONDS));
                verify(client, never()).asyncCall(any(ImageSynthesisParam.class));
            } finally {
                call.cancel(true);
                releaseWorker.countDown();
                callers.shutdownNow();
            }
        }
    }

    @Test
    void Logo等待窗口仍从轮到该结果时开始() throws Exception {
        CountDownLatch releasePhoto = new CountDownLatch(1);
        when(photos.searchContentImages("照片")).thenAnswer(ignored -> {
            releasePhoto.await();
            return List.of();
        });
        when(client.fetch(anyString(), anyString())).thenReturn(result("RUNNING"), result("SUCCEEDED"));
        try {
            // 照片先消耗自己的 1 秒等待，Logo 在提交约 1.5 秒后完成，仍应被交付。
            String context = service(1500, 1).collectPromptContext("网站");
            assertTrue(context.contains("logo.png"), "不能把 Logo 超时提前到任务提交时开始计算");
            verify(client, times(2)).fetch(anyString(), anyString());
        } finally {
            releasePhoto.countDown();
        }
    }

    private ImageCollectionService service(long pollIntervalMs, int timeoutSeconds) {
        LogoGeneratorTool logos = configured(new LogoGeneratorTool(pollIntervalMs, 10, () -> client,
                System::nanoTime, TimeUnit.NANOSECONDS::sleep));
        return new ImageCollectionService(planner, photos, logos, imageExecutor,
                new ImageCollectionPromptBuilder(), timeoutSeconds);
    }
}
