package dev.langchain4j.service;

import java.util.ArrayDeque;
import java.util.Queue;

/** 按 provider 回调到达顺序串行提交单个 generation 的状态变更。 */
final class GenerationCallbackSequencer {

    private final Object monitor = new Object();
    private final Queue<Runnable> actions = new ArrayDeque<>();
    private final Runnable beforeBatch;
    private final Runnable beforeRelease;
    private final Runnable afterBatch;
    private boolean running;

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
        for (int index = 0; index < completedBatches; index++) {
            try {
                afterBatch.run();
            } catch (RuntimeException | Error afterFailure) {
                failure = mergeFailure(failure, afterFailure);
            }
        }
        rethrow(failure);
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
        if (current != added) {
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
