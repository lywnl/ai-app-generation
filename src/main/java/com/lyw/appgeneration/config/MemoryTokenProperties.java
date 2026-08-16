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

    public static final int L1_MAX_SUMMARY_TOKENS = 3_072;
    private static final int REQUIRED_L2_MAX_RECALL_TOKENS = 1_024;
    private static final int REQUIRED_ASYNC_COMPRESSION_THRESHOLD = 28_672;
    private static final Duration REQUIRED_L2_DEBOUNCE =
            Duration.ofSeconds(30);

    private int l0RetainedTokens = 12_288;
    private int l1MaxSummaryTokens = L1_MAX_SUMMARY_TOKENS;
    private int l2MaxRecallTokens = REQUIRED_L2_MAX_RECALL_TOKENS;
    private int asyncCompressionThreshold =
            REQUIRED_ASYNC_COMPRESSION_THRESHOLD;
    private int blockingCompressionThreshold = 30_720;
    private int hardInputLimit = 32_768;
    private int maxOutputTokens = 8_192;
    private int minimumModelContextWindow = 40_960;
    private Duration blockingTimeout = Duration.ofSeconds(60);
    private Duration l2Debounce = REQUIRED_L2_DEBOUNCE;
    private double estimationSafetyFactor = 1.15D;

    @Override
    public void afterPropertiesSet() {
        requirePositive(l0RetainedTokens, "L0 保留预算");
        if (l1MaxSummaryTokens != L1_MAX_SUMMARY_TOKENS) {
            throw new IllegalStateException(
                    "L1 摘要预算必须严格等于 3072 Token");
        }
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
        if (l2MaxRecallTokens != REQUIRED_L2_MAX_RECALL_TOKENS) {
            throw new IllegalStateException(
                    "L2 召回预算必须严格等于 1024 Token");
        }
        if (asyncCompressionThreshold
                != REQUIRED_ASYNC_COMPRESSION_THRESHOLD) {
            throw new IllegalStateException(
                    "异步压缩阈值必须严格等于 28672 Token");
        }
        if (!REQUIRED_L2_DEBOUNCE.equals(l2Debounce)) {
            throw new IllegalStateException("L2 防抖时间必须严格等于 30 秒");
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
