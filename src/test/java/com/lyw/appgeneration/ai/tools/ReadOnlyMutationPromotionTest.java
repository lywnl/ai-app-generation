package com.lyw.appgeneration.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.ai.VueToolNames;
import com.lyw.appgeneration.ai.plan.*;
import com.lyw.appgeneration.constants.AppConstant;
import com.lyw.appgeneration.core.builder.*;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import com.lyw.appgeneration.core.concurrency.VueTurnAdmissionController;
import com.lyw.appgeneration.core.handler.VueTurnContext;
import com.lyw.appgeneration.core.handler.VueTurnMode;
import com.lyw.appgeneration.monitor.VueBuildRepairMetricsCollector;
import dev.langchain4j.service.ToolExecutionGuard;
import dev.langchain4j.service.ToolLoopTerminationProtocol;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

public class ReadOnlyMutationPromotionTest {
    @Test
    void 真实修改绑定旧计划并构建且初始分类不变() throws Exception {
        try (Harness h = new Harness(true)) {
            var changed = h.modify();
            assertTrue(FileToolProtocolSupport.isAppliedMutation(changed.toolResult(), "modifyFile"));
            assertEquals(VueTurnMode.READ_ONLY, h.context.turnMode());
            assertTrue(h.context.requiresBuild());
            assertNotNull(changed.mutationPromotion());
            assertTrue(changed.buildObservation().trustedProgress());
            assertNull(changed.buildObservation().before());
            AppPlan plan = h.plan();
            assertEquals(h.turn, plan.activeTurnId());
            assertEquals(4, plan.version());
            assertEquals(PlanFileState.TOUCHED, plan.files().getFirst().state());
            assertTrue(changed.mutationPromotion().planSummary().get().contains("version=4"));
            var built = h.build();
            assertTrue(built.toolResult().contains("\"success\":true"));
            assertEquals(1, h.context.lease().snapshot().buildAttempt());
            assertEquals(PlanStatus.BUILT, h.plan().status());
        }
    }

    @Test
    void 无变更和失败不升级也不触碰旧计划() throws Exception {
        try (Harness h = new Harness(true)) {
            byte[] before = Files.readAllBytes(h.root.resolve(".plan.json"));
            for (String old : List.of("不存在", "登录入口")) {
                var noChange = h.invoke("modifyFile", () -> h.modifier.modifyFile(h.path, old, old, h.appId));
                assertNull(noChange.mutationPromotion());
                assertFalse(h.context.requiresBuild());
            }
            var missing = h.invoke("modifyFile", () -> h.modifier.modifyFile("missing.vue", "a", "b", h.appId));
            assertTrue(missing.toolResult().contains("NOT_FOUND"));
            assertFalse(h.context.requiresBuild());
            assertTrue(h.build().toolResult().contains("只读"));
            assertTrue(h.keepPlan().toolResult().contains("REJECTED"));
            assertArrayEquals(before, Files.readAllBytes(h.root.resolve(".plan.json")));
            assertEquals(0, h.context.lease().snapshot().buildAttempt());
        }
    }

    @Test
    void 无计划补KEEP允许构建而MODIFY仍需后续真实修改() throws Exception {
        for (String action : List.of("KEEP", "MODIFY")) {
            try (Harness h = new Harness(false)) {
                h.modify();
                assertTrue(h.build().toolResult().contains("makePlan"));
                var made = h.invoke("makePlan", () -> h.maker.makePlan("登录入口", h.files(action), h.appId));
                assertTrue(made.toolResult().contains("APPLIED"));
                assertEquals(PlanFileState.PENDING, h.plan().files().getFirst().state());
                if (action.equals("MODIFY")) {
                    assertTrue(h.build().toolResult().contains("REJECTED"));
                    var kept = h.keepPlan();
                    assertTrue(kept.toolResult().contains("APPLIED"));
                }
                assertTrue(h.build().toolResult().contains("\"success\":true"));
            }
        }
    }

    @Test
    void 首次计划外修改触发偏差并可用KEEP解释() throws Exception {
        try (Harness h = new Harness(true)) {
            Files.writeString(h.root.resolve("Other.vue"), "a");
            h.invoke("modifyFile", () -> h.modifier.modifyFile("Other.vue", "a", "b", h.appId));
            assertEquals(1, h.context.replanContext().feedbackCount());
            assertTrue(h.context.replanContext().replanPending());
            assertEquals(PlanFileState.OUT_OF_PLAN, h.plan().files().getLast().state());
            assertTrue(h.build().toolResult().contains("REJECTED"));
            var fixed = h.invoke("updatePlan", () -> h.updater.updatePlan("已经修改完毕，无需再改", "[]", "[]",
                    "[{\"path\":\"Other.vue\",\"purpose\":\"保留完成的变更\",\"action\":\"KEEP\",\"dependsOn\":[]}]", h.appId));
            assertTrue(fixed.toolResult().contains("APPLIED"));
            assertFalse(h.context.replanContext().replanPending());
            assertTrue(h.build().toolResult().contains("\"success\":true"));
        }
    }

    @Test
    void 初始化只执行一次且保留恢复的待修订状态() throws Exception {
        try (Harness h = new Harness(true)) {
            AppPlan old = h.plan();
            h.manager.save(h.appId, new AppPlan(old.planId(), old.lastModifiedTurnId(), old.activeTurnId(),
                    old.version(), old.round(), old.mode(), old.summary(), old.files(), old.history(), PlanStatus.REPLAN_PENDING),
                    old.version(), old.activeTurnId());
            h.modify();
            assertTrue(h.context.replanContext().replanPending());
            h.context.replanContext().acknowledgePlanUpdate();
            h.context.initializePlanContext(h.manager);
            assertFalse(h.context.replanContext().replanPending(), "重复初始化不得恢复旧 pending");
        }
    }

    @Test
    void 连续真实修改只升级一次且累计真实序号() throws Exception {
        try (Harness h = new Harness(true)) {
            assertNotNull(h.modify().mutationPromotion());
            var next = h.invoke("modifyFile", () -> h.modifier.modifyFile(h.path, "显著登录按钮", "最终登录按钮", h.appId));
            assertNull(next.mutationPromotion());
            assertEquals(2, h.context.lease().snapshot().mutationRevision());
            assertEquals(4, h.plan().version());
            assertEquals(h.turn, h.plan().activeTurnId());
        }
    }

    @Test
    void 取消先于初始化不得重绑定计划且下一回合独立() throws Exception {
        try (Harness h = new Harness(true)) {
            byte[] before = Files.readAllBytes(h.root.resolve(".plan.json"));
            h.context.lease().cancel();
            assertThrows(RuntimeException.class, () -> h.context.initializePlanContext(h.manager));
            assertArrayEquals(before, Files.readAllBytes(h.root.resolve(".plan.json")));
        }
        try (Harness next = new Harness(true)) {
            assertFalse(next.context.requiresBuild());
            assertNotNull(next.modify().mutationPromotion());
        }
    }

    @Test
    void 损坏计划初始化失败仍保留APPLIED与构建义务() throws Exception {
        try (Harness h = new Harness(false)) {
            Files.writeString(h.root.resolve(".plan.json"), "invalid json");
            var result = h.modify();
            assertTrue(FileToolProtocolSupport.isAppliedMutation(result.toolResult(), "modifyFile"));
            assertEquals("显著登录按钮", Files.readString(h.root.resolve(h.path)));
            assertEquals(1, h.context.lease().snapshot().mutationRevision());
            assertTrue(h.context.requiresBuild());
            assertNull(result.buildObservation());
            assertNull(result.mutationPromotion());
            assertEquals(ToolLoopTerminationProtocol.ControlledTerminationReason.PLAN_INITIALIZATION_FAILED,
                    result.controlledTermination().reason());
        }
    }

    /** 真实工具与隔离工程，构建器仅替换 npm 执行，不访问模型或数据库。 */
    public static final class Harness implements AutoCloseable {
        private static final AtomicLong IDS = new AtomicLong(8_901_000);
        public final long appId = IDS.incrementAndGet();
        public final String turn = "promotion-" + appId;
        public final String path = "src/components/SiteHeader.vue";
        public final Path root = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_" + appId);
        public final AppPlanStateManager manager;
        public final VueTurnContext context;
        public final FileToolExecutionScopeManager scopes;
        public final FileToolExecutionScopeManager.FileToolScope scope;
        final FileModifyTool modifier;
        final MakePlanTool maker;
        final UpdatePlanTool updater;
        final BuildProjectTool builder;

        public Harness(boolean existingPlan) throws Exception {
            if (Files.exists(root)) throw new IllegalStateException("隔离工程已存在，禁止覆盖");
            Files.createDirectories(root.resolve(path).getParent());
            Files.writeString(root.resolve(path), "登录入口");
            manager = new AppPlanStateManager(new ObjectMapper(), new PlanStoragePathResolver(Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR)));
            scopes = new FileToolExecutionScopeManager(new FileToolBudgetGuard(), manager);
            var operation = new AppOperationLeaseManager().acquire(appId, AppOperationLeaseManager.AppOperationType.GENERATE, turn);
            var lease = new VueBuildSessionManager().open(operation, 9, turn);
            var metrics = new VueBuildRepairMetricsCollector(new SimpleMeterRegistry());
            context = new VueTurnContext(appId, 9, turn, operation, lease,
                    new VueTurnAdmissionController(metrics).tryAcquire().orElseThrow(), new FileToolBudgetGuard().newSession());
            scope = scopes.online(lease, turn, appId, Set.copyOf(VueToolNames.ONLINE), context.budgetSession(),
                    context::requiresBuild, context.replanContext(), () -> context.initializePlanContext(manager));
            modifier = new FileModifyTool(scopes);
            maker = new MakePlanTool(manager, scopes);
            updater = new UpdatePlanTool(manager, scopes);
            builder = new BuildProjectTool(new VueProjectBuilder() {
                @Override public BuildResult buildProjectDetailed(Path projectRoot, BuildExecutionContext execution) {
                    return new BuildResult(true, BuildStage.SUCCESS, 0, false, false, null, "成功", 1);
                }
            }, (projectRoot, result) -> "", scopes, metrics, manager);
            if (existingPlan) manager.save(appId, new AppPlan("old-plan", "old-turn", "old-turn", 4, 1, PlanMode.FULL,
                    "登录导航", List.of(new PlanFile(path, "导航栏", PlanFileAction.MODIFY, List.of(), PlanFileState.TOUCHED)),
                    List.of(), PlanStatus.BUILT), 0, "old-turn");
        }

        public ToolExecutionGuard.GuardedToolExecution invoke(String name, Supplier<String> action) {
            return scopes.callInScopeWithBuildObservation(scope, name, action);
        }
        public ToolExecutionGuard.GuardedToolExecution modify() {
            return invoke("modifyFile", () -> modifier.modifyFile(path, "登录入口", "显著登录按钮", appId));
        }
        public ToolExecutionGuard.GuardedToolExecution build() { return invoke("buildProject", () -> builder.buildProject(appId)); }
        public AppPlan plan() { return manager.load(appId).orElseThrow(); }
        String files(String action) { return "[{\"path\":\"" + path + "\",\"purpose\":\"导航\",\"action\":\"" + action + "\",\"dependsOn\":[]}]"; }
        ToolExecutionGuard.GuardedToolExecution keepPlan() {
            return invoke("updatePlan", () -> updater.updatePlan("已经完成无需再改", "[]", "[]", files("KEEP"), appId));
        }
        @Override public void close() throws Exception {
            context.closeResources();
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }
}
