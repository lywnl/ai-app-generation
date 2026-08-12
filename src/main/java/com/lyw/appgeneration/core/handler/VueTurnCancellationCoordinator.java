package com.lyw.appgeneration.core.handler;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** 在客户端断开后仍受应用生命周期管理地完成 Vue 回合稳定收尾。 */
@Slf4j
@Component
public class VueTurnCancellationCoordinator implements AutoCloseable {

    static final Duration QUIESCENCE_TIMEOUT = Duration.ofSeconds(10);

    private final VueTurnFinalizer finalizer;
    private final ExecutorService executor;

    @Autowired
    public VueTurnCancellationCoordinator(VueTurnFinalizer finalizer) {
        this(finalizer, Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("vue-turn-cancel-", 0).factory()));
    }

    VueTurnCancellationCoordinator(
            VueTurnFinalizer finalizer, ExecutorService executor) {
        this.finalizer = Objects.requireNonNull(finalizer);
        this.executor = Objects.requireNonNull(executor);
    }

    public boolean requestCancellation(
            VueTurnContext context, Supplier<String> canonicalPrefix) {
        Objects.requireNonNull(context, "context 不能为空");
        Objects.requireNonNull(canonicalPrefix, "canonicalPrefix 不能为空");
        if (!context.tryClaimTerminal()) {
            return false;
        }
        context.revokeCallbacks();
        executor.submit(() -> finalizeCancellation(context, canonicalPrefix));
        return true;
    }

    private void finalizeCancellation(
            VueTurnContext context, Supplier<String> canonicalPrefix) {
        try {
            context.cancelGeneration();
            if (!context.awaitQuiescence(QUIESCENCE_TIMEOUT)) {
                log.warn("Vue 取消等待回调静默超时,appId={},turnId={}",
                        context.appId(), context.turnId());
            }
            String message = "本次生成已取消。";
            String canonical = JsonMessageStreamHandler.appendTerminalText(
                    canonicalPrefix.get(), message);
            finalizer.finalizeOnce(context, new VueTurnOutcome(
                    context.phase(), VueTurnOutcome.TurnOutcomeType.CANCELLED,
                    canonical, false, message));
        } catch (RuntimeException exception) {
            log.error("Vue 取消后台收尾异常,appId={},turnId={}",
                    context.appId(), context.turnId(), exception);
        }
    }

    @Override
    @PreDestroy
    public void close() {
        executor.close();
    }
}
