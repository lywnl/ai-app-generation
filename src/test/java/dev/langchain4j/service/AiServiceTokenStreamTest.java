package dev.langchain4j.service;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiServiceTokenStreamTest {

    @Test
    void cancelBeforeStartDoesNotPublishRetrievedContentsOrStartModel() {
        AiServiceContext context = new AiServiceContext(Object.class);
        AtomicInteger modelCalls = new AtomicInteger();
        context.streamingChatModel = new StreamingChatModel() {
            @Override
            public void doChat(
                    dev.langchain4j.model.chat.request.ChatRequest request,
                    dev.langchain4j.model.chat.response.StreamingChatResponseHandler handler) {
                modelCalls.incrementAndGet();
            }
        };
        AiServiceTokenStream stream = new AiServiceTokenStream(
                AiServiceTokenStreamParameters.builder()
                        .messages(List.of(UserMessage.from("test")))
                        .toolSpecifications(List.of())
                        .toolExecutors(Map.of())
                        .retrievedContents(List.of())
                        .context(context)
                        .memoryId("memory")
                        .methodKey("method")
                        .build());
        AtomicInteger contentCalls = new AtomicInteger();
        stream.onPartialResponse(partial -> { })
                .onRetrieved(contents -> contentCalls.incrementAndGet())
                .ignoreErrors();

        stream.cancel();
        stream.start();

        assertEquals(0, contentCalls.get());
        assertEquals(0, modelCalls.get());
    }
}
