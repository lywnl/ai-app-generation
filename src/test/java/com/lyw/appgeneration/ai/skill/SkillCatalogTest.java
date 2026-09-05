package com.lyw.appgeneration.ai.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillCatalogTest {

    @Test
    void 初始化只暴露元数据并按名称懒加载正文() {
        SkillCatalog catalog = new SkillCatalog();

        assertTrue(catalog.findMetadata("vue-frontend-design").isPresent());
        assertFalse(catalog.metadataMessage().text().contains("把用户的业务主题"));
        assertTrue(catalog.load("vue-frontend-design").body()
                .contains("把用户的业务主题"));
    }

    @Test
    void 未知名称不能转成任意资源路径() {
        SkillCatalog catalog = new SkillCatalog();

        assertThrows(IllegalArgumentException.class,
                () -> catalog.load("../secret"));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.load("missing-skill"));
    }
}
