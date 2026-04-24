package dev.langchain4j.service;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.ChatExecutor;
import dev.langchain4j.internal.Json;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AiServiceStreamingResponseHandlerTest {

    @Test
    void shouldSkipInvalidToolAndContinueWithValidTool() throws Exception {
        AiServiceContext context = new AiServiceContext(Object.class);
        CapturingStreamingChatModel capturingModel = new CapturingStreamingChatModel();
        context.streamingChatModel = capturingModel;

        java.util.concurrent.CopyOnWriteArrayList<ToolExecutionRequest> observedToolExecutions =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        ToolExecutor writeFileExecutor = (request, memoryId) -> {
            observedToolExecutions.add(request);
            return "ok";
        };

        AiServiceStreamingResponseHandler handler = newHandler(
                context,
                Map.of("writeFile", writeFileExecutor)
        );

        ToolExecutionRequest invalid = ToolExecutionRequest.builder()
                .id("call_invalid")
                .name("writeFile")
                .arguments("{\"relativeFilePath\":\"src/a.vue\", \"content\": ???}")
                .build();
        ToolExecutionRequest valid = ToolExecutionRequest.builder()
                .id("call_valid")
                .name("writeFile")
                .arguments("{\"relativeFilePath\":\"src/b.vue\" \"content\":\"hello\"}")
                .build();

        ChatResponse responseWithTools = ChatResponse.builder()
                .aiMessage(AiMessage.from(List.of(invalid, valid)))
                .metadata(ChatResponseMetadata.builder()
                        .tokenUsage(new TokenUsage())
                        .build())
                .build();

        handler.onCompleteResponse(responseWithTools);

        assertEquals(1, observedToolExecutions.size(), "Only valid/repaired tool call should be executed");
        assertEquals("call_valid", observedToolExecutions.get(0).id());
        assertJsonEquals("{\"relativeFilePath\":\"src/b.vue\",\"content\":\"hello\"}", observedToolExecutions.get(0).arguments());

        assertEquals(1, capturingModel.chatInvocations, "Handler should continue the chain with another chat request");
        assertNotNull(capturingModel.lastChatRequest);
    }

    private static AiServiceStreamingResponseHandler newHandler(
            AiServiceContext context,
            Map<String, ToolExecutor> toolExecutors) throws Exception {
        Constructor<AiServiceStreamingResponseHandler> ctor = AiServiceStreamingResponseHandler.class.getDeclaredConstructor(
                dev.langchain4j.guardrail.ChatExecutor.class,
                AiServiceContext.class,
                Object.class,
                java.util.function.Consumer.class,
                java.util.function.BiConsumer.class,
                java.util.function.BiConsumer.class,
                java.util.function.Consumer.class,
                java.util.function.Consumer.class,
                java.util.function.Consumer.class,
                dev.langchain4j.memory.ChatMemory.class,
                TokenUsage.class,
                List.class,
                Map.class,
                dev.langchain4j.guardrail.GuardrailRequestParams.class,
                Object.class
        );
        ctor.setAccessible(true);

        return ctor.newInstance(
                (ChatExecutor) new NoopChatExecutor(),
                context,
                "mem-1",
                (java.util.function.Consumer<String>) s -> {
                },
                (java.util.function.BiConsumer<Integer, ToolExecutionRequest>) (i, r) -> {
                },
                (java.util.function.BiConsumer<Integer, ToolExecutionRequest>) (i, r) -> {
                },
                (java.util.function.Consumer<dev.langchain4j.service.tool.ToolExecution>) toolExecution -> {
                },
                (java.util.function.Consumer<ChatResponse>) response -> {
                },
                (java.util.function.Consumer<Throwable>) throwable -> fail("Should not raise onError"),
                MessageWindowChatMemory.withMaxMessages(100),
                new TokenUsage(),
                List.<ToolSpecification>of(),
                toolExecutors,
                null,
                "method-1"
        );
    }

    private static class CapturingStreamingChatModel implements StreamingChatModel {

        private int chatInvocations;
        private ChatRequest lastChatRequest;

        @Override
        public void doChat(ChatRequest chatRequest, dev.langchain4j.model.chat.response.StreamingChatResponseHandler handler) {
            this.chatInvocations++;
            this.lastChatRequest = chatRequest;
            ChatResponse terminal = ChatResponse.builder()
                    .aiMessage(AiMessage.from("done"))
                    .metadata(ChatResponseMetadata.builder()
                            .tokenUsage(new TokenUsage())
                            .build())
                    .build();
            handler.onCompleteResponse(terminal);
        }
    }

    private static class NoopChatExecutor implements ChatExecutor {

        @Override
        public ChatResponse execute() {
            return null;
        }

        @Override
        public ChatResponse execute(List<dev.langchain4j.data.message.ChatMessage> messages) {
            return null;
        }
    }

    private static void assertJsonEquals(String expected, String actual) {
        assertEquals(Json.fromJson(expected, Object.class), Json.fromJson(actual, Object.class));
    }
}
