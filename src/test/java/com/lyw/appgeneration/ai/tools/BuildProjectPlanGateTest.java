package com.lyw.appgeneration.ai.tools;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.ai.plan.AppPlan;
import com.lyw.appgeneration.ai.plan.AppPlanStateManager;
import com.lyw.appgeneration.ai.plan.PlanFile;
import com.lyw.appgeneration.ai.plan.PlanFileAction;
import com.lyw.appgeneration.ai.plan.PlanFileState;
import com.lyw.appgeneration.ai.plan.PlanMode;
import com.lyw.appgeneration.ai.plan.PlanStatus;
import com.lyw.appgeneration.ai.plan.PlanStoragePathResolver;
import com.lyw.appgeneration.core.builder.BuildResult;
import com.lyw.appgeneration.core.builder.BuildStage;
import com.lyw.appgeneration.core.builder.VueBuildFailureKind;
import com.lyw.appgeneration.core.builder.VueProjectBuilder;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import com.lyw.appgeneration.monitor.VueBuildRepairMetricsCollector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildProjectPlanGateTest {

    private static final long APP_ID = 9101001L;
    private static final long USER_ID = 9101002L;
    private static final String TURN_ID = "build-plan-turn";

    @Test
    void 没有计划时构建闸门不消耗真实构建尝试() throws Exception {
        Harness harness = harness(new AtomicInteger());
        try {
            JSONObject result = JSONUtil.parseObj(harness.invoke());
            assertEquals("REJECTED", result.getStr("invocationStatus"));
            assertTrue(result.getStr("message").contains("makePlan"));
            assertEquals(0, harness.builderCalls.get());
            assertEquals(0, harness.lease.snapshot().buildAttempt());
        } finally {
            harness.close();
        }
    }

    @Test
    void KEEP计划通过闸门后才调用构建器() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Harness harness = harness(calls);
        try {
            AppPlan plan = new AppPlan(
                    "plan-1", TURN_ID, TURN_ID, 1, 1, PlanMode.FULL,
                    "保留现有工程", java.util.List.of(new PlanFile(
                    "package.json", "保留依赖", PlanFileAction.KEEP,
                    java.util.List.of(), PlanFileState.PENDING)),
                    java.util.List.of(), PlanStatus.PLANNED);
            harness.planStateManager.save(APP_ID, plan, 0, TURN_ID);

            JSONObject result = JSONUtil.parseObj(harness.invoke());
            assertEquals("COMPLETED", result.getStr("invocationStatus"));
            assertTrue(result.getBool("success"));
            assertEquals(1, calls.get());
            assertEquals(1, harness.lease.snapshot().buildAttempt());
            assertEquals(PlanStatus.BUILT,
                    harness.planStateManager.load(APP_ID).orElseThrow().status());
        } finally {
            harness.close();
        }
    }

    @Test
    void 只读回合调用构建工具也不会进入计划闸门() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Harness harness = harness(calls, () -> false);
        try {
            JSONObject result = JSONUtil.parseObj(harness.invoke());
            assertEquals("REJECTED", result.getStr("invocationStatus"));
            assertTrue(result.getStr("message").contains("只读"));
            assertEquals(0, calls.get());
        } finally {
            harness.close();
        }
    }

    @Test
    void 计划外成功变更会被构建闸门拒绝() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Harness harness = harness(calls);
        try {
            AppPlan plan = new AppPlan(
                    "plan-1", TURN_ID, TURN_ID, 1, 1, PlanMode.FULL,
                    "保留现有工程", java.util.List.of(new PlanFile(
                    "package.json", "保留依赖", PlanFileAction.KEEP,
                    java.util.List.of(), PlanFileState.PENDING)),
                    java.util.List.of(), PlanStatus.PLANNED);
            harness.planStateManager.save(APP_ID, plan, 0, TURN_ID);
            harness.planStateManager.recordSuccessfulMutation(
                    APP_ID, TURN_ID, "src/Unexpected.vue");

            JSONObject result = JSONUtil.parseObj(harness.invoke());
            assertEquals("REJECTED", result.getStr("invocationStatus"));
            assertEquals(0, calls.get());
        } finally {
            harness.close();
        }
    }

    @Test
    void 依赖未完成时构建闸门拒绝且不消耗次数() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Harness harness = harness(calls);
        try {
            AppPlan plan = new AppPlan(
                    "plan-dependency", TURN_ID, TURN_ID, 1, 1, PlanMode.FULL,
                    "依赖测试", java.util.List.of(
                    new PlanFile("A.vue", "基础", PlanFileAction.MODIFY,
                            java.util.List.of(), PlanFileState.PENDING),
                    new PlanFile("B.vue", "页面", PlanFileAction.MODIFY,
                            java.util.List.of("A.vue"), PlanFileState.TOUCHED)),
                    java.util.List.of(), PlanStatus.PLANNED);
            harness.planStateManager.save(APP_ID, plan, 0, TURN_ID);

            JSONObject result = JSONUtil.parseObj(harness.invoke());
            assertEquals("REJECTED", result.getStr("invocationStatus"));
            assertTrue(result.getStr("message").contains("依赖"));
            assertEquals(0, calls.get());
            assertEquals(0, harness.lease.snapshot().buildAttempt());
        } finally {
            harness.close();
        }
    }

    private Harness harness(AtomicInteger builderCalls) {
        return harness(builderCalls, () -> true);
    }

    private Harness harness(
            AtomicInteger builderCalls, BooleanSupplier mutationAllowed) {
        Path planRoot;
        try {
            planRoot = Files.createTempDirectory("build-plan-state-");
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
        AppPlanStateManager planStateManager = new AppPlanStateManager(
                new ObjectMapper(), new PlanStoragePathResolver(planRoot));
        FileToolExecutionScopeManager scopeManager =
                new FileToolExecutionScopeManager(
                        new FileToolBudgetGuard(), planStateManager);
        AppOperationLeaseManager operations = new AppOperationLeaseManager();
        var operation = operations.acquire(
                APP_ID, AppOperationLeaseManager.AppOperationType.GENERATE, TURN_ID);
        var lease = new VueBuildSessionManager().open(operation, USER_ID, TURN_ID);
        var scope = scopeManager.online(
                lease, TURN_ID, APP_ID, Set.of("buildProject"),
                new FileToolBudgetGuard().newSession(), mutationAllowed);
        VueProjectBuilder builder = new VueProjectBuilder() {
            @Override
            public BuildResult buildProjectDetailed(
                    Path projectRoot,
                    com.lyw.appgeneration.core.builder.BuildExecutionContext context) {
                builderCalls.incrementAndGet();
                return new BuildResult(true, BuildStage.SUCCESS, 0,
                        false, false, null, "成功", 1L);
            }
        };
        BuildProjectTool tool = new BuildProjectTool(
                builder, (path, result) -> "", scopeManager,
                new VueBuildRepairMetricsCollector(new SimpleMeterRegistry()),
                planStateManager);
        return new Harness(
                operations, operation, lease, scopeManager, scope,
                tool, planStateManager, builderCalls, planRoot);
    }

    private record Harness(
            AppOperationLeaseManager operations,
            AppOperationLeaseManager.AppOperationLease operation,
            VueBuildSessionManager.VueBuildLease lease,
            FileToolExecutionScopeManager scopeManager,
            FileToolExecutionScopeManager.FileToolScope scope,
            BuildProjectTool tool,
            AppPlanStateManager planStateManager,
            AtomicInteger builderCalls,
            Path planRoot) implements AutoCloseable {

        private String invoke() {
            return scopeManager.callInScope(
                    scope, "buildProject", () -> tool.buildProject(APP_ID));
        }

        @Override
        public void close() {
            lease.close();
            operation.close();
            try (var paths = Files.walk(planRoot)) {
                paths.sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (java.io.IOException exception) {
                                throw new RuntimeException(exception);
                            }
                        });
            } catch (java.io.IOException exception) {
                throw new RuntimeException(exception);
            }
        }
    }
}
