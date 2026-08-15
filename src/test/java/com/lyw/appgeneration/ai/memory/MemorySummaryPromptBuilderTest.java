package com.lyw.appgeneration.ai.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MemorySummaryPromptBuilder} 单测:验证 5 段固定模板、旧摘要滚动合并、新增对话注入。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
class MemorySummaryPromptBuilderTest {

    @Test
    void buildContainsFiveSectionsAndOldSummaryAndNewMessages() {
        String prompt = MemorySummaryPromptBuilder.build(
                "# 应用目标与定位\n旧摘要内容",
                "用户:把按钮改成蓝色\nAI:已修改");
        assertTrue(prompt.contains("应用目标与定位"));
        assertTrue(prompt.contains("用户偏好与硬约束"));
        assertTrue(prompt.contains("已否决的方案"));
        assertTrue(prompt.contains("关键设计决策与理由"));
        assertTrue(prompt.contains("当前进度速览"));
        assertTrue(prompt.contains("旧摘要内容"), "应包含旧摘要用于滚动合并");
        assertTrue(prompt.contains("把按钮改成蓝色"), "应包含新增对话");
        assertTrue(prompt.contains("不得超过 3072 Token"));
        assertFalse(prompt.contains("1800"));
        assertFalse(prompt.contains("2400"));
    }

    @Test
    void buildWithBlankOldSummaryStillValid() {
        String prompt = MemorySummaryPromptBuilder.build(
                "", "用户:做个博客");
        assertTrue(prompt.contains("做个博客"));
        assertNotNull(prompt);
    }
}
