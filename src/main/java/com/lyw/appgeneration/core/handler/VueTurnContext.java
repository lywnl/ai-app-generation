package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.ai.memory.ContextContinuationGate;
import com.lyw.appgeneration.ai.model.message.ContextCompressionMessage;
import com.lyw.appgeneration.ai.model.message.ToolProtocolRecoveryMessage;
import com.lyw.appgeneration.ai.model.message.IncompleteToolChainRecoveryMessage;
import com.lyw.appgeneration.ai.tools.FileToolBudgetGuard;
import com.lyw.appgeneration.core.builder.VueBuildPhase;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager.VueBuildLease;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager.VueBuildSnapshot;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager.AppOperationLease;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager.CancellationClaimResult;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager.DeleteTakeoverContext;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager.DeleteTakeoverCallbackRegistration;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** 精确绑定一次 app 操作租约和 Vue 构建租约的在线回合上下文。 */
@Slf4j
public final class VueTurnContext implements ContextContinuationGate {

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
    private final AtomicReference<OutputSafetySeal> outputSafetySeal =
            new AtomicReference<>(OutputSafetySeal.unsealed());
    private final AtomicReference<OutputSafetyRegistration>
            outputSafetyRegistration = new AtomicReference<>(
            OutputSafetyRegistration.open());
    private final Object outputSafetyLock = new Object();
    private final AtomicReference<VueTurnMode> turnMode =
            new AtomicReference<>();
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
    private final TurnProgressChannel progressChannel =
            new TurnProgressChannel();
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
        initializeMode(VueTurnMode.READ_ONLY);
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
        initializeMode(VueTurnMode.READ_ONLY);
    }

    /**
     * 生产回合专用构造器。延迟初始化模式，确保用户消息提交前已经完成分类。
     */
    public VueTurnContext(long appId, long userId, String turnId,
            AppOperationLease operationLease, VueBuildLease lease,
            AdmissionPermit admissionPermit,
            FileToolBudgetGuard.Session budgetSession,
            boolean deferModeInitialization) {
        this(appId, userId, turnId, operationLease, lease,
                Objects.requireNonNull(admissionPermit,
                        "Vue 回合准入许可不能为空"),
                null, false, operationLease.startedAtNanos(), TURN_DEADLINE,
                System::nanoTime, budgetSession);
        VueBuildSnapshot snapshot = lease.snapshot();
        validateIdentity(appId, userId, turnId, operationLease, snapshot);
        if (!deferModeInitialization) {
            initializeMode(VueTurnMode.READ_ONLY);
        }
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
        return testing(appId, userId, turnId, phase,
                VueTurnMode.READ_ONLY);
    }

    static VueTurnContext testing(
            long appId, long userId, String turnId,
            VueBuildPhase phase, boolean timedOut) {
        return testing(appId, userId, turnId, phase, timedOut,
                VueTurnMode.READ_ONLY);
    }

    static VueTurnContext testing(
            long appId, long userId, String turnId, VueBuildPhase phase,
            Duration deadlineDuration, LongSupplier nanoTicker) {
        return testing(appId, userId, turnId, phase, deadlineDuration,
                nanoTicker, VueTurnMode.READ_ONLY);
    }

    static VueTurnContext testing(
            long appId, long userId, String turnId, VueBuildPhase phase,
            VueTurnMode mode) {
        LongSupplier ticker = System::nanoTime;
        VueTurnContext context = new VueTurnContext(appId, userId, turnId,
                null, null, null, Objects.requireNonNull(phase), false,
                ticker.getAsLong(), TURN_DEADLINE, ticker,
                new FileToolBudgetGuard().newSession());
        context.initializeMode(mode);
        return context;
    }

    static VueTurnContext testing(
            long appId, long userId, String turnId, VueBuildPhase phase,
            boolean timedOut, VueTurnMode mode) {
        LongSupplier ticker = System::nanoTime;
        VueTurnContext context = new VueTurnContext(appId, userId, turnId,
                null, null, null, Objects.requireNonNull(phase), timedOut,
                ticker.getAsLong(), TURN_DEADLINE, ticker,
                new FileToolBudgetGuard().newSession());
        context.initializeMode(mode);
        return context;
    }

    static VueTurnContext testing(
            long appId, long userId, String turnId, VueBuildPhase phase,
            Duration deadlineDuration, LongSupplier nanoTicker,
            VueTurnMode mode) {
        LongSupplier ticker = Objects.requireNonNull(nanoTicker);
        long startedAt = ticker.getAsLong();
        VueTurnContext context = new VueTurnContext(appId, userId, turnId,
                null, null, null,
                Objects.requireNonNull(phase), false,
                startedAt, deadlineDuration, ticker,
                new FileToolBudgetGuard().newSession());
        context.initializeMode(mode);
        return context;
    }

    static VueTurnContext testing(
            long appId, long userId, String turnId, VueBuildPhase phase,
            FileToolBudgetGuard.Session budgetSession) {
        VueTurnContext context = new VueTurnContext(appId, userId, turnId,
                null, null, null,
                Objects.requireNonNull(phase), false,
                System.nanoTime(), TURN_DEADLINE, System::nanoTime,
                budgetSession);
        context.initializeMode(VueTurnMode.READ_ONLY);
        return context;
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

    /** 在用户消息提交前设置一次本回合执行契约。 */
    public void initializeMode(VueTurnMode mode) {
        VueTurnMode checkedMode = Objects.requireNonNull(mode,
                "Vue 回合模式不能为空");
        if (!this.turnMode.compareAndSet(null, checkedMode)) {
            throw new IllegalStateException("Vue 回合模式已经初始化");
        }
    }

    /** 返回已经初始化的回合模式。 */
    public VueTurnMode turnMode() {
        VueTurnMode mode = turnMode.get();
        if (mode == null) {
            throw new IllegalStateException("Vue 回合模式尚未初始化");
        }
        return mode;
    }

    /** 只读回合在真实写工具成功落盘后自动升级为构建义务。 */
    public boolean requiresBuild() {
        VueTurnMode mode = turnMode();
        long mutationRevision = mutationRevision();
        return mode == VueTurnMode.MUTATION_REQUIRED || mutationRevision > 0L;
    }

    /** ANSWERED 只能用于尚未发生真实变更的只读回合。 */
    public boolean isReadOnlyAnswerEligible() {
        return turnMode() == VueTurnMode.READ_ONLY && mutationRevision() == 0L;
    }

    private long mutationRevision() {
        return lease == null ? 0L : lease.snapshot().mutationRevision();
    }

    /** 只有工程变更回合的首次模型请求需要强制结构化工具调用。 */
    public boolean requiresInitialToolCall() {
        return turnMode() == VueTurnMode.MUTATION_REQUIRED;
    }

    public UserCommitResult commitUser(BooleanSupplier persistUser) {
        Objects.requireNonNull(persistUser, "用户消息持久化动作不能为空");
        turnMode();
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

    /** 注册延迟读取最新转录与工程事实的唯一输出安全封口器。 */
    public void registerOutputSafetySealer(
            Supplier<OutputSafetySeal> sealer) {
        OutputSafetyRegistration registered =
                OutputSafetyRegistration.registered(
                        Objects.requireNonNull(sealer,
                                "输出安全封口器不能为空"));
        synchronized (outputSafetyLock) {
            if (outputSafetyRegistration.get().state()
                    != OutputSafetyRegistrationState.OPEN) {
                throw new IllegalStateException("输出安全封口器注册窗口已经关闭");
            }
            outputSafetyRegistration.set(registered);
        }
    }

    /** 执行已注册封口器；未注册或封口失败时保持 UNSEALED 安全失败关闭。 */
    public OutputSafetySeal sealRegisteredOutputSafety() {
        while (true) {
            OutputSafetyRegistration sealing = null;
            CompletableFuture<OutputSafetySeal> pending = null;
            synchronized (outputSafetyLock) {
                OutputSafetySeal sealed = outputSafetySeal.get();
                if (sealed.state() != OutputSafetySeal.SealState.UNSEALED) {
                    return sealed;
                }
                OutputSafetyRegistration current =
                        outputSafetyRegistration.get();
                switch (current.state()) {
                    case OPEN -> {
                        outputSafetyRegistration.set(
                                OutputSafetyRegistration.closedUnsealed());
                        return outputSafetySeal.get();
                    }
                    case REGISTERED -> {
                        sealing = current.sealing();
                        outputSafetyRegistration.set(sealing);
                    }
                    case SEALING -> pending = current.completion();
                    case CLOSED_BEFORE_HANDLER, CLOSED_UNSEALED,
                         CLOSED_SEALED -> {
                        return outputSafetySeal.get();
                    }
                }
            }
            if (sealing != null) {
                return executeOutputSafetySealer(sealing);
            }
            return pending.join();
        }
    }

    /** 仅 Handler 从未注册时原子封为 SAFE，不能覆盖已注册封口器。 */
    public OutputSafetySeal sealSafeBeforeHandler() {
        synchronized (outputSafetyLock) {
            OutputSafetySeal sealed = outputSafetySeal.get();
            if (sealed.state() != OutputSafetySeal.SealState.UNSEALED) {
                return sealed;
            }
            OutputSafetyRegistration current = outputSafetyRegistration.get();
            if (current.state() != OutputSafetyRegistrationState.OPEN) {
                return sealed;
            }
            outputSafetySeal.set(OutputSafetySeal.safe());
            outputSafetyRegistration.set(
                    OutputSafetyRegistration.closedBeforeHandler());
            return outputSafetySeal.get();
        }
    }

    public OutputSafetySeal outputSafetySeal() {
        return outputSafetySeal.get();
    }

    private OutputSafetySeal executeOutputSafetySealer(
            OutputSafetyRegistration sealing) {
        OutputSafetySeal result = OutputSafetySeal.unsealed();
        try {
            OutputSafetySeal candidate = Objects.requireNonNull(
                    sealing.sealer().get(),
                    "输出安全封口器不能返回空值");
            if (candidate.state() == OutputSafetySeal.SealState.SAFE
                    || candidate.state()
                    == OutputSafetySeal.SealState.RESERVED) {
                result = candidate;
            }
        } catch (RuntimeException exception) {
            log.warn("输出安全封口失败，保持 UNSEALED 失败关闭 turnId={} type={}",
                    turnId, exception.getClass().getSimpleName());
        } finally {
            synchronized (outputSafetyLock) {
                if (result.state() != OutputSafetySeal.SealState.UNSEALED) {
                    outputSafetySeal.set(result);
                }
                outputSafetyRegistration.set(
                        result.state() == OutputSafetySeal.SealState.UNSEALED
                                ? OutputSafetyRegistration.closedUnsealed()
                                : OutputSafetyRegistration.closedSealed());
            }
            sealing.completion().complete(result);
        }
        return result;
    }

    /** complete、error、cancel、timeout 与删除接管只允许一个分支决定规范终态。 */
    public boolean tryStartFinalization(TerminalTrigger trigger) {
        Objects.requireNonNull(trigger, "终态触发原因不能为空");
        while (true) {
            TurnState current = turnState.get();
            if (current.stage() != TurnStage.ACTIVE) {
                return false;
            }
            progressChannel.close();
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
        storeDeleteTakeoverRegistration(registration);
    }

    private void storeDeleteTakeoverRegistration(
            DeleteTakeoverRegistration registration) {
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

    @Override
    public boolean tryRun(Runnable action) {
        return tryRunCallback(action);
    }

    @Override
    public void publishContextCompression(
            ContextCompressionMessage message) {
        progressChannel.publish(message);
    }

    public void publishToolProtocolRecovery(
            ToolProtocolRecoveryMessage message) {
        progressChannel.publish(message);
    }

    public void publishIncompleteToolChainRecovery(
            IncompleteToolChainRecoveryMessage message) {
        progressChannel.publish(message);
    }

    public Flux<GenerationStreamEvent> mergeProgress(
            Flux<GenerationStreamEvent> business) {
        return progressChannel.mergeWith(business);
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

    /** 原子登记删除参与者，并仅用临时 callback 票据包围同步 Handler 装配。 */
    public <T> Optional<T> tryCallHandlerSetup(Supplier<T> action) {
        Objects.requireNonNull(action, "Handler 装配动作不能为空");
        if (!isUserCommitted()) {
            throw new IllegalStateException("用户消息提交前不能装配 Handler");
        }
        CallbackGate.Ticket inner = callbackGate.tryEnter();
        if (inner == null) {
            return Optional.empty();
        }
        try (inner) {
            DeleteTakeoverCallbackRegistration registration;
            try {
                registration = lease().enterHandlerCallback(
                        this::participateInDeleteTakeover);
            } catch (IllegalStateException rejected) {
                return Optional.empty();
            }
            try (registration) {
                storeDeleteTakeoverRegistration(
                        registration.transferDeleteTakeoverRegistration());
                return Optional.ofNullable(action.get());
            }
        }
    }

    public void registerModelCancellation(Runnable cancellationAction) {
        lease().registerModelCancellation(cancellationAction);
    }

    /** 先关闭内层回调门，再触发 Vue/app 租约取消及模型、构建取消动作。 */
    public void cancelGeneration() {
        callbackGate.revoke();
        progressChannel.close();
        if (lease != null) {
            lease.cancel();
        }
    }

    public void revokeCallbacks() {
        callbackGate.revoke();
        progressChannel.close();
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

    /** 暴露唯一稳定终态，供主动取消接口继续通过原 SSE 返回终态。 */
    public Mono<VueTurnFinalizer.FinalizationResult> finalizationSignal() {
        return Mono.fromFuture(finalization, true);
    }

    public void closeResources() {
        if (!resourcesClosed.compareAndSet(false, true)) {
            return;
        }
        progressChannel.close();
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

    /** 不可变输出安全判定；只有 RESERVED 携带可信工程事实投影。 */
    public static final class OutputSafetySeal {

        private static final OutputSafetySeal UNSEALED =
                new OutputSafetySeal(SealState.UNSEALED, null);
        private static final OutputSafetySeal SAFE =
                new OutputSafetySeal(SealState.SAFE, null);
        private final SealState state;
        private final String memoryProjection;

        private OutputSafetySeal(
                SealState state, String memoryProjection) {
            this.state = Objects.requireNonNull(
                    state, "输出安全状态不能为空");
            if (state == SealState.RESERVED
                    && (memoryProjection == null
                    || memoryProjection.isBlank())) {
                throw new IllegalArgumentException(
                        "RESERVED 必须携带可信记忆投影");
            }
            if (state != SealState.RESERVED && memoryProjection != null) {
                throw new IllegalArgumentException(
                        "只有 RESERVED 可以携带记忆投影");
            }
            this.memoryProjection = memoryProjection;
        }

        public static OutputSafetySeal unsealed() {
            return UNSEALED;
        }

        public static OutputSafetySeal safe() {
            return SAFE;
        }

        public static OutputSafetySeal reserved(String memoryProjection) {
            return new OutputSafetySeal(SealState.RESERVED, memoryProjection);
        }

        public SealState state() {
            return state;
        }

        public String memoryProjection() {
            return memoryProjection;
        }

        public enum SealState {
            UNSEALED,
            SAFE,
            RESERVED
        }
    }

    private enum OutputSafetyRegistrationState {
        OPEN,
        REGISTERED,
        SEALING,
        CLOSED_BEFORE_HANDLER,
        CLOSED_UNSEALED,
        CLOSED_SEALED
    }

    private record OutputSafetyRegistration(
            OutputSafetyRegistrationState state,
            Supplier<OutputSafetySeal> sealer,
            CompletableFuture<OutputSafetySeal> completion) {

        private OutputSafetyRegistration {
            Objects.requireNonNull(state, "输出安全注册状态不能为空");
        }

        private static OutputSafetyRegistration open() {
            return new OutputSafetyRegistration(
                    OutputSafetyRegistrationState.OPEN, null, null);
        }

        private static OutputSafetyRegistration registered(
                Supplier<OutputSafetySeal> sealer) {
            return new OutputSafetyRegistration(
                    OutputSafetyRegistrationState.REGISTERED,
                    sealer, new CompletableFuture<>());
        }

        private OutputSafetyRegistration sealing() {
            return new OutputSafetyRegistration(
                    OutputSafetyRegistrationState.SEALING,
                    sealer, completion);
        }

        private static OutputSafetyRegistration closedBeforeHandler() {
            return new OutputSafetyRegistration(
                    OutputSafetyRegistrationState.CLOSED_BEFORE_HANDLER,
                    null, null);
        }

        private static OutputSafetyRegistration closedUnsealed() {
            return new OutputSafetyRegistration(
                    OutputSafetyRegistrationState.CLOSED_UNSEALED,
                    null, null);
        }

        private static OutputSafetyRegistration closedSealed() {
            return new OutputSafetyRegistration(
                    OutputSafetyRegistrationState.CLOSED_SEALED,
                    null, null);
        }

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
