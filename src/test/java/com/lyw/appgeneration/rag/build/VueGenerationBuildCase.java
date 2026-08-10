package com.lyw.appgeneration.rag.build;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 一条高成本真实 Vue 生成与构建用例。
 */
public record VueGenerationBuildCase(
        @JsonProperty("caseId") String caseId,
        @JsonProperty("appId") long appId,
        @JsonProperty("category") String category,
        @JsonProperty("prompt") String prompt
) {
}
