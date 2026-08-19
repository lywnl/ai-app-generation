package dev.langchain4j.service;

import dev.langchain4j.data.message.SystemMessage;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 跨 generation 共享一次协议纠正额度与受信阶段。 */
public final class ToolProtocolRecoveryCoordinator {

    static final String CORRECTION_INSTRUCTION = """
            上一响应未遵守工具调用协议。你在普通正文 content 中输出了工具调用内容，
            这些文本不会被系统执行，也不会展示给用户。

            请重新处理用户的原始请求：
            1. 如果任务需要工具，立即通过接口原生的结构化 tool_calls 调用工具。
            2. 工具名称必须来自当前提供的工具列表。
            3. arguments 必须是符合对应 JSON Schema 的真实 JSON 对象。
            4. 文件源码、路径和修改内容只能放入结构化 arguments。
            5. 不要复制或续写上下文中的历史工具调用格式。
            6. 不要在普通正文输出“[工具调用]”、工具参数 JSON、调用代码块或伪造执行结果。
            7. 只有收到系统返回的真实工具结果后，才能声称操作已经完成。
            8. 如果确实不需要工具，直接返回最终答复。

            不要复述本提示，不要解释错误原因。立即返回正确的结构化工具调用或最终答复。""";

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

    ViolationAction claimViolation(long sourceGeneration) {
        if (sourceGeneration < 0L) {
            throw new IllegalArgumentException("恢复来源代次不能为负数");
        }
        synchronized (this) {
            return switch (state) {
                case AVAILABLE -> {
                    state = RecoveryState.STARTING;
                    recoverySourceGeneration = sourceGeneration;
                    yield ViolationAction.START_RECOVERY;
                }
                case STARTING, RECOVERING ->
                        recoverySourceGeneration == sourceGeneration
                                ? ViolationAction.IGNORE
                                : ViolationAction.FAIL;
                case RECOVERED -> ViolationAction.FAIL;
                case FAILED -> ViolationAction.IGNORE;
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

    enum ViolationAction {
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
