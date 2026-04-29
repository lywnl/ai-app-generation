package dev.langchain4j.model.openai.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;

import java.util.Locale;

/**
 * Overrides langchain4j-open-ai internal Json helper to inject DeepSeek compatibility fields.
 */
class Json {

    static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES);

    private static final String DEEPSEEK_V4_FLASH_MODEL_KEYWORD = "deepseek-v4-flash";

    static String toJson(Object o) {
        try {
            String json = OBJECT_MAPPER.writeValueAsString(o);
            if (o instanceof ChatCompletionRequest request) {
                return withDeepSeekThinkingDisabledIfNeeded(request, json);
            }
            return json;
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private static String withDeepSeekThinkingDisabledIfNeeded(ChatCompletionRequest request, String json)
            throws JsonProcessingException {
        if (!isDeepSeekV4Flash(request.model())) {
            return json;
        }

        JsonNode rootNode = OBJECT_MAPPER.readTree(json);
        if (!(rootNode instanceof ObjectNode objectNode)) {
            return json;
        }

        if (!objectNode.has("thinking")) {
            ObjectNode thinkingNode = OBJECT_MAPPER.createObjectNode();
            thinkingNode.put("type", "disabled");
            objectNode.set("thinking", thinkingNode);
        }
        objectNode.remove("reasoning_effort");
        return OBJECT_MAPPER.writeValueAsString(objectNode);
    }

    private static boolean isDeepSeekV4Flash(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return false;
        }
        return modelName.toLowerCase(Locale.ROOT).contains(DEEPSEEK_V4_FLASH_MODEL_KEYWORD);
    }

    static <T> T fromJson(String json, Class<T> clazz) {
        try {
            return OBJECT_MAPPER.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}

