package dev.langchain4j.service;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** 测试域显式受管派发门禁，避免测试重新依赖生产默认内联语义。 */
final class ManagedModelRequestGate implements ModelRequestGate, AutoCloseable {

    private final Function<Request, CompletionStage<Decision>> preparation;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    ManagedModelRequestGate(
            Function<Request, CompletionStage<Decision>> preparation) {
        this.preparation = Objects.requireNonNull(
                preparation, "测试门禁准备函数不能为空");
    }

    @Override
    public CompletionStage<Decision> prepare(Request request) {
        return preparation.apply(request);
    }

    @Override
    public CompletionStage<DispatchStatus> onPrepared(
            CompletionStage<Decision> preparation,
            BiConsumer<Decision, Throwable> completion) {
        CompletableFuture<DispatchStatus> dispatch = new CompletableFuture<>();
        preparation.whenComplete((decision, failure) -> {
            try {
                executor.execute(() -> {
                    try {
                        completion.accept(decision, failure);
                        dispatch.complete(DispatchStatus.DISPATCHED);
                    } catch (Throwable exception) {
                        dispatch.completeExceptionally(exception);
                    }
                });
            } catch (RejectedExecutionException exception) {
                dispatch.complete(DispatchStatus.REJECTED);
            }
        });
        return dispatch;
    }

    void awaitIdle() throws Exception {
        executor.submit(() -> { }).get(2, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
