package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleGenerationTurnContextTest {

    @Test
    void 资源关闭具有幂等性() {
        AppOperationLeaseManager leases = new AppOperationLeaseManager();
        var operation = leases.acquire(
                7L, AppOperationLeaseManager.AppOperationType.GENERATE, "普通回合");
        SimpleGenerationTurnContext context =
                new SimpleGenerationTurnContext(operation);

        context.close();

        assertDoesNotThrow(context::close);
        assertDoesNotThrow(() -> leases.acquire(
                7L, AppOperationLeaseManager.AppOperationType.GENERATE,
                "下一轮").close());
    }

    @Test
    void 删除早于上游绑定时后续绑定立即取消() throws Exception {
        AppOperationLeaseManager leases = new AppOperationLeaseManager();
        var operation = leases.acquire(
                7L, AppOperationLeaseManager.AppOperationType.GENERATE, "普通回合");
        SimpleGenerationTurnContext context =
                new SimpleGenerationTurnContext(operation);
        AtomicBoolean upstreamCancelled = new AtomicBoolean();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var deletion = executor.submit(() -> leases.cancelAndAcquireDelete(
                    7L, "删除", Duration.ofSeconds(2)));
            assertTrue(context.awaitCancellation(Duration.ofSeconds(1)));

            context.bindUpstream(() -> upstreamCancelled.set(true));
            assertTrue(upstreamCancelled.get());
            context.close();

            deletion.get(2, TimeUnit.SECONDS).close();
        }
    }

    @Test
    void 删除等待贯穿整轮的回调票据退出() throws Exception {
        AppOperationLeaseManager leases = new AppOperationLeaseManager();
        var operation = leases.acquire(
                7L, AppOperationLeaseManager.AppOperationType.GENERATE, "普通回合");
        SimpleGenerationTurnContext context =
                new SimpleGenerationTurnContext(operation);
        CountDownLatch upstreamCancelled = new CountDownLatch(1);
        context.bindUpstream(upstreamCancelled::countDown);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var deletion = executor.submit(() -> leases.cancelAndAcquireDelete(
                    7L, "删除", Duration.ofSeconds(2)));
            assertTrue(upstreamCancelled.await(1, TimeUnit.SECONDS));
            assertFalse(deletion.isDone());

            context.close();

            deletion.get(2, TimeUnit.SECONDS).close();
        }
    }
}
