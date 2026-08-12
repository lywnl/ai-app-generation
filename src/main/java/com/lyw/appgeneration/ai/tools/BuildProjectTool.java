package com.lyw.appgeneration.ai.tools;

import cn.hutool.json.JSONObject;
import com.lyw.appgeneration.constants.AppConstant;
import com.lyw.appgeneration.core.builder.BuildCancellationSignal;
import com.lyw.appgeneration.core.builder.BuildErrorSanitizer;
import com.lyw.appgeneration.core.builder.BuildExecutionContext;
import com.lyw.appgeneration.core.builder.BuildLogSink;
import com.lyw.appgeneration.core.builder.BuildResult;
import com.lyw.appgeneration.core.builder.BuildStage;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager.BuildAttemptTicket;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager.BuildInProgressException;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager.VueBuildLease;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager.VueBuildSnapshot;
import com.lyw.appgeneration.core.builder.VueBuildPhase;
import com.lyw.appgeneration.core.builder.VueProjectBuilder;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.function.BiFunction;

/** 在可信在线作用域内原子消费构建次数的 Vue 项目构建工具。 */
@Slf4j
@Component
public final class BuildProjectTool extends BaseTool {

    private final VueProjectBuilder vueProjectBuilder;
    private final BiFunction<Path, BuildResult, String> errorSanitizer;
    private final FileToolExecutionScopeManager scopeManager;

    @Autowired
    public BuildProjectTool(
            VueProjectBuilder vueProjectBuilder,
            BuildErrorSanitizer errorSanitizer,
            FileToolExecutionScopeManager scopeManager) {
        this(vueProjectBuilder, errorSanitizer::sanitize, scopeManager);
    }

    BuildProjectTool(
            VueProjectBuilder vueProjectBuilder,
            BiFunction<Path, BuildResult, String> errorSanitizer,
            FileToolExecutionScopeManager scopeManager) {
        this.vueProjectBuilder = vueProjectBuilder;
        this.errorSanitizer = errorSanitizer;
        this.scopeManager = scopeManager;
    }

    @Tool("构建当前Vue项目。完成文件修改后调用；失败时根据返回诊断修复，成功或达到上限时系统自动结束。")
    public String buildProject(@ToolMemoryId Long appId) {
        FileToolExecutionScopeManager.FileToolScope scope;
        try {
            scope = scopeManager.requireCurrent(
                    appId == null ? Long.MIN_VALUE : appId, getToolName());
        } catch (FileToolExecutionScopeManager.ScopeCancelledException exception) {
            return json(BuildProjectToolResult.cancelled(null, null, exception.getMessage()));
        } catch (FileToolExecutionScopeManager.ScopeViolationException exception) {
            return json(BuildProjectToolResult.rejected(exception.getMessage(), true));
        }
        if (scope.type() != FileToolExecutionScopeManager.ScopeType.ONLINE) {
            return json(BuildProjectToolResult.rejected(
                    "PROTOCOL_ERROR: buildProject 只允许在线 Vue 作用域调用", true));
        }
        return executeBuild(scope);
    }

    private String executeBuild(FileToolExecutionScopeManager.FileToolScope scope) {
        VueBuildLease lease = scope.lease();
        BuildAttemptTicket ticket;
        try {
            ticket = lease.beginBuild();
        } catch (BuildInProgressException exception) {
            return json(BuildProjectToolResult.buildInProgress());
        } catch (RuntimeException exception) {
            return json(terminalOrProtocolRejection(lease, exception));
        }

        BuildCancellationSignal cancellation = new BuildCancellationSignal();
        try (ticket) {
            CommittedBuild committedBuild;
            try {
                committedBuild = buildAndCommit(scope, ticket, cancellation);
            } catch (RuntimeException exception) {
                log.error("执行受控 Vue 构建失败: appId={}, attempt={}",
                        scope.appId(), ticket.attempt(), exception);
                return recordInfrastructureFailure(
                        scope, ticket, cancellation, exception);
            }
            return renderCommittedResult(scope, ticket, committedBuild);
        }
    }

    private CommittedBuild buildAndCommit(
            FileToolExecutionScopeManager.FileToolScope scope,
            BuildAttemptTicket ticket,
            BuildCancellationSignal cancellation) {
        Path projectRoot = Path.of(
                AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_" + scope.appId());
        ticket.registerCancellation(cancellation::cancel);
        BuildResult result;
        try (BuildLogSink logSink = new BuildLogSink(
                scope.appId(), scope.ownerToken(), ticket.attempt(), BuildStage.VALIDATION)) {
            BuildExecutionContext context = new BuildExecutionContext(
                    scope.appId(), scope.ownerToken(), ticket.attempt(), cancellation, logSink);
            result = vueProjectBuilder.buildProjectDetailed(projectRoot, context);
        }
        VueBuildSnapshot snapshot = scope.lease().recordResult(
                ticket, result, cancellation::isCancelled);
        return new CommittedBuild(result, snapshot);
    }

    private String renderCommittedResult(
            FileToolExecutionScopeManager.FileToolScope scope,
            BuildAttemptTicket ticket,
            CommittedBuild committedBuild) {
        try {
            return renderCommittedResult(scope, ticket, committedBuild.result(),
                    committedBuild.snapshot());
        } catch (RuntimeException exception) {
            log.error("构建结果已提交，但后处理失败: appId={}, attempt={}",
                    scope.appId(), ticket.attempt(), exception);
            return protocolErrorAfterCommit();
        }
    }

    private String renderCommittedResult(
            FileToolExecutionScopeManager.FileToolScope scope,
            BuildAttemptTicket ticket,
            BuildResult result,
            VueBuildSnapshot snapshot) {
        if (snapshot.phase() == VueBuildPhase.CANCELLED) {
            return json(BuildProjectToolResult.cancelled(
                    ticket.attempt(), result.stage(), "构建已取消"));
        }
        if (result.success()) {
            return json(BuildProjectToolResult.completedSuccess(ticket.attempt()));
        }
        Path projectRoot = Path.of(
                AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_" + scope.appId());
        String errorSummary = errorSanitizer.apply(projectRoot, result);
        return json(BuildProjectToolResult.completedFailure(
                ticket.attempt(), result.stage(), result.failureKind(),
                result.timedOut(), errorSummary));
    }

    private String recordInfrastructureFailure(
            FileToolExecutionScopeManager.FileToolScope scope,
            BuildAttemptTicket ticket,
            BuildCancellationSignal cancellation,
            RuntimeException exception) {
        BuildResult failure = new BuildResult(false, BuildStage.VALIDATION,
                null, false, false,
                com.lyw.appgeneration.core.builder.VueBuildFailureKind.INFRASTRUCTURE,
                exception.getClass().getSimpleName(), 0L);
        VueBuildSnapshot snapshot;
        try {
            snapshot = scope.lease().recordResult(
                    ticket, failure, cancellation::isCancelled);
        } catch (RuntimeException recordFailure) {
            exception.addSuppressed(recordFailure);
            log.error("记录构建基础设施失败时发生异常: appId={}, attempt={}",
                    scope.appId(), ticket.attempt(), recordFailure);
            return json(BuildProjectToolResult.rejected(
                    "PROTOCOL_ERROR: 无法提交构建失败状态", true));
        }
        return renderCommittedResult(
                scope, ticket, new CommittedBuild(failure, snapshot));
    }

    private String protocolErrorAfterCommit() {
        return json(BuildProjectToolResult.rejected(
                "PROTOCOL_ERROR: 构建结果后处理失败", true));
    }

    private BuildProjectToolResult terminalOrProtocolRejection(
            VueBuildLease lease, RuntimeException exception) {
        try {
            VueBuildSnapshot snapshot = lease.snapshot();
            if (snapshot.phase()
                    == com.lyw.appgeneration.core.builder.VueBuildPhase.CANCELLED) {
                return BuildProjectToolResult.cancelled(null, null, "构建回合已取消");
            }
        } catch (RuntimeException ignored) {
            // 精确租约失效统一按协议拒绝，不尝试查找其他回合。
        }
        return BuildProjectToolResult.rejected(
                "PROTOCOL_ERROR: 当前构建回合不能继续构建", true);
    }

    private String json(BuildProjectToolResult result) {
        return BuildProjectProtocolSupport.json(result);
    }

    @Override
    public String getToolName() {
        return "buildProject";
    }

    @Override
    public String getDisplayName() {
        return "构建项目";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        return generateToolExecutedResult(arguments, null);
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments, String rawResult) {
        BuildProjectToolResult result;
        try {
            result = BuildProjectProtocolSupport.parse(rawResult);
            if (!BuildProjectToolResult.PROTOCOL.equals(result.protocol())) {
                throw new IllegalArgumentException("构建工具协议不匹配");
            }
        } catch (RuntimeException exception) {
            return "构建工具结果协议解析失败";
        }
        if (result.invocationStatus()
                != BuildProjectToolResult.BuildInvocationStatus.COMPLETED) {
            return result.message();
        }
        if (Boolean.TRUE.equals(result.success())) {
            return "第 " + result.attempt() + " 次构建成功";
        }
        return switch (result.nextAction()) {
            case REPAIR -> "第 1 次构建失败（阶段："
                    + result.stage() + "），正在进行最小修复";
            case RETRY_BUILD -> "第 1 次构建失败（阶段："
                    + result.stage() + "），正在直接重试构建，未修改业务代码";
            case FINAL_DIAGNOSIS -> "第 2 次构建失败，已进入最终诊断";
            case STOP -> "第 3 次构建失败，已停止自动修复";
        };
    }

    private record CommittedBuild(BuildResult result, VueBuildSnapshot snapshot) {
    }
}
