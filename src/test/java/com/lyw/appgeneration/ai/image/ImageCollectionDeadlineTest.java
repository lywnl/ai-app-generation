package com.lyw.appgeneration.ai.image;

import com.lyw.appgeneration.ai.image.model.ImageCollectionPlan;
import com.lyw.appgeneration.ai.image.model.ImageCategoryEnum;
import com.lyw.appgeneration.ai.image.model.ImageResource;
import com.lyw.appgeneration.ai.image.tools.ImageSearchTool;
import com.lyw.appgeneration.ai.image.tools.LogoGeneratorTool;
import com.lyw.appgeneration.core.resource.ResourcePreparationScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class ImageCollectionDeadlineTest {
    @Test
    void 前主题未完成时保留后主题且迟到响应不能覆盖() throws Exception {
        var planner = mock(ImageCollectionPlanService.class);
        var search = mock(ImageSearchTool.class);
        var logo = mock(LogoGeneratorTool.class);
        var plan = new ImageCollectionPlan();
        plan.setContentImageTasks(List.of(new ImageCollectionPlan.ImageSearchTask("slow"),
                new ImageCollectionPlan.ImageSearchTask("fast"), new ImageCollectionPlan.ImageSearchTask("excess")));
        when(planner.planImageCollection(anyString())).thenReturn(plan);
        CountDownLatch slowEntered = new CountDownLatch(1), release = new CountDownLatch(1), published = new CountDownLatch(1);
        when(search.searchContentImages(eq("slow"), anyInt(), any(BooleanSupplier.class))).thenAnswer(i -> {
            slowEntered.countDown();
            try { release.await(); } catch (InterruptedException stopped) { /* 模拟供应商忽略取消并迟到返回。 */ }
            return List.of(photo("late"));
        });
        when(search.searchContentImages(eq("fast"), anyInt(), any(BooleanSupplier.class)))
                .thenReturn(List.of(photo("accepted")));
        ImageCollectionPromptBuilder formatter = new ImageCollectionPromptBuilder() {
            @Override public String buildContext(List<ImageResource> images, ImageCollectionQuota quota) {
                String text = super.buildContext(images, quota);
                if (text.contains("accepted")) published.countDown();
                return text;
            }
        };
        AtomicLong clock = new AtomicLong();
        try (var pool = Executors.newFixedThreadPool(2);
             var scope = new ResourcePreparationScope(Duration.ofSeconds(8), () -> true, clock::get, "images-test")) {
            var service = new ImageCollectionService(planner, search, logo, pool, formatter, 30);
            var slot = scope.slot("images");
            service.startPromptContext("替换图片", ImageCollectionQuota.FOLLOW_UP, scope, slot);
            assertTrue(slowEntered.await(1, TimeUnit.SECONDS));
            assertTrue(published.await(1, TimeUnit.SECONDS));
            // 等格式化完成并发布；发布本身也受截止约束。
            pool.submit(() -> { }).get(1, TimeUnit.SECONDS);
            clock.set(Duration.ofSeconds(8).toNanos());
            var result = scope.awaitAndFreeze();
            assertEquals(1, result.size());
            assertTrue(result.getFirst().text().contains("accepted"));
            assertFalse(result.getFirst().text().contains("late"));
            release.countDown();
            verify(search, times(2)).searchContentImages(anyString(), anyInt(), any(BooleanSupplier.class));
            verifyNoInteractions(logo);
        } finally { release.countDown(); }
    }

    @Test
    void 图片规划耗尽预算后不能启动搜索或Logo() throws Exception {
        var planner = mock(ImageCollectionPlanService.class);
        var search = mock(ImageSearchTool.class);
        var logo = mock(LogoGeneratorTool.class);
        AtomicLong clock = new AtomicLong();
        when(planner.planImageCollection(anyString())).thenAnswer(i -> {
            clock.set(Duration.ofSeconds(8).toNanos());
            var plan = new ImageCollectionPlan();
            plan.setContentImageTasks(List.of(new ImageCollectionPlan.ImageSearchTask("blocked")));
            plan.setLogoTasks(List.of(new ImageCollectionPlan.LogoTask("blocked-logo")));
            return plan;
        });
        try (var pool = Executors.newFixedThreadPool(1);
             var scope = new ResourcePreparationScope(Duration.ofSeconds(8), () -> true, clock::get, "plan-test")) {
            var service = new ImageCollectionService(planner, search, logo, pool, new ImageCollectionPromptBuilder(), 30);
            var slot = scope.slot("images");
            assertThrows(CancellationException.class, () -> service.startPromptContext(
                    "图片", ImageCollectionQuota.FOLLOW_UP, scope, slot));
            assertTrue(scope.awaitAndFreeze().isEmpty());
            verifyNoInteractions(search, logo);
        }
    }

    private ImageResource photo(String id) {
        return ImageResource.builder().category(ImageCategoryEnum.CONTENT).description(id)
                .url("https://images.test/" + id + ".jpg?full=url").build();
    }
}
