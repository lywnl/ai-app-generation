package com.lyw.appgeneration.core.concurrency;

import com.lyw.appgeneration.monitor.AppLifecycleMetricsCollector;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * 统一管理同一应用上的生成、部署、下载和删除互斥关系。
 */
@Component
public final class AppOperationLeaseManager {

    private static final Duration MAX_DELETE_QUIESCENCE_WAIT = Duration.ofSeconds(10);

    private final ConcurrentHashMap<Long, OperationState> operations =
            new ConcurrentHashMap<>();
    private final Runnable registrationTestHook;
    private final CancellationDispatchStarter cancellationDispatchStarter;

    @Resource
    private AppLifecycleMetricsCollector appLifecycleMetricsCollector;

    public AppOperationLeaseManager() {
        this(null, task -> Thread.ofVirtual()
                .name("app-delete-cancellation-", 0).start(task));
    }

    AppOperationLeaseManager(Runnable registrationTestHook) {
        this(registrationTestHook, task -> Thread.ofVirtual()
                .name("app-delete-cancellation-", 0).start(task));
    }

    AppOperationLeaseManager(
            Runnable registrationTestHook,
            CancellationDispatchStarter cancellationDispatchStarter) {
        this.registrationTestHook = registrationTestHook;
        this.cancellationDispatchStarter =
                Objects.requireNonNull(cancellationDispatchStarter);
    }

    public enum AppOperationType {
        GENERATE, DEPLOY, DOWNLOAD, DELETE
    }

    @FunctionalInterface
    interface CancellationDispatchStarter {
        void start(Runnable task);
    }

    /** 原子领取指定应用的操作租约。 */
    public AppOperationLease acquire(
            long appId, AppOperationType operationType, String ownerToken) {
        validateIdentity(appId, operationType, ownerToken);
        OperationState state = new OperationState(
                this, appId, operationType, ownerToken);
        OperationState active = operations.putIfAbsent(appId, state);
        if (active != null) {
            throw new ActiveAppOperationException(
                    appId, active.operationType, active.ownerToken);
        }
        return new AppOperationLease(state);
    }

    /**
     * 关闭活跃生成的取消门，等待已进入的回调退出后原子换成删除租约。
     */
    public AppOperationLease cancelAndAcquireDelete(
            long appId, String ownerToken, Duration quiescenceTimeout) {
        validateIdentity(appId, AppOperationType.DELETE, ownerToken);
        Duration boundedTimeout = boundedTimeout(quiescenceTimeout);
        long deadlineNanos = deadlineAfter(boundedTimeout);
        AtomicReference<OperationState> sourceReference = new AtomicReference<>();
        AtomicReference<OperationState> deleteReference = new AtomicReference<>();
        AtomicReference<DeleteTakeoverEntry> participantReference =
                new AtomicReference<>();
        operations.compute(appId, (ignored, active) -> {
            if (active == null) {
                OperationState delete = new OperationState(
                        this, appId, AppOperationType.DELETE, ownerToken);
                deleteReference.set(delete);
                return delete;
            }
            if (active.operationType != AppOperationType.GENERATE) {
                throw new ActiveAppOperationException(
                        appId, active.operationType, active.ownerToken);
            }
            participantReference.set(active.beginDeleteTakeover());
            sourceReference.set(active);
            return active;
        });
        OperationState existingDelete = deleteReference.get();
        if (existingDelete != null) {
            return new AppOperationLease(existingDelete);
        }

        OperationState source = sourceReference.get();
        CancellationDispatch cancellationDispatch =
                source.prepareCancellationDispatchForStart();
        if (cancellationDispatch != null) {
            try {
                cancellationDispatchStarter.start(
                        () -> source.executeCancellationDispatch(cancellationDispatch, false));
            } catch (Throwable startFailure) {
                completeFailedDispatchStart(source, cancellationDispatch, startFailure);
            }
            source.completeCancellationDispatchStart();
        }
        try {
            runDeleteTakeoverParticipant(
                    source, participantReference.get(), deadlineNanos);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            abortDeleteTakeover(source);
            recordDeleteTakeoverCancellation(
                    AppLifecycleMetricsCollector.OperationCancellationResult.FAILED);
            throw new OperationQuiescenceTimeoutException(
                    appId, "等待删除接管参与者时线程被中断", exception);
        } catch (TimeoutException exception) {
            abortDeleteTakeover(source);
            recordDeleteTakeoverCancellation(
                    AppLifecycleMetricsCollector.OperationCancellationResult.TIMED_OUT);
            throw new OperationQuiescenceTimeoutException(
                    appId, "等待删除接管参与者超时", exception);
        } catch (ExecutionException exception) {
            abortDeleteTakeover(source);
            recordDeleteTakeoverCancellation(
                    AppLifecycleMetricsCollector.OperationCancellationResult.FAILED);
            throwUnchecked(exception.getCause());
        }
        boolean readyForReplacement;
        try {
            readyForReplacement = source.awaitQuiescenceAndSeal(deadlineNanos);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            abortDeleteTakeover(source);
            recordDeleteTakeoverCancellation(
                    AppLifecycleMetricsCollector.OperationCancellationResult.FAILED);
            throw new OperationQuiescenceTimeoutException(
                    appId, "等待静默时线程被中断", exception);
        }
        if (!readyForReplacement) {
            abortDeleteTakeover(source);
            recordDeleteTakeoverCancellation(
                    AppLifecycleMetricsCollector.OperationCancellationResult.TIMED_OUT);
            throw new OperationQuiescenceTimeoutException(
                    appId, "等待生成回调静默超时", null);
        }
        Throwable cancellationFailure = source.consumeCancellationFailure();
        if (cancellationFailure != null) {
            abortDeleteTakeover(source);
            recordDeleteTakeoverCancellation(
                    AppLifecycleMetricsCollector.OperationCancellationResult.FAILED);
            throwUnchecked(cancellationFailure);
        }
        OperationState delete = new OperationState(
                this, appId, AppOperationType.DELETE, ownerToken);
        if (!operations.replace(appId, source, delete)) {
            source.abortDeleteTakeover();
            throw new IllegalStateException("生成租约在删除接管期间发生了非法替换");
        }
        source.completeDeleteTakeover();
        recordDeleteTakeoverCancellation(
                AppLifecycleMetricsCollector.OperationCancellationResult.COMPLETED);
        return new AppOperationLease(delete);
    }

    private void runDeleteTakeoverParticipant(
            OperationState source,
            DeleteTakeoverEntry entry,
            long deadlineNanos)
            throws InterruptedException, TimeoutException, ExecutionException {
        if (!entry.tryStart()) {
            return;
        }
        DeleteTakeoverContext context = new DeleteTakeoverContext(
                source, deadlineNanos);
        AtomicBoolean invocationClaimed = new AtomicBoolean();
        FutureTask<Void> task = new FutureTask<>(() -> {
            if (!invocationClaimed.compareAndSet(false, true)) {
                return null;
            }
            boolean completed = false;
            try {
                entry.participant().participate(context);
                completed = true;
                return null;
            } finally {
                entry.finish(completed);
            }
        });
        Thread participantThread = Thread.ofVirtual()
                .name("app-delete-takeover-" + source.appId + "-" + entry.id())
                .start(task);
        long remainingNanos = OperationState.remainingNanos(deadlineNanos);
        if (remainingNanos <= 0) {
            task.cancel(true);
            releaseParticipantInvocationIfNotStarted(entry, invocationClaimed);
            throw new TimeoutException("删除接管参与者没有剩余执行时间");
        }
        try {
            task.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException | InterruptedException exception) {
            task.cancel(true);
            releaseParticipantInvocationIfNotStarted(entry, invocationClaimed);
            throw exception;
        } finally {
            if (task.isCancelled()) {
                participantThread.interrupt();
            }
        }
    }

    private void releaseParticipantInvocationIfNotStarted(
            DeleteTakeoverEntry entry, AtomicBoolean invocationClaimed) {
        if (invocationClaimed.compareAndSet(false, true)) {
            entry.finish(false);
        }
    }

    private void completeFailedDispatchStart(
            OperationState source,
            CancellationDispatch cancellationDispatch,
            Throwable startFailure) {
        try {
            if (source.rollbackFailedDispatchStart(cancellationDispatch)) {
                operations.remove(source.appId, source);
            }
        } catch (Throwable cleanupFailure) {
            if (cleanupFailure != startFailure) {
                startFailure.addSuppressed(cleanupFailure);
            }
        }
        try {
            abortDeleteTakeover(source);
        } catch (Throwable cleanupFailure) {
            if (cleanupFailure != startFailure) {
                startFailure.addSuppressed(cleanupFailure);
            }
        }
        recordDeleteTakeoverCancellation(
                AppLifecycleMetricsCollector.OperationCancellationResult.FAILED);
        throwUnchecked(startFailure);
    }

    private void recordDeleteTakeoverCancellation(
            AppLifecycleMetricsCollector.OperationCancellationResult result) {
        if (appLifecycleMetricsCollector != null) {
            appLifecycleMetricsCollector.recordOperationCancellation(
                    AppLifecycleMetricsCollector.OperationCancellationTrigger.DELETE_TAKEOVER,
                    result);
        }
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("取消分发出现受检异常", failure);
    }

    private void abortDeleteTakeover(OperationState source) {
        boolean removable = source.abortDeleteTakeover();
        if (removable) {
            operations.remove(source.appId, source);
        }
    }

    private void ownerClosed(OperationState state) {
        boolean removable = state.closeOwner();
        Throwable failure = null;
        try {
            CancellationDispatch dispatch;
            while ((dispatch =
                    state.awaitDispatchDecisionAndPrepareNextForClosedOwner()) != null) {
                state.executeCancellationDispatch(dispatch, false);
                failure = appendFailure(failure, dispatch.failure.get());
            }
        } finally {
            if (removable) {
                operations.remove(state.appId, state);
            }
        }
        if (failure != null) {
            throwUnchecked(failure);
        }
    }

    private static Throwable appendFailure(Throwable failure, Throwable nextFailure) {
        if (failure == null) {
            return nextFailure;
        }
        if (nextFailure != null && failure != nextFailure) {
            failure.addSuppressed(nextFailure);
        }
        return failure;
    }

    private void callbackClosed(OperationState state) {
        if (state.exitCallback()) {
            operations.remove(state.appId, state);
        }
    }

    private void cancellationActivityFinished(OperationState state) {
        if (state.isRemovable()) {
            operations.remove(state.appId, state);
        }
    }

    private static Duration boundedTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "quiescenceTimeout 不能为空");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("quiescenceTimeout 不能为负数");
        }
        return timeout.compareTo(MAX_DELETE_QUIESCENCE_WAIT) > 0
                ? MAX_DELETE_QUIESCENCE_WAIT : timeout;
    }

    private static long deadlineAfter(Duration timeout) {
        long now = System.nanoTime();
        long timeoutNanos = timeout.toNanos();
        return timeoutNanos >= Long.MAX_VALUE - now
                ? Long.MAX_VALUE : now + timeoutNanos;
    }

    private static void validateIdentity(
            long appId, AppOperationType operationType, String ownerToken) {
        if (appId <= 0) {
            throw new IllegalArgumentException("appId 必须大于 0");
        }
        Objects.requireNonNull(operationType, "operationType 不能为空");
        Objects.requireNonNull(ownerToken, "ownerToken 不能为空");
        if (ownerToken.isBlank()) {
            throw new IllegalArgumentException("ownerToken 不能为空白");
        }
    }

    /** 持有一次精确操作权限的不可伪造租约。 */
    public static final class AppOperationLease implements AutoCloseable {

        private final OperationState state;
        private final AtomicBoolean closed = new AtomicBoolean();

        private AppOperationLease(OperationState state) {
            this.state = state;
        }

        public long appId() {
            return state.appId;
        }

        public AppOperationType operationType() {
            return state.operationType;
        }

        public String ownerToken() {
            return state.ownerToken;
        }

        /** 领取租约时记录的单调时钟时间，用于回合绝对截止时间。 */
        public long startedAtNanos() {
            return state.startedAtNanos;
        }

        public boolean isActive() {
            return !closed.get() && state.isActiveInstance();
        }

        /** 当前精确操作租约的取消门是否已经关闭。 */
        public boolean isCancellationRequested() {
            return state.isCancellationRequested();
        }

        public void claimVueSession() {
            ensureActive();
            state.claimVueSession();
        }

        public CancellationRegistration registerCancellation(Runnable action) {
            ensureActiveOrCancellationRequested();
            Runnable hook = state.manager.registrationTestHook;
            if (hook != null) {
                hook.run();
            }
            return state.registerCancellation(action);
        }

        /** 为当前精确生成租约注册唯一的回合删除接管参与者。 */
        public DeleteTakeoverRegistration registerDeleteTakeoverParticipant(
                DeleteTakeoverParticipant participant) {
            ensureActive();
            if (state.operationType != AppOperationType.GENERATE) {
                throw new IllegalStateException("只有生成租约可以注册删除接管参与者");
            }
            return state.registerDeleteTakeoverParticipant(participant);
        }

        public CallbackRegistration enterCallback() {
            ensureActive();
            return state.enterCallback();
        }

        public boolean requestCancellation() {
            ensureActiveOrCancellationRequested();
            boolean changed = state.requestCancellation();
            state.fireCancellationActions();
            return changed;
        }

        /**
         * 在应用取消门的同一监视器内执行终态认领并关门。
         * 认领失败时不改变租约；认领成功后，晚到回调不可能再取得票据。
         */
        public boolean requestCancellationIf(BooleanSupplier claimAction) {
            ensureActiveOrCancellationRequested();
            boolean changed = state.requestCancellationIf(claimAction);
            if (changed) {
                state.fireCancellationActions();
            }
            return changed;
        }

        public boolean awaitQuiescence(Duration timeout) throws InterruptedException {
            Objects.requireNonNull(timeout, "timeout 不能为空");
            if (timeout.isNegative()) {
                throw new IllegalArgumentException("timeout 不能为负数");
            }
            return state.awaitQuiescence(timeout);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                state.manager.ownerClosed(state);
            }
        }

        private void ensureActive() {
            if (closed.get() || !state.isActiveInstance()) {
                throw new IllegalStateException("应用操作租约已经失效");
            }
        }

        private void ensureActiveOrCancellationRequested() {
            if (closed.get() || !state.isOwnedInstance()) {
                throw new IllegalStateException("应用操作租约已经失效");
            }
        }
    }

    /** 可注销的单次取消动作。 */
    public static final class CancellationRegistration implements AutoCloseable {

        private final OperationState state;
        private final CancellationEntry entry;

        private CancellationRegistration(OperationState state, CancellationEntry entry) {
            this.state = state;
            this.entry = entry;
        }

        @Override
        public void close() {
            state.cancelCancellation(entry);
        }
    }

    /** 已进入回调的引用计数凭证。 */
    public static final class CallbackRegistration implements AutoCloseable {

        private final OperationState state;
        private final AtomicBoolean closed = new AtomicBoolean();

        private CallbackRegistration(OperationState state) {
            this.state = state;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                state.manager.callbackClosed(state);
            }
        }
    }

    /** 活跃生成回合在删除替换前必须完成的接管动作。 */
    @FunctionalInterface
    public interface DeleteTakeoverParticipant {

        void participate(DeleteTakeoverContext context) throws Exception;
    }

    /** 向接管参与者暴露同一个删除绝对截止时间和排除自身的静默等待。 */
    public static final class DeleteTakeoverContext {

        private final OperationState state;
        private final long deadlineNanos;

        private DeleteTakeoverContext(
                OperationState state, long deadlineNanos) {
            this.state = state;
            this.deadlineNanos = deadlineNanos;
        }

        public boolean awaitQuiescence() throws InterruptedException {
            return state.awaitQuiescenceAndSeal(deadlineNanos, false);
        }

        public Duration remainingTime() {
            long remainingNanos = OperationState.remainingNanos(deadlineNanos);
            return remainingNanos <= 0
                    ? Duration.ZERO : Duration.ofNanos(remainingNanos);
        }
    }

    /** 尚未开始接管时可撤销，参与者开始后关闭不会中断收尾。 */
    public static final class DeleteTakeoverRegistration implements AutoCloseable {

        private final OperationState state;
        private final DeleteTakeoverEntry entry;
        private final AtomicBoolean closed = new AtomicBoolean();

        private DeleteTakeoverRegistration(
                OperationState state, DeleteTakeoverEntry entry) {
            this.state = state;
            this.entry = entry;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                state.removeDeleteTakeoverParticipant(entry);
            }
        }
    }

    public static final class ActiveAppOperationException extends IllegalStateException {

        private final AppOperationType activeOperation;

        private ActiveAppOperationException(
                long appId, AppOperationType type, String ownerToken) {
            super("应用 " + appId + " 已有活跃操作: " + type + ", owner=" + ownerToken);
            this.activeOperation = type;
        }

        public AppOperationType activeOperation() {
            return activeOperation;
        }
    }

    public static final class OperationQuiescenceTimeoutException extends IllegalStateException {

        private OperationQuiescenceTimeoutException(
                long appId, String message, Throwable cause) {
            super("应用 " + appId + message, cause);
        }
    }

    private static final class OperationState {

        private final AppOperationLeaseManager manager;
        private final long appId;
        private final AppOperationType operationType;
        private final String ownerToken;
        private final long startedAtNanos;
        private final Map<Long, CancellationEntry> cancellationEntries =
                new LinkedHashMap<>();

        private long nextCancellationId;
        private long nextDeleteTakeoverParticipantId;
        private int callbackCount;
        private int cancellationDispatchCount;
        private int cancellationDispatchStartDecisionCount;
        private int runningCancellationCount;
        private Throwable cancellationFailure;
        private boolean cancellationRequested;
        private boolean ownerClosed;
        private boolean deleteTakeover;
        private boolean replaced;
        private boolean cancellationRegistrationSealed;
        private boolean vueSessionClaimed;
        private boolean deleteTakeoverParticipantRegistered;
        private DeleteTakeoverEntry deleteTakeoverParticipant;

        private OperationState(
                AppOperationLeaseManager manager,
                long appId,
                AppOperationType operationType,
                String ownerToken) {
            this.manager = manager;
            this.appId = appId;
            this.operationType = operationType;
            this.ownerToken = ownerToken;
            this.startedAtNanos = System.nanoTime();
        }

        private synchronized boolean isActiveInstance() {
            return !ownerClosed && !replaced && !cancellationRequested;
        }

        private synchronized boolean isOwnedInstance() {
            return !ownerClosed && !replaced;
        }

        private synchronized boolean isCancellationRequested() {
            return cancellationRequested;
        }

        private synchronized void claimVueSession() {
            if (vueSessionClaimed) {
                throw new IllegalStateException("生成操作租约已绑定 Vue 构建会话");
            }
            vueSessionClaimed = true;
        }

        private synchronized CallbackRegistration enterCallback() {
            if (ownerClosed || replaced || cancellationRequested) {
                throw new IllegalStateException("应用操作取消门已经关闭");
            }
            callbackCount++;
            return new CallbackRegistration(this);
        }

        private synchronized boolean exitCallback() {
            if (callbackCount <= 0) {
                throw new IllegalStateException("回调引用计数不合法");
            }
            callbackCount--;
            if (callbackCount == 0) {
                notifyAll();
            }
            return removable();
        }

        private CancellationRegistration registerCancellation(Runnable action) {
            Objects.requireNonNull(action, "取消动作不能为空");
            CancellationEntry entry;
            boolean runImmediately;
            synchronized (this) {
                if (ownerClosed || replaced || cancellationRegistrationSealed) {
                    throw new IllegalStateException("取消动作注册边界已经关闭");
                }
                entry = new CancellationEntry(++nextCancellationId, action);
                runImmediately = cancellationRequested;
                if (runImmediately) {
                    cancellationDispatchCount++;
                } else {
                    cancellationEntries.put(entry.id, entry);
                }
            }
            if (runImmediately) {
                CancellationDispatch dispatch = new CancellationDispatch(List.of(entry));
                Throwable failure = null;
                try {
                    runCancellationAction(entry);
                } catch (Throwable exception) {
                    failure = exception;
                } finally {
                    finishCancellationDispatch(dispatch, failure);
                }
                if (failure != null) {
                    throwUnchecked(failure);
                }
            }
            return new CancellationRegistration(this, entry);
        }

        private synchronized DeleteTakeoverRegistration
                registerDeleteTakeoverParticipant(
                        DeleteTakeoverParticipant participant) {
            Objects.requireNonNull(participant, "删除接管参与者不能为空");
            if (ownerClosed || replaced || cancellationRequested
                    || cancellationRegistrationSealed) {
                throw new IllegalStateException("删除接管参与者注册边界已经关闭");
            }
            if (deleteTakeoverParticipantRegistered) {
                throw new IllegalStateException("生成租约只能注册一个删除接管参与者");
            }
            deleteTakeoverParticipantRegistered = true;
            DeleteTakeoverEntry entry = new DeleteTakeoverEntry(
                    ++nextDeleteTakeoverParticipantId, participant);
            deleteTakeoverParticipant = entry;
            return new DeleteTakeoverRegistration(this, entry);
        }

        private synchronized void removeDeleteTakeoverParticipant(
                DeleteTakeoverEntry entry) {
            if (deleteTakeoverParticipant == entry
                    && !deleteTakeover
                    && !entry.started()) {
                entry.cancel();
                deleteTakeoverParticipant = null;
            }
        }

        private void cancelCancellation(CancellationEntry entry) {
            if (!entry.cancelPending()) {
                return;
            }
            boolean removable;
            synchronized (this) {
                cancellationEntries.remove(entry.id, entry);
                notifyIfQuiescent();
                removable = removable();
            }
            if (removable) {
                manager.cancellationActivityFinished(this);
            }
        }

        private synchronized boolean requestCancellation() {
            if (cancellationRequested) {
                return false;
            }
            cancellationRequested = true;
            notifyAll();
            return true;
        }

        private synchronized boolean requestCancellationIf(
                BooleanSupplier claimAction) {
            Objects.requireNonNull(claimAction, "终态认领动作不能为空");
            if (ownerClosed || replaced) {
                throw new IllegalStateException("应用操作租约已经失效");
            }
            if (cancellationRequested || !claimAction.getAsBoolean()) {
                return false;
            }
            cancellationRequested = true;
            notifyAll();
            return true;
        }

        private void fireCancellationActions() {
            CancellationDispatch dispatch = prepareCancellationDispatch();
            if (dispatch != null) {
                executeCancellationDispatch(dispatch, true);
            }
        }

        private CancellationDispatch
                awaitDispatchDecisionAndPrepareNextForClosedOwner() {
            boolean interrupted = false;
            synchronized (this) {
                while (cancellationEntries.isEmpty()
                        && cancellationDispatchStartDecisionCount > 0) {
                    try {
                        wait();
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
                CancellationDispatch dispatch = prepareCancellationDispatch();
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                return dispatch;
            }
        }

        private CancellationDispatch prepareCancellationDispatch() {
            return prepareCancellationDispatch(false);
        }

        private CancellationDispatch prepareCancellationDispatchForStart() {
            return prepareCancellationDispatch(true);
        }

        private CancellationDispatch prepareCancellationDispatch(
                boolean startDecisionPending) {
            List<CancellationEntry> entries;
            synchronized (this) {
                if (!cancellationRequested || cancellationEntries.isEmpty()) {
                    return null;
                }
                cancellationDispatchCount++;
                if (startDecisionPending) {
                    cancellationDispatchStartDecisionCount++;
                }
                entries = new ArrayList<>(cancellationEntries.values());
                cancellationEntries.clear();
            }
            return new CancellationDispatch(entries);
        }

        private synchronized void completeCancellationDispatchStart() {
            if (cancellationDispatchStartDecisionCount <= 0) {
                throw new IllegalStateException("取消分发启动决策计数不合法");
            }
            cancellationDispatchStartDecisionCount--;
            notifyAll();
        }

        private void executeCancellationDispatch(
                CancellationDispatch dispatch, boolean propagateFailure) {
            Throwable failure = null;
            try {
                for (CancellationEntry entry : dispatch.entries) {
                    try {
                        runCancellationAction(entry);
                    } catch (Throwable exception) {
                        if (failure == null) {
                            failure = exception;
                        } else if (failure != exception) {
                            failure.addSuppressed(exception);
                        }
                    }
                }
            } finally {
                finishCancellationDispatch(dispatch, failure);
            }
            if (propagateFailure && failure != null) {
                throwUnchecked(failure);
            }
        }

        private void runCancellationAction(CancellationEntry entry) {
            synchronized (this) {
                if (!entry.start()) {
                    return;
                }
                runningCancellationCount++;
            }
            try {
                entry.runAction();
            } finally {
                boolean removable;
                synchronized (this) {
                    entry.finish();
                    runningCancellationCount--;
                    notifyIfQuiescent();
                    removable = removable();
                }
                if (removable) {
                    manager.cancellationActivityFinished(this);
                }
            }
        }

        private void finishCancellationDispatch(
                CancellationDispatch dispatch, Throwable failure) {
            boolean removable;
            synchronized (this) {
                dispatch.failure.set(failure);
                cancellationFailure = appendFailure(cancellationFailure, failure);
                cancellationDispatchCount--;
                notifyAll();
                removable = removable();
            }
            if (removable) {
                manager.cancellationActivityFinished(this);
            }
        }

        private boolean rollbackFailedDispatchStart(CancellationDispatch dispatch) {
            Throwable invariantFailure = null;
            boolean removable;
            synchronized (this) {
                cancellationDispatchCount--;
                if (cancellationDispatchStartDecisionCount <= 0) {
                    invariantFailure = new IllegalStateException(
                            "取消分发启动回退计数不合法");
                } else {
                    cancellationDispatchStartDecisionCount--;
                }
                for (CancellationEntry entry : dispatch.entries) {
                    switch (entry.lifecycle()) {
                        case PENDING -> {
                            CancellationEntry existing =
                                    cancellationEntries.putIfAbsent(entry.id, entry);
                            if (existing != null && existing != entry
                                    && invariantFailure == null) {
                                invariantFailure = new IllegalStateException(
                                        "取消动作回退时出现重复标识");
                            }
                        }
                        case RUNNING -> {
                            if (invariantFailure == null) {
                                invariantFailure = new IllegalStateException(
                                        "启动失败的取消分发中存在运行中动作");
                            }
                        }
                        case CANCELLED, DONE -> {
                            // 已关闭或已完成的动作不能重新进入待执行队列。
                        }
                    }
                }
                notifyAll();
                removable = removable();
            }
            if (invariantFailure != null) {
                throwUnchecked(invariantFailure);
            }
            return removable;
        }

        private synchronized Throwable consumeCancellationFailure() {
            Throwable failure = cancellationFailure;
            cancellationFailure = null;
            return failure;
        }

        private synchronized boolean awaitQuiescence(Duration timeout)
                throws InterruptedException {
            long deadlineNanos = deadlineAfter(timeout);
            long remainingNanos = remainingNanos(deadlineNanos);
            while (!isQuiescent()) {
                if (remainingNanos <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(this, remainingNanos);
                remainingNanos = remainingNanos(deadlineNanos);
            }
            return true;
        }

        private synchronized boolean awaitQuiescenceAndSeal(long deadlineNanos)
                throws InterruptedException {
            return awaitQuiescenceAndSeal(deadlineNanos, true);
        }

        private synchronized boolean awaitQuiescenceAndSeal(
                long deadlineNanos, boolean sealRegistration)
                throws InterruptedException {
            long remainingNanos = remainingNanos(deadlineNanos);
            while (!isQuiescent()) {
                if (remainingNanos <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(this, remainingNanos);
                remainingNanos = remainingNanos(deadlineNanos);
            }
            if (sealRegistration) {
                cancellationRegistrationSealed = true;
            }
            return true;
        }

        private static long remainingNanos(long deadlineNanos) {
            if (deadlineNanos == Long.MAX_VALUE) {
                return Long.MAX_VALUE;
            }
            return deadlineNanos - System.nanoTime();
        }

        private synchronized DeleteTakeoverEntry beginDeleteTakeover() {
            if (deleteTakeover) {
                throw new ActiveAppOperationException(appId, operationType, ownerToken);
            }
            DeleteTakeoverEntry participant = deleteTakeoverParticipant;
            if (participant == null || participant.cancelled()) {
                throw new IllegalStateException("活跃生成缺少删除接管参与者");
            }
            deleteTakeover = true;
            requestCancellation();
            return participant;
        }

        private synchronized boolean abortDeleteTakeover() {
            deleteTakeover = false;
            return removable();
        }

        private synchronized void completeDeleteTakeover() {
            if (!cancellationRegistrationSealed || !isQuiescent()) {
                throw new IllegalStateException("删除替换前取消注册边界尚未静默封闭");
            }
            replaced = true;
            deleteTakeover = false;
        }

        private synchronized boolean closeOwner() {
            if (!ownerClosed) {
                cancellationRegistrationSealed = true;
                ownerClosed = true;
                requestCancellation();
            }
            return removable();
        }

        private boolean removable() {
            return ownerClosed && isQuiescent() && cancellationEntries.isEmpty()
                    && !deleteTakeover && !replaced;
        }

        private synchronized boolean isRemovable() {
            return removable();
        }

        private boolean isQuiescent() {
            return callbackCount == 0 && cancellationDispatchCount == 0
                    && runningCancellationCount == 0;
        }

        private void notifyIfQuiescent() {
            if (isQuiescent()) {
                notifyAll();
            }
        }
    }

    private static final class DeleteTakeoverEntry {

        private final long id;
        private final DeleteTakeoverParticipant participant;
        private DeleteTakeoverLifecycle lifecycle = DeleteTakeoverLifecycle.READY;
        private boolean cancelled;

        private DeleteTakeoverEntry(
                long id, DeleteTakeoverParticipant participant) {
            this.id = id;
            this.participant = participant;
        }

        private long id() {
            return id;
        }

        private DeleteTakeoverParticipant participant() {
            return participant;
        }

        private synchronized boolean tryStart() {
            if (cancelled) {
                throw new IllegalStateException("删除接管参与者状态不合法");
            }
            if (lifecycle == DeleteTakeoverLifecycle.COMPLETED) {
                return false;
            }
            if (lifecycle != DeleteTakeoverLifecycle.READY) {
                throw new IllegalStateException("删除接管参与者仍在执行");
            }
            lifecycle = DeleteTakeoverLifecycle.RUNNING;
            return true;
        }

        private synchronized void cancel() {
            if (lifecycle == DeleteTakeoverLifecycle.READY) {
                cancelled = true;
            }
        }

        private synchronized void finish(boolean completed) {
            if (lifecycle != DeleteTakeoverLifecycle.RUNNING) {
                throw new IllegalStateException("删除接管参与者完成状态不合法");
            }
            lifecycle = completed
                    ? DeleteTakeoverLifecycle.COMPLETED
                    : DeleteTakeoverLifecycle.READY;
        }

        private synchronized boolean started() {
            return lifecycle != DeleteTakeoverLifecycle.READY;
        }

        private synchronized boolean cancelled() {
            return cancelled;
        }
    }

    private enum DeleteTakeoverLifecycle {
        READY,
        RUNNING,
        COMPLETED
    }

    private static final class CancellationDispatch {

        private final List<CancellationEntry> entries;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private CancellationDispatch(List<CancellationEntry> entries) {
            this.entries = entries;
        }
    }

    private static final class CancellationEntry {

        private enum Lifecycle {
            PENDING, RUNNING, CANCELLED, DONE
        }

        private final long id;
        private final Runnable action;
        private final AtomicReference<Lifecycle> lifecycle =
                new AtomicReference<>(Lifecycle.PENDING);

        private CancellationEntry(long id, Runnable action) {
            this.id = id;
            this.action = action;
        }

        private boolean start() {
            return lifecycle.compareAndSet(Lifecycle.PENDING, Lifecycle.RUNNING);
        }

        private Lifecycle lifecycle() {
            return lifecycle.get();
        }

        private boolean cancelPending() {
            return lifecycle.compareAndSet(Lifecycle.PENDING, Lifecycle.CANCELLED);
        }

        private void runAction() {
            action.run();
        }

        private void finish() {
            if (!lifecycle.compareAndSet(Lifecycle.RUNNING, Lifecycle.DONE)) {
                throw new IllegalStateException("取消动作生命周期不合法");
            }
        }
    }
}
