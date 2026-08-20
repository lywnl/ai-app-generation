package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.ai.tools.VueToolExecutionFact;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
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
        List<ChatMessage> latestReadBatch,
        FailureReason failureReason
) {

    public ToolChainCheckpointResult {
        requestMessages = List.copyOf(Objects.requireNonNull(
                requestMessages, "检查点请求消息不能为空"));
        checkpointMessage = Objects.requireNonNull(
                checkpointMessage, "检查点消息容器不能为空");
        facts = List.copyOf(Objects.requireNonNull(
                facts, "检查点事实不能为空"));
        latestReadBatch = List.copyOf(Objects.requireNonNull(
                latestReadBatch, "最新读取批次不能为空"));
        failureReason = Objects.requireNonNull(
                failureReason, "检查点失败原因不能为空");
        validateState(complete, requestMessages, checkpointMessage,
                facts, latestReadBatch, failureReason);
    }

    public static ToolChainCheckpointResult completed(
            UserMessage userMessage,
            SystemMessage checkpointMessage,
            List<VueToolExecutionFact> facts) {
        return completed(userMessage, checkpointMessage, facts, List.of());
    }

    public static ToolChainCheckpointResult completed(
            UserMessage userMessage,
            SystemMessage checkpointMessage,
            List<VueToolExecutionFact> facts,
            List<ChatMessage> latestReadBatch) {
        UserMessage user = Objects.requireNonNull(
                userMessage, "检查点用户要求不能为空");
        SystemMessage message = Objects.requireNonNull(
                checkpointMessage, "检查点消息不能为空");
        List<ChatMessage> batch = List.copyOf(Objects.requireNonNull(
                latestReadBatch, "最新读取批次不能为空"));
        List<ChatMessage> requestMessages = new java.util.ArrayList<>(
                2 + batch.size());
        requestMessages.add(user);
        requestMessages.add(message);
        requestMessages.addAll(batch);
        return new ToolChainCheckpointResult(
                true, requestMessages, Optional.of(message),
                facts, batch, FailureReason.NONE);
    }

    public static ToolChainCheckpointResult failed(FailureReason reason) {
        if (reason == null || reason == FailureReason.NONE) {
            throw new IllegalArgumentException("失败结果必须提供失败原因");
        }
        return new ToolChainCheckpointResult(
                false, List.of(), Optional.empty(), List.of(), List.of(), reason);
    }

    public List<ChatMessage> messagesWithoutLatestReadBatch() {
        return latestReadBatch.isEmpty()
                ? requestMessages
                : requestMessages.subList(0,
                requestMessages.size() - latestReadBatch.size());
    }

    public boolean latestReadBatchContainsReadDir() {
        if (latestReadBatch.isEmpty()) {
            return false;
        }
        AiMessage callMessage = (AiMessage) latestReadBatch.getFirst();
        return callMessage.toolExecutionRequests().stream()
                .map(ToolExecutionRequest::name)
                .anyMatch("readDir"::equals);
    }

    private static void validateState(
            boolean complete,
            List<ChatMessage> requestMessages,
            Optional<SystemMessage> checkpointMessage,
            List<VueToolExecutionFact> facts,
            List<ChatMessage> latestReadBatch,
            FailureReason failureReason) {
        if (!complete) {
            if (!requestMessages.isEmpty() || checkpointMessage.isPresent()
                    || !facts.isEmpty() || !latestReadBatch.isEmpty()
                    || failureReason == FailureReason.NONE) {
                throw new IllegalArgumentException("失败检查点不能携带部分投影");
            }
            return;
        }
        if (failureReason != FailureReason.NONE
                || checkpointMessage.isEmpty() || facts.isEmpty()
                || requestMessages.size() < 2
                || !(requestMessages.getFirst() instanceof UserMessage)
                || !requestMessages.get(1).equals(checkpointMessage.get())
                || !hasValidLatestReadBatch(latestReadBatch)
                || !requestMessages.subList(2, requestMessages.size())
                .equals(latestReadBatch)) {
            throw new IllegalArgumentException("成功检查点字段组合不合法");
        }
    }

    private static boolean hasValidLatestReadBatch(
            List<ChatMessage> latestReadBatch) {
        if (latestReadBatch.isEmpty()) {
            return true;
        }
        if (!(latestReadBatch.getFirst() instanceof AiMessage aiMessage)
                || !aiMessage.hasToolExecutionRequests()
                || latestReadBatch.size()
                != aiMessage.toolExecutionRequests().size() + 1) {
            return false;
        }
        java.util.Map<String, String> calls = new java.util.LinkedHashMap<>();
        for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
            if (request == null || !("readFile".equals(request.name())
                    || "readDir".equals(request.name()))
                    || calls.putIfAbsent(request.id(), request.name()) != null) {
                return false;
            }
        }
        for (ChatMessage message : latestReadBatch.subList(
                1, latestReadBatch.size())) {
            if (!(message instanceof ToolExecutionResultMessage result)
                    || !Objects.equals(calls.remove(result.id()), result.toolName())) {
                return false;
            }
        }
        return calls.isEmpty();
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
