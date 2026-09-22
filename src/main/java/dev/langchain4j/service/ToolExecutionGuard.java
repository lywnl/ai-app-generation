package dev.langchain4j.service;

import java.util.Objects;
import java.util.function.Supplier;

/** 在调用方提供的受信词法作用域内执行真实工具。 */
@FunctionalInterface
public interface ToolExecutionGuard {

    GuardedToolExecution execute(
            String toolName,
            Object memoryId,
            Supplier<String> action);

    static ToolExecutionGuard direct() {
        return (toolName, memoryId, action) -> {
            String result = Objects.requireNonNull(action, "action 不能为空").get();
            ToolLoopTerminationProtocol.ToolLoopTermination parsed =
                    ToolLoopTerminationProtocol.parseTrusted(toolName, result);
            ToolLoopTerminationProtocol.ControlledTermination termination = parsed.terminate()
                    ? new ToolLoopTerminationProtocol.ControlledTermination(
                    parsed.reason(), parsed.finalResponse())
                    : null;
            return new GuardedToolExecution(result, termination);
        };
    }

    record GuardedToolExecution(
            String toolResult,
            ToolLoopTerminationProtocol.ControlledTermination controlledTermination,
            BuildProgressGuard.Observation buildObservation,
            MutationPromotion mutationPromotion) {

        public GuardedToolExecution(String toolResult,
                ToolLoopTerminationProtocol.ControlledTermination controlledTermination,
                BuildProgressGuard.Observation buildObservation) {
            this(toolResult, controlledTermination, buildObservation, null);
        }

        public GuardedToolExecution(String toolResult,
                ToolLoopTerminationProtocol.ControlledTermination controlledTermination) {
            this(toolResult, controlledTermination, null);
        }

        public GuardedToolExecution {
            Objects.requireNonNull(toolResult, "toolResult 不能为空");
        }
    }

    /** 仅内部使用，计划摘要在下一请求准备时读取，同批工具修订不会留下旧摘要。 */
    record MutationPromotion(String turnId, String relativePath, Supplier<String> planSummary) {
        public MutationPromotion {
            Objects.requireNonNull(turnId);
            Objects.requireNonNull(relativePath);
            Objects.requireNonNull(planSummary);
        }
    }
}
