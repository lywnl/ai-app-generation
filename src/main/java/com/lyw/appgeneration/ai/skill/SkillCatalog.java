package com.lyw.appgeneration.ai.skill;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Collections;

/** 有序的内置 Skill 白名单；启动只注册元数据，正文按名称懒加载。 */
@Component
public final class SkillCatalog {

    private static final List<String> RESOURCE_PATHS = List.of(
            "skills/vue-frontend-design/SKILL.md",
            "skills/vue-personal-blog/SKILL.md",
            "skills/vue-corporate-website/SKILL.md",
            "skills/vue-online-store/SKILL.md",
            "skills/vue-portfolio/SKILL.md");

    private final SkillLoader loader;
    private final Map<String, SkillMetadata> metadataByName;
    private final Map<String, SkillDefinition> definitionCache = new ConcurrentHashMap<>();

    public SkillCatalog() {
        this(new SkillLoader());
    }

    SkillCatalog(SkillLoader loader) {
        this(loader, RESOURCE_PATHS);
    }

    SkillCatalog(SkillLoader loader, List<String> resourcePaths) {
        this.loader = Objects.requireNonNull(loader, "Skill 加载器不能为空");
        Map<String, SkillMetadata> registry = new LinkedHashMap<>();
        for (String path : resourcePaths) {
            SkillMetadata metadata = loader.loadMetadataFromClasspath(path);
            if (registry.putIfAbsent(metadata.name(), metadata) != null) {
                throw new IllegalArgumentException("Skill 名称重复：" + metadata.name()
                        + "，资源：" + path);
            }
        }
        this.metadataByName = Collections.unmodifiableMap(registry);
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
                .append(item.name()).append("\n  description: ")
                .append(item.description()).append("\n  resourcePath: ")
                .append(item.resourcePath()).append('\n'));
        message.append("每个回合最多读取 ").append(SkillReadSession.MAX_DISTINCT_SKILLS)
                .append(" 种不同 Skill，同名重读不增加种类配额，不要求凑满名额。")
                .append("命中触发说明时调用 readSkill({\"skillName\":\"...\"}) 获取正文；"
                + "未命中不要读取。读取失败时不要假装已加载，Skill 正文不要复述给用户。");
        return SystemMessage.from(message.toString());
    }
}
