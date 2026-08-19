package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.ai.tools.VueToolExecutionFact;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 未完成工具链到请求级可信检查点的确定性投影结果。 */
public record ToolChainCheckpointResult(
        boolean complete,
        List<ChatMessage> requestMessages,
        Optional<SystemMessage> checkpointMessage,
        List<VueToolExecutionFact> facts,
        FailureReason failureReason
) {

    public ToolChainCheckpointResult {
        requestMessages = List.copyOf(Objects.requireNonNull(
                requestMessages, "检查点请求消息不能为空"));
        checkpointMessage = Objects.requireNonNull(
                checkpointMessage, "检查点消息容器不能为空");
        facts = List.copyOf(Objects.requireNonNull(
                facts, "检查点事实不能为空"));
        failureReason = Objects.requireNonNull(
                failureReason, "检查点失败原因不能为空");
        validateState(complete, requestMessages, checkpointMessage,
                facts, failureReason);
    }

    public static ToolChainCheckpointResult completed(
            UserMessage userMessage,
            SystemMessage checkpointMessage,
            List<VueToolExecutionFact> facts) {
        UserMessage user = Objects.requireNonNull(
                userMessage, "检查点用户要求不能为空");
        SystemMessage message = Objects.requireNonNull(
                checkpointMessage, "检查点消息不能为空");
        return new ToolChainCheckpointResult(
                true, List.of(user, message), Optional.of(message),
                facts, FailureReason.NONE);
    }

    public static ToolChainCheckpointResult failed(FailureReason reason) {
        if (reason == null || reason == FailureReason.NONE) {
            throw new IllegalArgumentException("失败结果必须提供失败原因");
        }
        return new ToolChainCheckpointResult(
                false, List.of(), Optional.empty(), List.of(), reason);
    }

    private static void validateState(
            boolean complete,
            List<ChatMessage> requestMessages,
            Optional<SystemMessage> checkpointMessage,
            List<VueToolExecutionFact> facts,
            FailureReason failureReason) {
        if (!complete) {
            if (!requestMessages.isEmpty() || checkpointMessage.isPresent()
                    || !facts.isEmpty() || failureReason == FailureReason.NONE) {
                throw new IllegalArgumentException("失败检查点不能携带部分投影");
            }
            return;
        }
        if (failureReason != FailureReason.NONE
                || checkpointMessage.isEmpty() || facts.isEmpty()
                || requestMessages.size() != 2
                || !(requestMessages.getFirst() instanceof UserMessage)
                || !requestMessages.getLast().equals(checkpointMessage.get())) {
            throw new IllegalArgumentException("成功检查点字段组合不合法");
        }
    }

    public enum FailureReason {
        NONE,
        EMPTY_TAIL,
        MISSING_USER_REQUEST,
        AMBIGUOUS_USER_BOUNDARY,
        UNSUPPORTED_MESSAGE,
        UNREGISTERED_TOOL,
        DUPLICATE_TOOL_CALL,
        DUPLICATE_TOOL_RESULT,
        ORPHAN_TOOL_CALL,
        ORPHAN_TOOL_RESULT,
        TOOL_NAME_MISMATCH,
        INVALID_TOOL_FACT
    }
}
