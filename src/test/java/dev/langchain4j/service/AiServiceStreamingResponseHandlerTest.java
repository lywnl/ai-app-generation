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
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AiServiceStreamingResponseHandlerTest {

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
    void 工具续调用必须等待门禁完成且等待期间释放当前模型回调票据() {
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
        ModelRequestGate gate = request -> {
            gateRequest.set(request);
            return preparation;
        };
        AiServiceStreamingResponseHandler handler = gatedHandler(
                context, memory, controller, gate, action -> {
                    action.run();
                    return true;
                }, error -> fail("不应触发错误回调", error));

        handler.onCompleteResponse(responseWithTools(
                tool("large-tool", "writeFile")));

        assertEquals(0, model.chatInvocations,
                "工具结果加入后必须先等待统一门禁");
        assertTrue(controller.awaitQuiescence(
                        java.time.Duration.ofMillis(100)),
                "prepare 返回后必须立即释放当前 SDK callback 票据");
        assertSame(memory, gateRequest.get().latestMemory().get());

        List<ChatMessage> compressedMessages = List.of(
                UserMessage.from("压缩后的工具上下文"));
        preparation.complete(new ModelRequestGate.Decision(
                ModelRequestGate.Status.ALLOWED,
                compressedMessages,
                12_000,
                ""));

        assertEquals(1, model.chatInvocations);
        assertEquals(compressedMessages, model.lastChatRequest.messages());
    }

    @Test
    void 已完成门禁结果也必须先释放旧模型回调票据再续调() {
        AiServiceContext context = new AiServiceContext(Object.class);
        CapturingStreamingChatModel model = new CapturingStreamingChatModel();
        context.streamingChatModel = model;
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(100);
        StreamingRequestController controller = new StreamingRequestController();
        assertTrue(controller.beforeModelRequest());
        AtomicBoolean oldCallbackReleased = new AtomicBoolean();
        ModelRequestGate gate = request -> CompletableFuture.completedFuture(
                allowed(request.latestMemory().get().messages()));
        AiServiceStreamingResponseHandler handler = gatedHandler(
                context, memory, controller, gate, action -> {
                    oldCallbackReleased.set(controller.awaitQuiescence(
                            java.time.Duration.ZERO));
                    action.run();
                    return true;
                }, error -> fail("不应触发错误回调", error));

        handler.onCompleteResponse(responseWithTools(
                tool("completed-gate-tool", "writeFile")));

        assertTrue(oldCallbackReleased.get(),
                "即使 prepare 返回已完成 Future，也必须先退出旧 SDK callback");
        assertEquals(1, model.chatInvocations);
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
            ModelRequestGate.Status status) {
        AiServiceContext context = new AiServiceContext(Object.class);
        CapturingStreamingChatModel model = new CapturingStreamingChatModel();
        context.streamingChatModel = model;
        StreamingRequestController controller = new StreamingRequestController();
        assertTrue(controller.beforeModelRequest());
        AtomicReference<Throwable> error = new AtomicReference<>();
        ModelRequestGate gate = request -> CompletableFuture.completedFuture(
                new ModelRequestGate.Decision(
                        status, request.latestMemory().get().messages(),
                        32_768, "上下文门禁安全提示"));
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

        assertEquals(0, model.chatInvocations);
        assertEquals("上下文门禁安全提示", error.get().getMessage());
    }

    @Test
    void 取消期间完成的晚到门禁结果不得启动新模型请求() {
        AiServiceContext context = new AiServiceContext(Object.class);
        CapturingStreamingChatModel model = new CapturingStreamingChatModel();
        context.streamingChatModel = model;
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(100);
        StreamingRequestController controller = new StreamingRequestController();
        assertTrue(controller.beforeModelRequest());
        CompletableFuture<ModelRequestGate.Decision> preparation =
                new CompletableFuture<>();
        AiServiceStreamingResponseHandler handler = gatedHandler(
                context, memory, controller, request -> preparation,
                action -> {
                    action.run();
                    return true;
                }, error -> fail("取消后不应报告普通错误", error));

        handler.onCompleteResponse(responseWithTools(
                tool("cancelled-tool", "writeFile")));
        controller.cancel();
        preparation.complete(allowed(memory.messages()));

        assertEquals(0, model.chatInvocations);
    }

    @Test
    void 门禁结果不是永久授权回合关门后不得启动新模型请求() {
        AiServiceContext context = new AiServiceContext(Object.class);
        CapturingStreamingChatModel model = new CapturingStreamingChatModel();
        context.streamingChatModel = model;
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(100);
        StreamingRequestController controller = new StreamingRequestController();
        assertTrue(controller.beforeModelRequest());
        CompletableFuture<ModelRequestGate.Decision> preparation =
                new CompletableFuture<>();
        AtomicBoolean continuationOpen = new AtomicBoolean(true);
        AiServiceStreamingResponseHandler handler = gatedHandler(
                context, memory, controller, request -> preparation,
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

        assertEquals(0, model.chatInvocations);
    }

    @Test
    void 旧请求代次的晚到门禁结果不得重复启动模型() {
        AiServiceContext context = new AiServiceContext(Object.class);
        CapturingStreamingChatModel model = new CapturingStreamingChatModel();
        context.streamingChatModel = model;
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(100);
        StreamingRequestController controller = new StreamingRequestController();
        assertTrue(controller.beforeModelRequest());
        CompletableFuture<ModelRequestGate.Decision> preparation =
                new CompletableFuture<>();
        AiServiceStreamingResponseHandler handler = gatedHandler(
                context, memory, controller, request -> preparation,
                action -> {
                    action.run();
                    return true;
                }, error -> fail("旧代次不应报告普通错误", error));

        handler.onCompleteResponse(responseWithTools(
                tool("stale-generation-tool", "writeFile")));
        assertTrue(controller.beforeModelRequest(1L));
        preparation.complete(allowed(memory.messages()));

        assertEquals(0, model.chatInvocations);
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

        assertEquals(2, memory.messages().size(), "AI 工具请求必须由取消结果配对闭合");
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
