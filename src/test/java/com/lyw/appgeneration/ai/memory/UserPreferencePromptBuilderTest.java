package com.lyw.appgeneration.ai.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UserPreferencePromptBuilderTest {

    @Test
    void buildContainsJudgementRuleAndCategoriesAndInputs() {
        String prompt = UserPreferencePromptBuilder.build(
                "- 语言偏好:简体中文", "用户:以后所有应用都用扁平极简风\nAI:好的");
        // 判定标准:强调跨 app 通用、排除 app 特有
        assertTrue(prompt.contains("跨") && prompt.contains("通用"), "应含跨app通用的判定标准");
        assertTrue(prompt.contains("不要抽") || prompt.contains("不抽"), "应明确排除 app 特有需求");
        // 半封闭类别清单
        assertTrue(prompt.contains("语言偏好"));
        assertTrue(prompt.contains("视觉风格"));
        assertTrue(prompt.contains("技术栈倾向"));
        // 结构化输出指令
        assertTrue(prompt.contains("JSON"), "应要求 JSON 数组输出");
        // 已有偏好 + 新对话注入
        assertTrue(prompt.contains("简体中文"), "应含已有偏好用于滚动判断");
        assertTrue(prompt.contains("扁平极简"), "应含新增对话");
    }

    @Test
    void buildWithBlankExistingStillValid() {
        String prompt = UserPreferencePromptBuilder.build("", "用户:做个博客");
        assertNotNull(prompt);
        assertTrue(prompt.contains("做个博客"));
    }
}
