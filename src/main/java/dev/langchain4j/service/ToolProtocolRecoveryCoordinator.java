package dev.langchain4j.service;

import dev.langchain4j.data.message.SystemMessage;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 跨 generation 共享一次协议纠正额度与受信阶段。 */
public final class ToolProtocolRecoveryCoordinator {

    static final String CORRECTION_INSTRUCTION = """
            上一响应未遵守工具调用协议。你把工具名称和参数写进了普通文本 content，系统不会执行这种文本形式的工具调用。

            请重新处理用户的原始请求：
            1. 如果任务需要工具，必须通过接口原生的结构化 tool_calls 字段调用工具。
            2. 工具名称必须来自当前提供的工具列表。
            3. arguments 必须是符合对应 JSON Schema 的有效 JSON 对象。
            4. 不要在普通文本中输出“[工具调用]”、参数 JSON、工具代码块或伪造的执行结果。
            5. 不要复述本提示，不要解释错误原因。
            6. 如果确实不需要工具，直接返回最终答复。

            立即返回正确的结构化工具调用或最终答复。""";

    private final ToolProtocolRecoveryPolicy policy;
    private final Set<String> registeredToolNames;
    private RecoveryState state = RecoveryState.AVAILABLE;
    private long recoverySourceGeneration = -1L;

    public ToolProtocolRecoveryCoordinator(
            ToolProtocolRecoveryPolicy policy,
            Set<String> registeredToolNames) {
        this.policy = Objects.requireNonNull(policy, "恢复策略不能为空");
        this.registeredToolNames = Set.copyOf(Objects.requireNonNull(
                registeredToolNames, "注册工具集合不能为空"));
    }

    ToolProtocolRecoveryDetector newDetector() {
        return new ToolProtocolRecoveryDetector(
                registeredToolNames);
    }

    List<dev.langchain4j.data.message.ChatMessage> transientMessages() {
        return List.of(SystemMessage.from(CORRECTION_INSTRUCTION));
    }

    DuplicateAction claimDuplicate(long sourceGeneration) {
        if (sourceGeneration < 0L) {
            throw new IllegalArgumentException("恢复来源代次不能为负数");
        }
        synchronized (this) {
            return switch (state) {
                case AVAILABLE -> {
                    state = RecoveryState.STARTING;
                    recoverySourceGeneration = sourceGeneration;
                    yield DuplicateAction.START_RECOVERY;
                }
                case STARTING, RECOVERING ->
                        recoverySourceGeneration == sourceGeneration
                                ? DuplicateAction.IGNORE
                                : DuplicateAction.FAIL;
                case RECOVERED -> DuplicateAction.FAIL;
                case FAILED -> DuplicateAction.IGNORE;
            };
        }
    }

    void releaseRecoveryReservation() {
        synchronized (this) {
            if (state == RecoveryState.STARTING) {
                state = RecoveryState.AVAILABLE;
                recoverySourceGeneration = -1L;
            }
        }
    }

    void recoveryStarted() {
        boolean publish;
        synchronized (this) {
            publish = state == RecoveryState.STARTING;
            if (publish) {
                state = RecoveryState.RECOVERING;
            }
        }
        if (publish) {
            policy.publish(ToolProtocolRecoveryPolicy.Phase.STARTED);
        }
    }

    void recovered() {
        boolean publish;
        synchronized (this) {
            publish = state == RecoveryState.RECOVERING;
            if (publish) {
                state = RecoveryState.RECOVERED;
            }
        }
        if (publish) {
            policy.publish(ToolProtocolRecoveryPolicy.Phase.RECOVERED);
        }
    }

    void failIfRecovering() {
        boolean publish;
        synchronized (this) {
            publish = state == RecoveryState.STARTING
                    || state == RecoveryState.RECOVERING;
            if (publish) {
                state = RecoveryState.FAILED;
            }
        }
        if (publish) {
            policy.publish(ToolProtocolRecoveryPolicy.Phase.FAILED);
        }
    }

    void failForProtocolViolation() {
        boolean publish;
        synchronized (this) {
            publish = state == RecoveryState.STARTING
                    || state == RecoveryState.RECOVERING
                    || state == RecoveryState.RECOVERED;
            if (publish) {
                state = RecoveryState.FAILED;
            }
        }
        if (publish) {
            policy.publish(ToolProtocolRecoveryPolicy.Phase.FAILED);
        }
    }

    enum DuplicateAction {
        START_RECOVERY,
        FAIL,
        IGNORE
    }

    private enum RecoveryState {
        AVAILABLE,
        STARTING,
        RECOVERING,
        RECOVERED,
        FAILED
    }
}
