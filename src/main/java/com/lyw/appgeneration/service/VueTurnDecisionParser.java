package com.lyw.appgeneration.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.core.handler.VueTurnDecision;
import com.lyw.appgeneration.core.handler.VueTurnMode;

/** 模式错误使普通请求失败，资源字段错误仅降级对应字段。 */
public final class VueTurnDecisionParser {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private VueTurnDecisionParser() {
    }

    public static ParsedDecision parse(String response, boolean selectedElement) {
        if (response == null || response.isBlank()) {
            throw new VueTurnModeRoutingException("分类模型返回空结果");
        }
        JsonNode root;
        try {
            root = JSON.readTree(response);
        } catch (JsonProcessingException exception) {
            // Jackson 异常可能包含响应片段，不放入日志或前端异常链。
            throw new VueTurnModeRoutingException("分类模型返回无效 JSON");
        }
        if (root == null || !root.isObject()) {
            throw new VueTurnModeRoutingException("分类模型未返回 JSON 对象");
        }
        JsonNode modeValue = root.get("mode");
        if (modeValue == null || !modeValue.isTextual()
                || !("READ_ONLY".equals(modeValue.textValue())
                || "MUTATION_REQUIRED".equals(modeValue.textValue()))) {
            throw new VueTurnModeRoutingException("分类模型返回无效模式");
        }
        VueTurnMode mode = selectedElement ? VueTurnMode.MUTATION_REQUIRED
                : VueTurnMode.valueOf(modeValue.textValue());
        String rag = flag(root.get("needRag"));
        String images = flag(root.get("needImageSearch"));
        return new ParsedDecision(new VueTurnDecision(mode, "true".equals(rag),
                "true".equals(images)), rag, images,
                reason("needRag", rag) + reason("needImageSearch", images));
    }

    private static String flag(JsonNode value) {
        if (value == null) return "MISSING";
        if (value.isNull()) return "NULL";
        return value.isBoolean() ? Boolean.toString(value.booleanValue()) : "INVALID_TYPE";
    }

    private static String reason(String field, String value) {
        return "true".equals(value) || "false".equals(value) ? "" : field + "=" + value + ";";
    }

    public record ParsedDecision(VueTurnDecision decision, String rawRag, String rawImages,
                                 String degradationReason) {
    }
}
