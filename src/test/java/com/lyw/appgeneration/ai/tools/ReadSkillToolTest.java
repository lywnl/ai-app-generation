package com.lyw.appgeneration.ai.tools;

import com.lyw.appgeneration.ai.VueToolNames;
import com.lyw.appgeneration.ai.skill.SkillCatalog;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager.VueBuildLease;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadSkillToolTest {

    @Test
    void 在线作用域读取内置Skill并限制第二个不同Skill() {
        FileToolExecutionScopeManager manager =
                new FileToolExecutionScopeManager(new FileToolBudgetGuard());
        AppOperationLeaseManager operations = new AppOperationLeaseManager();
        VueBuildSessionManager sessions = new VueBuildSessionManager();
        try (var operation = operations.acquire(
                7L, AppOperationLeaseManager.AppOperationType.GENERATE, "turn-1");
             VueBuildLease lease = sessions.open(operation, 9L, "turn-1")) {
            var scope = manager.online(lease, "turn-1", 7L,
                    Set.copyOf(VueToolNames.ONLINE));
            ReadSkillTool tool = new ReadSkillTool(new SkillCatalog(), manager);

            String first = manager.callInScope(scope, "readSkill",
                    () -> tool.readSkill("vue-frontend-design", 7L));
            String second = manager.callInScope(scope, "readSkill",
                    () -> tool.readSkill("another-skill", 7L));

            var firstJson = cn.hutool.json.JSONUtil.parseObj(first);
            var secondJson = cn.hutool.json.JSONUtil.parseObj(second);
            assertEquals("APPLIED", firstJson.getStr("status"));
            assertTrue(firstJson.getStr("content").contains("本项目边界"));
            assertEquals("NOT_FOUND", secondJson.getStr("status"));
            assertEquals(cn.hutool.json.JSONNull.NULL, secondJson.get("content"));
        }
    }

    @Test
    void 评测作用域不能读取Skill() {
        FileToolExecutionScopeManager manager =
                new FileToolExecutionScopeManager(new FileToolBudgetGuard());
        var scope = manager.evaluation(7L, "evaluation", Set.of("readSkill"));
        ReadSkillTool tool = new ReadSkillTool(new SkillCatalog(), manager);

        String result = manager.callInScope(scope, "readSkill",
                () -> tool.readSkill("vue-frontend-design", 7L));

        assertEquals("REJECTED", cn.hutool.json.JSONUtil.parseObj(result)
                .getStr("status"));
    }
}
