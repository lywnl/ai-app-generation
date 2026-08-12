package com.lyw.appgeneration.core.concurrency;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 统一管理同一应用上的生成、部署、下载和删除互斥关系。
 */
@Component
public final class AppOperationLeaseManager {

    private static final Duration MAX_DELETE_QUIESCENCE_WAIT = Duration.ofSeconds(10);

    private final ConcurrentHashMap<Long, OperationState> operations =
            new ConcurrentHashMap<>();

    public enum AppOperationType {
        GENERATE, DEPLOY, DOWNLOAD, DELETE
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
        AtomicReference<OperationState> sourceReference = new AtomicReference<>();
        AtomicReference<OperationState> deleteReference = new AtomicReference<>();
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
            active.beginDeleteTakeover();
            sourceReference.set(active);
            return active;
        });
        OperationState existingDelete = deleteReference.get();
        if (existingDelete != null) {
            return new AppOperationLease(existingDelete);
        }

        OperationState source = sourceReference.get();
        try {
            source.fireCancellationActions();
        } catch (RuntimeException exception) {
            abortDeleteTakeover(source);
            throw exception;
        }
        boolean quiescent;
        try {
            quiescent = source.awaitQuiescence(boundedTimeout);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            abortDeleteTakeover(source);
            throw new OperationQuiescenceTimeoutException(
                    appId, "等待静默时线程被中断", exception);
        }
        if (!quiescent) {
            abortDeleteTakeover(source);
            throw new OperationQuiescenceTimeoutException(
                    appId, "等待生成回调静默超时", null);
        }
        OperationState delete = new OperationState(
                this, appId, AppOperationType.DELETE, ownerToken);
        if (!operations.replace(appId, source, delete)) {
            source.abortDeleteTakeover();
            throw new IllegalStateException("生成租约在删除接管期间发生了非法替换");
        }
        source.completeDeleteTakeover();
        return new AppOperationLease(delete);
    }

    private void abortDeleteTakeover(OperationState source) {
        boolean removable = source.abortDeleteTakeover();
        if (removable) {
            operations.remove(source.appId, source);
        }
    }

    private void ownerClosed(OperationState state) {
        boolean removable = state.closeOwner();
        try {
            state.fireCancellationActions();
        } finally {
            if (removable) {
                operations.remove(state.appId, state);
            }
        }
    }

    private void callbackClosed(OperationState state) {
        if (state.exitCallback()) {
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

        public boolean isActive() {
            return !closed.get() && state.isActiveInstance();
        }

        public void claimVueSession() {
            ensureActive();
            state.claimVueSession();
        }

        public CancellationRegistration registerCancellation(Runnable action) {
            ensureActiveOrCancellationRequested();
            return state.registerCancellation(action);
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
            state.removeCancellation(entry);
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

    public static final class ActiveAppOperationException extends IllegalStateException {

        private ActiveAppOperationException(
                long appId, AppOperationType type, String ownerToken) {
            super("应用 " + appId + " 已有活跃操作: " + type + ", owner=" + ownerToken);
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
        private final Map<Long, CancellationEntry> cancellationEntries =
                new LinkedHashMap<>();

        private long nextCancellationId;
        private int callbackCount;
        private boolean cancellationRequested;
        private boolean ownerClosed;
        private boolean deleteTakeover;
        private boolean replaced;
        private boolean vueSessionClaimed;

        private OperationState(
                AppOperationLeaseManager manager,
                long appId,
                AppOperationType operationType,
                String ownerToken) {
            this.manager = manager;
            this.appId = appId;
            this.operationType = operationType;
            this.ownerToken = ownerToken;
        }

        private synchronized boolean isActiveInstance() {
            return !ownerClosed && !replaced && !cancellationRequested;
        }

        private synchronized boolean isOwnedInstance() {
            return !ownerClosed && !replaced;
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
                entry = new CancellationEntry(++nextCancellationId, action);
                runImmediately = cancellationRequested;
                if (!runImmediately) {
                    cancellationEntries.put(entry.id, entry);
                }
            }
            if (runImmediately) {
                entry.runOnce();
            }
            return new CancellationRegistration(this, entry);
        }

        private synchronized void removeCancellation(CancellationEntry entry) {
            cancellationEntries.remove(entry.id, entry);
        }

        private synchronized boolean requestCancellation() {
            if (cancellationRequested) {
                return false;
            }
            cancellationRequested = true;
            notifyAll();
            return true;
        }

        private void fireCancellationActions() {
            List<CancellationEntry> entries;
            synchronized (this) {
                if (!cancellationRequested || cancellationEntries.isEmpty()) {
                    return;
                }
                entries = new ArrayList<>(cancellationEntries.values());
                cancellationEntries.clear();
            }
            RuntimeException failure = null;
            for (CancellationEntry entry : entries) {
                try {
                    entry.runOnce();
                } catch (RuntimeException exception) {
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private synchronized boolean awaitQuiescence(Duration timeout)
                throws InterruptedException {
            long remainingNanos = timeout.toNanos();
            long deadlineNanos = System.nanoTime() + remainingNanos;
            while (callbackCount > 0) {
                if (remainingNanos <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(this, remainingNanos);
                remainingNanos = deadlineNanos - System.nanoTime();
            }
            return true;
        }

        private synchronized void beginDeleteTakeover() {
            if (deleteTakeover) {
                throw new ActiveAppOperationException(appId, operationType, ownerToken);
            }
            deleteTakeover = true;
            requestCancellation();
        }

        private synchronized boolean abortDeleteTakeover() {
            deleteTakeover = false;
            return removable();
        }

        private synchronized void completeDeleteTakeover() {
            replaced = true;
            deleteTakeover = false;
        }

        private synchronized boolean closeOwner() {
            if (!ownerClosed) {
                ownerClosed = true;
                requestCancellation();
            }
            return removable();
        }

        private boolean removable() {
            return ownerClosed && callbackCount == 0 && !deleteTakeover && !replaced;
        }
    }

    private static final class CancellationEntry {

        private final long id;
        private final Runnable action;
        private final AtomicBoolean executed = new AtomicBoolean();

        private CancellationEntry(long id, Runnable action) {
            this.id = id;
            this.action = action;
        }

        private void runOnce() {
            if (executed.compareAndSet(false, true)) {
                action.run();
            }
        }
    }
}
