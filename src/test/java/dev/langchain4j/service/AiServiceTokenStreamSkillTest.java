package dev.langchain4j.service;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiServiceTokenStreamSkillTest {

    @Test
    void 回合临时Skill消息进入模型请求但不改变原始消息() {
        AiServiceContext context = new AiServiceContext(Object.class);
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        context.streamingChatModel = new StreamingChatModel() {
            @Override
            public void doChat(
                    ChatRequest request,
                    StreamingChatResponseHandler handler) {
                captured.set(request);
            }
        };
        AiServiceTokenStream stream = new AiServiceTokenStream(
                AiServiceTokenStreamParameters.builder()
                        .messages(List.of(UserMessage.from("生成后台")))
                        .toolSpecifications(List.of())
                        .toolExecutors(Map.of())
                        .retrievedContents(List.of())
                        .context(context)
                        .memoryId("skill-test")
                        .methodKey("method")
                        .build());
        stream.turnTransientMessages(List.of(
                SystemMessage.from("Vue 前端设计 Skill")));

        stream.onPartialResponse(ignored -> { })
                .ignoreErrors()
                .start();

        assertEquals(2, captured.get().messages().size());
        ChatMessage original = captured.get().messages().get(0);
        assertTrue(original instanceof UserMessage);
        assertTrue(((UserMessage) original).singleText().contains("生成后台"));
        assertTrue(captured.get().messages().get(1).toString()
                .contains("Vue 前端设计 Skill"));
    }

    @Test
    void 门禁请求包含Skill但活动记忆不包含Skill() {
        AiServiceContext context = new AiServiceContext(Object.class);
        AtomicReference<ModelRequestGate.Request> gateRequest =
                new AtomicReference<>();
        context.streamingChatModel = new StreamingChatModel() {
            @Override
            public void doChat(
                    ChatRequest request,
                    StreamingChatResponseHandler handler) {
            }
        };
        AiServiceTokenStream stream = new AiServiceTokenStream(
                AiServiceTokenStreamParameters.builder()
                        .messages(List.of(UserMessage.from("生成后台")))
                        .toolSpecifications(List.of())
                        .toolExecutors(Map.of())
                        .retrievedContents(List.of())
                        .context(context)
                        .memoryId("skill-gate-test")
                        .methodKey("method")
                        .build());
        stream.turnTransientMessages(List.of(
                SystemMessage.from("Vue 前端设计 Skill")));
        stream.modelRequestGate(request -> {
            gateRequest.set(request);
            List<ChatMessage> messages = new ArrayList<>(
                    request.latestMemory().get().messages());
            messages.addAll(request.transientMessages());
            return CompletableFuture.completedFuture(
                    new ModelRequestGate.Decision(
                            ModelRequestGate.Status.ALLOWED, messages, 2, ""));
        }, action -> {
            action.run();
            return true;
        });

        stream.onPartialResponse(ignored -> { })
                .ignoreErrors()
                .start();

        assertEquals(1, gateRequest.get().transientMessages().size());
        assertTrue(gateRequest.get().transientMessages().get(0).toString()
                .contains("Vue 前端设计 Skill"));
        assertEquals(1, gateRequest.get().latestMemory().get().messages().size());
        assertTrue(gateRequest.get().latestMemory().get().messages().get(0)
                .toString().contains("生成后台"));
    }
}
