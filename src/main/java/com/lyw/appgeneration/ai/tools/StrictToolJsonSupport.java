package com.lyw.appgeneration.ai.tools;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/** 工具协议进入语义解析前的标准 JSON 词法边界。 */
final class StrictToolJsonSupport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(
            JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private StrictToolJsonSupport() {
    }

    static void requireObject(String rawJson) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(rawJson);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("工具结果必须是标准 JSON 对象");
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("工具结果不是标准 JSON 对象", exception);
        }
    }
}
