package dev.langchain4j.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** 单个 TokenStream 的工具协议恢复开关与受信阶段监听契约。 */
public final class ToolProtocolRecoveryPolicy {

    private static final Logger LOG = LoggerFactory.getLogger(
            ToolProtocolRecoveryPolicy.class);

    private final Set<String> registeredToolNames;
    private final Consumer<Phase> phaseListener;

    public ToolProtocolRecoveryPolicy(
            Set<String> registeredToolNames,
            Consumer<Phase> phaseListener) {
        Objects.requireNonNull(registeredToolNames, "注册工具集合不能为空");
        this.registeredToolNames = Set.copyOf(registeredToolNames);
        if (this.registeredToolNames.stream().anyMatch(
                name -> name == null || name.isBlank())) {
            throw new IllegalArgumentException("注册工具名不能为空");
        }
        this.phaseListener = Objects.requireNonNull(
                phaseListener, "恢复阶段监听器不能为空");
    }

    public Set<String> registeredToolNames() {
        return registeredToolNames;
    }

    void publish(Phase phase) {
        try {
            phaseListener.accept(phase);
        } catch (RuntimeException exception) {
            // 观察端不能反向破坏已完成线性化的恢复或终态。
            LOG.warn("Tool protocol recovery phase listener failed: phase={}, type={}",
                    phase, exception.getClass().getSimpleName());
        }
    }

    public enum Phase {
        STARTED,
        RECOVERED,
        FAILED
    }
}
