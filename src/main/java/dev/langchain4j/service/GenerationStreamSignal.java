package dev.langchain4j.service;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecution;

import java.util.Objects;

/** 模型层向受信转录层发布的 generation 信号。 */
public sealed interface GenerationStreamSignal permits GenerationStreamSignal.AiText,
        GenerationStreamSignal.PartialToolRequest,
        GenerationStreamSignal.CompleteToolRequest,
        GenerationStreamSignal.ToolExecuted {

    record AiText(long generation, String text) implements GenerationStreamSignal {

        public AiText {
            validateGeneration(generation, "正文");
            text = Objects.requireNonNull(text, "正文不能为空");
        }
    }

    record PartialToolRequest(long generation, int index, ToolExecutionRequest request)
            implements GenerationStreamSignal {

        public PartialToolRequest {
            validateGeneration(generation, "局部工具请求");
            validateToolIndex(index);
            request = Objects.requireNonNull(request, "局部工具请求不能为空");
        }
    }

    record CompleteToolRequest(long generation, int index, ToolExecutionRequest request)
            implements GenerationStreamSignal {

        public CompleteToolRequest {
            validateGeneration(generation, "完整工具请求");
            validateToolIndex(index);
            request = Objects.requireNonNull(request, "完整工具请求不能为空");
        }
    }

    record ToolExecuted(long generation, ToolExecution execution)
            implements GenerationStreamSignal {

        public ToolExecuted {
            validateGeneration(generation, "工具执行结果");
            execution = Objects.requireNonNull(execution, "工具执行结果不能为空");
        }
    }

    private static void validateGeneration(long generation, String name) {
        if (generation <= 0L) {
            throw new IllegalArgumentException(name + " generation 必须为正数");
        }
    }

    private static void validateToolIndex(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("工具索引不能为负数");
        }
    }

}
