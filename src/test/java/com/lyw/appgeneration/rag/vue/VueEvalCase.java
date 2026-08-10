package com.lyw.appgeneration.rag.vue;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Vue 双层检索的一条人工标注用例。
 */
public record VueEvalCase(
        @JsonProperty("queryId") String queryId,
        @JsonProperty("query") String query,
        @JsonProperty("queryStyle") String queryStyle,
        @JsonProperty("expectedSkeletonIds") List<String> expectedSkeletonIds,
        @JsonProperty("expectedFeatureIds") List<String> expectedFeatureIds
) {

    public VueEvalCase {
        expectedSkeletonIds = expectedSkeletonIds == null
                ? List.of()
                : List.copyOf(expectedSkeletonIds);
        expectedFeatureIds = expectedFeatureIds == null
                ? List.of()
                : List.copyOf(expectedFeatureIds);
    }
}
