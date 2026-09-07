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
    void 在线读取两种Skill拒绝第三种且允许满额重读() {
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
                    () -> tool.readSkill("vue-personal-blog", 7L));

            var firstJson = cn.hutool.json.JSONUtil.parseObj(first);
            var secondJson = cn.hutool.json.JSONUtil.parseObj(second);
            assertEquals("APPLIED", firstJson.getStr("status"));
            assertTrue(firstJson.getStr("content").contains("本项目边界"));
            assertEquals("APPLIED", secondJson.getStr("status"));
            String third = manager.callInScope(scope, "readSkill",
                    () -> tool.readSkill("vue-online-store", 7L));
            var rejected = SkillToolProtocolSupport.parse(third);
            assertEquals(SkillToolResult.Status.REJECTED, rejected.status());
            assertEquals("TOO_MANY_SKILLS", rejected.failureReason());
            org.junit.jupiter.api.Assertions.assertNull(rejected.content());
            assertEquals(first, manager.callInScope(scope, "readSkill",
                    () -> tool.readSkill("vue-frontend-design", 7L)));
            assertEquals(second, manager.callInScope(scope, "readSkill",
                    () -> tool.readSkill("vue-personal-blog", 7L)));
        }
    }

    @Test
    void 未知和非法名称不占配额且不同回合与应用隔离() {
        FileToolExecutionScopeManager manager = new FileToolExecutionScopeManager(new FileToolBudgetGuard());
        AppOperationLeaseManager operations = new AppOperationLeaseManager();
        VueBuildSessionManager sessions = new VueBuildSessionManager();
        for (int round = 0; round < 3; round++) {
            long appId = round == 2 ? 8L : 7L;
            String turn = "turn-" + round;
            try (var operation = operations.acquire(appId,
                    AppOperationLeaseManager.AppOperationType.GENERATE, turn);
                 VueBuildLease lease = sessions.open(operation, 9L, turn)) {
                var scope = manager.online(lease, turn, appId, Set.copyOf(VueToolNames.ONLINE));
                ReadSkillTool tool = new ReadSkillTool(new SkillCatalog(), manager);
                for (String invalid : new String[]{"missing", "../secret", ""}) {
                    var result = SkillToolProtocolSupport.parse(manager.callInScope(scope, "readSkill",
                            () -> tool.readSkill(invalid, appId)));
                    assertTrue(result.status() != SkillToolResult.Status.APPLIED);
                }
                assertEquals(0, scope.skillReadSession().claimedCount());
                for (String name : new String[]{"vue-online-store", "vue-portfolio"}) {
                    assertEquals(SkillToolResult.Status.APPLIED,
                            SkillToolProtocolSupport.parse(manager.callInScope(scope, "readSkill",
                                    () -> tool.readSkill(name, appId))).status());
                }
                assertEquals(2, scope.skillReadSession().claimedCount());
            }
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
