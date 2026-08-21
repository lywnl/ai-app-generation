package dev.langchain4j.service;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;

import java.util.List;
import java.util.Objects;

/** 跨 generation 共享一次未完成工具链续行额度。 */
final class IncompleteToolChainRecoveryCoordinator {

    static final String CONTINUATION_INSTRUCTION = """
            当前 Vue 工具回合尚未达到受信构建终态。
            上一响应提前返回了普通总结，但普通总结不能替代真实工具执行和构建结果，
            该响应不会展示给用户，也不会写入记忆。

            请继续处理用户的原始请求：
            1. 需要读写文件时，必须使用接口原生的结构化 tool_calls。
            2. 工具名称和 arguments 必须符合当前工具列表及对应 JSON Schema。
            3. 完成文件操作后，必须单独调用 buildProject 获取真实构建终态。
            4. 不得复述或模仿“Vue 项目回合结果”“实际执行工具”等内部状态格式。
            5. 不要输出工具调用代码块、伪造执行结果或思维过程。

            不要解释本提示，立即返回下一步真实结构化工具调用。""";

    private final IncompleteToolChainRecoveryPolicy policy;
    private RecoveryState state = RecoveryState.AVAILABLE;
    private long recoverySourceGeneration = -1L;

    IncompleteToolChainRecoveryCoordinator(
            IncompleteToolChainRecoveryPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "未完成工具链策略不能为空");
    }

    boolean shouldQuarantineOrdinaryText() {
        return policy.requiresContinuation();
    }

    List<ChatMessage> transientMessages() {
        return List.of(SystemMessage.from(CONTINUATION_INSTRUCTION));
    }

    CompletionAction claimOrdinaryCompletion(long sourceGeneration) {
        if (!policy.requiresContinuation()) {
            return CompletionAction.COMPLETE;
        }
        synchronized (this) {
            return switch (state) {
                case AVAILABLE -> {
                    state = RecoveryState.STARTING;
                    recoverySourceGeneration = sourceGeneration;
                    yield CompletionAction.START_RECOVERY;
                }
                case STARTING, RECOVERING ->
                        recoverySourceGeneration == sourceGeneration
                                ? CompletionAction.IGNORE
                                : CompletionAction.FAIL;
                case RECOVERED -> CompletionAction.FAIL;
                case FAILED -> CompletionAction.IGNORE;
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
        if (move(RecoveryState.STARTING, RecoveryState.RECOVERING)) {
            policy.publish(IncompleteToolChainRecoveryPolicy
                    .RecoveryPhase.STARTED);
        }
    }

    void recovered() {
        if (move(RecoveryState.RECOVERING, RecoveryState.RECOVERED)) {
            policy.publish(IncompleteToolChainRecoveryPolicy
                    .RecoveryPhase.RECOVERED);
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
            policy.publish(IncompleteToolChainRecoveryPolicy
                    .RecoveryPhase.FAILED);
        }
    }

    void failForIncompleteCompletion() {
        boolean publish;
        synchronized (this) {
            publish = state != RecoveryState.FAILED;
            state = RecoveryState.FAILED;
        }
        if (publish) {
            policy.publish(IncompleteToolChainRecoveryPolicy
                    .RecoveryPhase.FAILED);
        }
    }

    private boolean move(RecoveryState expected, RecoveryState next) {
        synchronized (this) {
            if (state != expected) {
                return false;
            }
            state = next;
            return true;
        }
    }

    enum CompletionAction {
        COMPLETE,
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
