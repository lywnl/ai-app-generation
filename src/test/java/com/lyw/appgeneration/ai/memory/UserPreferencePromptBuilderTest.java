package com.lyw.appgeneration.ai.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UserPreferencePromptBuilderTest {

    @Test
    void 构建结果包含判定规则白名单和结构化字段() {
        String prompt = UserPreferencePromptBuilder.build(
                "- 语言偏好:简体中文",
                "turnId=10001\n用户:以后所有应用都用扁平极简风",
                List.of(10001L));
        // 判定标准:强调跨 app 通用、排除 app 特有
        assertTrue(prompt.contains("跨") && prompt.contains("通用"), "应含跨app通用的判定标准");
        assertTrue(prompt.contains("不要抽") || prompt.contains("不抽"), "应明确排除 app 特有需求");
        // 半封闭类别清单
        assertTrue(prompt.contains("语言偏好"));
        assertTrue(prompt.contains("视觉风格"));
        assertTrue(prompt.contains("技术栈倾向"));
        // 结构化输出指令
        assertTrue(prompt.contains("JSON"), "应要求 JSON 数组输出");
        assertTrue(prompt.contains("evidenceType"));
        assertTrue(prompt.contains("turnIds"));
        assertTrue(prompt.contains("EXPLICIT"));
        assertTrue(prompt.contains("IMPLICIT"));
        assertTrue(prompt.contains("10001"), "应明确列出服务端白名单");
        // 已有偏好 + 新对话注入
        assertTrue(prompt.contains("简体中文"), "应含已有偏好用于滚动判断");
        assertTrue(prompt.contains("扁平极简"), "应含新增对话");
    }

    @Test
    void 空已有偏好仍生成有效提示词() {
        String prompt = UserPreferencePromptBuilder.build(
                "", "turnId=9\n用户:做个博客", List.of(9L));
        assertNotNull(prompt);
        assertTrue(prompt.contains("做个博客"));
    }

    @Test
    void 单回合隐式弱证据可成为候选但激活只由服务端判定() {
        String prompt = UserPreferencePromptBuilder.build(
                "(无,首次抽取)",
                "turnId=11\n用户:这次还是使用冷色界面",
                List.of(11L));

        assertTrue(prompt.contains("EXPLICIT 表示用户直接、明确表达"));
        assertTrue(prompt.contains("单个完整回合"));
        assertTrue(prompt.contains("弱证据候选"));
        assertTrue(prompt.contains("不得表述为已确认的长期偏好"));
        assertTrue(prompt.contains("是否激活只由服务端判定"));
        assertTrue(prompt.contains("不同 turnId 累计达到 2"));
        assertTrue(prompt.contains("单次行为"));
        assertTrue(prompt.contains("模型行为"));
        assertTrue(prompt.contains("工具结果"));
        assertFalse(prompt.contains("不得从单个完整回合直接推断"));
    }

    @Test
    void 同内容偏好出现本批新证据时仍要求再次输出候选() {
        String prompt = UserPreferencePromptBuilder.build(
                "- name=视觉风格; status=CANDIDATE; "
                        + "evidenceType=IMPLICIT; content=偏好冷色界面",
                "turnId=21\n用户:这次仍然选择冷色界面",
                List.of(21L));

        assertTrue(prompt.contains("本批新 turn"));
        assertTrue(prompt.contains("仍必须再次输出"));
        assertTrue(prompt.contains("本批 turnIds"));
        assertTrue(prompt.contains("只有本批没有新证据才省略"));
    }
}
