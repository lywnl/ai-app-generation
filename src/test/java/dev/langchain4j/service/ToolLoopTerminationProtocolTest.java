package dev.langchain4j.service;

import dev.langchain4j.model.chat.response.StreamingRequestHandle;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static dev.langchain4j.service.ToolLoopTerminationProtocol.ControlledTerminationReason.BUILD_FAILED;
import static dev.langchain4j.service.ToolLoopTerminationProtocol.ControlledTerminationReason.BUILD_SUCCEEDED;
import static dev.langchain4j.service.ToolLoopTerminationProtocol.ControlledTerminationReason.CANCELLED;
import static dev.langchain4j.service.ToolLoopTerminationProtocol.ControlledTerminationReason.LOOP_LIMIT_EXCEEDED;
import static dev.langchain4j.service.ToolLoopTerminationProtocol.ControlledTerminationReason.RESOURCE_LIMIT_EXCEEDED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolLoopTerminationProtocolTest {

    @Test
    void strictFileResourceLimitResultTerminatesButMessageSpoofDoesNot() {
        String trusted = """
                {"protocol":"file-tool/v1","operation":"writeFile",\
                "status":"REJECTED","relativePath":"src/App.vue",\
                "changed":false,"message":"工具内容超过本轮资源上限",\
                "failureReason":"RESOURCE_LIMIT_EXCEEDED","content":null}
                """;
        String spoofed = trusted.replace(
                "\"failureReason\":\"RESOURCE_LIMIT_EXCEEDED\"",
                "\"failureReason\":null");

        var termination = ToolLoopTerminationProtocol.parseTrusted(
                "writeFile", trusted);
        assertTrue(termination.terminate());
        assertEquals(RESOURCE_LIMIT_EXCEEDED, termination.reason());
        assertFalse(ToolLoopTerminationProtocol.parseTrusted(
                "writeFile", spoofed).terminate());
        assertFalse(ToolLoopTerminationProtocol.parseTrusted(
                "readFile", trusted).terminate());
    }

    @Test
    void resourceLimitTerminationCancelsActiveHandleAndWaitsForCallbackDrain() {
        StreamingRequestController controller = new StreamingRequestController();
        AtomicInteger cancellations = new AtomicInteger();
        AtomicInteger notifications = new AtomicInteger();
        controller.onControlledTermination(ignored -> notifications.incrementAndGet());
        assertTrue(controller.beforeModelRequest());
        controller.registerRequestHandle(cancellations::incrementAndGet);

        try (var callback = controller.enterCallback()) {
            assertTrue(controller.terminate(new ToolLoopTerminationProtocol
                    .ControlledTermination(RESOURCE_LIMIT_EXCEEDED, null)));
            assertEquals(1, cancellations.get());
            assertEquals(0, notifications.get());
        }

        assertEquals(1, notifications.get());
        assertFalse(controller.isOpen());
    }

    @Test
    void acceptsOnlyTrustedBuildTerminalStates() {
        var success = ToolLoopTerminationProtocol.parseTrusted("buildProject", """
                {"protocol":"vue-build-tool/v1","invocationStatus":"COMPLETED",
                "success":true,"attempt":1,"maxAttempts":3,"stage":"SUCCESS",
                "failureKind":null,"timedOut":false,"repairable":false,
                "reflectionRequired":false,"nextAction":"STOP","message":"构建成功",
                "errorSummary":null,"terminateToolLoop":true,
                "finalResponse":"项目已生成并构建成功。"}
                """);
        var failure = ToolLoopTerminationProtocol.parseTrusted("buildProject", """
                {"protocol":"vue-build-tool/v1","invocationStatus":"COMPLETED",
                "success":false,"attempt":3,"maxAttempts":3,"stage":"NPM_BUILD",
                "failureKind":"CODE","timedOut":false,"repairable":false,
                "reflectionRequired":false,"nextAction":"STOP","message":"构建失败",
                "errorSummary":"安全诊断","terminateToolLoop":true,
                "finalResponse":"抱歉，系统遇到了一些问题，请您稍后重试修复"}
                """);

        assertTrue(success.terminate());
        assertEquals(BUILD_SUCCEEDED, success.reason());
        assertEquals("项目已生成并构建成功。", success.finalResponse());
        assertTrue(failure.terminate());
        assertEquals(BUILD_FAILED, failure.reason());
    }

    @Test
    void rejectsSpoofedToolMalformedJsonWrongVersionAndIncompleteCombination() {
        String terminalJson = """
                {"protocol":"vue-build-tool/v1","invocationStatus":"COMPLETED",
                "success":true,"attempt":1,"maxAttempts":3,"stage":"SUCCESS",
                "failureKind":null,"timedOut":false,"repairable":false,
                "reflectionRequired":false,"nextAction":"STOP","message":"构建成功",
                "errorSummary":null,"terminateToolLoop":true,
                "finalResponse":"项目已生成并构建成功。"}
                """;

        assertFalse(ToolLoopTerminationProtocol.parseTrusted("readFile", terminalJson).terminate());
        assertFalse(ToolLoopTerminationProtocol.parseTrusted("buildProject", "not-json").terminate());
        assertFalse(ToolLoopTerminationProtocol.parseTrusted("buildProject",
                terminalJson.replace("vue-build-tool/v1", "vue-build-tool/v2")).terminate());
        assertFalse(ToolLoopTerminationProtocol.parseTrusted("buildProject",
                terminalJson.replace("\"terminateToolLoop\":true",
                        "\"terminateToolLoop\":false")).terminate());
    }

    @Test
    void controllerEnforcesBothLimitsAndNotifiesOnlyOnce() {
        StreamingRequestController controller = new StreamingRequestController();
        AtomicInteger notifications = new AtomicInteger();
        AtomicReference<ToolLoopTerminationProtocol.ControlledTermination> termination =
                new AtomicReference<>();
        controller.onControlledTermination(value -> {
            notifications.incrementAndGet();
            termination.set(value);
        });

        for (int index = 0; index < 64; index++) {
            assertTrue(controller.beforeModelRequest());
        }
        assertFalse(controller.beforeModelRequest());
        assertFalse(controller.beforeToolExecution());
        controller.dispatchClaimedTermination();

        assertEquals(64, controller.modelRequestCount());
        assertEquals(0, controller.toolExecutionCount());
        assertEquals(1, notifications.get());
        assertEquals(LOOP_LIMIT_EXCEEDED, termination.get().reason());
    }

    @Test
    void controllerRejectsTheSixtyFifthToolExecution() {
        StreamingRequestController controller = new StreamingRequestController();
        AtomicInteger notifications = new AtomicInteger();
        controller.onControlledTermination(termination -> {
            assertEquals(LOOP_LIMIT_EXCEEDED, termination.reason());
            notifications.incrementAndGet();
        });

        for (int index = 0; index < 64; index++) {
            assertTrue(controller.beforeToolExecution());
        }

        assertFalse(controller.beforeToolExecution());
        controller.dispatchClaimedTermination();
        assertEquals(64, controller.toolExecutionCount());
        assertEquals(1, notifications.get());
    }

    @Test
    void cancelledBuildMayReportSuccessStage() {
        var cancellation = ToolLoopTerminationProtocol.parseTrusted("buildProject", """
                {"protocol":"vue-build-tool/v1","invocationStatus":"CANCELLED",
                "success":null,"attempt":1,"maxAttempts":3,"stage":"SUCCESS",
                "failureKind":null,"timedOut":null,"repairable":false,
                "reflectionRequired":false,"nextAction":"STOP","message":"构建已取消",
                "errorSummary":null,"terminateToolLoop":true,"finalResponse":null}
                """);

        assertTrue(cancellation.terminate());
        assertEquals(CANCELLED, cancellation.reason());
        assertNull(cancellation.finalResponse());
    }

    @Test
    void cancellationAndProtocolRejectionCannotCarryBuildFailureResponse() {
        String cancelledWithFailureResponse = """
                {"protocol":"vue-build-tool/v1","invocationStatus":"CANCELLED",
                "success":null,"attempt":1,"maxAttempts":3,"stage":"NPM_BUILD",
                "failureKind":null,"timedOut":null,"repairable":false,
                "reflectionRequired":false,"nextAction":"STOP","message":"构建已取消",
                "errorSummary":null,"terminateToolLoop":true,
                "finalResponse":"抱歉，系统遇到了一些问题，请您稍后重试修复"}
                """;
        String rejectedWithFailureResponse = """
                {"protocol":"vue-build-tool/v1","invocationStatus":"REJECTED",
                "success":null,"attempt":null,"maxAttempts":3,"stage":null,
                "failureKind":null,"timedOut":null,"repairable":false,
                "reflectionRequired":false,"nextAction":"STOP",
                "message":"PROTOCOL_ERROR: 旧租约已经失效","errorSummary":null,
                "terminateToolLoop":true,
                "finalResponse":"抱歉，系统遇到了一些问题，请您稍后重试修复"}
                """;

        assertFalse(ToolLoopTerminationProtocol.parseTrusted(
                "buildProject", cancelledWithFailureResponse).terminate());
        assertFalse(ToolLoopTerminationProtocol.parseTrusted(
                "buildProject", rejectedWithFailureResponse).terminate());
        assertThrows(IllegalArgumentException.class,
                () -> new ToolLoopTerminationProtocol.ControlledTermination(
                        CANCELLED,
                        "抱歉，系统遇到了一些问题，请您稍后重试修复"));
    }

    @Test
    void terminationHandlerFailureIsIsolatedAndNeverRetried() {
        StreamingRequestController controller = new StreamingRequestController();
        AtomicInteger notifications = new AtomicInteger();
        controller.onControlledTermination(termination -> {
            notifications.incrementAndGet();
            throw new IllegalStateException("收尾失败");
        });

        controller.cancel();
        controller.onControlledTermination(termination -> notifications.incrementAndGet());

        assertEquals(1, notifications.get());
    }

    @Test
    void lateTerminationHandlerFailureIsAlsoIsolated() {
        StreamingRequestController controller = new StreamingRequestController();
        controller.cancel();

        controller.onControlledTermination(termination -> {
            throw new IllegalStateException("迟注册收尾失败");
        });

        assertEquals(CANCELLED, controller.controlledTermination().reason());
    }

    @Test
    void blockingModelStartDoesNotBlockCancellation() throws Exception {
        StreamingRequestController controller = new StreamingRequestController();
        assertTrue(controller.beforeModelRequest());
        long generation = controller.latestModelRequestGeneration();
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        try {
            var starting = executor.submit(() -> {
                if (!controller.isCurrentGeneration(generation)) {
                    return false;
                }
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return true;
            });
            assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS));

            var cancelling = executor.submit(controller::cancel);

            cancelling.get(200, java.util.concurrent.TimeUnit.MILLISECONDS);
            assertTrue(controller.isCancelled());
            release.countDown();
            assertTrue(starting.get(1, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.close();
        }
    }

    @Test
    void 工具批次提交先赢后外部动作阻塞时取消不得等待controller锁()
            throws Exception {
        StreamingRequestController controller = new StreamingRequestController();
        assertTrue(controller.beforeModelRequest());
        long generation = controller.latestModelRequestGeneration();
        StreamingRequestController.ToolBatchTicket batch =
                controller.prepareToolBatch(generation, 1);
        assertNotNull(batch);
        assertTrue(controller.tryStartToolBatchWrite(batch));
        assertTrue(controller.commitToolBatch(batch));
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        try {
            var committing = executor.submit(() -> {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                assertEquals(StreamingRequestController.ToolExecutionDecision
                                .CANCELLED,
                        controller.claimToolExecution(batch, 0));
                StreamingRequestController.ToolResultClaim claim =
                        controller.prepareToolResult(batch, 0, null);
                return controller.commitToolResult(batch, 0, claim);
            });
            assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS));

            var cancelling = executor.submit(controller::cancel);

            cancelling.get(200, java.util.concurrent.TimeUnit.MILLISECONDS);
            assertTrue(controller.isCancelled());
            release.countDown();
            assertEquals(StreamingRequestController.ToolResultDecision
                            .CANCELLED,
                    committing.get(1, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(controller.finishToolBatch(batch));
        } finally {
            release.countDown();
            executor.close();
        }
    }

    @Test
    void 取消先赢时工具批次提交必须失败() {
        StreamingRequestController controller = new StreamingRequestController();
        assertTrue(controller.beforeModelRequest());
        long generation = controller.latestModelRequestGeneration();

        controller.cancel();

        assertNull(controller.prepareToolBatch(generation, 1));
    }

    @Test
    void 未取得写入许可的工具批次不得提交且取消必须回滚票据() {
        StreamingRequestController controller = new StreamingRequestController();
        assertTrue(controller.beforeModelRequest());
        long generation = controller.latestModelRequestGeneration();
        StreamingRequestController.ToolBatchTicket batch =
                controller.prepareToolBatch(generation, 1);
        assertNotNull(batch);

        assertFalse(controller.commitToolBatch(batch));
        controller.cancel();

        assertFalse(controller.tryStartToolBatchWrite(batch));
        assertFalse(controller.failPreparedToolBatch(batch));
        assertEquals(StreamingRequestController.ToolExecutionDecision.REJECTED,
                controller.claimToolExecution(batch, 0));
    }

    @Test
    void 工具请求写入启动先赢后取消仍必须提交并闭合取消结果() {
        StreamingRequestController controller = new StreamingRequestController();
        assertTrue(controller.beforeModelRequest());
        long generation = controller.latestModelRequestGeneration();
        StreamingRequestController.ToolBatchTicket batch =
                controller.prepareToolBatch(generation, 1);
        assertNotNull(batch);
        assertTrue(controller.tryStartToolBatchWrite(batch));

        controller.cancel();

        assertTrue(controller.commitToolBatch(batch));
        assertEquals(StreamingRequestController.ToolExecutionDecision.CANCELLED,
                controller.claimToolExecution(batch, 0));
        StreamingRequestController.ToolResultClaim result =
                controller.prepareToolResult(batch, 0, null);
        assertEquals(StreamingRequestController.ToolResultDecision.CANCELLED,
                result.decision());
        assertEquals(StreamingRequestController.ToolResultDecision.CANCELLED,
                controller.commitToolResult(batch, 0, result));
        assertFalse(controller.finishToolBatch(batch));
    }

    @Test
    void 工具请求写入启动后失败必须唯一回滚批次并收口普通错误() {
        StreamingRequestController controller = new StreamingRequestController();
        assertTrue(controller.beforeModelRequest());
        long generation = controller.latestModelRequestGeneration();
        StreamingRequestController.ToolBatchTicket batch =
                controller.prepareToolBatch(generation, 1);
        assertNotNull(batch);
        assertTrue(controller.tryStartToolBatchWrite(batch));

        assertTrue(controller.failPreparedToolBatch(batch));
        assertFalse(controller.failPreparedToolBatch(batch));
        assertFalse(controller.isOpen());
        assertNull(controller.controlledTermination());
    }

    @Test
    void 写入启动后取消先于写入失败不得覆盖取消终态() {
        StreamingRequestController controller = new StreamingRequestController();
        AtomicInteger terminations = new AtomicInteger();
        controller.onControlledTermination(ignored ->
                terminations.incrementAndGet());
        assertTrue(controller.beforeModelRequest());
        long generation = controller.latestModelRequestGeneration();
        StreamingRequestController.ToolBatchTicket batch =
                controller.prepareToolBatch(generation, 1);
        assertNotNull(batch);
        assertTrue(controller.tryStartToolBatchWrite(batch));

        controller.cancel();

        assertFalse(controller.failPreparedToolBatch(batch));
        assertTrue(controller.isCancelled());
        assertEquals(CANCELLED, controller.controlledTermination().reason());
        assertEquals(1, terminations.get());
    }

    @Test
    void 恢复撤销必须释放未启动写入的旧工具批次() {
        StreamingRequestController controller = new StreamingRequestController();
        assertTrue(controller.beforeModelRequest());
        long sourceGeneration = controller.latestModelRequestGeneration();
        assertNotNull(controller.prepareToolBatch(sourceGeneration, 1));

        assertEquals(StreamingRequestController.GenerationCancellation.CANCELLED,
                controller.cancelGenerationForRecovery(sourceGeneration));
        assertTrue(controller.beforeModelRequest(sourceGeneration));
        long recoveryGeneration = controller.latestModelRequestGeneration();

        assertNotNull(controller.prepareToolBatch(recoveryGeneration, 1),
                "旧 PREPARED 票据不得阻塞恢复代的新工具批次");
    }

    @Test
    void 工具结果提交先赢后取消必须保留真实结果决定() {
        StreamingRequestController controller = new StreamingRequestController();
        assertTrue(controller.beforeModelRequest());
        long generation = controller.latestModelRequestGeneration();
        StreamingRequestController.ToolBatchTicket batch =
                controller.prepareToolBatch(generation, 1);
        assertNotNull(batch);
        assertTrue(controller.tryStartToolBatchWrite(batch));
        assertTrue(controller.commitToolBatch(batch));
        assertEquals(StreamingRequestController.ToolExecutionDecision.EXECUTE,
                controller.claimToolExecution(batch, 0));

        StreamingRequestController.ToolResultClaim claim =
                controller.prepareToolResult(batch, 0, null);
        StreamingRequestController.ToolResultDecision result =
                controller.commitToolResult(batch, 0, claim);
        controller.cancel();

        assertEquals(StreamingRequestController.ToolResultDecision.PROVIDED,
                result);
        assertFalse(controller.finishToolBatch(batch));
    }

    @Test
    void 工具结果预留先赢后取消必须保留预留的真实结果决定() {
        StreamingRequestController controller = new StreamingRequestController();
        assertTrue(controller.beforeModelRequest());
        long generation = controller.latestModelRequestGeneration();
        StreamingRequestController.ToolBatchTicket batch =
                controller.prepareToolBatch(generation, 1);
        assertNotNull(batch);
        assertTrue(controller.tryStartToolBatchWrite(batch));
        assertTrue(controller.commitToolBatch(batch));
        assertEquals(StreamingRequestController.ToolExecutionDecision.EXECUTE,
                controller.claimToolExecution(batch, 0));

        StreamingRequestController.ToolResultClaim claim =
                controller.prepareToolResult(batch, 0, null);
        controller.cancel();
        StreamingRequestController.ToolResultDecision result =
                controller.commitToolResult(batch, 0, claim);

        assertEquals(StreamingRequestController.ToolResultDecision.PROVIDED,
                result);
        assertFalse(controller.finishToolBatch(batch));
    }

    @Test
    void staleHandleCannotOverwriteNewerSynchronousRequestHandle() {
        StreamingRequestController controller = new StreamingRequestController();
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        assertTrue(controller.beforeModelRequest());
        long firstGeneration = controller.latestModelRequestGeneration();
        assertTrue(controller.beforeModelRequest());
        long secondGeneration = controller.latestModelRequestGeneration();

        controller.registerRequestHandle(secondGeneration, second::incrementAndGet);
        controller.registerRequestHandle(firstGeneration, first::incrementAndGet);
        controller.cancel();

        assertEquals(1, first.get(), "迟到的旧请求 handle 必须立即取消");
        assertEquals(1, second.get(), "取消必须命中最新请求 handle");
    }

    @Test
    void cancellationBeforeRegistrationAndRepeatedCancellationCancelHandleOnce() {
        StreamingRequestController controller = new StreamingRequestController();
        AtomicInteger cancellations = new AtomicInteger();
        AtomicReference<ToolLoopTerminationProtocol.ControlledTermination> termination =
                new AtomicReference<>();
        controller.onControlledTermination(termination::set);

        controller.cancel();
        controller.cancel();
        controller.registerRequestHandle(cancellations::incrementAndGet);

        assertEquals(1, cancellations.get());
        assertEquals(CANCELLED, termination.get().reason());
        assertTrue(controller.awaitQuiescence(Duration.ofMillis(50)));
    }

    @Test
    void lateCancellationHandlerWaitsUntilActiveCallbackExits() {
        StreamingRequestController controller = new StreamingRequestController();
        AtomicInteger notifications = new AtomicInteger();
        StreamingRequestController.CallbackTicket callback = controller.enterCallback();

        controller.cancel();
        controller.onControlledTermination(termination -> notifications.incrementAndGet());

        assertEquals(0, notifications.get(), "活跃回调退出前不能开始取消收尾");
        callback.close();
        assertEquals(1, notifications.get());
    }

    @Test
    void cancellationWaitsForAllCallbacksAndUsesLastHandlerRegisteredBeforeDelivery() {
        StreamingRequestController controller = new StreamingRequestController();
        AtomicInteger firstHandler = new AtomicInteger();
        AtomicInteger lastHandler = new AtomicInteger();
        StreamingRequestController.CallbackTicket firstCallback = controller.enterCallback();
        StreamingRequestController.CallbackTicket secondCallback = controller.enterCallback();

        controller.onControlledTermination(termination -> firstHandler.incrementAndGet());
        controller.cancel();
        controller.onControlledTermination(termination -> lastHandler.incrementAndGet());
        firstCallback.close();

        assertEquals(0, firstHandler.get());
        assertEquals(0, lastHandler.get());
        secondCallback.close();
        assertEquals(0, firstHandler.get(), "终止交付前重复注册采用最后一个处理器");
        assertEquals(1, lastHandler.get());
    }

    @Test
    void cancellationTargetsLatestRegisteredHandle() {
        StreamingRequestController controller = new StreamingRequestController();
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        StreamingRequestHandle firstHandle = first::incrementAndGet;
        StreamingRequestHandle secondHandle = second::incrementAndGet;

        controller.registerRequestHandle(firstHandle);
        controller.registerRequestHandle(secondHandle);
        controller.cancel();

        assertEquals(0, first.get());
        assertEquals(1, second.get());
        assertNull(controller.enterCallback());
    }

    @Test
    void normalCompletionClaimPreventsCancellationFromOverwritingOutcome() {
        StreamingRequestController controller = new StreamingRequestController();
        AtomicInteger cancellations = new AtomicInteger();
        controller.onControlledTermination(termination -> cancellations.incrementAndGet());

        assertTrue(controller.claimNormalCompletion());
        controller.cancel();

        assertTrue(controller.finishNormalCompletion());
        assertEquals(0, cancellations.get());
        assertNull(controller.controlledTermination());
        assertNull(controller.enterCallback());
    }

    @Test
    void failedNormalCompletionDeliversErrorExactlyOnceAndIsolatesHandlerFailure() {
        StreamingRequestController controller = new StreamingRequestController();
        AtomicInteger errors = new AtomicInteger();
        assertTrue(controller.claimNormalCompletion());

        assertTrue(controller.failNormalCompletion(new IllegalStateException("提交失败"), error -> {
            errors.incrementAndGet();
            throw new IllegalStateException("错误回调失败");
        }));
        assertFalse(controller.failNormalCompletion(
                new IllegalStateException("重复失败"), error -> errors.incrementAndGet()));

        assertEquals(1, errors.get());
        assertNull(controller.enterCallback());
    }
}
