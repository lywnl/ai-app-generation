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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanToolTest {

    private static final long APP_ID = 9001001L;
    private static final long USER_ID = 9001002L;
    private static final String TURN_ID = "plan-tool-turn";
    private static final Set<String> TOOLS = Set.of("makePlan", "updatePlan");
    private static final AtomicLong REVISION_APP_IDS = new AtomicLong(System.currentTimeMillis());

    @Test
    void 修订路由后真实写回只需修改路由而不必重写入口() throws Exception {
        PlanFile router = touched("src/router/index.js", List.of());
        PlanFile main = touched("src/main.js", List.of(router.path()));
        try (RevisionHarness harness = new RevisionHarness(List.of(router, main))) {
            Path mainPath = harness.projectRoot.resolve(main.path());
            Path routerPath = harness.projectRoot.resolve(router.path());
            Files.createDirectories(routerPath.getParent());
            Files.writeString(mainPath, "import router from './router/index.js'\n");
            Files.writeString(routerPath, "export default []\n");
            byte[] originalMain = Files.readAllBytes(mainPath);
            var mainModified = Files.getLastModifiedTime(mainPath);

            PlanToolResult result = harness.update("[]", "[]", harness.inputs(List.of(router)));
            assertEquals(PlanToolResult.Status.APPLIED, result.status());
            assertTrue(result.message().contains(main.path()));
            assertEquals(2, result.version());
            assertEquals(PlanFileState.PENDING, result.files().getFirst().state());
            assertEquals(main, result.files().get(1));
            assertFalse(harness.manager.beforeBuild(harness.appId, TURN_ID).allowed());
            assertEquals(0, harness.replan.feedbackCount());
            assertFalse(harness.replan.replanPending());
            assertEquals(PlanStatus.PLANNED, harness.current().status());

            String raw = harness.scopeManager.callInScope(harness.scope, "writeFile",
                    () -> harness.writer.writeFile(router.path(), "export default ['/login']\n", harness.appId));
            assertTrue(FileToolProtocolSupport.isAppliedMutation(raw, "writeFile"));
            assertTrue(harness.manager.beforeBuild(harness.appId, TURN_ID).allowed());
            assertEquals(PlanStatus.READY_TO_BUILD, harness.current().status());
            assertEquals(main, harness.current().files().get(1));
            assertArrayEquals(originalMain, Files.readAllBytes(mainPath));
            assertEquals(mainModified, Files.getLastModifiedTime(mainPath));
            assertEquals(1, harness.lease.snapshot().mutationRevision());
            assertEquals(List.of(router.path()), harness.current().history().getFirst().modified()
                    .stream().map(PlanFile::path).toList());
        }
    }

    @Test
    void 检查提示按计划顺序去重并限制二十条且无影响保持默认文案() throws Exception {
        PlanFile a = touched("A.vue", List.of());
        PlanFile b = touched("B.vue", List.of("A.vue"));
        List<PlanFile> files = new java.util.ArrayList<>(List.of(a, b));
        for (int index = 0; index < 23; index++) {
            files.add(touched("D%02d.vue".formatted(index), List.of("A.vue", "B.vue")));
        }
        try (RevisionHarness harness = new RevisionHarness(files)) {
            PlanToolResult result = harness.update("[]", "[]", harness.inputs(List.of(a, b)));
            assertEquals(PlanToolResult.Status.APPLIED, result.status());
            assertTrue(result.message().contains("D00.vue、D01.vue"));
            assertTrue(result.message().contains("D19.vue"));
            assertFalse(result.message().contains("D20.vue"));
            assertFalse(result.message().contains("A.vue"));
            assertFalse(result.message().contains("B.vue"));
            assertTrue(result.message().contains("另有 3 个受影响文件"));
            assertEquals(1, result.message().split("D00.vue", -1).length - 1);
            assertEquals(PlanFileState.PENDING, result.files().get(1).state());
            assertEquals(files.subList(2, files.size()), result.files().subList(2, files.size()));
            PlanToolResult leaf = harness.update("[]", "[]", harness.inputs(List.of(files.getLast())));
            assertEquals("计划已保存", leaf.message());
        }
    }

    @Test
    void 非法修订拒绝后计划字节和版本不变且没有成功检查提示() throws Exception {
        PlanFile a = touched("A.vue", List.of());
        PlanFile b = touched("B.vue", List.of("A.vue"));
        try (RevisionHarness harness = new RevisionHarness(List.of(a, b))) {
            byte[] before = Files.readAllBytes(harness.planPath);
            List<PlanToolResult> failures = List.of(
                    harness.update("[]", "[\"A.vue\"]", "[]"),
                    harness.update("[]", "[]", harness.inputs(List.of(touched("A.vue", List.of("A.vue"))))),
                    harness.update("[]", "[]", harness.inputs(List.of(touched("A.vue", List.of("B.vue"))))));
            for (PlanToolResult failure : failures) {
                assertEquals(PlanToolResult.Status.REJECTED, failure.status());
                assertFalse(failure.message().contains("计划已保存"));
            }
            assertArrayEquals(before, Files.readAllBytes(harness.planPath));
            assertEquals(1, harness.current().version());
            assertTrue(harness.current().history().isEmpty());
            assertEquals(0, harness.replan.feedbackCount());
        }
    }

    @Test
    void 合法删除重写依赖不会删除物理文件或重置其余依赖者() throws Exception {
        PlanFile a = touched("A.vue", List.of());
        PlanFile b = touched("B.vue", List.of("A.vue"));
        PlanFile c = touched("C.vue", List.of("B.vue"));
        try (RevisionHarness harness = new RevisionHarness(List.of(a, b, c))) {
            Files.writeString(harness.projectRoot.resolve("A.vue"), "保留物理文件");
            PlanToolResult result = harness.update("[]", "[\"A.vue\"]",
                    harness.inputs(List.of(touched("B.vue", List.of()))));
            assertEquals(PlanToolResult.Status.APPLIED, result.status());
            assertEquals(List.of("B.vue", "C.vue"), result.files().stream().map(PlanFile::path).toList());
            assertEquals(c, result.files().getLast());
            assertTrue(result.message().contains("C.vue"));
            assertEquals("保留物理文件", Files.readString(harness.projectRoot.resolve("A.vue")));
            assertEquals(List.of(a), harness.current().history().getFirst().removed());
        }
    }

    @Test
    void 提交前取消不写入新版本或返回成功提示() throws Exception {
        PlanFile a = touched("A.vue", List.of());
        try (RevisionHarness harness = new RevisionHarness(List.of(a))) {
            byte[] before = Files.readAllBytes(harness.planPath);
            String modify = harness.inputs(List.of(a));
            PlanToolResult result = PlanToolProtocolSupport.parse(harness.scopeManager.callInScope(
                    harness.scope, "updatePlan", () -> {
                        harness.lease.cancel();
                        return harness.tool.updatePlan("取消后的修订", "[]", "[]", modify, harness.appId);
                    }));
            assertEquals(PlanToolResult.Status.REJECTED, result.status());
            assertFalse(result.message().contains("计划已保存"));
            assertArrayEquals(before, Files.readAllBytes(harness.planPath));
        }
    }

    private static PlanFile touched(String path, List<String> dependsOn) {
        return new PlanFile(path, "文件用途", PlanFileAction.MODIFY, dependsOn, PlanFileState.TOUCHED);
    }

    private static final class RevisionHarness implements AutoCloseable {
        private final long appId = REVISION_APP_IDS.incrementAndGet();
        private final ObjectMapper mapper = new ObjectMapper();
        private final Path planRoot = Files.createTempDirectory("plan-revision-");
        private final Path planPath = planRoot.resolve("vue_project_" + appId).resolve(".plan.json");
        private final Path projectRoot = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_" + appId);
        private final AppPlanStateManager manager = new AppPlanStateManager(mapper, new PlanStoragePathResolver(planRoot));
        private final FileToolBudgetGuard budget = new FileToolBudgetGuard();
        private final FileToolExecutionScopeManager scopeManager = new FileToolExecutionScopeManager(budget, manager);
        private final AppOperationLeaseManager.AppOperationLease operation;
        private final VueBuildSessionManager.VueBuildLease lease;
        private final ReplanContext replan = new ReplanContext();
        private final FileToolExecutionScopeManager.FileToolScope scope;
        private final UpdatePlanTool tool = new UpdatePlanTool(manager, scopeManager);
        private final FileWriteTool writer = new FileWriteTool(new com.lyw.appgeneration.manger.AppFileStateManager(), scopeManager);

        private RevisionHarness(List<PlanFile> files) throws Exception {
            Files.createDirectories(projectRoot.getParent());
            Files.createDirectory(projectRoot);
            operation = new AppOperationLeaseManager().acquire(appId,
                    AppOperationLeaseManager.AppOperationType.GENERATE, TURN_ID);
            lease = new VueBuildSessionManager().open(operation, USER_ID, TURN_ID);
            replan.setDetector(new ReplanDetector(manager, appId, TURN_ID)::detect);
            scope = scopeManager.online(lease, TURN_ID, appId, Set.of("updatePlan", "writeFile"),
                    budget.newSession(), () -> true, replan);
            manager.save(appId, new AppPlan("revision-plan", TURN_ID, TURN_ID, 1, 1,
                    PlanMode.FULL, "修改页面", files, List.of(), PlanStatus.BUILT), 0, TURN_ID);
        }

        private String inputs(List<PlanFile> files) throws Exception {
            return mapper.writeValueAsString(files.stream().map(file -> Map.of(
                    "path", file.path(), "purpose", file.purpose(), "action", file.action(),
                    "dependsOn", file.dependsOn())).toList());
        }

        private PlanToolResult update(String add, String remove, String modify) {
            return PlanToolProtocolSupport.parse(scopeManager.callInScope(scope, "updatePlan",
                    () -> tool.updatePlan("调整路由", add, remove, modify, appId)));
        }

        private AppPlan current() {
            return manager.loadReadOnly(appId).orElseThrow();
        }

        @Override
        public void close() throws Exception {
            lease.close();
            operation.close();
            for (Path root : List.of(planRoot, projectRoot)) {
                try (var paths = Files.walk(root)) {
                    for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        Files.delete(path);
                    }
                }
            }
        }
    }

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

            byte[] beforeRepeatedCreate = Files.readAllBytes(root.resolve("vue_project_" + APP_ID).resolve(".plan.json"));
            PlanToolResult repeated = PlanToolProtocolSupport.parse(scopeManager.callInScope(scope, "makePlan",
                    () -> makePlan.makePlan("不应覆盖原计划", "[]", APP_ID)));
            assertEquals(PlanToolResult.Status.REJECTED, repeated.status());
            assertEquals("当前项目已有计划，请调用 updatePlan 修订", repeated.message());
            assertArrayEquals(beforeRepeatedCreate,
                    Files.readAllBytes(root.resolve("vue_project_" + APP_ID).resolve(".plan.json")));

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
            assertEquals(PlanFileState.PENDING, revisedResult.files().stream()
                    .filter(file -> file.path().equals("src/router/index.js"))
                    .findFirst().orElseThrow().state());
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
