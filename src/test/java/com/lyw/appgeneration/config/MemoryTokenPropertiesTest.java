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
        assertEquals(49_152, properties.getAsyncCompressionThreshold());
        assertEquals(57_344, properties.getBlockingCompressionThreshold());
        assertEquals(65_536, properties.getHardInputLimit());
        assertEquals(8_192, properties.getMaxOutputTokens());
        assertEquals(73_728, properties.getMinimumModelContextWindow());
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
    @DisplayName("启动校验拒绝任何非 12288 的 L0 保留预算")
    void rejectsNonCanonicalL0RetainedTokens() {
        MemoryTokenProperties properties = new MemoryTokenProperties();
        properties.setL0RetainedTokens(12_289);

        assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
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
    @DisplayName("启动校验拒绝任何非 49152 的异步压缩阈值")
    void 启动拒绝非固定异步压缩阈值() {
        MemoryTokenProperties properties = new MemoryTokenProperties();
        properties.setAsyncCompressionThreshold(49_153);

        assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
    }

    @Test
    @DisplayName("启动校验拒绝任何非 57344 的同步压缩阈值")
    void rejectsNonCanonicalBlockingCompressionThreshold() {
        MemoryTokenProperties properties = new MemoryTokenProperties();
        properties.setBlockingCompressionThreshold(57_345);

        assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
    }

    @Test
    @DisplayName("启动校验拒绝任何非 65536 的输入硬上限")
    void rejectsNonCanonicalHardInputLimit() {
        MemoryTokenProperties properties = new MemoryTokenProperties();
        properties.setHardInputLimit(65_535);

        assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
    }

    @Test
    @DisplayName("启动校验拒绝任何非 8192 的最大输出预算")
    void rejectsNonCanonicalMaxOutputTokens() {
        MemoryTokenProperties properties = new MemoryTokenProperties();
        properties.setMaxOutputTokens(8_191);

        assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
    }

    @Test
    @DisplayName("启动校验拒绝任何非 73728 的模型最小上下文窗口")
    void rejectsNonCanonicalMinimumModelContextWindow() {
        MemoryTokenProperties properties = new MemoryTokenProperties();
        properties.setMinimumModelContextWindow(73_729);

        assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
    }

    @Test
    @DisplayName("启动校验拒绝任何非六十秒的同步压缩超时")
    void rejectsNonCanonicalBlockingTimeout() {
        MemoryTokenProperties properties = new MemoryTokenProperties();
        properties.setBlockingTimeout(Duration.ofSeconds(61));

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
        properties.setMinimumModelContextWindow(73_727);

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

    @Test
    @DisplayName("启动校验拒绝任何非 1.15 的 Token 估算安全系数")
    void rejectsNonCanonicalEstimationSafetyFactor() {
        MemoryTokenProperties properties = new MemoryTokenProperties();
        properties.setEstimationSafetyFactor(1.14D);

        assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
    }
}
