package com.lyw.appgeneration.ai.plan;

import com.lyw.appgeneration.ai.tools.FileToolBudgetGuard;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BuildBlockDiagnosticTest {
    @Test
    void 同时保留待修订文件和依赖而不依赖文案分类() {
        var a = new PlanFile("src/A.vue", "A", PlanFileAction.CREATE, List.of(), PlanFileState.PENDING);
        var b = new PlanFile("src/B.vue", "B", PlanFileAction.MODIFY, List.of(a.path()), PlanFileState.TOUCHED);
        var plan = plan(List.of(b, a), PlanStatus.REPLAN_PENDING);
        var result = BuildBlockDiagnostic.forPlan(plan, false);
        assertEquals(BuildBlockDiagnostic.Reason.REPLAN_PENDING, result.reason());
        assertEquals(3, result.blockers().size());
        assertTrue(result.message().contains("src/A.vue"));
        assertTrue(result.message().contains("CREATE / PENDING"));
        assertTrue(result.message().contains("updatePlan"));
        assertEquals(result.blockerSet(), BuildBlockDiagnostic.forPlan(plan(List.of(a, b), PlanStatus.REPLAN_PENDING), false).blockerSet());
    }

    @Test
    void 空计划无计划身份和代码错误有独立诊断() {
        assertEquals(BuildBlockDiagnostic.Reason.NO_PLAN, BuildBlockDiagnostic.forPlan(null, false).reason());
        assertTrue(BuildBlockDiagnostic.forPlan(null, false).message().contains("makePlan"));
        assertEquals(BuildBlockDiagnostic.Reason.EMPTY_PLAN, BuildBlockDiagnostic.forPlan(plan(List.of(), PlanStatus.PLANNED), false).reason());
        assertFalse(BuildBlockDiagnostic.single(BuildBlockDiagnostic.Reason.TURN_MISMATCH).recoverable());
        assertTrue(BuildBlockDiagnostic.single(BuildBlockDiagnostic.Reason.CODE_MUTATION_REQUIRED).message().contains("上一次构建错误"));
    }

    @Test
    void KEEP不阻塞而长路径诊断有界() {
        var keep = new PlanFile("src/A.vue", "A", PlanFileAction.KEEP, List.of(), PlanFileState.PENDING);
        assertEquals(BuildBlockDiagnostic.Reason.NONE, BuildBlockDiagnostic.forPlan(plan(List.of(keep), PlanStatus.PLANNED), false).reason());
        var files = java.util.stream.IntStream.range(0, 40).mapToObj(i -> new PlanFile(
                "src/" + "长路径".repeat(100) + i + ".vue", "页面", PlanFileAction.CREATE, List.of(), PlanFileState.PENDING)).toList();
        var result = BuildBlockDiagnostic.forPlan(plan(files, PlanStatus.PLANNED), false);
        assertEquals(40, result.blockers().size());
        assertTrue(FileToolBudgetGuard.codePointCount(result.message()) <= BuildBlockDiagnostic.MAX_MESSAGE_CODE_POINTS);
        assertTrue(result.message().contains("KEEP"));
    }

    @Test
    void 自身文件完成不应改变仍存在的依赖阻塞身份() {
        var a = new PlanFile("A.vue", "A", PlanFileAction.MODIFY, List.of(), PlanFileState.PENDING);
        var b = new PlanFile("B.vue", "B", PlanFileAction.MODIFY, List.of("A.vue"), PlanFileState.PENDING);
        var before = BuildBlockDiagnostic.forPlan(plan(List.of(a, b), PlanStatus.PLANNED), false);
        var touched = new PlanFile(b.path(), b.purpose(), b.action(), b.dependsOn(), PlanFileState.TOUCHED);
        var after = BuildBlockDiagnostic.forPlan(plan(List.of(a, touched), PlanStatus.PLANNED), false);
        assertTrue(before.blockerSet().containsAll(after.blockerSet()));
        assertTrue(before.blockers().size() > after.blockers().size());
    }

    private AppPlan plan(List<PlanFile> files, PlanStatus status) {
        return new AppPlan("p", "t", "t", 1, 1, PlanMode.FULL, "页面", files, List.of(), status);
    }
}
