package dev.langchain4j.service;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.service.tool.ToolExecution;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalOutputRecoveryCoordinatorTest {

    private static final String PREFIX = "[[internal.";
    private static final String MARKER = "<internal-ack>";

    @Test
    void 信号校验字段组合并防御复制工具请求集合() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("request-1").name("readFile").arguments("{}").build();
        ToolExecution execution = ToolExecution.builder()
                .request(request).result("安全").build();
        Set<String> ids = new HashSet<>(Set.of("request-1"));

        GenerationStreamSignal.Rollback rollback = new GenerationStreamSignal.Rollback(1, 0, ids);
        ids.add("request-2");
        assertEquals(Set.of("request-1"), rollback.provisionalToolRequestIds());
        assertThrows(UnsupportedOperationException.class,
                () -> rollback.provisionalToolRequestIds().add("request-3"));
        assertEquals("正文", new GenerationStreamSignal.AiText(1, "正文").text());
        assertEquals(request, new GenerationStreamSignal.PartialToolRequest(1, 0, request).request());
        assertEquals(request, new GenerationStreamSignal.CompleteToolRequest(1, 0, request).request());
        assertEquals(execution, new GenerationStreamSignal.ToolExecuted(1, execution).execution());
        assertEquals(GenerationStreamSignal.Recovery.Phase.STARTED,
                new GenerationStreamSignal.Recovery(
                        GenerationStreamSignal.Recovery.Phase.STARTED, 1, 2L, null).phase());

        assertThrows(IllegalArgumentException.class,
                () -> new GenerationStreamSignal.AiText(0, "正文"));
        assertThrows(NullPointerException.class,
                () -> new GenerationStreamSignal.AiText(1, null));
        assertThrows(IllegalArgumentException.class,
                () -> new GenerationStreamSignal.PartialToolRequest(1, -1, request));
        assertThrows(IllegalArgumentException.class,
                () -> new GenerationStreamSignal.CompleteToolRequest(0, 0, request));
        assertThrows(IllegalArgumentException.class,
                () -> new GenerationStreamSignal.Rollback(1, -1, Set.of()));
        assertThrows(NullPointerException.class,
                () -> new GenerationStreamSignal.Rollback(1, 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> new GenerationStreamSignal.Rollback(1, 0, Set.of(" ")));
        assertThrows(NullPointerException.class,
                () -> new GenerationStreamSignal.CompleteToolRequest(1, 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> new GenerationStreamSignal.Recovery(
                        GenerationStreamSignal.Recovery.Phase.STARTED, 1, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new GenerationStreamSignal.Recovery(
                        GenerationStreamSignal.Recovery.Phase.RECOVERED, 1, 2L, 2L));
        assertThrows(IllegalArgumentException.class,
                () -> new GenerationStreamSignal.Recovery(
                        GenerationStreamSignal.Recovery.Phase.FAILED, 1, null, 2L));
        assertThrows(IllegalArgumentException.class,
                () -> new GenerationStreamSignal.Recovery(
                        GenerationStreamSignal.Recovery.Phase.FAILED, 2, 3L, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new GenerationStreamSignal.Recovery(
                        GenerationStreamSignal.Recovery.Phase.FAILED, 2, 1L, 3L));
        assertThrows(IllegalArgumentException.class,
                () -> new GenerationStreamSignal.Recovery(
                        GenerationStreamSignal.Recovery.Phase.STARTED, 2, 2L, null));
    }

    @Test
    void 一次恢复只允许首次认领并按启动恢复顺序发布() {
        List<GenerationStreamSignal> signals = new ArrayList<>();
        InternalOutputRecoveryCoordinator coordinator = coordinator(
                InternalOutputRecoveryPolicy.Mode.RECOVER_ONCE, signals);

        assertEquals(InternalOutputRecoveryCoordinator.ViolationAction.START_RECOVERY,
                coordinator.claimViolation(1));
        assertEquals(InternalOutputRecoveryCoordinator.ViolationAction.IGNORE,
                coordinator.claimViolation(1));
        assertThrows(IllegalArgumentException.class,
                () -> coordinator.recoveryStartCommitted(1));
        coordinator.recoveryStartCommitted(2);
        coordinator.recoveryStartCommitted(2);
        coordinator.recovered(1);
        coordinator.recovered(3);
        coordinator.recovered(3);

        assertEquals(List.of(
                new GenerationStreamSignal.Recovery(
                        GenerationStreamSignal.Recovery.Phase.STARTED, 1, 2L, null),
                new GenerationStreamSignal.Recovery(
                        GenerationStreamSignal.Recovery.Phase.RECOVERED, 1, 2L, null)), signals);
        assertEquals(InternalOutputRecoveryCoordinator.ViolationAction.FAIL,
                coordinator.claimViolation(3));
    }

    @Test
    void 快速失败不保留恢复额度也不发布信号() {
        List<GenerationStreamSignal> signals = new ArrayList<>();
        InternalOutputRecoveryCoordinator coordinator = coordinator(
                InternalOutputRecoveryPolicy.Mode.FAIL_FAST, signals);

        assertEquals(InternalOutputRecoveryCoordinator.ViolationAction.FAIL,
                coordinator.claimViolation(1));
        assertEquals(InternalOutputRecoveryCoordinator.ViolationAction.FAIL,
                coordinator.claimViolation(1));
        assertEquals(List.of(), signals);
    }

    @Test
    void 释放启动预留后可重新认领且失败字段区分启动前后和后续代() {
        List<GenerationStreamSignal> signals = new ArrayList<>();
        InternalOutputRecoveryCoordinator coordinator = coordinator(
                InternalOutputRecoveryPolicy.Mode.RECOVER_ONCE, signals);

        assertEquals(InternalOutputRecoveryCoordinator.ViolationAction.START_RECOVERY,
                coordinator.claimViolation(1));
        coordinator.releaseRecoveryReservation();
        assertEquals(InternalOutputRecoveryCoordinator.ViolationAction.START_RECOVERY,
                coordinator.claimViolation(1));
        coordinator.failBeforeRecoveryStart();
        assertEquals(List.of(new GenerationStreamSignal.Recovery(
                GenerationStreamSignal.Recovery.Phase.FAILED, 1, null, 1L)), signals);

        signals.clear();
        coordinator = coordinator(InternalOutputRecoveryPolicy.Mode.RECOVER_ONCE, signals);
        coordinator.claimViolation(1);
        coordinator.recoveryStartCommitted(2);
        coordinator.failAfterRecoveryStart();
        assertEquals(List.of(
                new GenerationStreamSignal.Recovery(
                        GenerationStreamSignal.Recovery.Phase.STARTED, 1, 2L, null),
                new GenerationStreamSignal.Recovery(
                        GenerationStreamSignal.Recovery.Phase.FAILED, 1, 2L, 2L)), signals);

        signals.clear();
        coordinator = coordinator(InternalOutputRecoveryPolicy.Mode.RECOVER_ONCE, signals);
        coordinator.claimViolation(1);
        coordinator.recoveryStartCommitted(2);
        coordinator.recovered(2);
        InternalOutputRecoveryCoordinator recoveredCoordinator = coordinator;
        assertThrows(IllegalArgumentException.class,
                () -> recoveredCoordinator.failForRecoveryViolation(1));
        coordinator.failForRecoveryViolation(3);
        assertEquals(List.of(
                new GenerationStreamSignal.Recovery(
                        GenerationStreamSignal.Recovery.Phase.STARTED, 1, 2L, null),
                new GenerationStreamSignal.Recovery(
                        GenerationStreamSignal.Recovery.Phase.RECOVERED, 1, 2L, null),
                new GenerationStreamSignal.Recovery(
                        GenerationStreamSignal.Recovery.Phase.FAILED, 1, 2L, 3L)), signals);

        signals.clear();
        coordinator = coordinator(InternalOutputRecoveryPolicy.Mode.RECOVER_ONCE, signals);
        coordinator.claimViolation(1);
        coordinator.recoveryStartCommitted(2);
        coordinator.recovered(2);
        coordinator.failAfterRecoveryStart();
        assertEquals(List.of(
                new GenerationStreamSignal.Recovery(
                        GenerationStreamSignal.Recovery.Phase.STARTED, 1, 2L, null),
                new GenerationStreamSignal.Recovery(
                        GenerationStreamSignal.Recovery.Phase.RECOVERED, 1, 2L, null)), signals);
    }

    @Test
    void 关闭保持静默且临时提示固定不依赖应用协议类() {
        List<GenerationStreamSignal> signals = new ArrayList<>();
        InternalOutputRecoveryCoordinator coordinator = coordinator(
                InternalOutputRecoveryPolicy.Mode.RECOVER_ONCE, signals);

        assertEquals(1, coordinator.transientMessages().size());
        SystemMessage transientMessage = (SystemMessage) coordinator.transientMessages().getFirst();
        assertTrue(transientMessage.text()
                .contains("上一响应复述了服务端内部状态标记"));
        assertFalse(transientMessage.text()
                .contains("SyntheticMemoryMessageProtocol"));
        coordinator.claimViolation(1);
        coordinator.closeSilently();
        coordinator.recoveryStartCommitted(2);
        coordinator.recovered(2);
        coordinator.failAfterRecoveryStart();
        coordinator.failForRecoveryViolation(2);
        assertEquals(InternalOutputRecoveryCoordinator.ViolationAction.IGNORE,
                coordinator.claimViolation(2));
        assertEquals(List.of(), signals);
    }

    @Test
    void 监听器异常向上传播但不会回滚已提交状态或重复发布() {
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        InternalOutputRecoveryCoordinator coordinator = new InternalOutputRecoveryCoordinator(
                new InternalOutputRecoveryPolicy(
                        InternalOutputRecoveryPolicy.Mode.RECOVER_ONCE, PREFIX, Set.of(MARKER)),
                ignored -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("观察端失败");
                });

        assertEquals(InternalOutputRecoveryCoordinator.ViolationAction.START_RECOVERY,
                coordinator.claimViolation(1));
        assertThrows(IllegalStateException.class,
                () -> coordinator.recoveryStartCommitted(2));
        coordinator.recoveryStartCommitted(2);
        assertThrows(IllegalStateException.class, () -> coordinator.recovered(2));
        coordinator.recovered(2);

        assertEquals(2, attempts.get());
    }

    @Test
    void 并发违规只有一次恢复认领() throws Exception {
        InternalOutputRecoveryCoordinator coordinator = coordinator(
                InternalOutputRecoveryPolicy.Mode.RECOVER_ONCE, new ArrayList<>());
        CountDownLatch ready = new CountDownLatch(32);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<InternalOutputRecoveryCoordinator.ViolationAction>> results = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return coordinator.claimViolation(1);
                }));
            }
            ready.await();
            start.countDown();
            assertEquals(1, results.stream().map(this::resultOf)
                    .filter(InternalOutputRecoveryCoordinator.ViolationAction.START_RECOVERY::equals)
                    .count());
            assertEquals(31, results.stream().map(this::resultOf)
                    .filter(InternalOutputRecoveryCoordinator.ViolationAction.IGNORE::equals)
                    .count());
        }
    }

    @Test
    void 启动信号监听阻塞时并发恢复仍按提交顺序发布() throws Exception {
        List<GenerationStreamSignal> signals = Collections.synchronizedList(
                new ArrayList<>());
        CountDownLatch startedListenerEntered = new CountDownLatch(1);
        CountDownLatch releaseStartedListener = new CountDownLatch(1);
        InternalOutputRecoveryCoordinator coordinator = coordinatorWithBlockingStarted(
                signals, startedListenerEntered, releaseStartedListener);
        coordinator.claimViolation(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> started = executor.submit(
                    () -> coordinator.recoveryStartCommitted(2));
            try {
                assertTrue(startedListenerEntered.await(3, TimeUnit.SECONDS));
                Future<?> recovered = executor.submit(() -> coordinator.recovered(2));
                recovered.get(3, TimeUnit.SECONDS);
            } finally {
                releaseStartedListener.countDown();
            }
            started.get(3, TimeUnit.SECONDS);
        }

        assertEquals(List.of(
                new GenerationStreamSignal.Recovery(
                        GenerationStreamSignal.Recovery.Phase.STARTED,
                        1, 2L, null),
                new GenerationStreamSignal.Recovery(
                        GenerationStreamSignal.Recovery.Phase.RECOVERED,
                        1, 2L, null)), signals);
    }

    @Test
    void 启动信号监听阻塞时并发失败仍按提交顺序发布() throws Exception {
        List<GenerationStreamSignal> signals = Collections.synchronizedList(
                new ArrayList<>());
        CountDownLatch startedListenerEntered = new CountDownLatch(1);
        CountDownLatch releaseStartedListener = new CountDownLatch(1);
        InternalOutputRecoveryCoordinator coordinator = coordinatorWithBlockingStarted(
                signals, startedListenerEntered, releaseStartedListener);
        coordinator.claimViolation(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> started = executor.submit(
                    () -> coordinator.recoveryStartCommitted(2));
            try {
                assertTrue(startedListenerEntered.await(3, TimeUnit.SECONDS));
                Future<?> failed = executor.submit(coordinator::failAfterRecoveryStart);
                failed.get(3, TimeUnit.SECONDS);
            } finally {
                releaseStartedListener.countDown();
            }
            started.get(3, TimeUnit.SECONDS);
        }

        assertEquals(List.of(
                new GenerationStreamSignal.Recovery(
                        GenerationStreamSignal.Recovery.Phase.STARTED,
                        1, 2L, null),
                new GenerationStreamSignal.Recovery(
                        GenerationStreamSignal.Recovery.Phase.FAILED,
                        1, 2L, 2L)), signals);
    }

    private InternalOutputRecoveryCoordinator.ViolationAction resultOf(
            Future<InternalOutputRecoveryCoordinator.ViolationAction> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private InternalOutputRecoveryCoordinator coordinator(
            InternalOutputRecoveryPolicy.Mode mode,
            List<GenerationStreamSignal> signals) {
        return new InternalOutputRecoveryCoordinator(
                new InternalOutputRecoveryPolicy(mode, PREFIX, Set.of(MARKER)), signals::add);
    }

    private InternalOutputRecoveryCoordinator coordinatorWithBlockingStarted(
            List<GenerationStreamSignal> signals,
            CountDownLatch startedListenerEntered,
            CountDownLatch releaseStartedListener) {
        return new InternalOutputRecoveryCoordinator(
                new InternalOutputRecoveryPolicy(
                        InternalOutputRecoveryPolicy.Mode.RECOVER_ONCE,
                        PREFIX,
                        Set.of(MARKER)),
                signal -> {
                    if (signal instanceof GenerationStreamSignal.Recovery recovery
                            && recovery.phase()
                            == GenerationStreamSignal.Recovery.Phase.STARTED) {
                        startedListenerEntered.countDown();
                        try {
                            if (!releaseStartedListener.await(
                                    3, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("等待启动信号释放超时");
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("等待启动信号释放时被中断", exception);
                        }
                    }
                    signals.add(signal);
                });
    }
}
