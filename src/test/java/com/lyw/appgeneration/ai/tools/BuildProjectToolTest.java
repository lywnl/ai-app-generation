package com.lyw.appgeneration.ai.tools;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.lyw.appgeneration.core.builder.BuildErrorSanitizer;
import com.lyw.appgeneration.core.builder.BuildExecutionContext;
import com.lyw.appgeneration.core.builder.BuildResult;
import com.lyw.appgeneration.core.builder.BuildStage;
import com.lyw.appgeneration.core.builder.VueBuildFailureKind;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager;
import com.lyw.appgeneration.core.builder.VueProjectBuilder;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BuildProjectToolTest {

    private static final long APP_ID = 701L;
    private static final long USER_ID = 81L;
    private static final String TURN_ID = "turn-build-1";

    @Test
    void modelSignatureExposesOnlyMemoryAppId() throws Exception {
        Method method = BuildProjectTool.class.getMethod("buildProject", Long.class);

        assertTrue(method.isAnnotationPresent(Tool.class));
        assertEquals(1, method.getParameterCount());
        assertTrue(method.getParameters()[0].isAnnotationPresent(ToolMemoryId.class));
    }

    @Test
    void mapsCompletedAttemptsToStableRepairPolicyAndStopsOnThirdFailure() {
        VueProjectBuilder builder = mock(VueProjectBuilder.class);
        when(builder.buildProjectDetailed(any(Path.class), any(BuildExecutionContext.class)))
                .thenReturn(codeFailure(), dependencyFailure(), infrastructureFailure());
        Harness harness = harness(builder);
        try (harness) {
            JSONObject first = invoke(harness);
            assertCompletedFailure(first, 1, "REPAIR", true, false, false);

            JSONObject second = invoke(harness);
            assertCompletedFailure(second, 2, "FINAL_DIAGNOSIS", false, true, false);

            JSONObject third = invoke(harness);
            assertCompletedFailure(third, 3, "STOP", false, true, true);
            assertEquals("抱歉，系统遇到了一些问题，请您稍后重试修复",
                    third.getStr("finalResponse"));

            JSONObject terminalRetry = invoke(harness);
            assertEquals("REJECTED", terminalRetry.getStr("invocationStatus"));
            assertNull(terminalRetry.get("attempt"));
            verify(builder, org.mockito.Mockito.times(3))
                    .buildProjectDetailed(any(Path.class), any(BuildExecutionContext.class));
        }
    }

    @Test
    void firstDependencyOrInfrastructureFailureRequestsBuildRetryWithoutCodeRepair() {
        for (BuildResult result : new BuildResult[]{dependencyFailure(), infrastructureFailure()}) {
            VueProjectBuilder builder = mock(VueProjectBuilder.class);
            when(builder.buildProjectDetailed(any(Path.class), any(BuildExecutionContext.class)))
                    .thenReturn(result);
            Harness harness = harness(builder);
            try (harness) {
                JSONObject json = invoke(harness);
                assertCompletedFailure(json, 1, "RETRY_BUILD", false, false, false);
            }
        }
    }

    @Test
    void anySuccessfulAttemptTerminatesWithFixedFinalResponse() {
        VueProjectBuilder builder = mock(VueProjectBuilder.class);
        when(builder.buildProjectDetailed(any(Path.class), any(BuildExecutionContext.class)))
                .thenReturn(success());
        Harness harness = harness(builder);
        try (harness) {
            JSONObject json = invoke(harness);

            assertEquals("vue-build-tool/v1", json.getStr("protocol"));
            assertEquals("COMPLETED", json.getStr("invocationStatus"));
            assertTrue(json.getBool("success"));
            assertEquals(1, json.getInt("attempt"));
            assertEquals("STOP", json.getStr("nextAction"));
            assertTrue(json.getBool("terminateToolLoop"));
            assertEquals("项目已生成并构建成功。", json.getStr("finalResponse"));
        }
    }

    @Test
    void concurrentInvocationDoesNotConsumeSecondAttempt() throws Exception {
        VueProjectBuilder builder = mock(VueProjectBuilder.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(builder.buildProjectDetailed(any(Path.class), any(BuildExecutionContext.class)))
                .thenAnswer(invocation -> {
                    entered.countDown();
                    assertTrue(release.await(2, TimeUnit.SECONDS));
                    return codeFailure();
                });
        Harness harness = harness(builder);
        try (harness; var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstFuture = executor.submit(() -> invoke(harness));
            assertTrue(entered.await(1, TimeUnit.SECONDS));

            JSONObject concurrent = invoke(harness);
            release.countDown();
            JSONObject first = firstFuture.get(2, TimeUnit.SECONDS);

            assertEquals("BUILD_IN_PROGRESS", concurrent.getStr("invocationStatus"));
            assertNull(concurrent.get("attempt"));
            assertNull(concurrent.get("stage"));
            assertFalse(concurrent.getBool("terminateToolLoop"));
            assertEquals(1, first.getInt("attempt"));
            assertEquals(1, harness.lease.snapshot().buildAttempt());
            verify(builder, org.mockito.Mockito.times(1))
                    .buildProjectDetailed(any(Path.class), any(BuildExecutionContext.class));
        }
    }

    @Test
    void missingOrMismatchedScopeIsRejectedWithoutCallingBuilder() {
        VueProjectBuilder builder = mock(VueProjectBuilder.class);
        Harness harness = harness(builder);
        try (harness) {
            JSONObject missing = JSONUtil.parseObj(harness.tool.buildProject(APP_ID));
            assertProtocolRejection(missing);

            String mismatched = harness.scopeManager.callInScope(
                    harness.scope,
                    () -> harness.tool.buildProject(APP_ID + 1));
            assertProtocolRejection(JSONUtil.parseObj(mismatched));
            verify(builder, never()).buildProjectDetailed(any(), any());
        }
    }

    @Test
    void cancellationAfterScopeEntryTerminatesWithoutInventingAttempt() {
        VueProjectBuilder builder = mock(VueProjectBuilder.class);
        Harness harness = harness(builder);
        try (harness) {
            String raw = harness.scopeManager.callInScope(harness.scope, () -> {
                harness.lease.cancel();
                return harness.tool.buildProject(APP_ID);
            });
            JSONObject json = JSONUtil.parseObj(raw);

            assertEquals("CANCELLED", json.getStr("invocationStatus"));
            assertNull(json.get("attempt"));
            assertNull(json.get("stage"));
            assertTrue(json.getBool("terminateToolLoop"));
            verify(builder, never()).buildProjectDetailed(any(), any());
        }
    }

    @Test
    void cancellationAfterReservationKeepsConsumedAttemptAndStage() {
        VueProjectBuilder builder = mock(VueProjectBuilder.class);
        Harness harness = harness(builder);
        when(builder.buildProjectDetailed(any(Path.class), any(BuildExecutionContext.class)))
                .thenAnswer(invocation -> {
                    harness.lease.cancel();
                    return new BuildResult(false, BuildStage.NPM_BUILD, null,
                            false, true, null, "构建已取消", 1L);
                });
        try (harness) {
            JSONObject json = invoke(harness);

            assertEquals("CANCELLED", json.getStr("invocationStatus"));
            assertEquals(1, json.getInt("attempt"));
            assertEquals("NPM_BUILD", json.getStr("stage"));
            assertTrue(json.getBool("terminateToolLoop"));
        }
    }

    @Test
    void cancellationSignalWinsRaceWithOrdinaryCommandFailure() {
        VueProjectBuilder builder = mock(VueProjectBuilder.class);
        when(builder.buildProjectDetailed(any(Path.class), any(BuildExecutionContext.class)))
                .thenAnswer(invocation -> {
                    BuildExecutionContext context = invocation.getArgument(1);
                    context.cancellation().cancel();
                    return codeFailure();
                });
        Harness harness = harness(builder);
        try (harness) {
            JSONObject json = invoke(harness);

            assertEquals("CANCELLED", json.getStr("invocationStatus"));
            assertEquals(1, json.getInt("attempt"));
            assertEquals("NPM_BUILD", json.getStr("stage"));
            assertTrue(json.getBool("terminateToolLoop"));
        }
    }

    @Test
    void leaseCancellationWinsRaceWithSuccessfulBuilderReturn() {
        VueProjectBuilder builder = mock(VueProjectBuilder.class);
        Harness harness = harness(builder);
        when(builder.buildProjectDetailed(any(Path.class), any(BuildExecutionContext.class)))
                .thenAnswer(invocation -> {
                    harness.lease.cancel();
                    return success();
                });
        try (harness) {
            JSONObject json = invoke(harness);

            assertEquals("CANCELLED", json.getStr("invocationStatus"));
            assertEquals(1, json.getInt("attempt"));
            assertEquals("SUCCESS", json.getStr("stage"));
            assertTrue(json.getBool("terminateToolLoop"));
        }
    }

    @Test
    void protocolRecordRejectsInventedTransientBuildState() {
        assertThrows(IllegalArgumentException.class, () -> new BuildProjectToolResult(
                BuildProjectToolResult.PROTOCOL,
                BuildProjectToolResult.BuildInvocationStatus.BUILD_IN_PROGRESS,
                null, null, BuildProjectToolResult.MAX_ATTEMPTS, null, null, null,
                true, false, null, "伪造状态", null, true, "伪造终止响应"));
    }

    @Test
    void protocolRecordRejectsContradictoryCompletedStates() {
        assertThrows(IllegalArgumentException.class, () -> new BuildProjectToolResult(
                BuildProjectToolResult.PROTOCOL,
                BuildProjectToolResult.BuildInvocationStatus.COMPLETED,
                true, 1, BuildProjectToolResult.MAX_ATTEMPTS, BuildStage.SUCCESS,
                VueBuildFailureKind.CODE, false, true, false,
                BuildProjectToolResult.BuildNextAction.REPAIR,
                "伪造成功", "伪造诊断", false, null));
        assertThrows(IllegalArgumentException.class, () -> new BuildProjectToolResult(
                BuildProjectToolResult.PROTOCOL,
                BuildProjectToolResult.BuildInvocationStatus.COMPLETED,
                false, 3, BuildProjectToolResult.MAX_ATTEMPTS, BuildStage.NPM_BUILD,
                null, false, false, false,
                BuildProjectToolResult.BuildNextAction.STOP,
                "伪造失败", null, false, null));
    }

    @Test
    void stableMarkdownNeverCopiesErrorSummary() {
        BuildProjectTool tool = new BuildProjectTool(
                mock(VueProjectBuilder.class), new BuildErrorSanitizer(),
                new FileToolExecutionScopeManager());
        BuildProjectToolResult result = BuildProjectToolResult.completedFailure(
                1, BuildStage.NPM_BUILD, VueBuildFailureKind.CODE,
                false, "绝密原始诊断 /private/project");

        String markdown = tool.generateToolExecutedResult(
                new JSONObject(), BuildProjectProtocolSupport.json(result));

        assertEquals("第 1 次构建失败（阶段：NPM_BUILD），正在进行最小修复", markdown);
        assertFalse(markdown.contains("绝密原始诊断"));
        assertFalse(markdown.contains("/private/project"));
    }

    @Test
    void unexpectedBuilderFailureConsumesReservedAttemptAsInfrastructureFailure() {
        VueProjectBuilder builder = mock(VueProjectBuilder.class);
        when(builder.buildProjectDetailed(any(Path.class), any(BuildExecutionContext.class)))
                .thenThrow(new IllegalStateException("构建器意外异常"));
        Harness harness = harness(builder);
        try (harness) {
            JSONObject json = invoke(harness);

            assertCompletedFailure(json, 1, "RETRY_BUILD", false, false, false);
            assertEquals("INFRASTRUCTURE", json.getStr("failureKind"));
            assertEquals(1, harness.lease.snapshot().buildAttempt());
        }
    }

    @Test
    void cancellationSignalWinsWhenBuilderThrowsAfterCancellation() {
        VueProjectBuilder builder = mock(VueProjectBuilder.class);
        when(builder.buildProjectDetailed(any(Path.class), any(BuildExecutionContext.class)))
                .thenAnswer(invocation -> {
                    BuildExecutionContext context = invocation.getArgument(1);
                    context.cancellation().cancel();
                    throw new IllegalStateException("取消后的构建器异常");
                });
        Harness harness = harness(builder);
        try (harness) {
            JSONObject json = invoke(harness);

            assertEquals("CANCELLED", json.getStr("invocationStatus"));
            assertEquals(1, json.getInt("attempt"));
            assertEquals("VALIDATION", json.getStr("stage"));
            assertTrue(json.getBool("terminateToolLoop"));
        }
    }

    private JSONObject invoke(Harness harness) {
        return JSONUtil.parseObj(harness.scopeManager.callInScope(
                harness.scope, () -> harness.tool.buildProject(APP_ID)));
    }

    private void assertCompletedFailure(
            JSONObject json,
            int attempt,
            String nextAction,
            boolean repairable,
            boolean reflectionRequired,
            boolean terminate) {
        assertEquals("vue-build-tool/v1", json.getStr("protocol"));
        assertEquals("COMPLETED", json.getStr("invocationStatus"));
        assertFalse(json.getBool("success"));
        assertEquals(attempt, json.getInt("attempt"));
        assertEquals(nextAction, json.getStr("nextAction"));
        assertEquals(repairable, json.getBool("repairable"));
        assertEquals(reflectionRequired, json.getBool("reflectionRequired"));
        assertEquals(terminate, json.getBool("terminateToolLoop"));
    }

    private void assertProtocolRejection(JSONObject json) {
        assertEquals("REJECTED", json.getStr("invocationStatus"));
        assertNull(json.get("attempt"));
        assertNull(json.get("stage"));
        assertNull(json.get("success"));
        assertTrue(json.getBool("terminateToolLoop"));
        assertTrue(json.getStr("message").contains("PROTOCOL_ERROR"));
    }

    private Harness harness(VueProjectBuilder builder) {
        AppOperationLeaseManager operationManager = new AppOperationLeaseManager();
        var operation = operationManager.acquire(
                APP_ID, AppOperationLeaseManager.AppOperationType.GENERATE, TURN_ID);
        var lease = new VueBuildSessionManager().open(operation, USER_ID, TURN_ID);
        FileToolExecutionScopeManager scopeManager = new FileToolExecutionScopeManager();
        var scope = scopeManager.online(
                lease, TURN_ID, APP_ID, Set.of("buildProject"));
        BuildProjectTool tool = new BuildProjectTool(
                builder, new BuildErrorSanitizer(), scopeManager);
        return new Harness(operation, lease, scopeManager, scope, tool);
    }

    private BuildResult codeFailure() {
        return new BuildResult(false, BuildStage.NPM_BUILD, 2, false, false,
                VueBuildFailureKind.CODE, "编译失败", 1L);
    }

    private BuildResult dependencyFailure() {
        return new BuildResult(false, BuildStage.NPM_INSTALL, 1, false, false,
                VueBuildFailureKind.DEPENDENCY, "依赖失败", 1L);
    }

    private BuildResult infrastructureFailure() {
        return new BuildResult(false, BuildStage.NPM_INSTALL, null, true, false,
                VueBuildFailureKind.INFRASTRUCTURE, "安装超时", 1L);
    }

    private BuildResult success() {
        return new BuildResult(true, BuildStage.SUCCESS, 0, false, false,
                null, "构建成功", 1L);
    }

    private record Harness(
            AppOperationLeaseManager.AppOperationLease operation,
            VueBuildSessionManager.VueBuildLease lease,
            FileToolExecutionScopeManager scopeManager,
            FileToolExecutionScopeManager.FileToolScope scope,
            BuildProjectTool tool
    ) implements AutoCloseable {

        @Override
        public void close() {
            lease.close();
            operation.close();
        }
    }
}
