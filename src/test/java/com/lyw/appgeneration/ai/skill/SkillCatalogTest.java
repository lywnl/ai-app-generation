package com.lyw.appgeneration.ai.skill;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillCatalogTest {

    @Test
    void 启动只打开元数据且正文只在首次读取时打开一次() {
        Map<String, Integer> opens = new HashMap<>();
        SkillLoader loader = new SkillLoader(new ClassLoader() {
            @Override
            public InputStream getResourceAsStream(String name) {
                opens.merge(name, 1, Integer::sum);
                return SkillCatalogTest.class.getClassLoader().getResourceAsStream(name);
            }
        });
        SkillCatalog catalog = new SkillCatalog(loader);
        assertEquals(5, opens.size());
        assertTrue(opens.values().stream().allMatch(count -> count == 1));
        catalog.metadataMessage();
        catalog.load("vue-personal-blog");
        catalog.load("vue-personal-blog");
        assertEquals(2, opens.get("skills/vue-personal-blog/SKILL.md"));
        assertEquals(1, opens.get("skills/vue-online-store/SKILL.md"));
    }

    @Test
    void 重复注册和不存在资源拒绝启动并定位路径() {
        String path = "skills/vue-personal-blog/SKILL.md";
        var duplicate = assertThrows(IllegalArgumentException.class,
                () -> new SkillCatalog(new SkillLoader(), List.of(path, path)));
        assertTrue(duplicate.getMessage().contains(path));
        assertTrue(duplicate.getMessage().contains("重复"));
        var missing = assertThrows(IllegalArgumentException.class,
                () -> new SkillCatalog(new SkillLoader(), List.of("skills/missing/SKILL.md")));
        assertTrue(missing.getMessage().contains("skills/missing/SKILL.md"));
    }

    @Test
    void 五个Skill按稳定顺序注册且正文按需缓存() {
        SkillCatalog catalog = new SkillCatalog();
        assertEquals(List.of("vue-frontend-design", "vue-personal-blog",
                        "vue-corporate-website", "vue-online-store", "vue-portfolio"),
                catalog.metadata().stream().map(SkillMetadata::name).toList());
        for (SkillMetadata metadata : catalog.metadata()) {
            SkillDefinition first = catalog.load(metadata.name());
            assertSame(first, catalog.load(metadata.name()));
            assertFalse(catalog.metadataMessage().text().contains(first.body()));
        }
        assertFalse(catalog.metadataMessage().text().contains("（Vue 前端设计）"));
    }

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
