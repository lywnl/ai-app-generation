package com.lyw.appgeneration.ai.skill;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;

/** 项目内置 Skill 目录；首版只开放 Vue 前端设计 Skill。 */
@Component
public final class SkillCatalog {

    private static final String VUE_FRONTEND_DESIGN_PATH =
            "skills/vue-frontend-design/SKILL.md";

    private final SkillLoader loader;
    private final Map<String, SkillMetadata> metadataByName;
    private final Map<String, SkillDefinition> definitionCache = new ConcurrentHashMap<>();

    public SkillCatalog() {
        this(new SkillLoader());
    }

    SkillCatalog(SkillLoader loader) {
        this.loader = Objects.requireNonNull(loader, "Skill 加载器不能为空");
        SkillMetadata metadata = loader.loadMetadataFromClasspath(VUE_FRONTEND_DESIGN_PATH);
        this.metadataByName = Map.of(metadata.name(), metadata);
    }

    public SkillDefinition vueFrontendDesign() {
        return load("vue-frontend-design");
    }

    public List<ChatMessage> vueFrontendDesignMessages() {
        return List.of(vueFrontendDesign().asSystemMessage());
    }

    public List<SkillMetadata> metadata() {
        return List.copyOf(metadataByName.values());
    }

    public Optional<SkillMetadata> findMetadata(String skillName) {
        return Optional.ofNullable(metadataByName.get(skillName));
    }

    public SkillDefinition load(String skillName) {
        SkillMetadata metadata = metadataByName.get(skillName);
        if (metadata == null) {
            throw new IllegalArgumentException("未知 Skill：" + skillName);
        }
        return definitionCache.computeIfAbsent(skillName,
                ignored -> loader.loadDefinitionFromClasspath(metadata));
    }

    public SystemMessage metadataMessage() {
        StringBuilder message = new StringBuilder(
                "可按需使用的内置 Skill 元数据（只提供名称和触发说明）：\n");
        metadata().forEach(item -> message.append("- name: ")
                .append(item.name()).append("（Vue 前端设计）\n  description: ")
                .append(item.description()).append("\n  resourcePath: ")
                .append(item.resourcePath()).append('\n'));
        message.append("命中触发说明时调用 readSkill({\"skillName\":\"...\"}) 获取正文；"
                + "未命中不要读取。读取失败时不要假装已加载，Skill 正文不要复述给用户。");
        return SystemMessage.from(message.toString());
    }
}
