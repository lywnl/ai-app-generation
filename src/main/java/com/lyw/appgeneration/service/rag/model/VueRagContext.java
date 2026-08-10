package com.lyw.appgeneration.service.rag.model;

import java.util.List;

/**
 * Vue 工程检索上下文。
 *
 * @param skeleton 最终工程骨架；仅目录不可用时为空
 * @param features 最多四个兼容功能片段
 * @param catalogVersion 当前目录版本；目录不可用时为空
 * @param degraded 是否发生召回、重排或基础骨架降级
 */
public record VueRagContext(
        TemplateDoc skeleton,
        List<TemplateDoc> features,
        String catalogVersion,
        boolean degraded
) {

    private static final int MAX_FEATURES = 4;

    public VueRagContext {
        List<TemplateDoc> safeFeatures = features == null ? List.of() : features;
        features = List.copyOf(safeFeatures.stream().limit(MAX_FEATURES).toList());
    }

    public static VueRagContext unavailable() {
        return new VueRagContext(null, List.of(), null, true);
    }
}
