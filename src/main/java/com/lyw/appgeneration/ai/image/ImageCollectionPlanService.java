package com.lyw.appgeneration.ai.image;

import com.lyw.appgeneration.ai.image.model.ImageCollectionPlan;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 图片收集规划服务
 */
public interface ImageCollectionPlanService {

    @SystemMessage(fromResource = "prompt/image-collection-plan-system-prompt.txt")
    ImageCollectionPlan planImageCollection(@UserMessage String userPrompt);
}
