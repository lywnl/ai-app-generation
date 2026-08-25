package com.lyw.appgeneration.service;

import com.lyw.appgeneration.ai.VueTurnModeRoutingServiceFactory;
import com.lyw.appgeneration.core.handler.VueTurnMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** Vue 回合模式路由器，仅保留选中元素的确定性规则，其余交给分类模型。 */
@Slf4j
@Service
public final class VueTurnModeRouter {

    private final VueTurnModeRoutingServiceFactory factory;

    public VueTurnModeRouter(VueTurnModeRoutingServiceFactory factory) {
        this.factory = Objects.requireNonNull(factory, "Vue 模式路由工厂不能为空");
    }

    /**
     * @param userMessage 用户消息
     * @param hasHistory 是否已经存在历史消息；仅用于保持调用方接口兼容
     * @return 回合执行模式
     */
    public VueTurnMode route(String userMessage, boolean hasHistory) {
        long startedAt = System.nanoTime();
        if (containsSelectedElementInfo(userMessage)) {
            return recordDecision(VueTurnMode.MUTATION_REQUIRED,
                    "SELECTED_ELEMENT",
                    "NONE", startedAt);
        }
        try {
            VueTurnMode modelMode = factory.create().route(userMessage);
            if (modelMode == null) {
                throw new VueTurnModeRoutingException("分类模型返回空结果");
            }
            return recordDecision(modelMode, "ROUTING_MODEL", "NONE", startedAt);
        } catch (RuntimeException exception) {
            if (exception instanceof VueTurnModeRoutingException) {
                log.warn("Vue 回合模式分类失败，source=ROUTING_MODEL,"
                                + "exceptionType={}",
                        exception.getClass().getSimpleName());
                throw exception;
            }
            log.warn("Vue 回合模式分类失败，source=ROUTING_MODEL,"
                            + "exceptionType={}",
                    exception.getClass().getSimpleName());
            throw new VueTurnModeRoutingException("分类模型调用失败", exception);
        }
    }

    private VueTurnMode recordDecision(
            VueTurnMode mode, String source, String exceptionType,
            long startedAt) {
        long elapsedMillis = Math.max(0L,
                (System.nanoTime() - startedAt) / 1_000_000L);
        log.info("Vue 回合模式分类完成，mode={},source={},elapsedMs={},"
                        + "exceptionType={}",
                mode, source, elapsedMillis, exceptionType);
        return mode;
    }

    private boolean containsSelectedElementInfo(String userMessage) {
        return userMessage != null && userMessage.contains("选中元素信息：");
    }
}
