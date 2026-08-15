package com.lyw.appgeneration.config;

import lombok.Data;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 分层记忆的统一 Token 预算配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.memory.token")
public class MemoryTokenProperties implements InitializingBean {

    private int l0RetainedTokens = 12_288;
    private int l1MaxSummaryTokens = 3_072;
    private int l2MaxRecallTokens = 1_024;
    private int asyncCompressionThreshold = 28_672;
    private int blockingCompressionThreshold = 30_720;
    private int hardInputLimit = 32_768;
    private int maxOutputTokens = 8_192;
    private int minimumModelContextWindow = 40_960;
    private Duration blockingTimeout = Duration.ofSeconds(60);
    private Duration l2Debounce = Duration.ofSeconds(30);
    private double estimationSafetyFactor = 1.15D;

    @Override
    public void afterPropertiesSet() {
        requirePositive(l0RetainedTokens, "L0 保留预算");
        requirePositive(l1MaxSummaryTokens, "L1 摘要预算");
        requirePositive(l2MaxRecallTokens, "L2 召回预算");
        requirePositive(asyncCompressionThreshold, "异步压缩阈值");
        requirePositive(blockingCompressionThreshold, "同步压缩阈值");
        requirePositive(hardInputLimit, "输入硬上限");
        requirePositive(maxOutputTokens, "最大输出预算");
        requirePositive(minimumModelContextWindow, "模型最小上下文窗口");
        if (!(l0RetainedTokens < asyncCompressionThreshold
                && asyncCompressionThreshold < blockingCompressionThreshold
                && blockingCompressionThreshold < hardInputLimit)) {
            throw new IllegalStateException(
                    "Token 阈值必须满足 L0 < 异步阈值 < 同步阈值 < 输入硬上限");
        }
        if ((long) hardInputLimit + maxOutputTokens
                > minimumModelContextWindow) {
            throw new IllegalStateException(
                    "模型上下文窗口必须容纳输入硬上限与最大输出预算");
        }
        requirePositive(blockingTimeout, "同步压缩超时");
        requirePositive(l2Debounce, "L2 防抖时间");
        if (!Double.isFinite(estimationSafetyFactor)
                || estimationSafetyFactor < 1D) {
            throw new IllegalStateException("Token 估算安全系数不能小于 1");
        }
    }

    private void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalStateException(name + "必须大于 0");
        }
    }

    private void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(name + "必须大于 0");
        }
    }
}
