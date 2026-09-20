package com.lyw.appgeneration.ai.tools;

import cn.hutool.json.JSONObject;
import com.lyw.appgeneration.ai.plan.AppPlan;
import com.lyw.appgeneration.ai.plan.AppPlanStateManager;
import com.lyw.appgeneration.ai.plan.PlanChange;
import com.lyw.appgeneration.ai.plan.PlanFile;
import com.lyw.appgeneration.ai.plan.PlanFileState;
import com.lyw.appgeneration.ai.plan.PlanMode;
import com.lyw.appgeneration.ai.plan.PlanStatus;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager.CommitRejectedException;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 修订当前 Vue 在线执行计划。 */
@Component
public final class UpdatePlanTool extends BaseTool {

    private final AppPlanStateManager planStateManager;
    private final FileToolExecutionScopeManager scopeManager;

    public UpdatePlanTool(
            AppPlanStateManager planStateManager,
            FileToolExecutionScopeManager scopeManager) {
        this.planStateManager = planStateManager;
        this.scopeManager = scopeManager;
    }

    @Tool("修订当前 Vue 执行计划。发现路径、依赖或步骤不合理时必须先调用。")
    public String updatePlan(
            @P("修订原因") String reason,
            @P("新增文件 JSON 数组") String add,
            @P("删除文件路径 JSON 数组") String remove,
            @P("修改文件 JSON 数组") String modify,
            @ToolMemoryId Long appId) {
        String operation = getToolName();
        try {
            FileToolExecutionScopeManager.FileToolScope scope =
                    scopeManager.requireCurrent(
                            appId == null ? Long.MIN_VALUE : appId, operation);
            scopeManager.requireMutationAllowed(scope, operation);
            if (reason == null || reason.isBlank()) {
                return json(PlanToolResult.rejected(operation, "修订原因不能为空"));
            }
            String turnId = scope.ownerToken();
            AppPlan current = scope.lease().commitWhileActive(() ->
                    planStateManager.loadForTurn(appId, turnId).orElse(null));
            if (current == null) {
                return json(PlanToolResult.rejected(
                        operation, "当前项目没有计划，请先调用 makePlan"));
            }
            List<PlanFile> added = PlanToolProtocolSupport.parseInputFiles(add);
            List<String> removedPaths = PlanToolProtocolSupport.parseInputPaths(remove);
            List<PlanFile> modified = PlanToolProtocolSupport.parseInputFiles(modify);
            Map<String, PlanFile> files = new LinkedHashMap<>();
            current.files().forEach(file -> files.put(file.path(), file));
            validateAndApplyAdded(files, added);
            List<PlanFile> removed = applyRemoved(files, removedPaths);
            List<PlanFile> changed = applyModified(files, modified);
            if (added.isEmpty() && removed.isEmpty() && changed.isEmpty()) {
                return json(PlanToolResult.rejected(
                        operation, "计划修订必须至少包含一项新增、删除或修改"));
            }
            Set<String> changedPaths = new HashSet<>();
            added.forEach(file -> changedPaths.add(file.path()));
            changedPaths.addAll(removedPaths);
            modified.forEach(file -> changedPaths.add(file.path()));
            List<PlanFile> rebasedFiles = planStateManager.resetAffectedStates(
                    List.copyOf(files.values()), changedPaths);
            planStateManager.validatePlanFiles(rebasedFiles);
            int nextVersion = current.version() + 1;
            PlanChange change = new PlanChange(
                    nextVersion, turnId, reason.strip(), added, removed, changed);
            List<PlanChange> history = new ArrayList<>(current.history());
            history.add(change);
            AppPlan next = new AppPlan(
                    current.planId(), turnId, turnId, nextVersion,
                    current.round(), PlanMode.CHANGESET, current.summary(),
                    rebasedFiles, history, PlanStatus.PLANNED);
            scope.lease().commitWhileActive(() -> {
                planStateManager.save(
                        appId, next, current.version(), turnId,
                        current.activeTurnId());
                return null;
            });
            scopeManager.acknowledgePlanUpdate(scope);
            return json(PlanToolResult.applied(
                    operation, next.planId(), next.version(),
                    next.summary(), next.files()));
        } catch (AppPlanStateManager.PlanVersionConflictException exception) {
            return json(PlanToolResult.conflict(operation, exception.getMessage()));
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

    private void validateAndApplyAdded(
            Map<String, PlanFile> files, List<PlanFile> added) {
        added.forEach(file -> {
            PlanFile existing = files.get(file.path());
            if (existing != null && existing.state() != PlanFileState.OUT_OF_PLAN) {
                throw new IllegalArgumentException("新增文件已在计划中: " + file.path());
            }
            files.put(file.path(), existing == null ? file : new PlanFile(
                    file.path(), file.purpose(), file.action(),
                    file.dependsOn(), PlanFileState.TOUCHED));
        });
    }

    private List<PlanFile> applyRemoved(
            Map<String, PlanFile> files, List<String> paths) {
        List<PlanFile> removed = new ArrayList<>();
        paths.forEach(path -> {
            PlanFile existing = files.remove(path);
            if (existing == null) {
                throw new IllegalArgumentException("删除文件不在当前计划中: " + path);
            }
            removed.add(existing);
        });
        return removed;
    }

    private List<PlanFile> applyModified(
            Map<String, PlanFile> files, List<PlanFile> modified) {
        modified.forEach(file -> {
            PlanFile existing = files.get(file.path());
            if (existing == null) {
                throw new IllegalArgumentException("修改文件不在当前计划中: " + file.path());
            }
            files.put(file.path(), new PlanFile(
                    file.path(), file.purpose(), file.action(),
                    file.dependsOn(), existing.state() == PlanFileState.OUT_OF_PLAN
                            ? PlanFileState.TOUCHED : PlanFileState.PENDING));
        });
        return modified;
    }

    private String json(PlanToolResult result) {
        return PlanToolProtocolSupport.json(result);
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? "修订计划失败" : message.replaceAll("[\\r\\n\\t]", " ");
    }

    @Override
    public String getToolName() {
        return "updatePlan";
    }

    @Override
    public String getDisplayName() {
        return "修订计划";
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
