package dev.langchain4j.service;

import dev.langchain4j.Internal;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.guardrail.ChatExecutor;
import dev.langchain4j.guardrail.GuardrailRequestParams;
import dev.langchain4j.guardrail.OutputGuardrailRequest;
import dev.langchain4j.internal.ToolArgumentsJsonNormalizer;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingRequestHandle;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.tool.ToolExecution;
import dev.langchain4j.service.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static dev.langchain4j.internal.Utils.copy;
import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;

/**
 * Handles response from a language model for AI Service that is streamed token-by-token. Handles both regular (text)
 * responses and responses with the request to execute one or multiple tools.
 */
@Internal
class AiServiceStreamingResponseHandler implements StreamingChatResponseHandler {
    private static final Logger LOG = LoggerFactory.getLogger(AiServiceStreamingResponseHandler.class);

    private final ChatExecutor chatExecutor;
    private final AiServiceContext context;
    private final Object memoryId;
    private final GuardrailRequestParams commonGuardrailParams;
    private final Object methodKey;

    private final Consumer<String> partialResponseHandler;
    private final BiConsumer<Integer, ToolExecutionRequest> partialToolExecutionRequestHandler;
    private final BiConsumer<Integer, ToolExecutionRequest> completeToolExecutionRequestHandler;
    private final Consumer<ToolExecution> toolExecutionHandler;
    private final Consumer<ChatResponse> completeResponseHandler;

    private final Consumer<Throwable> errorHandler;

    private final ChatMemory temporaryMemory;
    private final TokenUsage tokenUsage;

    private final List<ToolSpecification> toolSpecifications;
    private final Map<String, ToolExecutor> toolExecutors;
    private final List<String> responseBuffer = new ArrayList<>();
    private final boolean hasOutputGuardrails;
    private final StreamingRequestController requestController;
    private final ToolExecutionGuard toolExecutionGuard;
    private final long requestGeneration;
    private final ModelRequestGate modelRequestGate;
    private final ModelRequestGate.ContinuationGate continuationGate;
    private final Set<String> completedToolRequestIds =
            ConcurrentHashMap.newKeySet();

    AiServiceStreamingResponseHandler(
            ChatExecutor chatExecutor,
            AiServiceContext context,
            Object memoryId,
            Consumer<String> partialResponseHandler,
            BiConsumer<Integer, ToolExecutionRequest> partialToolExecutionRequestHandler,
            BiConsumer<Integer, ToolExecutionRequest> completeToolExecutionRequestHandler,
            Consumer<ToolExecution> toolExecutionHandler,
            Consumer<ChatResponse> completeResponseHandler,
            Consumer<Throwable> errorHandler,
            ChatMemory temporaryMemory,
            TokenUsage tokenUsage,
            List<ToolSpecification> toolSpecifications,
            Map<String, ToolExecutor> toolExecutors,
            GuardrailRequestParams commonGuardrailParams,
            Object methodKey) {
        this(chatExecutor, context, memoryId, partialResponseHandler,
                partialToolExecutionRequestHandler, completeToolExecutionRequestHandler,
                toolExecutionHandler, completeResponseHandler, errorHandler,
                temporaryMemory, tokenUsage, toolSpecifications, toolExecutors,
                commonGuardrailParams, methodKey, new StreamingRequestController(),
                ToolExecutionGuard.direct(), 0L, null, null);
    }

    AiServiceStreamingResponseHandler(
            ChatExecutor chatExecutor,
            AiServiceContext context,
            Object memoryId,
            Consumer<String> partialResponseHandler,
            BiConsumer<Integer, ToolExecutionRequest> partialToolExecutionRequestHandler,
            BiConsumer<Integer, ToolExecutionRequest> completeToolExecutionRequestHandler,
            Consumer<ToolExecution> toolExecutionHandler,
            Consumer<ChatResponse> completeResponseHandler,
            Consumer<Throwable> errorHandler,
            ChatMemory temporaryMemory,
            TokenUsage tokenUsage,
            List<ToolSpecification> toolSpecifications,
            Map<String, ToolExecutor> toolExecutors,
            GuardrailRequestParams commonGuardrailParams,
            Object methodKey,
            StreamingRequestController requestController,
            ToolExecutionGuard toolExecutionGuard) {
        this(chatExecutor, context, memoryId, partialResponseHandler,
                partialToolExecutionRequestHandler, completeToolExecutionRequestHandler,
                toolExecutionHandler, completeResponseHandler, errorHandler,
                temporaryMemory, tokenUsage, toolSpecifications, toolExecutors,
                commonGuardrailParams, methodKey, requestController, toolExecutionGuard,
                requestController.latestModelRequestGeneration(), null, null);
    }

    AiServiceStreamingResponseHandler(
            ChatExecutor chatExecutor,
            AiServiceContext context,
            Object memoryId,
            Consumer<String> partialResponseHandler,
            BiConsumer<Integer, ToolExecutionRequest> partialToolExecutionRequestHandler,
            BiConsumer<Integer, ToolExecutionRequest> completeToolExecutionRequestHandler,
            Consumer<ToolExecution> toolExecutionHandler,
            Consumer<ChatResponse> completeResponseHandler,
            Consumer<Throwable> errorHandler,
            ChatMemory temporaryMemory,
            TokenUsage tokenUsage,
            List<ToolSpecification> toolSpecifications,
            Map<String, ToolExecutor> toolExecutors,
            GuardrailRequestParams commonGuardrailParams,
            Object methodKey,
            StreamingRequestController requestController,
            ToolExecutionGuard toolExecutionGuard,
            long requestGeneration) {
        this(chatExecutor, context, memoryId, partialResponseHandler,
                partialToolExecutionRequestHandler,
                completeToolExecutionRequestHandler,
                toolExecutionHandler, completeResponseHandler, errorHandler,
                temporaryMemory, tokenUsage, toolSpecifications, toolExecutors,
                commonGuardrailParams, methodKey, requestController,
                toolExecutionGuard, requestGeneration, null, null);
    }

    AiServiceStreamingResponseHandler(
            ChatExecutor chatExecutor,
            AiServiceContext context,
            Object memoryId,
            Consumer<String> partialResponseHandler,
            BiConsumer<Integer, ToolExecutionRequest> partialToolExecutionRequestHandler,
            BiConsumer<Integer, ToolExecutionRequest> completeToolExecutionRequestHandler,
            Consumer<ToolExecution> toolExecutionHandler,
            Consumer<ChatResponse> completeResponseHandler,
            Consumer<Throwable> errorHandler,
            ChatMemory temporaryMemory,
            TokenUsage tokenUsage,
            List<ToolSpecification> toolSpecifications,
            Map<String, ToolExecutor> toolExecutors,
            GuardrailRequestParams commonGuardrailParams,
            Object methodKey,
            StreamingRequestController requestController,
            ToolExecutionGuard toolExecutionGuard,
            long requestGeneration,
            ModelRequestGate modelRequestGate,
            ModelRequestGate.ContinuationGate continuationGate) {
        this.chatExecutor = ensureNotNull(chatExecutor, "chatExecutor");
        this.context = ensureNotNull(context, "context");
        this.memoryId = ensureNotNull(memoryId, "memoryId");
        this.methodKey = methodKey;

        this.partialResponseHandler = ensureNotNull(partialResponseHandler, "partialResponseHandler");
        this.partialToolExecutionRequestHandler = partialToolExecutionRequestHandler;
        this.completeToolExecutionRequestHandler = completeToolExecutionRequestHandler;
        this.completeResponseHandler = completeResponseHandler;
        this.toolExecutionHandler = toolExecutionHandler;
        this.errorHandler = errorHandler;

        this.temporaryMemory = temporaryMemory;
        this.tokenUsage = ensureNotNull(tokenUsage, "tokenUsage");
        this.commonGuardrailParams = commonGuardrailParams;

        this.toolSpecifications = copy(toolSpecifications);
        this.toolExecutors = copy(toolExecutors);
        this.hasOutputGuardrails = context.guardrailService().hasOutputGuardrails(methodKey);
        this.requestController = ensureNotNull(requestController, "requestController");
        this.toolExecutionGuard = ensureNotNull(toolExecutionGuard, "toolExecutionGuard");
        this.requestGeneration = requestGeneration;
        if ((modelRequestGate == null) != (continuationGate == null)) {
            throw new IllegalArgumentException(
                    "模型请求门禁和回合原子门必须同时安装");
        }
        this.modelRequestGate = modelRequestGate;
        this.continuationGate = continuationGate;
    }

    @Override
    public void onRequestHandle(StreamingRequestHandle handle) {
        requestController.registerRequestHandle(requestGeneration, handle);
    }

    @Override
    public void onPartialResponse(String partialResponse) {
        try (var callback = requestController.enterCallback()) {
            if (callback == null) {
                return;
            }
            if (hasOutputGuardrails) {
                requestController.runIfOpen(() -> responseBuffer.add(partialResponse));
            } else {
                requestController.runIfOpen(
                        () -> partialResponseHandler.accept(partialResponse));
            }
        }
    }

    @Override
    public void onPartialToolExecutionRequest(int index, ToolExecutionRequest partialToolExecutionRequest) {
        try (var callback = requestController.enterCallback()) {
            if (callback != null && partialToolExecutionRequestHandler != null
                    && requestController.isOpen()) {
                requestController.runIfOpen(() -> partialToolExecutionRequestHandler
                        .accept(index, partialToolExecutionRequest));
            }
        }
    }

    @Override
    public void onCompleteToolExecutionRequest(
            int index, ToolExecutionRequest completeToolExecutionRequest) {
        try (var callback = requestController.enterCallback()) {
            if (callback != null && completeToolExecutionRequestHandler != null
                    && claimCompleteToolRequest(completeToolExecutionRequest)
                    && requestController.isOpen()) {
                requestController.runIfOpen(() -> completeToolExecutionRequestHandler
                        .accept(index, completeToolExecutionRequest));
            }
        }
    }

    @Override
    public void onCompleteResponse(ChatResponse completeResponse) {
        PendingModelRequestPreparation pendingPreparation = null;
        try (var callback = requestController.enterCallback()) {
            if (callback == null) {
                return;
            }
            pendingPreparation = processCompleteResponse(completeResponse);
        }
        if (pendingPreparation != null) {
            observePreparation(pendingPreparation);
        }
    }

    private PendingModelRequestPreparation processCompleteResponse(
            ChatResponse completeResponse) {
        if (!requestController.isOpen()) {
            return null;
        }
        AiMessage aiMessage = completeResponse.aiMessage();
        if (!aiMessage.hasToolExecutionRequests()) {
            completeOrdinaryResponse(completeResponse, aiMessage);
            return null;
        }
        if (!requestController.runIfOpen(() -> addToMemory(aiMessage))) {
            return null;
        }

        if (aiMessage.hasToolExecutionRequests()) {
            List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
            for (int index = 0; index < requests.size(); index++) {
                ToolExecutionRequest toolExecutionRequest = requests.get(index);
                if (!requestController.isOpen()) {
                    RuntimeException failure = skipRemainingBestEffort(
                            requests,
                            index,
                            "受控跳过：请求已经取消",
                            null);
                    rethrow(failure);
                    return null;
                }
                ToolExecutionRequest normalizedRequest = normalizeToolExecutionRequest(toolExecutionRequest);
                if (normalizedRequest == null) {
                    notifyToolExecuted(toolExecutionRequest,
                            "受控跳过：工具参数不是合法 JSON");
                    continue;
                }
                String toolName = normalizedRequest.name();
                ToolExecutor toolExecutor = toolExecutors.get(toolName);
                if (toolExecutor == null) {
                    LOG.warn("Tool executor not found, skip tool call: name={}, id={}", toolName, normalizedRequest.id());
                    notifyToolExecuted(normalizedRequest,
                            String.format("受控跳过：工具 '%s' 未注册", toolName));
                    continue;
                }
                if (!requestController.beforeToolExecution()) {
                    RuntimeException failure = null;
                    try {
                        failure = skipRemainingBestEffort(
                                requests,
                                index,
                                requestController.isCancelled()
                                        ? "受控跳过：请求已经取消"
                                        : "受控跳过：工具执行次数超过上限",
                                null);
                    } finally {
                        if (!requestController.isCancelled()) {
                            requestController.dispatchClaimedTermination();
                        }
                    }
                    rethrow(failure);
                    return null;
                }
                int toolRequestIndex = index;
                if (completeToolExecutionRequestHandler != null
                        && claimCompleteToolRequest(normalizedRequest)
                        && !requestController.runIfOpen(() ->
                        completeToolExecutionRequestHandler.accept(
                                toolRequestIndex, normalizedRequest))) {
                    return null;
                }
                ToolExecutionGuard.GuardedToolExecution guardedExecution;
                try {
                    guardedExecution = toolExecutionGuard.execute(toolName, memoryId,
                            () -> toolExecutor.execute(normalizedRequest, memoryId));
                } catch (RuntimeException e) {
                    LOG.warn("Tool execution failed, skip this tool and continue: name={}, id={}",
                            normalizedRequest.name(), normalizedRequest.id(), e);
                    notifyToolExecuted(normalizedRequest,
                            String.format("受控跳过：工具 '%s' 执行失败：%s",
                                    normalizedRequest.name(), e.getMessage()));
                    continue;
                }
                if (!requestController.isOpen()) {
                    RuntimeException failure = skipRemainingBestEffort(
                            requests,
                            index,
                            "受控跳过：请求已经取消",
                            null);
                    rethrow(failure);
                    return null;
                }
                ToolLoopTerminationProtocol.ControlledTermination termination =
                        guardedExecution.controlledTermination();
                if (termination != null) {
                    if (!requestController.claimControlledTermination(termination)) {
                        if (requestController.isCancelled()) {
                            RuntimeException failure = skipRemainingBestEffort(
                                    requests,
                                    index,
                                    "受控跳过：请求已经取消",
                                    null);
                            rethrow(failure);
                        }
                        return null;
                    }
                    RuntimeException failure = null;
                    try {
                        try {
                            notifyToolExecuted(normalizedRequest, guardedExecution.toolResult());
                        } catch (RuntimeException exception) {
                            failure = exception;
                        }
                        failure = skipRemainingBestEffort(
                                requests,
                                index + 1,
                                "受控跳过：本批次已有工具触发终止",
                                failure);
                        if (failure == null) {
                            try {
                                completeClaimedTermination(termination);
                            } catch (RuntimeException exception) {
                                failure = exception;
                            }
                        }
                    } finally {
                        requestController.dispatchClaimedTermination();
                    }
                    rethrow(failure);
                    return null;
                }
                if (!requestController.runIfOpen(() -> notifyToolExecuted(
                        normalizedRequest, guardedExecution.toolResult()))) {
                    return null;
                }
            }
            if (!requestController.isOpen()) {
                return null;
            }
            return prepareNextModelRequest(completeResponse);
        }
        return null;
    }

    private PendingModelRequestPreparation prepareNextModelRequest(
            ChatResponse completeResponse) {
        TokenUsage accumulatedUsage = TokenUsage.sum(
                tokenUsage, completeResponse.metadata().tokenUsage());
        if (modelRequestGate == null) {
            startNextModelRequest(
                    messagesToSend(memoryId), accumulatedUsage, false);
            return null;
        }
        CompletionStage<ModelRequestGate.Decision> preparation;
        try {
            preparation = modelRequestGate.prepare(new ModelRequestGate.Request(
                    memoryId,
                    this::getMemory,
                    toolSpecifications,
                    continuationGate));
        } catch (RuntimeException exception) {
            return claimPreparationFailure(new IllegalStateException(
                    "模型请求门禁准备失败", exception));
        }
        if (preparation == null) {
            return claimPreparationFailure(new IllegalStateException(
                    "模型请求门禁未返回准备结果"));
        }
        return PendingModelRequestPreparation.prepared(
                preparation, accumulatedUsage);
    }

    private PendingModelRequestPreparation claimPreparationFailure(
            Throwable failure) {
        if (!requestController.completeNormally()) {
            return null;
        }
        return PendingModelRequestPreparation.failed(failure);
    }

    private void observePreparation(
            PendingModelRequestPreparation pendingPreparation) {
        if (pendingPreparation.claimedFailure() != null) {
            notifyError(pendingPreparation.claimedFailure());
            return;
        }
        CompletionStage<ModelRequestGate.DispatchStatus> dispatch;
        try {
            dispatch = modelRequestGate.onPrepared(
                    pendingPreparation.preparation(),
                    (decision, failure) -> finishNextPreparation(
                            decision,
                            failure,
                            pendingPreparation.accumulatedUsage()));
        } catch (RuntimeException exception) {
            deliverError(new IllegalStateException(
                    "模型请求门禁完成回调注册失败", exception));
            return;
        }
        if (dispatch == null) {
            deliverError(new IllegalStateException(
                    "模型请求门禁未返回完成回调调度结果"));
            return;
        }
        dispatch.whenComplete(this::finishPreparationDispatch);
    }

    private void finishPreparationDispatch(
            ModelRequestGate.DispatchStatus status,
            Throwable failure) {
        if (failure != null) {
            deliverError(new IllegalStateException(
                    "模型请求门禁完成回调执行失败", failure));
            return;
        }
        if (status != ModelRequestGate.DispatchStatus.DISPATCHED) {
            deliverError(new IllegalStateException(
                    "模型请求门禁完成回调调度失败"));
        }
    }

    private void finishNextPreparation(
            ModelRequestGate.Decision decision,
            Throwable failure,
            TokenUsage accumulatedUsage) {
        if (failure != null) {
            deliverError(new IllegalStateException(
                    "模型请求门禁执行失败", failure));
            return;
        }
        if (decision == null) {
            deliverError(new IllegalStateException(
                    "模型请求门禁返回空决策"));
            return;
        }
        switch (decision.status()) {
            case ALLOWED -> startAllowedNextRequest(
                    decision.messages(), accumulatedUsage);
            case CANCELLED -> requestController.cancel();
            case COMPRESSION_FAILED, HARD_LIMIT_REJECTED ->
                    deliverError(new ModelRequestGateException(
                            ModelRequestGateException.Stage.CONTINUATION,
                            decision.status(), decision.safeMessage()));
        }
    }

    private void startAllowedNextRequest(
            List<ChatMessage> preparedMessages,
            TokenUsage accumulatedUsage) {
        boolean accepted;
        try {
            accepted = continuationGate.tryRun(() -> {
                try (var callback = requestController.enterCallback()) {
                    if (callback != null) {
                        startNextModelRequest(
                                preparedMessages, accumulatedUsage, true);
                    }
                }
            });
        } catch (RuntimeException exception) {
            deliverError(exception);
            return;
        }
        if (!accepted) {
            requestController.cancel();
        }
    }

    private void startNextModelRequest(
            List<ChatMessage> requestMessages,
            TokenUsage accumulatedUsage,
            boolean verifyGeneration) {
        boolean requestAccepted = verifyGeneration
                ? requestController.beforeModelRequest(requestGeneration)
                : requestController.beforeModelRequest();
        if (!requestAccepted) {
            requestController.dispatchClaimedTermination();
            return;
        }
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(requestMessages)
                .toolSpecifications(toolSpecifications)
                .build();
        long nextGeneration = requestController.latestModelRequestGeneration();
        var handler = new AiServiceStreamingResponseHandler(
                chatExecutor,
                context,
                memoryId,
                partialResponseHandler,
                partialToolExecutionRequestHandler,
                completeToolExecutionRequestHandler,
                toolExecutionHandler,
                completeResponseHandler,
                errorHandler,
                temporaryMemory,
                accumulatedUsage,
                toolSpecifications,
                toolExecutors,
                commonGuardrailParams,
                methodKey,
                requestController,
                toolExecutionGuard,
                nextGeneration,
                modelRequestGate,
                continuationGate);

        try {
            requestController.startModelRequestIfOpen(
                    () -> context.streamingChatModel.chat(chatRequest, handler));
        } catch (RuntimeException exception) {
            handler.onError(exception);
        }
    }

    private void completeOrdinaryResponse(
            ChatResponse completeResponse, AiMessage aiMessage) {
        if (!requestController.claimNormalCompletion()) {
            return;
        }
        try {
            addToMemory(aiMessage);
            if (completeResponseHandler != null) {
                ChatResponse finalChatResponse = ChatResponse.builder()
                        .aiMessage(aiMessage)
                        .metadata(completeResponse.metadata().toBuilder()
                                .tokenUsage(tokenUsage.add(
                                        completeResponse.metadata().tokenUsage()))
                                .build())
                        .build();

                // Invoke output guardrails
                if (hasOutputGuardrails) {
                    if (commonGuardrailParams != null) {
                        var newCommonParams = GuardrailRequestParams.builder()
                                .chatMemory(getMemory())
                                .augmentationResult(commonGuardrailParams.augmentationResult())
                                .userMessageTemplate(commonGuardrailParams.userMessageTemplate())
                                .variables(commonGuardrailParams.variables())
                                .build();

                        var outputGuardrailParams = OutputGuardrailRequest.builder()
                                .responseFromLLM(finalChatResponse)
                                .chatExecutor(chatExecutor)
                                .requestParams(newCommonParams)
                                .build();

                        finalChatResponse =
                                context.guardrailService().executeGuardrails(methodKey, outputGuardrailParams);
                    }

                    // If we have output guardrails, we should process all of the partial responses first before
                    // completing
                    responseBuffer.forEach(partialResponseHandler::accept);
                    responseBuffer.clear();
                }

                // TODO should completeResponseHandler accept all ChatResponses that happened?
                completeResponseHandler.accept(finalChatResponse);
            }
            requestController.finishNormalCompletion();
        } catch (RuntimeException exception) {
            requestController.failNormalCompletion(exception, errorHandler);
        }
    }

    private void completeClaimedTermination(
            ToolLoopTerminationProtocol.ControlledTermination termination) {
        String finalResponse = termination.finalResponse();
        if (finalResponse != null) {
            AiMessage finalMessage = AiMessage.from(finalResponse);
            addToMemory(finalMessage);
            partialResponseHandler.accept(finalResponse);
            // 受控终止由专用回调唯一收口；普通完成回调会抢先结束上层 Flux，
            // 使随后的 CANCELLED / PROTOCOL_ERROR 等类型化终态被静默丢弃。
        }
    }

    private RuntimeException skipRemainingBestEffort(
            List<ToolExecutionRequest> requests,
            int startIndex,
            String reason,
            RuntimeException firstFailure) {
        RuntimeException failure = firstFailure;
        for (int index = startIndex; index < requests.size(); index++) {
            try {
                notifyToolExecuted(requests.get(index), reason);
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        return failure;
    }

    private void rethrow(RuntimeException failure) {
        if (failure != null) {
            throw failure;
        }
    }

    private void notifyToolExecuted(ToolExecutionRequest request, String result) {
        addToMemory(ToolExecutionResultMessage.from(request, result));
        if (toolExecutionHandler != null) {
            toolExecutionHandler.accept(ToolExecution.builder()
                    .request(request)
                    .result(result)
                    .build());
        }
    }

    private ChatMemory getMemory() {
        return getMemory(memoryId);
    }

    private ChatMemory getMemory(Object memId) {
        return context.hasChatMemory() ? context.chatMemoryService.getOrCreateChatMemory(memoryId) : temporaryMemory;
    }

    private void addToMemory(ChatMessage chatMessage) {
        getMemory().add(chatMessage);
    }

    private List<ChatMessage> messagesToSend(Object memoryId) {
        return getMemory(memoryId).messages();
    }

    @Override
    public void onError(Throwable error) {
        try (var callback = requestController.enterCallback()) {
            if (callback == null) {
                return;
            }
            deliverError(error);
        }
    }

    private void deliverError(Throwable error) {
        if (requestController.completeNormally()) {
            notifyError(error);
        }
    }

    private void notifyError(Throwable error) {
        if (errorHandler != null) {
            try {
                errorHandler.accept(error);
            } catch (Exception e) {
                LOG.error("While handling the following error...", error);
                LOG.error("...the following error happened", e);
            }
        } else {
            LOG.warn("Ignored error", error);
        }
    }

    private ToolExecutionRequest normalizeToolExecutionRequest(ToolExecutionRequest request) {
        ToolArgumentsJsonNormalizer.Result normalized = ToolArgumentsJsonNormalizer.normalize(request.arguments());
        if (!normalized.isValid()) {
            LOG.warn("Skip malformed tool arguments: id={}, name={}, reason={}",
                    request.id(), request.name(), normalized.reason());
            return null;
        }
        if (normalized.repaired()) {
            LOG.info("Repaired malformed tool arguments before execution: id={}, name={}, reason={}",
                    request.id(), request.name(), normalized.reason());
        }
        return ToolExecutionRequest.builder()
                .id(request.id())
                .name(request.name())
                .arguments(normalized.normalizedArguments())
                .build();
    }

    /**
     * 某些模型只在完整响应中携带工具请求，另一些模型还会提前发送完整工具请求回调。
     * 以工具调用 ID 去重，确保执行前兜底不会让同一调用被通知两次。
     */
    private boolean claimCompleteToolRequest(ToolExecutionRequest request) {
        String requestId = request.id();
        return requestId == null || completedToolRequestIds.add(requestId);
    }

    private record PendingModelRequestPreparation(
            CompletionStage<ModelRequestGate.Decision> preparation,
            TokenUsage accumulatedUsage,
            Throwable claimedFailure) {

        private static PendingModelRequestPreparation prepared(
                CompletionStage<ModelRequestGate.Decision> preparation,
                TokenUsage accumulatedUsage) {
            return new PendingModelRequestPreparation(
                    Objects.requireNonNull(preparation),
                    Objects.requireNonNull(accumulatedUsage),
                    null);
        }

        private static PendingModelRequestPreparation failed(
                Throwable claimedFailure) {
            return new PendingModelRequestPreparation(
                    null,
                    null,
                    Objects.requireNonNull(claimedFailure));
        }
    }
}
