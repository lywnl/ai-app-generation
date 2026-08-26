package com.lyw.appgeneration.core.handler;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationCancellationRegistryTest {

    @Test
    void 只按应用和生成任务ID取消且注销不会误伤下一轮() {
        GenerationCancellationRegistry registry =
                new GenerationCancellationRegistry();
        AtomicInteger oldCancellation = new AtomicInteger();
        AtomicInteger currentCancellation = new AtomicInteger();
        Runnable oldAction = oldCancellation::incrementAndGet;
        Runnable currentAction = currentCancellation::incrementAndGet;

        assertTrue(registry.register(7L, "任务一", 9L, oldAction));
        assertEquals(
                GenerationCancellationRegistry.CancellationResult.REQUESTED,
                registry.cancel(7L, "任务一", 9L));
        registry.unregister(7L, "任务一", oldAction);
        assertTrue(registry.register(7L, "任务二", 9L, currentAction));

        assertEquals(
                GenerationCancellationRegistry.CancellationResult.NOT_FOUND,
                registry.cancel(7L, "任务一", 9L));
        assertEquals(
                GenerationCancellationRegistry.CancellationResult.FORBIDDEN,
                registry.cancel(7L, "任务二", 10L));
        assertEquals(
                GenerationCancellationRegistry.CancellationResult.REQUESTED,
                registry.cancel(7L, "任务二", 9L));
        assertEquals(1, oldCancellation.get());
        assertEquals(1, currentCancellation.get());
        assertFalse(registry.register(7L, "任务二", 9L, currentAction));
    }
}
