package com.lyw.appgeneration.rag.vue;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 独立 Vue 双层检索评测集。
 */
public record VueEvalDataset(
        @JsonProperty("version") String version,
        @JsonProperty("queries") List<VueEvalCase> queries
) {

    public VueEvalDataset {
        queries = queries == null ? List.of() : List.copyOf(queries);
    }

    public static VueEvalDataset load(String classpathResource, ObjectMapper objectMapper)
            throws IOException {
        ClassLoader classLoader = VueEvalDataset.class.getClassLoader();
        try (InputStream input = classLoader.getResourceAsStream(classpathResource)) {
            if (input == null) {
                throw new IllegalStateException("Vue 评测集不存在: " + classpathResource);
            }
            return objectMapper.readValue(input, VueEvalDataset.class);
        }
    }
}
