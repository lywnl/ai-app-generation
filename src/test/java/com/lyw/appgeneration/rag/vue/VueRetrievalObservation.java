package com.lyw.appgeneration.rag.vue;

import java.util.List;

/**
 * 一条真实检索结果及其可选错误信息。
 */
public record VueRetrievalObservation(
        VueEvalCase evalCase,
        String retrievedSkeletonId,
        List<String> retrievedFeatureIds,
        String error
) {

    public VueRetrievalObservation {
        retrievedFeatureIds = retrievedFeatureIds == null
                ? List.of()
                : List.copyOf(retrievedFeatureIds);
    }
}
