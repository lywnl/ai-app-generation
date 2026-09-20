package com.lyw.appgeneration.ai.tools;

import com.lyw.appgeneration.ai.plan.PlanFile;
import com.lyw.appgeneration.ai.plan.PlanFileAction;
import com.lyw.appgeneration.ai.plan.PlanFileState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanToolProtocolSupportTest {

    @Test
    void 计划结果可以严格往返并保留文件动作() {
        PlanFile file = new PlanFile(
                "src/App.vue", "应用入口", PlanFileAction.MODIFY,
                List.of(), PlanFileState.PENDING);
        PlanToolResult result = PlanToolResult.applied(
                "makePlan", "plan-1", 1, "创建页面", List.of(file));

        PlanToolResult decoded = PlanToolProtocolSupport.parse(
                PlanToolProtocolSupport.json(result));

        assertEquals(result, decoded);
        assertEquals("src/App.vue", decoded.files().getFirst().path());
    }

    @Test
    void 文件工具协议以外的未知字段被拒绝() {
        String raw = PlanToolProtocolSupport.json(
                PlanToolResult.rejected("updatePlan", "请先创建计划"));
        String withUnknown = raw.substring(0, raw.length() - 1)
                + ",\"unknown\":true}";

        assertThrows(RuntimeException.class,
                () -> PlanToolProtocolSupport.parse(withUnknown));
    }

    @Test
    void 输入文件数组默认进入待处理状态() {
        List<PlanFile> files = PlanToolProtocolSupport.parseInputFiles("""
                [{"path":"src/App.vue","purpose":"入口","action":"MODIFY","dependsOn":[]}]
                """);

        assertEquals(PlanFileState.PENDING, files.getFirst().state());
        assertTrue(files.getFirst().dependsOn().isEmpty());
    }
}
