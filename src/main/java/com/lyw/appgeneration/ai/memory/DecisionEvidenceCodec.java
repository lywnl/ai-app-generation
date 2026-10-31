package com.lyw.appgeneration.ai.memory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 独立字段的严格版本解码，不能从 memoryMessage 或模型文字补造证据。 */
public final class DecisionEvidenceCodec {
    private static final int MAX_JSON_BYTES = 16 * 1024 * 1024 - 1;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private DecisionEvidenceCodec() { }

    public static DecisionEvidence decode(String json, long userHistoryId, String turnId) {
        if (json == null) return DecisionEvidence.empty(userHistoryId, turnId);
        try {
            if (json.isBlank() || json.length() > MAX_JSON_BYTES) throw new IllegalArgumentException();
            DecisionEvidence evidence = MAPPER.readValue(json, DecisionEvidence.class);
            if (evidence == null || evidence.userHistoryId() != userHistoryId || !evidence.turnId().equals(turnId)) {
                throw new IllegalArgumentException();
            }
            return evidence;
        } catch (Exception exception) {
            throw new IllegalArgumentException("决策证据格式或回合归属无效");
        }
    }

    public static DecisionEvidence decode(String json, long userHistoryId) {
        if (json == null) return null;
        try {
            var evidence = MAPPER.readValue(json, DecisionEvidence.class);
            if (evidence == null || evidence.userHistoryId() != userHistoryId) throw new IllegalArgumentException();
            return evidence;
        } catch (Exception exception) {
            throw new IllegalArgumentException("决策证据格式或持久来源无效");
        }
    }

    public static String encode(DecisionEvidence evidence) {
        try {
            String json = MAPPER.writeValueAsString(evidence);
            if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_JSON_BYTES) throw new IllegalArgumentException();
            return json;
        } catch (Exception exception) {
            throw new IllegalArgumentException("决策证据编码失败或超过存储上限");
        }
    }
}
