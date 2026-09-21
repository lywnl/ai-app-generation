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
import dev.langchain4j.service.BuildProgressGuard;
import dev.langchain4j.service.ReplanContext;
import com.lyw.appgeneration.ai.plan.BuildBlockDiagnostic;
import com.lyw.appgeneration.constants.AppConstant;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BuildProjectPlanGateTest {

    private static final long APP_ID = 9101001L;
    private static final long USER_ID = 9101002L;
    private static final String TURN_ID = "build-plan-turn";

    @Test
    void 真实构建拒绝携带调用级诊断且不会泄漏到下一工具() {
        try (Harness harness = harness(new AtomicInteger())) {
            var observed = harness.scopeManager.callInScopeWithBuildObservation(
                    harness.scope, "buildProject", () -> harness.tool.buildProject(APP_ID));
            assertEquals(BuildBlockDiagnostic.Reason.NO_PLAN, observed.buildObservation().rejection().reason());
            assertEquals(0, harness.lease.snapshot().buildAttempt());
            JSONObject raw = JSONUtil.parseObj(observed.toolResult());
            assertEquals(15, raw.size());
            assertFalse(raw.containsKey("buildObservation"));
            var next = harness.scopeManager.callInScopeWithBuildObservation(harness.scope, "readFile", () -> "只读结果");
            assertNull(next.buildObservation());
        }
    }

    @Test
    void 活动Replan拒绝包含底层阻塞且不再自动追加反馈() {
        try (Harness harness = harness(new AtomicInteger())) {
            PlanFile file = new PlanFile("A.vue", "页面", PlanFileAction.MODIFY, java.util.List.of(), PlanFileState.PENDING);
            harness.planStateManager.save(APP_ID, new AppPlan("p", TURN_ID, TURN_ID, 1, 1,
                    PlanMode.FULL, "页面", java.util.List.of(file), java.util.List.of(), PlanStatus.PLANNED), 0, TURN_ID);
            harness.scope.replanContext().restorePending("已有偏差", "A.vue");
            var result = harness.scopeManager.callInScopeWithBuildObservation(harness.scope, "buildProject", () -> harness.tool.buildProject(APP_ID));
            assertEquals(BuildBlockDiagnostic.Reason.REPLAN_PENDING, result.buildObservation().rejection().reason());
            assertEquals(2, result.buildObservation().rejection().blockers().size());
            assertEquals(0, harness.scope.replanContext().feedbackCount());
            assertEquals(0, harness.lease.snapshot().buildAttempt());
        }
    }

    @Test
    void 代码失败后的拒绝和真实文件写回复用构建会话序号() throws Exception {
        try (Harness harness = harness(new AtomicInteger())) {
            String path = "src/GuardRepair-" + java.util.UUID.randomUUID() + ".vue";
            PlanFile file = new PlanFile(path, "页面", PlanFileAction.MODIFY, java.util.List.of(), PlanFileState.TOUCHED);
            harness.planStateManager.save(APP_ID, new AppPlan("p", TURN_ID, TURN_ID, 1, 1,
                    PlanMode.FULL, "页面", java.util.List.of(file), java.util.List.of(), PlanStatus.PLANNED), 0, TURN_ID);
            try (var ticket = harness.lease.beginBuild()) {
                harness.lease.recordFailure(ticket, new BuildResult(false, BuildStage.NPM_BUILD, 1, false, false,
                        VueBuildFailureKind.CODE, "构建错误", 1));
            }
            var rejected = harness.scopeManager.callInScopeWithBuildObservation(harness.scope, "buildProject", () -> harness.tool.buildProject(APP_ID));
            assertEquals(BuildBlockDiagnostic.Reason.CODE_MUTATION_REQUIRED, rejected.buildObservation().rejection().reason());
            assertEquals(1, harness.lease.snapshot().buildAttempt());
            BuildProgressGuard guard = new BuildProgressGuard(APP_ID, TURN_ID);
            guard.observe(1, "reject", rejected.buildObservation());
            Path physical = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_" + APP_ID, path);
            try {
                FileWriteTool writer = new FileWriteTool(new com.lyw.appgeneration.manger.AppFileStateManager(), harness.scopeManager);
                var write = harness.scopeManager.callInScopeWithBuildObservation(harness.scope, "writeFile",
                        () -> writer.writeFile(path, "<template>修复</template>", APP_ID));
                assertTrue(write.buildObservation().trustedProgress());
                assertEquals(1, write.buildObservation().mutationRevision());
                assertTrue(Files.readString(physical).contains("修复"));
                guard.observe(1, "write", write.buildObservation());
                assertEquals(0, guard.blockedCount());
                assertFalse(harness.lease.requiresCodeMutation());
                var built = harness.scopeManager.callInScopeWithBuildObservation(harness.scope, "buildProject", () -> harness.tool.buildProject(APP_ID));
                assertNotNull(built.buildObservation());
                assertTrue(built.buildObservation().realBuild());
                assertEquals(2, harness.lease.snapshot().buildAttempt());
            } finally {
                Files.deleteIfExists(physical);
            }
        }
    }

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
                lease, TURN_ID, APP_ID, Set.of("buildProject", "readFile", "writeFile"),
                new FileToolBudgetGuard().newSession(), mutationAllowed, new ReplanContext());
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
