package com.lyw.appgeneration.ai.skill;

import dev.langchain4j.data.message.SystemMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillLoaderTest {

    @Test
    void 能读取内置Vue前端设计Skill并保留项目边界() {
        SkillDefinition definition = new SkillLoader()
                .loadFromClasspath("skills/vue-frontend-design/SKILL.md");

        assertEquals("vue-frontend-design", definition.name());
        assertTrue(definition.description().contains("Vue 3"));
        assertTrue(definition.body().contains("本项目边界"));
        assertTrue(definition.body().contains("不增加依赖"));
        assertFalse(definition.body().contains("writeFile"));
    }

    @Test
    void Skill快照内容和消息列表不可变() {
        SkillDefinition definition = new SkillLoader()
                .loadFromClasspath("skills/vue-frontend-design/SKILL.md");

        assertThrows(UnsupportedOperationException.class,
                () -> definition.messages().add(null));
        assertEquals(1, definition.messages().size());
        assertTrue(((SystemMessage) definition.messages().get(0)).text().contains(
                "Vue 前端设计"));
        assertTrue(((SystemMessage) definition.messages().get(0)).text().contains(
                "先判断当前请求是否命中触发说明"));
    }

    @Test
    void 缺少合法frontmatter时拒绝加载() {
        SkillLoader loader = new SkillLoader();

        assertThrows(IllegalArgumentException.class,
                () -> loader.parse("# 没有 frontmatter\n"));
        assertThrows(IllegalArgumentException.class,
                () -> loader.parse("---\nname: bad_name\ndescription: x\n---\n正文"));
    }
}
