package dev.langchain4j.service;

import com.lyw.appgeneration.ai.memory.ContextCompressionAttemptState;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
    private final ToolProtocolRecoveryCoordinator recoveryCoordinator;
    private final ContextCompressionAttemptState compressionAttemptState;
    private final ToolProtocolRecoveryDetector recoveryDetector;
    private final boolean recoveryGeneration;
    private final GenerationAwareModelRequestOrchestrator requestOrchestrator;
    private final Object recoveryDetectionMonitor = new Object();
    private final StringBuilder observedResponseText = new StringBuilder();
    private final StringBuilder trustedResponseText = new StringBuilder();
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
                ToolExecutionGuard.direct(), 0L, null, null, null, false,
                null, new ContextCompressionAttemptState());
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
                requestController.latestModelRequestGeneration(), null, null,
                null, false, null, new ContextCompressionAttemptState());
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
                toolExecutionGuard, requestGeneration, null, null, null, false,
                null, new ContextCompressionAttemptState());
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
        this(chatExecutor, context, memoryId, partialResponseHandler,
                partialToolExecutionRequestHandler,
                completeToolExecutionRequestHandler,
                toolExecutionHandler, completeResponseHandler, errorHandler,
                temporaryMemory, tokenUsage, toolSpecifications, toolExecutors,
                commonGuardrailParams, methodKey, requestController,
                toolExecutionGuard, requestGeneration, modelRequestGate,
                continuationGate, null, false, null,
                new ContextCompressionAttemptState());
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
            ModelRequestGate.ContinuationGate continuationGate,
            ToolProtocolRecoveryCoordinator recoveryCoordinator) {
        this(chatExecutor, context, memoryId, partialResponseHandler,
                partialToolExecutionRequestHandler,
                completeToolExecutionRequestHandler,
                toolExecutionHandler, completeResponseHandler, errorHandler,
                temporaryMemory, tokenUsage, toolSpecifications, toolExecutors,
                commonGuardrailParams, methodKey, requestController,
                toolExecutionGuard, requestGeneration, modelRequestGate,
                continuationGate, recoveryCoordinator,
                new ContextCompressionAttemptState());
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
            ModelRequestGate.ContinuationGate continuationGate,
            ToolProtocolRecoveryCoordinator recoveryCoordinator,
            ContextCompressionAttemptState compressionAttemptState) {
        this(chatExecutor, context, memoryId, partialResponseHandler,
                partialToolExecutionRequestHandler,
                completeToolExecutionRequestHandler,
                toolExecutionHandler, completeResponseHandler, errorHandler,
                temporaryMemory, tokenUsage, toolSpecifications, toolExecutors,
                commonGuardrailParams, methodKey, requestController,
                toolExecutionGuard, requestGeneration, modelRequestGate,
                continuationGate, recoveryCoordinator, false, null,
                compressionAttemptState);
    }

    private AiServiceStreamingResponseHandler(
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
            ModelRequestGate.ContinuationGate continuationGate,
            ToolProtocolRecoveryCoordinator recoveryCoordinator,
            boolean recoveryGeneration,
            GenerationAwareModelRequestOrchestrator requestOrchestrator,
            ContextCompressionAttemptState compressionAttemptState) {
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
        this.recoveryCoordinator = recoveryCoordinator;
        this.compressionAttemptState = ensureNotNull(
                compressionAttemptState, "上下文压缩尝试状态不能为空");
        this.recoveryDetector = recoveryCoordinator == null
                ? null : recoveryCoordinator.newDetector();
        this.recoveryGeneration = recoveryGeneration;
        this.requestOrchestrator = requestOrchestrator == null
                ? new GenerationAwareModelRequestOrchestrator(
                        requestController, modelRequestGate, continuationGate)
                : requestOrchestrator;
    }

    @Override
    public void onRequestHandle(StreamingRequestHandle handle) {
        requestController.registerRequestHandle(requestGeneration, handle);
    }

    @Override
    public void onPartialResponse(String partialResponse) {
        AtomicBoolean recoveryPrepared = new AtomicBoolean();
        try (var callback = requestController.enterCallback(
                requestGeneration)) {
            if (callback == null) {
                return;
            }
            processPartialResponse(partialResponse, recoveryPrepared);
        }
        if (recoveryPrepared.get()) {
            prepareRecoveryRequest(new TokenUsage());
        }
    }

    private void processPartialResponse(
            String partialResponse,
            AtomicBoolean recoveryPrepared) {
        if (recoveryDetector == null) {
            deliverTrustedPartial(partialResponse);
            return;
        }
        ToolProtocolRecoveryDetector.Result result;
        synchronized (recoveryDetectionMonitor) {
            observedResponseText.append(partialResponse);
            result = recoveryDetector.accept(partialResponse);
        }
        handleDetectionResult(result, recoveryPrepared);
    }

    private boolean handleDetectionResult(
            ToolProtocolRecoveryDetector.Result result,
            AtomicBoolean recoveryPrepared) {
        if (result instanceof ToolProtocolRecoveryDetector.Text text) {
            markRecoveredBeforeTrustedOutput();
            deliverTrustedPartial(text.text());
            return true;
        }
        if (!(result instanceof ToolProtocolRecoveryDetector.Violation violation)) {
            return true;
        }
        deliverTrustedPartial(violation.trustedText());
        if (structuredToolCallSupersedesViolation()) {
            return true;
        }
        handleViolation(recoveryPrepared);
        return false;
    }

    private boolean structuredToolCallSupersedesViolation() {
        synchronized (recoveryDetectionMonitor) {
            return recoveryDetector.hasObservedStructuredToolCall()
                    && !recoveryDetector
                            .hasViolationObservedBeforeStructuredToolCall();
        }
    }

    private void deliverTrustedPartial(String text) {
        if (text.isEmpty()) {
            return;
        }
        synchronized (recoveryDetectionMonitor) {
            trustedResponseText.append(text);
        }
        if (hasOutputGuardrails) {
            responseBuffer.add(text);
        } else {
            partialResponseHandler.accept(text);
        }
    }

    private void markRecoveredBeforeTrustedOutput() {
        if (recoveryGeneration && recoveryCoordinator != null) {
            recoveryCoordinator.recovered();
        }
    }

    private void handleViolation(AtomicBoolean recoveryPrepared) {
        ToolProtocolRecoveryCoordinator.ViolationAction action =
                recoveryCoordinator.claimViolation(requestGeneration);
        if (action == ToolProtocolRecoveryCoordinator.ViolationAction.IGNORE) {
            return;
        }
        if (action == ToolProtocolRecoveryCoordinator.ViolationAction.FAIL) {
            failProtocolRecovery();
            return;
        }
        StreamingRequestController.GenerationCancellation cancellation =
                requestController.cancelGenerationForRecovery(
                        requestGeneration);
        if (cancellation != StreamingRequestController
                .GenerationCancellation.CANCELLED) {
            recoveryCoordinator.releaseRecoveryReservation();
            return;
        }
        recoveryCoordinator.recoveryStarted();
        recoveryPrepared.set(true);
    }

    private void failProtocolRecovery() {
        ToolLoopTerminationProtocol.ControlledTermination termination =
                new ToolLoopTerminationProtocol.ControlledTermination(
                        ToolLoopTerminationProtocol
                                .ControlledTerminationReason.PROTOCOL_ERROR,
                        null);
        if (requestController.claimControlledTermination(
                requestGeneration, termination)) {
            recoveryCoordinator.failForProtocolViolation();
            requestController.dispatchClaimedTermination();
        }
    }

    @Override
    public void onPartialToolExecutionRequest(int index, ToolExecutionRequest partialToolExecutionRequest) {
        try (var callback = requestController.enterCallback(
                requestGeneration)) {
            if (callback != null) {
                observeStructuredToolCall();
                if (partialToolExecutionRequestHandler != null) {
                    partialToolExecutionRequestHandler.accept(
                            index, partialToolExecutionRequest);
                }
            }
        }
    }

    @Override
    public void onCompleteToolExecutionRequest(
            int index, ToolExecutionRequest completeToolExecutionRequest) {
        try (var callback = requestController.enterCallback(
                requestGeneration)) {
            if (callback != null) {
                observeStructuredToolCall();
                // 供应商可能在完整响应前提前发送该事件。这里只观察结构化
                // 工具调用，用户工具卡统一延迟到工具批次提交成功后再发布，
                // 避免取消先赢时产生没有结果的孤立工具卡。
            }
        }
    }

    private void observeStructuredToolCall() {
        if (recoveryDetector == null) {
            return;
        }
        ToolProtocolRecoveryDetector.Result result;
        synchronized (recoveryDetectionMonitor) {
            result = recoveryDetector.observeStructuredToolCall();
        }
        if (!(result instanceof ToolProtocolRecoveryDetector.Violation)) {
            markRecoveredBeforeTrustedOutput();
        }
        if (result instanceof ToolProtocolRecoveryDetector.Text text) {
            deliverTrustedPartial(text.text());
        }
    }

    @Override
    public void onCompleteResponse(ChatResponse completeResponse) {
        AtomicBoolean recoveryPrepared = new AtomicBoolean();
        AtomicReference<ChatResponse> continuationResponse =
                new AtomicReference<>();
        try (var callback = requestController.enterCallback(
                requestGeneration)) {
            if (callback == null) {
                return;
            }
            processCompleteResponse(
                    completeResponse,
                    recoveryPrepared,
                    continuationResponse);
        }
        if (recoveryPrepared.get()) {
            prepareRecoveryRequest(TokenUsage.sum(
                    tokenUsage, completeResponse.metadata().tokenUsage()));
        } else if (continuationResponse.get() != null) {
            submitNextModelRequest(continuationResponse.get());
        }
    }

    private void processCompleteResponse(
            ChatResponse completeResponse,
            AtomicBoolean recoveryPrepared,
            AtomicReference<ChatResponse> continuationResponse) {
        if (!requestController.isCurrentGeneration(requestGeneration)) {
            return;
        }
        AiMessage aiMessage = completeResponse.aiMessage();
        if (!aiMessage.hasToolExecutionRequests()) {
            if (!completeRecoveryDetection(
                    aiMessage.text(), recoveryPrepared)) {
                return;
            }
            markRecoveredBeforeTrustedOutput();
            completeOrdinaryResponse(completeResponse, aiMessage);
            return;
        }
        observeStructuredToolCall();
        if (!completeToolRecoveryDetection(aiMessage.text())) {
            return;
        }
        aiMessage = sanitizedToolMessage(aiMessage);
        List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
        StreamingRequestController.ToolBatchTicket batchTicket =
                requestController.prepareToolBatch(
                        requestGeneration, requests.size());
        if (batchTicket == null) {
            return;
        }
        ChatMemory memory;
        try {
            memory = getMemory();
        } catch (RuntimeException exception) {
            if (requestController.failPreparedToolBatch(batchTicket)) {
                notifyError(exception);
            }
            return;
        }
        if (!requestController.tryStartToolBatchWrite(batchTicket)) {
            return;
        }
        try {
            memory.add(aiMessage);
        } catch (RuntimeException exception) {
            if (requestController.failPreparedToolBatch(batchTicket)) {
                notifyError(exception);
            }
            return;
        }
        if (!requestController.commitToolBatch(batchTicket)) {
            return;
        }
        executeCommittedToolBatch(
                requests, batchTicket, completeResponse,
                continuationResponse);
    }

    private AiMessage sanitizedToolMessage(AiMessage original) {
        if (recoveryDetector == null) {
            return original;
        }
        String trustedText;
        synchronized (recoveryDetectionMonitor) {
            trustedText = trustedResponseText.toString();
        }
        List<ToolExecutionRequest> requests =
                original.toolExecutionRequests();
        return trustedText.isEmpty()
                ? AiMessage.from(requests)
                : AiMessage.from(trustedText, requests);
    }

    private boolean completeToolRecoveryDetection(String completeText) {
        if (recoveryDetector == null) {
            return true;
        }
        String normalizedText = completeText == null ? "" : completeText;
        ToolProtocolRecoveryDetector.Result suffixResult;
        ToolProtocolRecoveryDetector.Result finishResult;
        boolean streamMismatch;
        synchronized (recoveryDetectionMonitor) {
            String observed = observedResponseText.toString();
            boolean completeTextOmitted = normalizedText.isEmpty();
            streamMismatch = !completeTextOmitted
                    && !normalizedText.startsWith(observed);
            if (streamMismatch) {
                suffixResult = null;
                finishResult = null;
            } else {
                String suffix = completeTextOmitted
                        ? "" : normalizedText.substring(observed.length());
                observedResponseText.append(suffix);
                suffixResult = recoveryDetector.accept(suffix);
                finishResult = recoveryDetector.finish();
            }
        }
        if (streamMismatch) {
            failStreamConsistency();
            return false;
        }
        deliverToolResponseText(suffixResult);
        deliverToolResponseText(finishResult);
        return requestController.isCurrentGeneration(requestGeneration);
    }

    private void deliverToolResponseText(
            ToolProtocolRecoveryDetector.Result result) {
        if (result instanceof ToolProtocolRecoveryDetector.Text text) {
            deliverTrustedPartial(text.text());
        } else if (result instanceof ToolProtocolRecoveryDetector.Violation
                violation) {
            deliverTrustedPartial(violation.trustedText());
        }
    }

    private void executeCommittedToolBatch(
            List<ToolExecutionRequest> requests,
            StreamingRequestController.ToolBatchTicket batchTicket,
            ChatResponse completeResponse,
            AtomicReference<ChatResponse> continuationResponse) {
        RuntimeException failure = null;
        String skipRemainderReason = null;
        ToolLoopTerminationProtocol.ControlledTermination claimedTermination =
                null;
        boolean dispatchTermination = false;

        for (int index = 0; index < requests.size(); index++) {
            ToolExecutionRequest originalRequest = requests.get(index);
            if (skipRemainderReason != null) {
                ToolResultCommit commit = commitToolResult(
                        batchTicket, index, originalRequest,
                        skipRemainderReason, null);
                failure = mergeFailure(failure, commit.failure());
                if (commit.persistenceFailed()) {
                    return;
                }
                continue;
            }

            ToolExecutionRequest normalizedRequest =
                    normalizeToolExecutionRequest(originalRequest);
            if (normalizedRequest == null) {
                ToolResultCommit commit = commitToolResult(
                        batchTicket, index, originalRequest,
                        "受控跳过：工具参数不是合法 JSON", null);
                failure = mergeFailure(failure, commit.failure());
                if (commit.persistenceFailed()) {
                    return;
                }
                continue;
            }
            String toolName = normalizedRequest.name();
            ToolExecutor toolExecutor = toolExecutors.get(toolName);
            if (toolExecutor == null) {
                LOG.warn("Tool executor not found, skip tool call: name={}, id={}",
                        toolName, normalizedRequest.id());
                ToolResultCommit commit = commitToolResult(
                        batchTicket, index, normalizedRequest,
                        String.format("受控跳过：工具 '%s' 未注册", toolName),
                        null);
                failure = mergeFailure(failure, commit.failure());
                if (commit.persistenceFailed()) {
                    return;
                }
                continue;
            }

            if (completeToolExecutionRequestHandler != null
                    && claimCompleteToolRequest(normalizedRequest)) {
                try {
                    completeToolExecutionRequestHandler.accept(
                            index, normalizedRequest);
                } catch (RuntimeException exception) {
                    failure = mergeFailure(failure, exception);
                    skipRemainderReason =
                            "受控跳过：工具请求回调执行失败";
                    ToolResultCommit commit = commitToolResult(
                            batchTicket, index, normalizedRequest,
                            skipRemainderReason, null);
                    failure = mergeFailure(failure, commit.failure());
                    if (commit.persistenceFailed()) {
                        return;
                    }
                    continue;
                }
            }

            StreamingRequestController.ToolExecutionDecision executionDecision =
                    requestController.claimToolExecution(batchTicket, index);
            if (executionDecision
                    != StreamingRequestController.ToolExecutionDecision.EXECUTE) {
                String reason = switch (executionDecision) {
                    case CANCELLED -> "受控跳过：请求已经取消";
                    case LOOP_LIMIT_EXCEEDED ->
                            "受控跳过：工具执行次数超过上限";
                    case TERMINATED ->
                            "受控跳过：本批次已有工具触发终止";
                    case REJECTED -> "受控跳过：工具执行认领已失效";
                    case EXECUTE -> throw new IllegalStateException(
                            "已执行分支不能作为跳过原因");
                };
                ToolLoopTerminationProtocol.ControlledTermination
                        loopLimitTermination = executionDecision
                        == StreamingRequestController.ToolExecutionDecision
                        .LOOP_LIMIT_EXCEEDED
                        ? new ToolLoopTerminationProtocol.ControlledTermination(
                        ToolLoopTerminationProtocol
                                .ControlledTerminationReason.LOOP_LIMIT_EXCEEDED,
                        null)
                        : null;
                ToolResultCommit commit = commitToolResult(
                        batchTicket, index, normalizedRequest, reason,
                        loopLimitTermination);
                failure = mergeFailure(failure, commit.failure());
                if (commit.persistenceFailed()) {
                    return;
                }
                if (commit.decision() == StreamingRequestController
                        .ToolResultDecision.TERMINATED) {
                    claimedTermination = loopLimitTermination;
                    skipRemainderReason =
                            "受控跳过：工具执行次数超过上限";
                    dispatchTermination = true;
                }
                continue;
            }

            ToolExecutionGuard.GuardedToolExecution guardedExecution;
            try {
                guardedExecution = toolExecutionGuard.execute(
                        toolName, memoryId,
                        () -> toolExecutor.execute(
                                normalizedRequest, memoryId));
            } catch (RuntimeException exception) {
                LOG.warn("Tool execution failed, skip this tool and continue: name={}, id={}",
                        normalizedRequest.name(), normalizedRequest.id(),
                        exception);
                ToolResultCommit commit = commitToolResult(
                        batchTicket, index, normalizedRequest,
                        String.format("受控跳过：工具 '%s' 执行失败：%s",
                                normalizedRequest.name(),
                                exception.getMessage()), null);
                failure = mergeFailure(failure, commit.failure());
                if (commit.persistenceFailed()) {
                    return;
                }
                continue;
            }

            ToolResultCommit commit = commitToolResult(
                    batchTicket, index, normalizedRequest,
                    guardedExecution.toolResult(),
                    guardedExecution.controlledTermination());
            failure = mergeFailure(failure, commit.failure());
            if (commit.persistenceFailed()) {
                return;
            }
            if (commit.decision() == StreamingRequestController
                    .ToolResultDecision.TERMINATED) {
                claimedTermination = guardedExecution.controlledTermination();
                skipRemainderReason =
                        "受控跳过：本批次已有工具触发终止";
                dispatchTermination = true;
            }
        }

        boolean continueModelLoop = requestController.finishToolBatch(
                batchTicket);
        try {
            if (claimedTermination != null && failure == null) {
                completeClaimedTermination(claimedTermination);
            }
        } catch (RuntimeException exception) {
            failure = mergeFailure(failure, exception);
        } finally {
            if (dispatchTermination) {
                requestController.dispatchClaimedTermination();
            }
        }
        if (failure != null) {
            throw failure;
        }
        if (continueModelLoop) {
            continuationResponse.set(completeResponse);
        }
    }

    private ToolResultCommit commitToolResult(
            StreamingRequestController.ToolBatchTicket batchTicket,
            int index,
            ToolExecutionRequest request,
            String providedResult,
            ToolLoopTerminationProtocol.ControlledTermination termination) {
        StreamingRequestController.ToolResultClaim claim =
                requestController.prepareToolResult(
                        batchTicket, index, termination);
        StreamingRequestController.ToolResultDecision preparedDecision =
                claim.decision();
        if (preparedDecision == StreamingRequestController
                .ToolResultDecision.REJECTED) {
            return new ToolResultCommit(preparedDecision, null, false);
        }
        String committedResult = preparedDecision == StreamingRequestController
                .ToolResultDecision.CANCELLED
                ? "受控跳过：请求已经取消"
                : providedResult;
        try {
            addToMemory(ToolExecutionResultMessage.from(
                    request, committedResult));
        } catch (RuntimeException exception) {
            if (requestController.failPreparedToolResult(
                    batchTicket, index, claim)) {
                notifyError(exception);
            }
            return new ToolResultCommit(preparedDecision, null, true);
        }
        StreamingRequestController.ToolResultDecision committedDecision =
                requestController.commitToolResult(
                        batchTicket, index, claim);
        if (committedDecision == StreamingRequestController
                .ToolResultDecision.REJECTED) {
            return new ToolResultCommit(committedDecision, null, false);
        }
        try {
            notifyToolExecutedCallback(request, committedResult);
            return new ToolResultCommit(
                    committedDecision, null, false);
        } catch (RuntimeException exception) {
            return new ToolResultCommit(
                    committedDecision, exception, false);
        }
    }

    private RuntimeException mergeFailure(
            RuntimeException current, RuntimeException next) {
        if (next == null) {
            return current;
        }
        if (current == null) {
            return next;
        }
        if (current == next) {
            return current;
        }
        current.addSuppressed(next);
        return current;
    }

    private record ToolResultCommit(
            StreamingRequestController.ToolResultDecision decision,
            RuntimeException failure,
            boolean persistenceFailed) {
    }

    private boolean completeRecoveryDetection(
            String completeText,
            AtomicBoolean recoveryPrepared) {
        if (recoveryDetector == null) {
            return true;
        }
        String normalizedText = completeText == null ? "" : completeText;
        ToolProtocolRecoveryDetector.Result suffixResult;
        ToolProtocolRecoveryDetector.Result finishResult;
        boolean streamMismatch;
        synchronized (recoveryDetectionMonitor) {
            String observed = observedResponseText.toString();
            streamMismatch = !normalizedText.startsWith(observed);
            if (streamMismatch) {
                suffixResult = null;
                finishResult = null;
            } else {
                String suffix = normalizedText.substring(observed.length());
                observedResponseText.append(suffix);
                suffixResult = recoveryDetector.accept(suffix);
                if (suffixResult instanceof ToolProtocolRecoveryDetector.Violation) {
                    finishResult = null;
                } else {
                    finishResult = recoveryDetector.finish();
                }
            }
        }
        if (streamMismatch) {
            failStreamConsistency();
            return false;
        }
        if (!handleDetectionResult(suffixResult, recoveryPrepared)) {
            return false;
        }
        if (finishResult != null
                && !handleDetectionResult(finishResult, recoveryPrepared)) {
            return false;
        }
        return requestController.isCurrentGeneration(requestGeneration);
    }

    private void failStreamConsistency() {
        if (requestController.claimErrorCompletion(requestGeneration)) {
            notifyError(new StreamingResponseConsistencyException());
        }
    }

    private void prepareRecoveryRequest(TokenUsage accumulatedUsage) {
        ModelRequestGate.Request gateRequest = modelRequestGate == null
                ? null
                : new ModelRequestGate.Request(
                        memoryId,
                        this::getMemory,
                        toolSpecifications,
                        continuationGate,
                        recoveryCoordinator.transientMessages(),
                        compressionAttemptState);
        requestOrchestrator.submit(
                GenerationAwareModelRequestOrchestrator.recovery(
                        requestGeneration,
                        gateRequest,
                        () -> messagesToSend(memoryId),
                        recoveryCoordinator::failIfRecovering,
                        this::notifyRecoveryFailure,
                        (messages, generation) -> startModelRequest(
                                messages,
                                accumulatedUsage,
                                generation,
                                true)));
    }

    private void notifyRecoveryFailure(Throwable failure) {
        recoveryCoordinator.failIfRecovering();
        notifyError(failure);
    }

    private void submitNextModelRequest(ChatResponse completeResponse) {
        TokenUsage accumulatedUsage = TokenUsage.sum(
                tokenUsage, completeResponse.metadata().tokenUsage());
        ModelRequestGate.Request gateRequest = modelRequestGate == null
                ? null
                : new ModelRequestGate.Request(
                        memoryId,
                        this::getMemory,
                        toolSpecifications,
                        continuationGate,
                        List.of(),
                        compressionAttemptState);
        requestOrchestrator.submit(
                GenerationAwareModelRequestOrchestrator.continuation(
                        requestGeneration,
                        gateRequest,
                        () -> messagesToSend(memoryId),
                        this::notifyError,
                        (messages, generation) -> startModelRequest(
                                messages,
                                accumulatedUsage,
                                generation,
                                false)));
    }

    private Runnable startModelRequest(
            List<ChatMessage> requestMessages,
            TokenUsage accumulatedUsage,
            long nextGeneration,
            boolean recoveryGeneration) {
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(requestMessages)
                .toolSpecifications(toolSpecifications)
                .build();
        AiServiceStreamingResponseHandler child = childHandler(
                accumulatedUsage, nextGeneration, recoveryGeneration);
        return () -> context.streamingChatModel.chat(chatRequest, child);
    }

    private AiServiceStreamingResponseHandler childHandler(
            TokenUsage accumulatedUsage,
            long nextGeneration,
            boolean recoveryGeneration) {
        return new AiServiceStreamingResponseHandler(
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
                continuationGate,
                recoveryCoordinator,
                recoveryGeneration,
                requestOrchestrator,
                compressionAttemptState);
    }

    private void completeOrdinaryResponse(
            ChatResponse completeResponse, AiMessage aiMessage) {
        if (!requestController.claimNormalCompletion(requestGeneration)) {
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

    private void notifyToolExecutedCallback(
            ToolExecutionRequest request, String result) {
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
        try (var callback = requestController.enterCallback(
                requestGeneration)) {
            if (callback == null) {
                return;
            }
            if (!requestController.claimErrorCompletion(requestGeneration)) {
                return;
            }
            if (recoveryGeneration && recoveryCoordinator != null) {
                recoveryCoordinator.failIfRecovering();
            }
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

}
