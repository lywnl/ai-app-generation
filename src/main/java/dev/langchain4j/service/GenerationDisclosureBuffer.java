package dev.langchain4j.service;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Queue;

/** 按模型事件到达顺序串行发布已完成安全判定的 generation 内容。 */
final class GenerationDisclosureBuffer {

    private final Object monitor = new Object();
    private final Queue<Disclosure> disclosures = new ArrayDeque<>();
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

    Disclosure enqueueResolved(Runnable action) {
        Disclosure disclosure = new Disclosure(action, action);
        synchronized (monitor) {
            disclosures.add(disclosure);
        }
        publishReady();
        return disclosure;
    }

    void enqueueResolvedBatch(Collection<Runnable> actions) {
        synchronized (monitor) {
            for (Runnable action : actions) {
                disclosures.add(new Disclosure(action, action));
            }
        }
        publishReady();
    }

    Disclosure enqueuePending(Runnable delayedAction) {
        Disclosure disclosure = new Disclosure(null, delayedAction);
        synchronized (monitor) {
            disclosures.add(disclosure);
        }
        return disclosure;
    }

    void resolve(Disclosure disclosure, Runnable action) {
        synchronized (monitor) {
            disclosure.action = action;
        }
        publishReady();
    }

    void resolveDelayed(Disclosure disclosure) {
        resolve(disclosure, disclosure.delayedAction);
    }

    void remove(Disclosure disclosure) {
        synchronized (monitor) {
            disclosures.remove(disclosure);
        }
        publishReady();
    }

    void removeAll(Collection<Disclosure> removed) {
        synchronized (monitor) {
            disclosures.removeAll(removed);
        }
        publishReady();
    }

    void clear() {
        synchronized (monitor) {
            disclosures.clear();
        }
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
                Disclosure head = disclosures.peek();
                if (head == null || head.action == null) {
                    publishing = false;
                    rethrow(failure);
                    return;
                }
                action = disclosures.remove().action;
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

    static final class Disclosure {

        private final Runnable delayedAction;
        private Runnable action;

        private Disclosure(Runnable action, Runnable delayedAction) {
            this.action = action;
            this.delayedAction = delayedAction;
        }
    }
}
