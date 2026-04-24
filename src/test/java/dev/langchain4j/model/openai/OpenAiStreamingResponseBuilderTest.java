package dev.langchain4j.model.openai;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.internal.Json;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionChoice;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import dev.langchain4j.model.openai.internal.chat.Delta;
import dev.langchain4j.model.openai.internal.chat.FunctionCall;
import dev.langchain4j.model.openai.internal.chat.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiStreamingResponseBuilderTest {

    @Test
    void shouldRepairMalformedArgumentsInFinalToolRequest() {
        OpenAiStreamingResponseBuilder builder = new OpenAiStreamingResponseBuilder();

        builder.append(toolCallPartial(0, "call_1", "writeFile", "{\"relativeFilePath\":\"src/a.vue\" "));
        builder.append(toolCallPartial(0, null, null, "\"content\":\"hello\"}"));

        ChatResponse response = builder.build();
        assertNotNull(response);
        List<ToolExecutionRequest> requests = response.aiMessage().toolExecutionRequests();
        assertEquals(1, requests.size());
        assertJsonEquals("{\"relativeFilePath\":\"src/a.vue\",\"content\":\"hello\"}", requests.get(0).arguments());
    }

    @Test
    void shouldKeepOriginalArgumentsWhenUnrecoverable() {
        OpenAiStreamingResponseBuilder builder = new OpenAiStreamingResponseBuilder();

        String rawMalformed = "{\"content\": ???";
        builder.append(toolCallPartial(0, "call_1", "writeFile", rawMalformed));

        ChatResponse response = builder.build();
        assertNotNull(response);
        List<ToolExecutionRequest> requests = response.aiMessage().toolExecutionRequests();
        assertEquals(1, requests.size());
        assertEquals(rawMalformed, requests.get(0).arguments());
    }

    private static void assertJsonEquals(String expected, String actual) {
        assertEquals(Json.fromJson(expected, Object.class), Json.fromJson(actual, Object.class));
    }

    private static ChatCompletionResponse toolCallPartial(Integer index, String id, String name, String arguments) {
        FunctionCall functionCall = FunctionCall.builder()
                .name(name)
                .arguments(arguments)
                .build();
        ToolCall toolCall = ToolCall.builder()
                .index(index)
                .id(id)
                .function(functionCall)
                .build();
        Delta delta = Delta.builder()
                .toolCalls(List.of(toolCall))
                .build();
        ChatCompletionChoice choice = ChatCompletionChoice.builder()
                .index(0)
                .delta(delta)
                .build();
        return ChatCompletionResponse.builder()
                .choices(List.of(choice))
                .build();
    }
}
