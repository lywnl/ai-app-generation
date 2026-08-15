package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.ai.tools.FileToolBudgetGuard;
import com.lyw.appgeneration.core.builder.VueBuildPhase;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager.VueBuildLease;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager.VueBuildSnapshot;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager.AppOperationLease;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager.CancellationClaimResult;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager.DeleteTakeoverContext;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager.DeleteTakeoverRegistration;
import com.lyw.appgeneration.core.concurrency.VueTurnAdmissionController.AdmissionPermit;
import dev.langchain4j.service.ToolLoopTerminationProtocol.ControlledTermination;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.Optional;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** 精确绑定一次 app 操作租约和 Vue 构建租约的在线回合上下文。 */
@Slf4j
public final class VueTurnContext {

    public static final Duration TURN_DEADLINE = Duration.ofMinutes(30);

    private final long appId;
    private final long userId;
    private final String turnId;
    private final AppOperationLease operationLease;
    private final VueBuildLease lease;
    private final AdmissionPermit admissionPermit;
    private final VueBuildPhase testingPhase;
    private final boolean testingTimedOut;
    private final long startedAtNanos;
    private final long deadlineNanos;
    private final LongSupplier nanoTicker;
    private final AtomicReference<ControlledTermination> controlledTermination =
            new AtomicReference<>();
    private final AtomicReference<TurnState> turnState =
            new AtomicReference<>(TurnState.active());
    private final CompletableFuture<VueTurnFinalizer.FinalizationResult> finalization =
            new CompletableFuture<>();
    private final Object finalizationObserverLock = new Object();
    private final Map<Long, FinalizationObserver> finalizationObservers =
            new LinkedHashMap<>();
    private final AtomicLong nextObserverId = new AtomicLong();
    private boolean finalizationExecutionClaimed;
    private final AtomicBoolean resourcesClosed = new AtomicBoolean();
    private final AtomicReference<DeleteTakeoverRegistration>
            deleteTakeoverRegistration = new AtomicReference<>();
    private final Sinks.One<DeleteTakeoverRequest> deleteTakeoverSignal =
            Sinks.one();
    private final ReentrantLock commitGate = new ReentrantLock();
    private UserCommitState userCommitState = UserCommitState.PREPARING;
    private final CallbackGate callbackGate = new CallbackGate();
    private final FileToolBudgetGuard.Session budgetSession;

    VueTurnContext(long appId, long userId, String turnId,
            AppOperationLease operationLease, VueBuildLease lease,
            FileToolBudgetGuard.Session budgetSession) {
        this(appId, userId, turnId, operationLease, lease, null,
                null, false,
                operationLease.startedAtNanos(), TURN_DEADLINE,
                System::nanoTime, budgetSession);
        VueBuildSnapshot snapshot = lease.snapshot();
        validateIdentity(appId, userId, turnId, operationLease, snapshot);
    }

    public VueTurnContext(long appId, long userId, String turnId,
            AppOperationLease operationLease, VueBuildLease lease,
            AdmissionPermit admissionPermit,
            FileToolBudgetGuard.Session budgetSession) {
        this(appId, userId, turnId, operationLease, lease,
                Objects.requireNonNull(admissionPermit,
                        "Vue 回合准入许可不能为空"),
                null, false, operationLease.startedAtNanos(), TURN_DEADLINE,
                System::nanoTime, budgetSession);
        VueBuildSnapshot snapshot = lease.snapshot();
        validateIdentity(appId, userId, turnId, operationLease, snapshot);
    }

    private VueTurnContext(long appId, long userId, String turnId,
            AppOperationLease operationLease, VueBuildLease lease,
            AdmissionPermit admissionPermit,
            VueBuildPhase testingPhase, boolean testingTimedOut,
            long startedAtNanos, Duration deadlineDuration,
            LongSupplier nanoTicker,
            FileToolBudgetGuard.Session budgetSession) {
        if (appId <= 0 || userId <= 0) {
            throw new IllegalArgumentException("appId 和 userId 必须大于 0");
        }
        this.appId = appId;
        this.userId = userId;
        this.turnId = Objects.requireNonNull(turnId, "turnId 不能为空");
        if (turnId.isBlank()) {
            throw new IllegalArgumentException("turnId 不能为空白");
        }
        this.operationLease = operationLease;
        this.lease = lease;
        this.admissionPermit = admissionPermit;
        this.testingPhase = testingPhase;
        this.testingTimedOut = testingTimedOut;
        this.startedAtNanos = startedAtNanos;
        this.nanoTicker = Objects.requireNonNull(nanoTicker, "nanoTicker 不能为空");
        Objects.requireNonNull(deadlineDuration, "deadlineDuration 不能为空");
        if (deadlineDuration.isZero() || deadlineDuration.isNegative()) {
            throw new IllegalArgumentException("回合截止时间必须大于 0");
        }
        this.deadlineNanos = saturatingAdd(
                startedAtNanos, deadlineDuration.toNanos());
        this.budgetSession = Objects.requireNonNull(
                budgetSession, "回合体积预算会话不能为空");
    }

    static VueTurnContext testing(
            long appId, long userId, String turnId, VueBuildPhase phase) {
        return new VueTurnContext(appId, userId, turnId, null, null, null,
                Objects.requireNonNull(phase), false,
                System.nanoTime(), TURN_DEADLINE, System::nanoTime,
                new FileToolBudgetGuard().newSession());
    }

    static VueTurnContext testing(
            long appId, long userId, String turnId,
            VueBuildPhase phase, boolean timedOut) {
        return new VueTurnContext(appId, userId, turnId, null, null, null,
                Objects.requireNonNull(phase), timedOut,
                System.nanoTime(), TURN_DEADLINE, System::nanoTime,
                new FileToolBudgetGuard().newSession());
    }

    static VueTurnContext testing(
            long appId, long userId, String turnId, VueBuildPhase phase,
            Duration deadlineDuration, LongSupplier nanoTicker) {
        LongSupplier ticker = Objects.requireNonNull(nanoTicker);
        long startedAt = ticker.getAsLong();
        return new VueTurnContext(appId, userId, turnId, null, null, null,
                Objects.requireNonNull(phase), false,
                startedAt, deadlineDuration, ticker,
                new FileToolBudgetGuard().newSession());
    }

    static VueTurnContext testing(
            long appId, long userId, String turnId, VueBuildPhase phase,
            FileToolBudgetGuard.Session budgetSession) {
        return new VueTurnContext(appId, userId, turnId, null, null, null,
                Objects.requireNonNull(phase), false,
                System.nanoTime(), TURN_DEADLINE, System::nanoTime,
                budgetSession);
    }

    private static void validateIdentity(
            long appId, long userId, String turnId,
            AppOperationLease operationLease, VueBuildSnapshot snapshot) {
        if (operationLease.appId() != appId || snapshot.appId() != appId
                || snapshot.userId() != userId
                || !operationLease.ownerToken().equals(turnId)
                || !snapshot.turnId().equals(turnId)) {
            throw new IllegalArgumentException("回合上下文身份与精确租约不匹配");
        }
    }

    public long appId() {
        return appId;
    }

    public long userId() {
        return userId;
    }

    public String turnId() {
        return turnId;
    }

    public long startedAtNanos() {
        return startedAtNanos;
    }

    public long deadlineNanos() {
        return deadlineNanos;
    }

    public Duration remainingUntilDeadline() {
        return Duration.ofNanos(Math.max(
                0L, deadlineNanos - nanoTicker.getAsLong()));
    }

    public UserCommitResult commitUser(BooleanSupplier persistUser) {
        Objects.requireNonNull(persistUser, "用户消息持久化动作不能为空");
        commitGate.lock();
        try {
            if (userCommitState == UserCommitState.PRE_COMMIT_TERMINATED) {
                return UserCommitResult.TERMINATED_BEFORE_COMMIT;
            }
            if (userCommitState == UserCommitState.COMMITTED) {
                throw new IllegalStateException("用户消息已经提交");
            }
            if (!persistUser.getAsBoolean()) {
                return UserCommitResult.STORE_FAILED;
            }
            userCommitState = UserCommitState.COMMITTED;
            return UserCommitResult.COMMITTED;
        } finally {
            commitGate.unlock();
        }
    }

    /**
     * 在预提交状态门内原子领取准备步骤的两层回调票据，随后在锁外执行动作。
     *
     * <p>动作结果允许为 {@code null}；未获准通过 {@link CancellationException}
     * 单独表达，避免把“首轮无历史”误判为取消。</p>
     */
    public <T> T callPreparation(Supplier<T> action) {
        Objects.requireNonNull(action, "准备动作不能为空");
        PreparationPermit permit;
        commitGate.lock();
        try {
            TurnState current = turnState.get();
            if (current.stage() != TurnStage.ACTIVE
                    || userCommitState != UserCommitState.PREPARING) {
                throw new CancellationException("Vue 回合准备阶段已终止");
            }
            permit = tryAcquirePreparationPermit();
        } finally {
            commitGate.unlock();
        }
        if (permit == null) {
            throw new CancellationException("Vue 回合准备阶段未取得回调票据");
        }
        try (permit) {
            return action.get();
        }
    }

    private PreparationPermit tryAcquirePreparationPermit() {
        CallbackGate.Ticket inner = callbackGate.tryEnter();
        if (inner == null) {
            return null;
        }
        try {
            AutoCloseable outer = lease == null ? null : lease.enterCallback();
            return new PreparationPermit(inner, outer);
        } catch (IllegalStateException rejected) {
            inner.close();
            return null;
        }
    }

    public PreCommitTerminationDecision claimPreCommitTermination() {
        commitGate.lock();
        try {
            return switch (userCommitState) {
                case PREPARING -> {
                    userCommitState = UserCommitState.PRE_COMMIT_TERMINATED;
                    yield PreCommitTerminationDecision.PRE_COMMIT_WON;
                }
                case PRE_COMMIT_TERMINATED ->
                        PreCommitTerminationDecision.ALREADY_TERMINATED;
                case COMMITTED ->
                        PreCommitTerminationDecision.POST_COMMIT_REQUIRED;
            };
        } finally {
            commitGate.unlock();
        }
    }

    public UserCommitState userCommitState() {
        commitGate.lock();
        try {
            return userCommitState;
        } finally {
            commitGate.unlock();
        }
    }

    public boolean isUserCommitted() {
        return userCommitState() == UserCommitState.COMMITTED;
    }

    public VueBuildLease lease() {
        if (lease == null) {
            throw new IllegalStateException("测试上下文没有真实 Vue 租约");
        }
        return lease;
    }

    public VueBuildPhase phase() {
        return lease == null ? testingPhase : lease.snapshot().phase();
    }

    public boolean timedOut() {
        return lease == null ? testingTimedOut : lease.snapshot().timedOut();
    }

    public boolean recordControlledTermination(ControlledTermination termination) {
        return controlledTermination.compareAndSet(
                null, Objects.requireNonNull(termination, "受控终止不能为空"));
    }

    public FileToolBudgetGuard.Session budgetSession() {
        return budgetSession;
    }

    public Optional<ControlledTermination> controlledTermination() {
        return Optional.ofNullable(controlledTermination.get());
    }

    /** complete、error、cancel、timeout 与删除接管只允许一个分支决定规范终态。 */
    public boolean tryStartFinalization(TerminalTrigger trigger) {
        Objects.requireNonNull(trigger, "终态触发原因不能为空");
        while (true) {
            TurnState current = turnState.get();
            if (current.stage() != TurnStage.ACTIVE) {
                return false;
            }
            if (turnState.compareAndSet(current, TurnState.finalizing(trigger))) {
                return true;
            }
        }
    }

    /**
     * 原子认领取消类终态并关闭应用外层回调门，再撤销回合内层门和生成动作。
     */
    public boolean tryStartCancellation(TerminalTrigger trigger) {
        Objects.requireNonNull(trigger, "终态触发原因不能为空");
        if (trigger != TerminalTrigger.CANCELLED
                && trigger != TerminalTrigger.TIMED_OUT) {
            throw new IllegalArgumentException("只有取消或超时可以关闭回合取消门");
        }
        if (operationLease == null) {
            return completeCancellationClaim(tryStartFinalization(trigger));
        }
        CancellationClaimResult result = operationLease
                .requestCancellationDecisionIf(
                        () -> tryStartFinalization(trigger));
        if (result == CancellationClaimResult.LEASE_INACTIVE) {
            if (turnState.get().stage() != TurnStage.ACTIVE) {
                return false;
            }
            throw new IllegalStateException("应用操作租约已经失效");
        }
        return completeCancellationClaim(
                result == CancellationClaimResult.CLAIMED);
    }

    private boolean completeCancellationClaim(boolean claimed) {
        if (!claimed) {
            return false;
        }
        callbackGate.revoke();
        if (lease != null) {
            lease.cancel();
        }
        return true;
    }

    /** 删除 manager 已关闭 app 外层门，此处只认领回合终态并撤销内层门。 */
    public boolean tryStartDeleteTakeoverFinalization() {
        if (!tryStartFinalization(TerminalTrigger.DELETE_TAKEOVER)) {
            return false;
        }
        callbackGate.revoke();
        return true;
    }

    /** 在本轮 User 已提交后，为精确生成租约绑定唯一删除接管入口。 */
    public void registerDeleteTakeoverParticipant() {
        if (!isUserCommitted()) {
            throw new IllegalStateException("用户消息提交前不能注册删除接管参与者");
        }
        AppOperationLease currentLease = Objects.requireNonNull(
                operationLease, "测试上下文不能注册删除接管参与者");
        DeleteTakeoverRegistration registration = currentLease
                .registerDeleteTakeoverParticipant(this::participateInDeleteTakeover);
        if (!deleteTakeoverRegistration.compareAndSet(null, registration)) {
            registration.close();
            throw new IllegalStateException("Vue 回合已经注册删除接管参与者");
        }
    }

    private void participateInDeleteTakeover(DeleteTakeoverContext takeoverContext)
            throws Exception {
        DeleteTakeoverRequest request = new DeleteTakeoverRequest(takeoverContext);
        try (AutoCloseable ignored = onFinalized(
                request::complete, request::fail)) {
            if (!request.isDone()) {
                deleteTakeoverSignal.tryEmitValue(request);
            }
            request.awaitCompletion();
        }
    }

    Mono<DeleteTakeoverRequest> deleteTakeoverSignal() {
        return deleteTakeoverSignal.asMono();
    }

    public Optional<TerminalTrigger> terminalWinner() {
        TurnState current = turnState.get();
        return current.stage() == TurnStage.ACTIVE
                ? Optional.empty() : Optional.of(current.trigger());
    }

    public TurnState turnState() {
        return turnState.get();
    }

    boolean tryClaimFinalizationExecution(TerminalTrigger fallbackTrigger) {
        Objects.requireNonNull(fallbackTrigger, "兜底终态触发原因不能为空");
        TurnState current = turnState.get();
        if (current.stage() == TurnStage.ACTIVE
                && !tryStartFinalization(fallbackTrigger)) {
            current = turnState.get();
        }
        if (current.stage() == TurnStage.FINALIZED
                || turnState.get().stage() == TurnStage.FINALIZED) {
            return false;
        }
        synchronized (finalizationObserverLock) {
            if (finalizationExecutionClaimed || finalization.isDone()) {
                return false;
            }
            finalizationExecutionClaimed = true;
            return true;
        }
    }

    public void ensureTerminalOpen() {
        TurnState current = turnState.get();
        if (current.stage() != TurnStage.ACTIVE) {
            throw new IllegalStateException(
                    "Vue 回合终态已由 " + current.trigger() + " 占用");
        }
    }

    /**
     * 领取“回合内层 + app 外层”两张回调票据。取消关门后，晚到回调会被静默拒绝。
     */
    public boolean tryRunCallback(Runnable action) {
        Objects.requireNonNull(action, "回调不能为空");
        return tryCallCallback(() -> {
            action.run();
            return Boolean.TRUE;
        }).isPresent();
    }

    /** 在回调双门内执行准备或模型动作；关门后拒绝迟到动作。 */
    public <T> Optional<T> tryCallCallback(Supplier<T> action) {
        Objects.requireNonNull(action, "回调不能为空");
        CallbackGate.Ticket inner = callbackGate.tryEnter();
        if (inner == null) {
            return Optional.empty();
        }
        AutoCloseable outer = null;
        try (inner) {
            if (lease != null) {
                try {
                    outer = lease.enterCallback();
                } catch (IllegalStateException rejected) {
                    return Optional.empty();
                }
            }
            try {
                return Optional.ofNullable(action.get());
            } finally {
                if (outer != null) {
                    outer.close();
                }
            }
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("关闭 Vue 回合回调票据失败", exception);
        }
    }

    public void registerModelCancellation(Runnable cancellationAction) {
        lease().registerModelCancellation(cancellationAction);
    }

    /** 先关闭内层回调门，再触发 Vue/app 租约取消及模型、构建取消动作。 */
    public void cancelGeneration() {
        callbackGate.revoke();
        if (lease != null) {
            lease.cancel();
        }
    }

    public void revokeCallbacks() {
        callbackGate.revoke();
    }

    /** 依次等待回合内层与 app/Vue 外层回调静默，共享同一超时预算。 */
    public boolean awaitQuiescence(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout 不能为空");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 不能为负数");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        if (!callbackGate.awaitQuiescence(timeout)) {
            return false;
        }
        if (lease == null) {
            return true;
        }
        long remaining = Math.max(0L, deadline - System.nanoTime());
        try {
            return lease.awaitQuiescence(Duration.ofNanos(remaining));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    void completeFinalization(VueTurnFinalizer.FinalizationResult result) {
        Objects.requireNonNull(result, "回合终态结果不能为空");
        transitionToFinalized();
        completeSharedFinalization(result, null);
    }

    void failFinalization(Throwable cause) {
        Objects.requireNonNull(cause, "回合终态异常不能为空");
        transitionToFinalized();
        completeSharedFinalization(null, cause);
    }

    private void transitionToFinalized() {
        while (true) {
            TurnState current = turnState.get();
            if (current.stage() != TurnStage.FINALIZING) {
                throw new IllegalStateException("回合不处于可完成的收尾阶段");
            }
            if (turnState.compareAndSet(current, current.finalized())) {
                return;
            }
        }
    }

    private void completeSharedFinalization(
            VueTurnFinalizer.FinalizationResult result, Throwable cause) {
        Map<Long, FinalizationObserver> observers;
        synchronized (finalizationObserverLock) {
            boolean completed = cause == null
                    ? finalization.complete(result)
                    : finalization.completeExceptionally(cause);
            if (!completed) {
                throw new IllegalStateException("回合终态已经完成");
            }
            observers = new LinkedHashMap<>(finalizationObservers);
            finalizationObservers.clear();
        }
        observers.values().forEach(observer -> observer.notify(result, cause));
    }

    AutoCloseable onFinalized(
            Consumer<VueTurnFinalizer.FinalizationResult> success,
            Consumer<Throwable> failure) {
        FinalizationObserver observer = new FinalizationObserver(
                nextObserverId.incrementAndGet(), success, failure);
        boolean completed;
        synchronized (finalizationObserverLock) {
            completed = finalization.isDone();
            if (!completed) {
                finalizationObservers.put(observer.id(), observer);
            }
        }
        if (completed) {
            notifyCompletedObserver(observer);
        }
        return () -> removeObserver(observer);
    }

    private void notifyCompletedObserver(FinalizationObserver observer) {
        try {
            observer.notify(finalization.join(), null);
        } catch (CompletionException exception) {
            observer.notify(null, exception.getCause());
        }
    }

    private void removeObserver(FinalizationObserver observer) {
        observer.deactivate();
        synchronized (finalizationObserverLock) {
            finalizationObservers.remove(observer.id(), observer);
        }
    }

    VueTurnFinalizer.FinalizationResult awaitFinalization() {
        return finalization.join();
    }

    public void closeResources() {
        if (!resourcesClosed.compareAndSet(false, true)) {
            return;
        }
        DeleteTakeoverRegistration takeoverRegistration =
                deleteTakeoverRegistration.getAndSet(null);
        closeAll(takeoverRegistration, lease, operationLease, admissionPermit);
    }

    public static void closeAll(AutoCloseable... resources) {
        Throwable failure = null;
        for (AutoCloseable resource : resources) {
            if (resource == null) {
                continue;
            }
            try {
                resource.close();
            } catch (Throwable exception) {
                if (failure == null) {
                    failure = exception;
                } else if (failure != exception) {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new IllegalStateException("关闭回合资源失败", failure);
        }
    }

    private static long saturatingAdd(long value, long increment) {
        return increment > 0 && value > Long.MAX_VALUE - increment
                ? Long.MAX_VALUE : value + increment;
    }

    public enum UserCommitState {
        PREPARING,
        PRE_COMMIT_TERMINATED,
        COMMITTED
    }

    public enum UserCommitResult {
        COMMITTED,
        TERMINATED_BEFORE_COMMIT,
        STORE_FAILED
    }

    public enum PreCommitTerminationDecision {
        PRE_COMMIT_WON,
        ALREADY_TERMINATED,
        POST_COMMIT_REQUIRED
    }

    public enum TurnStage {
        ACTIVE,
        FINALIZING,
        FINALIZED
    }

    static final class DeleteTakeoverRequest {

        private final DeleteTakeoverContext takeoverContext;
        private final CompletableFuture<VueTurnFinalizer.FinalizationResult>
                completion = new CompletableFuture<>();

        private DeleteTakeoverRequest(DeleteTakeoverContext takeoverContext) {
            this.takeoverContext = Objects.requireNonNull(
                    takeoverContext, "删除接管上下文不能为空");
        }

        DeleteTakeoverContext takeoverContext() {
            return takeoverContext;
        }

        boolean isDone() {
            return completion.isDone();
        }

        void complete(VueTurnFinalizer.FinalizationResult result) {
            completion.complete(Objects.requireNonNull(
                    result, "删除接管终态结果不能为空"));
        }

        void fail(Throwable cause) {
            completion.completeExceptionally(Objects.requireNonNull(
                    cause, "删除接管终态异常不能为空"));
        }

        VueTurnFinalizer.FinalizationResult awaitCompletion()
                throws InterruptedException, ExecutionException, TimeoutException {
            Duration remaining = takeoverContext.remainingTime();
            if (remaining.isZero() || remaining.isNegative()) {
                throw new TimeoutException("删除接管等待回合终态超时");
            }
            try {
                return completion.get(
                        remaining.toNanos(), TimeUnit.NANOSECONDS);
            } catch (ExecutionException exception) {
                if (exception.getCause() instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (exception.getCause() instanceof Error error) {
                    throw error;
                }
                throw exception;
            }
        }
    }

    public record TurnState(TurnStage stage, TerminalTrigger trigger) {

        public TurnState {
            Objects.requireNonNull(stage, "回合阶段不能为空");
            if ((stage == TurnStage.ACTIVE) != (trigger == null)) {
                throw new IllegalArgumentException("活跃阶段不能携带终态触发原因");
            }
        }

        public static TurnState active() {
            return new TurnState(TurnStage.ACTIVE, null);
        }

        public static TurnState finalizing(TerminalTrigger trigger) {
            return new TurnState(TurnStage.FINALIZING,
                    Objects.requireNonNull(trigger, "终态触发原因不能为空"));
        }

        public TurnState finalized() {
            if (stage != TurnStage.FINALIZING) {
                throw new IllegalStateException("只有收尾中状态可以完成");
            }
            return new TurnState(TurnStage.FINALIZED, trigger);
        }
    }

    private final class FinalizationObserver {

        private final long id;
        private final Consumer<VueTurnFinalizer.FinalizationResult> success;
        private final Consumer<Throwable> failure;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private FinalizationObserver(
                long id,
                Consumer<VueTurnFinalizer.FinalizationResult> success,
                Consumer<Throwable> failure) {
            this.id = id;
            this.success = Objects.requireNonNull(success, "成功观察回调不能为空");
            this.failure = Objects.requireNonNull(failure, "失败观察回调不能为空");
        }

        private long id() {
            return id;
        }

        private void deactivate() {
            active.set(false);
        }

        @SuppressWarnings("removal")
        private void notify(
                VueTurnFinalizer.FinalizationResult result, Throwable cause) {
            if (!active.compareAndSet(true, false)) {
                return;
            }
            try {
                if (cause == null) {
                    success.accept(result);
                } else {
                    failure.accept(cause);
                }
            } catch (VirtualMachineError | ThreadDeath fatal) {
                throw fatal;
            } catch (Throwable exception) {
                log.warn("Vue 回合终态观察回调异常,appId={},turnId={}",
                        appId, turnId, exception);
            }
        }
    }

    private static final class PreparationPermit implements AutoCloseable {

        private final CallbackGate.Ticket inner;
        private final AutoCloseable outer;
        private final AtomicBoolean closed = new AtomicBoolean();

        private PreparationPermit(
                CallbackGate.Ticket inner, AutoCloseable outer) {
            this.inner = Objects.requireNonNull(inner, "内层回调票据不能为空");
            this.outer = outer;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            RuntimeException failure = null;
            if (outer != null) {
                try {
                    outer.close();
                } catch (RuntimeException exception) {
                    failure = exception;
                } catch (Exception exception) {
                    failure = new IllegalStateException(
                            "关闭应用回调票据失败", exception);
                }
            }
            try {
                inner.close();
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else if (failure != exception) {
                    failure.addSuppressed(exception);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static final class CallbackGate {

        private boolean active = true;
        private int inFlight;

        private synchronized Ticket tryEnter() {
            if (!active) {
                return null;
            }
            inFlight++;
            return new Ticket(this);
        }

        private synchronized void revoke() {
            active = false;
        }

        private synchronized void leave() {
            inFlight--;
            if (inFlight == 0) {
                notifyAll();
            }
        }

        private synchronized boolean awaitQuiescence(Duration timeout) {
            long remaining = timeout.toNanos();
            long deadline = System.nanoTime() + remaining;
            while (inFlight > 0) {
                if (remaining <= 0) {
                    return false;
                }
                try {
                    wait(Math.max(1L, remaining / 1_000_000L));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                remaining = deadline - System.nanoTime();
            }
            return true;
        }

        private static final class Ticket implements AutoCloseable {

            private final CallbackGate gate;
            private boolean closed;

            private Ticket(CallbackGate gate) {
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
    }

    public enum TerminalTrigger {
        COMPLETED,
        FAILED,
        CANCELLED,
        TIMED_OUT,
        DELETE_TAKEOVER
    }
}
