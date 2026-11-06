package com.lyw.appgeneration.service.rag.model;

import com.lyw.appgeneration.constants.RagConstants;
import java.util.List;

/**
 * Vue 工程检索上下文。
 *
 * @param skeleton 最终工程骨架；资料不可用、已有工程无候选或离线基线未命中时可为空
 * @param features 按相关性选取的功能片段，受首轮共享上限约束
 * @param catalogVersion 当前目录版本；目录不可用时为空
 * @param degraded 是否发生召回、重排或基础骨架降级
 */
public record VueRagContext(
        TemplateDoc skeleton,
        List<TemplateDoc> features,
        String catalogVersion,
        boolean degraded
) {

    public VueRagContext {
        List<TemplateDoc> safeFeatures = features == null ? List.of() : features;
        features = List.copyOf(safeFeatures.stream().limit(RagConstants.VUE_INITIAL_FEATURE_TOP_K).toList());
    }

    public static VueRagContext unavailable() {
        return new VueRagContext(null, List.of(), null, true);
    }
}
