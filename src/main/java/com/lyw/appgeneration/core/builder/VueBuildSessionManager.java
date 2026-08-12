package com.lyw.appgeneration.core.builder;

import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager.AppOperationLease;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager.AppOperationType;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager.CallbackRegistration;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager.CancellationRegistration;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Vue 构建回合状态机。
 *
 * <p>该类型不拥有 app 级锁，也不保存 appId 到会话的第二套映射。每个回合只消费
 * 一份精确的 {@link AppOperationLease}，全局互斥、取消门和回调静默均由操作租约负责。</p>
 */
@Component
public final class VueBuildSessionManager {

    private static final int MAX_BUILD_ATTEMPTS = 3;

    /** 用生成操作租约打开一次不可伪造的 Vue 构建回合。 */
    public VueBuildLease open(
            AppOperationLease operationLease, long userId, String turnId) {
        Objects.requireNonNull(operationLease, "operationLease 不能为空");
        validateIdentity(userId, turnId);
        if (!operationLease.isActive()) {
            throw new IllegalStateException("应用操作租约已经失效");
        }
        if (operationLease.operationType() != AppOperationType.GENERATE) {
            throw new IllegalArgumentException("Vue 构建回合只接受 GENERATE 租约");
        }
        if (!operationLease.ownerToken().equals(turnId)) {
            throw new IllegalArgumentException("turnId 必须匹配操作租约 ownerToken");
        }
        operationLease.claimVueSession();
        Session session = new Session(operationLease, userId, turnId);
        session.bindOperationCancellation();
        return new VueBuildLease(session);
    }

    private static void validateIdentity(long userId, String turnId) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId 必须大于 0");
        }
        Objects.requireNonNull(turnId, "turnId 不能为空");
        if (turnId.isBlank()) {
            throw new IllegalArgumentException("turnId 不能为空白");
        }
    }

    /** 单次 Vue 生成回合的精确权限句柄。 */
    public static final class VueBuildLease implements AutoCloseable {

        private final Session session;
        private final AtomicBoolean closed = new AtomicBoolean();

        private VueBuildLease(Session session) {
            this.session = session;
        }

        public VueBuildSnapshot snapshot() {
            ensureOpen();
            return session.snapshot();
        }

        public boolean canBuild() {
            ensureOpen();
            return session.canBuild();
        }

        public BuildAttemptTicket beginBuild() {
            ensureOpen();
            return session.beginBuild();
        }

        public VueBuildSnapshot recordSuccess(
                BuildAttemptTicket ticket, BuildResult result) {
            ensureOpen();
            return session.recordSuccess(ticket, result);
        }

        public VueBuildSnapshot recordFailure(
                BuildAttemptTicket ticket, BuildResult result) {
            ensureOpen();
            return session.recordFailure(ticket, result);
        }

        public void registerModelCancellation(Runnable cancellationAction) {
            ensureOpen();
            session.registerModelCancellation(cancellationAction);
        }

        public boolean cancel() {
            ensureOpen();
            return session.cancel();
        }

        public AutoCloseable enterCallback() {
            ensureOpen();
            return session.enterCallback();
        }

        public boolean awaitQuiescence(Duration timeout) throws InterruptedException {
            ensureOpen();
            return session.awaitQuiescence(timeout);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                session.close();
            }
        }

        private void ensureOpen() {
            if (closed.get()) {
                throw new IllegalStateException("Vue 构建租约已经关闭");
            }
        }
    }

    /**
     * 一次已消费构建次数的不可伪造票据。
     *
     * <p>关闭未提交的票据不会退回次数，而是转成基础设施失败。</p>
     */
    public static final class BuildAttemptTicket implements AutoCloseable {

        private final Session owner;
        private final int attempt;
        private final AtomicBoolean closed = new AtomicBoolean();
        private CancellationRegistration cancellationRegistration;
        private boolean cancellationRegistered;
        private boolean completed;

        private BuildAttemptTicket(Session owner, int attempt) {
            this.owner = owner;
            this.attempt = attempt;
        }

        public int attempt() {
            return attempt;
        }

        public void registerCancellation(Runnable cancellationAction) {
            Objects.requireNonNull(cancellationAction, "取消动作不能为空");
            synchronized (owner) {
                ensureUsableForRegistration();
                cancellationRegistered = true;
                cancellationRegistration = owner.operationLease
                        .registerCancellation(cancellationAction);
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.closeTicket(this);
            }
        }

        private void markCompleted() {
            completed = true;
        }

        private void closeCancellationRegistration() {
            CancellationRegistration registration = cancellationRegistration;
            cancellationRegistration = null;
            if (registration != null) {
                registration.close();
            }
        }

        private void ensureUsableForRegistration() {
            if (closed.get() || completed) {
                throw new IllegalStateException("构建票据已经完成或关闭");
            }
            if (cancellationRegistered) {
                throw new IllegalStateException("构建取消动作已注册");
            }
        }
    }

    /** 可跨线程安全读取的不可变回合快照。 */
    public record VueBuildSnapshot(
            long appId,
            long userId,
            String turnId,
            VueBuildPhase phase,
            int buildAttempt,
            VueBuildFailureKind failureKind) {
    }

    /** 同一回合已有真实构建占用。 */
    public static final class BuildInProgressException extends IllegalStateException {

        private BuildInProgressException() {
            super("当前 Vue 回合已有构建正在执行");
        }
    }

    private static final class Session {

        private final AppOperationLease operationLease;
        private final long userId;
        private final String turnId;

        private VueBuildPhase phase = VueBuildPhase.GENERATING;
        private int buildAttempt;
        private VueBuildFailureKind failureKind;
        private BuildAttemptTicket activeTicket;
        private CancellationRegistration operationCancellation;
        private CancellationRegistration modelCancellation;
        private boolean modelCancellationRegistered;
        private boolean closed;

        private Session(
                AppOperationLease operationLease, long userId, String turnId) {
            this.operationLease = operationLease;
            this.userId = userId;
            this.turnId = turnId;
        }

        private void bindOperationCancellation() {
            operationCancellation = operationLease.registerCancellation(
                    this::cancelFromOperation);
        }

        private synchronized VueBuildSnapshot snapshot() {
            return snapshotUnsafe();
        }

        private synchronized boolean canBuild() {
            return !closed && !isTerminal() && buildAttempt < MAX_BUILD_ATTEMPTS
                    && activeTicket == null;
        }

        private synchronized BuildAttemptTicket beginBuild() {
            ensureOpen();
            if (isTerminal() || buildAttempt >= MAX_BUILD_ATTEMPTS) {
                throw new IllegalStateException("当前 Vue 回合不能继续构建");
            }
            if (activeTicket != null) {
                throw new BuildInProgressException();
            }
            buildAttempt++;
            activeTicket = new BuildAttemptTicket(this, buildAttempt);
            return activeTicket;
        }

        private synchronized VueBuildSnapshot recordSuccess(
                BuildAttemptTicket ticket, BuildResult result) {
            validateTicket(ticket);
            Objects.requireNonNull(result, "BuildResult 不能为空");
            if (!result.success() || result.stage() != BuildStage.SUCCESS) {
                throw new IllegalArgumentException("recordSuccess 只接受成功构建结果");
            }
            completeTicket(ticket);
            failureKind = null;
            phase = VueBuildPhase.SUCCEEDED;
            return snapshotUnsafe();
        }

        private synchronized VueBuildSnapshot recordFailure(
                BuildAttemptTicket ticket, BuildResult result) {
            Objects.requireNonNull(result, "BuildResult 不能为空");
            if (result.success()) {
                throw new IllegalArgumentException("recordFailure 只接受失败构建结果");
            }
            validateTicket(ticket, result.cancelled() && phase == VueBuildPhase.CANCELLED);
            completeTicket(ticket);
            if (result.cancelled()) {
                failureKind = null;
                phase = VueBuildPhase.CANCELLED;
                return snapshotUnsafe();
            }
            failureKind = classifyFailure(result);
            phase = failurePhase(ticket.attempt);
            return snapshotUnsafe();
        }

        private void registerModelCancellation(Runnable cancellationAction) {
            Objects.requireNonNull(cancellationAction, "模型取消动作不能为空");
            synchronized (this) {
                ensureOpen();
                if (modelCancellationRegistered) {
                    throw new IllegalStateException("模型取消动作已注册");
                }
                modelCancellationRegistered = true;
                modelCancellation = operationLease.registerCancellation(cancellationAction);
            }
        }

        private boolean cancel() {
            synchronized (this) {
                ensureOpen();
                if (phase == VueBuildPhase.CANCELLED || isCompletedTerminal()) {
                    return false;
                }
                phase = VueBuildPhase.CANCELLED;
                failureKind = null;
            }
            return operationLease.requestCancellation();
        }

        private synchronized void cancelFromOperation() {
            if (!isCompletedTerminal()) {
                phase = VueBuildPhase.CANCELLED;
                failureKind = null;
            }
        }

        private AutoCloseable enterCallback() {
            synchronized (this) {
                ensureOpen();
                if (phase == VueBuildPhase.CANCELLED) {
                    throw new IllegalStateException("Vue 构建回合已经取消");
                }
            }
            CallbackRegistration registration = operationLease.enterCallback();
            return registration::close;
        }

        private boolean awaitQuiescence(Duration timeout) throws InterruptedException {
            synchronized (this) {
                ensureOpen();
            }
            return operationLease.awaitQuiescence(timeout);
        }

        private void close() {
            BuildAttemptTicket ticket;
            CancellationRegistration modelRegistration;
            CancellationRegistration operationRegistration;
            synchronized (this) {
                if (closed) {
                    return;
                }
                ticket = activeTicket;
                modelRegistration = modelCancellation;
                operationRegistration = operationCancellation;
                closed = true;
            }
            if (ticket != null) {
                ticket.close();
            }
            if (modelRegistration != null) {
                modelRegistration.close();
            }
            if (operationRegistration != null) {
                operationRegistration.close();
            }
        }

        private synchronized void closeTicket(BuildAttemptTicket ticket) {
            if (ticket.owner != this || activeTicket != ticket) {
                ticket.closeCancellationRegistration();
                return;
            }
            if (!ticket.completed && !isTerminal()) {
                failureKind = VueBuildFailureKind.INFRASTRUCTURE;
                phase = failurePhase(ticket.attempt);
            }
            ticket.closeCancellationRegistration();
            activeTicket = null;
        }

        private void validateTicket(BuildAttemptTicket ticket) {
            validateTicket(ticket, false);
        }

        private void validateTicket(
                BuildAttemptTicket ticket, boolean allowExistingCancellation) {
            Objects.requireNonNull(ticket, "ticket 不能为空");
            if (ticket.owner != this) {
                throw new IllegalArgumentException("构建票据不属于当前 Vue 回合");
            }
            if (ticket.closed.get() || ticket.completed || activeTicket != ticket) {
                throw new IllegalStateException("构建票据已经完成、关闭或失效");
            }
            if (isTerminal() && !allowExistingCancellation) {
                throw new IllegalStateException("当前 Vue 回合不能记录构建结果");
            }
        }

        private void completeTicket(BuildAttemptTicket ticket) {
            ticket.markCompleted();
            ticket.closeCancellationRegistration();
            activeTicket = null;
        }

        private VueBuildSnapshot snapshotUnsafe() {
            return new VueBuildSnapshot(
                    operationLease.appId(), userId, turnId,
                    phase, buildAttempt, failureKind);
        }

        private boolean isTerminal() {
            return isCompletedTerminal() || phase == VueBuildPhase.CANCELLED;
        }

        private boolean isCompletedTerminal() {
            return phase == VueBuildPhase.SUCCEEDED || phase == VueBuildPhase.FAILED;
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException("Vue 构建回合已经关闭");
            }
        }

        private static VueBuildPhase failurePhase(int attempt) {
            return switch (attempt) {
                case 1 -> VueBuildPhase.REPAIRING;
                case 2 -> VueBuildPhase.FINAL_DIAGNOSIS;
                case 3 -> VueBuildPhase.FAILED;
                default -> throw new IllegalStateException("构建次数不能超过 3 次");
            };
        }

        private static VueBuildFailureKind classifyFailure(BuildResult result) {
            return Objects.requireNonNull(
                    result.failureKind(), "失败构建结果必须包含 failureKind");
        }
    }
}
