package com.lyw.appgeneration.ai.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AppPlanReadOnlyTest {
    @TempDir
    Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 查询缺失计划无副作用且重复读取保持文件原样() throws Exception {
        var paths = new PlanStoragePathResolver(root);
        var manager = new AppPlanStateManager(mapper, paths);
        assertTrue(manager.loadReadOnly(7).isEmpty());
        assertFalse(Files.exists(paths.projectRoot(7)));
        AppPlan plan = plan(1, PlanFileState.PENDING);
        manager.save(7, plan, 0, "turn");
        byte[] original = Files.readAllBytes(paths.planPath(7));
        for (int i = 0; i < 3; i++) {
            assertEquals(plan, manager.loadReadOnly(7).orElseThrow());
        }
        assertArrayEquals(original, Files.readAllBytes(paths.planPath(7)));
    }

    @Test
    void 同版本文件进度更新能够被读取() {
        var manager = new AppPlanStateManager(mapper, new PlanStoragePathResolver(root));
        manager.save(7, plan(1, PlanFileState.PENDING), 0, "turn");
        manager.recordSuccessfulMutation(7, "turn", "src/App.vue");
        AppPlan actual = manager.loadReadOnly(7).orElseThrow();
        assertEquals(1, actual.version());
        assertEquals(PlanFileState.TOUCHED, actual.files().getFirst().state());
        assertEquals(PlanStatus.READY_TO_BUILD, actual.status());
    }

    @Test
    void 损坏及悬空依赖不得作为正常空计划返回() throws Exception {
        var paths = new PlanStoragePathResolver(root);
        var manager = new AppPlanStateManager(mapper, paths);
        Files.createDirectories(paths.projectRoot(7));
        Files.writeString(paths.planPath(7), "{broken");
        assertThrows(IllegalStateException.class, () -> manager.loadReadOnly(7));
        AppPlan invalid = new AppPlan("plan", "turn", "turn", 1, 1, PlanMode.FULL,
                "目标", List.of(new PlanFile("src/App.vue", "入口", PlanFileAction.CREATE,
                List.of("src/missing.vue"), PlanFileState.PENDING)), List.of(), PlanStatus.PLANNED);
        mapper.writeValue(paths.planPath(7).toFile(), invalid);
        assertThrows(IllegalStateException.class, () -> manager.loadReadOnly(7));
    }

    @Test
    void 并发版本替换只返回完整快照() throws Exception {
        var manager = new AppPlanStateManager(mapper, new PlanStoragePathResolver(root));
        manager.save(7, plan(1, PlanFileState.PENDING), 0, "turn");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = executor.submit(() -> {
                for (int version = 2; version <= 30; version++) {
                    manager.save(7, plan(version, PlanFileState.PENDING), version - 1, "turn");
                }
            });
            for (int i = 0; i < 60; i++) {
                AppPlan read = manager.loadReadOnly(7).orElseThrow();
                assertEquals("目标" + read.version(), read.summary());
                assertEquals("用途" + read.version(), read.files().getFirst().purpose());
            }
            writer.get(5, TimeUnit.SECONDS);
        }
        assertEquals(30, manager.loadReadOnly(7).orElseThrow().version());
    }

    @Test
    void 请求边界逐次读取版本及同版本状态并保留动作依赖说明() throws Exception {
        var paths = new PlanStoragePathResolver(root);
        var manager = new AppPlanStateManager(mapper, paths);
        var boundary = com.lyw.appgeneration.ai.memory.TurnRequestBoundary.of("查看当前进度", List.of(), 10, "readonly")
                .withPlanReader(() -> manager.loadReadOnly(7));
        assertTrue(boundary.readLatestPlan().isEmpty());
        assertFalse(Files.exists(paths.projectRoot(7)));
        manager.save(7, plan(1, PlanFileState.PENDING), 0, "turn");
        assertEquals(1, boundary.readLatestPlan().orElseThrow().version());
        manager.recordSuccessfulMutation(7, "turn", "src/App.vue");
        assertEquals(PlanFileState.TOUCHED, boundary.readLatestPlan().orElseThrow().files().getFirst().state());
        manager.save(7, new AppPlan("plan", "turn", "turn", 2, 1, PlanMode.FULL, "新目标",
                List.of(new PlanFile("src/api.ts", "数据访问", PlanFileAction.CREATE, List.of(), PlanFileState.PENDING),
                        new PlanFile("src/App.vue", "入口", PlanFileAction.MODIFY, List.of("src/api.ts"), PlanFileState.PENDING)),
                List.of(), PlanStatus.PLANNED), 1, "turn");
        byte[] saved = Files.readAllBytes(paths.planPath(7));
        var latest = boundary.readLatestPlan().orElseThrow();
        assertEquals(2, latest.version());
        String text = AppPlanStateManager.formatPromptContext(latest);
        assertTrue(text.contains("依赖=src/api.ts"));
        assertTrue(text.contains("动作=MODIFY"));
        assertTrue(text.contains("不等于构建验证或验收成功"));
        assertEquals("turn", latest.activeTurnId());
        assertArrayEquals(saved, Files.readAllBytes(paths.planPath(7)));
        Files.writeString(paths.planPath(7), "损坏计划");
        assertThrows(IllegalStateException.class, boundary::readLatestPlan, "读取失败不能伪装为没有计划");
    }

    private AppPlan plan(int version, PlanFileState state) {
        return new AppPlan("plan", "turn", "turn", version, 1, PlanMode.FULL,
                "目标" + version, List.of(new PlanFile("src/App.vue", "用途" + version,
                PlanFileAction.CREATE, List.of(), state)), List.of(), PlanStatus.PLANNED);
    }
}
