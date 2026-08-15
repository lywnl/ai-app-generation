package com.lyw.appgeneration.ai.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummaryCompressionPromptBuilderTest {

    @Test
    void reducerOnlyCompressesExistingSummaryToSingleThreeKLimit() {
        String prompt = SummaryCompressionPromptBuilder.build(
                "# 应用目标与定位\n已有事实");

        assertTrue(prompt.contains("只压缩现有摘要"));
        assertTrue(prompt.contains("禁止引入任何新事实"));
        assertTrue(prompt.contains("不得超过 3072 Token"));
        assertTrue(prompt.contains("已有事实"));
        assertFalse(prompt.contains("1800"));
        assertFalse(prompt.contains("2400"));
    }
}
