package dev.langchain4j.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Vue 工具链未达到受信构建终态时的一次自动续行策略。 */
public final class IncompleteToolChainRecoveryPolicy {

    private static final Logger LOG = LoggerFactory.getLogger(
            IncompleteToolChainRecoveryPolicy.class);

    private final Supplier<BuildState> buildStateSupplier;
    private final BooleanSupplier requiresBuildSupplier;
    private final Consumer<RecoveryPhase> phaseListener;

    public IncompleteToolChainRecoveryPolicy(
            Supplier<BuildState> buildStateSupplier,
            Consumer<RecoveryPhase> phaseListener) {
        this(() -> true, buildStateSupplier, phaseListener);
    }

    public IncompleteToolChainRecoveryPolicy(
            BooleanSupplier requiresBuildSupplier,
            Supplier<BuildState> buildStateSupplier,
            Consumer<RecoveryPhase> phaseListener) {
        this.requiresBuildSupplier = Objects.requireNonNull(
                requiresBuildSupplier, "构建义务读取器不能为空");
        this.buildStateSupplier = Objects.requireNonNull(
                buildStateSupplier, "构建状态读取器不能为空");
        this.phaseListener = Objects.requireNonNull(
                phaseListener, "续行阶段监听器不能为空");
    }

    BuildState currentBuildState() {
        return Objects.requireNonNull(
                buildStateSupplier.get(), "构建状态读取结果不能为空");
    }

    boolean requiresContinuation() {
        return requiresBuildSupplier.getAsBoolean()
                && currentBuildState().requiresContinuation();
    }

    void publish(RecoveryPhase phase) {
        try {
            phaseListener.accept(phase);
        } catch (RuntimeException exception) {
            LOG.warn("Incomplete tool chain recovery listener failed: phase={}, type={}",
                    phase, exception.getClass().getSimpleName());
        }
    }

    public enum BuildState {
        GENERATING,
        REPAIRING,
        RETRYING,
        FINAL_DIAGNOSIS,
        SUCCEEDED,
        FAILED,
        CANCELLED;

        boolean requiresContinuation() {
            return switch (this) {
                case GENERATING, REPAIRING, RETRYING, FINAL_DIAGNOSIS -> true;
                case SUCCEEDED, FAILED, CANCELLED -> false;
            };
        }
    }

    public enum RecoveryPhase {
        STARTED,
        RECOVERED,
        FAILED
    }
}
