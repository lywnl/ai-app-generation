package com.lyw.appgeneration.ai.memory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

import java.util.Map;
import java.util.Set;

/** 从原检查点实现提取的纯工具配对规则，不生成文字，也不推断工具执行事实。 */
public final class ToolBatchProtocol {
    private ToolBatchProtocol() { }

    public enum Failure {
        NONE, UNSUPPORTED_MESSAGE, ORPHAN_TOOL_CALL, INVALID_TOOL_FACT, UNREGISTERED_TOOL,
        DUPLICATE_TOOL_CALL, ORPHAN_TOOL_RESULT, DUPLICATE_TOOL_RESULT, TOOL_NAME_MISMATCH
    }

    public static Failure observeCalls(AiMessage message, Set<String> registeredTools,
                                       Map<String, ToolExecutionRequest> calls, Set<String> completedIds) {
        if (!message.hasToolExecutionRequests()) return Failure.UNSUPPORTED_MESSAGE;
        if (completedIds.size() != calls.size()) return Failure.ORPHAN_TOOL_CALL;
        for (ToolExecutionRequest request : message.toolExecutionRequests()) {
            if (request == null || request.id() == null || request.id().isBlank()
                    || request.name() == null || request.name().isBlank()) return Failure.INVALID_TOOL_FACT;
            if (registeredTools != null && !registeredTools.contains(request.name())) return Failure.UNREGISTERED_TOOL;
            if (calls.putIfAbsent(request.id(), request) != null) return Failure.DUPLICATE_TOOL_CALL;
        }
        return Failure.NONE;
    }

    public static Failure observeResult(ToolExecutionResultMessage result,
                                        Map<String, ToolExecutionRequest> calls, Set<String> completedIds) {
        ToolExecutionRequest request = calls.get(result.id());
        if (request == null) return Failure.ORPHAN_TOOL_RESULT;
        if (!completedIds.add(result.id())) return Failure.DUPLICATE_TOOL_RESULT;
        if (!request.name().equals(result.toolName())) return Failure.TOOL_NAME_MISMATCH;
        return Failure.NONE;
    }
}
