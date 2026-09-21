package com.lyw.appgeneration.ai.tools;

import com.lyw.appgeneration.core.builder.VueBuildPhase;
import com.lyw.appgeneration.core.builder.VueBuildFailureKind;
import com.lyw.appgeneration.ai.plan.AppPlanStateManager;
import com.lyw.appgeneration.ai.plan.BuildBlockDiagnostic;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager.VueBuildLease;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager.VueBuildSnapshot;
import com.lyw.appgeneration.ai.skill.SkillReadSession;
import org.springframework.stereotype.Component;
import dev.langchain4j.service.ReplanContext;
import dev.langchain4j.service.BuildProgressGuard;
import dev.langchain4j.service.ToolExecutionGuard;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Objects;
import java.util.Set;
import java.time.Duration;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;

/** 为文件类工具提供不跨线程泄漏的词法执行权限。 */
@Component
public final class FileToolExecutionScopeManager {

    private static final ScopedValue<FileToolScope> CURRENT_SCOPE = ScopedValue.newInstance();
    private static final ScopedValue<BuildObservationCapture> BUILD_OBSERVATION = ScopedValue.newInstance();
    private static final Set<String> MUTATION_TOOLS = Set.of(
            "writeFile", "modifyFile", "deleteFile");
    private static final String NON_CODE_FAILURE_MESSAGE =
            "当前为依赖或基础设施故障，请勿修改业务文件，直接重新构建";
    private static final String REPLAN_PENDING_MESSAGE =
            "当前计划存在未处理偏差，请先调用 updatePlan";
    private final ScopeAuthority scopeAuthority = new ScopeAuthority();
    private final FileToolBudgetGuard budgetGuard;
    private final AppPlanStateManager planStateManager;

    @Autowired
    public FileToolExecutionScopeManager(
            FileToolBudgetGuard budgetGuard,
            AppPlanStateManager planStateManager) {
        this.budgetGuard = Objects.requireNonNull(budgetGuard, "文件工具预算不能为空");
        this.planStateManager = Objects.requireNonNull(
                planStateManager, "计划状态管理器不能为空");
    }

    public FileToolExecutionScopeManager(FileToolBudgetGuard budgetGuard) {
        this.budgetGuard = Objects.requireNonNull(budgetGuard, "文件工具预算不能为空");
        this.planStateManager = null;
    }

    public FileToolScope online(
            VueBuildLease lease,
            String ownerToken,
            long appId,
            Set<String> allowedTools) {
        return online(lease, ownerToken, appId, allowedTools,
                budgetGuard.newSession());
    }

    public FileToolScope online(
            VueBuildLease lease,
            String ownerToken,
            long appId,
            Set<String> allowedTools,
            FileToolBudgetGuard.Session budgetSession) {
        return online(lease, ownerToken, appId, allowedTools,
                budgetSession, () -> true, null);
    }

    public FileToolScope online(
            VueBuildLease lease,
            String ownerToken,
            long appId,
            Set<String> allowedTools,
            FileToolBudgetGuard.Session budgetSession,
            BooleanSupplier mutationAllowed) {
        return online(lease, ownerToken, appId, allowedTools,
                budgetSession, mutationAllowed, null);
    }

    public FileToolScope online(
            VueBuildLease lease,
            String ownerToken,
            long appId,
            Set<String> allowedTools,
            FileToolBudgetGuard.Session budgetSession,
            BooleanSupplier mutationAllowed,
            ReplanContext replanContext) {
        Objects.requireNonNull(lease, "lease 不能为空");
        VueBuildSnapshot snapshot = lease.snapshot();
        if (snapshot.appId() != appId || !snapshot.turnId().equals(ownerToken)) {
            throw new IllegalArgumentException("在线作用域身份与精确租约不匹配");
        }
        return new FileToolScope(
                ScopeType.ONLINE, appId, ownerToken, Set.copyOf(allowedTools), lease,
                null, budgetSession, new SkillReadSession(), scopeAuthority,
                Objects.requireNonNull(mutationAllowed, "计划变更权限不能为空"),
                replanContext, planStateManager);
    }

    public FileToolScope evaluation(
            long appId, String ownerToken, Set<String> allowedTools) {
        if (appId <= 0) {
            throw new IllegalArgumentException("appId 必须大于 0");
        }
        return new FileToolScope(
                ScopeType.EVALUATION, appId, requireToken(ownerToken),
                Set.copyOf(allowedTools), null, new EvaluationGate(),
                budgetGuard.newSession(), null, scopeAuthority, () -> false,
                null, null);
    }

    public <T> T callInScope(
            FileToolScope scope, String toolName, Supplier<T> action) {
        Objects.requireNonNull(scope, "scope 不能为空");
        Objects.requireNonNull(action, "action 不能为空");
        requireIssuedScope(scope);
        requireToolName(scope, toolName);
        if (scope.type() == ScopeType.EVALUATION) {
            try (EvaluationTicket ignored = scope.evaluationGate().enter()) {
                return ScopedValue.where(CURRENT_SCOPE, scope).call(action::get);
            }
        }
        try (AutoCloseable ignored = scope.lease().enterCallback()) {
            T result = ScopedValue.where(CURRENT_SCOPE, scope).call(action::get);
            recordAppliedMutation(scope, toolName, result);
            return result;
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("关闭在线工具回调失败", exception);
        }
    }

    /** 内部观察与单次词法调用绑定，不经过模型可见的工具协议。 */
    public ToolExecutionGuard.GuardedToolExecution callInScopeWithBuildObservation(
            FileToolScope scope, String toolName, Supplier<String> action) {
        BuildObservationCapture capture = new BuildObservationCapture();
        boolean observe = scope.type() == ScopeType.ONLINE && scope.mutationAllowed().getAsBoolean()
                && scope.planStateManager() != null;
        boolean progressTool = MUTATION_TOOLS.contains(toolName) || "makePlan".equals(toolName) || "updatePlan".equals(toolName);
        String result = ScopedValue.where(BUILD_OBSERVATION, capture).call(() -> callInScope(scope, toolName, () -> {
            if (observe && progressTool) {
                capture.before = buildDiagnostic(scope);
                capture.failOpenBefore = scope.replanContext() != null && scope.replanContext().failOpen();
            }
            return action.get();
        }));
        BuildProgressGuard.Observation observation = null;
        if (observe) {
            var fact = VueToolExecutionFact.parse(toolName, result).orElse(null);
            boolean successfulMutation = fact != null && fact.changedRelativePath() != null;
            boolean successfulPlan = fact != null && ("makePlan".equals(toolName) || "updatePlan".equals(toolName))
                    && fact.status() == VueToolExecutionFact.ExecutionStatus.SUCCEEDED;
            boolean realBuild = fact != null && "buildProject".equals(toolName) && fact.buildAttempt() != null
                    && (fact.status() == VueToolExecutionFact.ExecutionStatus.SUCCEEDED
                    || fact.status() == VueToolExecutionFact.ExecutionStatus.FAILED
                    || fact.status() == VueToolExecutionFact.ExecutionStatus.TIMED_OUT);
            boolean progress = successfulMutation || successfulPlan;
            try {
                if (capture.rejection != null || progress || realBuild) {
                    observation = scope.lease().commitWhileActive(() -> new BuildProgressGuard.Observation(
                            scope.ownerToken(), capture.before, progress ? buildDiagnostic(scope) : null,
                            fact != null && fact.status() == VueToolExecutionFact.ExecutionStatus.REJECTED ? capture.rejection : null,
                            progress, realBuild, scope.lease().snapshot().mutationRevision(),
                            !capture.failOpenBefore && scope.replanContext() != null && scope.replanContext().failOpen()));
                }
            } catch (com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager.CommitRejectedException ignored) {
                // 取消先赢时仍保留真实工具结果，由结果提交栅栏决定发布，观察不得推进。
            }
        }
        return new ToolExecutionGuard.GuardedToolExecution(result, null, observation);
    }

    public void recordBuildRejection(FileToolScope scope, BuildBlockDiagnostic diagnostic) {
        requireIssuedScope(scope);
        if (CURRENT_SCOPE.isBound() && CURRENT_SCOPE.get() == scope && BUILD_OBSERVATION.isBound()) {
            BUILD_OBSERVATION.get().rejection = diagnostic;
        }
    }

    private BuildBlockDiagnostic buildDiagnostic(FileToolScope scope) {
        boolean pending = scope.replanContext() != null && scope.replanContext().replanPending() && !scope.replanContext().failOpen();
        var plan = scope.planStateManager().beforeBuild(scope.appId(), scope.ownerToken(), pending);
        return plan.allowed() && scope.lease().requiresCodeMutation()
                ? BuildBlockDiagnostic.single(BuildBlockDiagnostic.Reason.CODE_MUTATION_REQUIRED) : plan.diagnostic();
    }

    private static final class BuildObservationCapture {
        private BuildBlockDiagnostic before;
        private BuildBlockDiagnostic rejection;
        private boolean failOpenBefore;
    }

    private void requireToolName(FileToolScope scope, String toolName) {
        if (toolName == null || toolName.isBlank()) {
            throw new ScopeViolationException("PROTOCOL_ERROR: 工具名称不能为空");
        }
        if (!scope.allowedTools().contains(toolName)) {
            throw new ScopeViolationException("PROTOCOL_ERROR: 工具不在当前作用域白名单中");
        }
    }

    /**
     * 在具体 mutation 工具完成身份校验后、解析路径或执行 IO 前应用故障策略。
     *
     * @return 非空表示本次 mutation 被策略拒绝，返回值是可直接交给模型的受信协议
     */
    public String rejectForbiddenMutation(
            FileToolScope scope, String toolName, String relativePath) {
        Objects.requireNonNull(scope, "scope 不能为空");
        requireIssuedScope(scope);
        requireToolName(scope, toolName);
        if (scope.type() != ScopeType.ONLINE || !MUTATION_TOOLS.contains(toolName)) {
            return null;
        }
        if (scope.replanContext() != null
                && scope.replanContext().replanPending()
                && !scope.replanContext().failOpen()) {
            return FileToolProtocolSupport.json(FileToolResult.rejected(
                    toolName, relativePath, REPLAN_PENDING_MESSAGE));
        }
        VueBuildFailureKind failureKind = scope.lease().snapshot().failureKind();
        if (failureKind != VueBuildFailureKind.DEPENDENCY
                && failureKind != VueBuildFailureKind.INFRASTRUCTURE) {
            return null;
        }
        return FileToolProtocolSupport.json(FileToolResult.rejected(
                toolName, relativePath, NON_CODE_FAILURE_MESSAGE));
    }

    public void requireMutationAllowed(FileToolScope scope, String toolName) {
        Objects.requireNonNull(scope, "scope 不能为空");
        requireIssuedScope(scope);
        requireToolName(scope, toolName);
        if (scope.type() != ScopeType.ONLINE || !scope.mutationAllowed().getAsBoolean()) {
            throw new ScopeViolationException(
                    "PROTOCOL_ERROR: 当前只读回合不允许计划变更");
        }
    }

    public void acknowledgePlanUpdate(FileToolScope scope) {
        Objects.requireNonNull(scope, "scope 不能为空");
        requireIssuedScope(scope);
        if (scope.replanContext() != null) {
            scope.replanContext().acknowledgePlanUpdate();
        }
    }

    private void recordAppliedMutation(
            FileToolScope scope, String toolName, Object rawResult) {
        if (scope.type() == ScopeType.ONLINE
                && MUTATION_TOOLS.contains(toolName)
                && rawResult instanceof String result
                && FileToolProtocolSupport.isAppliedMutation(result, toolName)) {
            String relativePath = FileToolProtocolSupport.parseTrustedResult(
                    result, toolName).relativePath();
            if (scope.replanContext() != null) {
                // 先观察原始可信结果，再归档计划状态，避免计划外路径被提前加入计划后丢失偏差信号。
                scope.replanContext().observeToolExecution(toolName, result);
            }
            scope.lease().commitWhileActive(() -> {
                if (scope.replanContext() != null
                        && scope.replanContext().replanPending()
                        && scope.planStateManager() != null) {
                    scope.planStateManager().markReplanPending(
                            scope.appId(), scope.ownerToken());
                }
                scope.lease().recordSuccessfulMutation();
                if (scope.planStateManager() != null) {
                    scope.planStateManager().recordSuccessfulMutation(
                            scope.appId(), scope.ownerToken(), relativePath);
                }
                return null;
            });
        }
    }

    /** 关闭评测作用域；关闭后旧 guard 无法重新取得执行票据。 */
    public void closeEvaluation(FileToolScope scope) {
        revokeEvaluation(scope);
    }

    /** 立即撤销评测作用域的新工具票据，不等待已经开始的动作。 */
    public void revokeEvaluation(FileToolScope scope) {
        Objects.requireNonNull(scope, "scope 不能为空");
        requireIssuedScope(scope);
        if (scope.type() != ScopeType.EVALUATION) {
            throw new IllegalArgumentException("只能关闭评测工具作用域");
        }
        scope.evaluationGate().revoke();
    }

    /** 在给定上限内等待已领取的评测工具票据退出。 */
    public boolean awaitEvaluationQuiescence(FileToolScope scope, Duration timeout) {
        Objects.requireNonNull(scope, "scope 不能为空");
        Objects.requireNonNull(timeout, "timeout 不能为空");
        requireIssuedScope(scope);
        if (scope.type() != ScopeType.EVALUATION) {
            throw new IllegalArgumentException("只能等待评测工具作用域");
        }
        return scope.evaluationGate().awaitQuiescence(timeout);
    }

    public FileToolScope requireCurrent(long appId, String toolName) {
        if (!CURRENT_SCOPE.isBound()) {
            throw new ScopeViolationException("PROTOCOL_ERROR: 缺少可信工具执行作用域");
        }
        FileToolScope scope = CURRENT_SCOPE.get();
        requireIssuedScope(scope);
        if (scope.appId() != appId) {
            throw new ScopeViolationException("PROTOCOL_ERROR: 应用标识与工具作用域不匹配");
        }
        if (!scope.allowedTools().contains(toolName)) {
            throw new ScopeViolationException("PROTOCOL_ERROR: 工具不在当前作用域白名单中");
        }
        if (scope.type() == ScopeType.ONLINE) {
            validateOnlineScope(scope);
        } else {
            scope.evaluationGate().requireActive();
        }
        return scope;
    }

    private void validateOnlineScope(FileToolScope scope) {
        VueBuildSnapshot snapshot;
        try {
            snapshot = scope.lease().snapshot();
        } catch (RuntimeException exception) {
            throw new ScopeViolationException(
                    "PROTOCOL_ERROR: 在线工具租约已经失效", exception);
        }
        if (snapshot.appId() != scope.appId()
                || !snapshot.turnId().equals(scope.ownerToken())) {
            throw new ScopeViolationException("PROTOCOL_ERROR: 在线工具租约身份不匹配");
        }
        if (snapshot.phase() == VueBuildPhase.CANCELLED) {
            throw new ScopeCancelledException("当前在线生成回合已经取消");
        }
        if (snapshot.phase() == VueBuildPhase.SUCCEEDED
                || snapshot.phase() == VueBuildPhase.FAILED) {
            throw new ScopeViolationException("PROTOCOL_ERROR: 在线生成回合已经终止");
        }
    }

    private static String requireToken(String ownerToken) {
        Objects.requireNonNull(ownerToken, "ownerToken 不能为空");
        if (ownerToken.isBlank()) {
            throw new IllegalArgumentException("ownerToken 不能为空白");
        }
        return ownerToken;
    }

    private void requireIssuedScope(FileToolScope scope) {
        if (scope.authority() != scopeAuthority) {
            throw new ScopeViolationException("PROTOCOL_ERROR: 工具执行作用域不是由当前管理器签发");
        }
    }

    public enum ScopeType {
        ONLINE,
        EVALUATION
    }

    public record FileToolScope(
                ScopeType type,
                long appId,
                String ownerToken,
                Set<String> allowedTools,
                VueBuildLease lease,
                EvaluationGate evaluationGate,
                FileToolBudgetGuard.Session budgetSession,
                SkillReadSession skillReadSession,
                ScopeAuthority authority,
                BooleanSupplier mutationAllowed,
                ReplanContext replanContext,
                AppPlanStateManager planStateManager) {

        public FileToolScope {
            type = Objects.requireNonNull(type, "type 不能为空");
            ownerToken = requireToken(ownerToken);
            allowedTools = Set.copyOf(
                    Objects.requireNonNull(allowedTools, "allowedTools 不能为空"));
            Objects.requireNonNull(authority, "authority 不能为空");
            Objects.requireNonNull(mutationAllowed, "计划变更权限不能为空");
            Objects.requireNonNull(budgetSession, "文件工具预算会话不能为空");
            if (type == ScopeType.ONLINE && skillReadSession == null) {
                throw new IllegalArgumentException("在线作用域必须绑定 Skill 读取会话");
            }
            if (type == ScopeType.EVALUATION && skillReadSession != null) {
                throw new IllegalArgumentException("评测作用域不能绑定 Skill 读取会话");
            }
            if (type == ScopeType.ONLINE && lease == null) {
                throw new IllegalArgumentException("在线作用域必须绑定精确 Vue 租约");
            }
            if (type == ScopeType.ONLINE && evaluationGate != null) {
                throw new IllegalArgumentException("在线作用域不能绑定评测生命周期门");
            }
            if (type == ScopeType.EVALUATION
                && (lease != null || evaluationGate == null)) {
                throw new IllegalArgumentException("评测作用域必须绑定独立生命周期门且不能绑定在线租约");
            }
        }

        public SkillReadSession skillReadSession() {
            if (skillReadSession == null) {
                throw new ScopeViolationException("评测作用域不支持 Skill 读取");
            }
            return skillReadSession;
        }
    }

    private static final class EvaluationGate {

        private boolean active = true;
        private int inFlight;

        private synchronized EvaluationTicket enter() {
            if (!active) {
                throw new ScopeViolationException("PROTOCOL_ERROR: 评测工具作用域已经关闭");
            }
            inFlight++;
            return new EvaluationTicket(this);
        }

        private synchronized void leave() {
            inFlight--;
            if (inFlight == 0) {
                notifyAll();
            }
        }

        private synchronized void revoke() {
            active = false;
        }

        private synchronized void requireActive() {
            if (!active) {
                throw new ScopeViolationException("PROTOCOL_ERROR: 评测工具作用域已经关闭");
            }
        }

        private synchronized boolean awaitQuiescence(Duration timeout) {
            if (timeout.isNegative()) {
                throw new IllegalArgumentException("等待时长不能为负数");
            }
            long remainingNanos = timeout.toNanos();
            long deadline = System.nanoTime() + remainingNanos;
            while (inFlight > 0) {
                if (remainingNanos <= 0) {
                    return false;
                }
                try {
                    long millis = Math.max(1L, remainingNanos / 1_000_000L);
                    wait(millis);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                remainingNanos = deadline - System.nanoTime();
            }
            return true;
        }
    }

    private static final class EvaluationTicket implements AutoCloseable {

        private final EvaluationGate gate;
        private boolean closed;

        private EvaluationTicket(EvaluationGate gate) {
            this.gate = gate;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                gate.leave();
            }
        }
    }

    private static final class ScopeAuthority {
    }

    public static class ScopeViolationException extends IllegalStateException {

        public ScopeViolationException(String message) {
            super(message);
        }

        public ScopeViolationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class ScopeCancelledException extends ScopeViolationException {

        public ScopeCancelledException(String message) {
            super(message);
        }
    }
}
