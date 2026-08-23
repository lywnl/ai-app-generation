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
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingRequestHandle;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.tool.ToolExecution;
import dev.langchain4j.service.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
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
    static final int MAX_TRACKED_RESPONSE_CHARS = 65_536;

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
    private final IncompleteToolChainRecoveryCoordinator
            incompleteRecoveryCoordinator;
    private final InternalOutputRecoveryPolicy internalOutputRecoveryPolicy;
    private final InternalOutputRecoveryCoordinator
            internalOutputRecoveryCoordinator;
    private final Consumer<GenerationStreamSignal>
            generationStreamSignalHandler;
    private final ContextCompressionAttemptState compressionAttemptState;
    private final ToolProtocolRecoveryDetector recoveryDetector;
    private final boolean recoveryGeneration;
    private final boolean incompleteRecoveryGeneration;
    private final GenerationAwareModelRequestOrchestrator requestOrchestrator;
    private final Object recoveryDetectionMonitor = new Object();
    private final StringBuilder observedResponseText = new StringBuilder();
    private final StringBuilder trustedResponseText = new StringBuilder();
    private int deliveredTrustedResponseChars;
    private boolean structuredToolCallObserved;
    private int streamedResponseChars;
    private final Set<String> completedToolRequestIds =
            ConcurrentHashMap.newKeySet();
    private final InternalOutputLeakDetector internalOutputLeakDetector;
    private final ToolArgumentLeakScanner toolArgumentLeakScanner;
    private final StringBuilder internalObservedResponseText =
            new StringBuilder();
    private final Set<String> bufferingToolRequestIds =
            new LinkedHashSet<>();
    private final Set<String> provisionalToolRequestIds =
            new LinkedHashSet<>();
    private final GenerationDisclosureBuffer disclosureBuffer =
            new GenerationDisclosureBuffer();
    private final Map<String, List<GenerationDisclosureBuffer.Disclosure>>
            bufferedToolDisclosures = new HashMap<>();
    private final Map<String, String> verifiedToolArguments =
            new HashMap<>();
    private GenerationDisclosureBuffer.Disclosure bufferedTextDisclosure;
    private int publishedTextCodePoints;
    private final AtomicBoolean generationSignalPublishingClosed =
            new AtomicBoolean();
    private GenerationCallbackSequencer callbackSequencer;

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
                null, null, false, new ContextCompressionAttemptState(),
                null, null, null);
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
                null, false, null, null, false,
                new ContextCompressionAttemptState(), null, null, null);
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
                null, null, false, new ContextCompressionAttemptState(),
                null, null, null);
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
                null, false, new ContextCompressionAttemptState(),
                null, null, null);
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
                null, new ContextCompressionAttemptState(),
                null, null, null);
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
                continuationGate, recoveryCoordinator, null,
                compressionAttemptState, null, null, null);
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
            IncompleteToolChainRecoveryCoordinator incompleteRecoveryCoordinator,
            ContextCompressionAttemptState compressionAttemptState) {
        this(chatExecutor, context, memoryId, partialResponseHandler,
                partialToolExecutionRequestHandler,
                completeToolExecutionRequestHandler,
                toolExecutionHandler, completeResponseHandler, errorHandler,
                temporaryMemory, tokenUsage, toolSpecifications, toolExecutors,
                commonGuardrailParams, methodKey, requestController,
                toolExecutionGuard, requestGeneration, modelRequestGate,
                continuationGate, recoveryCoordinator, false, null,
                incompleteRecoveryCoordinator, false,
                compressionAttemptState, null, null, null);
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
            IncompleteToolChainRecoveryCoordinator incompleteRecoveryCoordinator,
            ContextCompressionAttemptState compressionAttemptState,
            InternalOutputRecoveryPolicy internalOutputRecoveryPolicy,
            InternalOutputRecoveryCoordinator internalOutputRecoveryCoordinator,
            Consumer<GenerationStreamSignal> generationStreamSignalHandler) {
        this(chatExecutor, context, memoryId, partialResponseHandler,
                partialToolExecutionRequestHandler,
                completeToolExecutionRequestHandler,
                toolExecutionHandler, completeResponseHandler, errorHandler,
                temporaryMemory, tokenUsage, toolSpecifications, toolExecutors,
                commonGuardrailParams, methodKey, requestController,
                toolExecutionGuard, requestGeneration, modelRequestGate,
                continuationGate, recoveryCoordinator, false,
                null, incompleteRecoveryCoordinator,
                false, compressionAttemptState,
                internalOutputRecoveryPolicy,
                internalOutputRecoveryCoordinator,
                generationStreamSignalHandler);
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
            IncompleteToolChainRecoveryCoordinator incompleteRecoveryCoordinator,
            boolean incompleteRecoveryGeneration,
            ContextCompressionAttemptState compressionAttemptState,
            InternalOutputRecoveryPolicy internalOutputRecoveryPolicy,
            InternalOutputRecoveryCoordinator internalOutputRecoveryCoordinator,
            Consumer<GenerationStreamSignal> generationStreamSignalHandler) {
        this.chatExecutor = ensureNotNull(chatExecutor, "chatExecutor");
        this.context = ensureNotNull(context, "context");
        this.memoryId = ensureNotNull(memoryId, "memoryId");
        this.methodKey = methodKey;

        if (partialResponseHandler == null
                && generationStreamSignalHandler == null) {
            throw new NullPointerException(
                    "partialResponseHandler 和 generationStreamSignalHandler 不能同时为空");
        }
        this.partialResponseHandler = partialResponseHandler;
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
        this.incompleteRecoveryCoordinator = incompleteRecoveryCoordinator;
        this.internalOutputRecoveryPolicy = internalOutputRecoveryPolicy;
        this.internalOutputRecoveryCoordinator =
                internalOutputRecoveryCoordinator;
        this.generationStreamSignalHandler = generationStreamSignalHandler;
        this.compressionAttemptState = ensureNotNull(
                compressionAttemptState, "上下文压缩尝试状态不能为空");
        this.recoveryDetector = recoveryCoordinator == null
                ? null : recoveryCoordinator.newDetector();
        this.recoveryGeneration = recoveryGeneration;
        this.incompleteRecoveryGeneration = incompleteRecoveryGeneration;
        this.requestOrchestrator = requestOrchestrator == null
                ? new GenerationAwareModelRequestOrchestrator(
                        requestController, modelRequestGate, continuationGate)
                : requestOrchestrator;
        this.internalOutputLeakDetector = internalOutputRecoveryPolicy == null
                ? null : internalOutputRecoveryPolicy.newLeakDetector();
        this.toolArgumentLeakScanner = internalOutputRecoveryPolicy == null
                ? null : internalOutputRecoveryPolicy
                        .newToolArgumentLeakScanner();
        if (generationStreamSignalHandler
                instanceof GenerationSignalPublisher publisher) {
            this.callbackSequencer = new GenerationCallbackSequencer(
                    () -> pauseGenerationBatch(publisher),
                    disclosureBuffer::resumePublishing,
                    publisher::resumePublishing);
        } else {
            this.callbackSequencer = new GenerationCallbackSequencer(
                    disclosureBuffer::pausePublishing,
                    disclosureBuffer::resumePublishing);
        }
    }

    private void pauseGenerationBatch(
            GenerationSignalPublisher publisher) {
        disclosureBuffer.pausePublishing();
        try {
            publisher.pausePublishing();
        } catch (RuntimeException | Error failure) {
            disclosureBuffer.resumePublishing();
            throw failure;
        }
    }

    @Override
    public void onRequestHandle(StreamingRequestHandle handle) {
        requestController.registerRequestHandle(requestGeneration, handle);
    }

    @Override
    public void onPartialResponse(String partialResponse) {
        submitProviderCallback(() ->
                handlePartialResponse(partialResponse));
    }

    private void submitProviderCallback(Runnable action) {
        if (internalOutputRecoveryPolicy == null) {
            action.run();
            return;
        }
        callbackSequencer.submit(action);
    }

    private void handlePartialResponse(String partialResponse) {
        AtomicBoolean protocolRecoveryPrepared = new AtomicBoolean();
        AtomicBoolean internalRecoveryPrepared = new AtomicBoolean();
        try (var callback = requestController.enterCallback(
                requestGeneration)) {
            if (callback == null) {
                return;
            }
            processPartialResponse(
                    partialResponse,
                    protocolRecoveryPrepared,
                    internalRecoveryPrepared);
        }
        if (internalRecoveryPrepared.get()) {
            prepareInternalRecoveryRequest(new TokenUsage());
        } else if (protocolRecoveryPrepared.get()) {
            prepareRecoveryRequest(new TokenUsage());
        }
    }

    private void processPartialResponse(
            String partialResponse,
            AtomicBoolean protocolRecoveryPrepared,
            AtomicBoolean internalRecoveryPrepared) {
        if (!reservePartialResponse(partialResponse)) {
            terminateForResponseLimit();
            return;
        }
        if (internalOutputLeakDetector != null) {
            processInternalPartial(
                    partialResponse,
                    protocolRecoveryPrepared,
                    internalRecoveryPrepared);
            return;
        }
        processTrustedPartial(partialResponse, protocolRecoveryPrepared);
    }

    private void processInternalPartial(
            String partialResponse,
            AtomicBoolean protocolRecoveryPrepared,
            AtomicBoolean internalRecoveryPrepared) {
        InternalOutputLeakDetector.DetectionResult result;
        synchronized (recoveryDetectionMonitor) {
            internalObservedResponseText.append(partialResponse);
            result = internalOutputLeakDetector.accept(partialResponse);
        }
        discloseInternalText(
                result,
                protocolRecoveryPrepared,
                internalRecoveryPrepared);
    }

    private void discloseInternalText(
            InternalOutputLeakDetector.DetectionResult result,
            AtomicBoolean protocolRecoveryPrepared,
            AtomicBoolean internalRecoveryPrepared) {
        resolveBufferedText(
                result.safeText(), protocolRecoveryPrepared);
        if (result.status()
                == InternalOutputLeakDetector.Status.VIOLATION) {
            handleInternalViolation(internalRecoveryPrepared);
            return;
        }
        if (result.status()
                == InternalOutputLeakDetector.Status.BUFFERING) {
            bufferedTextDisclosure = disclosureBuffer.enqueuePending(null);
        }
    }

    private void resolveBufferedText(
            String safeText,
            AtomicBoolean protocolRecoveryPrepared) {
        GenerationDisclosureBuffer.Disclosure buffered =
                bufferedTextDisclosure;
        bufferedTextDisclosure = null;
        if (safeText.isEmpty()) {
            if (buffered != null) {
                disclosureBuffer.remove(buffered);
            }
            return;
        }
        Runnable action = () -> processTrustedPartial(
                safeText, protocolRecoveryPrepared);
        if (buffered == null) {
            disclosureBuffer.enqueueResolved(action);
        } else {
            disclosureBuffer.resolve(buffered, action);
        }
    }

    private void processTrustedPartial(
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
        deliverAvailableTrustedOutput();
    }

    private void deliverAvailableTrustedOutput() {
        String pending;
        synchronized (recoveryDetectionMonitor) {
            if (shouldQuarantineTrustedOutput()) {
                return;
            }
            pending = trustedResponseText.substring(
                    deliveredTrustedResponseChars);
            deliveredTrustedResponseChars = trustedResponseText.length();
        }
        if (pending.isEmpty()) {
            return;
        }
        if (hasOutputGuardrails) {
            responseBuffer.add(pending);
        } else {
            publishAiText(pending);
        }
    }

    private void publishAiText(String text) {
        if (generationStreamSignalHandler != null) {
            publishGenerationSignalsAtomically(() -> {
                markInternalRecoveredBeforeTrustedOutput();
                publishedTextCodePoints += text.codePointCount(
                        0, text.length());
                publishGenerationSignal(
                        new GenerationStreamSignal.AiText(
                                requestGeneration, text));
            });
        } else {
            markInternalRecoveredBeforeTrustedOutput();
            partialResponseHandler.accept(text);
        }
    }

    private void publishGenerationSignalsAtomically(Runnable action) {
        if (generationStreamSignalHandler
                instanceof GenerationSignalPublisher publisher) {
            publisher.publishAtomically(action);
            return;
        }
        action.run();
    }

    private boolean publishGenerationSignal(
            GenerationStreamSignal signal) {
        if (generationSignalPublishingClosed.get()) {
            return false;
        }
        try {
            generationStreamSignalHandler.accept(signal);
            boolean current = requestController.isCurrentGeneration(
                    requestGeneration);
            if (!current) {
                generationSignalPublishingClosed.set(true);
            }
            return current;
        } catch (RuntimeException | Error failure) {
            if (generationSignalPublishingClosed.compareAndSet(
                    false, true)) {
                terminateForGenerationListenerFailure();
            }
            return false;
        }
    }

    private void terminateForGenerationListenerFailure() {
        try {
            if (internalOutputRecoveryCoordinator != null) {
                if (!internalOutputRecoveryCoordinator
                        .failBeforeRecoveryStart()
                        && !internalOutputRecoveryCoordinator
                        .failAfterRecoveryStart()) {
                    internalOutputRecoveryCoordinator.closeSilently();
                }
            }
        } catch (RuntimeException | Error ignored) {
            internalOutputRecoveryCoordinator.closeSilently();
        } finally {
            failInternalOutputProtocol();
        }
    }

    private void markInternalRecoveredBeforeTrustedOutput() {
        if (internalOutputRecoveryCoordinator != null) {
            internalOutputRecoveryCoordinator.recovered(requestGeneration);
        }
    }

    private boolean shouldQuarantineTrustedOutput() {
        return incompleteRecoveryCoordinator != null
                && !structuredToolCallObserved
                && incompleteRecoveryCoordinator
                .shouldQuarantineOrdinaryText();
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
        submitProviderCallback(() ->
                handlePartialToolExecutionRequest(
                        index, partialToolExecutionRequest));
    }

    private void handlePartialToolExecutionRequest(
            int index,
            ToolExecutionRequest partialToolExecutionRequest) {
        AtomicBoolean recoveryPrepared = new AtomicBoolean();
        try (var callback = requestController.enterCallback(
                requestGeneration)) {
            if (callback != null) {
                processPartialToolRequest(
                        index, partialToolExecutionRequest,
                        recoveryPrepared);
            }
        }
        if (recoveryPrepared.get()) {
            prepareInternalRecoveryRequest(new TokenUsage());
        }
    }

    private void processPartialToolRequest(
            int index,
            ToolExecutionRequest request,
            AtomicBoolean recoveryPrepared) {
        observeStructuredToolCall();
        if (toolArgumentLeakScanner == null) {
            publishPartialToolRequest(index, request);
            return;
        }
        ToolArgumentLeakScanner.Status status = toolArgumentLeakScanner
                .accept(request.id(), request.arguments()).status();
        if (status == ToolArgumentLeakScanner.Status.INVALID
                || status == ToolArgumentLeakScanner.Status.MISMATCH) {
            failStreamConsistency();
            return;
        }
        if (status == ToolArgumentLeakScanner.Status.VIOLATION) {
            handleInternalViolation(recoveryPrepared);
            return;
        }
        enqueueToolDisclosure(
                request.id(), status,
                () -> publishPartialToolRequest(index, request));
    }

    private void publishPartialToolRequest(
            int index, ToolExecutionRequest request) {
        if (generationStreamSignalHandler != null) {
            provisionalToolRequestIds.add(request.id());
            publishGenerationSignal(
                    new GenerationStreamSignal.PartialToolRequest(
                            requestGeneration, index, request));
        } else if (partialToolExecutionRequestHandler != null) {
            partialToolExecutionRequestHandler.accept(index, request);
        }
    }

    private void enqueueToolDisclosure(
            String requestId,
            ToolArgumentLeakScanner.Status status,
        Runnable action) {
        if (status == ToolArgumentLeakScanner.Status.BUFFERING) {
            bufferingToolRequestIds.add(requestId);
            GenerationDisclosureBuffer.Disclosure disclosure =
                    disclosureBuffer.enqueuePending(action);
            bufferedToolDisclosures.computeIfAbsent(
                    requestId, ignored -> new ArrayList<>()).add(disclosure);
            return;
        }
        resolveBufferedToolDisclosures(requestId);
        disclosureBuffer.enqueueResolved(action);
    }

    private void resolveBufferedToolDisclosures(String requestId) {
        bufferingToolRequestIds.remove(requestId);
        List<GenerationDisclosureBuffer.Disclosure> disclosures =
                bufferedToolDisclosures.remove(
                requestId);
        if (disclosures == null) {
            return;
        }
        for (GenerationDisclosureBuffer.Disclosure disclosure :
                disclosures) {
            disclosureBuffer.resolveDelayed(disclosure);
        }
    }

    @Override
    public void onCompleteToolExecutionRequest(
            int index, ToolExecutionRequest completeToolExecutionRequest) {
        submitProviderCallback(() ->
                handleCompleteToolExecutionRequest(
                        index, completeToolExecutionRequest));
    }

    private void handleCompleteToolExecutionRequest(
            int index,
            ToolExecutionRequest completeToolExecutionRequest) {
        AtomicBoolean recoveryPrepared = new AtomicBoolean();
        try (var callback = requestController.enterCallback(
                requestGeneration)) {
            if (callback != null) {
                processCompleteToolRequest(
                        index, completeToolExecutionRequest,
                        recoveryPrepared);
            }
        }
        if (recoveryPrepared.get()) {
            prepareInternalRecoveryRequest(new TokenUsage());
        }
    }

    private void processCompleteToolRequest(
            int index,
            ToolExecutionRequest request,
            AtomicBoolean recoveryPrepared) {
        observeStructuredToolCall();
        if (toolArgumentLeakScanner == null) {
            return;
        }
        ToolArgumentLeakScanner.Status status = toolArgumentLeakScanner
                .complete(request.id(), request.arguments()).status();
        if (status == ToolArgumentLeakScanner.Status.VIOLATION) {
            dropBufferedToolDisclosures(request.id());
            handleInternalViolation(recoveryPrepared);
            return;
        }
        if (status != ToolArgumentLeakScanner.Status.SAFE) {
            dropBufferedToolDisclosures(request.id());
            failStreamConsistency();
            return;
        }
        verifiedToolArguments.put(request.id(), request.arguments());
        resolveBufferedToolDisclosures(request.id());
    }

    private void dropBufferedToolDisclosures(String requestId) {
        bufferingToolRequestIds.remove(requestId);
        List<GenerationDisclosureBuffer.Disclosure> disclosures =
                bufferedToolDisclosures.remove(
                requestId);
        if (disclosures == null) {
            return;
        }
        disclosureBuffer.removeAll(disclosures);
    }

    private void handleInternalViolation(
            AtomicBoolean recoveryPrepared) {
        clearPendingInternalDisclosures();
        InternalOutputRecoveryCoordinator.ViolationAction action =
                internalOutputRecoveryCoordinator.claimViolation(
                        requestGeneration);
        if (action == InternalOutputRecoveryCoordinator
                .ViolationAction.IGNORE) {
            return;
        }
        publishGenerationSignalsAtomically(() -> {
            publishInternalRollback();
            if (action == InternalOutputRecoveryCoordinator
                    .ViolationAction.FAIL) {
                internalOutputRecoveryCoordinator.failForRecoveryViolation(
                        requestGeneration);
            }
        });
        if (action == InternalOutputRecoveryCoordinator
                .ViolationAction.FAIL) {
            failInternalOutputProtocol();
            return;
        }
        StreamingRequestController.GenerationCancellation cancellation =
                requestController.cancelGenerationForRecovery(
                        requestGeneration);
        if (cancellation != StreamingRequestController
                .GenerationCancellation.CANCELLED) {
            internalOutputRecoveryCoordinator.releaseRecoveryReservation();
            return;
        }
        recoveryPrepared.set(true);
    }

    private void clearPendingInternalDisclosures() {
        disclosureBuffer.clear();
        bufferedTextDisclosure = null;
        bufferedToolDisclosures.clear();
        if (toolArgumentLeakScanner != null) {
            bufferingToolRequestIds.forEach(
                    toolArgumentLeakScanner::discard);
        }
        bufferingToolRequestIds.clear();
        verifiedToolArguments.clear();
    }

    private void publishInternalRollback() {
        if (generationStreamSignalHandler == null) {
            return;
        }
        publishGenerationSignal(
                new GenerationStreamSignal.Rollback(
                        requestGeneration,
                        publishedTextCodePoints,
                        Set.copyOf(provisionalToolRequestIds)));
    }

    private void failInternalOutputProtocol() {
        ToolLoopTerminationProtocol.ControlledTermination termination =
                new ToolLoopTerminationProtocol.ControlledTermination(
                        ToolLoopTerminationProtocol
                                .ControlledTerminationReason.PROTOCOL_ERROR,
                        null);
        if (requestController.claimControlledTermination(
                requestGeneration, termination)) {
            requestController.dispatchClaimedTermination();
        }
    }

    private void observeStructuredToolCall() {
        synchronized (recoveryDetectionMonitor) {
            structuredToolCallObserved = true;
        }
        if (incompleteRecoveryGeneration
                && incompleteRecoveryCoordinator != null) {
            incompleteRecoveryCoordinator.recovered();
        }
        deliverAvailableTrustedOutput();
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
        submitProviderCallback(() ->
                handleCompleteResponse(completeResponse));
    }

    private void handleCompleteResponse(ChatResponse completeResponse) {
        AtomicBoolean protocolRecoveryPrepared = new AtomicBoolean();
        AtomicBoolean internalRecoveryPrepared = new AtomicBoolean();
        AtomicBoolean incompleteRecoveryPrepared = new AtomicBoolean();
        AtomicReference<ChatResponse> continuationResponse =
                new AtomicReference<>();
        try (var callback = requestController.enterCallback(
                requestGeneration)) {
            if (callback == null) {
                return;
            }
            processCompleteResponse(
                    completeResponse,
                    protocolRecoveryPrepared,
                    internalRecoveryPrepared,
                    incompleteRecoveryPrepared,
                    continuationResponse);
        }
        if (internalRecoveryPrepared.get()) {
            prepareInternalRecoveryRequest(TokenUsage.sum(
                    tokenUsage, completeResponse.metadata().tokenUsage()));
        } else if (protocolRecoveryPrepared.get()) {
            prepareRecoveryRequest(TokenUsage.sum(
                    tokenUsage, completeResponse.metadata().tokenUsage()));
        } else if (incompleteRecoveryPrepared.get()) {
            prepareIncompleteRecoveryRequest(TokenUsage.sum(
                    tokenUsage, completeResponse.metadata().tokenUsage()));
        } else if (continuationResponse.get() != null) {
            submitNextModelRequest(continuationResponse.get());
        }
    }

    private void processCompleteResponse(
            ChatResponse completeResponse,
            AtomicBoolean protocolRecoveryPrepared,
            AtomicBoolean internalRecoveryPrepared,
            AtomicBoolean incompleteRecoveryPrepared,
            AtomicReference<ChatResponse> continuationResponse) {
        if (!requestController.isCurrentGeneration(requestGeneration)) {
            return;
        }
        if (completeResponse == null || completeResponse.aiMessage() == null) {
            failStreamConsistency();
            return;
        }
        AiMessage aiMessage = completeResponse.aiMessage();
        if (!isCompleteTextWithinLimit(aiMessage.text())) {
            terminateForResponseLimit();
            return;
        }
        if (!aiMessage.hasToolExecutionRequests()) {
            if (!completeInternalTextDetection(
                    aiMessage.text(), internalRecoveryPrepared)
                    || !completeRecoveryDetection(
                    aiMessage.text(), protocolRecoveryPrepared)) {
                return;
            }
            observeIncompleteOrdinaryCompletion(completeResponse, aiMessage);
            if (!handleIncompleteOrdinaryCompletion(
                    incompleteRecoveryPrepared)) {
                return;
            }
            aiMessage = sanitizedOrdinaryMessage(aiMessage);
            markRecoveredBeforeTrustedOutput();
            markIncompleteRecoveredBeforeTrustedOutput();
            deliverAvailableTrustedOutput();
            completeOrdinaryResponse(
                    completeResponse, aiMessage,
                    internalRecoveryPrepared);
            return;
        }
        observeStructuredToolCall();
        if (!completeInternalTextDetection(
                aiMessage.text(), internalRecoveryPrepared)
                || !completeToolRecoveryDetection(aiMessage.text())) {
            return;
        }
        if (!verifyCompleteToolRequests(
                aiMessage.toolExecutionRequests(),
                internalRecoveryPrepared)) {
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
        if (generationStreamSignalHandler
                instanceof GenerationSignalPublisher publisher) {
            publisher.publishAtomically(() -> {
                markInternalRecoveredBeforeTrustedOutput();
                publishCommittedToolRequests(requests);
            }, () -> executeCommittedToolBatchAndContinue(
                    requests, batchTicket, completeResponse));
            return;
        }
        publishGenerationSignalsAtomically(() -> {
            markInternalRecoveredBeforeTrustedOutput();
            publishCommittedToolRequests(requests);
        });
        executeCommittedToolBatch(
                requests, batchTicket, completeResponse,
                continuationResponse);
    }

    private boolean completeInternalTextDetection(
            String completeText,
            AtomicBoolean recoveryPrepared) {
        if (internalOutputLeakDetector == null) {
            return true;
        }
        String normalizedText = completeText == null ? "" : completeText;
        String suffix;
        synchronized (recoveryDetectionMonitor) {
            String observed = internalObservedResponseText.toString();
            if (!normalizedText.startsWith(observed)) {
                failStreamConsistency();
                return false;
            }
            suffix = normalizedText.substring(observed.length());
            internalObservedResponseText.append(suffix);
        }
        InternalOutputLeakDetector.DetectionResult suffixResult =
                internalOutputLeakDetector.accept(suffix);
        AtomicBoolean protocolRecoveryPrepared = new AtomicBoolean();
        discloseInternalText(
                suffixResult,
                protocolRecoveryPrepared,
                recoveryPrepared);
        if (suffixResult.status()
                == InternalOutputLeakDetector.Status.VIOLATION) {
            return false;
        }
        InternalOutputLeakDetector.DetectionResult finishResult =
                internalOutputLeakDetector.finish();
        discloseInternalText(
                finishResult,
                protocolRecoveryPrepared,
                recoveryPrepared);
        return finishResult.status()
                != InternalOutputLeakDetector.Status.VIOLATION
                && requestController.isCurrentGeneration(requestGeneration);
    }

    private boolean verifyCompleteToolRequests(
            List<ToolExecutionRequest> requests,
            AtomicBoolean recoveryPrepared) {
        if (toolArgumentLeakScanner == null) {
            return true;
        }
        for (ToolExecutionRequest request : requests) {
            String verifiedArguments = verifiedToolArguments.remove(
                    request.id());
            ToolArgumentLeakScanner.Status status = verifiedArguments == null
                    ? toolArgumentLeakScanner.complete(
                            request.id(), request.arguments()).status()
                    : verifiedArguments.equals(request.arguments())
                            ? ToolArgumentLeakScanner.Status.SAFE
                            : ToolArgumentLeakScanner.Status.MISMATCH;
            if (status == ToolArgumentLeakScanner.Status.VIOLATION) {
                handleInternalViolation(recoveryPrepared);
                return false;
            }
            if (status != ToolArgumentLeakScanner.Status.SAFE) {
                failStreamConsistency();
                return false;
            }
        }
        return true;
    }

    private void publishCommittedToolRequests(
            List<ToolExecutionRequest> requests) {
        if (generationStreamSignalHandler == null) {
            return;
        }
        for (int index = 0; index < requests.size(); index++) {
            ToolExecutionRequest request = requests.get(index);
            provisionalToolRequestIds.add(request.id());
            publishGenerationSignal(
                    new GenerationStreamSignal.CompleteToolRequest(
                            requestGeneration, index, request));
        }
    }

    private boolean handleIncompleteOrdinaryCompletion(
            AtomicBoolean recoveryPrepared) {
        if (incompleteRecoveryCoordinator == null) {
            return true;
        }
        IncompleteToolChainRecoveryCoordinator.CompletionAction action =
                incompleteRecoveryCoordinator.claimOrdinaryCompletion(
                        requestGeneration);
        if (action == IncompleteToolChainRecoveryCoordinator
                .CompletionAction.COMPLETE) {
            return true;
        }
        if (action == IncompleteToolChainRecoveryCoordinator
                .CompletionAction.IGNORE) {
            return false;
        }
        if (action == IncompleteToolChainRecoveryCoordinator
                .CompletionAction.FAIL) {
            failIncompleteToolChain();
            return false;
        }
        StreamingRequestController.GenerationCancellation cancellation =
                requestController.cancelGenerationForRecovery(
                        requestGeneration);
        if (cancellation != StreamingRequestController
                .GenerationCancellation.CANCELLED) {
            incompleteRecoveryCoordinator.releaseRecoveryReservation();
            return false;
        }
        incompleteRecoveryCoordinator.recoveryStarted();
        recoveryPrepared.set(true);
        return false;
    }

    private void observeIncompleteOrdinaryCompletion(
            ChatResponse response, AiMessage aiMessage) {
        if (incompleteRecoveryCoordinator == null
                || !incompleteRecoveryCoordinator
                .shouldQuarantineOrdinaryText()) {
            return;
        }
        ChatResponseMetadata metadata = response.metadata();
        TokenUsage usage = metadata == null ? null : metadata.tokenUsage();
        LOG.info("Incomplete tool chain returned ordinary text: memoryId={}, "
                        + "generation={}, recovery={}, textChars={}, "
                        + "finishReason={}, inputTokens={}, outputTokens={}",
                memoryId,
                requestGeneration,
                incompleteRecoveryGeneration,
                Objects.toString(aiMessage.text(), "").length(),
                metadata == null ? null : metadata.finishReason(),
                usage == null ? null : usage.inputTokenCount(),
                usage == null ? null : usage.outputTokenCount());
    }

    private void failIncompleteToolChain() {
        ToolLoopTerminationProtocol.ControlledTermination termination =
                new ToolLoopTerminationProtocol.ControlledTermination(
                        ToolLoopTerminationProtocol.ControlledTerminationReason
                                .INCOMPLETE_TOOL_CHAIN,
                        null);
        if (requestController.claimControlledTermination(
                requestGeneration, termination)) {
            incompleteRecoveryCoordinator.failForIncompleteCompletion();
            requestController.dispatchClaimedTermination();
        }
    }

    private void markIncompleteRecoveredBeforeTrustedOutput() {
        if (incompleteRecoveryGeneration
                && incompleteRecoveryCoordinator != null) {
            incompleteRecoveryCoordinator.recovered();
        }
    }

    private AiMessage sanitizedToolMessage(AiMessage original) {
        if (recoveryDetector == null
                && internalOutputLeakDetector == null) {
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

    private AiMessage sanitizedOrdinaryMessage(AiMessage original) {
        if (internalOutputLeakDetector == null) {
            return original;
        }
        String trustedText;
        synchronized (recoveryDetectionMonitor) {
            trustedText = trustedResponseText.toString();
        }
        return AiMessage.from(trustedText);
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
                continue;
            }
            RepeatedReadLoopGuard.Action readLoopAction =
                    requestController.observeRepeatedRead(
                            normalizedRequest,
                            guardedExecution.toolResult());
            if (readLoopAction == RepeatedReadLoopGuard.Action.TERMINATE) {
                ToolLoopTerminationProtocol.ControlledTermination
                        readLoopTermination =
                        new ToolLoopTerminationProtocol.ControlledTermination(
                                ToolLoopTerminationProtocol
                                        .ControlledTerminationReason
                                        .REPEATED_READ_LOOP,
                                null);
                if (requestController.claimControlledTermination(
                        requestGeneration, readLoopTermination)) {
                    claimedTermination = readLoopTermination;
                    skipRemainderReason =
                            "受控跳过：模型连续重复相同读取操作";
                    dispatchTermination = true;
                }
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

    private void executeCommittedToolBatchAndContinue(
            List<ToolExecutionRequest> requests,
            StreamingRequestController.ToolBatchTicket batchTicket,
            ChatResponse completeResponse) {
        AtomicReference<ChatResponse> continuationResponse =
                new AtomicReference<>();
        executeCommittedToolBatch(
                requests, batchTicket, completeResponse,
                continuationResponse);
        ChatResponse continuation = continuationResponse.get();
        if (continuation != null) {
            submitNextModelRequest(continuation);
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

    private boolean reservePartialResponse(String partialResponse) {
        Objects.requireNonNull(partialResponse, "流式正文分片不能为空");
        synchronized (recoveryDetectionMonitor) {
            int remaining = MAX_TRACKED_RESPONSE_CHARS
                    - streamedResponseChars;
            if (partialResponse.length() > remaining) {
                return false;
            }
            streamedResponseChars += partialResponse.length();
            return true;
        }
    }

    private boolean isCompleteTextWithinLimit(String completeText) {
        return completeText == null
                || completeText.length() <= MAX_TRACKED_RESPONSE_CHARS;
    }

    private void terminateForResponseLimit() {
        ToolLoopTerminationProtocol.ControlledTermination termination =
                new ToolLoopTerminationProtocol.ControlledTermination(
                        ToolLoopTerminationProtocol
                                .ControlledTerminationReason
                                .RESOURCE_LIMIT_EXCEEDED,
                        null);
        requestController.terminate(requestGeneration, termination);
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
                                true,
                                incompleteRecoveryGeneration)));
    }

    private void prepareInternalRecoveryRequest(
            TokenUsage accumulatedUsage) {
        ModelRequestGate.Request gateRequest = modelRequestGate == null
                ? null
                : new ModelRequestGate.Request(
                        memoryId,
                        this::getMemory,
                        toolSpecifications,
                        continuationGate,
                        internalOutputRecoveryCoordinator
                                .transientMessages(),
                        compressionAttemptState);
        requestOrchestrator.submit(
                GenerationAwareModelRequestOrchestrator
                        .internalProtocolRecovery(
                        requestGeneration,
                        gateRequest,
                        () -> messagesToSend(memoryId),
                        this::failInternalRecoveryBeforeStart,
                        internalOutputRecoveryCoordinator::closeSilently,
                        internalOutputRecoveryCoordinator
                                ::recoveryStartCommitted,
                        this::notifyInternalRecoveryFailure,
                        (messages, generation) -> startModelRequest(
                                messages,
                                accumulatedUsage,
                                generation,
                                recoveryGeneration,
                                incompleteRecoveryGeneration)));
    }

    private void failInternalRecoveryBeforeStart() {
        internalOutputRecoveryCoordinator.failBeforeRecoveryStart();
    }

    private void notifyInternalRecoveryFailure(Throwable failure) {
        if (!internalOutputRecoveryCoordinator.failBeforeRecoveryStart()) {
            internalOutputRecoveryCoordinator.failAfterRecoveryStart();
        }
    }

    private void prepareIncompleteRecoveryRequest(
            TokenUsage accumulatedUsage) {
        ModelRequestGate.Request gateRequest = modelRequestGate == null
                ? null
                : new ModelRequestGate.Request(
                        memoryId,
                        this::getMemory,
                        toolSpecifications,
                        continuationGate,
                        incompleteRecoveryCoordinator.transientMessages(),
                        compressionAttemptState);
        requestOrchestrator.submit(
                GenerationAwareModelRequestOrchestrator.recovery(
                        requestGeneration,
                        gateRequest,
                        () -> messagesToSend(memoryId),
                        incompleteRecoveryCoordinator::failIfRecovering,
                        this::notifyIncompleteRecoveryFailure,
                        (messages, generation) -> startModelRequest(
                                messages, accumulatedUsage, generation,
                                false, true)));
    }

    private void notifyIncompleteRecoveryFailure(Throwable failure) {
        incompleteRecoveryCoordinator.failIfRecovering();
        notifyError(failure);
    }

    private void notifyRecoveryFailure(Throwable failure) {
        recoveryCoordinator.failIfRecovering();
        notifyError(failure);
    }

    private void submitNextModelRequest(ChatResponse completeResponse) {
        TokenUsage accumulatedUsage = TokenUsage.sum(
                tokenUsage, completeResponse.metadata().tokenUsage());
        List<ChatMessage> transientMessages =
                requestController.claimRepeatedReadCorrection();
        ModelRequestGate.Request gateRequest = modelRequestGate == null
                ? null
                : new ModelRequestGate.Request(
                        memoryId,
                        this::getMemory,
                        toolSpecifications,
                        continuationGate,
                        transientMessages,
                        compressionAttemptState);
        requestOrchestrator.submit(
                GenerationAwareModelRequestOrchestrator.continuation(
                        requestGeneration,
                        gateRequest,
                        () -> messagesToSendWithTransient(
                                memoryId, transientMessages),
                        this::notifyError,
                        (messages, generation) -> startModelRequest(
                                messages,
                                accumulatedUsage,
                                generation,
                                false,
                                false)));
    }

    private List<ChatMessage> messagesToSendWithTransient(
            Object memId, List<ChatMessage> transientMessages) {
        List<ChatMessage> messages = new ArrayList<>(messagesToSend(memId));
        messages.addAll(transientMessages);
        return List.copyOf(messages);
    }

    private Runnable startModelRequest(
            List<ChatMessage> requestMessages,
            TokenUsage accumulatedUsage,
            long nextGeneration,
            boolean recoveryGeneration,
            boolean incompleteRecoveryGeneration) {
        ChatRequest.Builder requestBuilder = ChatRequest.builder()
                .messages(requestMessages)
                .toolSpecifications(toolSpecifications);
        if (shouldRequireToolCall(incompleteRecoveryGeneration)) {
            requestBuilder.toolChoice(ToolChoice.REQUIRED);
        }
        ChatRequest chatRequest = requestBuilder.build();
        AiServiceStreamingResponseHandler child = childHandler(
                accumulatedUsage, nextGeneration, recoveryGeneration,
                incompleteRecoveryGeneration);
        return () -> context.streamingChatModel.chat(chatRequest, child);
    }

    private boolean shouldRequireToolCall(
            boolean nextIncompleteRecoveryGeneration) {
        return nextIncompleteRecoveryGeneration
                && incompleteRecoveryCoordinator != null
                && incompleteRecoveryCoordinator.shouldQuarantineOrdinaryText();
    }

    private AiServiceStreamingResponseHandler childHandler(
            TokenUsage accumulatedUsage,
            long nextGeneration,
            boolean recoveryGeneration,
            boolean incompleteRecoveryGeneration) {
        AiServiceStreamingResponseHandler child =
                new AiServiceStreamingResponseHandler(
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
                incompleteRecoveryCoordinator,
                incompleteRecoveryGeneration,
                compressionAttemptState,
                internalOutputRecoveryPolicy,
                internalOutputRecoveryCoordinator,
                generationStreamSignalHandler);
        return child;
    }

    private void completeOrdinaryResponse(
            ChatResponse completeResponse,
            AiMessage aiMessage,
            AtomicBoolean internalRecoveryPrepared) {
        try {
            ChatResponse finalChatResponse = ChatResponse.builder()
                    .aiMessage(aiMessage)
                    .metadata(completeResponse.metadata().toBuilder()
                            .tokenUsage(tokenUsage.add(
                                    completeResponse.metadata().tokenUsage()))
                            .build())
                    .build();
            if (hasOutputGuardrails && commonGuardrailParams != null) {
                var newCommonParams = GuardrailRequestParams.builder()
                        .chatMemory(getMemory())
                        .augmentationResult(
                                commonGuardrailParams.augmentationResult())
                        .userMessageTemplate(
                                commonGuardrailParams.userMessageTemplate())
                        .variables(commonGuardrailParams.variables())
                        .build();
                var outputGuardrailParams = OutputGuardrailRequest.builder()
                        .responseFromLLM(finalChatResponse)
                        .chatExecutor(chatExecutor)
                        .requestParams(newCommonParams)
                        .build();
                finalChatResponse = context.guardrailService()
                        .executeGuardrails(
                                methodKey, outputGuardrailParams);
            }
            AiMessage finalAiMessage = finalChatResponse.aiMessage();
            if (finalAiMessage == null) {
                throw new StreamingResponseConsistencyException();
            }
            if (hasOutputGuardrails
                    && finalAiMessage.hasToolExecutionRequests()) {
                responseBuffer.clear();
                if (internalOutputRecoveryCoordinator != null) {
                    internalOutputRecoveryCoordinator.closeSilently();
                }
                failInternalOutputProtocol();
                return;
            }
            if (!isCompleteTextWithinLimit(finalAiMessage.text())) {
                responseBuffer.clear();
                terminateForResponseLimit();
                return;
            }
            if (hasOutputGuardrails
                    && finalProjectionViolatesInternalProtocol(
                    finalAiMessage.text())) {
                responseBuffer.clear();
                handleInternalViolation(internalRecoveryPrepared);
                return;
            }
            if (hasOutputGuardrails) {
                responseBuffer.clear();
                String finalText = finalAiMessage.text();
                if (finalText != null && !finalText.isEmpty()) {
                    publishAiText(finalText);
                }
                if (!requestController.isCurrentGeneration(
                        requestGeneration)) {
                    return;
                }
            }
            if (!requestController.claimNormalCompletion(
                    requestGeneration)) {
                return;
            }
            addToMemory(finalAiMessage);
            if (completeResponseHandler != null) {
                completeResponseHandler.accept(finalChatResponse);
            }
            requestController.finishNormalCompletion();
        } catch (RuntimeException exception) {
            if (!requestController.failNormalCompletion(
                    exception, errorHandler)
                    && requestController.claimErrorCompletion(
                    requestGeneration)) {
                notifyError(exception);
            }
        }
    }

    private boolean finalProjectionViolatesInternalProtocol(
            String finalText) {
        if (internalOutputRecoveryPolicy == null) {
            return false;
        }
        InternalOutputLeakDetector detector =
                internalOutputRecoveryPolicy.newLeakDetector();
        InternalOutputLeakDetector.DetectionResult accepted =
                detector.accept(finalText == null ? "" : finalText);
        if (accepted.status()
                == InternalOutputLeakDetector.Status.VIOLATION) {
            return true;
        }
        return detector.finish().status()
                == InternalOutputLeakDetector.Status.VIOLATION;
    }

    private void completeClaimedTermination(
            ToolLoopTerminationProtocol.ControlledTermination termination) {
        String finalResponse = termination.finalResponse();
        if (finalResponse != null) {
            AiMessage finalMessage = AiMessage.from(finalResponse);
            addToMemory(finalMessage);
            publishAiText(finalResponse);
            // 受控终止由专用回调唯一收口；普通完成回调会抢先结束上层 Flux，
            // 使随后的 CANCELLED / PROTOCOL_ERROR 等类型化终态被静默丢弃。
        }
    }

    private void notifyToolExecutedCallback(
            ToolExecutionRequest request, String result) {
        ToolExecution execution = ToolExecution.builder()
                .request(request)
                .result(result)
                .build();
        provisionalToolRequestIds.remove(request.id());
        if (generationStreamSignalHandler != null) {
            publishGenerationSignal(
                    new GenerationStreamSignal.ToolExecuted(
                            requestGeneration, execution));
        } else if (toolExecutionHandler != null) {
            toolExecutionHandler.accept(execution);
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
        submitProviderCallback(() -> handleError(error));
    }

    private void handleError(Throwable error) {
        boolean dispatchInternalProtocolTermination = false;
        try (var callback = requestController.enterCallback(
                requestGeneration)) {
            if (callback == null) {
                return;
            }
            if (internalOutputRecoveryCoordinator != null
                    && internalOutputRecoveryCoordinator
                    .isRecoveryInProgress()) {
                ToolLoopTerminationProtocol.ControlledTermination
                        termination =
                        new ToolLoopTerminationProtocol
                                .ControlledTermination(
                                ToolLoopTerminationProtocol
                                        .ControlledTerminationReason
                                        .PROTOCOL_ERROR,
                                null);
                if (!requestController.claimControlledTermination(
                        requestGeneration, termination)) {
                    return;
                }
                internalOutputRecoveryCoordinator
                        .failAfterRecoveryStart();
                dispatchInternalProtocolTermination = true;
                return;
            }
            if (!requestController.claimErrorCompletion(requestGeneration)) {
                return;
            }
            if (recoveryGeneration && recoveryCoordinator != null) {
                recoveryCoordinator.failIfRecovering();
            }
            if (incompleteRecoveryGeneration
                    && incompleteRecoveryCoordinator != null) {
                incompleteRecoveryCoordinator.failIfRecovering();
            }
            notifyError(error);
        } finally {
            if (dispatchInternalProtocolTermination) {
                requestController.dispatchClaimedTermination();
            }
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
