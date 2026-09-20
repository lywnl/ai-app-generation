package com.lyw.appgeneration.ai.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppPlanTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 计划快照可以完成Json往返并保留回合与文件状态() throws Exception {
        PlanFile file = new PlanFile(
                "src/views/../App.vue",
                "应用根组件",
                PlanFileAction.MODIFY,
                List.of("src/main.js"),
                PlanFileState.TOUCHED);
        AppPlan plan = new AppPlan(
                "plan-1",
                "turn-1",
                "turn-2",
                2,
                2,
                PlanMode.CHANGESET,
                "修订 Vue 页面",
                List.of(file),
                List.of(new PlanChange(
                        2, "turn-2", "路由入口发生变化",
                        List.of(file), List.of(), List.of())),
                PlanStatus.REPLAN_PENDING);

        AppPlan decoded = objectMapper.readValue(
                objectMapper.writeValueAsBytes(plan), AppPlan.class);

        assertEquals(plan, decoded);
        assertEquals("src/App.vue", decoded.files().getFirst().path());
        assertEquals("turn-1", decoded.lastModifiedTurnId());
        assertEquals("turn-2", decoded.activeTurnId());
    }

    @Test
    void 计划模型的集合不可变且拒绝非法路径() {
        AppPlan plan = new AppPlan(
                "plan-1", "turn-1", "turn-1", 1, 1, PlanMode.FULL,
                "创建项目", List.of(), List.of(), PlanStatus.PLANNED);

        assertThrows(UnsupportedOperationException.class,
                () -> plan.files().add(null));
        assertThrows(IllegalArgumentException.class,
                () -> new PlanFile(
                        "../outside.vue", "越界文件", PlanFileAction.CREATE,
                        List.of(), PlanFileState.PENDING));
        assertThrows(IllegalArgumentException.class,
                () -> new PlanChange(0, "turn-1", "无效版本",
                        List.of(), List.of(), List.of()));
    }
}
