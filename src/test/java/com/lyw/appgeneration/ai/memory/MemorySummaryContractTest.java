package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.service.MemorySummarySnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemorySummaryContractTest {

    private ChatTokenEstimator estimator;

    @BeforeEach
    void setUp() {
        estimator = new ConservativeChatTokenEstimator(
                new MemoryTokenProperties());
    }

    @Test
    @DisplayName("空摘要只能与零游标组成可用持久化状态")
    void emptySummaryRequiresZeroCursor() {
        assertTrue(MemorySummaryContract.isUsablePersistedState(
                "", 0L, estimator));
        assertFalse(MemorySummaryContract.isUsablePersistedState(
                "", 1L, estimator));
    }

    @Test
    @DisplayName("合法非空摘要只能与正游标组成可用持久化状态")
    void nonEmptySummaryRequiresPositiveCursor() {
        String summary = validSummary();

        assertFalse(MemorySummaryContract.isUsablePersistedState(
                summary, 0L, estimator));
        assertTrue(MemorySummaryContract.isUsablePersistedState(
                summary, 1L, estimator));
    }

    @Test
    @DisplayName("非法格式和负游标始终不是可用状态")
    void malformedSummaryAndNegativeCursorAreRejected() {
        assertFalse(MemorySummaryContract.isUsablePersistedState(
                "损坏摘要", 1L, estimator));
        assertFalse(MemorySummaryContract.isUsablePersistedState(
                validSummary(), -1L, estimator));
    }

    @Test
    void partialSummaryStillRequiresValidFormatAndTokenBudget() {
        var coverage = new SummaryCoverage(10L, "turn-1", "epoch-1", 40L,
                "a".repeat(64), 2L, null, null, false);
        assertTrue(MemorySummaryContract.isUsablePersistedState(
                new MemorySummarySnapshot(validSummary(), 0L, 1L, 2, coverage), estimator));
        assertFalse(MemorySummaryContract.isUsablePersistedState(
                new MemorySummarySnapshot("损坏摘要", 0L, 1L, 2, coverage), estimator));
        assertFalse(MemorySummaryContract.isUsablePersistedState(
                new MemorySummarySnapshot(validSummary() + "长内容".repeat(5000),
                        0L, 1L, 2, coverage), estimator));
    }

    private String validSummary() {
        return """
                # 应用目标与定位
                待办应用
                # 用户偏好与硬约束
                简体中文
                # 已否决的方案
                无
                # 关键设计决策与理由
                单页布局
                # 当前进度速览
                首页完成
                """;
    }
}
