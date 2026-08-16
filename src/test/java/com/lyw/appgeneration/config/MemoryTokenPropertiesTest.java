package com.lyw.appgeneration.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MemoryTokenPropertiesTest {

    @Test
    void defaultValuesMatchApprovedTokenBudgets() {
        MemoryTokenProperties properties = new MemoryTokenProperties();

        assertEquals(12_288, properties.getL0RetainedTokens());
        assertEquals(3_072, properties.getL1MaxSummaryTokens());
        assertEquals(1_024, properties.getL2MaxRecallTokens());
        assertEquals(28_672, properties.getAsyncCompressionThreshold());
        assertEquals(30_720, properties.getBlockingCompressionThreshold());
        assertEquals(32_768, properties.getHardInputLimit());
        assertEquals(8_192, properties.getMaxOutputTokens());
        assertEquals(40_960, properties.getMinimumModelContextWindow());
        assertEquals(Duration.ofSeconds(60), properties.getBlockingTimeout());
        assertEquals(Duration.ofSeconds(30), properties.getL2Debounce());
        assertEquals(1.15D, properties.getEstimationSafetyFactor());
    }

    @Test
    void approvedDefaultsPassStartupValidation() {
        MemoryTokenProperties properties = new MemoryTokenProperties();

        assertDoesNotThrow(properties::afterPropertiesSet);
    }

    @Test
    @DisplayName("启动校验拒绝任何非 3072 的 L1 摘要上限")
    void rejectsNonCanonicalL1SummaryLimit() {
        MemoryTokenProperties properties = new MemoryTokenProperties();
        properties.setL1MaxSummaryTokens(4_096);

        assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
    }

    @Test
    @DisplayName("启动校验拒绝任何非 1024 的 L2 召回上限")
    void 启动拒绝非固定L2召回上限() {
        MemoryTokenProperties properties = new MemoryTokenProperties();
        properties.setL2MaxRecallTokens(1_025);

        assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
    }

    @Test
    @DisplayName("启动校验拒绝任何非 28672 的异步压缩阈值")
    void 启动拒绝非固定异步压缩阈值() {
        MemoryTokenProperties properties = new MemoryTokenProperties();
        properties.setAsyncCompressionThreshold(28_673);

        assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
    }

    @Test
    @DisplayName("启动校验拒绝任何非三十秒的 L2 防抖时间")
    void 启动拒绝非固定L2防抖时间() {
        MemoryTokenProperties properties = new MemoryTokenProperties();
        properties.setL2Debounce(Duration.ofSeconds(31));

        assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
    }

    @Test
    void rejectsThresholdsThatAreNotStrictlyIncreasing() {
        MemoryTokenProperties properties = new MemoryTokenProperties();
        properties.setAsyncCompressionThreshold(30_720);

        assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
    }

    @Test
    void rejectsModelWindowThatCannotHoldInputAndOutputBudgets() {
        MemoryTokenProperties properties = new MemoryTokenProperties();
        properties.setMinimumModelContextWindow(40_959);

        assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
    }

    @Test
    void rejectsNonPositiveLayerBudgetsAndDurations() {
        MemoryTokenProperties properties = new MemoryTokenProperties();
        properties.setL1MaxSummaryTokens(0);
        assertThrows(IllegalStateException.class, properties::afterPropertiesSet);

        properties = new MemoryTokenProperties();
        properties.setBlockingTimeout(Duration.ZERO);
        assertThrows(IllegalStateException.class, properties::afterPropertiesSet);

        properties = new MemoryTokenProperties();
        properties.setL2Debounce(Duration.ofSeconds(-1));
        assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
    }

    @Test
    void rejectsSafetyFactorBelowOne() {
        MemoryTokenProperties properties = new MemoryTokenProperties();
        properties.setEstimationSafetyFactor(0.99D);

        assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
    }
}
