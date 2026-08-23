package dev.langchain4j.service;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.function.Consumer;

/** 单个 TokenStream 的内部输出恢复额度和跨 generation 终态协调器。 */
public final class InternalOutputRecoveryCoordinator {

    private static final String CORRECTION_INSTRUCTION_TEMPLATE = """
            上一响应复述了服务端内部状态标记。该内容不能作为用户答案，
            也不能展示或保存。

            请基于当前已完成的工具调用与结果继续处理本回合，不要重复已经成功执行的操作：
            1. 修改请求必须使用原生结构化工具调用完成剩余真实操作；
            2. 查询请求必须基于真实工程事实返回面向用户的答案；
            3. 当前回合若仍有构建义务，必须继续完成真实构建；
            4. 不得复述任何 %s 内部标记；
            5. 不要解释本次恢复，也不要复述本提示。
            """;

    private final InternalOutputRecoveryPolicy policy;
    private final Consumer<GenerationStreamSignal> signalListener;
    private State state = State.AVAILABLE;
    private long originalFailedGeneration = -1L;
    private long recoveryGeneration = -1L;
    private final Queue<GenerationStreamSignal> pendingSignals =
            new ArrayDeque<>();
    private boolean publishingSignals;

    public InternalOutputRecoveryCoordinator(
            InternalOutputRecoveryPolicy policy,
            Consumer<GenerationStreamSignal> signalListener) {
        this.policy = Objects.requireNonNull(policy, "内部输出恢复策略不能为空");
        this.signalListener = Objects.requireNonNull(signalListener, "内部输出信号监听器不能为空");
    }

    public List<ChatMessage> transientMessages() {
        return List.of(SystemMessage.from(CORRECTION_INSTRUCTION_TEMPLATE.formatted(
                policy.reservedPrefix() + "*")));
    }

    public ViolationAction claimViolation(long failedGeneration) {
        validateGeneration(failedGeneration);
        synchronized (this) {
            if (state == State.CLOSED || state == State.FAILED) {
                return ViolationAction.IGNORE;
            }
            if (policy.mode() == InternalOutputRecoveryPolicy.Mode.FAIL_FAST) {
                return ViolationAction.FAIL;
            }
            return switch (state) {
                case AVAILABLE -> {
                    state = State.STARTING;
                    originalFailedGeneration = failedGeneration;
                    yield ViolationAction.START_RECOVERY;
                }
                case STARTING -> originalFailedGeneration == failedGeneration
                        ? ViolationAction.IGNORE : ViolationAction.FAIL;
                case RECOVERING, RECOVERED -> ViolationAction.FAIL;
                case FAILED, CLOSED -> ViolationAction.IGNORE;
            };
        }
    }

    public void releaseRecoveryReservation() {
        synchronized (this) {
            if (state == State.STARTING) {
                state = State.AVAILABLE;
                originalFailedGeneration = -1L;
            }
        }
    }

    public void recoveryStartCommitted(long committedRecoveryGeneration) {
        validateGeneration(committedRecoveryGeneration);
        boolean shouldPublish = false;
        synchronized (this) {
            if (state == State.STARTING) {
                if (committedRecoveryGeneration <= originalFailedGeneration) {
                    throw new IllegalArgumentException("恢复 generation 必须晚于原始失败 generation");
                }
                recoveryGeneration = committedRecoveryGeneration;
                state = State.RECOVERING;
                shouldPublish = enqueueSignal(recoverySignal(
                        GenerationStreamSignal.Recovery.Phase.STARTED, null));
            }
        }
        publishQueuedSignals(shouldPublish);
    }

    public void recovered(long safeGeneration) {
        validateGeneration(safeGeneration);
        boolean shouldPublish = false;
        synchronized (this) {
            if (state == State.RECOVERING && safeGeneration >= recoveryGeneration) {
                state = State.RECOVERED;
                shouldPublish = enqueueSignal(recoverySignal(
                        GenerationStreamSignal.Recovery.Phase.RECOVERED, null));
            }
        }
        publishQueuedSignals(shouldPublish);
    }

    public boolean failBeforeRecoveryStart() {
        boolean shouldPublish = false;
        boolean transitioned = false;
        synchronized (this) {
            if (state == State.STARTING) {
                state = State.FAILED;
                transitioned = true;
                shouldPublish = enqueueSignal(recoverySignal(
                        GenerationStreamSignal.Recovery.Phase.FAILED,
                        originalFailedGeneration));
            }
        }
        publishQueuedSignals(shouldPublish);
        return transitioned;
    }

    public boolean failAfterRecoveryStart() {
        boolean shouldPublish = false;
        boolean transitioned = false;
        synchronized (this) {
            if (state == State.RECOVERING) {
                state = State.FAILED;
                transitioned = true;
                shouldPublish = enqueueSignal(recoverySignal(
                        GenerationStreamSignal.Recovery.Phase.FAILED,
                        recoveryGeneration));
            }
        }
        publishQueuedSignals(shouldPublish);
        return transitioned;
    }

    public synchronized boolean isRecoveryInProgress() {
        return state == State.RECOVERING;
    }

    public void failForRecoveryViolation(long failedGeneration) {
        validateGeneration(failedGeneration);
        boolean shouldPublish = false;
        synchronized (this) {
            if ((state == State.RECOVERING || state == State.RECOVERED)
                    && failedGeneration < recoveryGeneration) {
                throw new IllegalArgumentException("恢复分支失败 generation 不能早于恢复 generation");
            }
            if ((state == State.RECOVERING || state == State.RECOVERED)
                    && failedGeneration >= recoveryGeneration) {
                state = State.FAILED;
                shouldPublish = enqueueSignal(recoverySignal(
                        GenerationStreamSignal.Recovery.Phase.FAILED,
                        failedGeneration));
            }
        }
        publishQueuedSignals(shouldPublish);
    }

    public synchronized void closeSilently() {
        state = State.CLOSED;
    }

    private GenerationStreamSignal.Recovery recoverySignal(
            GenerationStreamSignal.Recovery.Phase phase,
            Long failedGeneration) {
        return new GenerationStreamSignal.Recovery(
                phase,
                originalFailedGeneration,
                recoveryGeneration < 0L ? null : recoveryGeneration,
                failedGeneration);
    }

    /** 必须在状态锁内调用，使信号入队顺序与状态提交顺序一致。 */
    private boolean enqueueSignal(GenerationStreamSignal signal) {
        pendingSignals.add(signal);
        if (publishingSignals) {
            return false;
        }
        publishingSignals = true;
        return true;
    }

    private void publishQueuedSignals(boolean shouldPublish) {
        if (!shouldPublish) {
            return;
        }
        Throwable failure = null;
        while (true) {
            GenerationStreamSignal signal;
            synchronized (this) {
                signal = pendingSignals.poll();
                if (signal == null) {
                    publishingSignals = false;
                    rethrow(failure);
                    return;
                }
            }
            try {
                signalListener.accept(signal);
            } catch (RuntimeException | Error listenerFailure) {
                if (failure == null) {
                    failure = listenerFailure;
                } else if (failure != listenerFailure) {
                    failure.addSuppressed(listenerFailure);
                }
            }
        }
    }

    private void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private void validateGeneration(long generation) {
        if (generation <= 0L) {
            throw new IllegalArgumentException("generation 必须为正数");
        }
    }

    public enum ViolationAction {
        START_RECOVERY,
        FAIL,
        IGNORE
    }

    private enum State {
        AVAILABLE,
        STARTING,
        RECOVERING,
        RECOVERED,
        FAILED,
        CLOSED
    }
}
