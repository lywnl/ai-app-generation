package dev.langchain4j.service;

import com.lyw.appgeneration.ai.plan.BuildBlockDiagnostic;
import com.lyw.appgeneration.ai.plan.PlanFileAction;
import com.lyw.appgeneration.ai.plan.PlanFileState;
import com.lyw.appgeneration.ai.plan.PlanStatus;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BuildProgressGuardTest {
    private final BuildBlockDiagnostic a = pending("src/A.vue");

    @Test
    void 两次后安排但只有启动并收到有效响应后再次拒绝才终止() {
        var guard = new BuildProgressGuard(7, "t");
        assertEquals(BuildProgressGuard.Action.CONTINUE, reject(guard, 1, "a", a));
        assertEquals(BuildProgressGuard.Action.CORRECT_NEXT_REQUEST, reject(guard, 1, "b", a));
        var ticket = guard.pendingFeedback();
        assertNotNull(ticket);
        assertEquals(BuildProgressGuard.Action.CONTINUE, reject(guard, 1, "c", a));
        guard.responseAccepted(1);
        assertFalse(guard.correctionDelivered());
        guard.requestStarted(2, ticket);
        assertFalse(guard.correctionDelivered());
        guard.responseAccepted(2);
        assertTrue(guard.correctionDelivered());
        assertEquals(BuildProgressGuard.Action.TERMINATE, reject(guard, 2, "d", a));
        assertTrue(guard.terminalMessage().contains("src/A.vue"));
    }

    @Test
    void 重复事件其他回合及关闭后不推进() {
        var guard = new BuildProgressGuard(7, "t");
        reject(guard, 1, "a", a); reject(guard, 1, "a", a);
        guard.observe(1, "foreign", BuildProgressGuard.Observation.rejected("other", a, 0));
        assertEquals(1, guard.blockedCount());
        guard.close(); reject(guard, 2, "b", a);
        assertEquals(0, guard.blockedCount());
        assertNull(guard.pendingFeedback());
        var next = new BuildProgressGuard(7, "next");
        assertEquals(0, next.blockedCount());
    }

    @Test
    void 阻塞替换和无关成功变更不清零但严格减少可重置() {
        var guard = new BuildProgressGuard(7, "t");
        reject(guard, 1, "a", a);
        var b = pending("src/B.vue");
        guard.observe(1, "update", progress(a, b, 0, false));
        assertEquals(1, guard.blockedCount());
        reject(guard, 2, "b", b);
        var ticket = guard.pendingFeedback();
        guard.observe(2, "unrelated", progress(b, b, 1, false));
        assertEquals(2, guard.blockedCount());
        guard.observe(2, "fixed", progress(b, ready(), 2, false));
        assertEquals(0, guard.blockedCount());
        guard.requestStarted(3, ticket); guard.responseAccepted(3);
        assertFalse(guard.correctionDelivered());
        assertEquals(BuildProgressGuard.Action.CONTINUE, reject(guard, 3, "new", b));
    }

    @Test
    void failOpen和未提交进展不应恢复机会() {
        var guard = new BuildProgressGuard(7, "t");
        var pending = new BuildBlockDiagnostic(BuildBlockDiagnostic.Reason.REPLAN_PENDING, PlanStatus.REPLAN_PENDING,
                List.of(new BuildBlockDiagnostic.Blocker(BuildBlockDiagnostic.Reason.REPLAN_PENDING, null, null, null, null)));
        reject(guard, 1, "a", pending);
        guard.observe(1, "fail-open", progress(pending, ready(), 1, true));
        assertEquals(1, guard.blockedCount());
        guard.observe(1, "later-unrelated", progress(ready(), ready(), 2, false));
        assertEquals(1, guard.blockedCount());
        guard.observe(1, "update", progress(pending, ready(), 2, false));
        assertEquals(0, guard.blockedCount());
    }

    @Test
    void 创建计划及真实代码修改和真实构建可重置() {
        var guard = new BuildProgressGuard(7, "t");
        var missing = BuildBlockDiagnostic.single(BuildBlockDiagnostic.Reason.NO_PLAN);
        reject(guard, 1, "missing", missing);
        guard.observe(1, "made", progress(missing, a, 0, false));
        assertEquals(0, guard.blockedCount());
        var code = BuildBlockDiagnostic.single(BuildBlockDiagnostic.Reason.CODE_MUTATION_REQUIRED);
        guard.observe(1, "code", BuildProgressGuard.Observation.rejected("t", code, 7));
        guard.observe(1, "plan-only", progress(code, ready(), 7, false));
        assertEquals(1, guard.blockedCount());
        guard.observe(1, "file", progress(code, ready(), 8, false));
        assertEquals(0, guard.blockedCount());
        reject(guard, 1, "blocked", a);
        guard.observe(1, "built", new BuildProgressGuard.Observation("t", null, null, null, false, true, 8, false));
        assertEquals(0, guard.blockedCount());
    }

    @Test
    void 待发送反馈更新诊断但不清零且旧票据不能确认新反馈() {
        var guard = new BuildProgressGuard(7, "t");
        reject(guard, 1, "a", a); reject(guard, 1, "b", a);
        var old = guard.pendingFeedback();
        var changed = pending("src/New.vue");
        guard.observe(1, "replace", progress(a, changed, 0, false));
        assertEquals(2, guard.blockedCount());
        assertTrue(guard.pendingFeedback().message().text().contains("src/New.vue"));
        guard.requestStarted(2, old); guard.responseAccepted(2);
        assertFalse(guard.correctionDelivered());
        guard.requestStarted(3, guard.pendingFeedback()); guard.responseAccepted(3);
        assertEquals(BuildProgressGuard.Action.TERMINATE, reject(guard, 3, "c", changed));
    }

    @Test
    void 长路径终态有界且代码停滞不伪装成构建失败三次() {
        for (var diagnostic : List.of(pending("src/" + "长路径".repeat(1000) + ".vue"),
                BuildBlockDiagnostic.single(BuildBlockDiagnostic.Reason.CODE_MUTATION_REQUIRED))) {
            var guard = new BuildProgressGuard(7, "t");
            reject(guard, 1, "a", diagnostic); reject(guard, 1, "b", diagnostic);
            guard.requestStarted(2, guard.pendingFeedback()); guard.responseAccepted(2);
            reject(guard, 2, "c", diagnostic);
            assertTrue(com.lyw.appgeneration.ai.tools.FileToolBudgetGuard.codePointCount(guard.terminalMessage()) <= BuildProgressGuard.MAX_TERMINAL_CODE_POINTS);
            assertFalse(guard.terminalMessage().contains("第 3 次"));
            assertFalse(guard.terminalMessage().contains("PROTOCOL_ERROR"));
        }
    }

    private BuildProgressGuard.Action reject(BuildProgressGuard guard, long generation, String id, BuildBlockDiagnostic diagnostic) {
        return guard.observe(generation, id, BuildProgressGuard.Observation.rejected("t", diagnostic, 0));
    }
    private BuildProgressGuard.Observation progress(BuildBlockDiagnostic before, BuildBlockDiagnostic after, long revision, boolean failOpen) {
        return new BuildProgressGuard.Observation("t", before, after, null, true, false, revision, failOpen);
    }
    private BuildBlockDiagnostic pending(String path) {
        return new BuildBlockDiagnostic(BuildBlockDiagnostic.Reason.FILES_PENDING, PlanStatus.PLANNED,
                List.of(new BuildBlockDiagnostic.Blocker(BuildBlockDiagnostic.Reason.FILES_PENDING, path, PlanFileAction.CREATE, PlanFileState.PENDING, null)));
    }
    private BuildBlockDiagnostic ready() {
        return new BuildBlockDiagnostic(BuildBlockDiagnostic.Reason.NONE, PlanStatus.READY_TO_BUILD, List.of());
    }
}
