package com.lyw.appgeneration.ai.image;

import com.lyw.appgeneration.ai.image.model.ImageCategoryEnum;
import com.lyw.appgeneration.ai.image.model.ImageCollectionPlan;
import com.lyw.appgeneration.ai.image.model.ImageResource;
import com.lyw.appgeneration.ai.image.tools.ImageSearchTool;
import com.lyw.appgeneration.ai.image.tools.LogoGeneratorTool;
import com.lyw.appgeneration.ai.image.tools.MermaidDiagramTool;
import com.lyw.appgeneration.ai.image.tools.UndrawIllustrationTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImageCollectionServiceTest {

    @Mock private ImageCollectionPlanService planService;
    @Mock private ImageSearchTool imageSearchTool;
    @Mock private UndrawIllustrationTool undrawIllustrationTool;
    @Mock private MermaidDiagramTool mermaidDiagramTool;
    @Mock private LogoGeneratorTool logoGeneratorTool;

    private ExecutorService executor;
    private ImageCollectionService service;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(4);
        service = new ImageCollectionService(
                planService,
                imageSearchTool,
                undrawIllustrationTool,
                mermaidDiagramTool,
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
        verifyNoInteractions(imageSearchTool, undrawIllustrationTool, mermaidDiagramTool, logoGeneratorTool);
    }

    @Test
    void enhancePrompt_oneToolFails_otherResultsAggregated() {
        ImageCollectionPlan plan = new ImageCollectionPlan();
        plan.setContentImageTasks(List.of(new ImageCollectionPlan.ImageSearchTask("风景")));
        plan.setLogoTasks(List.of(new ImageCollectionPlan.LogoTask("科技公司 logo")));
        when(planService.planImageCollection("科技站")).thenReturn(plan);
        when(imageSearchTool.searchContentImages("风景"))
                .thenThrow(new RuntimeException("Pexels down"));
        when(logoGeneratorTool.generateLogos("科技公司 logo")).thenReturn(List.of(
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
        when(logoGeneratorTool.generateLogos("slow-logo")).thenAnswer(inv -> {
            Thread.sleep(5_000);
            return List.of();
        });

        long start = System.currentTimeMillis();
        String result = service.enhancePrompt("站");
        long elapsed = System.currentTimeMillis() - start;

        assertEquals("站", result);
        assertTrue(elapsed < 4_000, "应在 2s 超时后返回,实际 " + elapsed + "ms");
    }
}
