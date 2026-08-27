package com.lyw.appgeneration.ai.skill;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;

import java.util.List;
import java.util.Objects;

/**
 * 已校验的内置 Skill 快照。
 *
 * <p>Skill 是本轮模型请求的临时上下文，不属于用户对话记忆，也不授予任何工具权限。</p>
 */
public record SkillDefinition(
        String name,
        String description,
        String body,
        List<ChatMessage> messages) {

    public SkillDefinition {
        name = Objects.requireNonNull(name, "Skill 名称不能为空");
        description = Objects.requireNonNull(description, "Skill 描述不能为空");
        body = Objects.requireNonNull(body, "Skill 正文不能为空");
        messages = List.copyOf(messages == null ? List.of() : messages);
    }

    /** 将 Skill 快照转为不可变的临时系统消息列表。 */
    public static SkillDefinition of(
            String name, String description, String body) {
        String message = "当前回合可使用 Skill：" + name + "\n"
                + "触发说明：" + description + "\n"
                + "先判断当前请求是否命中触发说明。命中时应用正文；"
                + "未命中时忽略正文，不要扩大修改范围，也不要因此拒绝任务。"
                + "\n\n" + body;
        return new SkillDefinition(
                name, description, body, List.of(SystemMessage.from(message)));
    }
}
