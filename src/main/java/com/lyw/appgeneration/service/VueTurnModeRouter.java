package com.lyw.appgeneration.service;

import com.lyw.appgeneration.ai.VueTurnModeRoutingServiceFactory;
import com.lyw.appgeneration.core.handler.VueTurnDecision;
import com.lyw.appgeneration.core.handler.VueTurnMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** 每个用户回合只联合判断一次，选中元素的修改权限由服务端固定。 */
@Slf4j
@Service
public final class VueTurnModeRouter {
    private final VueTurnModeRoutingServiceFactory factory;

    public VueTurnModeRouter(VueTurnModeRoutingServiceFactory factory) {
        this.factory = Objects.requireNonNull(factory, "Vue 模式路由工厂不能为空");
    }

    /** 兼容只需要模式的调用方，历史有无不参与分类。 */
    public VueTurnMode route(String userMessage, boolean hasHistory) {
        return decide(userMessage).mode();
    }

    public VueTurnDecision decide(String userMessage) {
        long startedAt = System.nanoTime();
        boolean selected = userMessage != null && userMessage.contains("选中元素信息：");
        String source = selected ? "SELECTED_ELEMENT" : "ROUTING_MODEL";
        try {
            var parsed = VueTurnDecisionParser.parse(factory.create().route(userMessage), selected);
            recordDecision(parsed.decision(), source, parsed.rawRag(), parsed.rawImages(),
                    parsed.degradationReason().isEmpty() ? "NONE" : parsed.degradationReason(), startedAt);
            return parsed.decision();
        } catch (RuntimeException exception) {
            if (selected) {
                var fallback = new VueTurnDecision(VueTurnMode.MUTATION_REQUIRED, false, false);
                recordDecision(fallback, source, "UNAVAILABLE", "UNAVAILABLE",
                        exception.getClass().getSimpleName(), startedAt);
                return fallback;
            }
            log.warn("Vue 回合联合分类失败,source={},elapsedMs={},exceptionType={}",
                    source, elapsedMillis(startedAt), exception.getClass().getSimpleName());
            if (exception instanceof VueTurnModeRoutingException) throw exception;
            throw new VueTurnModeRoutingException("分类模型调用失败", exception);
        }
    }

    private void recordDecision(VueTurnDecision decision, String source, String rawRag,
                                String rawImages, String degradation, long startedAt) {
        log.info("Vue 回合联合分类完成,mode={},source={},needRag={},needImageSearch={},"
                        + "rawNeedRag={},rawNeedImageSearch={},degradation={},elapsedMs={}",
                decision.mode(), source, decision.needRag(), decision.needImageSearch(),
                rawRag, rawImages, degradation, elapsedMillis(startedAt));
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000L);
    }
}
