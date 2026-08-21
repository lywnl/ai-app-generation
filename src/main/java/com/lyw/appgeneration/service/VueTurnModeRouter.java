package com.lyw.appgeneration.service;

import com.lyw.appgeneration.ai.VueTurnModeRoutingService;
import com.lyw.appgeneration.ai.VueTurnModeRoutingServiceFactory;
import com.lyw.appgeneration.core.handler.VueTurnMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** Vue 回合模式路由器，集中处理确定性规则和保守降级。 */
@Slf4j
@Service
public final class VueTurnModeRouter {

    private final VueTurnModeRoutingServiceFactory factory;

    public VueTurnModeRouter(VueTurnModeRoutingServiceFactory factory) {
        this.factory = Objects.requireNonNull(factory, "Vue 模式路由工厂不能为空");
    }

    /**
     * @param userMessage 用户消息
     * @param hasHistory 是否已经存在历史消息
     * @return 回合执行模式
     */
    public VueTurnMode route(String userMessage, boolean hasHistory) {
        long startedAt = System.nanoTime();
        if (!hasHistory || containsSelectedElementInfo(userMessage)) {
            String source = hasHistory
                    ? "SELECTED_ELEMENT" : "FIRST_TURN";
            return recordDecision(VueTurnMode.MUTATION_REQUIRED, source,
                    "NONE", startedAt);
        }
        try {
            VueTurnMode mode = factory.create().route(userMessage);
            if (mode == null) {
                return recordDecision(VueTurnMode.MUTATION_REQUIRED,
                        "ROUTING_MODEL", "INVALID_RESULT", startedAt);
            }
            return recordDecision(mode, "ROUTING_MODEL", "NONE", startedAt);
        } catch (RuntimeException exception) {
            return recordDecision(VueTurnMode.MUTATION_REQUIRED,
                    "ROUTING_MODEL", exception.getClass().getSimpleName(),
                    startedAt);
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
