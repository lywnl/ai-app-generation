package com.lyw.appgeneration.ai.image;

import com.lyw.appgeneration.ai.image.model.ImageCategoryEnum;
import com.lyw.appgeneration.ai.image.model.ImageCollectionPlan;
import com.lyw.appgeneration.ai.image.model.ImageResource;
import com.lyw.appgeneration.ai.image.tools.ImageSearchTool;
import com.lyw.appgeneration.ai.image.tools.LogoGeneratorTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImageCollectionServiceTest {

    @Mock private ImageCollectionPlanService planService;
    @Mock private ImageSearchTool imageSearchTool;
    @Mock private LogoGeneratorTool logoGeneratorTool;

    private ExecutorService executor;
    private ImageCollectionService service;

    @Test
    void 后续配额在搜索前和格式化后均限制且不凑数() {
        var plan = new ImageCollectionPlan();
        plan.setContentImageTasks(List.of(new ImageCollectionPlan.ImageSearchTask("  "),
                new ImageCollectionPlan.ImageSearchTask("新闻"), new ImageCollectionPlan.ImageSearchTask(" 新闻 "),
                new ImageCollectionPlan.ImageSearchTask("办公"), new ImageCollectionPlan.ImageSearchTask("城市")));
        plan.setLogoTasks(List.of(new ImageCollectionPlan.LogoTask("新Logo"), new ImageCollectionPlan.LogoTask("多余Logo")));
        when(planService.planImageCollection(anyString())).thenReturn(plan);
        when(imageSearchTool.searchContentImages(anyString())).thenAnswer(invocation -> IntStream.range(0, 8)
                .mapToObj(i -> ImageResource.builder().category(ImageCategoryEnum.CONTENT)
                        .description("😀".repeat(100)).url("https://images.test/" + invocation.getArgument(0) + "/" + i + "?size=200&fit=cover").build()).toList());
        when(logoGeneratorTool.generateLogos(anyString(), any(BooleanSupplier.class))).thenReturn(List.of(
                ImageResource.builder().category(ImageCategoryEnum.LOGO).url("https://logo.test/one").build(),
                ImageResource.builder().category(ImageCategoryEnum.LOGO).url("https://logo.test/two").build()));
        String result = service.collectPromptContext("只换图片", ImageCollectionQuota.FOLLOW_UP);
        verify(imageSearchTool, times(2)).searchContentImages(anyString());
        verify(imageSearchTool, never()).searchContentImages("城市");
        verify(logoGeneratorTool, times(1)).generateLogos(anyString(), any(BooleanSupplier.class));
        assertEquals(6, result.lines().filter(line -> line.contains("https://images.test/")).count());
        assertEquals(1, result.lines().filter(line -> line.contains("https://logo.test/")).count());
        assertTrue(result.contains("?size=200&fit=cover"));
        assertFalse(result.contains("😀".repeat(80)));
        assertFalse(result.contains("https://logo.test/two"));
    }

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(4);
        service = new ImageCollectionService(
                planService,
                imageSearchTool,
                logoGeneratorTool,
                executor,
                new ImageCollectionPromptBuilder(),
                2 // 短超时,加速测试
        );
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void enhancePrompt_happyPath_returnsEnhancedPrompt() {
        ImageCollectionPlan plan = new ImageCollectionPlan();
        plan.setContentImageTasks(List.of(new ImageCollectionPlan.ImageSearchTask("风景")));
        when(planService.planImageCollection("博客网站")).thenReturn(plan);
        when(imageSearchTool.searchContentImages("风景")).thenReturn(List.of(
                ImageResource.builder()
                        .category(ImageCategoryEnum.CONTENT)
                        .description("山")
                        .url("https://pexels.com/mountain.jpg")
                        .build()
        ));

        String result = service.enhancePrompt("博客网站");

        assertTrue(result.contains("博客网站"));
        assertTrue(result.contains("https://pexels.com/mountain.jpg"));
    }

    @Test
    void enhancePrompt_planThrows_returnsOriginalPrompt() {
        when(planService.planImageCollection(anyString()))
                .thenThrow(new RuntimeException("LLM down"));

        String result = service.enhancePrompt("博客网站");

        assertEquals("博客网站", result);
        verifyNoInteractions(imageSearchTool, logoGeneratorTool);
    }

    @Test
    void enhancePrompt_oneToolFails_otherResultsAggregated() {
        ImageCollectionPlan plan = new ImageCollectionPlan();
        plan.setContentImageTasks(List.of(new ImageCollectionPlan.ImageSearchTask("风景")));
        plan.setLogoTasks(List.of(new ImageCollectionPlan.LogoTask("科技公司 logo")));
        when(planService.planImageCollection("科技站")).thenReturn(plan);
        when(imageSearchTool.searchContentImages("风景"))
                .thenThrow(new RuntimeException("Pexels down"));
        when(logoGeneratorTool.generateLogos(eq("科技公司 logo"), any(BooleanSupplier.class))).thenReturn(List.of(
                ImageResource.builder()
                        .category(ImageCategoryEnum.LOGO)
                        .description("科技公司 logo")
                        .url("https://dashscope.com/logo.png")
                        .build()
        ));

        String result = service.enhancePrompt("科技站");

        assertTrue(result.contains("https://dashscope.com/logo.png"));
        assertFalse(result.contains("pexels"));
    }

    @Test
    void enhancePrompt_toolTimeout_treatedAsEmpty() {
        ImageCollectionPlan plan = new ImageCollectionPlan();
        plan.setLogoTasks(List.of(new ImageCollectionPlan.LogoTask("slow-logo")));
        when(planService.planImageCollection("站")).thenReturn(plan);
        when(logoGeneratorTool.generateLogos(eq("slow-logo"), any(BooleanSupplier.class))).thenAnswer(inv -> {
            Thread.sleep(5_000);
            return List.of();
        });

        long start = System.currentTimeMillis();
        String result = service.enhancePrompt("站");
        long elapsed = System.currentTimeMillis() - start;

        assertEquals("站", result);
        assertTrue(elapsed < 4_000, "应在 2s 超时后返回,实际 " + elapsed + "ms");
    }

    @Test
    void logo先获得工作线程但上下文仍按照片和Logo顺序交付() {
        executor.shutdownNow();
        executor = Executors.newSingleThreadExecutor();
        service = new ImageCollectionService(planService, imageSearchTool, logoGeneratorTool,
                executor, new ImageCollectionPromptBuilder(), 2);
        ImageCollectionPlan plan = new ImageCollectionPlan();
        plan.setContentImageTasks(List.of(new ImageCollectionPlan.ImageSearchTask("照片")));
        plan.setLogoTasks(List.of(new ImageCollectionPlan.LogoTask("品牌")));
        when(planService.planImageCollection("网站")).thenReturn(plan);
        List<String> started = new CopyOnWriteArrayList<>();
        when(logoGeneratorTool.generateLogos(eq("品牌"), any(BooleanSupplier.class))).thenAnswer(ignored -> {
            started.add("logo");
            return List.of(image(ImageCategoryEnum.LOGO, "品牌", "https://example.com/logo.png"));
        });
        when(imageSearchTool.searchContentImages("照片")).thenAnswer(ignored -> {
            started.add("photo");
            return List.of(image(ImageCategoryEnum.CONTENT, "照片", "https://example.com/photo.jpg"));
        });

        String result = service.collectPromptContext("网站");

        assertEquals(List.of("logo", "photo"), started);
        assertTrue(result.indexOf("photo.jpg") < result.indexOf("logo.png"));
    }

    @Test
    void logo和照片同时执行且等待全部结果() throws Exception {
        executor.shutdownNow();
        executor = Executors.newFixedThreadPool(2);
        service = new ImageCollectionService(planService, imageSearchTool, logoGeneratorTool,
                executor, new ImageCollectionPromptBuilder(), 10);
        ImageCollectionPlan plan = new ImageCollectionPlan();
        plan.setContentImageTasks(List.of(new ImageCollectionPlan.ImageSearchTask("照片")));
        plan.setLogoTasks(List.of(new ImageCollectionPlan.LogoTask("品牌")));
        when(planService.planImageCollection("网站")).thenReturn(plan);
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(logoGeneratorTool.generateLogos(eq("品牌"), any(BooleanSupplier.class))).thenAnswer(ignored -> {
            bothStarted.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return List.of(image(ImageCategoryEnum.LOGO, "品牌", "https://example.com/logo.png"));
        });
        when(imageSearchTool.searchContentImages("照片")).thenAnswer(ignored -> {
            bothStarted.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return List.of(image(ImageCategoryEnum.CONTENT, "照片", "https://example.com/photo.jpg"));
        });
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            var result = caller.submit(() -> service.collectPromptContext("网站"));
            assertTrue(bothStarted.await(3, TimeUnit.SECONDS), "两个任务必须能同时开始");
            assertFalse(result.isDone(), "任务结束前不能提前返回素材上下文");
            release.countDown();
            String context = result.get(3, TimeUnit.SECONDS);
            assertTrue(context.contains("photo.jpg"));
            assertTrue(context.contains("logo.png"));
        } finally {
            release.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    void logo失败后仍返回照片() {
        ImageCollectionPlan plan = new ImageCollectionPlan();
        plan.setContentImageTasks(List.of(new ImageCollectionPlan.ImageSearchTask("照片")));
        plan.setLogoTasks(List.of(new ImageCollectionPlan.LogoTask("品牌")));
        when(planService.planImageCollection("网站")).thenReturn(plan);
        when(logoGeneratorTool.generateLogos(eq("品牌"), any(BooleanSupplier.class))).thenThrow(new RuntimeException("生成失败"));
        when(imageSearchTool.searchContentImages("照片")).thenReturn(
                List.of(image(ImageCategoryEnum.CONTENT, "照片", "https://example.com/photo.jpg")));

        assertTrue(service.collectPromptContext("网站").contains("photo.jpg"));
    }

    @Test
    void 空计划不提交任何素材任务() {
        when(planService.planImageCollection("无图网站")).thenReturn(new ImageCollectionPlan());

        assertEquals("", service.collectPromptContext("无图网站"));
        verifyNoInteractions(imageSearchTool, logoGeneratorTool);
    }

    @Test
    void 工作线程共享本次图片请求标识且结束后恢复MDC() throws Exception {
        executor.shutdownNow();
        executor = Executors.newSingleThreadExecutor();
        service = new ImageCollectionService(planService, imageSearchTool, logoGeneratorTool,
                executor, new ImageCollectionPromptBuilder(), 2);
        ImageCollectionPlan plan = new ImageCollectionPlan();
        plan.setContentImageTasks(List.of(new ImageCollectionPlan.ImageSearchTask("照片")));
        plan.setLogoTasks(List.of(new ImageCollectionPlan.LogoTask("品牌")));
        when(planService.planImageCollection("网站")).thenReturn(plan);
        List<String> requestIds = new CopyOnWriteArrayList<>();
        when(logoGeneratorTool.generateLogos(eq("品牌"), any(BooleanSupplier.class))).thenAnswer(ignored -> {
            requestIds.add(MDC.get("imageCollectionRequestId"));
            return List.of();
        });
        when(imageSearchTool.searchContentImages("照片")).thenAnswer(ignored -> {
            requestIds.add(MDC.get("imageCollectionRequestId"));
            return List.of();
        });
        MDC.put("imageCollectionRequestId", "caller-id");
        try {
            service.collectPromptContext("网站");
            assertEquals(2, requestIds.size());
            assertNotNull(requestIds.getFirst());
            assertEquals(requestIds.getFirst(), requestIds.getLast());
            assertNotEquals("caller-id", requestIds.getFirst());
            assertEquals("caller-id", MDC.get("imageCollectionRequestId"));
            assertNull(executor.submit(() -> MDC.get("imageCollectionRequestId")).get(2, TimeUnit.SECONDS));
        } finally {
            MDC.remove("imageCollectionRequestId");
        }
    }

    @Test
    void 超量规划只执行六个有效搜索和一份完整Logo设计() {
        ImageCollectionPlan plan = new ImageCollectionPlan();
        List<ImageCollectionPlan.ImageSearchTask> tasks = new ArrayList<>(Arrays.asList(null,
                new ImageCollectionPlan.ImageSearchTask("\u00a0\n"),
                new ImageCollectionPlan.ImageSearchTask("  Coffee\nSHOP "),
                new ImageCollectionPlan.ImageSearchTask("coffee shop")));
        IntStream.rangeClosed(1, 8).forEach(i -> tasks.add(new ImageCollectionPlan.ImageSearchTask("主题" + i)));
        plan.setContentImageTasks(tasks);
        String design = "品牌设计\n" + "完整的中文设计细节".repeat(20);
        plan.setLogoTasks(Arrays.asList(null, new ImageCollectionPlan.LogoTask(" "),
                new ImageCollectionPlan.LogoTask(design), new ImageCollectionPlan.LogoTask("不应执行")));
        when(planService.planImageCollection("官网")).thenReturn(plan);
        when(imageSearchTool.searchContentImages(anyString())).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            return IntStream.rangeClosed(1, 5).mapToObj(i -> image(ImageCategoryEnum.CONTENT,
                    query + i, "https://example.com/" + query.hashCode() + "/" + i)).toList();
        });
        when(logoGeneratorTool.generateLogos(eq(design), any(BooleanSupplier.class))).thenReturn(List.of(
                image(ImageCategoryEnum.LOGO, design, "https://example.com/logo1"),
                image(ImageCategoryEnum.LOGO, "超量Logo", "https://example.com/logo2")));

        String context = service.collectPromptContext("官网");

        var queries = ArgumentCaptor.forClass(String.class);
        verify(imageSearchTool, times(6)).searchContentImages(queries.capture());
        assertEquals(Set.of("Coffee SHOP", "主题1", "主题2", "主题3", "主题4", "主题5"),
                new HashSet<>(queries.getAllValues()));
        verify(logoGeneratorTool, times(1)).generateLogos(eq(design), any(BooleanSupplier.class));
        assertEquals(18, context.lines().filter(line -> line.startsWith("- 内容图片：")).count());
        assertEquals(1, context.lines().filter(line -> line.startsWith("- LOGO图片：")).count());
        assertFalse(context.contains("logo2"));
        for (String query : queries.getAllValues()) {
            assertFalse(context.contains("https://example.com/" + query.hashCode() + "/4"));
        }
        assertTrue(context.indexOf("Coffee SHOP1") < context.indexOf("主题11"));
        assertTrue(context.indexOf("主题53") < context.indexOf("- LOGO图片："));
        assertEquals(design, plan.getLogoTasks().get(2).description());
    }

    @Test
    void 服务按任务去重限额后再跨任务去重且不补搜() {
        ImageCollectionPlan plan = new ImageCollectionPlan();
        plan.setContentImageTasks(List.of(new ImageCollectionPlan.ImageSearchTask("主题一"),
                new ImageCollectionPlan.ImageSearchTask("主题二")));
        when(planService.planImageCollection("网站")).thenReturn(plan);
        when(imageSearchTool.searchContentImages("主题一")).thenReturn(Arrays.asList(null,
                image(ImageCategoryEnum.CONTENT, "空地址", " "),
                image(ImageCategoryEnum.LOGO, "搜索不应返回Logo", "https://example.com/wrong-category"),
                image(ImageCategoryEnum.CONTENT, "首次说明", "https://example.com/shared"),
                image(ImageCategoryEnum.CONTENT, "重复说明", "https://example.com/shared"),
                image(ImageCategoryEnum.CONTENT, "照片A", "https://example.com/a"),
                image(ImageCategoryEnum.CONTENT, "照片B", "https://example.com/b"),
                image(ImageCategoryEnum.CONTENT, "超额一", "https://example.com/extra1")));
        when(imageSearchTool.searchContentImages("主题二")).thenReturn(List.of(
                image(ImageCategoryEnum.CONTENT, "第二任务重复说明", "https://example.com/shared"),
                image(ImageCategoryEnum.CONTENT, "照片C", "https://example.com/c"),
                image(ImageCategoryEnum.CONTENT, "照片D", "https://example.com/d"),
                image(ImageCategoryEnum.CONTENT, "超额二", "https://example.com/extra2")));

        String context = service.collectPromptContext("网站");

        assertEquals(5, context.lines().filter(line -> line.startsWith("- 内容图片：")).count());
        assertTrue(context.contains("首次说明"));
        assertFalse(context.contains("重复说明"));
        assertFalse(context.contains("wrong-category"));
        assertFalse(context.contains("extra"));
        verify(imageSearchTool, times(2)).searchContentImages(anyString());
        verifyNoInteractions(logoGeneratorTool);
    }

    @Test
    void 两个入口的连续调用不共享去重状态() {
        ImageCollectionPlan plan = new ImageCollectionPlan();
        plan.setContentImageTasks(List.of(new ImageCollectionPlan.ImageSearchTask("照片")));
        when(planService.planImageCollection("网站")).thenReturn(plan);
        when(imageSearchTool.searchContentImages("照片")).thenReturn(List.of(
                image(ImageCategoryEnum.CONTENT, "照片", "https://example.com/photo")));

        String context = service.collectPromptContext("网站");
        String enhanced = service.enhancePrompt("网站");

        assertTrue(context.contains("https://example.com/photo"));
        assertEquals("网站" + context, enhanced);
        verify(imageSearchTool, times(2)).searchContentImages("照片");
    }

    @Test
    void 无有效搜索和Logo任务时两个入口均不调用工具() {
        ImageCollectionPlan plan = new ImageCollectionPlan();
        plan.setContentImageTasks(Arrays.asList(null, new ImageCollectionPlan.ImageSearchTask(null),
                new ImageCollectionPlan.ImageSearchTask("\u00a0\n")));
        plan.setLogoTasks(Arrays.asList(null, new ImageCollectionPlan.LogoTask(null),
                new ImageCollectionPlan.LogoTask("\u3000")));
        when(planService.planImageCollection("无图网站")).thenReturn(plan);

        assertEquals("", service.collectPromptContext("无图网站"));
        assertEquals("无图网站", service.enhancePrompt("无图网站"));
        verifyNoInteractions(imageSearchTool, logoGeneratorTool);
    }

    private ImageResource image(ImageCategoryEnum category, String description, String url) {
        return ImageResource.builder().category(category).description(description).url(url).build();
    }
}
