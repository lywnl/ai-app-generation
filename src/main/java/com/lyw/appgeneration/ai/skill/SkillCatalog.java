package com.lyw.appgeneration.ai.skill;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/** 项目内置 Skill 目录；首版只开放 Vue 前端设计 Skill。 */
public final class SkillCatalog {

    private static final String VUE_FRONTEND_DESIGN_PATH =
            "skills/vue-frontend-design/SKILL.md";

    private final SkillDefinition vueFrontendDesign;

    public SkillCatalog() {
        vueFrontendDesign = new SkillLoader()
                .loadFromClasspath(VUE_FRONTEND_DESIGN_PATH);
    }

    public SkillDefinition vueFrontendDesign() {
        return vueFrontendDesign;
    }

    public List<ChatMessage> vueFrontendDesignMessages() {
        return vueFrontendDesign.messages();
    }
}
