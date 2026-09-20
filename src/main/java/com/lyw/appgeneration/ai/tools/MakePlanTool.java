package com.lyw.appgeneration.ai.tools;

import cn.hutool.json.JSONObject;
import com.lyw.appgeneration.ai.plan.AppPlan;
import com.lyw.appgeneration.ai.plan.AppPlanStateManager;
import com.lyw.appgeneration.ai.plan.PlanFile;
import com.lyw.appgeneration.ai.plan.PlanMode;
import com.lyw.appgeneration.ai.plan.PlanStatus;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager.CommitRejectedException;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** 创建 Vue 在线首轮执行计划。 */
@Component
public final class MakePlanTool extends BaseTool {

    static final int MAX_PLAN_FILES = 30;

    private final AppPlanStateManager planStateManager;
    private final FileToolExecutionScopeManager scopeManager;

    public MakePlanTool(
            AppPlanStateManager planStateManager,
            FileToolExecutionScopeManager scopeManager) {
        this.planStateManager = planStateManager;
        this.scopeManager = scopeManager;
    }

    @Tool("创建 Vue 项目的首轮完整执行计划。必须在任何文件变更前调用。")
    public String makePlan(
            @P("项目目标摘要") String summary,
            @P("计划文件 JSON 数组，每项包含 path、purpose、action、dependsOn") String files,
            @ToolMemoryId Long appId) {
        String operation = getToolName();
        try {
            FileToolExecutionScopeManager.FileToolScope scope =
                    scopeManager.requireCurrent(
                            appId == null ? Long.MIN_VALUE : appId, operation);
            scopeManager.requireMutationAllowed(scope, operation);
            if (summary == null || summary.isBlank()) {
                return json(PlanToolResult.rejected(operation, "计划目标不能为空"));
            }
            if (planStateManager.load(appId).isPresent()) {
                return json(PlanToolResult.rejected(
                        operation, "当前项目已有计划，请调用 updatePlan 修订"));
            }
            List<PlanFile> plannedFiles = PlanToolProtocolSupport.parseInputFiles(files);
            validateFiles(plannedFiles);
            String turnId = scope.ownerToken();
            AppPlan plan = new AppPlan(
                    "plan-" + UUID.randomUUID(), turnId, turnId,
                    1, 1, PlanMode.FULL, summary.strip(), plannedFiles,
                    List.of(), PlanStatus.PLANNED);
            scope.lease().commitWhileActive(() -> {
                planStateManager.save(appId, plan, 0, turnId);
                return null;
            });
            return json(PlanToolResult.applied(
                    operation, plan.planId(), plan.version(),
                    plan.summary(), plan.files()));
        } catch (FileToolExecutionScopeManager.ScopeViolationException exception) {
            return json(PlanToolResult.rejected(operation, exception.getMessage()));
        } catch (CommitRejectedException exception) {
            return json(PlanToolResult.rejected(operation, exception.getMessage()));
        } catch (IllegalArgumentException exception) {
            return json(PlanToolResult.rejected(operation, safeMessage(exception)));
        } catch (RuntimeException exception) {
            return json(PlanToolResult.failed(operation, safeMessage(exception)));
        }
    }

    private void validateFiles(List<PlanFile> files) {
        if (files.isEmpty()) {
            throw new IllegalArgumentException("完整计划至少需要一个文件动作");
        }
        if (files.size() > MAX_PLAN_FILES) {
            throw new IllegalArgumentException("计划文件数量不能超过 " + MAX_PLAN_FILES);
        }
        planStateManager.validatePlanFiles(files);
    }

    private String json(PlanToolResult result) {
        return PlanToolProtocolSupport.json(result);
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? "创建计划失败" : message.replaceAll("[\\r\\n\\t]", " ");
    }

    @Override
    public String getToolName() {
        return "makePlan";
    }

    @Override
    public String getDisplayName() {
        return "创建计划";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        return generateToolExecutedResult(arguments, null);
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments, String rawResult) {
        return PlanToolProtocolSupport.stableSummary(this, rawResult);
    }
}
