package com.lyw.appgeneration.ai.plan;

import com.lyw.appgeneration.ai.tools.VueToolExecutionFact;
import dev.langchain4j.service.ReplanContext;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** 根据可信工具事实检测当前计划是否出现可观测偏差。 */
public final class ReplanDetector {

    private static final int REPEATED_FAILURE_THRESHOLD = 3;

    private final AppPlanStateManager planStateManager;
    private final long appId;
    private final String turnId;
    private final Map<String, Integer> failureCounts = new HashMap<>();
    private int observedPlanVersion = -1;

    public ReplanDetector(
            AppPlanStateManager planStateManager,
            long appId,
            String turnId) {
        this.planStateManager = planStateManager;
        this.appId = appId;
        this.turnId = turnId;
    }

    public synchronized Optional<ReplanContext.PlanDeviation> detect(
            String toolName, String rawResult) {
        Optional<VueToolExecutionFact> parsed =
                VueToolExecutionFact.parse(toolName, rawResult);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        VueToolExecutionFact fact = parsed.orElseThrow();
        AppPlan plan = planStateManager.load(appId).orElse(null);
        if (plan == null || !turnId.equals(plan.activeTurnId())
                || isPlanTool(toolName)) {
            return Optional.empty();
        }
        if (observedPlanVersion != plan.version()) {
            failureCounts.clear();
            observedPlanVersion = plan.version();
        }
        if (fact.changedRelativePath() != null
                && plan.files().stream().noneMatch(file ->
                file.path().equals(fact.changedRelativePath()))) {
            String path = fact.changedRelativePath();
            return Optional.of(new ReplanContext.PlanDeviation(
                    "out-of-plan:" + path,
                    "工具修改了当前计划之外的文件：" + path,
                    "计划 version=" + plan.version()));
        }
        if (fact.isRead()
                && fact.status() == VueToolExecutionFact.ExecutionStatus.NOT_FOUND
                && fact.relativePath() != null
                && plan.files().stream().anyMatch(file ->
                file.path().equals(fact.relativePath()))) {
            String path = fact.relativePath();
            return Optional.of(new ReplanContext.PlanDeviation(
                    "missing-planned-file:" + path,
                    "计划中的目标文件不存在：" + path,
                    "计划 version=" + plan.version()));
        }
        if (isMutationTool(toolName)
                && fact.relativePath() != null
                && fact.status() == VueToolExecutionFact.ExecutionStatus.SUCCEEDED) {
            failureCounts.remove(fact.relativePath());
            return Optional.empty();
        }
        if (isMutationTool(toolName)
                && fact.relativePath() != null
                && isMutationFailure(fact.status())) {
            String path = fact.relativePath();
            int count = failureCounts.merge(path, 1, Integer::sum);
            if (count >= REPEATED_FAILURE_THRESHOLD) {
                return Optional.of(new ReplanContext.PlanDeviation(
                        "repeated-failure:" + path,
                        "同一路径连续多次变更失败：" + path,
                        "失败次数=" + count + "，计划 version=" + plan.version()));
            }
        }
        return Optional.empty();
    }

    private boolean isPlanTool(String toolName) {
        return "makePlan".equals(toolName) || "updatePlan".equals(toolName);
    }

    private boolean isMutationTool(String toolName) {
        return "writeFile".equals(toolName)
                || "modifyFile".equals(toolName)
                || "deleteFile".equals(toolName);
    }

    private boolean isMutationFailure(
            VueToolExecutionFact.ExecutionStatus status) {
        return status == VueToolExecutionFact.ExecutionStatus.NOT_FOUND
                || status == VueToolExecutionFact.ExecutionStatus.FAILED
                || status == VueToolExecutionFact.ExecutionStatus.NO_CHANGE;
    }
}
