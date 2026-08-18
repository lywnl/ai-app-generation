package dev.langchain4j.service;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** 在每次真实模型请求前异步准备并复检最新活动上下文。 */
@FunctionalInterface
public interface ModelRequestGate {

    CompletionStage<Decision> prepare(Request request);

    /**
     * 在门禁实现拥有的受控执行上下文中派发准备结果。
     * 默认实现失败关闭，避免已完成 Future 把续调用内联回模型 SDK 回调线程。
     * 在线门禁必须覆盖此方法，并把完成回调提交到自身受管执行器。
     */
    default CompletionStage<DispatchStatus> onPrepared(
            CompletionStage<Decision> preparation,
            BiConsumer<Decision, Throwable> completion) {
        Objects.requireNonNull(preparation, "门禁准备结果不能为空");
        Objects.requireNonNull(completion, "门禁完成回调不能为空");
        return CompletableFuture.completedFuture(DispatchStatus.REJECTED);
    }

    @FunctionalInterface
    interface ContinuationGate {

        boolean tryRun(Runnable action);
    }

    record Request(
            Object memoryId,
            Supplier<ChatMemory> latestMemory,
            List<ToolSpecification> toolSpecifications,
            ContinuationGate continuationGate,
            List<ChatMessage> transientMessages) {

        public Request {
            memoryId = Objects.requireNonNull(memoryId, "记忆 ID 不能为空");
            latestMemory = Objects.requireNonNull(
                    latestMemory, "最新活动记忆读取器不能为空");
            toolSpecifications = List.copyOf(
                    toolSpecifications == null ? List.of() : toolSpecifications);
            continuationGate = Objects.requireNonNull(
                    continuationGate, "回合原子门不能为空");
            transientMessages = List.copyOf(
                    transientMessages == null ? List.of() : transientMessages);
        }
    }

    record Decision(
            Status status,
            List<ChatMessage> messages,
            int estimatedInputTokens,
            String safeMessage) {

        public Decision {
            status = Objects.requireNonNull(status, "门禁状态不能为空");
            messages = List.copyOf(messages == null ? List.of() : messages);
            if (estimatedInputTokens < 0) {
                throw new IllegalArgumentException("预估输入 Token 不能为负数");
            }
            safeMessage = safeMessage == null ? "" : safeMessage;
        }
    }

    enum Status {
        ALLOWED,
        CANCELLED,
        COMPRESSION_FAILED,
        HARD_LIMIT_REJECTED
    }

    /** 门禁完成回调是否成功提交到实现方的受控执行上下文。 */
    enum DispatchStatus {
        DISPATCHED,
        REJECTED
    }
}
