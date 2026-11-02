package com.lyw.appgeneration.ai.image;

import com.lyw.appgeneration.ai.image.model.ImageCategoryEnum;
import com.lyw.appgeneration.ai.image.model.ImageCollectionPlan.ImageSearchTask;
import com.lyw.appgeneration.ai.image.model.ImageCollectionPlan.LogoTask;
import com.lyw.appgeneration.ai.image.model.ImageResource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ImageCollectionPolicyTest {

    @Test
    void 先过滤空值和重复主题再按首现顺序取六个且不改变原任务() {
        var first = new ImageSearchTask("  Coffee\n\t SHOP  ");
        List<ImageSearchTask> tasks = new ArrayList<>(Arrays.asList(null,
                new ImageSearchTask(null), new ImageSearchTask("\u00a0\t"), first,
                new ImageSearchTask("coffee\u3000shop")));
        IntStream.rangeClosed(1, 8).forEach(i -> tasks.add(new ImageSearchTask("主题" + i)));

        List<ImageSearchTask> selected = ImageCollectionPolicy.selectSearchTasks(tasks);

        assertEquals(List.of("Coffee SHOP", "主题1", "主题2", "主题3", "主题4", "主题5"),
                selected.stream().map(ImageSearchTask::query).toList());
        assertEquals("  Coffee\n\t SHOP  ", first.query());
        assertEquals(selected, ImageCollectionPolicy.selectSearchTasks(tasks), "不同调用不能共用去重集合");
    }

    @Test
    void 只取第一个有效Logo且不裁剪生成设计() {
        var valid = new LogoTask("  中文品牌\n" + "详细设计".repeat(40));
        List<LogoTask> tasks = Arrays.asList(null, new LogoTask(null), new LogoTask(" \u00a0 "),
                valid, new LogoTask("第二份设计"));

        var selected = ImageCollectionPolicy.selectLogoTasks(tasks);

        assertEquals(1, selected.size());
        assertSame(valid, selected.getFirst());
        assertTrue(selected.getFirst().description().length() > 80);
    }

    @Test
    void 结果按类别限制为十八张搜索图和一张Logo() {
        List<ImageResource> images = new ArrayList<>(Arrays.asList(null,
                image(null, "未知类别", "https://example.com/unknown"),
                image(ImageCategoryEnum.CONTENT, "空地址", "\u3000\t")));
        IntStream.rangeClosed(1, 20).forEach(i -> images.add(
                image(ImageCategoryEnum.CONTENT, "照片" + i, "https://example.com/" + i)));
        images.add(image(ImageCategoryEnum.LOGO, "品牌1", "https://example.com/logo1"));
        images.add(image(ImageCategoryEnum.LOGO, "品牌2", "https://example.com/logo2"));

        var selected = ImageCollectionPolicy.limitContextResources(images);

        assertEquals(19, selected.size());
        assertEquals("照片18", selected.get(17).getDescription());
        assertEquals("品牌1", selected.getLast().getDescription());
    }

    @Test
    void URL去重保留首次结果与不同参数且不修改输入对象() {
        var first = image(ImageCategoryEnum.CONTENT, "原始说明", " \nhttps://example.com/a?w=1&sig=%2F \t");
        List<ImageResource> images = List.of(first,
                image(ImageCategoryEnum.CONTENT, "重复说明", "https://example.com/a?w=1&sig=%2F"),
                image(ImageCategoryEnum.LOGO, "同址Logo", "https://example.com/a?w=1&sig=%2F"),
                image(ImageCategoryEnum.CONTENT, "另一尺寸", "https://example.com/a?w=2&sig=%2F"));

        var selected = ImageCollectionPolicy.limitContextResources(images);

        assertEquals(2, selected.size());
        assertEquals("https://example.com/a?w=1&sig=%2F", selected.getFirst().getUrl());
        assertEquals("原始说明", selected.getFirst().getDescription());
        assertNotSame(first, selected.getFirst());
        assertEquals(" \nhttps://example.com/a?w=1&sig=%2F \t", first.getUrl());
        assertEquals(selected, ImageCollectionPolicy.limitContextResources(images));
    }

    @Test
    void 单任务结果去重后按对应类别取三张或一张() {
        List<ImageResource> images = new ArrayList<>();
        images.add(image(ImageCategoryEnum.LOGO, "品牌", "https://example.com/logo"));
        images.add(image(ImageCategoryEnum.LOGO, "另一品牌", "https://example.com/other-logo"));
        images.add(image(ImageCategoryEnum.CONTENT, "首张", "https://example.com/1"));
        images.add(image(ImageCategoryEnum.CONTENT, "重复", "https://example.com/1"));
        IntStream.rangeClosed(2, 5).forEach(i -> images.add(
                image(ImageCategoryEnum.CONTENT, "照片" + i, "https://example.com/" + i)));

        assertEquals(List.of("https://example.com/1", "https://example.com/2", "https://example.com/3"),
                ImageCollectionPolicy.limitSearchResults(images).stream().map(ImageResource::getUrl).toList());
        assertEquals(List.of(images.getFirst()), ImageCollectionPolicy.limitLogoResults(images));
    }

    @Test
    void 描述在八十码点内截断且保留完整emoji() {
        String atLimit = "图".repeat(79) + "🌄";
        assertEquals(atLimit, ImageCollectionPolicy.conciseDescription(atLimit));
        String shortened = ImageCollectionPolicy.conciseDescription("图".repeat(78) + "🌄🙂额外内容");
        assertEquals("图".repeat(78) + "🌄…", shortened);
        assertEquals(80, shortened.codePointCount(0, shortened.length()));
        assertEquals("云 海 远山", ImageCollectionPolicy.conciseDescription("\u3000云\n\t\u00a0 海  远山\u3000"));
        assertEquals("", ImageCollectionPolicy.conciseDescription(null));
    }

    @Test
    void 空输入不产生任务或资源() {
        assertTrue(ImageCollectionPolicy.selectSearchTasks(null).isEmpty());
        assertTrue(ImageCollectionPolicy.selectLogoTasks(null).isEmpty());
        assertTrue(ImageCollectionPolicy.limitContextResources(null).isEmpty());
        assertTrue(ImageCollectionPolicy.limitSearchResults(List.of()).isEmpty());
        assertTrue(ImageCollectionPolicy.limitLogoResults(List.of()).isEmpty());
    }

    private ImageResource image(ImageCategoryEnum category, String description, String url) {
        return ImageResource.builder().category(category).description(description).url(url).build();
    }
}
