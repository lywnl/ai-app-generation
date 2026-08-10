package com.lyw.appgeneration.rag.build;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 独立真实生成构建用例集。
 */
public record VueGenerationBuildDataset(
        @JsonProperty("version") String version,
        @JsonProperty("cases") List<VueGenerationBuildCase> cases
) {

    public VueGenerationBuildDataset {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public static VueGenerationBuildDataset load(String resource, ObjectMapper objectMapper)
            throws IOException {
        try (InputStream input = VueGenerationBuildDataset.class
                .getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("真实生成构建用例不存在: " + resource);
            }
            return objectMapper.readValue(input, VueGenerationBuildDataset.class);
        }
    }
}
