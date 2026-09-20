package com.lyw.appgeneration.ai.tools;

import cn.hutool.json.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.ai.plan.AppPlan;
import com.lyw.appgeneration.ai.plan.AppPlanStateManager;
import com.lyw.appgeneration.ai.plan.PlanFile;
import com.lyw.appgeneration.ai.plan.PlanFileAction;
import com.lyw.appgeneration.ai.plan.PlanFileState;
import com.lyw.appgeneration.ai.plan.PlanMode;
import com.lyw.appgeneration.ai.plan.PlanStatus;
import com.lyw.appgeneration.ai.plan.PlanStoragePathResolver;
import com.lyw.appgeneration.ai.plan.ReplanDetector;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import com.lyw.appgeneration.constants.AppConstant;
import dev.langchain4j.service.ReplanContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanToolTest {

    private static final long APP_ID = 9001001L;
    private static final long USER_ID = 9001002L;
    private static final String TURN_ID = "plan-tool-turn";
    private static final Set<String> TOOLS = Set.of("makePlan", "updatePlan");

    @Test
    void 在线变更作用域可以创建并修订计划() throws Exception {
        Path root = Files.createTempDirectory("plan-tool-");
        AppPlanStateManager stateManager = new AppPlanStateManager(
                new ObjectMapper(), new PlanStoragePathResolver(root));
        FileToolExecutionScopeManager scopeManager =
                new FileToolExecutionScopeManager(new FileToolBudgetGuard());
        AppOperationLeaseManager operationManager = new AppOperationLeaseManager();
        var operation = operationManager.acquire(
                APP_ID, AppOperationLeaseManager.AppOperationType.GENERATE, TURN_ID);
        var lease = new VueBuildSessionManager().open(operation, USER_ID, TURN_ID);
        var scope = scopeManager.online(
                lease, TURN_ID, APP_ID, TOOLS,
                new FileToolBudgetGuard().newSession(), () -> true);
        MakePlanTool makePlan = new MakePlanTool(stateManager, scopeManager);
        UpdatePlanTool updatePlan = new UpdatePlanTool(stateManager, scopeManager);
        try {
            String created = scopeManager.callInScope(scope, "makePlan",
                    () -> makePlan.makePlan(
                            "创建页面", """
                                    [{"path":"src/App.vue","purpose":"入口","action":"MODIFY","dependsOn":[]}]
                                    """, APP_ID));
            PlanToolResult createdResult = PlanToolProtocolSupport.parse(created);
            assertEquals(PlanToolResult.Status.APPLIED, createdResult.status());
            assertEquals(1, createdResult.version());

            String revised = scopeManager.callInScope(scope, "updatePlan",
                    () -> updatePlan.updatePlan(
                            "补充路由入口", """
                                    [{"path":"src/router/index.js","purpose":"路由","action":"CREATE","dependsOn":[]}]
                                    """, "[]", "[]", APP_ID));
            PlanToolResult revisedResult = PlanToolProtocolSupport.parse(revised);
            assertEquals(PlanToolResult.Status.APPLIED, revisedResult.status());
            assertEquals(2, revisedResult.version());
            assertTrue(stateManager.load(APP_ID).orElseThrow()
                    .files().stream().anyMatch(file ->
                            file.path().equals("src/router/index.js")));
        } finally {
            lease.close();
            operation.close();
        }
    }

    @Test
    void 只读在线作用域拒绝计划变更() throws Exception {
        Path root = Files.createTempDirectory("plan-readonly-");
        AppPlanStateManager stateManager = new AppPlanStateManager(
                new ObjectMapper(), new PlanStoragePathResolver(root));
        FileToolExecutionScopeManager scopeManager =
                new FileToolExecutionScopeManager(new FileToolBudgetGuard());
        AppOperationLeaseManager operationManager = new AppOperationLeaseManager();
        var operation = operationManager.acquire(
                APP_ID + 1, AppOperationLeaseManager.AppOperationType.GENERATE, TURN_ID);
        var lease = new VueBuildSessionManager().open(
                operation, USER_ID, TURN_ID);
        var scope = scopeManager.online(
                lease, TURN_ID, APP_ID + 1, TOOLS,
                new FileToolBudgetGuard().newSession(), () -> false);
        try {
            MakePlanTool makePlan = new MakePlanTool(stateManager, scopeManager);
            PlanToolResult result = PlanToolProtocolSupport.parse(
                    scopeManager.callInScope(scope, "makePlan", () -> makePlan.makePlan(
                            "不应写入", "[]", APP_ID + 1)));
            assertEquals(PlanToolResult.Status.REJECTED, result.status());
            assertTrue(stateManager.load(APP_ID + 1).isEmpty());
        } finally {
            lease.close();
            operation.close();
        }
    }

    @Test
    void 待处理Replan阻止文件变更并在修订后解除() throws Exception {
        Path root = Files.createTempDirectory("plan-guard-");
        AppPlanStateManager stateManager = new AppPlanStateManager(
                new ObjectMapper(), new PlanStoragePathResolver(root));
        FileToolBudgetGuard budgetGuard = new FileToolBudgetGuard();
        FileToolExecutionScopeManager scopeManager =
                new FileToolExecutionScopeManager(budgetGuard);
        AppOperationLeaseManager operationManager = new AppOperationLeaseManager();
        long appId = APP_ID + 2;
        var operation = operationManager.acquire(
                appId, AppOperationLeaseManager.AppOperationType.GENERATE, TURN_ID);
        var lease = new VueBuildSessionManager().open(operation, USER_ID, TURN_ID);
        ReplanContext replanContext = new ReplanContext();
        var scope = scopeManager.online(
                lease, TURN_ID, appId,
                Set.of("makePlan", "updatePlan", "writeFile"),
                budgetGuard.newSession(), () -> true, replanContext);
        Path projectRoot = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR,
                "vue_project_" + appId);
        try {
            MakePlanTool makePlan = new MakePlanTool(stateManager, scopeManager);
            UpdatePlanTool updatePlan = new UpdatePlanTool(stateManager, scopeManager);
            FileWriteTool writeFile = new FileWriteTool(
                    new com.lyw.appgeneration.manger.AppFileStateManager(), scopeManager);
            scopeManager.callInScope(scope, "makePlan", () -> makePlan.makePlan(
                    "创建页面", """
                            [{"path":"src/App.vue","purpose":"入口","action":"MODIFY","dependsOn":[]}]
                            """, appId));
            replanContext.observe(new ReplanContext.PlanDeviation(
                    "trigger-guard", "计划路径不一致", "测试偏差"));

            JSONObject rejected = cn.hutool.json.JSONUtil.parseObj(
                    scopeManager.callInScope(scope, "writeFile",
                            () -> writeFile.writeFile(
                                    "src/App.vue", "不应落盘", appId)));
            assertEquals("REJECTED", rejected.getStr("status"));
            assertTrue(rejected.getStr("message").contains("updatePlan"));
            assertTrue(!Files.exists(projectRoot.resolve("src/App.vue")));

            scopeManager.callInScope(scope, "updatePlan", () -> updatePlan.updatePlan(
                    "修订入口路径", "[]", "[]", """
                            [{"path":"src/App.vue","purpose":"入口","action":"MODIFY","dependsOn":[]}]
                            """, appId));
            assertTrue(!replanContext.replanPending());
        } finally {
            if (Files.exists(projectRoot)) {
                try (var paths = Files.walk(projectRoot)) {
                    paths.sorted(java.util.Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (java.io.IOException exception) {
                                    throw new RuntimeException(exception);
                                }
                            });
                }
            }
            lease.close();
            operation.close();
        }
    }

    @Test
    void 取消和评测作用域都不能写入计划() throws Exception {
        Path root = Files.createTempDirectory("plan-scope-");
        AppPlanStateManager stateManager = new AppPlanStateManager(
                new ObjectMapper(), new PlanStoragePathResolver(root));
        FileToolExecutionScopeManager scopeManager =
                new FileToolExecutionScopeManager(new FileToolBudgetGuard());
        MakePlanTool makePlan = new MakePlanTool(stateManager, scopeManager);

        AppOperationLeaseManager operations = new AppOperationLeaseManager();
        var operation = operations.acquire(
                APP_ID + 3, AppOperationLeaseManager.AppOperationType.GENERATE, TURN_ID);
        var lease = new VueBuildSessionManager().open(operation, USER_ID, TURN_ID);
        var onlineScope = scopeManager.online(
                lease, TURN_ID, APP_ID + 3, Set.of("makePlan"),
                new FileToolBudgetGuard().newSession(), () -> true);
        try {
            lease.cancel();
            PlanToolResult cancelled = PlanToolProtocolSupport.parse(
                    makePlan.makePlan("取消后不得写入", "[]", APP_ID + 3));
            assertEquals(PlanToolResult.Status.REJECTED, cancelled.status());
        } finally {
            lease.close();
            operation.close();
        }

        var evaluationScope = scopeManager.evaluation(
                APP_ID + 4, "evaluation-plan", Set.of("makePlan"));
        PlanToolResult evaluation = PlanToolProtocolSupport.parse(
                scopeManager.callInScope(evaluationScope, "makePlan", () ->
                        makePlan.makePlan("评测不得写入", "[]", APP_ID + 4)));
        assertEquals(PlanToolResult.Status.REJECTED, evaluation.status());
    }

    @Test
    void 空完整计划和悬空依赖都会被拒绝() throws Exception {
        Path root = Files.createTempDirectory("plan-input-validation-");
        AppPlanStateManager stateManager = new AppPlanStateManager(
                new ObjectMapper(), new PlanStoragePathResolver(root));
        FileToolExecutionScopeManager scopeManager =
                new FileToolExecutionScopeManager(new FileToolBudgetGuard());
        AppOperationLeaseManager operations = new AppOperationLeaseManager();
        long appId = APP_ID + 20;
        var operation = operations.acquire(
                appId, AppOperationLeaseManager.AppOperationType.GENERATE, TURN_ID);
        var lease = new VueBuildSessionManager().open(operation, USER_ID, TURN_ID);
        var scope = scopeManager.online(
                lease, TURN_ID, appId, TOOLS,
                new FileToolBudgetGuard().newSession(), () -> true);
        MakePlanTool makePlan = new MakePlanTool(stateManager, scopeManager);
        try {
            PlanToolResult empty = PlanToolProtocolSupport.parse(
                    scopeManager.callInScope(scope, "makePlan", () ->
                            makePlan.makePlan("空计划", "[]", appId)));
            assertEquals(PlanToolResult.Status.REJECTED, empty.status());
            assertTrue(stateManager.load(appId).isEmpty());

            PlanToolResult dangling = PlanToolProtocolSupport.parse(
                    scopeManager.callInScope(scope, "makePlan", () ->
                            makePlan.makePlan("悬空依赖", """
                                    [{"path":"B.vue","purpose":"页面","action":"MODIFY","dependsOn":["A.vue"]}]
                                    """, appId)));
            assertEquals(PlanToolResult.Status.REJECTED, dangling.status());
            assertTrue(stateManager.load(appId).isEmpty());

            PlanToolResult protectedPath = PlanToolProtocolSupport.parse(
                    scopeManager.callInScope(scope, "makePlan", () ->
                            makePlan.makePlan("受保护路径", """
                                    [{"path":"node_modules/pkg/index.js","purpose":"依赖","action":"MODIFY","dependsOn":[]}]
                                    """, appId)));
            assertEquals(PlanToolResult.Status.REJECTED, protectedPath.status());
            assertTrue(stateManager.load(appId).isEmpty());
        } finally {
            lease.close();
            operation.close();
        }
    }

    @Test
    void 计划外成功事实在归档前触发Replan() throws Exception {
        Path root = Files.createTempDirectory("plan-observe-order-");
        AppPlanStateManager stateManager = new AppPlanStateManager(
                new ObjectMapper(), new PlanStoragePathResolver(root));
        FileToolBudgetGuard budgetGuard = new FileToolBudgetGuard();
        FileToolExecutionScopeManager scopeManager =
                new FileToolExecutionScopeManager(budgetGuard, stateManager);
        AppOperationLeaseManager operations = new AppOperationLeaseManager();
        long appId = APP_ID + 21;
        var operation = operations.acquire(
                appId, AppOperationLeaseManager.AppOperationType.GENERATE, TURN_ID);
        var lease = new VueBuildSessionManager().open(operation, USER_ID, TURN_ID);
        ReplanContext replan = new ReplanContext();
        replan.setDetector(new ReplanDetector(
                stateManager, appId, TURN_ID)::detect);
        var scope = scopeManager.online(
                lease, TURN_ID, appId, Set.of("writeFile"),
                budgetGuard.newSession(), () -> true, replan);
        Path projectRoot = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR,
                "vue_project_" + appId);
        try {
            AppPlan plan = new AppPlan(
                    "plan-order", TURN_ID, TURN_ID, 1, 1, PlanMode.FULL,
                    "顺序测试", java.util.List.of(new PlanFile(
                    "src/App.vue", "入口", PlanFileAction.MODIFY,
                    java.util.List.of(), PlanFileState.PENDING)),
                    java.util.List.of(), PlanStatus.PLANNED);
            stateManager.save(appId, plan, 0, TURN_ID);
            FileWriteTool writeFile = new FileWriteTool(
                    new com.lyw.appgeneration.manger.AppFileStateManager(),
                    scopeManager);

            scopeManager.callInScope(scope, "writeFile", () ->
                    writeFile.writeFile("src/Outside.vue", "计划外", appId));

            AppPlan observed = stateManager.load(appId).orElseThrow();
            assertEquals(PlanStatus.REPLAN_PENDING, observed.status());
            assertTrue(observed.files().stream().anyMatch(file ->
                    file.path().equals("src/Outside.vue")
                            && file.state() == PlanFileState.OUT_OF_PLAN));
        } finally {
            lease.close();
            operation.close();
            if (Files.exists(projectRoot)) {
                try (var paths = Files.walk(projectRoot)) {
                    paths.sorted(java.util.Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (java.io.IOException exception) {
                                    throw new RuntimeException(exception);
                                }
                            });
                }
            }
        }
    }
}
