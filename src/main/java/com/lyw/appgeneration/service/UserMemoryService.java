package com.lyw.appgeneration.service;

/**
 * L2 跨 app 用户长期记忆服务。
 *
 * <p>对话结束钩子异步触发抽取;{@code messages()} 拼装时调 {@link #recallByApp} 召回。
 * 全程 best-effort——绝不阻塞或拖慢主对话流。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
public interface UserMemoryService {

    /**
     * 钩子入口：登记该 app 已稳定持久化到的 AI 消息 ID，并按 userId 防抖抽取跨 app 偏好。
     */
    void triggerPreferenceExtractionAsync(
            Long userId, Long appId, Long stableAiMessageId);

    /** 召回:messages() 调用，按证据优先级拼接不超过 1K Token 的激活偏好。 */
    String recallByApp(Long appId);

    MemoryCacheInvalidationResult invalidateCaches(Long appId, Long userId);
}
