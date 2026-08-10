package com.lyw.appgeneration.rag.build;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 一条高成本真实 Vue 生成与构建用例。
 */
public record VueGenerationBuildCase(
        @JsonProperty("caseId") String caseId,
        @JsonProperty("category") String category,
        @JsonProperty("prompt") String prompt
) {

    @JsonCreator
    public static VueGenerationBuildCase fromJson(
            @JsonProperty("caseId") String caseId,
            @JsonProperty("appId") Long ignoredAppId,
            @JsonProperty("category") String category,
            @JsonProperty("prompt") String prompt) {
        return new VueGenerationBuildCase(caseId, category, prompt);
    }
}
