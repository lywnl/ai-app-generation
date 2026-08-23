package dev.langchain4j.service;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecution;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** 模型层向受信转录层发布的 generation 信号。 */
public sealed interface GenerationStreamSignal permits GenerationStreamSignal.AiText,
        GenerationStreamSignal.PartialToolRequest,
        GenerationStreamSignal.CompleteToolRequest,
        GenerationStreamSignal.ToolExecuted,
        GenerationStreamSignal.Rollback,
        GenerationStreamSignal.Recovery {

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

    record Rollback(
            long failedGeneration,
            int codePoints,
            Set<String> provisionalToolRequestIds) implements GenerationStreamSignal {

        public Rollback {
            validateGeneration(failedGeneration, "回滚");
            if (codePoints < 0) {
                throw new IllegalArgumentException("回滚正文码点数不能为负数");
            }
            provisionalToolRequestIds = copyToolRequestIds(provisionalToolRequestIds);
        }
    }

    record Recovery(
            Phase phase,
            long originalFailedGeneration,
            Long recoveryGeneration,
            Long failedGeneration) implements GenerationStreamSignal {

        public Recovery {
            phase = Objects.requireNonNull(phase, "恢复阶段不能为空");
            validateGeneration(originalFailedGeneration, "原始失败");
            validateOptionalGeneration(recoveryGeneration, "恢复");
            validateOptionalGeneration(failedGeneration, "失败");
            validatePhase(phase, originalFailedGeneration, recoveryGeneration, failedGeneration);
        }

        public enum Phase {
            STARTED,
            RECOVERED,
            FAILED
        }
    }

    private static void validateGeneration(long generation, String name) {
        if (generation <= 0L) {
            throw new IllegalArgumentException(name + " generation 必须为正数");
        }
    }

    private static void validateOptionalGeneration(Long generation, String name) {
        if (generation != null) {
            validateGeneration(generation, name);
        }
    }

    private static void validateToolIndex(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("工具索引不能为负数");
        }
    }

    private static Set<String> copyToolRequestIds(Set<String> requestIds) {
        Objects.requireNonNull(requestIds, "临时工具请求 ID 集合不能为空");
        Set<String> copied = new LinkedHashSet<>(requestIds);
        if (copied.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("临时工具请求 ID 不能为空白");
        }
        return Set.copyOf(copied);
    }

    private static void validatePhase(
            Recovery.Phase phase,
            long originalFailedGeneration,
            Long recoveryGeneration,
            Long failedGeneration) {
        if (phase == Recovery.Phase.STARTED || phase == Recovery.Phase.RECOVERED) {
            if (recoveryGeneration == null
                    || recoveryGeneration <= originalFailedGeneration
                    || failedGeneration != null) {
                throw new IllegalArgumentException(phase + " 必须有恢复 generation 且没有失败 generation");
            }
            return;
        }
        if (recoveryGeneration == null) {
            if (!Long.valueOf(originalFailedGeneration).equals(failedGeneration)) {
                throw new IllegalArgumentException("启动前 FAILED 必须指向原始失败 generation");
            }
            return;
        }
        if (recoveryGeneration <= originalFailedGeneration
                || failedGeneration == null
                || failedGeneration < recoveryGeneration) {
            throw new IllegalArgumentException("启动后 FAILED 必须有失败 generation");
        }
    }
}
