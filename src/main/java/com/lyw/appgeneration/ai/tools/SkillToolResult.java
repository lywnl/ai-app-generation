package com.lyw.appgeneration.ai.tools;

import java.util.Objects;
import java.util.Set;

/** readSkill 返回给模型的严格结构化协议。 */
public record SkillToolResult(
        String protocol,
        String operation,
        Status status,
        String skillName,
        String message,
        String failureReason,
        String content) {

    public static final String PROTOCOL = "skill-tool/v1";
    private static final Set<String> FAILURE_REASONS = Set.of(
            "INVALID_SKILL_NAME", "TOO_MANY_SKILLS", "RESOURCE_LIMIT_EXCEEDED");

    public SkillToolResult {
        if (!PROTOCOL.equals(protocol) || !"readSkill".equals(operation)) {
            throw new IllegalArgumentException("Skill 工具协议版本或操作不受支持");
        }
        if (skillName == null || skillName.isBlank()) {
            throw new IllegalArgumentException("Skill 名称不能为空");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Skill 工具状态说明不能为空");
        }
        Objects.requireNonNull(status, "Skill 工具状态不能为空");
        if (status != Status.APPLIED && content != null) {
            throw new IllegalArgumentException("失败读取不能携带正文");
        }
        if (failureReason != null
                && (status != Status.REJECTED || !FAILURE_REASONS.contains(failureReason))) {
            throw new IllegalArgumentException("Skill 工具失败原因不受支持");
        }
    }

    public static SkillToolResult applied(String skillName, String content) {
        return new SkillToolResult(PROTOCOL, "readSkill", Status.APPLIED,
                skillName, "Skill 正文已加载", null, content);
    }

    public static SkillToolResult notFound(String skillName) {
        return new SkillToolResult(PROTOCOL, "readSkill", Status.NOT_FOUND,
                skillName, "Skill 不存在", null, null);
    }

    public static SkillToolResult rejected(String skillName, String reason) {
        return new SkillToolResult(PROTOCOL, "readSkill", Status.REJECTED,
                skillName, "Skill 读取已拒绝", reason, null);
    }

    public static SkillToolResult failed(String skillName) {
        return new SkillToolResult(PROTOCOL, "readSkill", Status.FAILED,
                skillName, "Skill 读取失败", null, null);
    }

    public enum Status {
        APPLIED,
        NOT_FOUND,
        REJECTED,
        FAILED
    }
}
