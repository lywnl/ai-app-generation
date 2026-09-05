package com.lyw.appgeneration.ai.skill;

import dev.langchain4j.data.message.SystemMessage;

import java.util.Objects;

/**
 * 已校验的内置 Skill 快照。
 *
 * <p>Skill 是本轮模型请求的临时上下文，不属于用户对话记忆，也不授予任何工具权限。</p>
 */
public record SkillDefinition(
        String name,
        String description,
        String body) {

    public SkillDefinition {
        name = Objects.requireNonNull(name, "Skill 名称不能为空");
        description = Objects.requireNonNull(description, "Skill 描述不能为空");
        body = Objects.requireNonNull(body, "Skill 正文不能为空");
    }

    /** 将完整 Skill 正文转为当前模型请求的临时系统消息。 */
    public SystemMessage asSystemMessage() {
        return SystemMessage.from("当前回合应用 Skill：" + name + "\n"
                + "触发说明：" + description + "\n"
                + "先判断当前请求是否命中触发说明。命中时应用正文；"
                + "未命中时忽略正文，不要扩大修改范围，也不要因此拒绝任务。"
                + "\n\n" + body);
    }

    /** 兼容旧调用方的单消息视图；目录初始化不会调用它。 */
    public java.util.List<dev.langchain4j.data.message.ChatMessage> messages() {
        return java.util.List.of(asSystemMessage());
    }

    public static SkillDefinition of(
            String name, String description, String body) {
        return new SkillDefinition(name, description, body);
    }
}
