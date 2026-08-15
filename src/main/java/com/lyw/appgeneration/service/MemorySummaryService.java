package com.lyw.appgeneration.service;

import java.time.Duration;

/**
 * L1 滚动摘要服务。
 *
 * <p>上下文门禁按稳定完整回合边界触发提炼；{@code messages()} 拼装时读取当前摘要。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
public interface MemorySummaryService {

    /**
     * 异步触发摘要提炼(对话结束钩子调用)。内部 single-flight + best-effort,立即返回。
     *
     * @param appId 应用 ID
     */
    void triggerSummarizationAsync(Long appId, long summarizeThroughId);

    /**
     * 旧对话结束钩子的过渡兼容入口。Token 门禁接管触发前不再主动摘要近期 L0。
     */
    @Deprecated
    default void triggerSummarizationAsync(Long appId) {
        // 12K 淘汰边界只能由上下文门禁计算，不能在这里猜测。
    }

    /**
     * 在调用方截止时间内同步压缩到指定稳定完整回合。
     */
    MemoryCompressionResult compressNow(
            Long appId, long summarizeThroughId, Duration timeout);

    /**
     * 读取当前摘要(供 {@code LayeredChatMemory.messages()} 拼接)。无则返回空串。
     *
     * @param appId 应用 ID
     * @return 摘要正文,或空串
     */
    String getCurrentSummary(Long appId);

    MemoryCacheInvalidationResult invalidateCache(Long appId);
}
