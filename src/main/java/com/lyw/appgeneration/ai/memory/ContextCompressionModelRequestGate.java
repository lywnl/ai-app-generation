package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.ai.model.message.ContextCompressionMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.service.ModelRequestGate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;

/** 将同步上下文压缩协调器适配为每次模型请求使用的异步门禁。 */
@Component
public class ContextCompressionModelRequestGate implements ModelRequestGate {

    private static final String CANCELLED_MESSAGE = "当前生成已取消";
    private static final String COMPRESSION_FAILED_MESSAGE =
            "对话上下文整理失败，请稍后重试";
    private static final String HARD_LIMIT_MESSAGE =
            "对话上下文过长，请开启新会话后重试";

    private final ContextCompressionCoordinator coordinator;
    private final ExecutorService gateExecutor;

    public ContextCompressionModelRequestGate(
            ContextCompressionCoordinator coordinator,
            @Qualifier("modelRequestGateExecutor") ExecutorService gateExecutor) {
        this.coordinator = Objects.requireNonNull(
                coordinator, "上下文压缩协调器不能为空");
        this.gateExecutor = Objects.requireNonNull(
                gateExecutor, "模型请求门禁执行器不能为空");
    }

    @Override
    public CompletionStage<Decision> prepare(Request request) {
        Objects.requireNonNull(request, "模型请求门禁参数不能为空");
        CompletableFuture<Decision> result = new CompletableFuture<>();
        try {
            gateExecutor.execute(() -> prepareOnWorker(request, result));
        } catch (RejectedExecutionException exception) {
            result.complete(compressionFailure());
        }
        return result;
    }

    @Override
    public CompletionStage<DispatchStatus> onPrepared(
            CompletionStage<Decision> preparation,
            BiConsumer<Decision, Throwable> completion) {
        Objects.requireNonNull(preparation, "门禁准备结果不能为空");
        Objects.requireNonNull(completion, "门禁完成回调不能为空");
        CompletableFuture<DispatchStatus> dispatch = new CompletableFuture<>();
        preparation.whenComplete((decision, failure) ->
                dispatchCompletion(decision, failure, completion, dispatch));
        return dispatch;
    }

    private void dispatchCompletion(
            Decision decision,
            Throwable failure,
            BiConsumer<Decision, Throwable> completion,
            CompletableFuture<DispatchStatus> dispatch) {
        try {
            gateExecutor.execute(() -> {
                try {
                    completion.accept(decision, failure);
                    dispatch.complete(DispatchStatus.DISPATCHED);
                } catch (Throwable exception) {
                    dispatch.completeExceptionally(exception);
                }
            });
        } catch (RejectedExecutionException exception) {
            dispatch.complete(DispatchStatus.REJECTED);
        }
    }

    private void prepareOnWorker(
            Request request, CompletableFuture<Decision> future) {
        try {
            future.complete(decide(request));
        } catch (RuntimeException exception) {
            future.complete(compressionFailure());
        }
    }

    private Decision decide(Request request) {
        ChatMemory activeMemory = Objects.requireNonNull(
                request.latestMemory().get(), "活动 ChatMemory 不能为空");
        if (!(activeMemory instanceof CompressionAwareChatMemory memory)) {
            return compressionFailure();
        }
        ContextContinuationGate continuationGate =
                ContextContinuationGate.from(request.continuationGate());
        ContextAdmissionResult admission = request.transientMessages().isEmpty()
                ? coordinator.admit(
                memory,
                request.toolSpecifications(),
                transition -> publishStarted(
                        continuationGate, transition),
                continuationGate)
                : coordinator.admit(
                memory,
                request.toolSpecifications(),
                request.transientMessages(),
                transition -> publishStarted(
                        continuationGate, transition),
                continuationGate);
        publishCompleted(continuationGate, admission);
        return map(admission);
    }

    private void publishStarted(
            ContextContinuationGate continuationGate,
            ContextAdmissionResult transition) {
        if (transition.mode() == ContextCompressionMode.BLOCKING_STARTED) {
            continuationGate.publishContextCompression(
                    ContextCompressionMessage.started());
        }
    }

    private void publishCompleted(
            ContextContinuationGate continuationGate,
            ContextAdmissionResult admission) {
        if (admission.mode() != ContextCompressionMode.BLOCKING_COMPLETED) {
            return;
        }
        continuationGate.tryRun(() ->
                continuationGate.publishContextCompression(
                        ContextCompressionMessage.completed()));
    }

    private Decision map(ContextAdmissionResult admission) {
        if (admission.failureReason()
                == ContextAdmissionResult.FailureReason.TURN_TERMINATED) {
            return new Decision(Status.CANCELLED, admission.requestMessages(),
                    admission.finalTokens(), CANCELLED_MESSAGE);
        }
        if (admission.mode() == ContextCompressionMode.HARD_LIMIT_REJECTED) {
            return new Decision(Status.HARD_LIMIT_REJECTED,
                    admission.requestMessages(),
                    admission.finalTokens(), HARD_LIMIT_MESSAGE);
        }
        if (admission.canProceed()) {
            return new Decision(Status.ALLOWED, admission.requestMessages(),
                    admission.finalTokens(), "");
        }
        return new Decision(Status.COMPRESSION_FAILED,
                admission.requestMessages(),
                admission.finalTokens(), COMPRESSION_FAILED_MESSAGE);
    }

    private Decision compressionFailure() {
        return new Decision(Status.COMPRESSION_FAILED, List.of(), 0,
                COMPRESSION_FAILED_MESSAGE);
    }
}
