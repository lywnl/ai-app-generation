package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.ai.tools.VueToolExecutionFact;
import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 仅根据后端观察到的工具事实与可信终态生成 Vue 记忆投影。 */
public final class VueTurnMemoryProjection {

    public static final String PROTOCOL_ERROR_PROJECTION =
            "本轮发生工具协议异常。模型普通正文不得作为工程状态依据，"
                    + "已经落盘的文件修改会保留，后续操作以当前工程文件为准。";
    public static final String REPEATED_READ_LOOP_PROJECTION =
            "本轮发生重复读取循环，系统已安全停止。"
                    + "已经落盘的文件修改会保留，"
                    + "后续操作以当前工程文件为准。";

    private VueTurnMemoryProjection() {
    }

    public static String project(
            List<VueToolExecutionFact> facts,
            VueTurnOutcome.TurnOutcomeType outcome) {
        List<VueToolExecutionFact> trustedFacts = facts == null ? List.of() : facts;
        Set<String> toolNames = new LinkedHashSet<>();
        Set<String> changedPaths = new LinkedHashSet<>();
        int buildAttempts = 0;
        String buildStatus = "未达到终态";
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
                buildStatus = buildStatus(fact.status());
            }
        }
        String projection = "服务端工程状态\n回合终态：" + outcomeText(outcome)
                + "\n实际执行工具：" + joinOrNone(toolNames)
                + "\n实际变更文件：" + joinOrNone(changedPaths)
                + "\n构建状态：" + buildStatus
                + "\n构建尝试次数：" + buildAttempts
                + "\n后续操作以当前磁盘文件为准。";
        if (outcome == VueTurnOutcome.TurnOutcomeType.PROTOCOL_ERROR) {
            return PROTOCOL_ERROR_PROJECTION + "\n" + projection;
        }
        return projection;
    }

    public static ChatMemoryOutcome memoryOutcome(
            VueTurnOutcome.TurnOutcomeType outcome) {
        return switch (outcome) {
            case ANSWERED -> ChatMemoryOutcome.ANSWERED;
            case SUCCEEDED -> ChatMemoryOutcome.SUCCEEDED;
            case FAILED -> ChatMemoryOutcome.FAILED;
            case CANCELLED -> ChatMemoryOutcome.CANCELLED;
            case TIMED_OUT -> ChatMemoryOutcome.TIMED_OUT;
            case SYSTEM_ERROR -> ChatMemoryOutcome.SYSTEM_ERROR;
            case PROTOCOL_ERROR -> ChatMemoryOutcome.PROTOCOL_ERROR;
            case INCOMPLETE_TOOL_CHAIN ->
                    ChatMemoryOutcome.INCOMPLETE_TOOL_CHAIN;
        };
    }

    private static String outcomeText(VueTurnOutcome.TurnOutcomeType outcome) {
        return switch (outcome) {
            case ANSWERED -> "已回答";
            case SUCCEEDED -> "成功";
            case FAILED -> "失败";
            case CANCELLED -> "已取消";
            case TIMED_OUT -> "超时";
            case SYSTEM_ERROR -> "系统错误";
            case PROTOCOL_ERROR -> "工具协议异常";
            case INCOMPLETE_TOOL_CHAIN -> "工具链未完成";
        };
    }

    private static String buildStatus(
            VueToolExecutionFact.ExecutionStatus status) {
        return switch (status) {
            case SUCCEEDED -> "成功";
            case FAILED, TIMED_OUT -> "失败";
            case REJECTED -> "被拒绝";
            case CANCELLED -> "已取消";
            case IN_PROGRESS -> "执行中";
            case NO_CHANGE, NOT_FOUND -> "未达到终态";
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
