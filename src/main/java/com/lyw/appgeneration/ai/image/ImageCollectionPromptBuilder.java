package com.lyw.appgeneration.ai.image;

import cn.hutool.core.collection.CollUtil;
import com.lyw.appgeneration.ai.image.model.ImageResource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把收集到的图片列表拼接到原始提示词末尾,生成增强提示词
 */
@Component
public class ImageCollectionPromptBuilder {

    public String build(String originalPrompt, List<ImageResource> images) {
        if (CollUtil.isEmpty(images)) {
            return originalPrompt;
        }
        StringBuilder sb = new StringBuilder(originalPrompt);
        sb.append("\n\n## 可用素材资源\n");
        sb.append("请在生成网站使用以下图片资源，将这些图片合理地嵌入到网站的相应位置中。\n");
        for (ImageResource img : images) {
            sb.append("- ")
                    .append(img.getCategory().getText())
                    .append("：")
                    .append(img.getDescription())
                    .append("（")
                    .append(img.getUrl())
                    .append("）\n");
        }
        return sb.toString();
    }
}
