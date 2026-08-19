package dev.langchain4j.service;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.ChatExecutor;
import dev.langchain4j.internal.Json;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AiServiceStreamingResponseHandlerTest {

    private static final long SHORT_CANCEL_TIMEOUT_MILLIS = 250L;

    private static final String BUILD_SUCCESS = """
            {"protocol":"vue-build-tool/v1","invocationStatus":"COMPLETED",
            "success":true,"attempt":1,"maxAttempts":3,"stage":"SUCCESS",
            "failureKind":null,"timedOut":false,"repairable":false,
            "reflectionRequired":false,"nextAction":"STOP","message":"构建成功",
            "errorSummary":null,"terminateToolLoop":true,
            "finalResponse":"项目已生成并构建成功。"}
            """;
    private static final String BUILD_THIRD_FAILURE = """
            {"protocol":"vue-build-tool/v1","invocationStatus":"COMPLETED",
            "success":false,"attempt":3,"maxAttempts":3,"stage":"NPM_BUILD",
            "failureKind":"CODE","timedOut":false,"repairable":false,
            "reflectionRequired":false,"nextAction":"STOP","message":"构建失败",
            "errorSummary":"安全诊断","terminateToolLoop":true,
            "finalResponse":"抱歉，系统遇到了一些问题，请您稍后重试修复"}
            """;
    private static final String BUILD_FIRST_FAILURE = """
            {"protocol":"vue-build-tool/v1","invocationStatus":"COMPLETED",
            "success":false,"attempt":1,"maxAttempts":3,"stage":"NPM_BUILD",
            "failureKind":"CODE","timedOut":false,"repairable":true,
            "reflectionRequired":false,"nextAction":"REPAIR","message":"请修复",
            "errorSummary":"安全诊断","terminateToolLoop":false,"finalResponse":null}
            """;
    private static final String FILE_RESOURCE_LIMIT = """
            {"protocol":"file-tool/v1","operation":"writeFile",
            "status":"REJECTED","relativePath":"src/App.vue","changed":false,
            "message":"工具内容超过本轮资源上限",
            "failureReason":"RESOURCE_LIMIT_EXCEEDED","content":null}
            """;

    @Test
    void partial用户回调阻塞时全局取消不得等待controller锁()
            throws Exception {
        AiServiceContext context = new AiServiceContext(Object.class);
        context.streamingChatModel = new CapturingStreamingChatModel();
        StreamingRequestController controller = new StreamingRequestController();
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        CountDownLatch cancellationReturned = new CountDownLatch(1);
        AiServiceStreamingResponseHandler handler = ordinaryHandler(
                context,
                MessageWindowChatMemory.withMaxMessages(10),
                ignored -> {
                    handlerEntered.countDown();
                    awaitLatch(releaseHandler, "等待释放 partial 用户回调超时");
                },
                response -> { },
                error -> fail("不应触发错误回调", error),
                controller);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> partial = executor.submit(
                    () -> handler.onPartialResponse("阻塞正文"));
            Future<?> cancellation = null;
            boolean returnedQuickly;
            try {
                assertTrue(handlerEntered.await(2, TimeUnit.SECONDS),
                        "partial 用户回调必须先进入阻塞点");
                cancellation = executor.submit(() -> {
                    controller.cancel();
                    cancellationReturned.countDown();
                });
                returnedQuickly = cancellationReturned.await(
                        SHORT_CANCEL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } finally {
                releaseHandler.countDown();
            }
            partial.get(2, TimeUnit.SECONDS);
            if (cancellation != null) {
                cancellation.get(2, TimeUnit.SECONDS);
            }

            assertTrue(returnedQuickly,
                    "cancel() 不得等待阻塞的 partial 用户回调");
            assertTrue(controller.isCancelled());
        }
    }

    @Test
    void 取消先赢时partial回调不得获得提交许可() {
        AiServiceContext context = new AiServiceContext(Object.class);
        context.streamingChatModel = new CapturingStreamingChatModel();
        StreamingRequestController controller = new StreamingRequestController();
        AtomicInteger partials = new AtomicInteger();
        AiServiceStreamingResponseHandler handler = ordinaryHandler(
                context,
                MessageWindowChatMemory.withMaxMessages(10),
                ignored -> partials.incrementAndGet(),
                response -> { }, error -> fail("取消后不应报错", error),
                controller);

        controller.cancel();
        handler.onPartialResponse("迟到正文");

        assertEquals(0, partials.get());
    }

    @Test
    void partial票据先赢后取消不得撤销已认领的用户回调()
            throws Exception {
        AiServiceContext context = new AiServiceContext(Object.class);
        context.streamingChatModel = new CapturingStreamingChatModel();
        StreamingRequestController controller = new StreamingRequestController();
        AtomicInteger partials = new AtomicInteger();
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        AiServiceStreamingResponseHandler blockingHandler = ordinaryHandler(
                context, MessageWindowChatMemory.withMaxMessages(10),
                ignored -> {
                    callbackEntered.countDown();
                    awaitLatch(releaseCallback, "等待释放已认领 partial 超时");
                    partials.incrementAndGet();
                }, response -> { }, error -> fail("不应报错", error),
                controller);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> callback = threads.submit(() ->
                    blockingHandler.onPartialResponse("已认领正文"));
            assertTrue(callbackEntered.await(2, TimeUnit.SECONDS));
            controller.cancel();
            releaseCallback.countDown();
            callback.get(2, TimeUnit.SECONDS);
        } finally {
            releaseCallback.countDown();
        }

        assertEquals(1, partials.get());
    }

    @Test
    void executor认领先赢后取消必须立即返回并以取消结果闭合工具请求()
            throws Exception {
        AiServiceContext context = new AiServiceContext(Object.class);
        CapturingStreamingChatModel model = new CapturingStreamingChatModel();
        context.streamingChatModel = model;
        StreamingRequestController controller = new StreamingRequestController();
        List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        RecordingChatMemory memory = new RecordingChatMemory(events);
        CountDownLatch toolEntered = new CountDownLatch(1);
        CountDownLatch releaseTool = new CountDownLatch(1);
        CountDownLatch cancellationReturned = new CountDownLatch(1);
        AiServiceStreamingResponseHandler handler = newHandler(
                context,
                Map.of("writeFile", (request, memoryId) -> {
                    toolEntered.countDown();
                    awaitLatch(releaseTool, "等待释放工具执行超时");
                    return "工具结果";
                }),
                memory,
                events,
                controller,
                ToolExecutionGuard.direct());

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> completion = executor.submit(() ->
                    handler.onCompleteResponse(responseWithTools(
                            tool("blocked-tool", "writeFile"))));
            Future<?> cancellation = null;
            boolean returnedQuickly;
            try {
                assertTrue(toolEntered.await(2, TimeUnit.SECONDS),
                        "工具 executor 必须先进入阻塞点");
                cancellation = executor.submit(() -> {
                    controller.cancel();
                    cancellationReturned.countDown();
                });
                returnedQuickly = cancellationReturned.await(
                        SHORT_CANCEL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } finally {
                releaseTool.countDown();
            }
            completion.get(2, TimeUnit.SECONDS);
            if (cancellation != null) {
                cancellation.get(2, TimeUnit.SECONDS);
            }

            assertTrue(returnedQuickly,
                    "cancel() 不得等待阻塞的工具 executor");
            assertTrue(controller.isCancelled());
            assertEquals(2, memory.messages().size(),
                    "已提交的 AI 工具请求必须由明确取消结果闭合");
            ToolExecutionResultMessage cancellationResult =
                    (ToolExecutionResultMessage) memory.messages().get(1);
            assertTrue(cancellationResult.text().contains("请求已经取消"));
            assertFalse(cancellationResult.text().contains("工具结果"));
            assertEquals(List.of(
                    "memory:add-tool-result:blocked-tool",
                    "callback:on-tool-executed:writeFile"), events);
            assertEquals(0, model.chatInvocations,
                    "取消后的工具完成不得启动后继模型");
        }
    }

    @Test
    void 工具请求提交先赢但取消先于executor认领时必须跳过执行并闭合结果()
            throws Exception {
        AiServiceContext context = new AiServiceContext(Object.class);
        context.streamingChatModel = new CapturingStreamingChatModel();
        StreamingRequestController controller = new StreamingRequestController();
        List<ChatMessage> messages = new ArrayList<>();
        ChatMemory cancellingMemory = new ChatMemory() {
            @Override
            public Object id() {
                return "cancelling-memory";
            }

            @Override
            public void add(ChatMessage message) {
                messages.add(message);
                if (message instanceof AiMessage aiMessage
                        && aiMessage.hasToolExecutionRequests()) {
                    controller.cancel();
                }
            }

            @Override
            public List<ChatMessage> messages() {
                return List.copyOf(messages);
            }

            @Override
            public void clear() {
                messages.clear();
            }
        };
        AtomicInteger executorCalls = new AtomicInteger();
        AiServiceStreamingResponseHandler handler = newHandler(
                context,
                Map.of("writeFile", (request, memoryId) -> {
                    executorCalls.incrementAndGet();
                    return "不应执行";
                }),
                cancellingMemory,
                new ArrayList<>(),
                controller,
                ToolExecutionGuard.direct());

        handler.onCompleteResponse(responseWithTools(
                tool("cancel-before-executor", "writeFile")));

        assertEquals(0, executorCalls.get());
        assertEquals(2, messages.size(),
                "memory 提交先赢后即使取消，也必须补齐工具结果");
        ToolExecutionResultMessage cancellation =
                (ToolExecutionResultMessage) messages.get(1);
        assertTrue(cancellation.text().contains("请求已经取消"));
    }

    @Test
    void 取消先于工具请求写入确认时不得执行executor且必须闭合已写请求()
            throws Exception {
        AiServiceContext context = new AiServiceContext(Object.class);
        context.streamingChatModel = new CapturingStreamingChatModel();
        StreamingRequestController controller = new StreamingRequestController();
        List<ChatMessage> messages = new ArrayList<>();
        ChatMemory cancellingMemory = new ChatMemory() {
            @Override
            public Object id() {
                return "cancel-before-batch-confirm";
            }

            @Override
            public void add(ChatMessage message) {
                messages.add(message);
                if (message instanceof AiMessage aiMessage
                        && aiMessage.hasToolExecutionRequests()) {
                    controller.cancel();
                }
            }

            @Override
            public List<ChatMessage> messages() {
                return List.copyOf(messages);
            }

            @Override
            public void clear() {
                messages.clear();
            }
        };
        AtomicInteger executorCalls = new AtomicInteger();
        AiServiceStreamingResponseHandler handler = newHandler(
                context,
                Map.of("writeFile", (request, memoryId) -> {
                    executorCalls.incrementAndGet();
                    return "不应执行";
                }), cancellingMemory, new ArrayList<>(), controller,
                ToolExecutionGuard.direct());

        handler.onCompleteResponse(responseWithTools(
                tool("cancel-before-confirm", "writeFile")));

        assertEquals(0, executorCalls.get());
        assertEquals(2, messages.size(),
                "请求消息已写入后即使取消先于 controller 确认，也必须补取消结果");
        ToolExecutionResultMessage cancellation =
                assertInstanceOf(ToolExecutionResultMessage.class,
                        messages.get(1));
        assertTrue(cancellation.text().contains("请求已经取消"));
    }

    @Test
    void 工具批次预留后写入启动前取消先赢不得产生memory副作用()
            throws Exception {
        AiServiceContext context = new AiServiceContext(Object.class);
        CapturingStreamingChatModel model = new CapturingStreamingChatModel();
        context.streamingChatModel = model;
        StreamingRequestController controller =
                new StreamingRequestController();
        CountDownLatch memoryLookupStarted = new CountDownLatch(1);
        CountDownLatch allowWriteStart = new CountDownLatch(1);
        List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        RecordingChatMemory memory = new RecordingChatMemory(events);
        java.lang.reflect.Field chatMemoryServiceField =
                AiServiceContext.class.getDeclaredField("chatMemoryService");
        chatMemoryServiceField.setAccessible(true);
        Object blockingChatMemoryService = org.mockito.Mockito.mock(
                chatMemoryServiceField.getType(), invocation -> {
                    if (!invocation.getMethod().getName()
                            .equals("getOrCreateChatMemory")) {
                        return org.mockito.Mockito.RETURNS_DEFAULTS
                                .answer(invocation);
                    }
                    memoryLookupStarted.countDown();
                    awaitLatch(allowWriteStart,
                            "等待释放工具请求写入启动点超时");
                    return memory;
                });
        chatMemoryServiceField.set(context, blockingChatMemoryService);
        AtomicInteger executorCalls = new AtomicInteger();
        AiServiceStreamingResponseHandler handler = newHandler(
                context,
                Map.of("writeFile", (request, memoryId) -> {
                    executorCalls.incrementAndGet();
                    return "不应执行";
                }),
                memory,
                events,
                controller,
                ToolExecutionGuard.direct());

        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> completion = threads.submit(() ->
                    handler.onCompleteResponse(responseWithTools(
                            tool("cancel-before-write-start", "writeFile"))));
            try {
                assertTrue(memoryLookupStarted.await(2, TimeUnit.SECONDS),
                        "T1 必须已完成批次预留并进入 memory 获取");
                controller.cancel();
                assertTrue(controller.isCancelled(),
                        "T2 的 cancel 必须在释放 T1 前完成返回");
            } finally {
                allowWriteStart.countDown();
            }
            completion.get(2, TimeUnit.SECONDS);
        } finally {
            allowWriteStart.countDown();
        }

        assertTrue(memory.messages().isEmpty(),
                "取消先赢后不得开始写入 tool_calls 或配对结果");
        assertEquals(0, executorCalls.get());
        assertEquals(0, model.chatInvocations,
                "取消先赢后不得启动后继模型");
    }

    @Test
    void 多工具批次首个executor途中取消必须闭合全部请求且只执行首个()
            throws Exception {
        AiServiceContext context = new AiServiceContext(Object.class);
        CapturingStreamingChatModel model = new CapturingStreamingChatModel();
        context.streamingChatModel = model;
        StreamingRequestController controller = new StreamingRequestController();
        List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        RecordingChatMemory memory = new RecordingChatMemory(events);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger executorCalls = new AtomicInteger();
        ToolExecutor executor = (request, memoryId) -> {
            executorCalls.incrementAndGet();
            if (request.id().equals("first")) {
                firstEntered.countDown();
                awaitLatch(releaseFirst, "等待释放首个工具超时");
            }
            return "真实结果:" + request.id();
        };
        AiServiceStreamingResponseHandler handler = newHandler(
                context,
                Map.of("writeFile", executor,
                        "readFile", executor,
                        "deleteFile", executor),
                memory, events, controller, ToolExecutionGuard.direct());

        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> completion = threads.submit(() ->
                    handler.onCompleteResponse(responseWithTools(
                            tool("first", "writeFile"),
                            tool("second", "readFile"),
                            tool("third", "deleteFile"))));
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS));
            controller.cancel();
            releaseFirst.countDown();
            completion.get(2, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
        }

        assertEquals(1, executorCalls.get());
        assertEquals(4, memory.messages().size(),
                "一个工具请求消息必须由三个工具结果完整闭合");
        assertEquals(List.of("first", "second", "third"),
                memory.messages().subList(1, 4).stream()
                        .map(ToolExecutionResultMessage.class::cast)
                        .map(ToolExecutionResultMessage::id)
                        .toList());
        assertTrue(memory.messages().subList(1, 4).stream()
                .map(ToolExecutionResultMessage.class::cast)
                .allMatch(result -> result.text().contains("请求已经取消")));
        assertEquals(0, model.chatInvocations);
    }

    @Test
    void provider提前完整工具事件后取消不得产生孤立工具卡() {
        AiServiceContext context = new AiServiceContext(Object.class);
        context.streamingChatModel = new CapturingStreamingChatModel();
        StreamingRequestController controller = new StreamingRequestController();
        AtomicInteger completeToolCallbacks = new AtomicInteger();
        AtomicInteger executorCalls = new AtomicInteger();
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(10);
        AiServiceStreamingResponseHandler handler =
                new AiServiceStreamingResponseHandler(
                        new NoopChatExecutor(), context, "mem-1",
                        partial -> { },
                        (index, request) -> { },
                        (index, request) ->
                                completeToolCallbacks.incrementAndGet(),
                        execution -> { }, response -> { },
                        error -> fail("取消后不应报普通错误", error),
                        memory, new TokenUsage(), List.of(),
                        Map.of("writeFile", (request, memoryId) -> {
                            executorCalls.incrementAndGet();
                            return "不应执行";
                        }), null, "method-1", controller,
                        ToolExecutionGuard.direct());
        ToolExecutionRequest request = tool("provider", "writeFile");

        handler.onCompleteToolExecutionRequest(0, request);
        controller.cancel();
        handler.onCompleteResponse(responseWithTools(request));

        assertEquals(0, completeToolCallbacks.get());
        assertEquals(0, executorCalls.get());
        assertTrue(memory.messages().isEmpty());
    }

    @Test
    void 门禁prepare阻塞时取消不得等待controller锁()
            throws Exception {
        AiServiceContext context = new AiServiceContext(Object.class);
        CapturingStreamingChatModel model = new CapturingStreamingChatModel();
        context.streamingChatModel = model;
        StreamingRequestController controller = new StreamingRequestController();
        CountDownLatch gateEntered = new CountDownLatch(1);
        CountDownLatch releaseGate = new CountDownLatch(1);
        CountDownLatch cancellationReturned = new CountDownLatch(1);
        ModelRequestGate gate = request -> {
            gateEntered.countDown();
            awaitLatch(releaseGate, "等待释放门禁 prepare 超时");
            return CompletableFuture.completedFuture(
                    allowed(request.latestMemory().get().messages()));
        };
        AiServiceStreamingResponseHandler handler = gatedHandler(
                context,
                MessageWindowChatMemory.withMaxMessages(10),
                controller,
                gate,
                action -> {
                    action.run();
                    return true;
                },
                error -> fail("取消后不应触发普通错误", error));

        assertGateBlockDoesNotDelayCancellation(
                handler, controller, gateEntered, releaseGate,
                cancellationReturned);
        assertEquals(0, model.chatInvocations,
                "取消后门禁 prepare 结果不得启动后继模型");
    }

    @Test
    void 门禁onPrepared阻塞并同步回调时取消不得等待controller锁()
            throws Exception {
        AiServiceContext context = new AiServiceContext(Object.class);
        CapturingStreamingChatModel model = new CapturingStreamingChatModel();
        context.streamingChatModel = model;
        StreamingRequestController controller = new StreamingRequestController();
        CountDownLatch gateEntered = new CountDownLatch(1);
        CountDownLatch releaseGate = new CountDownLatch(1);
        CountDownLatch cancellationReturned = new CountDownLatch(1);
        ModelRequestGate gate = new ModelRequestGate() {
            @Override
            public java.util.concurrent.CompletionStage<Decision> prepare(
                    Request request) {
                return CompletableFuture.completedFuture(
                        allowed(request.latestMemory().get().messages()));
            }

            @Override
            public java.util.concurrent.CompletionStage<DispatchStatus> onPrepared(
                    java.util.concurrent.CompletionStage<Decision> preparation,
                    java.util.function.BiConsumer<Decision, Throwable> completion) {
                gateEntered.countDown();
                awaitLatch(releaseGate, "等待释放门禁 onPrepared 超时");
                preparation.whenComplete(completion);
                return CompletableFuture.completedFuture(
                        DispatchStatus.DISPATCHED);
            }
        };
        AiServiceStreamingResponseHandler handler = gatedHandler(
                context,
                MessageWindowChatMemory.withMaxMessages(10),
                controller,
                gate,
                action -> {
                    action.run();
                    return true;
                },
                error -> fail("取消后不应触发普通错误", error));

        assertGateBlockDoesNotDelayCancellation(
                handler, controller, gateEntered, releaseGate,
                cancellationReturned);
        assertEquals(0, model.chatInvocations,
                "取消后同步门禁回调不得启动后继模型");
    }

    @Test
    void 工具续调用必须等待门禁完成且等待期间释放当前模型回调票据()
            throws Exception {
        AiServiceContext context = new AiServiceContext(Object.class);
        CapturingStreamingChatModel model = new CapturingStreamingChatModel();
        context.streamingChatModel = model;
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(100);
        StreamingRequestController controller = new StreamingRequestController();
        assertTrue(controller.beforeModelRequest());
        CompletableFuture<ModelRequestGate.Decision> preparation =
                new CompletableFuture<>();
        AtomicReference<ModelRequestGate.Request> gateRequest =
                new AtomicReference<>();
        try (ManagedModelRequestGate gate = new ManagedModelRequestGate(request -> {
            gateRequest.set(request);
            return preparation;
        })) {
            AiServiceStreamingResponseHandler handler = gatedHandler(
                    context, memory, controller, gate, action -> {
                        action.run();
                        return true;
                    }, error -> fail("不应触发错误回调", error));

            handler.onCompleteResponse(responseWithTools(
                    tool("large-tool-1", "writeFile"),
                    tool("large-tool-2", "writeFile")));

            assertEquals(0, model.chatInvocations,
                    "工具结果加入后必须先等待统一门禁");
            assertTrue(controller.awaitQuiescence(
                            java.time.Duration.ofMillis(100)),
                    "prepare 返回后必须立即释放当前 SDK callback 票据");
            assertSame(memory, gateRequest.get().latestMemory().get());
            List<ChatMessage> completeBatch =
                    gateRequest.get().latestMemory().get().messages();
            assertEquals(2L, completeBatch.stream()
                    .filter(ToolExecutionResultMessage.class::isInstance)
                    .count(), "门禁只能在全部工具结果写入且批次闭合后运行");
            assertFalse(hasUnpairedToolRequests(completeBatch),
                    "门禁不得观察到孤立 tool_call");

            List<ChatMessage> compressedMessages = List.of(
                    UserMessage.from("压缩后的工具上下文"));
            preparation.complete(new ModelRequestGate.Decision(
                    ModelRequestGate.Status.ALLOWED,
                    compressedMessages,
                    12_000,
                    ""));
            gate.awaitIdle();

            assertEquals(1, model.chatInvocations);
            assertEquals(compressedMessages, model.lastChatRequest.messages());
        }
    }

    @Test
    void 工具结果提交后终态抢占使批次完成失败且不得进入续调门禁()
            throws Exception {
        AiServiceContext context = new AiServiceContext(Object.class);
        CapturingStreamingChatModel model = new CapturingStreamingChatModel();
        context.streamingChatModel = model;
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(100);
        StreamingRequestController controller = new StreamingRequestController();
        assertTrue(controller.beforeModelRequest());
        AtomicBoolean committedResultObserved = new AtomicBoolean();
        AtomicInteger gateCalls = new AtomicInteger();
        AtomicInteger continuationCalls = new AtomicInteger();
        try (ManagedModelRequestGate gate = new ManagedModelRequestGate(
                request -> {
                    gateCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            allowed(request.latestMemory().get().messages()));
                })) {
            AiServiceStreamingResponseHandler handler =
                    new AiServiceStreamingResponseHandler(
                            new NoopChatExecutor(), context, "mem-1",
                            partial -> { },
                            (index, request) -> { },
                            (index, request) -> { },
                            execution -> {
                                committedResultObserved.set(memory.messages()
                                        .stream()
                                        .anyMatch(ToolExecutionResultMessage
                                                .class::isInstance));
                                controller.cancel();
                            },
                            response -> { },
                            error -> fail("终态抢占后不应报告普通错误", error),
                            memory,
                            new TokenUsage(),
                            List.<ToolSpecification>of(),
                            Map.<String, ToolExecutor>of(
                                    "writeFile", (request, memoryId) ->
                                            "已提交工具结果"),
                            null,
                            "method-1",
                            controller,
                            ToolExecutionGuard.direct(),
                            controller.latestModelRequestGeneration(),
                            gate,
                            action -> {
                                continuationCalls.incrementAndGet();
                                action.run();
                                return true;
                            });

            handler.onCompleteResponse(responseWithTools(
                    tool("cancel-before-finish", "writeFile")));
            gate.awaitIdle();

            assertTrue(committedResultObserved.get(),
                    "终态必须在工具结果提交后、批次完成前抢占");
            assertTrue(controller.isCancelled());
            assertEquals(0, gateCalls.get(),
                    "finishToolBatch 返回 false 后不得运行模型门禁");
            assertEquals(0, continuationCalls.get());
            assertEquals(0, model.chatInvocations);
        }
    }

    @Test
    void 已完成门禁结果也必须先释放旧模型回调票据再续调()
            throws Exception {
        AiServiceContext context = new AiServiceContext(Object.class);
        CapturingStreamingChatModel model = new CapturingStreamingChatModel();
        context.streamingChatModel = model;
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(100);
        StreamingRequestController controller = new StreamingRequestController();
        assertTrue(controller.beforeModelRequest());
        AtomicBoolean oldCallbackReleased = new AtomicBoolean();
        try (ManagedModelRequestGate gate = new ManagedModelRequestGate(
                request -> CompletableFuture.completedFuture(
                        allowed(request.latestMemory().get().messages())))) {
            AiServiceStreamingResponseHandler handler = gatedHandler(
                    context, memory, controller, gate, action -> {
                        oldCallbackReleased.set(controller.awaitQuiescence(
                                java.time.Duration.ZERO));
                        action.run();
                        return true;
                    }, error -> fail("不应触发错误回调", error));

            handler.onCompleteResponse(responseWithTools(
                    tool("completed-gate-tool", "writeFile")));
            gate.awaitIdle();

            assertTrue(oldCallbackReleased.get(),
                    "即使 prepare 返回已完成 Future，也必须先退出旧 SDK callback");
            assertEquals(1, model.chatInvocations);
        }
    }

    @Test
    void 完成回调调度被拒绝时不得续调且失败只收口一次() {
        AiServiceContext context = new AiServiceContext(Object.class);
        CapturingStreamingChatModel model = new CapturingStreamingChatModel();
        context.streamingChatModel = model;
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(100);
        StreamingRequestController controller = new StreamingRequestController();
        assertTrue(controller.beforeModelRequest());
        AtomicInteger continuationCalls = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        AtomicBoolean errorAfterCallbackReleased = new AtomicBoolean();
        ModelRequestGate gate = new ModelRequestGate() {
            @Override
            public java.util.concurrent.CompletionStage<Decision> prepare(
                    Request request) {
                return CompletableFuture.completedFuture(
                        allowed(request.latestMemory().get().messages()));
            }

            @Override
            public java.util.concurrent.CompletionStage<DispatchStatus> onPrepared(
                    java.util.concurrent.CompletionStage<Decision> preparation,
                    java.util.function.BiConsumer<Decision, Throwable> completion) {
                return CompletableFuture.completedFuture(
                        DispatchStatus.REJECTED);
            }
        };
        AiServiceStreamingResponseHandler handler = gatedHandler(
                context, memory, controller, gate, action -> {
                    continuationCalls.incrementAndGet();
                    action.run();
                    return true;
                }, error -> {
                    errors.incrementAndGet();
                    errorAfterCallbackReleased.set(controller.awaitQuiescence(
                            java.time.Duration.ZERO));
                });

        handler.onCompleteResponse(responseWithTools(
                tool("rejected-dispatch-tool", "writeFile")));

        assertEquals(0, continuationCalls.get());
        assertEquals(0, model.chatInvocations);
        assertEquals(1, errors.get());
        assertTrue(errorAfterCallbackReleased.get(),
                "调度拒绝只能在旧 SDK callback 票据释放后收口失败");
    }

    @Test
    void 门禁同步提交失败只在旧模型回调票据释放后派发() {
        AiServiceContext context = new AiServiceContext(Object.class);
        context.streamingChatModel = new CapturingStreamingChatModel();
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(100);
        StreamingRequestController controller = new StreamingRequestController();
        assertTrue(controller.beforeModelRequest());
        AtomicInteger errors = new AtomicInteger();
        AtomicBoolean errorAfterCallbackReleased = new AtomicBoolean();
        ModelRequestGate gate = request -> {
            throw new IllegalStateException("门禁提交失败");
        };
        AiServiceStreamingResponseHandler handler = gatedHandler(
                context, memory, controller, gate, action -> {
                    fail("门禁提交失败后不得执行续调用");
                    return false;
                }, error -> {
                    errors.incrementAndGet();
                    errorAfterCallbackReleased.set(controller.awaitQuiescence(
                            java.time.Duration.ZERO));
                });

        handler.onCompleteResponse(responseWithTools(
                tool("failed-prepare-tool", "writeFile")));

        assertEquals(1, errors.get());
        assertTrue(errorAfterCallbackReleased.get(),
                "同步提交失败也必须先认领终态并释放旧 SDK callback 票据");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(
            value = ModelRequestGate.Status.class,
            names = {"COMPRESSION_FAILED", "HARD_LIMIT_REJECTED"})
    void 工具续调用门禁失败不得再次调用模型并返回安全错误(
            ModelRequestGate.Status status) throws Exception {
        AiServiceContext context = new AiServiceContext(Object.class);
        CapturingStreamingChatModel model = new CapturingStreamingChatModel();
        context.streamingChatModel = model;
        StreamingRequestController controller = new StreamingRequestController();
        assertTrue(controller.beforeModelRequest());
        AtomicReference<Throwable> error = new AtomicReference<>();
        try (ManagedModelRequestGate gate = new ManagedModelRequestGate(
                request -> CompletableFuture.completedFuture(
                        new ModelRequestGate.Decision(
                                status,
                                request.latestMemory().get().messages(),
                                32_768,
                                "上下文门禁安全提示")))) {
            AiServiceStreamingResponseHandler handler = gatedHandler(
                    context,
                    MessageWindowChatMemory.withMaxMessages(100),
                    controller,
                    gate,
                    action -> {
                        action.run();
                        return true;
                    },
                    error::set);

            handler.onCompleteResponse(responseWithTools(
                    tool("rejected-tool", "writeFile")));
            gate.awaitIdle();

            assertEquals(0, model.chatInvocations);
            ModelRequestGateException rejection = assertInstanceOf(
                    ModelRequestGateException.class, error.get());
            assertEquals(ModelRequestGateException.Stage.CONTINUATION,
                    rejection.stage());
            assertEquals(status, rejection.status());
            assertEquals("上下文门禁安全提示", rejection.getMessage());
        }
    }

    @Test
    void 取消期间完成的晚到门禁结果不得启动新模型请求()
            throws Exception {
        AiServiceContext context = new AiServiceContext(Object.class);
        CapturingStreamingChatModel model = new CapturingStreamingChatModel();
        context.streamingChatModel = model;
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(100);
        StreamingRequestController controller = new StreamingRequestController();
        assertTrue(controller.beforeModelRequest());
        CompletableFuture<ModelRequestGate.Decision> preparation =
                new CompletableFuture<>();
        try (ManagedModelRequestGate gate =
                     new ManagedModelRequestGate(request -> preparation)) {
            AiServiceStreamingResponseHandler handler = gatedHandler(
                    context, memory, controller, gate,
                    action -> {
                        action.run();
                        return true;
                    }, error -> fail("取消后不应报告普通错误", error));

            handler.onCompleteResponse(responseWithTools(
                    tool("cancelled-tool", "writeFile")));
            controller.cancel();
            preparation.complete(allowed(memory.messages()));
            gate.awaitIdle();

            assertEquals(0, model.chatInvocations);
        }
    }

    @Test
    void 门禁结果不是永久授权回合关门后不得启动新模型请求()
            throws Exception {
        AiServiceContext context = new AiServiceContext(Object.class);
        CapturingStreamingChatModel model = new CapturingStreamingChatModel();
        context.streamingChatModel = model;
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(100);
        StreamingRequestController controller = new StreamingRequestController();
        assertTrue(controller.beforeModelRequest());
        CompletableFuture<ModelRequestGate.Decision> preparation =
                new CompletableFuture<>();
        AtomicBoolean continuationOpen = new AtomicBoolean(true);
        try (ManagedModelRequestGate gate =
                     new ManagedModelRequestGate(request -> preparation)) {
            AiServiceStreamingResponseHandler handler = gatedHandler(
                    context, memory, controller, gate,
                    action -> {
                        if (!continuationOpen.get()) {
                            return false;
                        }
                        action.run();
                        return true;
                    }, error -> fail("关门后不应报告普通错误", error));

            handler.onCompleteResponse(responseWithTools(
                    tool("closed-turn-tool", "writeFile")));
            continuationOpen.set(false);
            preparation.complete(allowed(memory.messages()));
            gate.awaitIdle();

            assertEquals(0, model.chatInvocations);
        }
    }

    @Test
    void 旧请求代次的晚到门禁结果不得重复启动模型()
            throws Exception {
        AiServiceContext context = new AiServiceContext(Object.class);
        CapturingStreamingChatModel model = new CapturingStreamingChatModel();
        context.streamingChatModel = model;
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(100);
        StreamingRequestController controller = new StreamingRequestController();
        assertTrue(controller.beforeModelRequest());
        CompletableFuture<ModelRequestGate.Decision> preparation =
                new CompletableFuture<>();
        try (ManagedModelRequestGate gate =
                     new ManagedModelRequestGate(request -> preparation)) {
            AiServiceStreamingResponseHandler handler = gatedHandler(
                    context, memory, controller, gate,
                    action -> {
                        action.run();
                        return true;
                    }, error -> fail("旧代次不应报告普通错误", error));

            handler.onCompleteResponse(responseWithTools(
                    tool("stale-generation-tool", "writeFile")));
            assertTrue(controller.beforeModelRequest(1L));
            preparation.complete(allowed(memory.messages()));
            gate.awaitIdle();

            assertEquals(0, model.chatInvocations);
        }
    }

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

    @Test
    void completeToolRequestCallbackRunsBeforeRealExecutor() {
        AiServiceContext context = new AiServiceContext(Object.class);
        context.streamingChatModel = new CapturingStreamingChatModel();
        List<String> events = new ArrayList<>();
        AiServiceStreamingResponseHandler handler =
                new AiServiceStreamingResponseHandler(
                        new NoopChatExecutor(), context, "mem-1",
                        partial -> { },
                        (index, request) -> { },
                        (index, request) -> events.add(
                                "callback:on-complete-tool-request:"
                                        + request.id()),
                        execution -> { }, response -> { },
                        throwable -> fail("不应触发错误回调"),
                        MessageWindowChatMemory.withMaxMessages(100),
                        new TokenUsage(), List.of(),
                        Map.of("buildProject", (request, memoryId) -> {
                            events.add("executor:" + request.id());
                            return BUILD_FIRST_FAILURE;
                        }),
                        null, "method-1", new StreamingRequestController(),
                        ToolExecutionGuard.direct());

        handler.onCompleteResponse(
                responseWithTools(tool("build-start", "buildProject")));

        assertEquals(List.of(
                "callback:on-complete-tool-request:build-start",
                "executor:build-start"), events);
    }

    @Test
    void providerCompleteToolRequestCallbackIsNotRepeatedBeforeExecutor() {
        AiServiceContext context = new AiServiceContext(Object.class);
        context.streamingChatModel = new CapturingStreamingChatModel();
        List<String> events = new ArrayList<>();
        AiServiceStreamingResponseHandler handler =
                new AiServiceStreamingResponseHandler(
                        new NoopChatExecutor(), context, "mem-1",
                        partial -> { },
                        (index, request) -> { },
                        (index, request) -> events.add(
                                "callback:on-complete-tool-request:"
                                        + request.id()),
                        execution -> { }, response -> { },
                        throwable -> fail("不应触发错误回调"),
                        MessageWindowChatMemory.withMaxMessages(100),
                        new TokenUsage(), List.of(),
                        Map.of("buildProject", (request, memoryId) -> {
                            events.add("executor:" + request.id());
                            return BUILD_FIRST_FAILURE;
                        }),
                        null, "method-1", new StreamingRequestController(),
                        ToolExecutionGuard.direct());
        ToolExecutionRequest request = tool("build-provider", "buildProject");

        handler.onCompleteToolExecutionRequest(0, request);
        handler.onCompleteResponse(responseWithTools(request));

        assertEquals(List.of(
                "callback:on-complete-tool-request:build-provider",
                "executor:build-provider"), events);
    }

    @Test
    void controlledTerminationPublishesFinalTextWithoutOrdinaryCompletionAndSkipsRemainingBatch()
            throws Exception {
        AiServiceContext context = new AiServiceContext(Object.class);
        CapturingStreamingChatModel model = new CapturingStreamingChatModel();
        context.streamingChatModel = model;
        List<String> events = new ArrayList<>();
        RecordingChatMemory memory = new RecordingChatMemory(events);
        AtomicInteger writeCalls = new AtomicInteger();
        AtomicInteger terminations = new AtomicInteger();
        StreamingRequestController controller = new StreamingRequestController();
        controller.onControlledTermination(termination -> terminations.incrementAndGet());
        ToolExecutionGuard guard = (toolName, memoryId, action) -> {
            String result = action.get();
            var parsed = ToolLoopTerminationProtocol.parseTrusted(toolName, result);
            return new ToolExecutionGuard.GuardedToolExecution(
                    result,
                    parsed.terminate()
                            ? new ToolLoopTerminationProtocol.ControlledTermination(
                            parsed.reason(), parsed.finalResponse())
                            : null);
        };
        AiServiceStreamingResponseHandler handler = newHandler(
                context,
                Map.of(
                        "buildProject", (request, memoryId) -> BUILD_SUCCESS,
                        "writeFile", (request, memoryId) -> {
                            writeCalls.incrementAndGet();
                            return "不应执行";
                        }),
                memory,
                events,
                controller,
                guard);

        handler.onCompleteResponse(responseWithTools(
                tool("build", "buildProject"), tool("write", "writeFile")));

        assertEquals(List.of(
                "memory:add-tool-result:build",
                "callback:on-tool-executed:buildProject",
                "memory:add-tool-result:write",
                "callback:on-tool-executed:writeFile",
                "memory:add-final-ai",
                "callback:on-partial-final-text"), events);
        assertEquals(0, writeCalls.get());
        assertEquals(0, model.chatInvocations);
        assertEquals(1, terminations.get());
        ToolExecutionResultMessage skipped = (ToolExecutionResultMessage) memory.messages().get(2);
        assertTrue(skipped.text().contains("受控跳过"));
    }

    @Test
    void resourceLimitWritesCurrentAndSkippedToolResultsOnceWithoutNextModelRequest()
            throws Exception {
        AiServiceContext context = new AiServiceContext(Object.class);
        CapturingStreamingChatModel model = new CapturingStreamingChatModel();
        context.streamingChatModel = model;
        List<String> events = new ArrayList<>();
        RecordingChatMemory memory = new RecordingChatMemory(events);
        AtomicInteger limitedExecutorCalls = new AtomicInteger();
        AtomicInteger skippedExecutorCalls = new AtomicInteger();
        AtomicInteger terminationCalls = new AtomicInteger();
        AtomicReference<ToolLoopTerminationProtocol.ControlledTermination> termination =
                new AtomicReference<>();
        StreamingRequestController controller = new StreamingRequestController();
        controller.onControlledTermination(value -> {
            terminationCalls.incrementAndGet();
            termination.set(value);
        });
        ToolExecutionGuard guard = (toolName, memoryId, action) -> {
            String result = action.get();
            var parsed = ToolLoopTerminationProtocol.parseTrusted(toolName, result);
            return new ToolExecutionGuard.GuardedToolExecution(
                    result,
                    parsed.terminate()
                            ? new ToolLoopTerminationProtocol.ControlledTermination(
                            parsed.reason(), parsed.finalResponse())
                            : null);
        };
        AiServiceStreamingResponseHandler handler = newHandler(
                context,
                Map.of(
                        "writeFile", (request, memoryId) -> {
                            limitedExecutorCalls.incrementAndGet();
                            return FILE_RESOURCE_LIMIT;
                        },
                        "readFile", (request, memoryId) -> {
                            skippedExecutorCalls.incrementAndGet();
                            return "不应执行";
                        },
                        "deleteFile", (request, memoryId) -> {
                            skippedExecutorCalls.incrementAndGet();
                            return "不应执行";
                        }),
                memory,
                events,
                controller,
                guard);

        handler.onCompleteResponse(responseWithTools(
                tool("limited", "writeFile"),
                tool("skipped-read", "readFile"),
                tool("skipped-delete", "deleteFile")));

        assertEquals(1, limitedExecutorCalls.get());
        assertEquals(0, skippedExecutorCalls.get());
        assertEquals(0, model.chatInvocations);
        assertEquals(1, terminationCalls.get());
        assertNotNull(termination.get());
        assertEquals(ToolLoopTerminationProtocol.ControlledTerminationReason
                        .RESOURCE_LIMIT_EXCEEDED,
                termination.get().reason());
        assertNull(termination.get().finalResponse());
        assertEquals(List.of(
                "memory:add-tool-result:limited",
                "callback:on-tool-executed:writeFile",
                "memory:add-tool-result:skipped-read",
                "callback:on-tool-executed:readFile",
                "memory:add-tool-result:skipped-delete",
                "callback:on-tool-executed:deleteFile"), events);
        assertEquals(4, memory.messages().size());
        ToolExecutionResultMessage current =
                (ToolExecutionResultMessage) memory.messages().get(1);
        ToolExecutionResultMessage skippedRead =
                (ToolExecutionResultMessage) memory.messages().get(2);
        ToolExecutionResultMessage skippedDelete =
                (ToolExecutionResultMessage) memory.messages().get(3);
        assertJsonEquals(FILE_RESOURCE_LIMIT, current.text());
        assertTrue(skippedRead.text().contains("受控跳过"));
        assertTrue(skippedDelete.text().contains("受控跳过"));
    }

    @Test
    void guardWrapsRealExecutorAndCanTerminateNonBuildViolation() throws Exception {
        AiServiceContext context = new AiServiceContext(Object.class);
        CapturingStreamingChatModel model = new CapturingStreamingChatModel();
        context.streamingChatModel = model;
        AtomicInteger executorCalls = new AtomicInteger();
        AtomicInteger terminations = new AtomicInteger();
        List<String> events = new ArrayList<>();
        RecordingChatMemory memory = new RecordingChatMemory(events);
        StreamingRequestController controller = new StreamingRequestController();
        controller.onControlledTermination(termination -> {
            assertEquals(ToolLoopTerminationProtocol.ControlledTerminationReason.PROTOCOL_ERROR,
                    termination.reason());
            terminations.incrementAndGet();
        });
        ToolExecutionGuard rejectingGuard = (toolName, memoryId, action) ->
                new ToolExecutionGuard.GuardedToolExecution(
                        "PROTOCOL_ERROR: 旧租约已经失效",
                        new ToolLoopTerminationProtocol.ControlledTermination(
                                ToolLoopTerminationProtocol.ControlledTerminationReason.PROTOCOL_ERROR,
                                null));
        AiServiceStreamingResponseHandler handler = newHandler(
                context,
                Map.of("readFile", (request, memoryId) -> {
                    executorCalls.incrementAndGet();
                    return "secret";
                }),
                memory, events, controller, rejectingGuard);

        handler.onCompleteResponse(responseWithTools(tool("read", "readFile")));

        assertEquals(0, executorCalls.get());
        assertEquals(0, model.chatInvocations);
        assertEquals(1, terminations.get());
        assertEquals(List.of(
                "memory:add-tool-result:read",
                "callback:on-tool-executed:readFile"), events);
        assertEquals(2, memory.messages().size(),
                "内存只应包含原工具请求和协议拒绝工具结果，不能提前写入终态文案");
        assertTrue(memory.messages().stream().noneMatch(message ->
                message instanceof AiMessage ai && !ai.hasToolExecutionRequests()));
    }

    @Test
    void terminalToolCallbackFailureStillClosesRemainingCardsAndDispatchesTermination()
            throws Exception {
        AiServiceContext context = new AiServiceContext(Object.class);
        context.streamingChatModel = new CapturingStreamingChatModel();
        List<String> events = new ArrayList<>();
        RecordingChatMemory memory = new RecordingChatMemory(events);
        AtomicInteger terminations = new AtomicInteger();
        StreamingRequestController controller = new StreamingRequestController();
        controller.onControlledTermination(termination -> terminations.incrementAndGet());
        ToolExecutionGuard guard = (toolName, memoryId, action) -> {
            String result = action.get();
            return new ToolExecutionGuard.GuardedToolExecution(
                    result,
                    new ToolLoopTerminationProtocol.ControlledTermination(
                            ToolLoopTerminationProtocol.ControlledTerminationReason.BUILD_SUCCEEDED,
                            "项目已生成并构建成功。"));
        };
        AiServiceStreamingResponseHandler handler = new AiServiceStreamingResponseHandler(
                new NoopChatExecutor(), context, "mem-1", partial -> events.add("partial"),
                (index, request) -> { }, (index, request) -> { },
                execution -> {
                    events.add("callback:on-tool-executed:" + execution.request().id());
                    if (execution.request().id().equals("build")) {
                        throw new IllegalStateException("终止工具回调失败");
                    }
                },
                response -> events.add("complete"), throwable -> fail("不应改报普通错误"),
                memory, new TokenUsage(), List.of(), Map.of(
                "buildProject", (request, memoryId) -> BUILD_SUCCESS,
                "writeFile", (request, memoryId) -> "不应执行"),
                null, "method-1", controller, guard);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> handler.onCompleteResponse(responseWithTools(
                        tool("build", "buildProject"),
                        tool("write-1", "writeFile"),
                        tool("write-2", "writeFile"))));

        assertEquals("终止工具回调失败", failure.getMessage());
        assertEquals(1, terminations.get());
        assertEquals(List.of(
                "memory:add-tool-result:build",
                "callback:on-tool-executed:build",
                "memory:add-tool-result:write-1",
                "callback:on-tool-executed:write-1",
                "memory:add-tool-result:write-2",
                "callback:on-tool-executed:write-2"), events);
    }

    @Test
    void finalPartialCallbackFailureStillDispatchesControlledTermination()
            throws Exception {
        AiServiceContext context = new AiServiceContext(Object.class);
        context.streamingChatModel = new CapturingStreamingChatModel();
        List<String> events = new ArrayList<>();
        RecordingChatMemory memory = new RecordingChatMemory(events);
        AtomicInteger terminations = new AtomicInteger();
        StreamingRequestController controller = new StreamingRequestController();
        controller.onControlledTermination(termination -> terminations.incrementAndGet());
        ToolExecutionGuard guard = (toolName, memoryId, action) ->
                new ToolExecutionGuard.GuardedToolExecution(
                        action.get(),
                        new ToolLoopTerminationProtocol.ControlledTermination(
                                ToolLoopTerminationProtocol.ControlledTerminationReason.BUILD_SUCCEEDED,
                                "项目已生成并构建成功。"));
        AiServiceStreamingResponseHandler handler = new AiServiceStreamingResponseHandler(
                new NoopChatExecutor(), context, "mem-1", partial -> {
                    events.add("callback:on-partial-final-text");
                    throw new IllegalStateException("最终文本回调失败");
                },
                (index, request) -> { }, (index, request) -> { },
                execution -> events.add("callback:on-tool-executed:" + execution.request().id()),
                response -> events.add("callback:on-complete"),
                throwable -> fail("不应改报普通错误"), memory, new TokenUsage(), List.of(),
                Map.of("buildProject", (request, memoryId) -> BUILD_SUCCESS),
                null, "method-1", controller, guard);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> handler.onCompleteResponse(
                        responseWithTools(tool("build", "buildProject"))));

        assertEquals("最终文本回调失败", failure.getMessage());
        assertEquals(1, terminations.get());
        assertEquals(List.of(
                "memory:add-tool-result:build",
                "callback:on-tool-executed:build",
                "memory:add-final-ai",
                "callback:on-partial-final-text"), events);
    }

    @Test
    void toolLimitSkipFailureStillClosesOtherCardsAndDispatchesTermination()
            throws Exception {
        AiServiceContext context = new AiServiceContext(Object.class);
        context.streamingChatModel = new CapturingStreamingChatModel();
        List<String> events = new ArrayList<>();
        RecordingChatMemory memory = new RecordingChatMemory(events);
        AtomicInteger terminations = new AtomicInteger();
        StreamingRequestController controller = new StreamingRequestController();
        for (int index = 0; index < StreamingRequestController.MAX_TOOL_EXECUTIONS; index++) {
            assertTrue(controller.beforeToolExecution());
        }
        controller.onControlledTermination(termination -> {
            assertEquals(
                    ToolLoopTerminationProtocol.ControlledTerminationReason.LOOP_LIMIT_EXCEEDED,
                    termination.reason());
            terminations.incrementAndGet();
        });
        AiServiceStreamingResponseHandler handler = new AiServiceStreamingResponseHandler(
                new NoopChatExecutor(), context, "mem-1", partial -> { },
                (index, request) -> { }, (index, request) -> { },
                execution -> {
                    events.add("callback:on-tool-executed:" + execution.request().id());
                    if (execution.request().id().equals("write-1")) {
                        throw new IllegalStateException("首张跳过卡片回调失败");
                    }
                },
                response -> fail("不应完成普通响应"),
                throwable -> fail("不应改报普通错误"), memory, new TokenUsage(), List.of(),
                Map.of("writeFile", (request, memoryId) -> "不应执行"),
                null, "method-1", controller, ToolExecutionGuard.direct());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> handler.onCompleteResponse(responseWithTools(
                        tool("write-1", "writeFile"),
                        tool("write-2", "writeFile"))));

        assertEquals("首张跳过卡片回调失败", failure.getMessage());
        assertEquals(1, terminations.get());
        assertEquals(List.of(
                "memory:add-tool-result:write-1",
                "callback:on-tool-executed:write-1",
                "memory:add-tool-result:write-2",
                "callback:on-tool-executed:write-2"), events);
    }

    @Test
    void cancellationDropsLateCallbacksAndDoesNotMutateMemory() throws Exception {
        AiServiceContext context = new AiServiceContext(Object.class);
        context.streamingChatModel = new CapturingStreamingChatModel();
        List<String> events = new ArrayList<>();
        RecordingChatMemory memory = new RecordingChatMemory(events);
        StreamingRequestController controller = new StreamingRequestController();
        AiServiceStreamingResponseHandler handler = newHandler(
                context, Map.of("writeFile", (request, memoryId) -> "ok"), memory,
                events, controller, ToolExecutionGuard.direct());
        controller.cancel();

        handler.onPartialResponse("late");
        handler.onCompleteResponse(responseWithTools(tool("write", "writeFile")));

        assertTrue(events.isEmpty());
        assertTrue(memory.messages().isEmpty());
    }

    @Test
    void 旧代SDK六类迟到入口必须全部丢弃且不改变记忆或终态() {
        AiServiceContext context = new AiServiceContext(Object.class);
        context.streamingChatModel = new CapturingStreamingChatModel();
        StreamingRequestController controller = new StreamingRequestController();
        assertTrue(controller.beforeModelRequest());
        long oldGeneration = controller.latestModelRequestGeneration();
        assertTrue(controller.beforeModelRequest(oldGeneration));
        AtomicInteger handleCancellations = new AtomicInteger();
        AtomicInteger userCallbacks = new AtomicInteger();
        RecordingChatMemory memory = new RecordingChatMemory(
                new ArrayList<>());
        AiServiceStreamingResponseHandler oldHandler =
                new AiServiceStreamingResponseHandler(
                        new NoopChatExecutor(), context, "mem-1",
                        ignored -> userCallbacks.incrementAndGet(),
                        (index, request) -> userCallbacks.incrementAndGet(),
                        (index, request) -> userCallbacks.incrementAndGet(),
                        ignored -> userCallbacks.incrementAndGet(),
                        ignored -> userCallbacks.incrementAndGet(),
                        ignored -> userCallbacks.incrementAndGet(),
                        memory, new TokenUsage(), List.of(),
                        Map.of("writeFile", (request, memoryId) -> "不应执行"),
                        null, "method-1", controller,
                        ToolExecutionGuard.direct(), oldGeneration);
        ToolExecutionRequest lateTool = tool("late", "writeFile");

        oldHandler.onRequestHandle(handleCancellations::incrementAndGet);
        oldHandler.onPartialResponse("旧代正文");
        oldHandler.onPartialToolExecutionRequest(0, lateTool);
        oldHandler.onCompleteToolExecutionRequest(0, lateTool);
        oldHandler.onCompleteResponse(responseWithTools(lateTool));
        oldHandler.onError(new IllegalStateException("旧代错误"));

        assertEquals(1, handleCancellations.get());
        assertEquals(0, userCallbacks.get());
        assertTrue(memory.messages().isEmpty());
        assertTrue(controller.isOpen());
        assertNull(controller.controlledTermination());
    }

    @Test
    void cancellationDuringGuardDropsLateToolResultAndReleasesCallback() throws Exception {
        AiServiceContext context = new AiServiceContext(Object.class);
        context.streamingChatModel = new CapturingStreamingChatModel();
        List<String> events = new ArrayList<>();
        RecordingChatMemory memory = new RecordingChatMemory(events);
        StreamingRequestController controller = new StreamingRequestController();
        ToolExecutionGuard cancellingGuard = (toolName, memoryId, action) -> {
            controller.cancel();
            return new ToolExecutionGuard.GuardedToolExecution("late-result", null);
        };
        AiServiceStreamingResponseHandler handler = newHandler(
                context, Map.of("writeFile", (request, memoryId) -> "ok"), memory,
                events, controller, cancellingGuard);

        handler.onCompleteResponse(responseWithTools(tool("write", "writeFile")));

        assertEquals(2, memory.messages().size(),
                "AI 工具请求必须由取消结果配对闭合");
        ToolExecutionResultMessage cancellation =
                (ToolExecutionResultMessage) memory.messages().get(1);
        assertTrue(cancellation.text().contains("请求已经取消"));
        assertFalse(cancellation.text().contains("late-result"));
        assertTrue(controller.awaitQuiescence(java.time.Duration.ofMillis(50)));
    }

    @Test
    void completeToolRequestCallbackIsDroppedAfterCancellation() throws Exception {
        AiServiceContext context = new AiServiceContext(Object.class);
        context.streamingChatModel = new CapturingStreamingChatModel();
        AtomicInteger callbacks = new AtomicInteger();
        StreamingRequestController controller = new StreamingRequestController();
        AiServiceStreamingResponseHandler handler = new AiServiceStreamingResponseHandler(
                new NoopChatExecutor(), context, "mem-1", partial -> { },
                (index, request) -> callbacks.incrementAndGet(),
                (index, request) -> callbacks.incrementAndGet(), execution -> { },
                response -> { }, throwable -> fail("Should not raise onError"),
                MessageWindowChatMemory.withMaxMessages(10), new TokenUsage(),
                List.of(), Map.of(), null, "method-1", controller,
                ToolExecutionGuard.direct());
        controller.cancel();

        handler.onPartialToolExecutionRequest(0, tool("partial", "writeFile"));
        handler.onCompleteToolExecutionRequest(0, tool("complete", "writeFile"));

        assertEquals(0, callbacks.get());
    }

    @Test
    void 工具请求消息写入失败必须回滚批次并唯一报告错误() throws Exception {
        AiServiceContext context = new AiServiceContext(Object.class);
        CapturingStreamingChatModel model = new CapturingStreamingChatModel();
        context.streamingChatModel = model;
        StreamingRequestController controller = new StreamingRequestController();
        IllegalStateException persistenceFailure =
                new IllegalStateException("工具请求 memory 写入失败");
        List<ChatMessage> messages = new ArrayList<>();
        ChatMemory failingMemory = new ChatMemory() {
            @Override
            public Object id() {
                return "failing-tool-request";
            }

            @Override
            public void add(ChatMessage message) {
                if (message instanceof AiMessage aiMessage
                        && aiMessage.hasToolExecutionRequests()) {
                    throw persistenceFailure;
                }
                messages.add(message);
            }

            @Override
            public List<ChatMessage> messages() {
                return List.copyOf(messages);
            }

            @Override
            public void clear() {
                messages.clear();
            }
        };
        AtomicInteger executorCalls = new AtomicInteger();
        AtomicInteger toolRequestCallbacks = new AtomicInteger();
        AtomicInteger toolResultCallbacks = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        AtomicReference<Throwable> observedError = new AtomicReference<>();
        AiServiceStreamingResponseHandler handler =
                new AiServiceStreamingResponseHandler(
                        new NoopChatExecutor(), context, "mem-1",
                        partial -> { }, (index, request) -> { },
                        (index, request) ->
                                toolRequestCallbacks.incrementAndGet(),
                        execution -> toolResultCallbacks.incrementAndGet(),
                        response -> fail("工具请求写入失败后不得普通完成"),
                        error -> {
                            observedError.set(error);
                            errors.incrementAndGet();
                        }, failingMemory, new TokenUsage(), List.of(),
                        Map.of("writeFile", (request, memoryId) -> {
                            executorCalls.incrementAndGet();
                            return "不应执行";
                        }), null, "method-1", controller,
                        ToolExecutionGuard.direct());

        assertDoesNotThrow(() -> handler.onCompleteResponse(
                responseWithTools(tool("write", "writeFile"))));
        handler.onError(new IllegalStateException("迟到错误"));

        assertSame(persistenceFailure, observedError.get());
        assertEquals(1, errors.get());
        assertEquals(0, executorCalls.get());
        assertEquals(0, toolRequestCallbacks.get());
        assertEquals(0, toolResultCallbacks.get());
        assertEquals(0, model.chatInvocations);
        assertTrue(messages.isEmpty());
        assertFalse(controller.isOpen());
        assertNull(controller.controlledTermination());
        assertNull(org.springframework.test.util.ReflectionTestUtils.getField(
                controller, "activeToolBatch"),
                "失败的 PREPARED 批次必须回滚，不能泄漏活动票据");
    }

    @Test
    void 工具结果消息写入失败必须停止后续执行并唯一报告错误() throws Exception {
        AiServiceContext context = new AiServiceContext(Object.class);
        CapturingStreamingChatModel model = new CapturingStreamingChatModel();
        context.streamingChatModel = model;
        StreamingRequestController controller = new StreamingRequestController();
        IllegalStateException persistenceFailure =
                new IllegalStateException("工具结果 memory 写入失败");
        List<ChatMessage> messages = new ArrayList<>();
        ChatMemory failingMemory = new ChatMemory() {
            @Override
            public Object id() {
                return "failing-tool-result";
            }

            @Override
            public void add(ChatMessage message) {
                if (message instanceof ToolExecutionResultMessage) {
                    throw persistenceFailure;
                }
                messages.add(message);
            }

            @Override
            public List<ChatMessage> messages() {
                return List.copyOf(messages);
            }

            @Override
            public void clear() {
                messages.clear();
            }
        };
        AtomicInteger executorCalls = new AtomicInteger();
        AtomicInteger toolResultCallbacks = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        AtomicReference<Throwable> observedError = new AtomicReference<>();
        ToolExecutor executor = (request, memoryId) -> {
            executorCalls.incrementAndGet();
            return "真实结果:" + request.id();
        };
        AiServiceStreamingResponseHandler handler =
                new AiServiceStreamingResponseHandler(
                        new NoopChatExecutor(), context, "mem-1",
                        partial -> { }, (index, request) -> { },
                        (index, request) -> { },
                        execution -> toolResultCallbacks.incrementAndGet(),
                        response -> fail("工具结果写入失败后不得普通完成"),
                        error -> {
                            observedError.set(error);
                            errors.incrementAndGet();
                        }, failingMemory, new TokenUsage(), List.of(),
                        Map.of("writeFile", executor, "readFile", executor),
                        null, "method-1", controller,
                        ToolExecutionGuard.direct());

        assertDoesNotThrow(() -> handler.onCompleteResponse(
                responseWithTools(
                        tool("first", "writeFile"),
                        tool("second", "readFile"))));
        handler.onError(new IllegalStateException("迟到错误"));

        assertSame(persistenceFailure, observedError.get());
        assertEquals(1, errors.get());
        assertEquals(1, executorCalls.get(),
                "首个结果无法持久化后不得继续制造第二个外部副作用");
        assertEquals(0, toolResultCallbacks.get(),
                "结果未持久化前不得通知用户结果回调");
        assertEquals(0, model.chatInvocations);
        assertEquals(1, messages.size(),
                "通用 ChatMemory 无事务回滚能力，测试只允许保留已成功写入的请求消息");
        assertTrue(messages.getFirst() instanceof AiMessage);
        assertFalse(controller.isOpen());
        assertNull(controller.controlledTermination());
        assertNull(org.springframework.test.util.ReflectionTestUtils.getField(
                controller, "activeToolBatch"),
                "结果持久化失败必须回滚活动批次，不能伪装已提交");
    }

    @Test
    void 受控终止工具结果写入失败不得发布原终态() throws Exception {
        AiServiceContext context = new AiServiceContext(Object.class);
        context.streamingChatModel = new CapturingStreamingChatModel();
        StreamingRequestController controller = new StreamingRequestController();
        IllegalStateException persistenceFailure =
                new IllegalStateException("终止结果 memory 写入失败");
        List<ChatMessage> messages = new ArrayList<>();
        ChatMemory failingMemory = new ChatMemory() {
            @Override
            public Object id() {
                return "failing-terminal-result";
            }

            @Override
            public void add(ChatMessage message) {
                if (message instanceof ToolExecutionResultMessage) {
                    throw persistenceFailure;
                }
                messages.add(message);
            }

            @Override
            public List<ChatMessage> messages() {
                return List.copyOf(messages);
            }

            @Override
            public void clear() {
                messages.clear();
            }
        };
        AtomicInteger terminations = new AtomicInteger();
        AtomicInteger finalPartials = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        AtomicReference<Throwable> observedError = new AtomicReference<>();
        controller.onControlledTermination(ignored ->
                terminations.incrementAndGet());
        ToolExecutionGuard terminalGuard = (toolName, memoryId, action) ->
                new ToolExecutionGuard.GuardedToolExecution(
                        action.get(),
                        new ToolLoopTerminationProtocol.ControlledTermination(
                                ToolLoopTerminationProtocol
                                        .ControlledTerminationReason.BUILD_SUCCEEDED,
                                "项目已生成并构建成功。"));
        AiServiceStreamingResponseHandler handler =
                new AiServiceStreamingResponseHandler(
                        new NoopChatExecutor(), context, "mem-1",
                        partial -> finalPartials.incrementAndGet(),
                        (index, request) -> { }, (index, request) -> { },
                        execution -> { }, response -> fail("不得普通完成"),
                        error -> {
                            observedError.set(error);
                            errors.incrementAndGet();
                        }, failingMemory, new TokenUsage(), List.of(),
                        Map.of("buildProject", (request, memoryId) ->
                                BUILD_SUCCESS), null, "method-1", controller,
                        terminalGuard);

        assertDoesNotThrow(() -> handler.onCompleteResponse(
                responseWithTools(tool("build", "buildProject"))));

        assertSame(persistenceFailure, observedError.get());
        assertEquals(1, errors.get());
        assertEquals(0, terminations.get(),
                "终止结果未持久化时不得发布 BUILD_SUCCEEDED");
        assertEquals(0, finalPartials.get(),
                "终止结果未持久化时不得下发终态正文");
        assertNull(controller.controlledTermination());
        assertEquals(1, messages.size());
    }

    @Test
    void thirdBuildFailureTerminatesButOrdinaryFailureContinues() throws Exception {
        assertBuildResultModelCalls(BUILD_THIRD_FAILURE, 0);
        assertBuildResultModelCalls(BUILD_FIRST_FAILURE, 1);
    }

    @Test
    void nonBuildSpoofedTerminationAndWrongProtocolContinueNormally() throws Exception {
        assertToolResultModelCalls("readFile", BUILD_SUCCESS, 1);
        assertToolResultModelCalls("buildProject",
                BUILD_SUCCESS.replace("vue-build-tool/v1", "vue-build-tool/v2"), 1);
        assertToolResultModelCalls("buildProject", "not-json", 1);
    }

    @Test
    void ordinaryMemoryFailureReportsErrorWithoutCompleteOrCancellationOverride() {
        AiServiceContext context = new AiServiceContext(Object.class);
        context.streamingChatModel = new CapturingStreamingChatModel();
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger cancellations = new AtomicInteger();
        StreamingRequestController controller = new StreamingRequestController();
        controller.onControlledTermination(termination -> cancellations.incrementAndGet());
        ChatMemory failingMemory = new ChatMemory() {
            @Override public Object id() { return "failing"; }
            @Override public void add(ChatMessage message) {
                controller.cancel();
                throw new IllegalStateException("memory 写入失败");
            }
            @Override public List<ChatMessage> messages() { return List.of(); }
            @Override public void clear() { }
        };
        AiServiceStreamingResponseHandler handler = ordinaryHandler(
                context, failingMemory, partial -> { },
                response -> completed.incrementAndGet(),
                error -> {
                    assertEquals("memory 写入失败", error.getMessage());
                    errors.incrementAndGet();
                }, controller);

        handler.onCompleteResponse(ordinaryResponse("完成"));
        handler.onError(new IllegalStateException("迟到错误"));

        assertEquals(0, completed.get());
        assertEquals(1, errors.get());
        assertEquals(0, cancellations.get(), "正常完成 claim 后取消不能覆盖结果");
    }

    @Test
    void ordinaryCompleteCallbackFailureReportsErrorExactlyOnce() {
        AiServiceContext context = new AiServiceContext(Object.class);
        context.streamingChatModel = new CapturingStreamingChatModel();
        AtomicInteger successfulCompletes = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        StreamingRequestController controller = new StreamingRequestController();
        AiServiceStreamingResponseHandler handler = ordinaryHandler(
                context, MessageWindowChatMemory.withMaxMessages(10), partial -> { },
                response -> {
                    throw new IllegalStateException("complete 回调失败");
                },
                error -> {
                    assertEquals("complete 回调失败", error.getMessage());
                    errors.incrementAndGet();
                }, controller);

        handler.onCompleteResponse(ordinaryResponse("完成"));
        handler.onError(new IllegalStateException("迟到错误"));

        assertEquals(0, successfulCompletes.get());
        assertEquals(1, errors.get());
    }

    @Test
    void ordinaryOutputGuardrailFailureReportsErrorWithoutComplete() {
        AiServiceContext context = new AiServiceContext(Object.class);
        context.streamingChatModel = new CapturingStreamingChatModel();
        configureOutputGuardrails(context, new IllegalStateException("输出护栏失败"));
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        StreamingRequestController controller = new StreamingRequestController();
        AiServiceStreamingResponseHandler handler = new AiServiceStreamingResponseHandler(
                new NoopChatExecutor(), context, "mem-1", partial -> { },
                (index, request) -> { }, (index, request) -> { }, execution -> { },
                response -> completed.incrementAndGet(),
                error -> {
                    assertEquals("输出护栏失败", error.getMessage());
                    errors.incrementAndGet();
                },
                MessageWindowChatMemory.withMaxMessages(10), new TokenUsage(),
                List.of(), Map.of(),
                dev.langchain4j.guardrail.GuardrailRequestParams.builder()
                        .userMessageTemplate("测试").variables(Map.of()).build(),
                "method-1", controller, ToolExecutionGuard.direct());

        handler.onCompleteResponse(ordinaryResponse("完成"));

        assertEquals(0, completed.get());
        assertEquals(1, errors.get());
    }

    @Test
    void ordinaryBufferedPartialFailureReportsErrorWithoutComplete() {
        AiServiceContext context = new AiServiceContext(Object.class);
        context.streamingChatModel = new CapturingStreamingChatModel();
        configureOutputGuardrails(context, null);
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        StreamingRequestController controller = new StreamingRequestController();
        AiServiceStreamingResponseHandler handler = ordinaryHandler(
                context, MessageWindowChatMemory.withMaxMessages(10), partial -> {
                    throw new IllegalStateException("缓冲 partial 回调失败");
                },
                response -> completed.incrementAndGet(),
                error -> {
                    assertEquals("缓冲 partial 回调失败", error.getMessage());
                    errors.incrementAndGet();
                }, controller);

        handler.onPartialResponse("缓冲内容");
        handler.onCompleteResponse(ordinaryResponse("完成"));

        assertEquals(0, completed.get());
        assertEquals(1, errors.get());
    }

    @Test
    void outputGuardrailBufferNeverReceivesPseudoToolSuffix()
            throws Exception {
        AiServiceContext context = new AiServiceContext(Object.class);
        context.streamingChatModel = new CapturingStreamingChatModel();
        configureOutputGuardrails(context, null);
        MessageWindowChatMemory memory =
                MessageWindowChatMemory.withMaxMessages(20);
        List<String> partials = new ArrayList<>();
        StreamingRequestController controller =
                new StreamingRequestController();
        ToolProtocolRecoveryCoordinator coordinator =
                new ToolProtocolRecoveryCoordinator(
                        new ToolProtocolRecoveryPolicy(
                                Set.of("writeFile"), ignored -> { }),
                        Set.of("writeFile"));
        AiServiceStreamingResponseHandler handler =
                new AiServiceStreamingResponseHandler(
                        new NoopChatExecutor(), context, "mem-1",
                        partials::add, (index, request) -> { },
                        (index, request) -> { }, execution -> { },
                        response -> { }, throwable -> fail(
                        "Guardrail 混合响应不应报错", throwable),
                        memory, new TokenUsage(),
                        List.of(ToolSpecification.builder()
                                .name("writeFile").build()),
                        Map.of("writeFile", (request, memoryId) -> "成功"),
                        null, "method-1", controller,
                        ToolExecutionGuard.direct(),
                        controller.latestModelRequestGeneration(),
                        null, null, coordinator);
        ToolExecutionRequest request =
                tool("guardrail-mixed", "writeFile");
        String pseudoTool =
                "[工具调用] writeFile {\"path\":\"src/App.vue\"}";

        handler.onCompleteResponse(responseWithTextAndTools(
                "可信前缀" + pseudoTool, request));

        @SuppressWarnings("unchecked")
        List<String> buffered = (List<String>)
                org.springframework.test.util.ReflectionTestUtils.getField(
                        handler, "responseBuffer");
        AiMessage stored = memory.messages().stream()
                .filter(AiMessage.class::isInstance)
                .map(AiMessage.class::cast)
                .filter(AiMessage::hasToolExecutionRequests)
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("可信前缀"), buffered);
        assertTrue(partials.stream()
                .noneMatch(text -> text.contains(pseudoTool)));
        assertEquals("可信前缀", stored.text());
        assertFalse(stored.text().contains(pseudoTool));
    }

    @Test
    void ordinaryResponseSuccessCompletesOnceWithoutError() {
        AiServiceContext context = new AiServiceContext(Object.class);
        context.streamingChatModel = new CapturingStreamingChatModel();
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        StreamingRequestController controller = new StreamingRequestController();
        AiServiceStreamingResponseHandler handler = ordinaryHandler(
                context, MessageWindowChatMemory.withMaxMessages(10), partial -> { },
                response -> completed.incrementAndGet(),
                error -> errors.incrementAndGet(), controller);

        handler.onCompleteResponse(ordinaryResponse("完成"));
        handler.onCompleteResponse(ordinaryResponse("重复完成"));

        assertEquals(1, completed.get());
        assertEquals(0, errors.get());
    }

    private void assertGateBlockDoesNotDelayCancellation(
            AiServiceStreamingResponseHandler handler,
            StreamingRequestController controller,
            CountDownLatch gateEntered,
            CountDownLatch releaseGate,
            CountDownLatch cancellationReturned) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> completion = executor.submit(() ->
                    handler.onCompleteResponse(responseWithTools(
                            tool("blocked-gate-tool", "writeFile"))));
            Future<?> cancellation = null;
            boolean returnedQuickly;
            try {
                assertTrue(gateEntered.await(2, TimeUnit.SECONDS),
                        "模型请求门禁必须先进入阻塞点");
                cancellation = executor.submit(() -> {
                    controller.cancel();
                    cancellationReturned.countDown();
                });
                returnedQuickly = cancellationReturned.await(
                        SHORT_CANCEL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } finally {
                releaseGate.countDown();
            }
            completion.get(2, TimeUnit.SECONDS);
            if (cancellation != null) {
                cancellation.get(2, TimeUnit.SECONDS);
            }

            assertTrue(returnedQuickly,
                    "cancel() 不得等待阻塞的模型请求门禁");
            assertTrue(controller.isCancelled());
        }
    }

    private static void awaitLatch(
            CountDownLatch latch, String timeoutMessage) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                fail(timeoutMessage);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail(timeoutMessage, exception);
        }
    }

    private void assertBuildResultModelCalls(String result, int expectedCalls) throws Exception {
        assertToolResultModelCalls("buildProject", result, expectedCalls);
    }

    private void assertToolResultModelCalls(
            String toolName, String result, int expectedCalls) throws Exception {
        AiServiceContext context = new AiServiceContext(Object.class);
        CapturingStreamingChatModel model = new CapturingStreamingChatModel();
        context.streamingChatModel = model;
        AiServiceStreamingResponseHandler handler = newHandler(
                context, Map.of(toolName, (request, memoryId) -> result));

        handler.onCompleteResponse(responseWithTools(tool("call", toolName)));

        assertEquals(expectedCalls, model.chatInvocations);
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

    private static AiServiceStreamingResponseHandler newHandler(
            AiServiceContext context,
            Map<String, ToolExecutor> toolExecutors,
            ChatMemory memory,
            List<String> events,
            StreamingRequestController controller,
            ToolExecutionGuard guard) throws Exception {
        return new AiServiceStreamingResponseHandler(
                new NoopChatExecutor(), context, "mem-1",
                partial -> events.add("callback:on-partial-final-text"),
                (index, request) -> { }, (index, request) -> { },
                execution -> events.add("callback:on-tool-executed:"
                        + execution.request().name()),
                response -> events.add("callback:on-complete"),
                throwable -> fail("Should not raise onError"), memory,
                new TokenUsage(), List.of(), toolExecutors, null, "method-1",
                controller, guard);
    }

    private static AiServiceStreamingResponseHandler gatedHandler(
            AiServiceContext context,
            ChatMemory memory,
            StreamingRequestController controller,
            ModelRequestGate gate,
            ModelRequestGate.ContinuationGate continuationGate,
            java.util.function.Consumer<Throwable> errorHandler) {
        return new AiServiceStreamingResponseHandler(
                new NoopChatExecutor(), context, "mem-1",
                partial -> { },
                (index, request) -> { },
                (index, request) -> { },
                execution -> { },
                response -> { },
                errorHandler,
                memory,
                new TokenUsage(),
                List.<ToolSpecification>of(),
                Map.<String, ToolExecutor>of(
                        "writeFile", (request, memoryId) ->
                        "大工具结果".repeat(10_000)),
                null,
                "method-1",
                controller,
                ToolExecutionGuard.direct(),
                controller.latestModelRequestGeneration(),
                gate,
                continuationGate);
    }

    private static ModelRequestGate.Decision allowed(
            List<ChatMessage> messages) {
        return new ModelRequestGate.Decision(
                ModelRequestGate.Status.ALLOWED,
                messages,
                12_000,
                "");
    }

    private static ChatResponse responseWithTools(ToolExecutionRequest... requests) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(List.of(requests)))
                .metadata(ChatResponseMetadata.builder().tokenUsage(new TokenUsage()).build())
                .build();
    }

    private static ChatResponse responseWithTextAndTools(
            String text, ToolExecutionRequest... requests) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(text, List.of(requests)))
                .metadata(ChatResponseMetadata.builder()
                        .tokenUsage(new TokenUsage()).build())
                .build();
    }

    private static ChatResponse ordinaryResponse(String text) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(text))
                .metadata(ChatResponseMetadata.builder().tokenUsage(new TokenUsage()).build())
                .build();
    }

    private static AiServiceStreamingResponseHandler ordinaryHandler(
            AiServiceContext context,
            ChatMemory memory,
            java.util.function.Consumer<String> partial,
            java.util.function.Consumer<ChatResponse> complete,
            java.util.function.Consumer<Throwable> error,
            StreamingRequestController controller) {
        return new AiServiceStreamingResponseHandler(
                new NoopChatExecutor(), context, "mem-1", partial,
                (index, request) -> { }, (index, request) -> { }, execution -> { },
                complete, error, memory, new TokenUsage(), List.of(), Map.of(),
                null, "method-1", controller, ToolExecutionGuard.direct());
    }

    @SuppressWarnings("unchecked")
    private static void configureOutputGuardrails(
            AiServiceContext context, RuntimeException executionFailure) {
        dev.langchain4j.service.guardrail.GuardrailService guardrails =
                org.mockito.Mockito.mock(
                        dev.langchain4j.service.guardrail.GuardrailService.class);
        org.mockito.Mockito.when(guardrails.hasOutputGuardrails("method-1"))
                .thenReturn(true);
        if (executionFailure != null) {
            org.mockito.Mockito.when(guardrails.executeGuardrails(
                            org.mockito.ArgumentMatchers.eq("method-1"),
                            org.mockito.ArgumentMatchers.any(
                                    dev.langchain4j.guardrail.OutputGuardrailRequest.class)))
                    .thenThrow(executionFailure);
        }
        java.util.concurrent.atomic.AtomicReference<
                dev.langchain4j.service.guardrail.GuardrailService> reference =
                (java.util.concurrent.atomic.AtomicReference<
                        dev.langchain4j.service.guardrail.GuardrailService>)
                        org.springframework.test.util.ReflectionTestUtils.getField(
                                context, "guardrailService");
        reference.set(guardrails);
    }

    private static ToolExecutionRequest tool(String id, String name) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments("{}").build();
    }

    private static boolean hasUnpairedToolRequests(
            List<ChatMessage> messages) {
        Set<String> pendingIds = new java.util.HashSet<>();
        for (ChatMessage message : messages) {
            if (message instanceof AiMessage aiMessage
                    && aiMessage.hasToolExecutionRequests()) {
                aiMessage.toolExecutionRequests().stream()
                        .map(ToolExecutionRequest::id)
                        .forEach(pendingIds::add);
            } else if (message instanceof ToolExecutionResultMessage result) {
                pendingIds.remove(result.id());
            }
        }
        return !pendingIds.isEmpty();
    }

    private static final class RecordingChatMemory implements ChatMemory {

        private final List<String> events;
        private final List<ChatMessage> messages = new ArrayList<>();

        private RecordingChatMemory(List<String> events) {
            this.events = events;
        }

        @Override
        public Object id() {
            return "recording";
        }

        @Override
        public void add(ChatMessage message) {
            messages.add(message);
            if (message instanceof ToolExecutionResultMessage result) {
                events.add("memory:add-tool-result:" + result.id());
            } else if (message instanceof AiMessage ai && !ai.hasToolExecutionRequests()) {
                events.add("memory:add-final-ai");
            }
        }

        @Override
        public List<ChatMessage> messages() {
            return List.copyOf(messages);
        }

        @Override
        public void clear() {
            messages.clear();
        }
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
