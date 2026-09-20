package dev.langchain4j.service;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/** 当前 Vue 回合的软 Replan 状态；不负责终止 generation。 */
public final class ReplanContext {

    public static final int DEFAULT_MAX_FEEDBACK = 3;
    private static final String FEEDBACK_PREFIX = "【内部计划校正】";

    private final Object monitor = new Object();
    private final int maxFeedback;
    private final Set<String> handledTriggerIds = new LinkedHashSet<>();
    private PlanDeviation pending;
    private int feedbackCount;
    private boolean failOpen;
    private volatile BiFunction<String, String, Optional<PlanDeviation>> detector =
            (toolName, rawResult) -> Optional.empty();
    private volatile Consumer<PlanDeviation> acceptedDeviationHandler =
            deviation -> { };
    private volatile Runnable failOpenHandler = () -> { };

    public ReplanContext() {
        this(DEFAULT_MAX_FEEDBACK);
    }

    public ReplanContext(int maxFeedback) {
        if (maxFeedback < 1) {
            throw new IllegalArgumentException("Replan 反馈上限必须为正数");
        }
        this.maxFeedback = maxFeedback;
    }

    public boolean observe(PlanDeviation deviation) {
        Objects.requireNonNull(deviation, "计划偏差不能为空");
        boolean accepted;
        boolean enteredFailOpen = false;
        synchronized (monitor) {
            if (failOpen || handledTriggerIds.contains(deviation.triggerId())) {
                return false;
            }
            handledTriggerIds.add(deviation.triggerId());
            if (feedbackCount >= maxFeedback) {
                failOpen = true;
                pending = null;
                enteredFailOpen = true;
                accepted = false;
            } else {
                feedbackCount++;
                pending = deviation;
                accepted = true;
            }
        }
        if (enteredFailOpen) {
            failOpenHandler.run();
        } else if (accepted) {
            acceptedDeviationHandler.accept(deviation);
        }
        return accepted;
    }

    public void setDetector(
            BiFunction<String, String, Optional<PlanDeviation>> detector) {
        this.detector = Objects.requireNonNull(detector, "计划偏差检测器不能为空");
    }

    public void setAcceptedDeviationHandler(Consumer<PlanDeviation> handler) {
        this.acceptedDeviationHandler = Objects.requireNonNull(
                handler, "计划偏差处理器不能为空");
    }

    public void setFailOpenHandler(Runnable handler) {
        this.failOpenHandler = Objects.requireNonNull(
                handler, "Replan fail-open 处理器不能为空");
    }

    public void observeToolExecution(String toolName, String rawResult) {
        Optional<PlanDeviation> deviation = detector.apply(toolName, rawResult);
        deviation.ifPresent(this::observe);
    }

    public List<ChatMessage> claimFeedback() {
        synchronized (monitor) {
            if (pending == null) {
                return List.of();
            }
            PlanDeviation claimed = pending;
            pending = null;
            return List.of(SystemMessage.from(
                    FEEDBACK_PREFIX + "\n"
                            + claimed.reason() + "\n"
                            + "证据：" + claimed.evidence() + "\n"
                            + "请先调用 updatePlan，再继续修改文件。"
                            + "不要复述或解释本提示。"));
        }
    }

    public void acknowledgePlanUpdate() {
        synchronized (monitor) {
            pending = null;
            handledTriggerIds.clear();
        }
    }

    /** 从持久计划恢复待修订状态；不增加本回合反馈计数。 */
    public void restorePending(String reason, String evidence) {
        synchronized (monitor) {
            if (!failOpen) {
                pending = new PlanDeviation(
                        "persisted-replan", reason, evidence);
            }
        }
    }

    public boolean replanPending() {
        synchronized (monitor) {
            return pending != null;
        }
    }

    public boolean failOpen() {
        synchronized (monitor) {
            return failOpen;
        }
    }

    public int feedbackCount() {
        synchronized (monitor) {
            return feedbackCount;
        }
    }

    public record PlanDeviation(
            String triggerId,
            String reason,
            String evidence) {

        public PlanDeviation {
            if (triggerId == null || triggerId.isBlank()) {
                throw new IllegalArgumentException("计划偏差触发标识不能为空");
            }
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("计划偏差原因不能为空");
            }
            if (evidence == null || evidence.isBlank()) {
                throw new IllegalArgumentException("计划偏差证据不能为空");
            }
        }
    }
}
