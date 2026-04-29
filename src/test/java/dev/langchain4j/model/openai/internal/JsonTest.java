package dev.langchain4j.model.openai.internal;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    @Test
    void shouldInjectThinkingDisabledForDeepSeekV4Flash() {
        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model("deepseek-v4-flash")
                .messages(List.of(UserMessage.from("hello")))
                .reasoningEffort("high")
                .build();

        String payload = Json.toJson(request);
        JsonNode root = Json.fromJson(payload, JsonNode.class);

        assertTrue(root.has("thinking"));
        assertEquals("disabled", root.path("thinking").path("type").asText());
        assertFalse(root.has("reasoning_effort"));
    }

    @Test
    void shouldNotInjectThinkingForOtherModels() {
        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model("deepseek-chat")
                .messages(List.of(UserMessage.from("hello")))
                .build();

        String payload = Json.toJson(request);
        JsonNode root = Json.fromJson(payload, JsonNode.class);

        assertFalse(root.has("thinking"));
    }
}

