package com.lyw.appgeneration.ai.image;

import com.lyw.appgeneration.ai.image.model.ImageCategoryEnum;
import com.lyw.appgeneration.ai.image.model.ImageResource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

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

    @Test
    void 长描述按码点截断且URL和原资源保持完整() {
        String description = "\u3000\n" + "图".repeat(78) + "🌄🙂更多描述  ";
        String url = "https://example.com/photo?signature=" + "abc%2F".repeat(40) + "&width=800";
        ImageResource source = ImageResource.builder().category(ImageCategoryEnum.LOGO)
                .description(description).url(url).build();

        String context = builder.buildContext(List.of(source));

        assertTrue(context.contains("- LOGO图片：" + "图".repeat(78) + "🌄…（" + url + "）"));
        assertEquals(1, context.lines().filter(line -> line.startsWith("- ")).count());
        assertEquals(description, source.getDescription());
        assertEquals(url, source.getUrl());
    }

    @Test
    void 直接构建也限制十八张搜索图和一张Logo并去重() {
        List<ImageResource> images = new ArrayList<>(Arrays.asList(null,
                ImageResource.builder().description("未知类型").url("https://example.com/invalid").build()));
        images.add(ImageResource.builder().category(ImageCategoryEnum.CONTENT)
                .description("第一份").url(" https://example.com/1?width=1 ").build());
        images.add(ImageResource.builder().category(ImageCategoryEnum.CONTENT)
                .description("重复说明").url("https://example.com/1?width=1").build());
        images.add(ImageResource.builder().category(ImageCategoryEnum.CONTENT)
                .description("另一尺寸").url("https://example.com/1?width=2").build());
        IntStream.rangeClosed(2, 21).forEach(i -> images.add(ImageResource.builder()
                .category(ImageCategoryEnum.CONTENT).description("图" + i).url("https://example.com/" + i).build()));
        IntStream.rangeClosed(1, 2).forEach(i -> images.add(ImageResource.builder()
                .category(ImageCategoryEnum.LOGO).description("品牌" + i).url("https://example.com/logo" + i).build()));

        String context = builder.buildContext(images);

        assertEquals(18, context.lines().filter(line -> line.startsWith("- 内容图片：")).count());
        assertEquals(1, context.lines().filter(line -> line.startsWith("- LOGO图片：")).count());
        assertTrue(context.contains("https://example.com/1?width=1"));
        assertTrue(context.contains("https://example.com/1?width=2"));
        assertFalse(context.contains("重复说明"));
        assertFalse(context.contains("https://example.com/18"));
        assertFalse(context.contains("logo2"));
    }

    @Test
    void 仅有无效资源时不追加素材标题() {
        List<ImageResource> images = Arrays.asList(null,
                ImageResource.builder().category(ImageCategoryEnum.CONTENT).url("\u00a0").build(),
                ImageResource.builder().url("https://example.com/no-category").build());

        assertEquals("", builder.buildContext(images));
        assertEquals("原始提示", builder.build("原始提示", images));
    }

    @Test
    void 短描述合并空白且空描述不输出null() {
        String context = builder.buildContext(List.of(
                ImageResource.builder().category(ImageCategoryEnum.CONTENT)
                        .description("  山\n\t\u00a0 水  ").url("https://example.com/1").build(),
                ImageResource.builder().category(ImageCategoryEnum.CONTENT)
                        .url("https://example.com/2").build()));

        assertTrue(context.contains("内容图片：山 水（https://example.com/1）"));
        assertTrue(context.contains("内容图片：（https://example.com/2）"));
        assertFalse(context.contains("null"));
    }
}
