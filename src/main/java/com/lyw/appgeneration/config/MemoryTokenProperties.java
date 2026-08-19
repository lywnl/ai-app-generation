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

    private static final int REQUIRED_L0_RETAINED_TOKENS = 12_288;
    public static final int L1_MAX_SUMMARY_TOKENS = 3_072;
    private static final int REQUIRED_L2_MAX_RECALL_TOKENS = 1_024;
    private static final int REQUIRED_ASYNC_COMPRESSION_THRESHOLD = 49_152;
    private static final int REQUIRED_BLOCKING_COMPRESSION_THRESHOLD = 57_344;
    private static final int REQUIRED_HARD_INPUT_LIMIT = 65_536;
    private static final int REQUIRED_MAX_OUTPUT_TOKENS = 8_192;
    private static final int REQUIRED_MINIMUM_MODEL_CONTEXT_WINDOW = 73_728;
    private static final Duration REQUIRED_BLOCKING_TIMEOUT =
            Duration.ofSeconds(60);
    private static final Duration REQUIRED_L2_DEBOUNCE =
            Duration.ofSeconds(30);
    private static final double REQUIRED_ESTIMATION_SAFETY_FACTOR = 1.15D;

    private int l0RetainedTokens = REQUIRED_L0_RETAINED_TOKENS;
    private int l1MaxSummaryTokens = L1_MAX_SUMMARY_TOKENS;
    private int l2MaxRecallTokens = REQUIRED_L2_MAX_RECALL_TOKENS;
    private int asyncCompressionThreshold =
            REQUIRED_ASYNC_COMPRESSION_THRESHOLD;
    private int blockingCompressionThreshold =
            REQUIRED_BLOCKING_COMPRESSION_THRESHOLD;
    private int hardInputLimit = REQUIRED_HARD_INPUT_LIMIT;
    private int maxOutputTokens = REQUIRED_MAX_OUTPUT_TOKENS;
    private int minimumModelContextWindow =
            REQUIRED_MINIMUM_MODEL_CONTEXT_WINDOW;
    private Duration blockingTimeout = REQUIRED_BLOCKING_TIMEOUT;
    private Duration l2Debounce = REQUIRED_L2_DEBOUNCE;
    private double estimationSafetyFactor =
            REQUIRED_ESTIMATION_SAFETY_FACTOR;

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
        validateCanonicalValues();
    }

    private void validateCanonicalValues() {
        requireExact(l0RetainedTokens, REQUIRED_L0_RETAINED_TOKENS,
                "L0 保留预算");
        requireExact(l1MaxSummaryTokens, L1_MAX_SUMMARY_TOKENS,
                "L1 摘要预算");
        requireExact(l2MaxRecallTokens, REQUIRED_L2_MAX_RECALL_TOKENS,
                "L2 召回预算");
        requireExact(asyncCompressionThreshold,
                REQUIRED_ASYNC_COMPRESSION_THRESHOLD, "异步压缩阈值");
        requireExact(blockingCompressionThreshold,
                REQUIRED_BLOCKING_COMPRESSION_THRESHOLD, "同步压缩阈值");
        requireExact(hardInputLimit, REQUIRED_HARD_INPUT_LIMIT,
                "输入硬上限");
        requireExact(maxOutputTokens, REQUIRED_MAX_OUTPUT_TOKENS,
                "最大输出预算");
        requireExact(minimumModelContextWindow,
                REQUIRED_MINIMUM_MODEL_CONTEXT_WINDOW, "模型最小上下文窗口");
        requireExact(blockingTimeout, REQUIRED_BLOCKING_TIMEOUT,
                "同步压缩超时");
        requireExact(l2Debounce, REQUIRED_L2_DEBOUNCE,
                "L2 防抖时间");
        if (Double.compare(estimationSafetyFactor,
                REQUIRED_ESTIMATION_SAFETY_FACTOR) != 0) {
            throw new IllegalStateException(
                    "Token 估算安全系数必须严格等于 1.15");
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

    private void requireExact(int actual, int required, String name) {
        if (actual != required) {
            throw new IllegalStateException(
                    name + "必须严格等于 " + required);
        }
    }

    private void requireExact(
            Duration actual, Duration required, String name) {
        if (!required.equals(actual)) {
            throw new IllegalStateException(
                    name + "必须严格等于 " + required);
        }
    }
}
