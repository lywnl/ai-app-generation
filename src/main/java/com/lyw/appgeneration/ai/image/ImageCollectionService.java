package com.lyw.appgeneration.ai.image;

import cn.hutool.core.collection.CollUtil;
import com.lyw.appgeneration.ai.image.model.ImageCollectionPlan;
import com.lyw.appgeneration.ai.image.model.ImageResource;
import com.lyw.appgeneration.ai.image.tools.ImageSearchTool;
import com.lyw.appgeneration.ai.image.tools.LogoGeneratorTool;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

/**
 * 图片并发收集服务
 * 入口 {@link #enhancePrompt(String)},输入原始提示词,输出拼接图片资源后的增强提示词。
 * 任一环节失败都降级为原始提示词,不抛异常。
 */
@Slf4j
@Service
public class ImageCollectionService {

    private final ImageCollectionPlanService planService;
    private final ImageSearchTool imageSearchTool;
    private final LogoGeneratorTool logoGeneratorTool;
    private final ExecutorService executor;
    private final ImageCollectionPromptBuilder promptBuilder;
    private final int timeoutSeconds;

    public ImageCollectionService(
            ImageCollectionPlanService planService,
            ImageSearchTool imageSearchTool,
            LogoGeneratorTool logoGeneratorTool,
            @Qualifier("imageCollectionExecutor") ExecutorService executor,
            ImageCollectionPromptBuilder promptBuilder,
            @Value("${image.collection.timeout-seconds:30}") int timeoutSeconds
    ) {
        this.planService = planService;
        this.imageSearchTool = imageSearchTool;
        this.logoGeneratorTool = logoGeneratorTool;
        this.executor = executor;
        this.promptBuilder = promptBuilder;
        this.timeoutSeconds = timeoutSeconds;
    }

    public String enhancePrompt(String originalPrompt) {
        List<ImageResource> images = collectImages(originalPrompt);
        return promptBuilder.build(originalPrompt, images);
    }

    /** 仅收集图片参考资料，不重复包含原始用户需求。 */
    public String collectPromptContext(String originalPrompt) {
        return promptBuilder.buildContext(collectImages(originalPrompt));
    }

    private List<ImageResource> collectImages(String originalPrompt) {
        String requestId = UUID.randomUUID().toString();
        long startedAt = System.nanoTime();
        long planMs = 0;
        long collectionStartedAt = 0;
        int imageCount = 0;
        String stage = "plan";
        String status = "FAILED";
        List<ImageTaskFuture> logoFutures = new ArrayList<>();
        log.info("[图片耗时] 规划开始,requestId={}", requestId);
        try {
            ImageCollectionPlan plan;
            long planStartedAt = System.nanoTime();
            try {
                plan = planService.planImageCollection(originalPrompt);
            } finally {
                planMs = elapsedMillis(planStartedAt);
            }
            int photoTaskCount = plan == null || plan.getContentImageTasks() == null
                    ? 0 : plan.getContentImageTasks().size();
            int logoTaskCount = plan == null || plan.getLogoTasks() == null
                    ? 0 : plan.getLogoTasks().size();
            log.info("[图片耗时] 规划完成,requestId={},elapsedMs={},photoTaskCount={},logoTaskCount={}",
                    requestId, planMs, photoTaskCount, logoTaskCount);
            if (plan == null) {
                status = "EMPTY_PLAN";
                log.warn("图片收集计划为 null,跳过收集,requestId={}", requestId);
                return List.of();
            }
            stage = "collection";
            collectionStartedAt = System.nanoTime();
            // Logo 通常最慢，先提交以避免排在照片任务之后等待工作线程。
            addFutures(plan.getLogoTasks(), (t, cancelled) ->
                    logoGeneratorTool.generateLogos(t.description(), cancelled), logoFutures,
                    requestId, "logo");

            List<ImageTaskFuture> futures = new ArrayList<>();
            addFutures(plan.getContentImageTasks(), (t, cancelled) ->
                    imageSearchTool.searchContentImages(t.query()), futures,
                    requestId, "photo");
            // 提交优先级与交付顺序分开：上下文仍先展示照片，再展示 Logo。
            futures.addAll(logoFutures);

            List<ImageResource> aggregated = new ArrayList<>();
            int waitFailures = 0;
            for (ImageTaskFuture f : futures) {
                try {
                    List<ImageResource> part = f.get(timeoutSeconds, TimeUnit.SECONDS);
                    if (part != null) {
                        aggregated.addAll(part);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    status = "CANCELED";
                    log.info("图片收集等待已中断,requestId={}", requestId);
                    break;
                } catch (TimeoutException e) {
                    waitFailures++;
                    if (logoFutures.contains(f)) {
                        cancelLogoTask(f);
                    }
                    log.warn("单任务等待超时,忽略,requestId={},timeoutSeconds={}",
                            requestId, timeoutSeconds);
                } catch (Exception e) {
                    waitFailures++;
                    log.warn("单任务失败或超时,忽略,requestId={},errorType={}",
                            requestId, e.getClass().getSimpleName());
                }
            }
            imageCount = aggregated.size();
            if (!"CANCELED".equals(status)) {
                status = waitFailures > 0 ? "PARTIAL" : "RETURNED";
            }
            log.info("图片并发收集完成,共 {} 张,requestId={}", imageCount, requestId);
            return aggregated;
        } catch (Exception e) {
            log.error("图片收集整体失败,降级为原始提示词,requestId={},stage={},errorType={}",
                    requestId, stage, e.getClass().getSimpleName());
            return List.of();
        } finally {
            // 保留原有逐结果等待窗口；只在不再等待 Logo 时停止其后续查询。
            logoFutures.forEach(this::cancelLogoTask);
            long collectionMs = collectionStartedAt == 0 ? 0 : elapsedMillis(collectionStartedAt);
            log.info("[图片耗时] 图片准备结束,requestId={},planMs={},collectionMs={},totalMs={},"
                            + "imageCount={},status={}",
                    requestId, planMs, collectionMs, elapsedMillis(startedAt), imageCount, status);
        }
    }

    private <T> void addFutures(
            List<T> tasks,
            BiFunction<T, BooleanSupplier, List<ImageResource>> invoker,
            List<ImageTaskFuture> futures,
            String requestId,
            String stage
    ) {
        if (CollUtil.isEmpty(tasks)) {
            log.info("[图片耗时] 批次完成,requestId={},stage={},taskCount=0,elapsedMs=0",
                    requestId, stage);
            return;
        }
        long batchStartedAt = System.nanoTime();
        int taskCount = tasks.size();
        log.info("[图片耗时] 批次开始,requestId={},stage={},taskCount={}",
                requestId, stage, taskCount);
        int index = 0;
        for (T task : tasks) {
            int taskIndex = ++index;
            long submittedAt = System.nanoTime();
            AtomicBoolean cancelled = new AtomicBoolean();
            ImageTaskFuture future = new ImageTaskFuture(() -> {
                long queueMs = elapsedMillis(submittedAt);
                log.info("[图片耗时] 任务开始,requestId={},stage={},taskIndex={},queueMs={}",
                        requestId, stage, taskIndex, queueMs);
                long executionStartedAt = System.nanoTime();
                int resultCount = 0;
                String outcome = "FAILED";
                String previousRequestId = MDC.get("imageCollectionRequestId");
                MDC.put("imageCollectionRequestId", requestId);
                try {
                    List<ImageResource> result = invoker.apply(task, cancelled::get);
                    resultCount = result == null ? 0 : result.size();
                    // 工具可能在内部处理异常并返回空列表，因此空结果不能标记为成功。
                    outcome = resultCount == 0 ? "EMPTY" : "RETURNED";
                    return result;
                } catch (Exception e) {
                    log.warn("子任务异常,返回空列表,requestId={},stage={},taskIndex={},errorType={}",
                            requestId, stage, taskIndex, e.getClass().getSimpleName());
                    return Collections.emptyList();
                } finally {
                    if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                        outcome = "CANCELED";
                    }
                    log.info("[图片耗时] 任务结束,requestId={},stage={},taskIndex={},queueMs={},"
                                    + "executeMs={},elapsedMs={},imageCount={},status={}",
                            requestId, stage, taskIndex, queueMs, elapsedMillis(executionStartedAt),
                            elapsedMillis(submittedAt), resultCount, outcome);
                    if (previousRequestId == null) {
                        MDC.remove("imageCollectionRequestId");
                    } else {
                        MDC.put("imageCollectionRequestId", previousRequestId);
                    }
                }
            }, cancelled);
            futures.add(future);
            executor.execute(future);
        }
        // 批次耗时是并行任务从提交到全部完成的实际经过时间，不能累加各任务执行时间。
        CompletableFuture.allOf(futures.stream().map(f -> f.completion).toArray(CompletableFuture[]::new))
                .whenComplete((ignored, error) -> log.info(
                        "[图片耗时] 批次完成,requestId={},stage={},taskCount={},elapsedMs={}",
                        requestId, stage, taskCount, elapsedMillis(batchStartedAt)));
    }

    private void cancelLogoTask(ImageTaskFuture future) {
        if (!future.isDone()) {
            future.cancel(true);
            if (executor instanceof ThreadPoolExecutor threadPool) {
                threadPool.remove(future);
            }
        }
    }

    /** FutureTask 能中断实际工作线程，停止信号还能覆盖 SDK 吞掉中断的情况。 */
    private static final class ImageTaskFuture extends FutureTask<List<ImageResource>> {
        private final AtomicBoolean cancelled;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();

        private ImageTaskFuture(Callable<List<ImageResource>> callable, AtomicBoolean cancelled) {
            super(callable);
            this.cancelled = cancelled;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled.set(true);
            return super.cancel(mayInterruptIfRunning);
        }

        @Override
        protected void done() {
            completion.complete(null);
        }
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
