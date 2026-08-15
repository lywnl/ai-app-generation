package dev.langchain4j.service;

import dev.langchain4j.Internal;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.guardrail.ChatExecutor;
import dev.langchain4j.guardrail.GuardrailRequestParams;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.tool.ToolExecution;
import dev.langchain4j.service.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
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
    private BiConsumer<Integer, ToolExecutionRequest> partialToolExecutionRequestHandler;
    private BiConsumer<Integer, ToolExecutionRequest> completeToolExecutionRequestHandler;
    private final StreamingRequestController requestController =
            new StreamingRequestController();
    private ToolExecutionGuard toolExecutionGuard = ToolExecutionGuard.direct();
    private ModelRequestGate modelRequestGate;
    private ModelRequestGate.ContinuationGate continuationGate;

    private int onPartialResponseInvoked;
    private int onCompleteResponseInvoked;
    private int onRetrievedInvoked;
    private int onToolExecutedInvoked;
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
        return this;
    }

    @Override
    public TokenStream onCompleteToolExecutionRequest(BiConsumer<Integer, ToolExecutionRequest> completedHandler) {
        this.completeToolExecutionRequestHandler = completedHandler;
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
        requestController.cancel();
    }

    @Override
    public TokenStream toolExecutionGuard(ToolExecutionGuard guard) {
        this.toolExecutionGuard = ensureNotNull(guard, "toolExecutionGuard");
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
    public TokenStream onControlledTermination(
            Consumer<ToolLoopTerminationProtocol.ControlledTermination> handler) {
        requestController.onControlledTermination(handler);
        return this;
    }

    @Override
    public TokenStream requestControlledTermination(
            ToolLoopTerminationProtocol.ControlledTermination termination) {
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
        if (modelRequestGate == null) {
            startInitialModelRequest(messages, temporaryMemory, false);
            return;
        }
        prepareInitialModelRequest(temporaryMemory);
    }

    private void prepareInitialModelRequest(ChatMemory temporaryMemory) {
        CompletionStage<ModelRequestGate.Decision> preparation;
        try {
            preparation = modelRequestGate.prepare(new ModelRequestGate.Request(
                    memoryId,
                    () -> activeMemory(temporaryMemory),
                    toolSpecifications,
                    continuationGate));
        } catch (RuntimeException exception) {
            deliverGateFailure(new IllegalStateException(
                    "模型请求门禁准备失败", exception));
            return;
        }
        if (preparation == null) {
            deliverGateFailure(new IllegalStateException(
                    "模型请求门禁未返回准备结果"));
            return;
        }
        observeInitialPreparation(preparation, temporaryMemory);
    }

    private void observeInitialPreparation(
            CompletionStage<ModelRequestGate.Decision> preparation,
            ChatMemory temporaryMemory) {
        CompletionStage<ModelRequestGate.DispatchStatus> dispatch;
        try {
            dispatch = modelRequestGate.onPrepared(
                    preparation,
                    (decision, failure) -> finishInitialPreparation(
                            decision, failure, temporaryMemory));
        } catch (RuntimeException exception) {
            deliverGateFailure(new IllegalStateException(
                    "模型请求门禁完成回调注册失败", exception));
            return;
        }
        if (dispatch == null) {
            deliverGateFailure(new IllegalStateException(
                    "模型请求门禁未返回完成回调调度结果"));
            return;
        }
        dispatch.whenComplete(this::finishInitialDispatch);
    }

    private void finishInitialDispatch(
            ModelRequestGate.DispatchStatus status,
            Throwable failure) {
        if (failure != null) {
            deliverGateFailure(new IllegalStateException(
                    "模型请求门禁完成回调执行失败", failure));
            return;
        }
        if (status != ModelRequestGate.DispatchStatus.DISPATCHED) {
            deliverGateFailure(new IllegalStateException(
                    "模型请求门禁完成回调调度失败"));
        }
    }

    private void finishInitialPreparation(
            ModelRequestGate.Decision decision,
            Throwable failure,
            ChatMemory temporaryMemory) {
        if (failure != null) {
            deliverGateFailure(new IllegalStateException(
                    "模型请求门禁执行失败", failure));
            return;
        }
        if (decision == null) {
            deliverGateFailure(new IllegalStateException(
                    "模型请求门禁返回空决策"));
            return;
        }
        switch (decision.status()) {
            case ALLOWED -> startAllowedInitialRequest(
                    decision.messages(), temporaryMemory);
            case CANCELLED -> requestController.cancel();
            case COMPRESSION_FAILED, HARD_LIMIT_REJECTED ->
                    deliverGateFailure(new IllegalStateException(
                            decision.safeMessage()));
        }
    }

    private void startAllowedInitialRequest(
            List<ChatMessage> preparedMessages,
            ChatMemory temporaryMemory) {
        boolean accepted;
        try {
            accepted = continuationGate.tryRun(() -> {
                try (var callback = requestController.enterCallback()) {
                    if (callback != null) {
                        startInitialModelRequest(
                                preparedMessages, temporaryMemory, true);
                    }
                }
            });
        } catch (RuntimeException exception) {
            deliverGateFailure(exception);
            return;
        }
        if (!accepted) {
            requestController.cancel();
        }
    }

    private void startInitialModelRequest(
            List<ChatMessage> requestMessages,
            ChatMemory temporaryMemory,
            boolean verifyGeneration) {
        if (!requestController.isOpen()) {
            return;
        }

        ChatRequest chatRequest = ChatRequest.builder()
                .messages(requestMessages)
                .toolSpecifications(toolSpecifications)
                .build();

        ChatExecutor chatExecutor = ChatExecutor.builder(context.streamingChatModel)
                .errorHandler(errorHandler)
                .chatRequest(chatRequest)
                .build();

        boolean requestAccepted = verifyGeneration
                ? requestController.beforeModelRequest(0L)
                : requestController.beforeModelRequest();
        if (!requestAccepted) {
            requestController.dispatchClaimedTermination();
            return;
        }
        long requestGeneration = requestController.latestModelRequestGeneration();
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
                new TokenUsage(),
                toolSpecifications,
                toolExecutors,
                commonGuardrailParams,
                methodKey,
                requestController,
                toolExecutionGuard,
                requestGeneration,
                modelRequestGate,
                continuationGate);

        if (contentsHandler != null && retrievedContents != null
                && !requestController.runIfOpen(
                () -> contentsHandler.accept(retrievedContents))) {
            return;
        }

        try {
            requestController.startModelRequestIfOpen(
                    () -> context.streamingChatModel.chat(chatRequest, handler));
        } catch (RuntimeException exception) {
            handler.onError(exception);
        }
    }

    private ChatMemory activeMemory(ChatMemory temporaryMemory) {
        return context.hasChatMemory()
                ? context.chatMemoryService.getOrCreateChatMemory(memoryId)
                : temporaryMemory;
    }

    private void deliverGateFailure(Throwable failure) {
        if (!requestController.completeNormally()) {
            return;
        }
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
        if (onPartialResponseInvoked != 1) {
            throw new IllegalConfigurationException("onPartialResponse must be invoked on TokenStream exactly 1 time");
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
