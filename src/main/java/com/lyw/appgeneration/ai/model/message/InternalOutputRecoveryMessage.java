package com.lyw.appgeneration.ai.model.message;

import dev.langchain4j.service.GenerationStreamSignal;

import java.util.Objects;

/** 已由模型层验证的内部输出恢复阶段消息。 */
public final class InternalOutputRecoveryMessage extends StreamMessage {

    private GenerationStreamSignal.Recovery.Phase phase;
    private long originalFailedGeneration;
    private Long recoveryGeneration;
    private Long failedGeneration;

    public InternalOutputRecoveryMessage() {
        super(StreamMessageTypeEnum.INTERNAL_OUTPUT_RECOVERY.getValue());
    }

    public InternalOutputRecoveryMessage(
            GenerationStreamSignal.Recovery signal) {
        this(Objects.requireNonNull(signal, "恢复信号不能为空").phase(),
                signal.originalFailedGeneration(),
                signal.recoveryGeneration(), signal.failedGeneration());
    }

    public InternalOutputRecoveryMessage(
            GenerationStreamSignal.Recovery.Phase phase,
            long originalFailedGeneration,
            Long recoveryGeneration,
            Long failedGeneration) {
        super(StreamMessageTypeEnum.INTERNAL_OUTPUT_RECOVERY.getValue());
        GenerationStreamSignal.Recovery validated =
                new GenerationStreamSignal.Recovery(
                        phase, originalFailedGeneration,
                        recoveryGeneration, failedGeneration);
        this.phase = validated.phase();
        this.originalFailedGeneration =
                validated.originalFailedGeneration();
        this.recoveryGeneration = validated.recoveryGeneration();
        this.failedGeneration = validated.failedGeneration();
    }

    public GenerationStreamSignal.Recovery.Phase getPhase() {
        return phase;
    }

    public void setPhase(GenerationStreamSignal.Recovery.Phase phase) {
        this.phase = Objects.requireNonNull(phase, "恢复阶段不能为空");
    }

    public long getOriginalFailedGeneration() {
        return originalFailedGeneration;
    }

    public void setOriginalFailedGeneration(long originalFailedGeneration) {
        if (originalFailedGeneration <= 0L) {
            throw new IllegalArgumentException(
                    "原始失败 generation 必须大于 0");
        }
        this.originalFailedGeneration = originalFailedGeneration;
    }

    public Long getRecoveryGeneration() {
        return recoveryGeneration;
    }

    public void setRecoveryGeneration(Long recoveryGeneration) {
        validateOptionalGeneration(recoveryGeneration, "恢复");
        this.recoveryGeneration = recoveryGeneration;
    }

    public Long getFailedGeneration() {
        return failedGeneration;
    }

    public void setFailedGeneration(Long failedGeneration) {
        validateOptionalGeneration(failedGeneration, "失败");
        this.failedGeneration = failedGeneration;
    }

    private static void validateOptionalGeneration(
            Long generation, String name) {
        if (generation != null && generation <= 0L) {
            throw new IllegalArgumentException(
                    name + " generation 必须大于 0");
        }
    }
}
