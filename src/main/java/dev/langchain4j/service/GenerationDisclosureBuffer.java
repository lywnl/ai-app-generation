package dev.langchain4j.service;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Queue;

/** 按模型事件到达顺序串行发布 generation 内容。 */
final class GenerationDisclosureBuffer {

    private final Object monitor = new Object();
    private final Queue<Runnable> disclosures = new ArrayDeque<>();
    private boolean publishing;
    private int publishingPauseCount;

    void pausePublishing() {
        synchronized (monitor) {
            publishingPauseCount++;
        }
    }

    void resumePublishing() {
        synchronized (monitor) {
            if (publishingPauseCount <= 0) {
                throw new IllegalStateException("披露发布未处于暂停状态");
            }
            publishingPauseCount--;
        }
        publishReady();
    }

    void enqueueResolved(Runnable action) {
        synchronized (monitor) {
            disclosures.add(action);
        }
        publishReady();
    }

    void enqueueResolvedBatch(Collection<Runnable> actions) {
        synchronized (monitor) {
            for (Runnable action : actions) {
                disclosures.add(action);
            }
        }
        publishReady();
    }

    private void publishReady() {
        synchronized (monitor) {
            if (publishing || publishingPauseCount > 0) {
                return;
            }
            publishing = true;
        }
        publishLoop();
    }

    private void publishLoop() {
        Throwable failure = null;
        while (true) {
            Runnable action;
            synchronized (monitor) {
                if (publishingPauseCount > 0) {
                    publishing = false;
                    rethrow(failure);
                    return;
                }
                if (disclosures.isEmpty()) {
                    publishing = false;
                    rethrow(failure);
                    return;
                }
                action = disclosures.remove();
            }
            try {
                action.run();
            } catch (RuntimeException | Error actionFailure) {
                if (failure == null) {
                    failure = actionFailure;
                } else if (failure != actionFailure) {
                    failure.addSuppressed(actionFailure);
                }
            }
        }
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
