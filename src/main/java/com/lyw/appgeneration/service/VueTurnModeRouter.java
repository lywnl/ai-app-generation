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

    private final VueReadOnlyIntentPolicy readOnlyIntentPolicy;
    private final VueTurnModeRoutingServiceFactory factory;

    public VueTurnModeRouter(VueReadOnlyIntentPolicy readOnlyIntentPolicy,
            VueTurnModeRoutingServiceFactory factory) {
        this.readOnlyIntentPolicy = Objects.requireNonNull(readOnlyIntentPolicy,
                "Vue 只读资格策略不能为空");
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
            if (!readOnlyIntentPolicy.isExplicitReadOnly(userMessage)) {
                return recordDecision(VueTurnMode.MUTATION_REQUIRED,
                        "LOCAL_POLICY_REJECTED", "NONE", startedAt);
            }
        } catch (RuntimeException exception) {
            return recordDecision(VueTurnMode.MUTATION_REQUIRED,
                    "LOCAL_POLICY_ERROR", exception.getClass().getSimpleName(),
                    startedAt);
        }
        try {
            VueTurnMode modelMode = factory.create().route(userMessage);
            if (modelMode == VueTurnMode.READ_ONLY) {
                return recordDecision(VueTurnMode.READ_ONLY,
                        "ROUTING_MODEL_AND_POLICY", "NONE", startedAt);
            }
            return recordDecision(VueTurnMode.MUTATION_REQUIRED,
                    modelMode == null ? "ROUTING_MODEL_INVALID"
                            : "ROUTING_MODEL_REJECTED",
                    "NONE", startedAt);
        } catch (RuntimeException exception) {
            return recordDecision(VueTurnMode.MUTATION_REQUIRED,
                    "ROUTING_MODEL_ERROR", exception.getClass().getSimpleName(),
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
