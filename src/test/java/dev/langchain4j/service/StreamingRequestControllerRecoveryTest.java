package dev.langchain4j.service;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;

import static dev.langchain4j.service.StreamingRequestController.GenerationCancellation.CANCELLED;
import static dev.langchain4j.service.StreamingRequestController.GenerationCancellation.REJECTED;
import static dev.langchain4j.service.ToolLoopTerminationProtocol.ControlledTerminationReason.PROTOCOL_ERROR;
import static dev.langchain4j.service.ToolLoopTerminationProtocol.ControlledTerminationReason.RESOURCE_LIMIT_EXCEEDED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingRequestControllerRecoveryTest {

    @Test
    void 内部协议恢复门禁失败必须由控制器认领唯一协议终态() {
        StreamingRequestController controller = activeController();
        long sourceGeneration = controller.latestModelRequestGeneration();
        assertEquals(CANCELLED,
                controller.cancelGenerationForRecovery(sourceGeneration));
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger terminations = new AtomicInteger();
        controller.onControlledTermination(ignored ->
                terminations.incrementAndGet());
        GenerationAwareModelRequestOrchestrator orchestrator =
                new GenerationAwareModelRequestOrchestrator(
                        controller,
                        inlineGate(new ModelRequestGate.Decision(
                                ModelRequestGate.Status.HARD_LIMIT_REJECTED,
                                List.of(UserMessage.from("恢复上下文过长")),
                                32_768,
                                "恢复上下文过长")),
                        action -> {
                            action.run();
                            return true;
                        });

        orchestrator.submit(
                GenerationAwareModelRequestOrchestrator
                        .internalProtocolRecovery(
                                sourceGeneration,
                                null,
                                () -> List.of(UserMessage.from("恢复请求")),
                                () -> { },
                                () -> { },
                                ignored -> { },
                                ignored -> {
                                    assertEquals(PROTOCOL_ERROR,
                                            controller.controlledTermination()
                                                    .reason());
                                    assertEquals(0, terminations.get(),
                                            "FAILED 必须先于受控终止派发");
                                    failures.incrementAndGet();
                                },
                                (messages, generation) -> () -> { }));

        assertEquals(1, failures.get());
        assertEquals(1, terminations.get());
        assertFalse(controller.isOpen());
        assertEquals(PROTOCOL_ERROR,
                controller.controlledTermination().reason());
    }

    @Test
    void 内部协议恢复同步启动失败必须由控制器认领唯一协议终态() {
        StreamingRequestController controller = activeController();
        long sourceGeneration = controller.latestModelRequestGeneration();
        assertEquals(CANCELLED,
                controller.cancelGenerationForRecovery(sourceGeneration));
        List<String> order = new ArrayList<>();
        AtomicInteger terminations = new AtomicInteger();
        controller.onControlledTermination(ignored -> {
            order.add("terminated");
            terminations.incrementAndGet();
        });
        GenerationAwareModelRequestOrchestrator orchestrator =
                new GenerationAwareModelRequestOrchestrator(
                        controller, inlineAllowingGate(), action -> {
                            action.run();
                            return true;
                        });

        orchestrator.submit(
                GenerationAwareModelRequestOrchestrator
                        .internalProtocolRecovery(
                                sourceGeneration,
                                null,
                                () -> List.of(UserMessage.from("恢复请求")),
                                () -> { },
                                () -> { },
                                generation -> order.add(
                                        "started-" + generation),
                                ignored -> {
                                    assertEquals(PROTOCOL_ERROR,
                                            controller.controlledTermination()
                                                    .reason());
                                    assertEquals(0, terminations.get(),
                                            "FAILED 必须先于受控终止派发");
                                    order.add("failed");
                                },
                                (messages, generation) -> () -> {
                                    order.add("sdk-" + generation);
                                    throw new IllegalStateException(
                                            "SDK 同步失败");
                                }));

        assertEquals(List.of(
                "started-2", "sdk-2", "failed", "terminated"),
                order);
        assertEquals(1, terminations.get());
        assertFalse(controller.isOpen());
        assertEquals(PROTOCOL_ERROR,
                controller.controlledTermination().reason());
    }

    @Test
    void 内部恢复提交后的钩子必须先于SDK启动执行() {
        StreamingRequestController controller = activeController();
        long sourceGeneration = controller.latestModelRequestGeneration();
        assertEquals(CANCELLED, controller.cancelGenerationForRecovery(sourceGeneration));
        List<String> order = new ArrayList<>();
        GenerationAwareModelRequestOrchestrator orchestrator = new GenerationAwareModelRequestOrchestrator(
                controller, inlineAllowingGate(), action -> {
                    action.run();
                    return true;
                });

        orchestrator.submit(GenerationAwareModelRequestOrchestrator.recovery(
                sourceGeneration, null,
                () -> List.of(UserMessage.from("恢复请求")),
                () -> { }, () -> { },
                generation -> {
                    assertTrue(controller.isCurrentGeneration(generation));
                    order.add("started-" + generation);
                },
                ignored -> order.add("failed"),
                (messages, generation) -> () -> order.add("sdk-" + generation)));

        assertEquals(List.of("started-2", "sdk-2"), order);
    }

    @Test
    void 内部恢复准备失败或取消先赢不得调用启动钩子() throws Exception {
        StreamingRequestController preparationFailure = activeController();
        long preparationSource = preparationFailure.latestModelRequestGeneration();
        assertEquals(CANCELLED, preparationFailure.cancelGenerationForRecovery(preparationSource));
        AtomicInteger hooks = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger cancellations = new AtomicInteger();
        GenerationAwareModelRequestOrchestrator failureOrchestrator = new GenerationAwareModelRequestOrchestrator(
                preparationFailure, inlineAllowingGate(), action -> {
                    action.run();
                    return true;
                });
        failureOrchestrator.submit(GenerationAwareModelRequestOrchestrator.recovery(
                preparationSource, null, () -> List.of(UserMessage.from("恢复请求")),
                () -> { }, () -> { }, ignored -> hooks.incrementAndGet(),
                ignored -> failures.incrementAndGet(),
                (messages, generation) -> {
                    throw new IllegalStateException("准备失败");
                }));
        assertEquals(0, hooks.get());
        assertEquals(1, failures.get());

        StreamingRequestController cancelled = activeController();
        long cancelledSource = cancelled.latestModelRequestGeneration();
        assertEquals(CANCELLED, cancelled.cancelGenerationForRecovery(cancelledSource));
        failures.set(0);
        CountDownLatch prepareEntered = new CountDownLatch(1);
        CountDownLatch releasePrepare = new CountDownLatch(1);
        GenerationAwareModelRequestOrchestrator cancelledOrchestrator = new GenerationAwareModelRequestOrchestrator(
                cancelled, inlineAllowingGate(), action -> {
                    action.run();
                    return true;
                });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> submission = executor.submit(() -> cancelledOrchestrator.submit(
                    GenerationAwareModelRequestOrchestrator.recovery(
                            cancelledSource, null, () -> List.of(UserMessage.from("恢复请求")),
                            () -> { }, cancellations::incrementAndGet, ignored -> hooks.incrementAndGet(),
                            ignored -> failures.incrementAndGet(),
                            (messages, generation) -> {
                                prepareEntered.countDown();
                                await(releasePrepare);
                                return () -> { };
                            })));
            assertTrue(prepareEntered.await(1, TimeUnit.SECONDS));
            cancelled.cancel();
            releasePrepare.countDown();
            submission.get(1, TimeUnit.SECONDS);
        }
        assertEquals(0, hooks.get());
        assertEquals(0, failures.get());
        assertEquals(1, cancellations.get());
    }

    @Test
    void 内部恢复SDK同步失败必须先发布启动钩子再调用失败处理器() {
        StreamingRequestController controller = activeController();
        long sourceGeneration = controller.latestModelRequestGeneration();
        assertEquals(CANCELLED, controller.cancelGenerationForRecovery(sourceGeneration));
        List<String> order = new ArrayList<>();
        GenerationAwareModelRequestOrchestrator orchestrator = new GenerationAwareModelRequestOrchestrator(
                controller, inlineAllowingGate(), action -> {
                    action.run();
                    return true;
                });

        orchestrator.submit(GenerationAwareModelRequestOrchestrator.recovery(
                sourceGeneration, null, () -> List.of(UserMessage.from("恢复请求")),
                () -> { }, () -> { }, generation -> order.add("started-" + generation),
                ignored -> order.add("failed"),
                (messages, generation) -> () -> {
                    order.add("sdk-" + generation);
                    throw new IllegalStateException("SDK 同步失败");
                }));

        assertEquals(List.of("started-2", "sdk-2", "failed"), order);
    }

    @Test
    void 内部恢复启动钩子抛错必须先尝试钩子再失败且不得启动SDK() {
        StreamingRequestController controller = activeController();
        long sourceGeneration = controller.latestModelRequestGeneration();
        assertEquals(CANCELLED, controller.cancelGenerationForRecovery(sourceGeneration));
        List<String> order = new ArrayList<>();
        GenerationAwareModelRequestOrchestrator orchestrator = new GenerationAwareModelRequestOrchestrator(
                controller, inlineAllowingGate(), action -> {
                    action.run();
                    return true;
                });

        orchestrator.submit(GenerationAwareModelRequestOrchestrator.recovery(
                sourceGeneration, null, () -> List.of(UserMessage.from("恢复请求")),
                () -> { }, () -> order.add("cancelled"), generation -> {
                    order.add("started-" + generation);
                    throw new IllegalStateException("启动钩子失败");
                }, ignored -> order.add("failed"),
                (messages, generation) -> () -> order.add("sdk-" + generation)));

        assertEquals(List.of("started-2", "failed"), order);
    }

    @Test
    void 相同恢复来源竞争时仅一个提交启动钩子和SDK() throws Exception {
        StreamingRequestController controller = activeController();
        long sourceGeneration = controller.latestModelRequestGeneration();
        assertEquals(CANCELLED, controller.cancelGenerationForRecovery(sourceGeneration));
        AtomicInteger hooks = new AtomicInteger();
        AtomicInteger starts = new AtomicInteger();
        GenerationAwareModelRequestOrchestrator orchestrator = new GenerationAwareModelRequestOrchestrator(
                controller, inlineAllowingGate(), action -> {
                    action.run();
                    return true;
                });
        java.util.concurrent.Callable<Void> submit = () -> {
            orchestrator.submit(GenerationAwareModelRequestOrchestrator.recovery(
                    sourceGeneration, null, () -> List.of(UserMessage.from("恢复请求")),
                    () -> { }, () -> { }, ignored -> hooks.incrementAndGet(),
                    ignored -> { }, (messages, generation) -> starts::incrementAndGet));
            return null;
        };

        race(submit, submit);

        assertEquals(1, hooks.get());
        assertEquals(1, starts.get());
        assertEquals(2, controller.modelRequestCount());
    }

    @Test
    void initial认领后启动前异常必须按新generation唯一收口() {
        StreamingRequestController controller = new StreamingRequestController();
        AtomicInteger failures = new AtomicInteger();
        GenerationAwareModelRequestOrchestrator orchestrator =
                new GenerationAwareModelRequestOrchestrator(
                        controller, null, null);

        assertDoesNotThrow(() -> orchestrator.submit(
                GenerationAwareModelRequestOrchestrator.initial(
                        null,
                        () -> List.of(UserMessage.from("初始请求")),
                        ignored -> failures.incrementAndGet(),
                        (messages, generation) -> {
                            throw new IllegalStateException("initial 启动前失败");
                        })));

        assertEquals(1, failures.get());
        assertEquals(1, controller.modelRequestCount());
        assertFalse(controller.isOpen());
    }

    @Test
    void continuation认领后启动前异常必须按新generation唯一收口() {
        StreamingRequestController controller = activeController();
        long sourceGeneration = controller.latestModelRequestGeneration();
        AtomicInteger failures = new AtomicInteger();
        GenerationAwareModelRequestOrchestrator orchestrator =
                new GenerationAwareModelRequestOrchestrator(
                        controller, null, null);

        assertDoesNotThrow(() -> orchestrator.submit(
                GenerationAwareModelRequestOrchestrator.continuation(
                        sourceGeneration,
                        null,
                        () -> List.of(UserMessage.from("续调请求")),
                        ignored -> failures.incrementAndGet(),
                        (messages, generation) -> {
                            throw new IllegalStateException(
                                    "continuation 启动前失败");
                        })));

        assertEquals(1, failures.get());
        assertEquals(2, controller.modelRequestCount());
        assertFalse(controller.isOpen());
    }

    @Test
    void recovery认领后启动前异常必须按新generation唯一收口() {
        StreamingRequestController controller = activeController();
        long sourceGeneration = controller.latestModelRequestGeneration();
        assertEquals(CANCELLED,
                controller.cancelGenerationForRecovery(sourceGeneration));
        AtomicInteger failures = new AtomicInteger();
        ModelRequestGate gate = inlineAllowingGate();
        GenerationAwareModelRequestOrchestrator orchestrator =
                new GenerationAwareModelRequestOrchestrator(
                        controller, gate, action -> {
                            action.run();
                            return true;
                        });

        assertDoesNotThrow(() -> orchestrator.submit(
                GenerationAwareModelRequestOrchestrator.recovery(
                        sourceGeneration,
                        null,
                        () -> List.of(UserMessage.from("恢复请求")),
                        () -> { },
                        ignored -> failures.incrementAndGet(),
                        (messages, generation) -> {
                            throw new IllegalStateException("recovery 启动前失败");
                        })));

        assertEquals(1, failures.get());
        assertEquals(2, controller.modelRequestCount());
        assertFalse(controller.isOpen());
    }

    @Test
    void claim后取消先提交时启动器不得执行() throws Exception {
        StreamingRequestController controller = new StreamingRequestController();
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        CountDownLatch starterEntered = new CountDownLatch(1);
        CountDownLatch allowStarterCommit = new CountDownLatch(1);
        GenerationAwareModelRequestOrchestrator orchestrator =
                new GenerationAwareModelRequestOrchestrator(
                        controller, null, null);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> submission = executor.submit(() -> orchestrator.submit(
                    GenerationAwareModelRequestOrchestrator.initial(
                            null,
                            () -> List.of(UserMessage.from("取消竞态")),
                            ignored -> failures.incrementAndGet(),
                            (messages, generation) -> {
                                starterEntered.countDown();
                                await(allowStarterCommit);
                                return starts::incrementAndGet;
                            })));
            assertTrue(starterEntered.await(1, TimeUnit.SECONDS));

            controller.cancel();
            allowStarterCommit.countDown();
            submission.get(1, TimeUnit.SECONDS);
        }

        assertEquals(0, starts.get(), "cancel 先赢后不得调用真实 SDK 启动器");
        assertEquals(0, failures.get(), "用户取消不是普通启动错误");
        assertTrue(controller.isCancelled());
    }

    @Test
    void recovery在第六十五次模型请求边界必须保留循环上限并唯一发布失败阶段() {
        StreamingRequestController controller = new StreamingRequestController();
        for (int count = 0;
             count < StreamingRequestController.MAX_MODEL_REQUESTS;
             count++) {
            assertTrue(controller.beforeModelRequest());
        }
        long sourceGeneration = controller.latestModelRequestGeneration();
        assertEquals(CANCELLED,
                controller.cancelGenerationForRecovery(sourceGeneration));
        List<ToolProtocolRecoveryPolicy.Phase> phases =
                new CopyOnWriteArrayList<>();
        ToolProtocolRecoveryCoordinator coordinator =
                new ToolProtocolRecoveryCoordinator(
                        new ToolProtocolRecoveryPolicy(
                                java.util.Set.of("writeFile"), phases::add),
                        java.util.Set.of("writeFile"));
        assertEquals(ToolProtocolRecoveryCoordinator.ViolationAction.START_RECOVERY,
                coordinator.claimViolation(sourceGeneration));
        coordinator.recoveryStarted();
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger starts = new AtomicInteger();
        GenerationAwareModelRequestOrchestrator orchestrator =
                new GenerationAwareModelRequestOrchestrator(
                        controller, inlineAllowingGate(), action -> {
                            action.run();
                            return true;
                        });

        orchestrator.submit(GenerationAwareModelRequestOrchestrator.recovery(
                sourceGeneration,
                null,
                () -> List.of(UserMessage.from("不应启动的恢复请求")),
                coordinator::failIfRecovering,
                ignored -> errors.incrementAndGet(),
                (messages, generation) -> starts::incrementAndGet));

        assertEquals(ToolLoopTerminationProtocol.ControlledTerminationReason
                        .LOOP_LIMIT_EXCEEDED,
                controller.controlledTermination().reason());
        assertEquals(List.of(
                ToolProtocolRecoveryPolicy.Phase.STARTED,
                ToolProtocolRecoveryPolicy.Phase.FAILED), phases);
        assertEquals(0, errors.get());
        assertEquals(0, starts.get());
        assertEquals(StreamingRequestController.MAX_MODEL_REQUESTS,
                controller.modelRequestCount());
    }

    @Test
    void recovery门禁返回取消必须唯一闭合失败阶段且不得启动模型() {
        StreamingRequestController controller = activeController();
        long sourceGeneration = controller.latestModelRequestGeneration();
        assertEquals(CANCELLED,
                controller.cancelGenerationForRecovery(sourceGeneration));
        List<ToolProtocolRecoveryPolicy.Phase> phases =
                new CopyOnWriteArrayList<>();
        ToolProtocolRecoveryCoordinator coordinator =
                recoveringCoordinator(sourceGeneration, phases);
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger terminations = new AtomicInteger();
        controller.onControlledTermination(ignored ->
                terminations.incrementAndGet());
        ModelRequestGate cancellingGate = inlineGate(new ModelRequestGate.Decision(
                ModelRequestGate.Status.CANCELLED,
                List.of(), 0, "用户已取消"));
        GenerationAwareModelRequestOrchestrator orchestrator =
                new GenerationAwareModelRequestOrchestrator(
                        controller, cancellingGate, action -> {
                            action.run();
                            return true;
                        });

        orchestrator.submit(GenerationAwareModelRequestOrchestrator.recovery(
                sourceGeneration,
                null,
                () -> List.of(UserMessage.from("不应启动的恢复请求")),
                coordinator::failIfRecovering,
                ignored -> errors.incrementAndGet(),
                (messages, generation) -> starts::incrementAndGet));
        controller.cancel();
        controller.dispatchClaimedTermination();

        assertEquals(List.of(
                ToolProtocolRecoveryPolicy.Phase.STARTED,
                ToolProtocolRecoveryPolicy.Phase.FAILED), phases);
        assertEquals(0, starts.get());
        assertEquals(0, errors.get(), "正常取消不得冒充普通错误");
        assertEquals(1, terminations.get(), "取消终态必须唯一派发");
        assertTrue(controller.isCancelled());
    }

    @Test
    void recovery回合门拒绝执行必须唯一闭合失败阶段且不得启动模型() {
        StreamingRequestController controller = activeController();
        long sourceGeneration = controller.latestModelRequestGeneration();
        assertEquals(CANCELLED,
                controller.cancelGenerationForRecovery(sourceGeneration));
        List<ToolProtocolRecoveryPolicy.Phase> phases =
                new CopyOnWriteArrayList<>();
        ToolProtocolRecoveryCoordinator coordinator =
                recoveringCoordinator(sourceGeneration, phases);
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger terminations = new AtomicInteger();
        controller.onControlledTermination(ignored ->
                terminations.incrementAndGet());
        GenerationAwareModelRequestOrchestrator orchestrator =
                new GenerationAwareModelRequestOrchestrator(
                        controller,
                        inlineGate(new ModelRequestGate.Decision(
                                ModelRequestGate.Status.ALLOWED,
                                List.<ChatMessage>of(UserMessage.from("门禁后请求")),
                                1,
                                "")),
                        action -> false);

        orchestrator.submit(GenerationAwareModelRequestOrchestrator.recovery(
                sourceGeneration,
                null,
                () -> List.of(UserMessage.from("不应启动的恢复请求")),
                coordinator::failIfRecovering,
                ignored -> errors.incrementAndGet(),
                (messages, generation) -> starts::incrementAndGet));
        controller.cancel();
        controller.dispatchClaimedTermination();

        assertEquals(List.of(
                ToolProtocolRecoveryPolicy.Phase.STARTED,
                ToolProtocolRecoveryPolicy.Phase.FAILED), phases);
        assertEquals(0, starts.get());
        assertEquals(0, errors.get(), "回合门取消不得冒充普通错误");
        assertEquals(1, terminations.get(), "取消终态必须唯一派发");
        assertTrue(controller.isCancelled());
    }

    @Test
    void recovery来源检查后全局取消抢先仍必须唯一闭合失败阶段() {
        StreamingRequestController controller = activeController();
        long sourceGeneration = controller.latestModelRequestGeneration();
        assertEquals(CANCELLED,
                controller.cancelGenerationForRecovery(sourceGeneration));
        List<ToolProtocolRecoveryPolicy.Phase> phases =
                new CopyOnWriteArrayList<>();
        ToolProtocolRecoveryCoordinator coordinator =
                recoveringCoordinator(sourceGeneration, phases);
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger terminations = new AtomicInteger();
        controller.onControlledTermination(ignored ->
                terminations.incrementAndGet());
        GenerationAwareModelRequestOrchestrator orchestrator =
                new GenerationAwareModelRequestOrchestrator(
                        controller,
                        inlineGate(new ModelRequestGate.Decision(
                                ModelRequestGate.Status.ALLOWED,
                                List.<ChatMessage>of(UserMessage.from("门禁后请求")),
                                1,
                                "")),
                        action -> {
                            controller.cancel();
                            return false;
                        });

        orchestrator.submit(GenerationAwareModelRequestOrchestrator.recovery(
                sourceGeneration,
                null,
                () -> List.of(UserMessage.from("不应启动的恢复请求")),
                coordinator::failIfRecovering,
                ignored -> errors.incrementAndGet(),
                (messages, generation) -> starts::incrementAndGet));
        controller.dispatchClaimedTermination();

        assertEquals(List.of(
                ToolProtocolRecoveryPolicy.Phase.STARTED,
                ToolProtocolRecoveryPolicy.Phase.FAILED), phases);
        assertEquals(0, starts.get());
        assertEquals(0, errors.get(), "全局取消不得冒充普通错误");
        assertEquals(1, terminations.get(), "全局取消终态必须唯一派发");
        assertTrue(controller.isCancelled());
    }

    @Test
    void recovery异步门禁等待时全局取消后迟到结果必须唯一闭合失败阶段()
            throws Exception {
        StreamingRequestController controller = activeController();
        long sourceGeneration = controller.latestModelRequestGeneration();
        assertEquals(CANCELLED,
                controller.cancelGenerationForRecovery(sourceGeneration));
        List<ToolProtocolRecoveryPolicy.Phase> phases =
                new CopyOnWriteArrayList<>();
        ToolProtocolRecoveryCoordinator coordinator =
                recoveringCoordinator(sourceGeneration, phases);
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger terminations = new AtomicInteger();
        controller.onControlledTermination(ignored ->
                terminations.incrementAndGet());
        CompletableFuture<ModelRequestGate.Decision> preparation =
                new CompletableFuture<>();
        CountDownLatch completionObserved = new CountDownLatch(1);
        ModelRequestGate asynchronousGate = new ModelRequestGate() {
            @Override
            public java.util.concurrent.CompletionStage<Decision> prepare(
                    Request request) {
                return preparation;
            }

            @Override
            public java.util.concurrent.CompletionStage<DispatchStatus> onPrepared(
                    java.util.concurrent.CompletionStage<Decision> prepared,
                    java.util.function.BiConsumer<Decision, Throwable> completion) {
                prepared.whenComplete((decision, failure) -> {
                    completion.accept(decision, failure);
                    completionObserved.countDown();
                });
                return CompletableFuture.completedFuture(
                        DispatchStatus.DISPATCHED);
            }
        };
        GenerationAwareModelRequestOrchestrator orchestrator =
                new GenerationAwareModelRequestOrchestrator(
                        controller, asynchronousGate, action -> {
                            action.run();
                            return true;
                        });

        orchestrator.submit(GenerationAwareModelRequestOrchestrator.recovery(
                sourceGeneration,
                null,
                () -> List.of(UserMessage.from("不应启动的恢复请求")),
                coordinator::failIfRecovering,
                ignored -> errors.incrementAndGet(),
                (messages, generation) -> starts::incrementAndGet));
        controller.cancel();
        preparation.complete(new ModelRequestGate.Decision(
                ModelRequestGate.Status.ALLOWED,
                List.<ChatMessage>of(UserMessage.from("迟到门禁结果")),
                1,
                ""));
        assertTrue(completionObserved.await(1, TimeUnit.SECONDS));
        controller.cancel();
        controller.dispatchClaimedTermination();

        assertEquals(List.of(
                ToolProtocolRecoveryPolicy.Phase.STARTED,
                ToolProtocolRecoveryPolicy.Phase.FAILED), phases);
        assertEquals(0, starts.get());
        assertEquals(0, errors.get(), "全局取消不得冒充普通错误");
        assertEquals(1, terminations.get(), "全局取消终态必须唯一派发");
        assertTrue(controller.isCancelled());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(DispatchTailFailure.class)
    void recovery模型启动已提交后门禁尾部失败不得误闭合恢复阶段(
            DispatchTailFailure tailFailure) {
        StreamingRequestController controller = activeController();
        long sourceGeneration = controller.latestModelRequestGeneration();
        assertEquals(CANCELLED,
                controller.cancelGenerationForRecovery(sourceGeneration));
        List<ToolProtocolRecoveryPolicy.Phase> phases =
                new CopyOnWriteArrayList<>();
        ToolProtocolRecoveryCoordinator coordinator =
                recoveringCoordinator(sourceGeneration, phases);
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        ModelRequestGate gate = new ModelRequestGate() {
            @Override
            public java.util.concurrent.CompletionStage<Decision> prepare(
                    Request request) {
                return CompletableFuture.completedFuture(new Decision(
                        Status.ALLOWED,
                        List.<ChatMessage>of(UserMessage.from("门禁后请求")),
                        1,
                        ""));
            }

            @Override
            public java.util.concurrent.CompletionStage<DispatchStatus> onPrepared(
                    java.util.concurrent.CompletionStage<Decision> preparation,
                    java.util.function.BiConsumer<Decision, Throwable> completion) {
                preparation.whenComplete(completion);
                return tailFailure.result();
            }
        };
        GenerationAwareModelRequestOrchestrator orchestrator =
                new GenerationAwareModelRequestOrchestrator(
                        controller, gate, action -> {
                            action.run();
                            return true;
                        });

        orchestrator.submit(GenerationAwareModelRequestOrchestrator.recovery(
                sourceGeneration,
                null,
                () -> List.of(UserMessage.from("恢复请求")),
                coordinator::failIfRecovering,
                ignored -> errors.incrementAndGet(),
                (messages, generation) -> starts::incrementAndGet));
        coordinator.recovered();

        assertEquals(List.of(
                ToolProtocolRecoveryPolicy.Phase.STARTED,
                ToolProtocolRecoveryPolicy.Phase.RECOVERED), phases);
        assertEquals(1, starts.get());
        assertEquals(0, errors.get(),
                "已启动请求后的门禁尾部故障不得冒充请求失败");
        assertEquals(2, controller.modelRequestCount());
        assertTrue(controller.isOpen());
    }

    @Test
    void 撤销后仅恢复来源专用门可推进且普通当前代门继续拒绝() {
        StreamingRequestController controller = activeController();
        long generation = controller.latestModelRequestGeneration();
        assertEquals(CANCELLED,
                controller.cancelGenerationForRecovery(generation));

        assertFalse(controller.isCurrentGeneration(generation));
        assertTrue(controller.isRecoverySourceGeneration(generation));
    }

    @Test
    void 取消终态或已推进新代后恢复来源专用门必须拒绝() {
        StreamingRequestController cancelled = activeController();
        long cancelledGeneration = cancelled.latestModelRequestGeneration();
        assertEquals(CANCELLED, cancelled.cancelGenerationForRecovery(
                cancelledGeneration));
        cancelled.cancel();
        assertFalse(cancelled.isRecoverySourceGeneration(
                cancelledGeneration));

        StreamingRequestController terminated = activeController();
        long terminatedGeneration = terminated.latestModelRequestGeneration();
        assertEquals(CANCELLED, terminated.cancelGenerationForRecovery(
                terminatedGeneration));
        assertTrue(terminated.terminate(controlledTermination()));
        assertFalse(terminated.isRecoverySourceGeneration(
                terminatedGeneration));

        StreamingRequestController advanced = activeController();
        long advancedGeneration = advanced.latestModelRequestGeneration();
        assertEquals(CANCELLED, advanced.cancelGenerationForRecovery(
                advancedGeneration));
        assertTrue(advanced.beforeModelRequest(advancedGeneration));
        assertFalse(advanced.isRecoverySourceGeneration(
                advancedGeneration));
    }

    @Test
    void 并发两次恢复提交只能一个真正推进下一代() throws Exception {
        for (int iteration = 0; iteration < 100; iteration++) {
            StreamingRequestController controller = activeController();
            long generation = controller.latestModelRequestGeneration();
            assertEquals(CANCELLED,
                    controller.cancelGenerationForRecovery(generation));
            RaceResult<StreamingRequestController.ModelRequestClaim,
                    StreamingRequestController.ModelRequestClaim> race = race(
                    () -> controller.claimRecoveryModelRequest(generation),
                    () -> controller.claimRecoveryModelRequest(generation));

            assertEquals(1, java.util.stream.Stream.of(
                            race.first(), race.second())
                    .filter(java.util.Objects::nonNull)
                    .count());
            assertEquals(2, controller.modelRequestCount());
        }
    }

    @Test
    void 当前代物理取消异常不回滚恢复撤销语义() {
        StreamingRequestController controller = activeController();
        long oldGeneration = controller.latestModelRequestGeneration();
        AtomicInteger lateHandleCancellations = new AtomicInteger();
        controller.registerRequestHandle(oldGeneration, () -> {
            throw new RuntimeException("取消底层流失败");
        });

        StreamingRequestController.GenerationCancellation cancellation =
                assertDoesNotThrow(() -> controller
                        .cancelGenerationForRecovery(oldGeneration));

        assertEquals(CANCELLED, cancellation);
        assertTrue(controller.isOpen(), "物理取消失败不能终止整个用户回合");
        assertNull(controller.enterCallback(oldGeneration));
        assertFalse(controller.isCurrentGeneration(oldGeneration));
        controller.registerRequestHandle(
                oldGeneration, lateHandleCancellations::incrementAndGet);
        assertEquals(1, lateHandleCancellations.get(), "旧代迟到 handle 必须立即取消");
        assertTrue(controller.beforeModelRequest(oldGeneration),
                "刚撤销的旧代仍应能推进一次恢复请求");
    }

    @Test
    void 撤销当前代后整轮保持活动且旧代所有入口失效() {
        StreamingRequestController controller = new StreamingRequestController();
        AtomicInteger activeHandleCancellations = new AtomicInteger();
        AtomicInteger lateHandleCancellations = new AtomicInteger();

        assertTrue(controller.beforeModelRequest());
        long firstGeneration = controller.latestModelRequestGeneration();
        assertEquals(1L, firstGeneration);
        controller.registerRequestHandle(
                firstGeneration, activeHandleCancellations::incrementAndGet);

        assertEquals(CANCELLED,
                controller.cancelGenerationForRecovery(firstGeneration));

        assertTrue(controller.isOpen(), "generation 撤销不能终止整个用户回合");
        assertEquals(1, activeHandleCancellations.get());
        assertNull(controller.enterCallback(firstGeneration));
        assertFalse(controller.isCurrentGeneration(firstGeneration));
        controller.registerRequestHandle(
                firstGeneration, lateHandleCancellations::incrementAndGet);
        assertEquals(1, lateHandleCancellations.get(), "旧代迟到 handle 必须立即取消");
        assertEquals(REJECTED,
                controller.cancelGenerationForRecovery(firstGeneration));
        assertEquals(REJECTED,
                controller.cancelGenerationForRecovery(firstGeneration + 1));
    }

    @Test
    void 旧代未来代和撤销代均不能进入回调执行动作或保留句柄() {
        StreamingRequestController oldGenerationController = activeController();
        long oldGeneration = oldGenerationController
                .latestModelRequestGeneration();
        assertTrue(oldGenerationController.beforeModelRequest());
        assertRejectedAtEveryGenerationEntry(
                oldGenerationController, oldGeneration);

        StreamingRequestController futureGenerationController =
                activeController();
        long futureGeneration = futureGenerationController
                .latestModelRequestGeneration() + 1L;
        assertRejectedAtEveryGenerationEntry(
                futureGenerationController, futureGeneration);

        StreamingRequestController cancelledGenerationController =
                activeController();
        long cancelledGeneration = cancelledGenerationController
                .latestModelRequestGeneration();
        assertEquals(CANCELLED, cancelledGenerationController
                .cancelGenerationForRecovery(cancelledGeneration));
        assertRejectedAtEveryGenerationEntry(
                cancelledGenerationController, cancelledGeneration);
    }

    @Test
    void 恢复请求成功启动下一代并计入模型请求总数() {
        StreamingRequestController controller = activeController();
        long firstGeneration = controller.latestModelRequestGeneration();
        assertEquals(CANCELLED,
                controller.cancelGenerationForRecovery(firstGeneration));

        assertTrue(controller.beforeModelRequest(firstGeneration));
        long recoveryGeneration = controller.latestModelRequestGeneration();

        assertEquals(2L, recoveryGeneration);
        assertEquals(2, controller.modelRequestCount());
        assertFalse(controller.beforeModelRequest(firstGeneration),
                "同一旧代只能启动一次恢复请求，不能错误推进第三代");
        assertEquals(2, controller.modelRequestCount());
        assertTrue(controller.isCurrentGeneration(recoveryGeneration));
        try (StreamingRequestController.CallbackTicket ignored =
                     controller.enterCallback(recoveryGeneration)) {
            assertTrue(controller.isOpen());
        }
    }

    @Test
    void 终态先获胜时恢复撤销一律拒绝() {
        StreamingRequestController cancelled = activeController();
        long cancelledGeneration = cancelled.latestModelRequestGeneration();
        cancelled.cancel();
        assertEquals(REJECTED,
                cancelled.cancelGenerationForRecovery(cancelledGeneration));

        StreamingRequestController terminated = activeController();
        long terminatedGeneration = terminated.latestModelRequestGeneration();
        assertTrue(terminated.terminate(new ToolLoopTerminationProtocol
                .ControlledTermination(RESOURCE_LIMIT_EXCEEDED, null)));
        assertEquals(REJECTED,
                terminated.cancelGenerationForRecovery(terminatedGeneration));

        StreamingRequestController completed = activeController();
        long completedGeneration = completed.latestModelRequestGeneration();
        assertTrue(completed.completeNormally());
        assertEquals(REJECTED,
                completed.cancelGenerationForRecovery(completedGeneration));

        StreamingRequestController normalCompleting = activeController();
        long normalCompletingGeneration =
                normalCompleting.latestModelRequestGeneration();
        assertTrue(normalCompleting.claimNormalCompletion());
        assertEquals(REJECTED, normalCompleting.cancelGenerationForRecovery(
                normalCompletingGeneration));
    }

    @Test
    void 恢复撤销先获胜后旧代不能再发起第二次恢复() {
        StreamingRequestController controller = activeController();
        long generation = controller.latestModelRequestGeneration();

        assertEquals(CANCELLED,
                controller.cancelGenerationForRecovery(generation));
        assertEquals(REJECTED,
                controller.cancelGenerationForRecovery(generation));
        assertTrue(controller.completeNormally(), "回合保持 ACTIVE 后仍可正常完成");
    }

    @Test
    void 当前代回调打开时撤销可线性化且后续终态仍等待回调排空() {
        StreamingRequestController controller = activeController();
        long generation = controller.latestModelRequestGeneration();
        AtomicInteger notifications = new AtomicInteger();
        controller.onControlledTermination(
                ignored -> notifications.incrementAndGet());
        StreamingRequestController.CallbackTicket callback =
                controller.enterCallback(generation);

        assertEquals(CANCELLED,
                controller.cancelGenerationForRecovery(generation));
        assertTrue(controller.isOpen());
        controller.cancel();
        assertEquals(0, notifications.get(), "终态通知必须继续等待已登记回调退出");
        callback.close();
        assertEquals(1, notifications.get());
    }

    @Test
    void 恢复就绪必须同时等待来源回调与结构化工具批次闭合()
            throws Exception {
        StreamingRequestController controller = activeController();
        long generation = controller.latestModelRequestGeneration();
        StreamingRequestController.CallbackTicket callback =
                controller.enterCallback(generation);
        StreamingRequestController.ToolBatchTicket batch =
                controller.prepareToolBatch(generation, 1);
        assertTrue(controller.tryStartToolBatchWrite(batch));
        assertTrue(controller.commitToolBatch(batch));
        assertEquals(CANCELLED,
                controller.cancelGenerationForRecovery(generation));
        CompletableFuture<StreamingRequestController.RecoveryReadiness>
                readiness = controller.awaitRecoveryReadiness(generation)
                .toCompletableFuture();

        assertFalse(readiness.isDone());
        callback.close();
        assertFalse(readiness.isDone(), "回调退出后仍必须等待工具结果配对");

        assertEquals(StreamingRequestController.ToolExecutionDecision.CANCELLED,
                controller.claimToolExecution(batch, 0));
        StreamingRequestController.ToolResultClaim result =
                controller.prepareToolResult(batch, 0, null);
        assertEquals(StreamingRequestController.ToolResultDecision.CANCELLED,
                controller.commitToolResult(batch, 0, result));
        assertFalse(controller.finishToolBatch(batch));

        assertEquals(StreamingRequestController.RecoveryReadiness.READY,
                readiness.get(1, TimeUnit.SECONDS));
    }

    @Test
    void 恢复等待期间全局取消必须立即返回取消终态且不等待工具批次()
            throws Exception {
        StreamingRequestController controller = activeController();
        long generation = controller.latestModelRequestGeneration();
        StreamingRequestController.ToolBatchTicket batch =
                controller.prepareToolBatch(generation, 1);
        assertTrue(controller.tryStartToolBatchWrite(batch));
        assertTrue(controller.commitToolBatch(batch));
        assertEquals(CANCELLED,
                controller.cancelGenerationForRecovery(generation));
        CompletableFuture<StreamingRequestController.RecoveryReadiness>
                readiness = controller.awaitRecoveryReadiness(generation)
                .toCompletableFuture();
        assertFalse(readiness.isDone());

        controller.cancel();

        assertEquals(StreamingRequestController.RecoveryReadiness
                        .CANCELLED_OR_TERMINATED,
                readiness.get(1, TimeUnit.SECONDS));
    }

    @Test
    void 恢复等待期间新generation推进必须立即返回启动后过期()
            throws Exception {
        StreamingRequestController controller = activeController();
        long generation = controller.latestModelRequestGeneration();
        try (StreamingRequestController.CallbackTicket ignored =
                     controller.enterCallback(generation)) {
            assertEquals(CANCELLED,
                    controller.cancelGenerationForRecovery(generation));
            CompletableFuture<StreamingRequestController.RecoveryReadiness>
                    readiness = controller.awaitRecoveryReadiness(generation)
                    .toCompletableFuture();
            assertFalse(readiness.isDone());

            assertTrue(controller.claimRecoveryModelRequest(generation) != null);

            assertEquals(StreamingRequestController.RecoveryReadiness
                            .STALE_AFTER_START,
                    readiness.get(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void 多个恢复等待者由同一取消源完成且完成回调均在锁外运行()
            throws Exception {
        StreamingRequestController controller = activeController();
        long generation = controller.latestModelRequestGeneration();
        StreamingRequestController.CallbackTicket callback =
                controller.enterCallback(generation);
        assertEquals(CANCELLED,
                controller.cancelGenerationForRecovery(generation));
        List<CompletableFuture<StreamingRequestController.RecoveryReadiness>>
                readiness = java.util.stream.IntStream.range(0, 3)
                .mapToObj(ignored -> controller
                        .awaitRecoveryReadiness(generation)
                        .thenApply(result -> {
                            assertFalse(Thread.holdsLock(controller),
                                    "恢复就绪 continuation 不得在锁内执行");
                            return result;
                        })
                        .toCompletableFuture())
                .toList();

        callback.close();

        for (CompletableFuture<StreamingRequestController.RecoveryReadiness>
                future : readiness) {
            assertEquals(StreamingRequestController.RecoveryReadiness.READY,
                    future.get(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void 两个恢复线程同时起跑时只有一个成功() throws Exception {
        for (int iteration = 0; iteration < 100; iteration++) {
            StreamingRequestController controller = activeController();
            long generation = controller.latestModelRequestGeneration();
            RaceResult<StreamingRequestController.GenerationCancellation,
                    StreamingRequestController.GenerationCancellation> race =
                    race(
                            () -> controller.cancelGenerationForRecovery(
                                    generation),
                            () -> controller.cancelGenerationForRecovery(
                                    generation));

            assertEquals(1, java.util.stream.Stream.of(
                            race.first(), race.second())
                    .filter(CANCELLED::equals)
                    .count());
            assertTrue(controller.isOpen());
        }
    }

    @Test
    void 普通完成与恢复通过当前代原子门竞争时只有一个成功() throws Exception {
        for (int iteration = 0; iteration < 100; iteration++) {
            StreamingRequestController controller = activeController();
            long generation = controller.latestModelRequestGeneration();
            RaceResult<Boolean,
                    StreamingRequestController.GenerationCancellation> race =
                    race(
                            () -> controller.claimNormalCompletion(generation),
                            () -> controller.cancelGenerationForRecovery(
                                    generation));

            assertEquals(1, (race.first() ? 1 : 0)
                    + (race.second() == CANCELLED ? 1 : 0));
        }
    }

    @Test
    void 受控终止与恢复通过当前代原子门竞争时只有一个成功() throws Exception {
        for (int iteration = 0; iteration < 100; iteration++) {
            StreamingRequestController controller = activeController();
            long generation = controller.latestModelRequestGeneration();
            RaceResult<Boolean,
                    StreamingRequestController.GenerationCancellation> race =
                    race(
                            () -> controller.claimControlledTermination(
                                    generation, controlledTermination()),
                            () -> controller.cancelGenerationForRecovery(
                                    generation));

            if (race.first()) {
                controller.dispatchClaimedTermination();
            }
            assertEquals(1, (race.first() ? 1 : 0)
                    + (race.second() == CANCELLED ? 1 : 0));
        }
    }

    @Test
    void 全局取消与恢复竞争后最终取消关闭唯一推进路径() throws Exception {
        for (int iteration = 0; iteration < 100; iteration++) {
            StreamingRequestController controller = activeController();
            long generation = controller.latestModelRequestGeneration();
            RaceResult<Boolean,
                    StreamingRequestController.GenerationCancellation> race =
                    race(
                            () -> {
                                controller.cancel();
                                return controller.isCancelled();
                            },
                            () -> controller.cancelGenerationForRecovery(
                                    generation));

            assertTrue(race.first());
            assertTrue(controller.isCancelled());
            assertFalse(controller.beforeModelRequest(generation));
            assertNull(controller.enterCallback(generation));
            assertFalse(controller.isCurrentGeneration(generation));
        }
    }

    @Test
    void 恢复成功后下一代启动前全局取消可覆盖() {
        StreamingRequestController controller = activeController();
        long generation = controller.latestModelRequestGeneration();
        assertEquals(CANCELLED,
                controller.cancelGenerationForRecovery(generation));

        controller.cancel();

        assertTrue(controller.isCancelled());
        assertFalse(controller.beforeModelRequest(generation));
        assertNull(controller.enterCallback(generation));
        assertFalse(controller.isCurrentGeneration(generation));
    }

    private <F, S> RaceResult<F, S> race(
            java.util.concurrent.Callable<F> first,
            java.util.concurrent.Callable<S> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<F> firstFuture = executor.submit(() -> {
                start.await();
                return first.call();
            });
            Future<S> secondFuture = executor.submit(() -> {
                start.await();
                return second.call();
            });
            start.countDown();
            return new RaceResult<>(
                    firstFuture.get(2, TimeUnit.SECONDS),
                    secondFuture.get(2, TimeUnit.SECONDS));
        }
    }

    private StreamingRequestController activeController() {
        StreamingRequestController controller = new StreamingRequestController();
        assertTrue(controller.beforeModelRequest());
        return controller;
    }

    private ModelRequestGate inlineAllowingGate() {
        return inlineGate(new ModelRequestGate.Decision(
                ModelRequestGate.Status.ALLOWED,
                List.<ChatMessage>of(UserMessage.from("门禁后请求")),
                1,
                ""));
    }

    private enum DispatchTailFailure {
        THROWING,
        NULL_RESULT,
        REJECTED,
        EXCEPTIONAL;

        private java.util.concurrent.CompletionStage<
                ModelRequestGate.DispatchStatus> result() {
            return switch (this) {
                case THROWING -> throw new IllegalStateException(
                        "门禁同步回调后抛异常");
                case NULL_RESULT -> null;
                case REJECTED -> CompletableFuture.completedFuture(
                        ModelRequestGate.DispatchStatus.REJECTED);
                case EXCEPTIONAL -> CompletableFuture.failedFuture(
                        new IllegalStateException("门禁调度 Future 失败"));
            };
        }
    }

    private ModelRequestGate inlineGate(ModelRequestGate.Decision decision) {
        return new ModelRequestGate() {
            @Override
            public java.util.concurrent.CompletionStage<Decision> prepare(
                    Request request) {
                return CompletableFuture.completedFuture(decision);
            }

            @Override
            public java.util.concurrent.CompletionStage<DispatchStatus> onPrepared(
                    java.util.concurrent.CompletionStage<Decision> preparation,
                    java.util.function.BiConsumer<Decision, Throwable> completion) {
                preparation.whenComplete(completion);
                return CompletableFuture.completedFuture(
                        DispatchStatus.DISPATCHED);
            }
        };
    }

    private ToolProtocolRecoveryCoordinator recoveringCoordinator(
            long sourceGeneration,
            List<ToolProtocolRecoveryPolicy.Phase> phases) {
        ToolProtocolRecoveryCoordinator coordinator =
                new ToolProtocolRecoveryCoordinator(
                        new ToolProtocolRecoveryPolicy(
                                java.util.Set.of("writeFile"), phases::add),
                        java.util.Set.of("writeFile"));
        assertEquals(ToolProtocolRecoveryCoordinator.ViolationAction.START_RECOVERY,
                coordinator.claimViolation(sourceGeneration));
        coordinator.recoveryStarted();
        return coordinator;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("等待测试闩锁超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待测试闩锁被中断", exception);
        }
    }

    private void assertRejectedAtEveryGenerationEntry(
            StreamingRequestController controller, long generation) {
        AtomicInteger cancellations = new AtomicInteger();

        assertNull(controller.enterCallback(generation));
        assertFalse(controller.isCurrentGeneration(generation));
        controller.registerRequestHandle(
                generation, cancellations::incrementAndGet);

        assertEquals(1, cancellations.get(), "非当前代 handle 必须立即取消");
    }

    private ToolLoopTerminationProtocol.ControlledTermination
            controlledTermination() {
        return new ToolLoopTerminationProtocol.ControlledTermination(
                RESOURCE_LIMIT_EXCEEDED, null);
    }

    private record RaceResult<F, S>(F first, S second) {
    }
}
