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

    /** 统一入口以来源窗口选择材料，同步等待、草稿和提交共享原绝对截止。 */
    MemoryCompressionResult compressUnified(UnifiedSummaryRequest request,
                                             com.lyw.appgeneration.ai.memory.AdmissionDeadline deadline);

    /** 后台工作同样携带来源回合和截止，不由回合收尾无条件调用。 */
    void triggerUnifiedAsync(UnifiedSummaryRequest request,
                              com.lyw.appgeneration.ai.memory.AdmissionDeadline deadline);

    /** 只根据已提交快照幂等回收 L0；失败保留原文，不触发摘要模型。 */
    com.lyw.appgeneration.ai.memory.DeadlineAwareReplaceResult reclaimUnified(UnifiedSummaryRequest request,
                              com.lyw.appgeneration.ai.memory.AdmissionDeadline deadline);

    /** 每次尝试从新提交的数据库视图取得完整摘要，再读一次 L0；不相容时整对重读。 */
    CompatibleMemorySnapshot readCompatible(UnifiedSummaryRequest request,
                              com.lyw.appgeneration.ai.memory.AdmissionDeadline deadline);

    /**
     * 读取当前摘要(供 {@code LayeredChatMemory.messages()} 拼接)。无则返回空串。
     *
     * @param appId 应用 ID
     * @return 摘要正文,或空串
     */
    String getCurrentSummary(Long appId);

    /** 一次读取同一持久化版本的正文与游标；没有可用摘要时返回空快照，依赖失败必须传播。 */
    MemorySummarySnapshot readSnapshot(Long appId);

    /** 严格读取已覆盖目标边界的快照，调用方必须按返回的实际游标裁剪。 */
    default MemorySummarySnapshot readRequiredSnapshot(Long appId, long summarizedThroughId) {
        if (summarizedThroughId <= 0L) {
            throw new IllegalArgumentException("摘要边界必须为正数");
        }
        MemorySummarySnapshot snapshot = readSnapshot(appId);
        if (snapshot.lastSummarizedId() < summarizedThroughId) {
            throw new IllegalStateException("L1 摘要未覆盖指定边界");
        }
        return snapshot;
    }

    /**
     * 严格读取至少覆盖指定稳定边界的当前摘要。
     *
     * <p>仅供只需要正文的调用方兼容使用。需要裁剪 L0 时必须使用
     * {@link #readRequiredSnapshot(Long, long)} 返回的正文和实际游标，
     * 不能将此正文与另一次读取的游标组合。</p>
     */
    default String getRequiredSummary(
            Long appId, long summarizedThroughId) {
        return readRequiredSnapshot(appId, summarizedThroughId).summary();
    }

    /**
     * 读取 L1 已确认覆盖到的 chat_history.id；尚无摘要时返回 0。
     *
     * <p>只读游标的兼容接口，读取失败必须传播。请求组装应使用完整快照。</p>
     */
    long lastSummarizedId(Long appId);

    MemoryCacheInvalidationResult invalidateCache(Long appId);
}
