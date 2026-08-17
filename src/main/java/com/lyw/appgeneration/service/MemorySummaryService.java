package com.lyw.appgeneration.service;

import java.time.Duration;
import java.util.function.BooleanSupplier;

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

    /** 后台 worker 真正启动时仍获准，才读取依赖并执行摘要。 */
    default void triggerSummarizationAsync(
            Long appId,
            long summarizeThroughId,
            BooleanSupplier startPermit) {
        if (startPermit != null && startPermit.getAsBoolean()) {
            triggerSummarizationAsync(appId, summarizeThroughId);
        }
    }

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

    /**
     * 严格读取至少覆盖指定稳定边界的当前摘要。
     *
     * <p>与 best-effort 的 {@link #getCurrentSummary(Long)} 不同，缓存、数据库、
     * 格式或游标任一异常都必须向调用方传播；返回值可安全用于“先验证最终请求、
     * 后裁剪 L0”的提交协议。</p>
     */
    default String getRequiredSummary(
            Long appId, long summarizedThroughId) {
        String summary = getCurrentSummary(appId);
        if (summary == null || summary.isBlank()
                || lastSummarizedId(appId) < summarizedThroughId) {
            throw new IllegalStateException("L1 摘要尚未覆盖指定边界");
        }
        return summary;
    }

    /**
     * 读取 L1 已确认覆盖到的 chat_history.id；尚无摘要时返回 0。
     *
     * <p>该游标决定冷启动与 L0 裁剪边界，读取失败必须向调用方传播。</p>
     */
    long lastSummarizedId(Long appId);

    MemoryCacheInvalidationResult invalidateCache(Long appId);
}
