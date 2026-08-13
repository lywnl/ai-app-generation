package com.lyw.appgeneration.service;

/**
 * L1 滚动摘要服务。
 *
 * <p>对话结束钩子异步触发提炼;{@code messages()} 拼装时读取当前摘要。
 * 全程 best-effort——绝不阻塞或拖慢主对话流。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
public interface MemorySummaryService {

    /**
     * 异步触发摘要提炼(对话结束钩子调用)。内部 single-flight + best-effort,立即返回。
     *
     * @param appId 应用 ID
     */
    void triggerSummarizationAsync(Long appId);

    /**
     * 读取当前摘要(供 {@code LayeredChatMemory.messages()} 拼接)。无则返回空串。
     *
     * @param appId 应用 ID
     * @return 摘要正文,或空串
     */
    String getCurrentSummary(Long appId);

    MemoryCacheInvalidationResult invalidateCache(Long appId);
}
