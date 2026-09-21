package com.lyw.appgeneration.ai.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.model.vo.app.AppPlanVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class AppPlanViewTest {
    @Test
    void 投影保留展示字段但不暴露回合和完整差异() throws Exception {
        var file = new PlanFile("App.vue", "入口", PlanFileAction.CREATE, List.of(), PlanFileState.TOUCHED);
        var history = IntStream.rangeClosed(1, 25).mapToObj(version ->
                new PlanChange(version, "secret-turn", "修订" + version,
                        List.of(file), List.of(), List.of())).toList();
        var plan = new AppPlan("plan", "secret-last-turn", "secret-active-turn", 25, 1,
                PlanMode.FULL, "目标", List.of(file), history, PlanStatus.REPLAN_PENDING);
        var view = AppPlanVO.from(plan);
        var json = new ObjectMapper().valueToTree(view);
        assertEquals(Set.of("planId", "version", "status", "summary", "files", "history"),
                json.properties().stream().map(java.util.Map.Entry::getKey).collect(Collectors.toSet()));
        assertEquals(20, view.history().size());
        assertEquals(6, view.history().getFirst().version());
        assertEquals("修订25", view.history().getLast().reason());
        assertEquals(PlanFileState.TOUCHED, view.files().getFirst().state());
        assertFalse(json.toString().contains("secret"));
        assertFalse(json.toString().contains("added"));
        assertThrows(UnsupportedOperationException.class, () -> view.files().clear());
    }

    @Test
    void 无修订原因不生成虚构记录() {
        var plan = new AppPlan("plan", "turn", "turn", 1, 1, PlanMode.FULL,
                "目标", List.of(), List.of(), PlanStatus.REPLAN_PENDING);
        assertTrue(AppPlanVO.from(plan).history().isEmpty());
    }
}
