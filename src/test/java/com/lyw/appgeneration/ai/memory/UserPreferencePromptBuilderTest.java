package com.lyw.appgeneration.ai.memory;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class UserPreferencePromptBuilderTest {
    @Test void 构建结果含自由内容操作范围及真实白名单() {
        String prompt = UserPreferencePromptBuilder.build("正式中文、独立日语候选", "turnId=10001\n用户:以后默认日语", List.of(10001L));
        for (String field : List.of("ADD", "REPLACE", "REINFORCE", "RETRACT", "targetId", "attribute", "content", "scope", "USER", "APP", "THREAD", "EXPLICIT", "IMPLICIT", "\"10001\"")) {
            assertTrue(prompt.contains(field), field);
        }
        assertTrue(prompt.contains("正式中文、独立日语候选"));
        assertTrue(prompt.contains("以后默认日语"));
        assertTrue(prompt.contains("语言偏好 / 视觉风格 / 技术栈倾向 / 体验偏好"));
        assertFalse(prompt.contains("ZH_CN"));
        assertFalse(prompt.contains("每类最多"));
    }
    @Test void 用户证据范围和候选隔离明确() {
        String prompt = UserPreferencePromptBuilder.build("", "这次用中文", List.of(11L));
        assertTrue(prompt.contains("用户文本"));
        assertTrue(prompt.contains("本应用"));
        assertTrue(prompt.contains("工具结果"));
        assertTrue(prompt.contains("不能将候选证据加到冲突的正式记录"));
        assertTrue(prompt.contains("不直接复活"));
        assertTrue(prompt.contains("不要自动播放"));
        assertTrue(prompt.contains("不在本页不代表不存在"));
    }
}
