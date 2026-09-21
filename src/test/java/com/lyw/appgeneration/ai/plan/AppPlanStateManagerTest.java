package com.lyw.appgeneration.ai.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppPlanStateManagerTest {

    @Test
    void 新回合可以接管上一版本并按期望版本提交() throws Exception {
        Path root = Files.createTempDirectory("plan-state-");
        AppPlanStateManager manager = manager(root);
        AppPlan first = plan("turn-1", "turn-1", 1, 1, List.of());

        manager.save(7, first, 0, "turn-1");
        AppPlan rebound = manager.loadForTurn(7, "turn-2").orElseThrow();
        AppPlan updated = plan("turn-1", "turn-2", 2, 2, List.of());
        manager.save(7, updated, rebound.version(), "turn-2");

        AppPlan loaded = manager.load(7).orElseThrow();
        assertEquals("turn-1", loaded.lastModifiedTurnId());
        assertEquals("turn-2", loaded.activeTurnId());
        assertEquals(2, loaded.version());
    }

    @Test
    void 旧版本提交被拒绝且历史会限制长度() throws Exception {
        Path root = Files.createTempDirectory("plan-history-");
        AppPlanStateManager manager = manager(root);
        AppPlan first = plan("turn-1", "turn-1", 1, 1, List.of());
        manager.save(8, first, 0, "turn-1");

        List<PlanChange> history = IntStream.rangeClosed(1, 25)
                .mapToObj(version -> new PlanChange(
                        version, "turn-1", "第" + version + "次修订",
                        List.of(), List.of(), List.of()))
                .toList();
        AppPlan updated = plan("turn-1", "turn-1", 2, 1, history);
        manager.save(8, updated, 1, "turn-1");
        AppPlan tooOld = plan("turn-1", "turn-1", 3, 1, history);
        assertThrows(AppPlanStateManager.PlanVersionConflictException.class,
                () -> manager.save(8, tooOld, 1, "turn-1"));

        assertTrue(Files.isRegularFile(root.resolve("vue_project_8/.plan.json")));
        assertEquals(
                AppPlanStateManager.MAX_HISTORY_SIZE,
                manager.load(8).orElseThrow().history().size());
    }

    @Test
    void 计划上下文只展示当前待处理文件() {
        AppPlanStateManager manager = manager(Path.of("/tmp/plan-context-test"));
        PlanFile pending = new PlanFile(
                "src/App.vue", "页面入口", PlanFileAction.MODIFY,
                List.of(), PlanFileState.PENDING);
        AppPlan plan = new AppPlan(
                "plan-1", "turn-1", "turn-1", 1, 1, PlanMode.FULL,
                "创建页面", List.of(pending), List.of(), PlanStatus.PLANNED);

        String context = manager.toPromptContext(plan);

        assertTrue(context.contains("version=1"));
        assertTrue(context.contains("src/App.vue"));
        assertTrue(context.contains("当前项目已有计划，不要再次调用 makePlan"));
        assertTrue(context.contains("当前状态为 REPLAN_PENDING 时，先调用 updatePlan"));
        assertTrue(context.contains("仅继续执行现有计划时直接沿用"));
    }

    @Test
    void 计划文件本身是符号链接时不读取外部内容() throws Exception {
        Path root = Files.createTempDirectory("plan-link-");
        Path project = Files.createDirectories(root.resolve("vue_project_9"));
        Path external = Files.writeString(
                Files.createTempFile("external-plan-", ".json"), "{}");
        Files.createSymbolicLink(project.resolve(".plan.json"), external);

        AppPlanStateManager manager = manager(root);

        assertTrue(manager.load(9).isEmpty());
    }

    @Test
    void 新回合接管后旧回合不能用旧版本覆盖() throws Exception {
        Path root = Files.createTempDirectory("plan-turn-cas-");
        AppPlanStateManager manager = manager(root);
        AppPlan first = plan("turn-1", "turn-1", 1, 1, List.of());

        manager.save(10, first, 0, "turn-1");
        AppPlan rebound = manager.loadForTurn(10, "turn-2").orElseThrow();
        AppPlan stale = plan("turn-1", "turn-1", 2, 1, List.of());

        assertThrows(AppPlanStateManager.PlanTurnConflictException.class,
                () -> manager.save(10, stale, rebound.version(), "turn-1"));
        assertEquals("turn-2", manager.load(10).orElseThrow().activeTurnId());
        assertEquals(1, manager.load(10).orElseThrow().version());
    }

    @Test
    void 只重置显式文件并提示间接依赖且仍拒绝环() throws Exception {
        Path root = Files.createTempDirectory("plan-dependency-");
        AppPlanStateManager manager = manager(root);
        PlanFile a = new PlanFile(
                "A.vue", "基础组件", PlanFileAction.MODIFY,
                List.of(), PlanFileState.TOUCHED);
        PlanFile b = new PlanFile(
                "B.vue", "页面组件", PlanFileAction.MODIFY,
                List.of("A.vue"), PlanFileState.TOUCHED);
        PlanFile c = new PlanFile(
                "C.vue", "路由入口", PlanFileAction.MODIFY,
                List.of("B.vue"), PlanFileState.TOUCHED);

        List<PlanFile> rebased = manager.resetExplicitlyChangedStates(
                List.of(a, b, c), Set.of("A.vue"));
        assertEquals(PlanFileState.PENDING, rebased.getFirst().state());
        assertEquals(b, rebased.get(1));
        assertEquals(c, rebased.get(2));
        assertEquals(List.of("B.vue", "C.vue"), manager.indirectlyAffectedPaths(
                rebased, Set.of("A.vue")));
        assertThrows(IllegalArgumentException.class, () ->
                manager.validatePlanFiles(List.of(
                        new PlanFile("A.vue", "A", PlanFileAction.MODIFY,
                                List.of("B.vue"), PlanFileState.PENDING),
                        new PlanFile("B.vue", "B", PlanFileAction.MODIFY,
                                List.of("A.vue"), PlanFileState.PENDING))));
    }

    @Test
    void 间接文件保留各种状态和元数据而显式KEEP不重置() throws Exception {
        AppPlanStateManager manager = manager(Files.createTempDirectory("plan-states-"));
        PlanFile upstream = new PlanFile("A.vue", "上游", PlanFileAction.MODIFY,
                List.of(), PlanFileState.TOUCHED);
        List<PlanFile> files = new java.util.ArrayList<>(List.of(upstream));
        for (PlanFileState state : PlanFileState.values()) {
            files.add(new PlanFile(state + ".vue", "保留完整元数据", PlanFileAction.CREATE,
                    List.of("A.vue"), state));
        }
        PlanFile keep = new PlanFile("Keep.vue", "无需变更", PlanFileAction.KEEP,
                List.of("A.vue"), PlanFileState.TOUCHED);
        files.add(keep);
        List<PlanFile> reset = manager.resetExplicitlyChangedStates(files, Set.of("A.vue", "Keep.vue"));
        assertEquals(files.subList(1, files.size()), reset.subList(1, reset.size()));
        assertEquals(files, manager.resetExplicitlyChangedStates(files, Set.of()));
        assertTrue(manager.indirectlyAffectedPaths(files, Set.of()).isEmpty());
    }

    @Test
    void 影响分析按计划顺序去重并跨过显式修订节点() throws Exception {
        AppPlanStateManager manager = manager(Files.createTempDirectory("plan-order-"));
        PlanFile a = new PlanFile("A.vue", "A", PlanFileAction.MODIFY, List.of(), PlanFileState.TOUCHED);
        PlanFile b = new PlanFile("B.vue", "B", PlanFileAction.MODIFY, List.of("A.vue"), PlanFileState.TOUCHED);
        PlanFile c = new PlanFile("C.vue", "C", PlanFileAction.MODIFY, List.of("A.vue", "B.vue"), PlanFileState.TOUCHED);
        List<PlanFile> files = List.of(c, a, b);
        assertEquals(List.of("C.vue", "B.vue"), manager.indirectlyAffectedPaths(files, Set.of("A.vue")));
        assertEquals(List.of("C.vue"), manager.indirectlyAffectedPaths(files, Set.of("A.vue", "B.vue")));
        List<PlanFile> reset = manager.resetExplicitlyChangedStates(files, Set.of("A.vue", "B.vue"));
        assertEquals(c, reset.getFirst());
        assertEquals(PlanFileState.PENDING, reset.get(1).state());
        assertEquals(PlanFileState.PENDING, reset.get(2).state());
    }

    @Test
    void 计划偏差和构建成功状态可以持久化恢复() throws Exception {
        Path root = Files.createTempDirectory("plan-status-");
        AppPlanStateManager manager = manager(root);
        AppPlan initial = new AppPlan(
                "plan-status", "turn-status", "turn-status", 1, 1,
                PlanMode.FULL, "状态测试", List.of(new PlanFile(
                "src/App.vue", "入口", PlanFileAction.MODIFY,
                List.of(), PlanFileState.TOUCHED)), List.of(),
                PlanStatus.READY_TO_BUILD);
        manager.save(11, initial, 0, "turn-status");

        manager.markReplanPending(11, "turn-status");
        assertEquals(PlanStatus.REPLAN_PENDING,
                manager.load(11).orElseThrow().status());
        manager.clearReplanPending(11, "turn-status");
        assertEquals(PlanStatus.READY_TO_BUILD,
                manager.load(11).orElseThrow().status());
        manager.markBuilt(11, "turn-status");
        assertEquals(PlanStatus.BUILT,
                manager.load(11).orElseThrow().status());
    }

    private AppPlanStateManager manager(Path root) {
        return new AppPlanStateManager(
                new ObjectMapper(), new PlanStoragePathResolver(root));
    }

    private AppPlan plan(
            String lastTurn, String activeTurn, int version, int round,
            List<PlanChange> history) {
        return new AppPlan(
                "plan-1", lastTurn, activeTurn, version, round, PlanMode.FULL,
                "创建页面", List.of(), history, PlanStatus.PLANNED);
    }
}
