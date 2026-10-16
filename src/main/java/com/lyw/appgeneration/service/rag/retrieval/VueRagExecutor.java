package com.lyw.appgeneration.service.rag.retrieval;

import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.service.rag.model.RagDocumentKind;
import com.lyw.appgeneration.service.rag.monitor.VueRagMetricsCollector;
import com.lyw.appgeneration.service.rag.monitor.VueRagStage;
import jakarta.annotation.PreDestroy;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Vue RAG 专用有界执行器。请求在入口等待，工作线程只执行外部调用及非阻塞回调。
 */
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

    /** 等待许可时尚未提交子任务；不设置新的检索总超时。 */
    public RequestScope openRequest() {
        long start = System.nanoTime();
        try {
            if (closing.get()) {
                throw new RejectedExecutionException("Vue RAG 执行器正在关闭");
            }
            requests.acquire();
            if (closing.get()) {
                requests.release();
                throw new RejectedExecutionException("Vue RAG 执行器正在关闭");
            }
            return new RequestScope(MDC.getCopyOfContextMap());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw cancelled(interrupted);
        } finally {
            metrics.recordAdmissionWait(Duration.ofNanos(System.nanoTime() - start));
        }
    }

    /** 等待已接纳请求完整收尾后才关闭线程池，不在关闭过程中拒绝它们的后续依赖任务。 */
    @Override
    @PreDestroy
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        requests.acquireUninterruptibly(maxConcurrentRequests);
        try {
            workers.close();
        } finally {
            // 唤醒极少数在关闭检查与 acquire 之间进入等待的调用方，让它们明确收到关闭异常。
            requests.release(maxConcurrentRequests);
        }
    }

    private static CancellationException cancelled(InterruptedException cause) {
        CancellationException cancellation = new CancellationException("Vue RAG 请求已中断");
        cancellation.initCause(cause);
        return cancellation;
    }

    public final class RequestScope implements AutoCloseable {

        private final Map<String, String> context;
        private int pendingTasks;
        private int submittedTasks;
        private boolean closed;
        private boolean released;

        private RequestScope(Map<String, String> context) {
            this.context = context;
        }

        public <T> CompletableFuture<T> submit(VueRagStage stage, RagDocumentKind kind,
                                                Supplier<T> operation) {
            synchronized (this) {
                if (closed) {
                    return CompletableFuture.failedFuture(new CancellationException("Vue RAG 请求已结束"));
                }
                if (submittedTasks >= MAX_TASKS_PER_REQUEST) {
                    return CompletableFuture.failedFuture(new IllegalStateException("单次 Vue RAG 请求任务数超过 7"));
                }
                submittedTasks++;
                pendingTasks++;
            }
            CompletableFuture<T> result = new CompletableFuture<>();
            long enqueued = System.nanoTime();
            try {
                workers.execute(() -> runTask(stage, kind, operation, result, enqueued));
            } catch (RuntimeException | Error failure) {
                try {
                    result.completeExceptionally(failure);
                } finally {
                    taskFinished();
                }
            }
            return result;
        }

        private <T> void runTask(VueRagStage stage, RagDocumentKind kind, Supplier<T> operation,
                                  CompletableFuture<T> result, long enqueued) {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                restoreContext(context);
                metrics.recordExecutorQueueDuration(stage, kind,
                        Duration.ofNanos(System.nanoTime() - enqueued));
                if (isClosed() || result.isCancelled()) {
                    result.completeExceptionally(new CancellationException("Vue RAG 请求已结束"));
                    return;
                }
                long start = System.nanoTime();
                T value;
                try {
                    if (stage == VueRagStage.EMBEDDING) {
                        metrics.recordEmbeddingCall();
                    }
                    value = operation.get();
                } finally {
                    metrics.recordStageDuration(stage, kind, Duration.ofNanos(System.nanoTime() - start));
                }
                // complete 会同步触发依赖回调，所以调用计时必须先结束，避免把后续阶段算入本阶段。
                result.complete(value);
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            } finally {
                restoreContext(previous);
                taskFinished();
            }
        }

        /** 只能由请求调用线程使用；依赖组合通过 thenCompose/thenCombine 完成。 */
        public <T> T await(CompletableFuture<T> result) {
            try {
                return result.get();
            } catch (InterruptedException interrupted) {
                close();
                Thread.currentThread().interrupt();
                throw cancelled(interrupted);
            } catch (ExecutionException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new CompletionException(cause);
            }
        }

        private synchronized boolean isClosed() {
            return closed;
        }

        private synchronized void taskFinished() {
            pendingTasks--;
            releaseIfFinished();
        }

        @Override
        public synchronized void close() {
            closed = true;
            releaseIfFinished();
        }

        private void releaseIfFinished() {
            // Future 取消或调用方中断不等于外部调用停止，许可必须等到所有物理任务退出。
            if (closed && pendingTasks == 0 && !released) {
                released = true;
                requests.release();
            }
        }
    }

    private static void restoreContext(Map<String, String> context) {
        if (context == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }
}
