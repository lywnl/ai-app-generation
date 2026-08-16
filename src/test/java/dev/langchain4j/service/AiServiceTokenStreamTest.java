package dev.langchain4j.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class AiServiceTokenStreamTest {

    @Test
    void 首次请求必须使用门禁完成后的活动记忆而不是构造时旧快照()
            throws Exception {
        MutableChatMemory memory = new MutableChatMemory(7L);
        memory.add(UserMessage.from("旧上下文".repeat(10_000)));
        memory.add(AiMessage.from("旧回复"));
        AtomicReference<ChatRequest> capturedRequest = new AtomicReference<>();
        AtomicInteger modelCalls = new AtomicInteger();
        StreamingChatModel model = new StreamingChatModel() {
            @Override
            public void doChat(
                    ChatRequest request,
                    dev.langchain4j.model.chat.response
                            .StreamingChatResponseHandler handler) {
                capturedRequest.set(request);
                modelCalls.incrementAndGet();
            }
        };
        TestAiService service = AiServices.builder(TestAiService.class)
                .streamingChatModel(model)
                .chatMemoryProvider(ignored -> memory)
                .build();
        List<ChatMessage> compressedMessages = List.of(
                UserMessage.from("压缩摘要"),
                AiMessage.from("已读取摘要"),
                UserMessage.from("本轮问题"));
        try (ManagedModelRequestGate gate = new ManagedModelRequestGate(request -> {
            assertEquals(7L, request.memoryId());
            assertSame(memory, request.latestMemory().get());
            memory.replaceWith(compressedMessages);
            return CompletableFuture.completedFuture(
                    new ModelRequestGate.Decision(
                            ModelRequestGate.Status.ALLOWED,
                            request.latestMemory().get().messages(),
                            12_000,
                            ""));
        })) {
            TokenStream stream = service.chat(7L, "本轮问题");

            stream.modelRequestGate(gate, action -> {
                        action.run();
                        return true;
                    })
                    .onPartialResponse(ignored -> { })
                    .ignoreErrors()
                    .start();
            gate.awaitIdle();

            assertEquals(1, modelCalls.get());
            assertEquals(compressedMessages,
                    capturedRequest.get().messages());
        }
    }

    @Test
    void 首次请求等待门禁期间取消后晚到结果不得启动模型()
            throws Exception {
        MutableChatMemory memory = new MutableChatMemory(7L);
        memory.add(UserMessage.from("旧上下文"));
        AtomicInteger modelCalls = new AtomicInteger();
        StreamingChatModel model = new StreamingChatModel() {
            @Override
            public void doChat(
                    ChatRequest request,
                    dev.langchain4j.model.chat.response
                            .StreamingChatResponseHandler handler) {
                modelCalls.incrementAndGet();
            }
        };
        TestAiService service = AiServices.builder(TestAiService.class)
                .streamingChatModel(model)
                .chatMemoryProvider(ignored -> memory)
                .build();
        CompletableFuture<ModelRequestGate.Decision> preparation =
                new CompletableFuture<>();
        try (ManagedModelRequestGate gate =
                     new ManagedModelRequestGate(request -> preparation)) {
            TokenStream stream = service.chat(7L, "本轮问题");
            stream.modelRequestGate(
                            gate,
                            action -> {
                                action.run();
                                return true;
                            })
                    .onPartialResponse(ignored -> { })
                    .ignoreErrors()
                    .start();

            stream.cancel();
            preparation.complete(new ModelRequestGate.Decision(
                    ModelRequestGate.Status.ALLOWED,
                    memory.messages(),
                    10,
                    ""));
            gate.awaitIdle();

            assertEquals(0, modelCalls.get());
        }
    }

    @Test
    void 首次门禁硬拒绝必须保留拒绝类型与阶段且不调用模型()
            throws Exception {
        MutableChatMemory memory = new MutableChatMemory(7L);
        memory.add(UserMessage.from("超长上下文"));
        AtomicInteger modelCalls = new AtomicInteger();
        StreamingChatModel model = new StreamingChatModel() {
            @Override
            public void doChat(
                    ChatRequest request,
                    dev.langchain4j.model.chat.response
                            .StreamingChatResponseHandler handler) {
                modelCalls.incrementAndGet();
            }
        };
        TestAiService service = AiServices.builder(TestAiService.class)
                .streamingChatModel(model)
                .chatMemoryProvider(ignored -> memory)
                .build();
        AtomicReference<Throwable> error = new AtomicReference<>();
        try (ManagedModelRequestGate gate = new ManagedModelRequestGate(
                request -> CompletableFuture.completedFuture(
                        new ModelRequestGate.Decision(
                                ModelRequestGate.Status.HARD_LIMIT_REJECTED,
                                request.latestMemory().get().messages(),
                                37_785,
                                "对话上下文过长，请开启新会话后重试")))) {
            TokenStream stream = service.chat(7L, "本轮问题");

            stream.modelRequestGate(gate, action -> {
                        action.run();
                        return true;
                    })
                    .onPartialResponse(ignored -> { })
                    .onError(error::set)
                    .start();
            gate.awaitIdle();

            assertEquals(0, modelCalls.get());
            ModelRequestGateException rejection = assertInstanceOf(
                    ModelRequestGateException.class, error.get());
            assertEquals(ModelRequestGateException.Stage.INITIAL,
                    rejection.stage());
            assertEquals(ModelRequestGate.Status.HARD_LIMIT_REJECTED,
                    rejection.status());
            assertEquals("对话上下文过长，请开启新会话后重试",
                    rejection.getMessage());
        }
    }

    @Test
    void cancelBeforeStartDoesNotPrepareGatePublishContentsOrStartModel() {
        AiServiceContext context = new AiServiceContext(Object.class);
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger gateCalls = new AtomicInteger();
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
        stream.modelRequestGate(request -> {
                    gateCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            new ModelRequestGate.Decision(
                                    ModelRequestGate.Status.ALLOWED,
                                    request.latestMemory().get().messages(),
                                    1,
                                    ""));
                }, action -> {
                    action.run();
                    return true;
                })
                .onPartialResponse(partial -> { })
                .onRetrieved(contents -> contentCalls.incrementAndGet())
                .ignoreErrors();

        stream.cancel();
        stream.start();

        assertEquals(0, gateCalls.get());
        assertEquals(0, contentCalls.get());
        assertEquals(0, modelCalls.get());
    }

    interface TestAiService {

        @dev.langchain4j.service.UserMessage("{{message}}")
        TokenStream chat(
                @MemoryId long memoryId,
                @V("message") String message);
    }

    private static final class MutableChatMemory implements ChatMemory {

        private final Object id;
        private final List<ChatMessage> messages = new ArrayList<>();

        private MutableChatMemory(Object id) {
            this.id = id;
        }

        @Override
        public Object id() {
            return id;
        }

        @Override
        public synchronized void add(ChatMessage message) {
            messages.add(message);
        }

        @Override
        public synchronized List<ChatMessage> messages() {
            return List.copyOf(messages);
        }

        @Override
        public synchronized void clear() {
            messages.clear();
        }

        private synchronized void replaceWith(
                List<ChatMessage> replacement) {
            messages.clear();
            messages.addAll(replacement);
        }
    }
}
