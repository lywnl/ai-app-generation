package com.lyw.appgeneration.model.vo.app;

import com.lyw.appgeneration.ai.plan.AppPlan;
import com.lyw.appgeneration.ai.plan.PlanFileAction;
import com.lyw.appgeneration.ai.plan.PlanFileState;
import com.lyw.appgeneration.ai.plan.PlanStatus;

import java.util.List;

/** 仅供所有者查看的计划投影，不携带回合令牌和完整修订差异。 */
public record AppPlanVO(String planId, int version, PlanStatus status, String summary,
                        List<FileView> files, List<ChangeView> history) {

    public AppPlanVO {
        files = List.copyOf(files);
        history = List.copyOf(history);
    }

    public static AppPlanVO from(AppPlan plan) {
        var files = plan.files().stream().map(file -> new FileView(
                file.path(), file.purpose(), file.action(), file.dependsOn(), file.state())).toList();
        var history = plan.history().stream().skip(Math.max(0, plan.history().size() - 20L))
                .map(change -> new ChangeView(change.version(), change.reason())).toList();
        return new AppPlanVO(plan.planId(), plan.version(), plan.status(), plan.summary(), files, history);
    }

    public record FileView(String path, String purpose, PlanFileAction action,
                           List<String> dependsOn, PlanFileState state) {
        public FileView {
            dependsOn = List.copyOf(dependsOn);
        }
    }

    public record ChangeView(int version, String reason) {
    }
}
