package com.lyw.appgeneration.service.rag.retrieval;

import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.core.resource.ResourcePreparationScope;
import com.lyw.appgeneration.service.rag.model.RagDocumentKind;
import com.lyw.appgeneration.service.rag.monitor.VueRagMetricsCollector;
import com.lyw.appgeneration.service.rag.monitor.VueRagStage;
import jakarta.annotation.PreDestroy;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Vue RAG 有界执行器；请求许可直到物理调用结束才归还。 */
@Component
public class VueRagExecutor implements AutoCloseable {
    private static final int MAX_TASKS_PER_REQUEST = 7;
    private final ThreadPoolExecutor workers;
    private final Semaphore requests;
    private final int maxConcurrentRequests;
    private final VueRagMetricsCollector metrics;
    private final AtomicBoolean closing = new AtomicBoolean();

    public VueRagExecutor(RagProperties properties, VueRagMetricsCollector metrics) {
        RagProperties.Execution config = properties.getExecution();
        config.validate();
        this.metrics = metrics;
        maxConcurrentRequests = config.getMaxConcurrentRequests();
        requests = new Semaphore(maxConcurrentRequests, true);
        workers = new ThreadPoolExecutor(config.getWorkerThreads(), config.getWorkerThreads(),
                0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(config.getQueueCapacity()),
                Thread.ofPlatform().name("vue-rag-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    /** 保留离线及兼容调用的既有等待语义。 */
    public RequestScope openRequest() { return openRequest(null); }

    /** 新在线入口把许可等待计入同一个资料截止。 */
    public RequestScope openRequest(ResourcePreparationScope deadline) {
        long started = System.nanoTime();
        boolean acquired = false;
        try {
            if (closing.get()) throw new RejectedExecutionException("Vue RAG 执行器正在关闭");
            if (deadline == null) {
                requests.acquire();
                acquired = true;
            } else {
                while (!acquired) {
                    deadline.requireActive();
                    acquired = requests.tryAcquire(Math.min(deadline.remainingNanos(),
                            TimeUnit.MILLISECONDS.toNanos(25)), TimeUnit.NANOSECONDS);
                }
            }
            if (closing.get()) throw new RejectedExecutionException("Vue RAG 执行器正在关闭");
            if (deadline != null) deadline.requireActive();
            RequestScope request = new RequestScope(MDC.getCopyOfContextMap(), deadline);
            acquired = false; // 许可所有权已转交 RequestScope。
            request.registerCancellation();
            return request;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw cancelled(interrupted);
        } finally {
            if (acquired) requests.release();
            metrics.recordAdmissionWait(Duration.ofNanos(System.nanoTime() - started));
        }
    }

    @Override @PreDestroy public void close() {
        if (!closing.compareAndSet(false, true)) return;
        requests.acquireUninterruptibly(maxConcurrentRequests);
        try { workers.close(); }
        finally { requests.release(maxConcurrentRequests); }
    }

    private static CancellationException cancelled(InterruptedException cause) {
        CancellationException result = new CancellationException("Vue RAG 请求已中断");
        result.initCause(cause);
        return result;
    }

    public final class RequestScope implements AutoCloseable {
        private final Map<String, String> context;
        private final ResourcePreparationScope deadline;
        private final Set<RequestTask<?>> tasks = new HashSet<>();
        private int submittedTasks;
        private boolean closed;
        private boolean released;
        private AutoCloseable cancellation;

        private RequestScope(Map<String, String> context, ResourcePreparationScope deadline) {
            this.context = context;
            this.deadline = deadline;
        }

        private void registerCancellation() {
            if (deadline == null) return;
            AutoCloseable registration = deadline.onClose(this::close);
            boolean dispose;
            synchronized (this) { dispose = closed; if (!dispose) cancellation = registration; }
            if (dispose) closeRegistration(registration);
        }

        public <T> CompletableFuture<T> submit(VueRagStage stage, RagDocumentKind kind, Supplier<T> operation) {
            RequestTask<T> task;
            synchronized (this) {
                if (closed || deadline != null && !deadline.canStart()) {
                    return CompletableFuture.failedFuture(new CancellationException("Vue RAG 请求已结束"));
                }
                if (submittedTasks >= MAX_TASKS_PER_REQUEST) {
                    return CompletableFuture.failedFuture(new IllegalStateException("单次 Vue RAG 请求任务数超过 7"));
                }
                submittedTasks++;
                task = new RequestTask<>(stage, kind, operation);
                tasks.add(task);
                // 与 close 的任务快照互斥，避免撤销在入队之前完成。
                try { workers.execute(task); }
                catch (RuntimeException | Error failure) { task.reject(failure); }
            }
            return task.result;
        }

        public <T> T await(CompletableFuture<T> result) {
            try {
                return deadline == null ? result.get() : result.get(Math.max(1, deadline.remainingNanos()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                close();
                Thread.currentThread().interrupt();
                throw cancelled(interrupted);
            } catch (TimeoutException timeout) {
                close();
                throw new CancellationException("Vue RAG 资料截止已到达");
            } catch (ExecutionException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new CompletionException(cause);
            }
        }

        private synchronized boolean isClosed() { return closed; }

        private synchronized void taskFinished(RequestTask<?> task) {
            tasks.remove(task);
            releaseIfFinished();
        }

        @Override public void close() {
            java.util.List<RequestTask<?>> pending;
            AutoCloseable registration;
            synchronized (this) {
                if (closed) return;
                closed = true;
                pending = new ArrayList<>(tasks);
                registration = cancellation;
                cancellation = null;
                releaseIfFinished();
            }
            pending.forEach(task -> task.cancel(deadline != null));
            closeRegistration(registration);
        }

        private void releaseIfFinished() {
            if (closed && tasks.isEmpty() && !released) {
                released = true;
                requests.release();
            }
        }

        private final class RequestTask<T> implements Runnable {
            private final VueRagStage stage;
            private final RagDocumentKind kind;
            private final Supplier<T> operation;
            private final CompletableFuture<T> result = new CompletableFuture<>();
            private final long enqueued = System.nanoTime();
            private int state; // 0排队，1运行，2物理结束；Future取消不等于2。
            private Thread runner;

            private RequestTask(VueRagStage stage, RagDocumentKind kind, Supplier<T> operation) {
                this.stage = stage; this.kind = kind; this.operation = operation;
            }

            @Override public void run() {
                synchronized (this) {
                    if (state != 0) return;
                    state = 1;
                    runner = Thread.currentThread();
                }
                Map<String, String> previous = MDC.getCopyOfContextMap();
                try {
                    restoreContext(context);
                    metrics.recordExecutorQueueDuration(stage, kind, Duration.ofNanos(System.nanoTime() - enqueued));
                    if (isClosed() || result.isCancelled()) throw new CancellationException("Vue RAG 请求已结束");
                    if (deadline != null) deadline.requireActive();
                    long started = System.nanoTime();
                    T value;
                    try {
                        if (stage == VueRagStage.EMBEDDING) metrics.recordEmbeddingCall();
                        value = operation.get();
                    } finally {
                        metrics.recordStageDuration(stage, kind, Duration.ofNanos(System.nanoTime() - started));
                    }
                    if (deadline != null) deadline.requireActive();
                    result.complete(value);
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                } finally {
                    restoreContext(previous);
                    synchronized (this) { state = 2; runner = null; }
                    taskFinished(this);
                }
            }

            private void reject(Throwable failure) {
                result.completeExceptionally(failure);
                cancel(false);
            }

            private void cancel(boolean interruptRunning) {
                boolean queued;
                synchronized (this) {
                    if (state == 2) return;
                    queued = state == 0;
                    if (queued) state = 2;
                    else if (interruptRunning && !result.isDone() && runner != null) runner.interrupt();
                }
                result.cancel(false);
                if (queued) {
                    workers.remove(this);
                    taskFinished(this);
                }
            }
        }
    }

    private static void closeRegistration(AutoCloseable registration) {
        if (registration == null) return;
        try { registration.close(); }
        catch (Exception failure) { throw new IllegalStateException("关闭RAG取消注册失败", failure); }
    }

    private static void restoreContext(Map<String, String> context) {
        if (context == null) MDC.clear(); else MDC.setContextMap(context);
    }
}
