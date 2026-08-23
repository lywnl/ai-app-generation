package dev.langchain4j.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** 将一次状态动作产生的 generation 信号原子追加到共享披露队列。 */
final class GenerationSignalPublisher
        implements Consumer<GenerationStreamSignal> {

    private final GenerationDisclosureBuffer disclosureBuffer;
    private final Consumer<GenerationStreamSignal> listener;
    private final ThreadLocal<List<Runnable>> activeBatch =
            new ThreadLocal<>();

    GenerationSignalPublisher(
            GenerationDisclosureBuffer disclosureBuffer,
            Consumer<GenerationStreamSignal> listener) {
        this.disclosureBuffer = Objects.requireNonNull(
                disclosureBuffer, "generation 披露缓冲不能为空");
        this.listener = Objects.requireNonNull(
                listener, "generation 信号监听器不能为空");
    }

    @Override
    public void accept(GenerationStreamSignal signal) {
        GenerationStreamSignal checked = Objects.requireNonNull(
                signal, "generation 信号不能为空");
        List<Runnable> batch = activeBatch.get();
        if (batch != null) {
            batch.add(() -> listener.accept(checked));
            return;
        }
        enqueueBatch(List.of(() -> listener.accept(checked)));
    }

    void publishAtomically(Runnable action) {
        publishAtomically(action, () -> { });
    }

    void publishAtomically(
            Runnable action, Runnable afterPublication) {
        Objects.requireNonNull(action, "generation 批次动作不能为空");
        Objects.requireNonNull(
                afterPublication, "generation 批次尾部动作不能为空");
        List<Runnable> parentBatch = activeBatch.get();
        if (parentBatch != null) {
            action.run();
            parentBatch.add(afterPublication);
            return;
        }
        List<Runnable> batch = new ArrayList<>();
        activeBatch.set(batch);
        Throwable failure = null;
        try {
            action.run();
            batch.add(afterPublication);
        } catch (RuntimeException | Error actionFailure) {
            failure = actionFailure;
        } finally {
            activeBatch.remove();
        }
        try {
            enqueueBatch(batch);
        } catch (RuntimeException | Error enqueueFailure) {
            failure = mergeFailure(failure, enqueueFailure);
        }
        rethrow(failure);
    }

    void pausePublishing() {
        disclosureBuffer.pausePublishing();
    }

    void resumePublishing() {
        disclosureBuffer.resumePublishing();
    }

    private void enqueueBatch(List<Runnable> batch) {
        disclosureBuffer.enqueueResolvedBatch(batch);
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
