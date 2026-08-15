package com.lyw.appgeneration.ai.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.ModelRequestGate;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.UserMemoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextCompressionModelRequestGateTest {

    @ParameterizedTest
    @EnumSource(value = ContextCompressionMode.class,
            names = {"NORMAL", "ASYNC_SCHEDULED", "BLOCKING_COMPLETED"})
    void 可继续压缩模式映射为允许并读取协调后的活动记忆(
            ContextCompressionMode mode) throws Exception {
        ContextCompressionCoordinator coordinator =
                mock(ContextCompressionCoordinator.class);
        CompressionAwareChatMemory compressionMemory =
                compressionMemory("旧上下文".repeat(10_000));
        ChatMemory refreshedMemory = memory("压缩后的新消息");
        AtomicInteger memoryReads = new AtomicInteger();
        when(coordinator.admit(eq(compressionMemory), eq(List.of()),
                any(), any())).thenReturn(result(mode,
                ContextAdmissionResult.FailureReason.NONE, 30_001, 12_345));

        try (ExecutorService executor =
                     Executors.newVirtualThreadPerTaskExecutor()) {
            ContextCompressionModelRequestGate gate =
                    new ContextCompressionModelRequestGate(
                            coordinator, executor);

            ModelRequestGate.Decision decision = gate.prepare(
                            new ModelRequestGate.Request(
                                    7L,
                                    () -> memoryReads.getAndIncrement() == 0
                                            ? compressionMemory : refreshedMemory,
                                    List.of(),
                                    action -> {
                                        action.run();
                                        return true;
                                    }))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertEquals(ModelRequestGate.Status.ALLOWED, decision.status());
            assertEquals(refreshedMemory.messages(), decision.messages());
            assertEquals(12_345, decision.estimatedInputTokens());
            assertEquals(2, memoryReads.get(),
                    "协调完成后必须重新读取代理当前使用的活动 ChatMemory");
        }
    }

    @Test
    void 回合终态映射为取消() throws Exception {
        assertStatus(
                ContextCompressionMode.ADMISSION_FAILED,
                ContextAdmissionResult.FailureReason.TURN_TERMINATED,
                ModelRequestGate.Status.CANCELLED);
    }

    @Test
    void 压缩失败映射为类型化失败() throws Exception {
        assertStatus(
                ContextCompressionMode.BLOCKING_FAILED,
                ContextAdmissionResult.FailureReason.MODEL_FAILED,
                ModelRequestGate.Status.COMPRESSION_FAILED);
    }

    @Test
    void 硬上限拒绝保持独立状态() throws Exception {
        assertStatus(
                ContextCompressionMode.HARD_LIMIT_REJECTED,
                ContextAdmissionResult.FailureReason.STILL_OVER_HARD_LIMIT,
                ModelRequestGate.Status.HARD_LIMIT_REJECTED);
    }

    @Test
    void 协调器必须在受管虚拟线程而不是调用线程执行() throws Exception {
        ContextCompressionCoordinator coordinator =
                mock(ContextCompressionCoordinator.class);
        CompressionAwareChatMemory memory =
                compressionMemory("协调前消息");
        AtomicReference<Thread> worker = new AtomicReference<>();
        when(coordinator.admit(eq(memory), eq(List.of()), any(), any()))
                .thenAnswer(invocation -> {
                    worker.set(Thread.currentThread());
                    return result(ContextCompressionMode.NORMAL,
                            ContextAdmissionResult.FailureReason.NONE, 10, 10);
                });
        Thread caller = Thread.currentThread();

        try (ExecutorService executor =
                     Executors.newVirtualThreadPerTaskExecutor()) {
            ContextCompressionModelRequestGate gate =
                    new ContextCompressionModelRequestGate(
                            coordinator, executor);

            gate.prepare(request(memory)).toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);

            assertNotEquals(caller, worker.get());
            assertTrue(worker.get().isVirtual());
        }
    }

    @Test
    void 已完成Future的完成回调仍由受管虚拟线程派发() throws Exception {
        ContextCompressionCoordinator coordinator =
                mock(ContextCompressionCoordinator.class);
        ChatMemory memory = memory("已完成门禁结果");
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        Thread caller = Thread.currentThread();

        try (ExecutorService executor =
                     Executors.newVirtualThreadPerTaskExecutor()) {
            ContextCompressionModelRequestGate gate =
                    new ContextCompressionModelRequestGate(
                            coordinator, executor);
            ModelRequestGate.Decision allowed = new ModelRequestGate.Decision(
                    ModelRequestGate.Status.ALLOWED,
                    memory.messages(),
                    10,
                    "");

            gate.onPrepared(CompletableFuture.completedFuture(allowed),
                    (decision, failure) -> {
                        callbackThread.set(Thread.currentThread());
                        callbackFailure.set(failure);
                        completed.countDown();
                    });

            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertNull(callbackFailure.get());
            assertNotEquals(caller, callbackThread.get());
            assertTrue(callbackThread.get().isVirtual());
        }
    }

    @Test
    void 完成回调执行器拒绝时不得同步执行续调用() throws Exception {
        ContextCompressionCoordinator coordinator =
                mock(ContextCompressionCoordinator.class);
        ChatMemory memory = memory("拒绝派发的门禁结果");
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        executor.shutdownNow();
        ContextCompressionModelRequestGate gate =
                new ContextCompressionModelRequestGate(coordinator, executor);
        AtomicInteger callbacks = new AtomicInteger();
        ModelRequestGate.Decision allowed = new ModelRequestGate.Decision(
                ModelRequestGate.Status.ALLOWED,
                memory.messages(),
                10,
                "");

        ModelRequestGate.DispatchStatus dispatch = gate.onPrepared(
                        CompletableFuture.completedFuture(allowed),
                        (decision, failure) -> callbacks.incrementAndGet())
                .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals(ModelRequestGate.DispatchStatus.REJECTED, dispatch);
        assertEquals(0, callbacks.get(),
                "执行器拒绝后不能在调用线程同步执行续调用");
        verify(coordinator, never()).admit(any(), any(), any(), any());
    }

    @Test
    void 执行器拒绝时不得回退调用线程执行协调器() throws Exception {
        ContextCompressionCoordinator coordinator =
                mock(ContextCompressionCoordinator.class);
        CompressionAwareChatMemory memory =
                compressionMemory("执行器拒绝消息");
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        executor.shutdownNow();
        ContextCompressionModelRequestGate gate =
                new ContextCompressionModelRequestGate(coordinator, executor);

        ModelRequestGate.Decision decision = gate.prepare(request(memory))
                .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals(ModelRequestGate.Status.COMPRESSION_FAILED,
                decision.status());
        assertTrue(decision.messages().isEmpty());
        assertFalse(decision.safeMessage().isBlank());
        verify(coordinator, never()).admit(any(), any(), any(), any());
    }

    private void assertStatus(
            ContextCompressionMode mode,
            ContextAdmissionResult.FailureReason failureReason,
            ModelRequestGate.Status expected) throws Exception {
        ContextCompressionCoordinator coordinator =
                mock(ContextCompressionCoordinator.class);
        CompressionAwareChatMemory memory =
                compressionMemory("协调后的活动消息");
        List<ChatMessage> latestMessages = List.of(
                UserMessage.from("协调后的活动消息"));
        when(coordinator.admit(eq(memory), eq(List.of()), any(), any()))
                .thenReturn(result(mode, failureReason, 31_000, 29_000));

        try (ExecutorService executor =
                     Executors.newVirtualThreadPerTaskExecutor()) {
            ContextCompressionModelRequestGate gate =
                    new ContextCompressionModelRequestGate(
                            coordinator, executor);

            ModelRequestGate.Decision decision = gate.prepare(request(memory))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertEquals(expected, decision.status());
            assertEquals(latestMessages, decision.messages());
            assertEquals(29_000, decision.estimatedInputTokens());
            if (expected == ModelRequestGate.Status.ALLOWED) {
                assertTrue(decision.safeMessage().isBlank());
            } else {
                assertFalse(decision.safeMessage().isBlank());
            }
        }
    }

    private ModelRequestGate.Request request(ChatMemory memory) {
        return new ModelRequestGate.Request(
                7L, () -> memory, List.of(), action -> {
                    action.run();
                    return true;
                });
    }

    private ContextAdmissionResult result(
            ContextCompressionMode mode,
            ContextAdmissionResult.FailureReason failureReason,
            int initialTokens,
            int finalTokens) {
        return new ContextAdmissionResult(
                mode, initialTokens, finalTokens, 0L,
                failureReason, "测试结果");
    }

    private ChatMemory memory(String message) {
        MessageWindowChatMemory memory =
                MessageWindowChatMemory.withMaxMessages(10);
        memory.add(UserMessage.from(message));
        return memory;
    }

    private CompressionAwareChatMemory compressionMemory(String message) {
        MessageWindowChatMemory delegate = MessageWindowChatMemory.builder()
                .id(7L)
                .maxMessages(Integer.MAX_VALUE)
                .build();
        MemorySummaryService summaryService = mock(MemorySummaryService.class);
        UserMemoryService userMemoryService = mock(UserMemoryService.class);
        when(summaryService.getCurrentSummary(7L)).thenReturn("");
        when(userMemoryService.recallByApp(7L)).thenReturn("");
        CompressionAwareChatMemory memory = new CompressionAwareChatMemory(
                new TokenAwareChatMemory(delegate),
                summaryService,
                userMemoryService);
        memory.add(UserMessage.from(message));
        return memory;
    }
}
