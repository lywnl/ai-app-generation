package dev.langchain4j.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelRequestGateTest {

    @Test
    void 默认完成派发必须失败关闭且不得调用回调() {
        ModelRequestGate gate = request -> CompletableFuture.completedFuture(null);
        AtomicInteger completionCalls = new AtomicInteger();

        ModelRequestGate.DispatchStatus status = gate.onPrepared(
                        CompletableFuture.completedFuture(null),
                        (decision, failure) -> completionCalls.incrementAndGet())
                .toCompletableFuture()
                .join();

        assertEquals(ModelRequestGate.DispatchStatus.REJECTED, status);
        assertEquals(0, completionCalls.get());
    }
}
