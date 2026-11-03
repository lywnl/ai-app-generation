package com.lyw.appgeneration.core.resource;

import com.lyw.appgeneration.core.handler.VueTurnContext;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** 一轮资料的统一截止与可撤销任务组；到期只关闭资料，不取消生成回合。 */
@Slf4j
public final class ResourcePreparationScope implements AutoCloseable {
    private final Object monitor = new Object();
    private final long started;
    private final long budgetNanos;
    private final LongSupplier ticker;
    private final BooleanSupplier turnActive;
    private final String identity;
    private final Map<Object, Runnable> cancellationActions = new LinkedHashMap<>();
    private final List<Slot> slots = new ArrayList<>();
    private final AtomicReference<AutoCloseable> turnRegistration = new AtomicReference<>();
    private boolean closed;
    private boolean cancelled;

    public ResourcePreparationScope(Duration budget, BooleanSupplier turnActive, LongSupplier ticker, String identity) {
        if (budget.isNegative()) throw new IllegalArgumentException("资料预算不能为负数");
        this.budgetNanos = budget.toNanos();
        this.ticker = Objects.requireNonNull(ticker);
        this.started = ticker.getAsLong();
        this.turnActive = Objects.requireNonNull(turnActive);
        this.identity = Objects.requireNonNull(identity);
    }

    public static ResourcePreparationScope start(VueTurnContext context, Duration budget) {
        Duration remaining = context.remainingUntilDeadline();
        ResourcePreparationScope scope = new ResourcePreparationScope(
                remaining.compareTo(budget) < 0 ? remaining : budget,
                context::resourcePreparationActive, System::nanoTime,
                "appId=" + context.appId() + ",turnId=" + context.turnId());
        AutoCloseable registration = context.registerResourceCancellation(scope::cancel);
        scope.turnRegistration.set(registration);
        if (!scope.canStart()) scope.closeRegistration();
        return scope;
    }

    public long remainingNanos() {
        long elapsed = Math.max(0, ticker.getAsLong() - started);
        return elapsed >= budgetNanos ? 0 : budgetNanos - elapsed;
    }

    public int remainingMillisForRequest() {
        long nanos = remainingNanos();
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, (nanos + 999_999L) / 1_000_000L));
    }

    public boolean canStart() {
        if (!turnActive.getAsBoolean() || Thread.currentThread().isInterrupted()) return false;
        synchronized (monitor) { return !closed && !cancelled && remainingNanos() > 0; }
    }

    public void requireActive() {
        if (!canStart()) throw new CancellationException("资料准备已截止或取消");
    }

    public Slot slot(String stage) {
        synchronized (monitor) {
            Slot slot = new Slot(stage);
            if (closed) { slot.finished = true; slot.outcome = Outcome.CANCELLED; }
            slots.add(slot);
            return slot;
        }
    }

    /** 可独立注销；注册和关闭竞争时取消动作仍执行一次。 */
    public AutoCloseable onClose(Runnable action) {
        Object key = new Object();
        boolean runNow;
        synchronized (monitor) {
            runNow = closed;
            if (!runNow) cancellationActions.put(key, action);
        }
        if (runNow) action.run();
        return () -> { synchronized (monitor) { cancellationActions.remove(key); } };
    }

    /** 实际排队任务可取消和移除；完成回调不等待同池任务。 */
    public <T> CompletableFuture<T> submit(ExecutorService executor, String stage, Callable<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        AtomicReference<AutoCloseable> registration = new AtomicReference<>();
        long enqueued = ticker.getAsLong();
        FutureTask<Void> task = new FutureTask<>(() -> {
            long began = ticker.getAsLong();
            try {
                requireActive();
                T value = action.call();
                requireActive();
                result.complete(value);
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            } finally {
                log.info("[Vue资料任务] {},stage={},queueMs={},executeMs={}", identity, stage,
                        TimeUnit.NANOSECONDS.toMillis(Math.max(0, began - enqueued)),
                        TimeUnit.NANOSECONDS.toMillis(Math.max(0, ticker.getAsLong() - began)));
            }
            return null;
        }) {
            @Override protected void done() {
                if (isCancelled()) result.cancel(false);
                closeQuietly(registration.getAndSet(null));
            }
        };
        // 提交和队列撤销共用 monitor，避免先 remove 后 enqueue 留下幽灵排队项。
        synchronized (monitor) {
            if (!canStart()) {
                result.cancel(false);
                return result;
            }
            Object key = new Object();
            cancellationActions.put(key, () -> {
                task.cancel(true);
                if (executor instanceof ThreadPoolExecutor pool) pool.remove(task);
            });
            registration.set(() -> { synchronized (monitor) { cancellationActions.remove(key); } });
            try {
                executor.execute(task);
            } catch (RejectedExecutionException failure) {
                result.completeExceptionally(failure);
                task.cancel(false);
            }
        }
        return result;
    }

    /** 等待一次总期限，冻结已发布的部分结果；从不按 Future 分别重置预算。 */
    public List<PreparedMaterial> awaitAndFreeze() {
        List<PreparedMaterial> result;
        List<Runnable> cleanup;
        boolean interrupted = false;
        synchronized (monitor) {
            while (!closed && turnActive.getAsBoolean() && remainingNanos() > 0
                    && slots.stream().anyMatch(slot -> !slot.finished)) {
                try {
                    TimeUnit.NANOSECONDS.timedWait(monitor, remainingNanos());
                } catch (InterruptedException stop) {
                    interrupted = true;
                    cancelled = true;
                    break;
                }
            }
            cancelled |= !turnActive.getAsBoolean();
            closed = true;
            result = new ArrayList<>();
            for (Slot slot : slots) {
                Outcome outcome = cancelled ? Outcome.CANCELLED : slot.finished ? slot.outcome
                        : slot.value != null && !slot.value.text().isBlank() ? Outcome.PARTIAL : Outcome.TIMED_OUT;
                log.info("[Vue资料结果] {},stage={},status={},items={},themes={},totalMs={}", identity,
                        slot.stage, outcome, slot.value == null ? 0 : slot.value.items(),
                        slot.value == null ? 0 : slot.value.themes(),
                        TimeUnit.NANOSECONDS.toMillis(Math.max(0, ticker.getAsLong() - started)));
                if (!cancelled && slot.value != null && !slot.value.text().isBlank()) result.add(slot.value);
            }
            cleanup = new ArrayList<>(cancellationActions.values());
            cancellationActions.clear();
            monitor.notifyAll();
        }
        cleanup.forEach(ResourcePreparationScope::cancelQuietly);
        closeRegistration();
        if (interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("资料准备等待已中断");
        }
        return List.copyOf(result);
    }

    public void cancel() { terminate(true); }
    @Override public void close() { terminate(false); }

    private void terminate(boolean userCancelled) {
        List<Runnable> cleanup;
        synchronized (monitor) {
            cancelled |= userCancelled;
            closed = true;
            cleanup = new ArrayList<>(cancellationActions.values());
            cancellationActions.clear();
            monitor.notifyAll();
        }
        cleanup.forEach(ResourcePreparationScope::cancelQuietly);
        closeRegistration();
    }

    private void closeRegistration() { closeQuietly(turnRegistration.getAndSet(null)); }
    private static void closeQuietly(AutoCloseable value) {
        if (value != null) try { value.close(); }
        catch (Exception error) { log.warn("资料取消注册清理失败,errorType={}", error.getClass().getSimpleName()); }
    }
    private static void cancelQuietly(Runnable action) {
        try { action.run(); }
        catch (RuntimeException error) { log.warn("资料任务取消失败,errorType={}", error.getClass().getSimpleName()); }
    }

    public enum Outcome { SUCCEEDED, EMPTY, PARTIAL, TIMED_OUT, CANCELLED, REJECTED, FAILED }

    public record PreparedMaterial(String stage, String text, int items, int themes) {
        public PreparedMaterial { Objects.requireNonNull(stage); Objects.requireNonNull(text); }
    }

    public final class Slot {
        private final String stage;
        private PreparedMaterial value;
        private boolean finished;
        private Outcome outcome = Outcome.EMPTY;
        private Slot(String stage) { this.stage = Objects.requireNonNull(stage); }

        /** 发布的是已完成格式化的不可变正文；关闭之后无法覆盖之前的事实。 */
        public boolean publish(String text, int items, int themes) {
            if (!canStart()) return false;
            synchronized (monitor) {
                if (closed || finished || remainingNanos() == 0) return false;
                value = new PreparedMaterial(stage, Objects.requireNonNullElse(text, ""), items, themes);
                return true;
            }
        }
        public void finish(Outcome outcome) {
            synchronized (monitor) {
                if (finished) return;
                this.finished = true;
                this.outcome = outcome == Outcome.SUCCEEDED && (value == null || value.text().isBlank())
                        ? Outcome.EMPTY : outcome == Outcome.PARTIAL && (value == null || value.text().isBlank())
                        ? Outcome.FAILED : outcome;
                monitor.notifyAll();
            }
        }
    }
}
