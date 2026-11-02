package com.lyw.appgeneration.ai.image.tools;

import cn.hutool.json.JSONUtil;
import com.lyw.appgeneration.ai.image.ImageCollectionPlanService;
import com.lyw.appgeneration.ai.image.ImageCollectionPromptBuilder;
import com.lyw.appgeneration.ai.image.ImageCollectionService;
import com.lyw.appgeneration.ai.image.model.ImageCategoryEnum;
import com.lyw.appgeneration.ai.image.model.ImageCollectionPlan;
import com.lyw.appgeneration.ai.image.model.ImageResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImageCollectionHttpIntegrationTest {
    private ImageSearchHttpStub server;
    private ExecutorService executor;
    private ImageCollectionPlanService planner;
    private LogoGeneratorTool logos;
    private ImageCollectionService service;

    @BeforeEach
    void start() throws IOException {
        server = new ImageSearchHttpStub();
        executor = Executors.newFixedThreadPool(4);
        planner = mock(ImageCollectionPlanService.class);
        logos = mock(LogoGeneratorTool.class);
        service = new ImageCollectionService(planner, server.tool(), logos, executor,
                new ImageCollectionPromptBuilder(), 3);
    }

    @AfterEach
    void close() throws InterruptedException {
        executor.shutdownNow();
        try {
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        } finally {
            server.close();
        }
    }

    @Test
    void 真实收集链路只发六次三张请求并交付十八张搜索图和一张Logo() {
        ImageCollectionPlan plan = new ImageCollectionPlan();
        var tasks = new ArrayList<>(Arrays.asList(null,
                new ImageCollectionPlan.ImageSearchTask("  "),
                new ImageCollectionPlan.ImageSearchTask(" topic1 "),
                new ImageCollectionPlan.ImageSearchTask("TOPIC1")));
        IntStream.rangeClosed(2, 8).forEach(i -> tasks.add(new ImageCollectionPlan.ImageSearchTask("topic" + i)));
        plan.setContentImageTasks(tasks);
        String design = "中文品牌\n" + "完整设计细节🌄".repeat(30);
        plan.setLogoTasks(Arrays.asList(null, new ImageCollectionPlan.LogoTask("\u00a0"),
                new ImageCollectionPlan.LogoTask(design), new ImageCollectionPlan.LogoTask("第二个Logo")));
        when(planner.planImageCollection("生成中文官网")).thenReturn(plan);
        when(logos.generateLogos(eq(design), any(BooleanSupplier.class))).thenReturn(List.of(
                logo(design, "https://example.com/logo.png?sig=%2F&width=512"),
                logo("超量Logo", "https://example.com/extra-logo.png")));
        // 提供方故意忽略 per_page，返回旧规模的 12 张，校验客户端也会限制。
        server.respond(parameters -> JSONUtil.toJsonStr(Map.of("photos", IntStream.rangeClosed(1, 12)
                .mapToObj(i -> Map.of("alt", " 配图\n\t" + "图片说明🌄".repeat(30),
                        "src", Map.of("medium", photoUrl(parameters.get("query"), i))))
                .toList())));

        String context = service.collectPromptContext("生成中文官网");

        assertEquals(6, server.requests().size());
        assertEquals(Set.of("topic1", "topic2", "topic3", "topic4", "topic5", "topic6"),
                server.requests().stream().map(request -> request.parameters().get("query")).collect(Collectors.toSet()));
        assertTrue(server.requests().stream().allMatch(request -> "3".equals(request.parameters().get("per_page"))
                && "1".equals(request.parameters().get("page"))));
        assertEquals(18, context.lines().filter(line -> line.startsWith("- 内容图片：")).count());
        assertEquals(1, context.lines().filter(line -> line.startsWith("- LOGO图片：")).count());
        for (int topic = 1; topic <= 6; topic++) {
            for (int image = 1; image <= 3; image++) {
                assertTrue(context.contains(photoUrl("topic" + topic, image)));
            }
            assertFalse(context.contains(photoUrl("topic" + topic, 4)));
        }
        context.lines().filter(line -> line.startsWith("- ")).forEach(line -> {
            String description = line.substring(line.indexOf('：') + 1, line.indexOf('（'));
            assertEquals(80, description.codePointCount(0, description.length()));
            assertTrue(description.endsWith("…"));
        });
        assertTrue(context.contains("https://example.com/logo.png?sig=%2F&width=512"));
        assertFalse(context.contains("extra-logo"));
        assertFalse(context.contains(design));
        verify(logos, times(1)).generateLogos(eq(design), any(BooleanSupplier.class));
        assertTrue(context.indexOf(photoUrl("topic6", 3)) < context.indexOf("- LOGO图片："));
    }

    @Test
    void 真实HTTP失败和Logo失败不丢弃去重素材且后续调用仍能收集() {
        ImageCollectionPlan plan = new ImageCollectionPlan();
        plan.setContentImageTasks(List.of(new ImageCollectionPlan.ImageSearchTask("office"),
                new ImageCollectionPlan.ImageSearchTask("team"), new ImageCollectionPlan.ImageSearchTask("broken")));
        plan.setLogoTasks(List.of(new ImageCollectionPlan.LogoTask("品牌")));
        when(planner.planImageCollection("生成中文官网")).thenReturn(plan);
        when(logos.generateLogos(eq("品牌"), any(BooleanSupplier.class))).thenThrow(new IllegalStateException("测试失败"));
        server.respond(parameters -> {
            String query = parameters.get("query");
            if ("broken".equals(query)) {
                return "invalid-response";
            }
            return JSONUtil.toJsonStr(Map.of("photos", List.of(
                    Map.of("alt", "共享图片", "src", Map.of("medium", "https://example.com/shared")),
                    Map.of("alt", "局部图片一", "src", Map.of("medium", photoUrl(query, 1))),
                    Map.of("alt", "局部图片二", "src", Map.of("medium", photoUrl(query, 2))))));
        });

        String enhanced = service.enhancePrompt("生成中文官网");
        String context = service.collectPromptContext("生成中文官网");

        assertEquals("生成中文官网" + context, enhanced);
        assertEquals(5, context.lines().filter(line -> line.startsWith("- 内容图片：")).count());
        assertEquals(1, context.lines().filter(line -> line.contains("https://example.com/shared")).count());
        assertFalse(context.contains("- LOGO图片："));
        assertEquals(6, server.requests().size());
        verify(logos, times(2)).generateLogos(eq("品牌"), any(BooleanSupplier.class));
    }

    private static String photoUrl(String query, int index) {
        return "https://example.com/" + query + "/" + index + ".jpg?fit=crop&signature=a%2Fb";
    }

    private static ImageResource logo(String description, String url) {
        return ImageResource.builder().category(ImageCategoryEnum.LOGO).description(description).url(url).build();
    }
}
