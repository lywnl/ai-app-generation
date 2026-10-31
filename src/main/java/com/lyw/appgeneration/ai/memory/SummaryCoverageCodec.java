package com.lyw.appgeneration.ai.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** coverageVersion 由摘要记录携带；未知版本及损坏 JSON 均不能用于原文裁剪。 */
public final class SummaryCoverageCodec {

    public static final int LEGACY_VERSION = 1;
    public static final int CURRENT_VERSION = 2;
    private static final int MAX_JSON_CHARS = 4096;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private SummaryCoverageCodec() {
    }

    public static SummaryCoverage decode(int version, String json) {
        if (version != LEGACY_VERSION && version != CURRENT_VERSION) {
            throw new IllegalArgumentException("未知摘要覆盖版本");
        }
        if (json == null) {
            return null;
        }
        if (version != CURRENT_VERSION || json.isBlank() || json.length() > MAX_JSON_CHARS) {
            throw new IllegalArgumentException("摘要覆盖版本与内容不一致");
        }
        try {
            SummaryCoverage coverage = MAPPER.readValue(json, SummaryCoverage.class);
            if (coverage == null) {
                throw new IllegalArgumentException("摘要覆盖不能使用 JSON null");
            }
            return coverage;
        } catch (JsonProcessingException exception) {
            // 不把含原始材料的输入 JSON 写入异常或日志。
            throw new IllegalArgumentException("摘要覆盖 JSON 无效");
        }
    }

    public static String encode(SummaryCoverage coverage) {
        if (coverage == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(coverage);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("无法编码摘要覆盖");
        }
    }
}
