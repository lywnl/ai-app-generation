package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.ai.tools.FileToolBudgetGuard;
import com.lyw.appgeneration.core.builder.VueBuildPhase;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import com.lyw.appgeneration.core.resource.ResourcePreparationScope;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class VueTurnResourceCancellationTest {
    @Test
    void 通用资料注册与主模型注册相互独立且可注销() throws Exception {
        var operation = new AppOperationLeaseManager().acquire(7,
                AppOperationLeaseManager.AppOperationType.GENERATE, "resources");
        var lease = new VueBuildSessionManager().open(operation, 9, "resources");
        var context = new VueTurnContext(7, 9, "resources", operation, lease, new FileToolBudgetGuard().newSession());
        AtomicInteger discarded = new AtomicInteger(), kept = new AtomicInteger(), model = new AtomicInteger();
        try {
            var closed = context.registerResourceCancellation(discarded::incrementAndGet);
            closed.close();
            context.registerResourceCancellation(kept::incrementAndGet);
            context.registerModelCancellation(model::incrementAndGet);
            context.cancelGeneration();
            context.cancelGeneration();
            assertEquals(0, discarded.get());
            assertEquals(1, kept.get());
            assertEquals(1, model.get());
        } finally { context.closeResources(); }
    }

    @Test
    void 无租约测试回合也取消资料且剩余期限限制资料预算() {
        AtomicLong clock = new AtomicLong();
        var context = VueTurnContext.testing(7, 9, "short", VueBuildPhase.GENERATING,
                Duration.ofMillis(200), clock::get, VueTurnMode.MUTATION_REQUIRED);
        try (var scope = ResourcePreparationScope.start(context, Duration.ofSeconds(15))) {
            assertTrue(scope.remainingNanos() <= Duration.ofMillis(200).toNanos());
            var slot = scope.slot("images");
            context.cancelGeneration();
            assertFalse(slot.publish("late", 1, 1));
            assertTrue(scope.awaitAndFreeze().isEmpty());
        } finally { context.closeResources(); }
    }
}
