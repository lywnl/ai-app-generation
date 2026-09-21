package com.lyw.appgeneration.ai.tools;

import com.lyw.appgeneration.ai.plan.PlanFile;
import com.lyw.appgeneration.ai.plan.PlanFileAction;
import com.lyw.appgeneration.ai.plan.PlanFileState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

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

    @Test
    void 输入文件数组兼容动作大小写和JSON代码围栏() {
        List<PlanFile> files = PlanToolProtocolSupport.parseInputFiles("""
                ```json
                [{"path":"src/App.vue","purpose":"入口","action":"modify","dependsOn":[]}]
                ```
                """);

        assertEquals(PlanFileAction.MODIFY, files.getFirst().action());
    }

    @Test
    void 自定义检查提示往返但不改变协议字段和默认文案() throws Exception {
        String message = "计划已保存。\n以下文件可能受依赖变化影响，请检查：src/main.js。";
        PlanToolResult result = PlanToolResult.applied("updatePlan", "p", 2, "页面", List.of(), message);
        String json = PlanToolProtocolSupport.json(result);
        assertEquals(result, PlanToolProtocolSupport.parse(json));
        var fields = new java.util.HashSet<String>();
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("protocol", "operation", "status", "planId", "version", "message", "summary", "files"), fields);
        assertEquals("计划已保存", PlanToolResult.applied("makePlan", "p", 1, "页面", List.of()).message());
    }
}
