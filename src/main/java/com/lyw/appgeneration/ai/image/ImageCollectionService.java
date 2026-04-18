package com.lyw.appgeneration.ai.image;

import cn.hutool.core.collection.CollUtil;
import com.lyw.appgeneration.ai.image.model.ImageCollectionPlan;
import com.lyw.appgeneration.ai.image.model.ImageResource;
import com.lyw.appgeneration.ai.image.tools.ImageSearchTool;
import com.lyw.appgeneration.ai.image.tools.LogoGeneratorTool;
import com.lyw.appgeneration.ai.image.tools.MermaidDiagramTool;
import com.lyw.appgeneration.ai.image.tools.UndrawIllustrationTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

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
    private final UndrawIllustrationTool undrawIllustrationTool;
    private final MermaidDiagramTool mermaidDiagramTool;
    private final LogoGeneratorTool logoGeneratorTool;
    private final ExecutorService executor;
    private final ImageCollectionPromptBuilder promptBuilder;
    private final int timeoutSeconds;

    public ImageCollectionService(
            ImageCollectionPlanService planService,
            ImageSearchTool imageSearchTool,
            UndrawIllustrationTool undrawIllustrationTool,
            MermaidDiagramTool mermaidDiagramTool,
            LogoGeneratorTool logoGeneratorTool,
            @Qualifier("imageCollectionExecutor") ExecutorService executor,
            ImageCollectionPromptBuilder promptBuilder,
            @Value("${image.collection.timeout-seconds:30}") int timeoutSeconds
    ) {
        this.planService = planService;
        this.imageSearchTool = imageSearchTool;
        this.undrawIllustrationTool = undrawIllustrationTool;
        this.mermaidDiagramTool = mermaidDiagramTool;
        this.logoGeneratorTool = logoGeneratorTool;
        this.executor = executor;
        this.promptBuilder = promptBuilder;
        this.timeoutSeconds = timeoutSeconds;
    }

    public String enhancePrompt(String originalPrompt) {
        try {
            ImageCollectionPlan plan = planService.planImageCollection(originalPrompt);
            if (plan == null) {
                log.warn("图片收集计划为 null,跳过收集");
                return originalPrompt;
            }
            List<CompletableFuture<List<ImageResource>>> futures = new ArrayList<>();
            addFutures(plan.getContentImageTasks(), t ->
                    imageSearchTool.searchContentImages(t.query()), futures);
            addFutures(plan.getIllustrationTasks(), t ->
                    undrawIllustrationTool.searchIllustrations(t.query()), futures);
            addFutures(plan.getDiagramTasks(), t ->
                    mermaidDiagramTool.generateMermaidDiagram(t.mermaidCode(), t.description()), futures);
            addFutures(plan.getLogoTasks(), t ->
                    logoGeneratorTool.generateLogos(t.description()), futures);

            List<ImageResource> aggregated = new ArrayList<>();
            for (CompletableFuture<List<ImageResource>> f : futures) {
                try {
                    List<ImageResource> part = f.get(timeoutSeconds, TimeUnit.SECONDS);
                    if (part != null) {
                        aggregated.addAll(part);
                    }
                } catch (Exception e) {
                    log.warn("单任务失败或超时,忽略: {}", e.getMessage());
                }
            }
            log.info("图片并发收集完成,共 {} 张", aggregated.size());
            return promptBuilder.build(originalPrompt, aggregated);
        } catch (Exception e) {
            log.error("图片收集整体失败,降级为原始提示词: {}", e.getMessage(), e);
            return originalPrompt;
        }
    }

    private <T> void addFutures(
            List<T> tasks,
            Function<T, List<ImageResource>> invoker,
            List<CompletableFuture<List<ImageResource>>> futures
    ) {
        if (CollUtil.isEmpty(tasks)) {
            return;
        }
        for (T task : tasks) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return invoker.apply(task);
                } catch (Exception e) {
                    log.warn("子任务异常,返回空列表: {}", e.getMessage());
                    return Collections.emptyList();
                }
            }, executor));
        }
    }
}
