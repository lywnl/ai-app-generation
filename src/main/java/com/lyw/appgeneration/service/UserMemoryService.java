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

    /** 钩子入口:异步抽取该 app 新对话中的跨 app 用户偏好。single-flight 按 userId。 */
    void triggerPreferenceExtractionAsync(Long userId, Long appId);

    /** 召回:messages() 调用,内部 appId→userId 反查后取 top-N 偏好拼成文本(无则空串)。 */
    String recallByApp(Long appId);

    MemoryCacheInvalidationResult invalidateCaches(Long appId, Long userId);
}
