package dev.langchain4j.service;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * 统一模型请求的门禁、代次认领与 SDK 启动边界。
 *
 * <p>Controller 只负责短临界区状态转换；门禁、记忆读取、continuation
 * 调度以及真实模型启动均在 Controller monitor 之外执行。</p>
 */
final class GenerationAwareModelRequestOrchestrator {

    private final StreamingRequestController requestController;
    private final ModelRequestGate modelRequestGate;
    private final ModelRequestGate.ContinuationGate continuationGate;

    GenerationAwareModelRequestOrchestrator(
            StreamingRequestController requestController,
            ModelRequestGate modelRequestGate,
            ModelRequestGate.ContinuationGate continuationGate) {
        this.requestController = Objects.requireNonNull(
                requestController, "流式请求控制器不能为空");
        if ((modelRequestGate == null) != (continuationGate == null)) {
            throw new IllegalArgumentException(
                    "模型请求门禁和回合原子门必须同时安装");
        }
        this.modelRequestGate = modelRequestGate;
        this.continuationGate = continuationGate;
    }

    void submit(Submission submission) {
        Objects.requireNonNull(submission, "模型请求提交不能为空");
        if (!isSourceActive(submission)) {
            closePendingAsCancelled(submission);
            return;
        }
        if (submission.sourceKind() == SourceKind.RECOVERY) {
            awaitRecoveryReadiness(submission);
            return;
        }
        submitReady(submission);
    }

    private void awaitRecoveryReadiness(Submission submission) {
        CompletionStage<StreamingRequestController.RecoveryReadiness>
                readiness;
        try {
            readiness = requestController.awaitRecoveryReadiness(
                    submission.sourceGeneration());
        } catch (RuntimeException exception) {
            failSource(submission, new IllegalStateException(
                    "恢复请求等待旧回调闭合失败", exception));
            return;
        }
        readiness.whenComplete((result, failure) ->
                finishRecoveryReadiness(submission, result, failure));
    }

    private void finishRecoveryReadiness(
            Submission submission,
            StreamingRequestController.RecoveryReadiness readiness,
            Throwable failure) {
        if (failure != null) {
            failSource(submission, new IllegalStateException(
                    "恢复请求等待旧回调闭合失败", failure));
            return;
        }
        if (readiness == StreamingRequestController
                .RecoveryReadiness.READY) {
            submitReady(submission);
            return;
        }
        if (readiness == StreamingRequestController
                .RecoveryReadiness.CANCELLED_OR_TERMINATED) {
            closePendingAsCancelled(submission);
            return;
        }
        submission.closePending();
    }

    private void submitReady(Submission submission) {
        if (!isSourceActive(submission)) {
            closePendingAsCancelled(submission);
            return;
        }
        if (modelRequestGate == null) {
            submitWithoutGate(submission);
            return;
        }
        prepareWithGate(submission);
    }

    private void submitWithoutGate(Submission submission) {
        if (submission.gateRequired()) {
            failSource(submission, new IllegalStateException(
                    "模型恢复请求必须安装模型请求门禁"));
            return;
        }
        List<ChatMessage> messages;
        try {
            messages = List.copyOf(submission.directMessages().get());
        } catch (RuntimeException exception) {
            failSource(submission, new IllegalStateException(
                    "模型请求消息读取失败", exception));
            return;
        }
        if (!isSourceActive(submission)) {
            closePendingAsCancelled(submission);
            return;
        }
        claimAndStart(submission, messages);
    }

    private void prepareWithGate(Submission submission) {
        CompletionStage<ModelRequestGate.Decision> preparation;
        try {
            preparation = modelRequestGate.prepare(submission.gateRequest());
        } catch (RuntimeException exception) {
            failSource(submission, new IllegalStateException(
                    "模型请求门禁准备失败", exception));
            return;
        }
        if (preparation == null) {
            failSource(submission, new IllegalStateException(
                    "模型请求门禁未返回准备结果"));
            return;
        }
        observePreparation(submission, preparation);
    }

    private void observePreparation(
            Submission submission,
            CompletionStage<ModelRequestGate.Decision> preparation) {
        if (!isSourceActive(submission)) {
            closePendingAsCancelled(submission);
            return;
        }
        CompletionStage<ModelRequestGate.DispatchStatus> dispatch;
        try {
            dispatch = modelRequestGate.onPrepared(
                    preparation,
                    (decision, failure) -> finishPreparation(
                            submission, decision, failure));
        } catch (RuntimeException exception) {
            failSource(submission, new IllegalStateException(
                    "模型请求门禁完成回调注册失败", exception));
            return;
        }
        if (dispatch == null) {
            failSource(submission, new IllegalStateException(
                    "模型请求门禁未返回完成回调调度结果"));
            return;
        }
        dispatch.whenComplete((status, failure) ->
                finishDispatch(submission, status, failure));
    }

    private void finishPreparation(
            Submission submission,
            ModelRequestGate.Decision decision,
            Throwable failure) {
        if (!isSourceActive(submission)) {
            closePendingAsCancelled(submission);
            return;
        }
        if (failure != null) {
            failSource(submission, new IllegalStateException(
                    "模型请求门禁执行失败", failure));
            return;
        }
        if (decision == null) {
            failSource(submission, new IllegalStateException(
                    "模型请求门禁返回空决策"));
            return;
        }
        if (decision.status() == ModelRequestGate.Status.ALLOWED) {
            dispatchAllowed(submission, decision.messages());
            return;
        }
        if (decision.status() == ModelRequestGate.Status.CANCELLED) {
            cancelSource(submission);
            return;
        }
        failSource(submission, new ModelRequestGateException(
                submission.rejectionStage(),
                decision.status(),
                decision.safeMessage()));
    }

    private void finishDispatch(
            Submission submission,
            ModelRequestGate.DispatchStatus status,
            Throwable failure) {
        if (failure != null) {
            failSource(submission, new IllegalStateException(
                    "模型请求门禁完成回调执行失败", failure));
            return;
        }
        if (status != ModelRequestGate.DispatchStatus.DISPATCHED) {
            failSource(submission, new IllegalStateException(
                    "模型请求门禁完成回调调度失败"));
        }
    }

    private void dispatchAllowed(
            Submission submission, List<ChatMessage> messages) {
        boolean accepted;
        try {
            accepted = continuationGate.tryRun(
                    () -> claimAndStart(submission, messages));
        } catch (RuntimeException exception) {
            failSource(submission, exception);
            return;
        }
        if (!accepted) {
            cancelSource(submission);
        }
    }

    private void claimAndStart(
            Submission submission, List<ChatMessage> messages) {
        if (!submission.tryOwnStart()) {
            return;
        }
        StreamingRequestController.ModelRequestClaimResult claimResult =
                submission.sourceKind() == SourceKind.RECOVERY
                        ? requestController.claimRecoveryModelRequestResult(
                                submission.sourceGeneration())
                        : requestController.claimModelRequestResult(
                                submission.sourceGeneration());
        if (claimResult.status() == StreamingRequestController
                .ModelRequestClaimStatus.LOOP_LIMIT_EXCEEDED) {
            closeOwnedWithHandler(
                    submission, submission.loopLimitHandler());
            requestController.dispatchClaimedTermination();
            return;
        }
        StreamingRequestController.ModelRequestClaim claim =
                claimResult.claim();
        if (claim == null) {
            closeOwnedAsCancelled(submission);
            return;
        }
        if (!requestController.isCurrentGeneration(claim.generation())) {
            closeOwnedAsCancelled(submission);
            return;
        }
        try {
            Runnable sdkStart = submission.requestStarter().prepare(
                    List.copyOf(messages), claim.generation());
            if (sdkStart == null) {
                throw new IllegalStateException("模型请求启动器未返回 SDK 启动动作");
            }
            if (!requestController.tryCommitModelRequestStart(claim)) {
                closeOwnedAsCancelled(submission);
                return;
            }
            submission.commitStart();
            submission.startCommittedHandler().accept(claim.generation());
            sdkStart.run();
        } catch (RuntimeException exception) {
            failClaimedGeneration(submission, claim.generation(), exception);
        }
    }

    private boolean isSourceActive(Submission submission) {
        return submission.sourceKind() == SourceKind.RECOVERY
                ? requestController.isRecoverySourceGeneration(
                        submission.sourceGeneration())
                : requestController.isCurrentGeneration(
                        submission.sourceGeneration());
    }

    private void cancelSource(Submission submission) {
        if (!submission.closePending()) {
            return;
        }
        if (submission.sourceKind() == SourceKind.RECOVERY) {
            requestController.cancelIfRecoverySourceGeneration(
                    submission.sourceGeneration());
        } else {
            requestController.cancelIfCurrentGeneration(
                    submission.sourceGeneration());
        }
        submission.cancellationHandler().run();
    }

    private void closePendingAsCancelled(Submission submission) {
        if (submission.closePending()) {
            submission.cancellationHandler().run();
        }
    }

    private void closeOwnedAsCancelled(Submission submission) {
        closeOwnedWithHandler(
                submission, submission.cancellationHandler());
    }

    private void closeOwnedWithHandler(
            Submission submission, Runnable handler) {
        if (submission.closeOwned()) {
            handler.run();
        }
    }

    private void failSource(Submission submission, Throwable failure) {
        if (!submission.closePending()) {
            return;
        }
        boolean claimed = submission.sourceKind() == SourceKind.RECOVERY
                ? requestController.claimRecoverySourceFailure(
                        submission.sourceGeneration())
                : requestController.claimErrorCompletion(
                        submission.sourceGeneration());
        if (claimed) {
            submission.failureHandler().accept(failure);
        } else {
            submission.cancellationHandler().run();
        }
    }

    private void failClaimedGeneration(
            Submission submission, long claimedGeneration,
            Throwable failure) {
        if (!submission.closeOwned()) {
            return;
        }
        if (requestController.claimErrorCompletion(claimedGeneration)) {
            submission.failureHandler().accept(failure);
        } else {
            submission.cancellationHandler().run();
        }
    }

    enum SourceKind {
        CURRENT,
        RECOVERY
    }

    static Submission initial(
            ModelRequestGate.Request gateRequest,
            Supplier<List<ChatMessage>> directMessages,
            Consumer<Throwable> failureHandler,
            RequestStarter requestStarter) {
        return new Submission(
                0L,
                SourceKind.CURRENT,
                ModelRequestGateException.Stage.INITIAL,
                directMessages,
                gateRequest,
                false,
                () -> { },
                () -> { },
                ignored -> { },
                failureHandler,
                requestStarter);
    }

    static Submission continuation(
            long sourceGeneration,
            ModelRequestGate.Request gateRequest,
            Supplier<List<ChatMessage>> directMessages,
            Consumer<Throwable> failureHandler,
            RequestStarter requestStarter) {
        return new Submission(
                sourceGeneration,
                SourceKind.CURRENT,
                ModelRequestGateException.Stage.CONTINUATION,
                directMessages,
                gateRequest,
                false,
                () -> { },
                () -> { },
                ignored -> { },
                failureHandler,
                requestStarter);
    }

    static Submission recovery(
            long sourceGeneration,
            ModelRequestGate.Request gateRequest,
            Supplier<List<ChatMessage>> directMessages,
            Runnable loopLimitHandler,
            Consumer<Throwable> failureHandler,
            RequestStarter requestStarter) {
        return recovery(
                sourceGeneration,
                gateRequest,
                directMessages,
                loopLimitHandler,
                loopLimitHandler,
                ignored -> { },
                failureHandler,
                requestStarter);
    }

    static Submission recovery(
            long sourceGeneration,
            ModelRequestGate.Request gateRequest,
            Supplier<List<ChatMessage>> directMessages,
            Runnable loopLimitHandler,
            Runnable cancellationHandler,
            LongConsumer startCommittedHandler,
            Consumer<Throwable> failureHandler,
            RequestStarter requestStarter) {
        return new Submission(
                sourceGeneration,
                SourceKind.RECOVERY,
                ModelRequestGateException.Stage.CONTINUATION,
                directMessages,
                gateRequest,
                true,
                loopLimitHandler,
                cancellationHandler,
                startCommittedHandler,
                failureHandler,
                requestStarter);
    }

    static final class Submission {

        private final long sourceGeneration;
        private final SourceKind sourceKind;
        private final ModelRequestGateException.Stage rejectionStage;
        private final Supplier<List<ChatMessage>> directMessages;
        private final ModelRequestGate.Request gateRequest;
        private final boolean gateRequired;
        private final Runnable loopLimitHandler;
        private final Runnable cancellationHandler;
        private final LongConsumer startCommittedHandler;
        private final Consumer<Throwable> failureHandler;
        private final RequestStarter requestStarter;
        private final AtomicReference<SubmissionState> state =
                new AtomicReference<>(SubmissionState.PENDING);

        private Submission(
                long sourceGeneration,
                SourceKind sourceKind,
                ModelRequestGateException.Stage rejectionStage,
                Supplier<List<ChatMessage>> directMessages,
                ModelRequestGate.Request gateRequest,
                boolean gateRequired,
                Runnable loopLimitHandler,
                Runnable cancellationHandler,
                LongConsumer startCommittedHandler,
                Consumer<Throwable> failureHandler,
                RequestStarter requestStarter) {
            if (sourceGeneration < 0L) {
                throw new IllegalArgumentException(
                        "模型请求来源代次不能为负数");
            }
            this.sourceGeneration = sourceGeneration;
            this.sourceKind = Objects.requireNonNull(
                    sourceKind, "模型请求来源类型不能为空");
            this.rejectionStage = Objects.requireNonNull(
                    rejectionStage, "模型请求门禁阶段不能为空");
            this.directMessages = Objects.requireNonNull(
                    directMessages, "无门禁消息读取器不能为空");
            this.gateRequest = gateRequest;
            this.gateRequired = gateRequired;
            this.loopLimitHandler = Objects.requireNonNull(
                    loopLimitHandler, "模型循环上限处理器不能为空");
            this.cancellationHandler = Objects.requireNonNull(
                    cancellationHandler, "模型请求取消处理器不能为空");
            this.startCommittedHandler = Objects.requireNonNull(
                    startCommittedHandler, "模型启动提交处理器不能为空");
            this.failureHandler = Objects.requireNonNull(
                    failureHandler, "模型请求失败处理器不能为空");
            this.requestStarter = Objects.requireNonNull(
                    requestStarter, "模型请求启动器不能为空");
        }

        private long sourceGeneration() {
            return sourceGeneration;
        }

        private SourceKind sourceKind() {
            return sourceKind;
        }

        private ModelRequestGateException.Stage rejectionStage() {
            return rejectionStage;
        }

        private Supplier<List<ChatMessage>> directMessages() {
            return directMessages;
        }

        private ModelRequestGate.Request gateRequest() {
            return gateRequest;
        }

        private boolean gateRequired() {
            return gateRequired;
        }

        private Runnable loopLimitHandler() {
            return loopLimitHandler;
        }

        private Runnable cancellationHandler() {
            return cancellationHandler;
        }

        private Consumer<Throwable> failureHandler() {
            return failureHandler;
        }

        private LongConsumer startCommittedHandler() {
            return startCommittedHandler;
        }

        private RequestStarter requestStarter() {
            return requestStarter;
        }

        private boolean tryOwnStart() {
            return state.compareAndSet(
                    SubmissionState.PENDING,
                    SubmissionState.START_OWNED);
        }

        private void commitStart() {
            if (!state.compareAndSet(
                    SubmissionState.START_OWNED,
                    SubmissionState.START_COMMITTED)) {
                throw new IllegalStateException(
                        "模型请求提交不处于启动认领状态");
            }
        }

        private boolean closePending() {
            return state.compareAndSet(
                    SubmissionState.PENDING,
                    SubmissionState.CLOSED);
        }

        private boolean closeOwned() {
            SubmissionState current = state.get();
            while (current == SubmissionState.START_OWNED
                    || current == SubmissionState.START_COMMITTED) {
                if (state.compareAndSet(current, SubmissionState.CLOSED)) {
                    return true;
                }
                current = state.get();
            }
            return false;
        }
    }

    private enum SubmissionState {
        PENDING,
        START_OWNED,
        START_COMMITTED,
        CLOSED
    }

    @FunctionalInterface
    interface RequestStarter {

        /** 锁外完成请求准备，并返回只包含真实 SDK 调用的启动动作。 */
        Runnable prepare(List<ChatMessage> messages, long generation);
    }
}
