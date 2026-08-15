package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.config.MemoryTokenProperties;

import java.util.Objects;

/** 构建只针对现有 L1 摘要的二次压缩提示词。 */
public final class SummaryCompressionPromptBuilder {

    private static final String TEMPLATE = """
            你是对话摘要压缩助手。只压缩现有摘要，禁止引入任何新事实、推测或代码内容。
            必须保留现有摘要中的五个固定标题及其关键事实，合并重复表述并删除低价值细节。
            应优先保留应用目标、用户偏好、硬约束、已否决方案和关键决策理由。
            最终摘要不得超过 %d Token。
            直接输出压缩后的五段摘要正文，不要解释、不要寒暄。

            【现有摘要】
            %s
            """;

    private SummaryCompressionPromptBuilder() {
    }

    public static String build(String summary) {
        Objects.requireNonNull(summary, "待压缩摘要不能为空");
        return String.format(TEMPLATE,
                MemoryTokenProperties.L1_MAX_SUMMARY_TOKENS,
                summary);
    }
}
