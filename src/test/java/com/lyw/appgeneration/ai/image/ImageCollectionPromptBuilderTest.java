package com.lyw.appgeneration.ai.image;

import com.lyw.appgeneration.ai.image.model.ImageCategoryEnum;
import com.lyw.appgeneration.ai.image.model.ImageResource;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ImageCollectionPromptBuilderTest {

    private final ImageCollectionPromptBuilder builder = new ImageCollectionPromptBuilder();

    @Test
    void build_withEmptyList_returnsOriginalPrompt() {
        String result = builder.build("原始提示", Collections.emptyList());
        assertEquals("原始提示", result);
    }

    @Test
    void build_withNullList_returnsOriginalPrompt() {
        String result = builder.build("原始提示", null);
        assertEquals("原始提示", result);
    }

    @Test
    void build_withImages_appendsResourceSection() {
        List<ImageResource> images = List.of(
                ImageResource.builder()
                        .category(ImageCategoryEnum.CONTENT)
                        .description("风景")
                        .url("https://pexels.com/a.jpg")
                        .build(),
                ImageResource.builder()
                        .category(ImageCategoryEnum.LOGO)
                        .description("品牌 logo")
                        .url("https://cdn.com/logo.png")
                        .build()
        );
        String result = builder.build("原始提示", images);
        assertTrue(result.startsWith("原始提示"));
        assertTrue(result.contains("## 可用素材资源"));
        assertTrue(result.contains("内容图片：风景（https://pexels.com/a.jpg）"));
        assertTrue(result.contains("LOGO图片：品牌 logo（https://cdn.com/logo.png）"));
    }
}
