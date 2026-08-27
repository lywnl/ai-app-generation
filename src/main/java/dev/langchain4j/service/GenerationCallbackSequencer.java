package dev.langchain4j.service;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;

/** 按 provider 回调到达顺序串行提交单个 generation 的状态变更。 */
final class GenerationCallbackSequencer {

    private final Object monitor = new Object();
    private final Queue<Runnable> actions = new ArrayDeque<>();
    private final Runnable beforeBatch;
    private final Runnable beforeRelease;
    private final Runnable afterBatch;
    private final Queue<Runnable> afterBatchActions = new ArrayDeque<>();
    private boolean running;
    private boolean afterBatchRunning;

    GenerationCallbackSequencer() {
        this(() -> { }, () -> { }, () -> { });
    }

    GenerationCallbackSequencer(
            Runnable beforeBatch, Runnable afterBatch) {
        this(beforeBatch, () -> { }, afterBatch);
    }

    GenerationCallbackSequencer(
            Runnable beforeBatch,
            Runnable beforeRelease,
            Runnable afterBatch) {
        this.beforeBatch = beforeBatch;
        this.beforeRelease = beforeRelease;
        this.afterBatch = afterBatch;
    }

    void submit(Runnable action) {
        boolean shouldRun;
        synchronized (monitor) {
            actions.add(action);
            shouldRun = !running;
            if (shouldRun) {
                running = true;
            }
        }
        if (shouldRun) {
            runBatches();
        }
    }

    /**
     * 将动作安排在当前回调批次的结束钩子全部完成后执行。
     *
     * <p>内部恢复请求必须在 generation 披露队列真正恢复后再提交，
     * 否则监听器异常可能晚于恢复模型请求启动。</p>
     */
    void submitAfterBatch(Runnable action) {
        Objects.requireNonNull(action, "批次结束动作不能为空");
        boolean runImmediately;
        synchronized (monitor) {
            runImmediately = !running && !afterBatchRunning;
            if (!runImmediately) {
                afterBatchActions.add(action);
            }
        }
        if (runImmediately) {
            action.run();
        }
    }

    private void runBatches() {
        Throwable failure = null;
        int completedBatches = 0;
        while (true) {
            boolean batchStarted = false;
            try {
                beforeBatch.run();
                batchStarted = true;
                failure = mergeFailure(failure, runQueuedActions());
            } catch (RuntimeException | Error batchFailure) {
                failure = mergeFailure(failure, batchFailure);
            }
            if (!batchStarted) {
                releaseOwnership();
                break;
            }
            try {
                beforeRelease.run();
            } catch (RuntimeException | Error releaseFailure) {
                failure = mergeFailure(failure, releaseFailure);
            }
            completedBatches++;
            if (releaseOwnershipWhenQueueEmpty()) {
                break;
            }
        }
        try {
            for (int index = 0; index < completedBatches; index++) {
                try {
                    afterBatch.run();
                } catch (RuntimeException | Error afterFailure) {
                    failure = mergeFailure(failure, afterFailure);
                }
            }
            while (true) {
                failure = mergeFailure(failure, runAfterBatchActions());
                synchronized (monitor) {
                    if (afterBatchActions.isEmpty()) {
                        afterBatchRunning = false;
                        break;
                    }
                }
            }
        } finally {
            synchronized (monitor) {
                if (afterBatchRunning) {
                    afterBatchRunning = false;
                }
            }
        }
        rethrow(failure);
    }

    private Throwable runAfterBatchActions() {
        Throwable failure = null;
        while (true) {
            Runnable action;
            synchronized (monitor) {
                action = afterBatchActions.poll();
            }
            if (action == null) {
                return failure;
            }
            try {
                action.run();
            } catch (RuntimeException | Error actionFailure) {
                failure = mergeFailure(failure, actionFailure);
            }
        }
    }

    private Throwable runQueuedActions() {
        Throwable failure = null;
        while (true) {
            Runnable action;
            synchronized (monitor) {
                action = actions.poll();
                if (action == null) {
                    return failure;
                }
            }
            try {
                action.run();
            } catch (RuntimeException | Error actionFailure) {
                failure = mergeFailure(failure, actionFailure);
            }
        }
    }

    private boolean releaseOwnershipWhenQueueEmpty() {
        synchronized (monitor) {
            if (!actions.isEmpty()) {
                return false;
            }
            running = false;
            afterBatchRunning = true;
            return true;
        }
    }

    private void releaseOwnership() {
        synchronized (monitor) {
            running = false;
        }
    }

    private Throwable mergeFailure(
            Throwable current, Throwable added) {
        if (current == null) {
            return added;
        }
        if (added != null && current != added) {
            current.addSuppressed(added);
        }
        return current;
    }

    private void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }
}
