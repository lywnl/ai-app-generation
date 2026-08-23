package dev.langchain4j.service;

import com.lyw.appgeneration.ai.memory.ContextCompressionAttemptState;
import dev.langchain4j.Internal;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.guardrail.ChatExecutor;
import dev.langchain4j.guardrail.GuardrailRequestParams;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.tool.ToolExecution;
import dev.langchain4j.service.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static dev.langchain4j.internal.Utils.copy;
import static dev.langchain4j.internal.ValidationUtils.ensureNotEmpty;
import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;

@Internal
public class AiServiceTokenStream implements TokenStream {

    private static final Logger LOG =
            LoggerFactory.getLogger(AiServiceTokenStream.class);

    private final List<ChatMessage> messages;
    private final List<ToolSpecification> toolSpecifications;
    private final Map<String, ToolExecutor> toolExecutors;
    private final List<Content> retrievedContents;
    private final AiServiceContext context;
    private final Object memoryId;
    private final GuardrailRequestParams commonGuardrailParams;
    private final Object methodKey;

    private Consumer<String> partialResponseHandler;
    private Consumer<List<Content>> contentsHandler;
    private Consumer<ToolExecution> toolExecutionHandler;
    private Consumer<ChatResponse> completeResponseHandler;
    private Consumer<Throwable> errorHandler;
    private Consumer<ToolLoopTerminationProtocol.ControlledTermination>
            controlledTerminationHandler;
    private final AtomicBoolean controlledTerminationDelivered =
            new AtomicBoolean();
    private BiConsumer<Integer, ToolExecutionRequest> partialToolExecutionRequestHandler;
    private BiConsumer<Integer, ToolExecutionRequest> completeToolExecutionRequestHandler;
    private final StreamingRequestController requestController =
            new StreamingRequestController();
    private ToolExecutionGuard toolExecutionGuard = ToolExecutionGuard.direct();
    private ModelRequestGate modelRequestGate;
    private ModelRequestGate.ContinuationGate continuationGate;
    private ToolProtocolRecoveryCoordinator recoveryCoordinator;
    private IncompleteToolChainRecoveryCoordinator
            incompleteRecoveryCoordinator;
    private InternalOutputRecoveryPolicy internalOutputRecoveryPolicy;
    private Consumer<GenerationStreamSignal> generationStreamSignalHandler;
    private final GenerationDisclosureBuffer generationSignalBus =
            new GenerationDisclosureBuffer();
    private final AtomicBoolean generationSignalFailureHandled =
            new AtomicBoolean();
    private InternalOutputRecoveryCoordinator
            internalOutputRecoveryCoordinator;
    private GenerationAwareModelRequestOrchestrator requestOrchestrator;
    private boolean initialToolChoiceRequired;

    private int onPartialResponseInvoked;
    private int onCompleteResponseInvoked;
    private int onRetrievedInvoked;
    private int onToolExecutedInvoked;
    private int onPartialToolExecutionRequestInvoked;
    private int onCompleteToolExecutionRequestInvoked;
    private int internalOutputRecoveryPolicyInvoked;
    private int onGenerationStreamSignalInvoked;
    private int onErrorInvoked;
    private int ignoreErrorsInvoked;

    /**
     * Creates a new instance of {@link AiServiceTokenStream} with the given parameters.
     *
     * @param parameters the parameters for creating the token stream
     */
    public AiServiceTokenStream(AiServiceTokenStreamParameters parameters) {
        ensureNotNull(parameters, "parameters");
        this.messages = copy(ensureNotEmpty(parameters.messages(), "messages"));
        this.toolSpecifications = copy(parameters.toolSpecifications());
        this.toolExecutors = copy(parameters.toolExecutors());
        this.retrievedContents = copy(parameters.gretrievedContents());
        this.context = ensureNotNull(parameters.context(), "context");
        ensureNotNull(this.context.streamingChatModel, "streamingChatModel");
        this.memoryId = ensureNotNull(parameters.memoryId(), "memoryId");
        this.commonGuardrailParams = parameters.commonGuardrailParams();
        this.methodKey = parameters.methodKey();
    }

    @Override
    public TokenStream onPartialResponse(Consumer<String> partialResponseHandler) {
        this.partialResponseHandler = partialResponseHandler;
        this.onPartialResponseInvoked++;
        return this;
    }

    @Override
    public TokenStream onPartialToolExecutionRequest(BiConsumer<Integer, ToolExecutionRequest> toolExecutionRequestHandler) {
        this.partialToolExecutionRequestHandler = toolExecutionRequestHandler;
        this.onPartialToolExecutionRequestInvoked++;
        return this;
    }

    @Override
    public TokenStream onCompleteToolExecutionRequest(BiConsumer<Integer, ToolExecutionRequest> completedHandler) {
        this.completeToolExecutionRequestHandler = completedHandler;
        this.onCompleteToolExecutionRequestInvoked++;
        return this;
    }

    @Override
    public TokenStream onRetrieved(Consumer<List<Content>> contentsHandler) {
        this.contentsHandler = contentsHandler;
        this.onRetrievedInvoked++;
        return this;
    }

    @Override
    public TokenStream onToolExecuted(Consumer<ToolExecution> toolExecutionHandler) {
        this.toolExecutionHandler = toolExecutionHandler;
        this.onToolExecutedInvoked++;
        return this;
    }

    @Override
    public TokenStream onCompleteResponse(Consumer<ChatResponse> completionHandler) {
        this.completeResponseHandler = completionHandler;
        this.onCompleteResponseInvoked++;
        return this;
    }

    @Override
    public TokenStream onError(Consumer<Throwable> errorHandler) {
        this.errorHandler = errorHandler;
        this.onErrorInvoked++;
        return this;
    }

    @Override
    public TokenStream ignoreErrors() {
        this.errorHandler = null;
        this.ignoreErrorsInvoked++;
        return this;
    }

    @Override
    public void cancel() {
        if (internalOutputRecoveryCoordinator != null) {
            internalOutputRecoveryCoordinator.closeSilently();
        }
        requestController.cancel();
    }

    @Override
    public TokenStream toolExecutionGuard(ToolExecutionGuard guard) {
        this.toolExecutionGuard = ensureNotNull(guard, "toolExecutionGuard");
        return this;
    }

    @Override
    public TokenStream initialToolChoiceRequired(boolean required) {
        this.initialToolChoiceRequired = required;
        return this;
    }

    @Override
    public TokenStream modelRequestGate(
            ModelRequestGate gate,
            ModelRequestGate.ContinuationGate continuationGate) {
        this.modelRequestGate = ensureNotNull(gate, "modelRequestGate");
        this.continuationGate = ensureNotNull(
                continuationGate, "continuationGate");
        return this;
    }

    @Override
    public TokenStream toolProtocolRecoveryPolicy(
            ToolProtocolRecoveryPolicy policy) {
        ToolProtocolRecoveryPolicy checkedPolicy = ensureNotNull(
                policy, "toolProtocolRecoveryPolicy");
        Set<String> specificationNames = toolSpecifications.stream()
                .map(ToolSpecification::name)
                .collect(Collectors.toUnmodifiableSet());
        if (!checkedPolicy.registeredToolNames().equals(specificationNames)) {
            throw new IllegalArgumentException(
                    "恢复策略声明的工具名必须与当前工具规格完全一致");
        }
        if (!toolExecutors.keySet().equals(specificationNames)) {
            throw new IllegalArgumentException(
                    "当前工具执行器必须与当前工具规格完全一致");
        }
        this.recoveryCoordinator = new ToolProtocolRecoveryCoordinator(
                checkedPolicy, specificationNames);
        return this;
    }

    @Override
    public TokenStream incompleteToolChainRecoveryPolicy(
            IncompleteToolChainRecoveryPolicy policy) {
        this.incompleteRecoveryCoordinator =
                new IncompleteToolChainRecoveryCoordinator(
                        ensureNotNull(policy,
                                "incompleteToolChainRecoveryPolicy"));
        return this;
    }

    @Override
    public TokenStream internalOutputRecoveryPolicy(
            InternalOutputRecoveryPolicy policy) {
        this.internalOutputRecoveryPolicy = ensureNotNull(
                policy, "internalOutputRecoveryPolicy");
        this.internalOutputRecoveryPolicyInvoked++;
        return this;
    }

    @Override
    public TokenStream onGenerationStreamSignal(
            Consumer<GenerationStreamSignal> handler) {
        this.generationStreamSignalHandler = ensureNotNull(
                handler, "generationStreamSignalHandler");
        this.onGenerationStreamSignalInvoked++;
        return this;
    }

    @Override
    public TokenStream onControlledTermination(
            Consumer<ToolLoopTerminationProtocol.ControlledTermination> handler) {
        Consumer<ToolLoopTerminationProtocol.ControlledTermination> checked =
                ensureNotNull(handler, "controlledTerminationHandler");
        this.controlledTerminationHandler = termination -> {
            if (controlledTerminationDelivered.compareAndSet(false, true)) {
                checked.accept(termination);
            }
        };
        requestController.onControlledTermination(
                controlledTerminationHandler);
        return this;
    }

    @Override
    public TokenStream requestControlledTermination(
            ToolLoopTerminationProtocol.ControlledTermination termination) {
        if (internalOutputRecoveryCoordinator != null) {
            internalOutputRecoveryCoordinator.closeSilently();
        }
        requestController.terminate(termination);
        return this;
    }

    @Override
    public void start() {
        validateConfiguration();
        if (!requestController.isOpen()) {
            return;
        }

        ChatMemory temporaryMemory = initTemporaryMemory(context, messages);
        ContextCompressionAttemptState compressionAttemptState =
                new ContextCompressionAttemptState();
        requestOrchestrator = new GenerationAwareModelRequestOrchestrator(
                requestController, modelRequestGate, continuationGate);
        if (internalOutputRecoveryPolicy != null) {
            Consumer<GenerationStreamSignal> listener =
                    generationStreamSignalHandler;
            Consumer<GenerationStreamSignal> signalPublisher;
            if (listener == null) {
                signalPublisher = ignored -> { };
            } else {
                signalPublisher = new GenerationSignalPublisher(
                        generationSignalBus,
                        signal -> publishGenerationSignal(listener, signal));
                generationStreamSignalHandler = signalPublisher;
            }
            internalOutputRecoveryCoordinator =
                    new InternalOutputRecoveryCoordinator(
                            internalOutputRecoveryPolicy,
                            signalPublisher);
        }
        ModelRequestGate.Request gateRequest = modelRequestGate == null
                ? null
                : new ModelRequestGate.Request(
                        memoryId,
                        () -> activeMemory(temporaryMemory),
                        toolSpecifications,
                        continuationGate,
                        List.of(),
                        compressionAttemptState);
        requestOrchestrator.submit(
                GenerationAwareModelRequestOrchestrator.initial(
                        gateRequest,
                        () -> messages,
                        this::notifyGateFailure,
                        (preparedMessages, generation) ->
                                prepareInitialModelRequest(
                                        preparedMessages,
                                        temporaryMemory,
                                        generation,
                                        compressionAttemptState)));
    }

    private Runnable prepareInitialModelRequest(
            List<ChatMessage> requestMessages,
            ChatMemory temporaryMemory,
            long requestGeneration,
            ContextCompressionAttemptState compressionAttemptState) {
        ChatRequest.Builder requestBuilder = ChatRequest.builder()
                .messages(requestMessages)
                .toolSpecifications(toolSpecifications);
        if (initialToolChoiceRequired) {
            requestBuilder.toolChoice(ToolChoice.REQUIRED);
        }
        ChatRequest chatRequest = requestBuilder.build();

        ChatExecutor chatExecutor = ChatExecutor.builder(context.streamingChatModel)
                .errorHandler(errorHandler)
                .chatRequest(chatRequest)
                .build();

        var handler = new AiServiceStreamingResponseHandler(
                chatExecutor,
                context,
                memoryId,
                partialResponseHandler,
                partialToolExecutionRequestHandler,
                completeToolExecutionRequestHandler,
                toolExecutionHandler,
                completeResponseHandler,
                this::notifyStreamError,
                temporaryMemory,
                new TokenUsage(),
                toolSpecifications,
                toolExecutors,
                commonGuardrailParams,
                methodKey,
                requestController,
                toolExecutionGuard,
                requestGeneration,
                modelRequestGate,
                continuationGate,
                recoveryCoordinator,
                incompleteRecoveryCoordinator,
                compressionAttemptState,
                internalOutputRecoveryPolicy,
                internalOutputRecoveryCoordinator,
                generationStreamSignalHandler);

        if (contentsHandler != null && retrievedContents != null) {
            contentsHandler.accept(retrievedContents);
        }
        return () -> context.streamingChatModel.chat(chatRequest, handler);
    }

    private ChatMemory activeMemory(ChatMemory temporaryMemory) {
        return context.hasChatMemory()
                ? context.chatMemoryService.getOrCreateChatMemory(memoryId)
                : temporaryMemory;
    }

    private void notifyStreamError(Throwable failure) {
        notifyGateFailure(failure);
    }

    private void publishGenerationSignal(
            Consumer<GenerationStreamSignal> listener,
            GenerationStreamSignal signal) {
        if (generationSignalFailureHandled.get()) {
            return;
        }
        try {
            listener.accept(signal);
        } catch (RuntimeException | Error failure) {
            handleGenerationSignalFailure(signal);
        }
    }

    private void handleGenerationSignalFailure(
            GenerationStreamSignal signal) {
        if (!generationSignalFailureHandled.compareAndSet(false, true)) {
            return;
        }
        boolean recoveryFailed = false;
        if (internalOutputRecoveryCoordinator != null) {
            recoveryFailed = internalOutputRecoveryCoordinator
                    .failBeforeRecoveryStart();
            if (!recoveryFailed) {
                recoveryFailed = internalOutputRecoveryCoordinator
                        .failAfterRecoveryStart();
            }
            if (!recoveryFailed) {
                internalOutputRecoveryCoordinator.closeSilently();
            }
        }
        ToolLoopTerminationProtocol.ControlledTermination termination =
                new ToolLoopTerminationProtocol.ControlledTermination(
                        ToolLoopTerminationProtocol
                                .ControlledTerminationReason.PROTOCOL_ERROR,
                        null);
        requestController.terminate(termination);
    }

    private void notifyGateFailure(Throwable failure) {
        if (errorHandler == null) {
            LOG.warn("Ignored error", failure);
            return;
        }
        try {
            errorHandler.accept(failure);
        } catch (RuntimeException handlerError) {
            LOG.error("While handling the following error...", failure);
            LOG.error("...the following error happened", handlerError);
        }
    }

    private void validateConfiguration() {
        if (internalOutputRecoveryPolicyInvoked > 1) {
            throw new IllegalConfigurationException(
                    "TokenStream 最多只能安装一次内部输出恢复策略");
        }
        if (onGenerationStreamSignalInvoked > 1) {
            throw new IllegalConfigurationException(
                    "TokenStream 最多只能安装一次统一 generation 信号监听器");
        }
        boolean unifiedSignalMode = onGenerationStreamSignalInvoked == 1;
        int legacyStreamingCallbacks = onPartialResponseInvoked
                + onPartialToolExecutionRequestInvoked
                + onCompleteToolExecutionRequestInvoked
                + onToolExecutedInvoked;
        if (unifiedSignalMode && legacyStreamingCallbacks != 0) {
            throw new IllegalConfigurationException(
                    "统一 generation 信号监听器不能与旧流式回调同时安装");
        }
        if (!unifiedSignalMode && onPartialResponseInvoked != 1) {
            throw new IllegalConfigurationException("onPartialResponse must be invoked on TokenStream exactly 1 time");
        }
        if (unifiedSignalMode && internalOutputRecoveryPolicy == null) {
            throw new IllegalConfigurationException(
                    "统一 generation 信号监听器必须同时安装内部输出恢复策略");
        }
        if (internalOutputRecoveryPolicy != null
                && internalOutputRecoveryPolicy.mode()
                == InternalOutputRecoveryPolicy.Mode.RECOVER_ONCE
                && !unifiedSignalMode) {
            throw new IllegalConfigurationException(
                    "一次恢复模式必须安装统一 generation 信号监听器");
        }
        if (onCompleteResponseInvoked > 1) {
            throw new IllegalConfigurationException("onCompleteResponse can be invoked on TokenStream at most 1 time");
        }
        if (onRetrievedInvoked > 1) {
            throw new IllegalConfigurationException("onRetrieved can be invoked on TokenStream at most 1 time");
        }
        if (onToolExecutedInvoked > 1) {
            throw new IllegalConfigurationException("onToolExecuted can be invoked on TokenStream at most 1 time");
        }
        if (onPartialToolExecutionRequestInvoked > 1) {
            throw new IllegalConfigurationException(
                    "TokenStream 最多只能安装一次局部工具请求回调");
        }
        if (onCompleteToolExecutionRequestInvoked > 1) {
            throw new IllegalConfigurationException(
                    "TokenStream 最多只能安装一次完整工具请求回调");
        }
        if (onErrorInvoked + ignoreErrorsInvoked != 1) {
            throw new IllegalConfigurationException(
                    "One of [onError, ignoreErrors] " + "must be invoked on TokenStream exactly 1 time");
        }
    }

    private ChatMemory initTemporaryMemory(AiServiceContext context, List<ChatMessage> messagesToSend) {
        var chatMemory = MessageWindowChatMemory.withMaxMessages(Integer.MAX_VALUE);

        if (!context.hasChatMemory()) {
            chatMemory.add(messagesToSend);
        }

        return chatMemory;
    }
}
