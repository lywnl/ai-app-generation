package com.lyw.appgeneration.ai.memory;

import cn.hutool.core.util.StrUtil;
import com.lyw.appgeneration.config.MemoryTokenProperties;

import java.util.Objects;

/** L1 摘要内容、Token 上限与持久化游标的一致性契约。 */
public final class MemorySummaryContract {

    private MemorySummaryContract() {
    }

    public static boolean isRecallable(
            String summary, ChatTokenEstimator tokenEstimator) {
        Objects.requireNonNull(tokenEstimator, "Token 估算器不能为空");
        return summary != null
                && (summary.isEmpty()
                || (MemorySummaryFormat.isValid(summary)
                && tokenEstimator.estimateText(summary)
                <= MemoryTokenProperties.L1_MAX_SUMMARY_TOKENS));
    }

    public static boolean isUsablePersistedState(
            String summary,
            long lastSummarizedId,
            ChatTokenEstimator tokenEstimator) {
        String text = StrUtil.nullToEmpty(summary);
        if (lastSummarizedId == 0L) {
            return text.isEmpty();
        }
        return lastSummarizedId > 0L
                && StrUtil.isNotBlank(text)
                && isRecallable(text, tokenEstimator);
    }
}
