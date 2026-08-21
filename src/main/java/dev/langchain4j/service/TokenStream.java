package dev.langchain4j.service;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.tool.ToolExecution;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Represents a token stream from the model to which you can subscribe and receive updates
 * when a new partial response (usually a single token) is available,
 *  when the model finishes streaming, or when an error occurs during streaming.
 * It is intended to be used as a return type in AI Service.
 */
public interface TokenStream {

    default void cancel() {
    }

    default TokenStream toolExecutionGuard(ToolExecutionGuard guard) {
        return this;
    }

    /** 为在线模型请求安装统一异步门禁和本轮真实原子回调门。 */
    default TokenStream modelRequestGate(
            ModelRequestGate gate,
            ModelRequestGate.ContinuationGate continuationGate) {
        return this;
    }

    /** 为当前流显式启用一次工具协议自校正。 */
    default TokenStream toolProtocolRecoveryPolicy(
            ToolProtocolRecoveryPolicy policy) {
        return this;
    }

    /** 为 Vue 在线回合安装未完成工具链的一次自动续行策略。 */
    default TokenStream incompleteToolChainRecoveryPolicy(
            IncompleteToolChainRecoveryPolicy policy) {
        return this;
    }

    default TokenStream onControlledTermination(
            Consumer<ToolLoopTerminationProtocol.ControlledTermination> handler) {
        return this;
    }

    /** 从流式适配层触发框架级受控终止，不能降级为普通取消。 */
    default TokenStream requestControlledTermination(
            ToolLoopTerminationProtocol.ControlledTermination termination) {
        return this;
    }

    /**
     * The provided consumer will be invoked every time a new partial response (usually a single token)
     * from a language model is available.
     *
     * @param partialResponseHandler lambda that will be invoked when a model generates a new partial response
     * @return token stream instance used to configure or start stream processing
     */
    TokenStream onPartialResponse(Consumer<String> partialResponseHandler);

    TokenStream onPartialToolExecutionRequest(BiConsumer<Integer, ToolExecutionRequest> toolExecutionRequestHandler);

    TokenStream onCompleteToolExecutionRequest(BiConsumer<Integer, ToolExecutionRequest> completedHandler);

    /**
     * The provided consumer will be invoked if any {@link Content}s are retrieved using {@link RetrievalAugmentor}.
     * <p>
     * The invocation happens before any call is made to the language model.
     *
     * @param contentHandler lambda that consumes all retrieved contents
     * @return token stream instance used to configure or start stream processing
     */
    TokenStream onRetrieved(Consumer<List<Content>> contentHandler);

    /**
     * The provided consumer will be invoked if any tool is executed.
     * <p>
     * The invocation happens after the tool method has finished and before any other tool is executed.
     *
     * @param toolExecuteHandler lambda that consumes {@link ToolExecution}
     * @return token stream instance used to configure or start stream processing
     */
    TokenStream onToolExecuted(Consumer<ToolExecution> toolExecuteHandler);

    /**
     * The provided handler will be invoked when a language model finishes streaming a response.
     *
     * @param completeResponseHandler lambda that will be invoked when language model finishes streaming
     * @return token stream instance used to configure or start stream processing
     */
    TokenStream onCompleteResponse(Consumer<ChatResponse> completeResponseHandler);

    /**
     * The provided consumer will be invoked when an error occurs during streaming.
     *
     * @param errorHandler lambda that will be invoked when an error occurs
     * @return token stream instance used to configure or start stream processing
     */
    TokenStream onError(Consumer<Throwable> errorHandler);

    /**
     * All errors during streaming will be ignored (but will be logged with a WARN log level).
     *
     * @return token stream instance used to configure or start stream processing
     */
    TokenStream ignoreErrors();

    /**
     * Completes the current token stream building and starts processing.
     * <p>
     * Will send a request to LLM and start response streaming.
     */
    void start();
}
