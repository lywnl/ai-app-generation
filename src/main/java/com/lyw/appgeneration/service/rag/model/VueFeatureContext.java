package com.lyw.appgeneration.service.rag.model;

import com.lyw.appgeneration.constants.RagConstants;
import java.util.List;

/** 后续功能参考独立于骨架，避免以空骨架触发初始化提示。 */
public record VueFeatureContext(List<TemplateDoc> features, String catalogVersion, boolean degraded) {
    public static final int MAX_FEATURES = RagConstants.VUE_FOLLOWUP_FEATURE_TOP_K;
    public VueFeatureContext {
        features = features == null ? List.of() : List.copyOf(features.stream().limit(MAX_FEATURES).toList());
    }
    public static VueFeatureContext unavailable() { return new VueFeatureContext(List.of(), null, true); }
}
