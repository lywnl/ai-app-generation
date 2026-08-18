package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.ai.tools.VueToolExecutionFact;
import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 仅根据后端观察到的工具事实与可信终态生成 Vue 记忆投影。 */
public final class VueTurnMemoryProjection {

    public static final String PROTOCOL_ERROR_PROJECTION =
            "本轮发生工具协议异常，未完成真实工具执行或构建。"
                    + "不得复用本轮生成内容，后续操作以当前工程文件为准。";

    private VueTurnMemoryProjection() {
    }

    public static String project(
            List<VueToolExecutionFact> facts,
            VueTurnOutcome.TurnOutcomeType outcome) {
        if (outcome == VueTurnOutcome.TurnOutcomeType.PROTOCOL_ERROR) {
            return PROTOCOL_ERROR_PROJECTION;
        }
        List<VueToolExecutionFact> trustedFacts = facts == null ? List.of() : facts;
        Set<String> toolNames = new LinkedHashSet<>();
        Set<String> changedPaths = new LinkedHashSet<>();
        int buildAttempts = 0;
        for (VueToolExecutionFact fact : trustedFacts) {
            if (fact == null) {
                continue;
            }
            toolNames.add(fact.toolName());
            if (fact.changedRelativePath() != null) {
                changedPaths.add(requireSingleLinePath(
                        fact.changedRelativePath()));
            }
            if (fact.buildAttempt() != null) {
                buildAttempts = Math.max(buildAttempts, fact.buildAttempt());
            }
        }
        return "Vue 项目回合结果：" + outcomeText(outcome)
                + "\n实际执行工具：" + joinOrNone(toolNames)
                + "\n实际变更文件：" + joinOrNone(changedPaths)
                + "\n真实构建次数：" + buildAttempts;
    }

    public static ChatMemoryOutcome memoryOutcome(
            VueTurnOutcome.TurnOutcomeType outcome) {
        return switch (outcome) {
            case SUCCEEDED -> ChatMemoryOutcome.SUCCEEDED;
            case FAILED -> ChatMemoryOutcome.FAILED;
            case CANCELLED -> ChatMemoryOutcome.CANCELLED;
            case TIMED_OUT -> ChatMemoryOutcome.TIMED_OUT;
            case SYSTEM_ERROR -> ChatMemoryOutcome.SYSTEM_ERROR;
            case PROTOCOL_ERROR -> ChatMemoryOutcome.PROTOCOL_ERROR;
        };
    }

    private static String outcomeText(VueTurnOutcome.TurnOutcomeType outcome) {
        return switch (outcome) {
            case SUCCEEDED -> "成功";
            case FAILED -> "失败";
            case CANCELLED -> "已取消";
            case TIMED_OUT -> "超时";
            case SYSTEM_ERROR -> "系统错误";
            case PROTOCOL_ERROR -> throw new IllegalStateException("协议错误使用固定投影");
        };
    }

    private static String joinOrNone(Set<String> values) {
        return values.isEmpty() ? "无" : String.join("、", values);
    }

    private static String requireSingleLinePath(String path) {
        boolean unsafe = path.isBlank() || ".".equals(path)
                || path.indexOf('\\') >= 0 || path.contains("//")
                || path.endsWith("/")
                || path.codePoints().anyMatch(codePoint ->
                codePoint <= 0x1F || codePoint >= 0x7F && codePoint <= 0x9F
                        || codePoint == 0x2028 || codePoint == 0x2029);
        if (unsafe) {
            throw new IllegalArgumentException("变更文件路径不满足单行规范");
        }
        return path;
    }
}
