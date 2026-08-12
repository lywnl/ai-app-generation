package com.lyw.appgeneration.ai.tools;

import com.lyw.appgeneration.core.builder.VueBuildPhase;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager.VueBuildLease;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager.VueBuildSnapshot;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;
import java.time.Duration;
import java.util.function.Supplier;

/** 为文件类工具提供不跨线程泄漏的词法执行权限。 */
@Component
public final class FileToolExecutionScopeManager {

    private static final ScopedValue<FileToolScope> CURRENT_SCOPE = ScopedValue.newInstance();
    private final ScopeAuthority scopeAuthority = new ScopeAuthority();

    public FileToolScope online(
            VueBuildLease lease,
            String ownerToken,
            long appId,
            Set<String> allowedTools) {
        Objects.requireNonNull(lease, "lease 不能为空");
        VueBuildSnapshot snapshot = lease.snapshot();
        if (snapshot.appId() != appId || !snapshot.turnId().equals(ownerToken)) {
            throw new IllegalArgumentException("在线作用域身份与精确租约不匹配");
        }
        return new FileToolScope(
                ScopeType.ONLINE, appId, ownerToken, Set.copyOf(allowedTools), lease,
                null, scopeAuthority);
    }

    public FileToolScope evaluation(
            long appId, String ownerToken, Set<String> allowedTools) {
        if (appId <= 0) {
            throw new IllegalArgumentException("appId 必须大于 0");
        }
        return new FileToolScope(
                ScopeType.EVALUATION, appId, requireToken(ownerToken),
                Set.copyOf(allowedTools), null, new EvaluationGate(), scopeAuthority);
    }

    public <T> T callInScope(FileToolScope scope, Supplier<T> action) {
        Objects.requireNonNull(scope, "scope 不能为空");
        Objects.requireNonNull(action, "action 不能为空");
        requireIssuedScope(scope);
        if (scope.type() == ScopeType.EVALUATION) {
            try (EvaluationTicket ignored = scope.evaluationGate().enter()) {
                return ScopedValue.where(CURRENT_SCOPE, scope).call(action::get);
            }
        }
        try (AutoCloseable ignored = scope.lease().enterCallback()) {
            return ScopedValue.where(CURRENT_SCOPE, scope).call(action::get);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("关闭在线工具回调失败", exception);
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
                ScopeAuthority authority) {

        public FileToolScope {
            type = Objects.requireNonNull(type, "type 不能为空");
            ownerToken = requireToken(ownerToken);
            allowedTools = Set.copyOf(
                    Objects.requireNonNull(allowedTools, "allowedTools 不能为空"));
            Objects.requireNonNull(authority, "authority 不能为空");
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
