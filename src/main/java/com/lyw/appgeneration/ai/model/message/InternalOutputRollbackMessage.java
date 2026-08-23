package com.lyw.appgeneration.ai.model.message;

import dev.langchain4j.service.GenerationStreamSignal;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** 已由模型层验证的内部输出回滚消息。 */
public final class InternalOutputRollbackMessage extends StreamMessage {

    private long failedGeneration;
    private int codePoints;
    private Set<String> provisionalToolRequestIds;

    public InternalOutputRollbackMessage() {
        super(StreamMessageTypeEnum.INTERNAL_OUTPUT_ROLLBACK.getValue());
    }

    public InternalOutputRollbackMessage(
            GenerationStreamSignal.Rollback signal) {
        this(Objects.requireNonNull(signal, "回滚信号不能为空")
                        .failedGeneration(),
                signal.codePoints(), signal.provisionalToolRequestIds());
    }

    public InternalOutputRollbackMessage(
            long failedGeneration,
            int codePoints,
            Set<String> provisionalToolRequestIds) {
        super(StreamMessageTypeEnum.INTERNAL_OUTPUT_ROLLBACK.getValue());
        validateGeneration(failedGeneration);
        if (codePoints < 0) {
            throw new IllegalArgumentException("回滚正文码点数不能为负数");
        }
        Objects.requireNonNull(provisionalToolRequestIds,
                "临时工具请求 ID 集合不能为空");
        Set<String> copied = new LinkedHashSet<>(
                provisionalToolRequestIds);
        if (copied.stream().anyMatch(
                id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException(
                    "临时工具请求 ID 不能为空白");
        }
        this.failedGeneration = failedGeneration;
        this.codePoints = codePoints;
        this.provisionalToolRequestIds = Set.copyOf(copied);
    }

    public long getFailedGeneration() {
        return failedGeneration;
    }

    public void setFailedGeneration(long failedGeneration) {
        validateGeneration(failedGeneration);
        this.failedGeneration = failedGeneration;
    }

    public int getCodePoints() {
        return codePoints;
    }

    public void setCodePoints(int codePoints) {
        if (codePoints < 0) {
            throw new IllegalArgumentException("回滚正文码点数不能为负数");
        }
        this.codePoints = codePoints;
    }

    public Set<String> getProvisionalToolRequestIds() {
        return provisionalToolRequestIds;
    }

    public void setProvisionalToolRequestIds(
            Set<String> provisionalToolRequestIds) {
        Objects.requireNonNull(provisionalToolRequestIds,
                "临时工具请求 ID 集合不能为空");
        if (provisionalToolRequestIds.stream().anyMatch(
                id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException(
                    "临时工具请求 ID 不能为空白");
        }
        this.provisionalToolRequestIds = Set.copyOf(
                provisionalToolRequestIds);
    }

    private static void validateGeneration(long generation) {
        if (generation <= 0L) {
            throw new IllegalArgumentException(
                    "失败 generation 必须大于 0");
        }
    }
}
