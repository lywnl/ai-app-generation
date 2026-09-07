package com.lyw.appgeneration.ai.skill;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WebsiteSkillContractTest {

    @ParameterizedTest
    @CsvSource({
            "vue-personal-blog,Markdown,评论,文章",
            "vue-corporate-website,多语言,客服,案例",
            "vue-online-store,快照,规格,整数",
            "vue-portfolio,焦点,预览,履历"
    })
    void 业务正文包含默认需求关键约束且排除无关触发(
            String name, String first, String second, String third) {
        SkillDefinition skill = new SkillCatalog().load(name);
        for (String exclusion : new String[]{"纯视觉调整", "只读问答", "构建错误修复"}) {
            assertTrue(skill.description().contains(exclusion));
        }
        for (String rule : new String[]{first, second, third, "本项目边界", "局部修改不扩建整站",
                "依赖白名单", "完成自检", "演示", "不调用其他 Skill"}) {
            assertTrue(skill.body().contains(rule), name + " 缺少 " + rule);
        }
    }
}
