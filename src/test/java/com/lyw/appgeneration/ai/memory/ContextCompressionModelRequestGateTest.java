package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import com.lyw.appgeneration.core.handler.SimpleGenerationTurnContext;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemoryCompressionResult;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.ModelRequestGate;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.V;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.UserMemoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextCompressionModelRequestGateTest {

    @ParameterizedTest
    @EnumSource(value = ContextCompressionMode.class,
            names = {"NORMAL", "ASYNC_SCHEDULED", "BLOCKING_COMPLETED"})
    void 可继续压缩模式映射为允许并读取协调后的活动记忆(
            ContextCompressionMode mode) throws Exception {
        ContextCompressionCoordinator coordinator =
                mock(ContextCompressionCoordinator.class);
        CompressionAwareChatMemory compressionMemory =
                compressionMemory("旧上下文".repeat(10_000));
        ChatMemory refreshedMemory = memory("压缩后的新消息");
        AtomicInteger memoryReads = new AtomicInteger();
        when(coordinator.admit(eq(compressionMemory), eq(List.of()),
                any(), any())).thenReturn(result(mode,
                ContextAdmissionResult.FailureReason.NONE, 30_001, 12_345));

        try (ExecutorService executor =
                     Executors.newVirtualThreadPerTaskExecutor()) {
            ContextCompressionModelRequestGate gate =
                    new ContextCompressionModelRequestGate(
                            coordinator, executor);

            ModelRequestGate.Decision decision = gate.prepare(
                            new ModelRequestGate.Request(
                                    7L,
                                    () -> memoryReads.getAndIncrement() == 0
                                            ? compressionMemory : refreshedMemory,
                                    List.of(),
                                    action -> {
                                        action.run();
                                        return true;
                                    }))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertEquals(ModelRequestGate.Status.ALLOWED, decision.status());
            assertEquals(refreshedMemory.messages(), decision.messages());
            assertEquals(12_345, decision.estimatedInputTokens());
            assertEquals(2, memoryReads.get(),
                    "协调完成后必须重新读取代理当前使用的活动 ChatMemory");
        }
    }

    @Test
    void 回合终态映射为取消() throws Exception {
        assertStatus(
                ContextCompressionMode.ADMISSION_FAILED,
                ContextAdmissionResult.FailureReason.TURN_TERMINATED,
                ModelRequestGate.Status.CANCELLED);
    }

    @Test
    void 压缩失败映射为类型化失败() throws Exception {
        assertStatus(
                ContextCompressionMode.BLOCKING_FAILED,
                ContextAdmissionResult.FailureReason.MODEL_FAILED,
                ModelRequestGate.Status.COMPRESSION_FAILED);
    }

    @Test
    void 硬上限拒绝保持独立状态() throws Exception {
        assertStatus(
                ContextCompressionMode.HARD_LIMIT_REJECTED,
                ContextAdmissionResult.FailureReason.STILL_OVER_HARD_LIMIT,
                ModelRequestGate.Status.HARD_LIMIT_REJECTED);
    }

    @Test
    void 协调器必须在受管虚拟线程而不是调用线程执行() throws Exception {
        ContextCompressionCoordinator coordinator =
                mock(ContextCompressionCoordinator.class);
        CompressionAwareChatMemory memory =
                compressionMemory("协调前消息");
        AtomicReference<Thread> worker = new AtomicReference<>();
        when(coordinator.admit(eq(memory), eq(List.of()), any(), any()))
                .thenAnswer(invocation -> {
                    worker.set(Thread.currentThread());
                    return result(ContextCompressionMode.NORMAL,
                            ContextAdmissionResult.FailureReason.NONE, 10, 10);
                });
        Thread caller = Thread.currentThread();

        try (ExecutorService executor =
                     Executors.newVirtualThreadPerTaskExecutor()) {
            ContextCompressionModelRequestGate gate =
                    new ContextCompressionModelRequestGate(
                            coordinator, executor);

            gate.prepare(request(memory)).toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);

            assertNotEquals(caller, worker.get());
            assertTrue(worker.get().isVirtual());
        }
    }

    @Test
    void 已完成Future的完成回调仍由受管虚拟线程派发() throws Exception {
        ContextCompressionCoordinator coordinator =
                mock(ContextCompressionCoordinator.class);
        ChatMemory memory = memory("已完成门禁结果");
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        Thread caller = Thread.currentThread();

        try (ExecutorService executor =
                     Executors.newVirtualThreadPerTaskExecutor()) {
            ContextCompressionModelRequestGate gate =
                    new ContextCompressionModelRequestGate(
                            coordinator, executor);
            ModelRequestGate.Decision allowed = new ModelRequestGate.Decision(
                    ModelRequestGate.Status.ALLOWED,
                    memory.messages(),
                    10,
                    "");

            gate.onPrepared(CompletableFuture.completedFuture(allowed),
                    (decision, failure) -> {
                        callbackThread.set(Thread.currentThread());
                        callbackFailure.set(failure);
                        completed.countDown();
                    });

            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertNull(callbackFailure.get());
            assertNotEquals(caller, callbackThread.get());
            assertTrue(callbackThread.get().isVirtual());
        }
    }

    @Test
    void 完成回调执行器拒绝时不得同步执行续调用() throws Exception {
        ContextCompressionCoordinator coordinator =
                mock(ContextCompressionCoordinator.class);
        ChatMemory memory = memory("拒绝派发的门禁结果");
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        executor.shutdownNow();
        ContextCompressionModelRequestGate gate =
                new ContextCompressionModelRequestGate(coordinator, executor);
        AtomicInteger callbacks = new AtomicInteger();
        ModelRequestGate.Decision allowed = new ModelRequestGate.Decision(
                ModelRequestGate.Status.ALLOWED,
                memory.messages(),
                10,
                "");

        ModelRequestGate.DispatchStatus dispatch = gate.onPrepared(
                        CompletableFuture.completedFuture(allowed),
                        (decision, failure) -> callbacks.incrementAndGet())
                .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals(ModelRequestGate.DispatchStatus.REJECTED, dispatch);
        assertEquals(0, callbacks.get(),
                "执行器拒绝后不能在调用线程同步执行续调用");
        verify(coordinator, never()).admit(any(), any(), any(), any());
    }

    @Test
    void 执行器拒绝时不得回退调用线程执行协调器() throws Exception {
        ContextCompressionCoordinator coordinator =
                mock(ContextCompressionCoordinator.class);
        CompressionAwareChatMemory memory =
                compressionMemory("执行器拒绝消息");
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        executor.shutdownNow();
        ContextCompressionModelRequestGate gate =
                new ContextCompressionModelRequestGate(coordinator, executor);

        ModelRequestGate.Decision decision = gate.prepare(request(memory))
                .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals(ModelRequestGate.Status.COMPRESSION_FAILED,
                decision.status());
        assertTrue(decision.messages().isEmpty());
        assertFalse(decision.safeMessage().isBlank());
        verify(coordinator, never()).admit(any(), any(), any(), any());
    }

    @Test
    void 首次请求真实跨入30K时必须等待协调器裁剪后再调用模型()
            throws Exception {
        try (RealGateFixture fixture = new RealGateFixture(19_000, 12_000);
             SimpleGenerationTurnContext turnContext =
                     fixture.openTurn("real-initial-30k")) {
            RecordingStreamingChatModel model =
                    new RecordingStreamingChatModel();
            RealGateAiService service = AiServices.builder(
                            RealGateAiService.class)
                    .streamingChatModel(model)
                    .chatMemoryProvider(ignored -> fixture.memory())
                    .build();
            AtomicReference<Throwable> error = new AtomicReference<>();
            TokenStream stream = service.chat(7L, "本轮问题");

            stream.modelRequestGate(fixture.gate(), turnContext)
                    .onPartialResponse(ignored -> { })
                    .onError(error::set)
                    .start();

            assertTrue(fixture.awaitCompressionStarted(),
                    "31K 首次请求必须进入真实阻塞压缩");
            assertEquals(0, model.callCount(),
                    "压缩释放前不得调用模型");

            fixture.releaseCompression();

            assertTrue(model.awaitCalls(1));
            assertNull(error.get());
            ChatRequest request = model.request(0);
            assertEquals(fixture.memory().messages(), request.messages());
            assertFalse(containsText(request.messages(), fixture.oldUser()),
                    "真实协调器必须裁剪已摘要的旧完整回合");
            assertTrue(containsText(request.messages(), fixture.recentUser()));
            assertTrue(containsText(request.messages(), "本轮问题"));
            stream.cancel();
        }
    }

    @Test
    void 工具结果真实跨入30K时必须等待协调器压缩后才能续调模型()
            throws Exception {
        String largeToolResult = "工".repeat(4_000);
        try (RealGateFixture fixture = new RealGateFixture(15_000, 12_000);
             SimpleGenerationTurnContext turnContext =
                     fixture.openTurn("real-tool-30k")) {
            RecordingStreamingChatModel model =
                    new RecordingStreamingChatModel();
            RealGateAiService service = AiServices.builder(
                            RealGateAiService.class)
                    .streamingChatModel(model)
                    .chatMemoryProvider(ignored -> fixture.memory())
                    .tools(new LargeResultTool(largeToolResult))
                    .build();
            AtomicReference<Throwable> error = new AtomicReference<>();
            TokenStream stream = service.chat(7L, "本轮问题");

            stream.modelRequestGate(fixture.gate(), turnContext)
                    .onPartialResponse(ignored -> { })
                    .onError(error::set)
                    .start();

            assertTrue(model.awaitCalls(1));
            assertTrue(fixture.estimator().estimateRequest(
                            model.request(0).messages(), List.of())
                            < fixture.properties()
                            .getAsyncCompressionThreshold(),
                    "首次请求必须低于 28K");

            model.handler(0).onCompleteResponse(toolResponse());

            assertTrue(fixture.awaitCompressionStarted(),
                    "工具结果加入后必须真实跨入 30K 阻塞压缩");
            int expandedTokens = fixture.estimator().estimateRequest(
                    fixture.memory().messages(), List.of());
            assertTrue(expandedTokens >= fixture.properties()
                    .getBlockingCompressionThreshold());
            assertTrue(expandedTokens < fixture.properties()
                    .getHardInputLimit());
            assertEquals(1, model.callCount(),
                    "压缩释放前续调用次数必须保持为 1");

            fixture.releaseCompression();

            assertTrue(model.awaitCalls(2));
            assertNull(error.get());
            ChatRequest secondRequest = model.request(1);
            assertFalse(containsText(
                    secondRequest.messages(), fixture.oldUser()));
            assertTrue(secondRequest.messages().stream()
                    .filter(ToolExecutionResultMessage.class::isInstance)
                    .map(ToolExecutionResultMessage.class::cast)
                    .anyMatch(result -> largeToolResult.equals(result.text())));
            assertEquals(fixture.memory().messages(), secondRequest.messages());
            stream.cancel();
        }
    }

    private boolean containsText(
            List<ChatMessage> messages, String expected) {
        return messages.stream().anyMatch(message -> {
            if (message instanceof UserMessage userMessage
                    && userMessage.hasSingleText()) {
                return expected.equals(userMessage.singleText());
            }
            if (message instanceof AiMessage aiMessage) {
                return expected.equals(aiMessage.text());
            }
            return false;
        });
    }

    private ChatResponse toolResponse() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("large-result-call")
                .name("largeResult")
                .arguments("{}")
                .build();
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(List.of(request)))
                .metadata(ChatResponseMetadata.builder()
                        .tokenUsage(new TokenUsage())
                        .build())
                .build();
    }

    interface RealGateAiService {

        @dev.langchain4j.service.UserMessage("{{message}}")
        TokenStream chat(
                @MemoryId long memoryId,
                @V("message") String message);
    }

    static final class LargeResultTool {

        private final String result;

        private LargeResultTool(String result) {
            this.result = result;
        }

        @dev.langchain4j.agent.tool.Tool("返回大结果")
        public String largeResult() {
            return result;
        }
    }

    private static final class RecordingStreamingChatModel
            implements StreamingChatModel {

        private final CopyOnWriteArrayList<ChatRequest> requests =
                new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<StreamingChatResponseHandler>
                handlers = new CopyOnWriteArrayList<>();
        private final CountDownLatch firstCall = new CountDownLatch(1);
        private final CountDownLatch secondCall = new CountDownLatch(1);

        @Override
        public void doChat(
                ChatRequest request,
                StreamingChatResponseHandler handler) {
            requests.add(request);
            handlers.add(handler);
            int count = requests.size();
            if (count >= 1) {
                firstCall.countDown();
            }
            if (count >= 2) {
                secondCall.countDown();
            }
        }

        private boolean awaitCalls(int expected) throws InterruptedException {
            CountDownLatch latch = expected == 1 ? firstCall : secondCall;
            return latch.await(2, TimeUnit.SECONDS);
        }

        private int callCount() {
            return requests.size();
        }

        private ChatRequest request(int index) {
            return requests.get(index);
        }

        private StreamingChatResponseHandler handler(int index) {
            return handlers.get(index);
        }
    }

    private static final class CharacterCountingTokenEstimator
            implements ChatTokenEstimator {

        @Override
        public int estimateText(String text) {
            return text == null ? 0 : text.codePointCount(0, text.length());
        }

        @Override
        public int estimateMessages(List<ChatMessage> messages) {
            long tokens = 0L;
            for (ChatMessage message : messages) {
                tokens += estimateMessage(message);
            }
            return tokens >= Integer.MAX_VALUE
                    ? Integer.MAX_VALUE : (int) tokens;
        }

        @Override
        public int estimateToolSpecifications(List<ToolSpecification> tools) {
            return 0;
        }

        @Override
        public int estimateRequest(
                List<ChatMessage> messages,
                List<ToolSpecification> tools) {
            return estimateMessages(messages);
        }

        private int estimateMessage(ChatMessage message) {
            if (message instanceof UserMessage userMessage
                    && userMessage.hasSingleText()) {
                return estimateText(userMessage.singleText());
            }
            if (message instanceof AiMessage aiMessage) {
                int tokens = estimateText(aiMessage.text());
                for (ToolExecutionRequest request
                        : aiMessage.toolExecutionRequests()) {
                    tokens += estimateText(request.id());
                    tokens += estimateText(request.name());
                    tokens += estimateText(request.arguments());
                }
                return tokens;
            }
            if (message instanceof ToolExecutionResultMessage result) {
                return estimateText(result.id())
                        + estimateText(result.toolName())
                        + estimateText(result.text());
            }
            return estimateText(message.toString());
        }
    }

    private static final class RealGateFixture implements AutoCloseable {

        private static final long APP_ID = 7L;

        private final String oldUser;
        private final String oldAi;
        private final String recentUser;
        private final String recentAi;
        private final CharacterCountingTokenEstimator estimator =
                new CharacterCountingTokenEstimator();
        private final MemoryTokenProperties properties =
                new MemoryTokenProperties();
        private final MemorySummaryService summaryService =
                mock(MemorySummaryService.class);
        private final UserMemoryService userMemoryService =
                mock(UserMemoryService.class);
        private final ChatHistoryService historyService =
                mock(ChatHistoryService.class);
        private final ExecutorService compressionExecutor =
                Executors.newSingleThreadExecutor();
        private final ExecutorService gateExecutor =
                Executors.newVirtualThreadPerTaskExecutor();
        private final CountDownLatch compressionStarted =
                new CountDownLatch(1);
        private final CountDownLatch releaseCompression =
                new CountDownLatch(1);
        private final CompressionAwareChatMemory memory;
        private final ContextCompressionModelRequestGate gate;
        private final AppOperationLeaseManager operationManager =
                new AppOperationLeaseManager();

        private RealGateFixture(
                int oldTurnTokens,
                int recentTurnTokens) {
            oldUser = "旧".repeat(oldTurnTokens / 2);
            oldAi = "答".repeat(oldTurnTokens - oldUser.length());
            recentUser = "新".repeat(recentTurnTokens / 2);
            recentAi = "应".repeat(
                    recentTurnTokens - recentUser.length());
            properties.setBlockingTimeout(Duration.ofSeconds(5));
            when(summaryService.getCurrentSummary(APP_ID)).thenReturn("");
            when(summaryService.lastSummarizedId(APP_ID)).thenReturn(0L);
            when(userMemoryService.recallByApp(APP_ID)).thenReturn("");
            when(historyService.listRecentCompleteTurnBoundaries(APP_ID, 2))
                    .thenReturn(List.of(
                            new ChatHistoryService.StableTurnBoundary(
                                    1L, 2L, oldUser, oldAi),
                            new ChatHistoryService.StableTurnBoundary(
                                    3L, 4L, recentUser, recentAi)));
            when(summaryService.compressNow(
                    eq(APP_ID), eq(2L), any(Duration.class)))
                    .thenAnswer(invocation -> {
                        compressionStarted.countDown();
                        try {
                            if (!releaseCompression.await(
                                    5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException(
                                        "测试未及时释放真实压缩");
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(
                                    "真实压缩等待被中断", exception);
                        }
                        return new MemoryCompressionResult(
                                MemoryCompressionResult.Status.COMPRESSED,
                                2L, 800, "完成");
                    });
            MessageWindowChatMemory delegate =
                    MessageWindowChatMemory.builder()
                            .id(APP_ID)
                            .maxMessages(Integer.MAX_VALUE)
                            .build();
            memory = new CompressionAwareChatMemory(
                    new TokenAwareChatMemory(delegate),
                    summaryService,
                    userMemoryService);
            memory.add(UserMessage.from(oldUser));
            memory.add(AiMessage.from(oldAi));
            memory.add(UserMessage.from(recentUser));
            memory.add(AiMessage.from(recentAi));
            ContextCompressionCoordinator coordinator =
                    new ContextCompressionCoordinator(
                            estimator,
                            historyService,
                            summaryService,
                            properties,
                            compressionExecutor,
                            new AppDataLifecycleFence());
            gate = new ContextCompressionModelRequestGate(
                    coordinator, gateExecutor);
        }

        private SimpleGenerationTurnContext openTurn(String ownerToken) {
            var operation = operationManager.acquire(
                    APP_ID,
                    AppOperationLeaseManager.AppOperationType.GENERATE,
                    ownerToken);
            return new SimpleGenerationTurnContext(operation);
        }

        private boolean awaitCompressionStarted()
                throws InterruptedException {
            return compressionStarted.await(2, TimeUnit.SECONDS);
        }

        private void releaseCompression() {
            releaseCompression.countDown();
        }

        private CompressionAwareChatMemory memory() {
            return memory;
        }

        private ContextCompressionModelRequestGate gate() {
            return gate;
        }

        private CharacterCountingTokenEstimator estimator() {
            return estimator;
        }

        private MemoryTokenProperties properties() {
            return properties;
        }

        private String oldUser() {
            return oldUser;
        }

        private String recentUser() {
            return recentUser;
        }

        @Override
        public void close() {
            releaseCompression.countDown();
            gateExecutor.shutdownNow();
            compressionExecutor.shutdownNow();
        }
    }

    private void assertStatus(
            ContextCompressionMode mode,
            ContextAdmissionResult.FailureReason failureReason,
            ModelRequestGate.Status expected) throws Exception {
        ContextCompressionCoordinator coordinator =
                mock(ContextCompressionCoordinator.class);
        CompressionAwareChatMemory memory =
                compressionMemory("协调后的活动消息");
        List<ChatMessage> latestMessages = List.of(
                UserMessage.from("协调后的活动消息"));
        when(coordinator.admit(eq(memory), eq(List.of()), any(), any()))
                .thenReturn(result(mode, failureReason, 31_000, 29_000));

        try (ExecutorService executor =
                     Executors.newVirtualThreadPerTaskExecutor()) {
            ContextCompressionModelRequestGate gate =
                    new ContextCompressionModelRequestGate(
                            coordinator, executor);

            ModelRequestGate.Decision decision = gate.prepare(request(memory))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertEquals(expected, decision.status());
            assertEquals(latestMessages, decision.messages());
            assertEquals(29_000, decision.estimatedInputTokens());
            if (expected == ModelRequestGate.Status.ALLOWED) {
                assertTrue(decision.safeMessage().isBlank());
            } else {
                assertFalse(decision.safeMessage().isBlank());
            }
        }
    }

    private ModelRequestGate.Request request(ChatMemory memory) {
        return new ModelRequestGate.Request(
                7L, () -> memory, List.of(), action -> {
                    action.run();
                    return true;
                });
    }

    private ContextAdmissionResult result(
            ContextCompressionMode mode,
            ContextAdmissionResult.FailureReason failureReason,
            int initialTokens,
            int finalTokens) {
        return new ContextAdmissionResult(
                mode, initialTokens, finalTokens, 0L,
                failureReason, "测试结果");
    }

    private ChatMemory memory(String message) {
        MessageWindowChatMemory memory =
                MessageWindowChatMemory.withMaxMessages(10);
        memory.add(UserMessage.from(message));
        return memory;
    }

    private CompressionAwareChatMemory compressionMemory(String message) {
        MessageWindowChatMemory delegate = MessageWindowChatMemory.builder()
                .id(7L)
                .maxMessages(Integer.MAX_VALUE)
                .build();
        MemorySummaryService summaryService = mock(MemorySummaryService.class);
        UserMemoryService userMemoryService = mock(UserMemoryService.class);
        when(summaryService.getCurrentSummary(7L)).thenReturn("");
        when(userMemoryService.recallByApp(7L)).thenReturn("");
        CompressionAwareChatMemory memory = new CompressionAwareChatMemory(
                new TokenAwareChatMemory(delegate),
                summaryService,
                userMemoryService);
        memory.add(UserMessage.from(message));
        return memory;
    }
}
