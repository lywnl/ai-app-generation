package com.lyw.appgeneration.ai.plan;

import com.lyw.appgeneration.ai.tools.FileToolBudgetGuard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 构建前的服务端诊断；完整条目用于比较，受限文本用于模型和用户展示。 */
public record BuildBlockDiagnostic(Reason reason, PlanStatus planStatus, List<Blocker> blockers) {
    public static final int MAX_MESSAGE_CODE_POINTS = 1_000;
    private static final int MAX_DISPLAY_BLOCKERS = 20;
    private static final Comparator<Blocker> ORDER = Comparator.comparing((Blocker b) -> b.kind().name())
            .thenComparing(b -> Objects.toString(b.path(), ""))
            .thenComparing(b -> Objects.toString(b.dependency(), ""))
            .thenComparing(b -> Objects.toString(b.action(), ""))
            .thenComparing(b -> Objects.toString(b.state(), ""));

    public BuildBlockDiagnostic {
        Objects.requireNonNull(reason, "阻塞原因不能为空");
        blockers = blockers == null ? List.of() : blockers.stream().distinct().sorted(ORDER).toList();
    }

    public static BuildBlockDiagnostic single(Reason reason) {
        return new BuildBlockDiagnostic(reason, null, List.of(new Blocker(reason, null, null, null, null)));
    }

    public static BuildBlockDiagnostic forPlan(AppPlan plan, boolean replanPending) {
        if (plan == null) return single(Reason.NO_PLAN);
        List<Blocker> blockers = new ArrayList<>();
        var byPath = new HashMap<String, PlanFile>();
        plan.files().forEach(file -> byPath.put(file.path(), file));
        if (plan.files().isEmpty()) blockers.add(new Blocker(Reason.EMPTY_PLAN, null, null, null, null));
        if (replanPending || plan.status() == PlanStatus.REPLAN_PENDING) {
            blockers.add(new Blocker(Reason.REPLAN_PENDING, null, null, null, null));
        }
        for (PlanFile file : plan.files()) {
            if (file.action() != PlanFileAction.KEEP && file.state() != PlanFileState.TOUCHED) {
                blockers.add(new Blocker(Reason.FILES_PENDING, file.path(), file.action(), file.state(), null));
            }
            for (String path : file.dependsOn()) {
                PlanFile dependency = byPath.get(path);
                if (dependency == null || (dependency.action() != PlanFileAction.KEEP
                        && dependency.state() != PlanFileState.TOUCHED)) {
                    blockers.add(new Blocker(Reason.DEPENDENCY_PENDING, file.path(),
                            dependency == null ? null : dependency.action(),
                            dependency == null ? null : dependency.state(), path));
                }
            }
        }
        Reason reason = blockers.stream().anyMatch(b -> b.kind() == Reason.REPLAN_PENDING) ? Reason.REPLAN_PENDING
                : plan.files().isEmpty() ? Reason.EMPTY_PLAN
                : blockers.stream().anyMatch(b -> b.kind() == Reason.DEPENDENCY_PENDING) ? Reason.DEPENDENCY_PENDING
                : blockers.isEmpty() ? Reason.NONE : Reason.FILES_PENDING;
        return new BuildBlockDiagnostic(reason, plan.status(), blockers);
    }

    public Set<Blocker> blockerSet() { return Set.copyOf(blockers); }
    public boolean recoverable() { return reason != Reason.NONE && reason != Reason.TURN_MISMATCH; }
    public boolean hasPlan() { return reason != Reason.NO_PLAN && reason != Reason.TURN_MISMATCH; }
    public boolean hasNonEmptyPlan() { return hasPlan() && blockers.stream().noneMatch(b -> b.kind() == Reason.EMPTY_PLAN); }

    public String message() {
        String instruction = switch (reason) {
            case NONE -> "";
            case NO_PLAN -> "请先调用 makePlan 创建计划，再完成文件变更。";
            case EMPTY_PLAN -> "当前计划没有文件动作，请调用 updatePlan 补充真实任务。";
            case TURN_MISMATCH -> "当前计划不属于活动生成回合。";
            case REPLAN_PENDING -> "当前计划存在未处理偏差，请先调用 updatePlan 修订。";
            case FILES_PENDING, DEPENDENCY_PENDING -> "确实需要修改时请完成对应文件的真实变更；确认无需修改时，调用 updatePlan 改为 KEEP 并说明原因。";
            case CODE_MUTATION_REQUIRED -> "尚未检测到新的有效代码修改，请按照上一次构建错误完成真实修复后再构建；只有计划不合理时才调用 updatePlan。";
        };
        if (reason == Reason.NONE) return "";
        String details = blockers.stream().filter(b -> b.path() != null).limit(MAX_DISPLAY_BLOCKERS)
                .map(b -> "\n- " + safePath(b.path())
                        + (b.dependency() == null ? "：" : " 等待依赖 " + safePath(b.dependency()) + "：")
                        + (b.action() == null ? "依赖不存在" : b.action() + " / " + b.state()))
                .reduce("", String::concat);
        long pathCount = blockers.stream().filter(b -> b.path() != null).count();
        if (pathCount > MAX_DISPLAY_BLOCKERS) details += "\n另有 " + (pathCount - MAX_DISPLAY_BLOCKERS) + " 项阻塞。";
        String prefix = "无法构建。" + instruction + "处理前重复调用 buildProject 不会改变结果。";
        return prefix + FileToolBudgetGuard.prefixByCodePoints(details,
                MAX_MESSAGE_CODE_POINTS - FileToolBudgetGuard.codePointCount(prefix));
    }

    private static String safePath(String path) {
        StringBuilder safe = new StringBuilder();
        path.codePoints().forEach(cp -> safe.appendCodePoint(Character.isISOControl(cp)
                || cp == 0x2028 || cp == 0x2029 ? ' ' : cp));
        return FileToolBudgetGuard.prefixByCodePoints(safe.toString(), 160);
    }

    public enum Reason { NONE, NO_PLAN, EMPTY_PLAN, TURN_MISMATCH, FILES_PENDING, DEPENDENCY_PENDING, REPLAN_PENDING, CODE_MUTATION_REQUIRED }

    public record Blocker(Reason kind, String path, PlanFileAction action, PlanFileState state, String dependency) {
        public Blocker { Objects.requireNonNull(kind, "阻塞类型不能为空"); }
    }
}
