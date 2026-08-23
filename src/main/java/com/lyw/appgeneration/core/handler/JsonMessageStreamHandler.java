package com.lyw.appgeneration.core.handler;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.lyw.appgeneration.ai.model.message.AiResponseMessage;
import com.lyw.appgeneration.ai.model.message.InternalOutputRecoveryMessage;
import com.lyw.appgeneration.ai.model.message.InternalOutputRollbackMessage;
import com.lyw.appgeneration.ai.model.message.StreamMessage;
import com.lyw.appgeneration.ai.model.message.StreamMessageTypeEnum;
import com.lyw.appgeneration.ai.model.message.ToolArgumentDeltaMessage;
import com.lyw.appgeneration.ai.model.message.ToolArgumentMessage;
import com.lyw.appgeneration.ai.model.message.ToolExecutedMessage;
import com.lyw.appgeneration.ai.model.message.ToolRequestMessage;
import com.lyw.appgeneration.ai.model.message.TrustedToolDisplayMessage;
import com.lyw.appgeneration.ai.tools.BaseTool;
import com.lyw.appgeneration.ai.tools.FileToolBudgetGuard;
import com.lyw.appgeneration.ai.tools.VueToolExecutionFact;
import com.lyw.appgeneration.core.builder.VueBuildPhase;
import com.lyw.appgeneration.manger.ToolManager;
import dev.langchain4j.service.ToolLoopTerminationProtocol.ControlledTerminationReason;
import dev.langchain4j.service.GenerationStreamSignal;
import dev.langchain4j.service.tool.ToolExecution;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** 维护 Vue 正文/工具事件顺序，并把所有信号汇合为唯一稳定终态。 */
@Slf4j
@Component
public final class JsonMessageStreamHandler {

    private static final Set<String> CLIENT_REDACTED_FILE_TOOLS = Set.of(
            "readFile", "readDir", "writeFile", "modifyFile", "deleteFile");

    static final String SUCCESS_MESSAGE = VueTurnFinalizer.SUCCESS_MESSAGE;
    static final String BUILD_FAILED_MESSAGE = VueTurnFinalizer.BUILD_FAILED_MESSAGE;
    static final String SYSTEM_ERROR_MESSAGE = VueTurnFinalizer.SYSTEM_ERROR_MESSAGE;
    static final String PROTOCOL_MESSAGE = VueTurnFinalizer.PROTOCOL_MESSAGE;
    static final String SCOPE_PROTOCOL_MESSAGE = VueTurnFinalizer.SCOPE_PROTOCOL_MESSAGE;
    static final String LOOP_LIMIT_MESSAGE = VueTurnFinalizer.LOOP_LIMIT_MESSAGE;
    static final String TIMEOUT_MESSAGE = VueTurnFinalizer.TIMEOUT_MESSAGE;
    static final String RESOURCE_LIMIT_MESSAGE = VueTurnFinalizer.RESOURCE_LIMIT_MESSAGE;
    static final String CANCELLED_MESSAGE = VueTurnFinalizer.CANCELLED_MESSAGE;
    private static final int TERMINAL_RESERVE_CODE_POINTS =
            VueTurnFinalizer.terminalReserveCodePoints();

    private final ToolManager toolManager;
    private final VueTurnFinalizer finalizer;
    private final VueTurnCancellationCoordinator cancellationCoordinator;
    private final Scheduler deadlineScheduler;

    @Autowired
    public JsonMessageStreamHandler(
            ToolManager toolManager,
            VueTurnFinalizer finalizer,
            VueTurnCancellationCoordinator cancellationCoordinator) {
        this.toolManager = toolManager;
        this.finalizer = finalizer;
        this.cancellationCoordinator = cancellationCoordinator;
        this.deadlineScheduler = Schedulers.parallel();
    }

    JsonMessageStreamHandler(
            ToolManager toolManager,
            VueTurnFinalizer finalizer,
            VueTurnCancellationCoordinator cancellationCoordinator,
            Scheduler deadlineScheduler) {
        this.toolManager = toolManager;
        this.finalizer = finalizer;
        this.cancellationCoordinator = cancellationCoordinator;
        this.deadlineScheduler = deadlineScheduler;
    }

    public Flux<GenerationStreamEvent> handle(
            Flux<String> originFlux, VueTurnContext context) {
        return Flux.defer(() -> {
            VueTurnTranscriptAccumulator transcript =
                    new VueTurnTranscriptAccumulator(
                            context.budgetSession(),
                            TERMINAL_RESERVE_CODE_POINTS);
            Set<String> seenToolIds = new HashSet<>();
            List<VueToolExecutionFact> facts = new CopyOnWriteArrayList<>();
            context.registerOutputSafetySealer(() ->
                    transcript.containsReservedMarkerInAiText()
                            ? VueTurnContext.OutputSafetySeal.reserved(
                            VueTurnMemoryProjection.project(
                                    List.copyOf(facts),
                                    VueTurnOutcome.TurnOutcomeType
                                            .PROTOCOL_ERROR))
                            : VueTurnContext.OutputSafetySeal.safe());
            AtomicBoolean terminalDelivered = new AtomicBoolean();
            Flux<GenerationStreamEvent> body = originFlux.concatMap(chunk ->
                            handleJsonMessageChunk(
                                    chunk, transcript, facts,
                                    seenToolIds, context))
                    .filter(java.util.Objects::nonNull);
            AtomicBoolean deadlineReached = new AtomicBoolean();
            AtomicBoolean deleteTakeoverReached = new AtomicBoolean();
            Mono<Long> deadline = Mono.delay(
                            context.remainingUntilDeadline(), deadlineScheduler)
                    .doOnNext(ignored -> deadlineReached.set(true));
            Mono<VueTurnContext.DeleteTakeoverRequest> deleteTakeover =
                    context.deleteTakeoverSignal()
                            .doOnNext(ignored -> deleteTakeoverReached.set(true))
                            .cache();
            Flux<GenerationStreamEvent> normalFlow = body
                    .takeUntilOther(deadline)
                    .takeUntilOther(deleteTakeover)
                    .concatWith(Flux.defer(() -> {
                        if (deleteTakeoverReached.get()) {
                            return Flux.empty();
                        }
                        return deadlineReached.get()
                                ? finalizeTimeout(
                                        context, transcript::displayText, facts)
                                : finalizeSignal(
                                        context, transcript.displayText(), facts,
                                        transcript.answerMemoryText(), null);
                    }));
            Flux<GenerationStreamEvent> guardedNormalFlow = normalFlow
                    .onErrorResume(error -> {
                        if (!context.isUserCommitted()) {
                            if (context.terminalWinner().isEmpty()) {
                                context.closeResources();
                            }
                            return Flux.error(error);
                        }
                        if (context.terminalWinner().isPresent()) {
                            return context.terminalWinner().orElseThrow()
                                    == VueTurnContext.TerminalTrigger.DELETE_TAKEOVER
                                    ? Flux.empty() : Flux.error(error);
                        }
                        return finalizeSignal(
                                context, transcript.displayText(), facts,
                                transcript.answerMemoryText(), error);
                    });
            Flux<GenerationStreamEvent> deleteFlow = deleteTakeover
                    .flatMapMany(request -> cancellationCoordinator
                            .requestDeleteTakeover(
                                    context, request, transcript::displayText,
                                    () -> VueTurnMemoryProjection.project(
                                            List.copyOf(facts),
                                            VueTurnOutcome.TurnOutcomeType.CANCELLED))
                            .<Flux<GenerationStreamEvent>>map(finalization ->
                                    finalization.map(result ->
                                            (GenerationStreamEvent)
                                                    GenerationStreamEvent.turnOutcome(
                                                            result.outcome())).flux())
                            .orElseGet(Flux::empty));
            return Flux.merge(guardedNormalFlow, deleteFlow)
                    .takeUntil(event ->
                            event instanceof GenerationStreamEvent.TurnOutcome)
                    .doOnNext(event -> terminalDelivered.compareAndSet(
                            false,
                            event instanceof GenerationStreamEvent.TurnOutcome))
                    .doOnCancel(() -> {
                        if (!terminalDelivered.get()) {
                            cancellationCoordinator.requestCancellation(
                                    context, transcript::displayText,
                                    () -> VueTurnMemoryProjection.project(
                                            List.copyOf(facts),
                                            VueTurnOutcome.TurnOutcomeType.CANCELLED));
                        }
                    });
        });
    }

    private Flux<GenerationStreamEvent> finalizeTimeout(
            VueTurnContext context,
            Supplier<String> displayPrefix,
            List<VueToolExecutionFact> facts) {
        return cancellationCoordinator.requestTimeout(
                        context, displayPrefix,
                        () -> VueTurnMemoryProjection.project(
                                List.copyOf(facts),
                                VueTurnOutcome.TurnOutcomeType.TIMED_OUT))
                .map(result -> result.<GenerationStreamEvent>map(finalized ->
                        GenerationStreamEvent.turnOutcome(
                                finalized.outcome())).flux())
                .orElseGet(Flux::empty);
    }

    private Flux<GenerationStreamEvent> finalizeSignal(
            VueTurnContext context,
            String displayPrefix,
            List<VueToolExecutionFact> facts,
            String answerMemory,
            Throwable error) {
        VueTurnContext.TerminalTrigger trigger = error == null
                ? VueTurnContext.TerminalTrigger.COMPLETED
                : VueTurnContext.TerminalTrigger.FAILED;
        if (!context.tryStartFinalization(trigger)) {
            return Flux.empty();
        }
        context.sealRegisteredOutputSafety();
        VueTurnOutcome requested = resolveOutcome(
                context, displayPrefix, facts, answerMemory, error);
        VueTurnFinalizer.FinalizationResult result =
                finalizer.finalizeOnce(context, requested);
        GenerationStreamEvent event = GenerationStreamEvent.turnOutcome(
                result.outcome());
        return Flux.just(event);
    }

    private VueTurnOutcome resolveOutcome(
            VueTurnContext context,
            String prefix,
            List<VueToolExecutionFact> facts,
            String answerMemory,
            Throwable error) {
        ControlledTerminationReason reason = context.controlledTermination()
                .map(termination -> termination.reason()).orElse(null);
        VueBuildPhase phase = context.phase();
        if (reason == ControlledTerminationReason.BUILD_SUCCEEDED
                && phase == VueBuildPhase.SUCCEEDED) {
            return outcome(phase, VueTurnOutcome.TurnOutcomeType.SUCCEEDED,
                    prefix, facts, SUCCESS_MESSAGE, true);
        }
        if (reason == null && error == null
                && phase == VueBuildPhase.GENERATING
                && context.isReadOnlyAnswerEligible()
                && facts.stream().noneMatch(fact ->
                fact.buildAttempt() != null)
                && facts.stream().noneMatch(fact ->
                fact.changedRelativePath() != null)
                && answerMemory != null && !answerMemory.isBlank()) {
            return new VueTurnOutcome(
                    phase,
                    VueTurnOutcome.TurnOutcomeType.ANSWERED,
                    prefix,
                    answerMemory,
                    false,
                    "已回答");
        }
        if (reason == ControlledTerminationReason.BUILD_FAILED
                && phase == VueBuildPhase.FAILED) {
            if (context.timedOut()) {
                return outcome(phase, VueTurnOutcome.TurnOutcomeType.TIMED_OUT,
                        stripTrustedTerminal(prefix, BUILD_FAILED_MESSAGE),
                        facts, TIMEOUT_MESSAGE, false);
            }
            return outcome(phase, VueTurnOutcome.TurnOutcomeType.FAILED,
                    prefix, facts, BUILD_FAILED_MESSAGE, false);
        }
        if (reason == ControlledTerminationReason.LOOP_LIMIT_EXCEEDED) {
            return outcome(phase, VueTurnOutcome.TurnOutcomeType.SYSTEM_ERROR,
                    stripUntrustedControlledTerminal(prefix),
                    facts, LOOP_LIMIT_MESSAGE, false);
        }
        if (reason == ControlledTerminationReason.REPEATED_READ_LOOP) {
            return new VueTurnOutcome(
                    phase,
                    VueTurnOutcome.TurnOutcomeType.SYSTEM_ERROR,
                    appendTerminalText(
                            stripUntrustedControlledTerminal(prefix),
                            VueTurnFinalizer.REPEATED_READ_LOOP_MESSAGE),
                    VueTurnMemoryProjection.REPEATED_READ_LOOP_PROJECTION,
                    false,
                    VueTurnFinalizer.REPEATED_READ_LOOP_MESSAGE);
        }
        if (reason == ControlledTerminationReason.INCOMPLETE_TOOL_CHAIN) {
            return outcome(
                    phase,
                    VueTurnOutcome.TurnOutcomeType.INCOMPLETE_TOOL_CHAIN,
                    stripUntrustedControlledTerminal(prefix),
                    facts,
                    VueTurnFinalizer.INCOMPLETE_TOOL_CHAIN_MESSAGE,
                    false);
        }
        if (reason == ControlledTerminationReason.RESOURCE_LIMIT_EXCEEDED) {
            return outcome(phase, VueTurnOutcome.TurnOutcomeType.SYSTEM_ERROR,
                    stripUntrustedControlledTerminal(prefix),
                    facts, RESOURCE_LIMIT_MESSAGE, false);
        }
        if (reason == ControlledTerminationReason.CANCELLED
                || phase == VueBuildPhase.CANCELLED) {
            return outcome(phase, VueTurnOutcome.TurnOutcomeType.CANCELLED,
                    stripUntrustedControlledTerminal(prefix),
                    facts, CANCELLED_MESSAGE, false);
        }
        if (reason == ControlledTerminationReason.PROTOCOL_ERROR
                || reason == ControlledTerminationReason.EVALUATION_COMPLETED) {
            return protocolErrorOutcome(phase, facts);
        }
        if (error instanceof VueStreamProtocolException) {
            return protocolErrorOutcome(phase, facts);
        }
        if (error != null) {
            return outcome(phase, VueTurnOutcome.TurnOutcomeType.SYSTEM_ERROR,
                    prefix, facts, SYSTEM_ERROR_MESSAGE, false);
        }
        return outcome(phase, VueTurnOutcome.TurnOutcomeType.PROTOCOL_ERROR,
                prefix, facts, PROTOCOL_MESSAGE, false);
    }

    private VueTurnOutcome protocolErrorOutcome(
            VueBuildPhase phase, List<VueToolExecutionFact> facts) {
        return new VueTurnOutcome(
                phase,
                VueTurnOutcome.TurnOutcomeType.PROTOCOL_ERROR,
                SCOPE_PROTOCOL_MESSAGE,
                VueTurnMemoryProjection.project(
                        facts,
                        VueTurnOutcome.TurnOutcomeType.PROTOCOL_ERROR),
                false,
                SCOPE_PROTOCOL_MESSAGE);
    }

    private VueTurnOutcome outcome(
            VueBuildPhase phase, VueTurnOutcome.TurnOutcomeType type,
            String prefix,
            List<VueToolExecutionFact> facts,
            String message,
            boolean refresh) {
        return new VueTurnOutcome(
                phase, type,
                appendTerminalText(prefix, message),
                VueTurnMemoryProjection.project(facts, type),
                refresh, message);
    }

    static String appendTerminalText(String prefix, String terminalText) {
        String stablePrefix = prefix == null ? "" : prefix;
        if (stablePrefix.endsWith(terminalText)) {
            return stablePrefix;
        }
        if (stablePrefix.isBlank()) {
            return terminalText;
        }
        return stablePrefix + "\n\n" + terminalText;
    }

    private static String stripTrustedTerminal(
            String prefix, String trustedTerminal) {
        if (prefix == null || !prefix.endsWith(trustedTerminal)) {
            return prefix;
        }
        return prefix.substring(0, prefix.length() - trustedTerminal.length())
                .stripTrailing();
    }

    private static String stripUntrustedControlledTerminal(String prefix) {
        String withoutFailure = stripTrustedTerminal(prefix, BUILD_FAILED_MESSAGE);
        return stripTrustedTerminal(withoutFailure, SUCCESS_MESSAGE);
    }

    private Flux<GenerationStreamEvent> handleJsonMessageChunk(
            String chunk,
            VueTurnTranscriptAccumulator transcript,
            List<VueToolExecutionFact> facts,
            Set<String> seenToolIds, VueTurnContext context) {
        StreamMessage streamMessage = parseTrusted(() -> {
            StreamMessage parsed = JSONUtil.toBean(
                    chunk, StreamMessage.class);
            if (parsed == null) {
                throw new IllegalArgumentException(
                        "Vue 在线消息不能为空");
            }
            return parsed;
        });
        StreamMessageTypeEnum type = StreamMessageTypeEnum.getEnumByValue(
                streamMessage.getType());
        if (type == null) {
            return Flux.error(new VueStreamProtocolException(
                    "不支持的 Vue 在线消息类型"));
        }
        return switch (type) {
            case AI_RESPONSE -> {
                AiResponseMessage message = parseTrusted(() -> {
                    AiResponseMessage parsed = JSONUtil.toBean(
                            chunk, AiResponseMessage.class);
                    return new AiResponseMessage(
                            parsed.getGeneration(), parsed.getData());
                });
                VueTurnTranscriptAccumulator.AppendDecision decision =
                        transcript.appendAiText(
                                message.getGeneration(), message.getData());
                recordResourceLimit(context, decision);
                yield decision.resourceLimitExceeded()
                        ? resourceLimitAfter(
                                GenerationStreamEvent.aiText(
                                        message.getGeneration(),
                                        decision.acceptedPrefix()))
                        : eventIfNotEmpty(GenerationStreamEvent.aiText(
                                message.getGeneration(),
                                decision.acceptedPrefix()));
            }
            case TOOL_REQUEST -> {
                ToolRequestMessage request = parseTrusted(() -> {
                    ToolRequestMessage parsed = JSONUtil.toBean(
                            chunk, ToolRequestMessage.class);
                    return new ToolRequestMessage(
                            parsed.getGeneration(), parsed.getId(),
                            parsed.getName(), parsed.getArguments());
                });
                if (request.getId() != null && seenToolIds.add(request.getId())) {
                    String realtimeEvent = JSONUtil.toJsonStr(
                            new ToolRequestMessage(
                                    request.getGeneration(), request.getId(),
                                    request.getName(), null));
                    String displayText = toolManager.getTool(request.getName())
                            .generateToolRequestResponse();
                    yield Flux.just(
                            GenerationStreamEvent.structuredToolEvent(
                                    request.getGeneration(),
                                    stripTransportGeneration(realtimeEvent)),
                            GenerationStreamEvent.trustedToolDisplay(
                                    new TrustedToolDisplayMessage(
                                            request.getGeneration(),
                                            request.getId(),
                                            TrustedToolDisplayMessage.Stage
                                                    .REQUESTED,
                                            displayText)));
                }
                yield Flux.empty();
            }
            case TOOL_EXECUTED -> {
                ToolExecutedMessage executed = parseTrusted(() ->
                        trustedExecuted(JSONUtil.toBean(
                                chunk, ToolExecutedMessage.class)));
                observeTrustedFact(executed, facts);
                JSONObject arguments = JSONUtil.parseObj(executed.getArguments());
                BaseTool tool = toolManager.getTool(executed.getName());
                String markdown = tool.generateToolExecutedResult(
                        arguments, executed.getResult());
                String output = String.format("\n\n%s\n\n", markdown);
                VueTurnTranscriptAccumulator.AppendDecision decision =
                        transcript.appendTrustedToolDisplay(
                                executed.getGeneration(), executed.getId(),
                                output);
                recordResourceLimit(context, decision);
                GenerationStreamEvent structured =
                        GenerationStreamEvent.structuredToolEvent(
                                executed.getGeneration(),
                                realtimeToolExecutedChunk(chunk, executed));
                GenerationStreamEvent display =
                        GenerationStreamEvent.trustedToolDisplay(
                                new TrustedToolDisplayMessage(
                                        executed.getGeneration(),
                                        executed.getId(),
                                        TrustedToolDisplayMessage.Stage
                                                .EXECUTED,
                                        decision.acceptedPrefix()));
                yield decision.resourceLimitExceeded()
                        ? Flux.just(structured)
                        .concatWith(Flux.error(new ResourceLimitExceededException()))
                        : decision.acceptedPrefix().isEmpty()
                                ? Flux.just(structured)
                                : Flux.just(structured, display);
            }
            case TOOL_ARGUMENT -> {
                ToolArgumentMessage message = parseTrusted(() -> {
                    ToolArgumentMessage parsed = JSONUtil.toBean(
                            chunk, ToolArgumentMessage.class);
                    return new ToolArgumentMessage(
                            parsed.getGeneration(), parsed.getId(),
                            parsed.getName(), parsed.getKey(),
                            parsed.getValue());
                });
                yield Flux.just(GenerationStreamEvent.structuredToolEvent(
                        message.getGeneration(), stripTransportGeneration(
                                JSONUtil.toJsonStr(message))));
            }
            case TOOL_ARGUMENT_DELTA -> {
                ToolArgumentDeltaMessage message = parseTrusted(() -> {
                    ToolArgumentDeltaMessage parsed = JSONUtil.toBean(
                            chunk, ToolArgumentDeltaMessage.class);
                    return new ToolArgumentDeltaMessage(
                            parsed.getGeneration(), parsed.getId(),
                            parsed.getName(), parsed.getKey(),
                            parsed.getDelta());
                });
                yield Flux.just(GenerationStreamEvent.structuredToolEvent(
                        message.getGeneration(), stripTransportGeneration(
                                JSONUtil.toJsonStr(message))));
            }
            case INTERNAL_OUTPUT_ROLLBACK -> {
                InternalOutputRollbackMessage message = parseTrusted(() -> {
                    InternalOutputRollbackMessage parsed = JSONUtil.toBean(
                            chunk, InternalOutputRollbackMessage.class);
                    return new InternalOutputRollbackMessage(
                            parsed.getFailedGeneration(),
                            parsed.getCodePoints(),
                            parsed.getProvisionalToolRequestIds());
                });
                transcript.rollbackAiText(
                        message.getFailedGeneration(),
                        message.getCodePoints());
                seenToolIds.removeAll(
                        message.getProvisionalToolRequestIds());
                yield Flux.just(GenerationStreamEvent.rollback(message));
            }
            case INTERNAL_OUTPUT_RECOVERY -> {
                InternalOutputRecoveryMessage message = parseTrusted(() -> {
                    InternalOutputRecoveryMessage parsed = JSONUtil.toBean(
                            chunk, InternalOutputRecoveryMessage.class);
                    return new InternalOutputRecoveryMessage(
                                new GenerationStreamSignal.Recovery(
                                        parsed.getPhase(),
                                        parsed.getOriginalFailedGeneration(),
                                        parsed.getRecoveryGeneration(),
                                        parsed.getFailedGeneration()));
                });
                yield Flux.just(
                        GenerationStreamEvent.internalRecovery(message));
            }
            case TURN_OUTCOME -> Flux.error(new VueStreamProtocolException(
                    "模型业务流不能伪造 Vue 回合终态"));
        };
    }

    private <T> T parseTrusted(Supplier<T> parser) {
        try {
            return parser.get();
        } catch (RuntimeException exception) {
            throw new VueStreamProtocolException(
                    "Vue 在线消息不符合受信协议", exception);
        }
    }

    private ToolExecutedMessage trustedExecuted(
            ToolExecutedMessage parsed) {
        if (parsed.getId() == null || parsed.getId().isBlank()
                || parsed.getName() == null || parsed.getName().isBlank()
                || parsed.getArguments() == null
                || parsed.getResult() == null) {
            throw new IllegalArgumentException(
                    "工具执行消息字段不完整");
        }
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id(parsed.getId())
                .name(parsed.getName())
                .arguments(parsed.getArguments())
                .build();
        ToolExecution execution = ToolExecution.builder()
                .request(request)
                .result(parsed.getResult())
                .build();
        return new ToolExecutedMessage(parsed.getGeneration(), execution);
    }

    private void observeTrustedFact(
            ToolExecutedMessage executed, List<VueToolExecutionFact> facts) {
        VueToolExecutionFact.parse(executed.getName(), executed.getResult())
                .ifPresent(facts::add);
    }

    private Flux<GenerationStreamEvent> resourceLimitAfter(
            GenerationStreamEvent acceptedPrefix) {
        Flux<GenerationStreamEvent> prefix = acceptedPrefix == null
                || acceptedPrefix instanceof GenerationStreamEvent.AiText text
                && text.text().isEmpty()
                ? Flux.empty() : Flux.just(acceptedPrefix);
        return prefix.concatWith(Flux.error(new ResourceLimitExceededException()));
    }

    private Flux<GenerationStreamEvent> eventIfNotEmpty(
            GenerationStreamEvent.AiText event) {
        return event.text().isEmpty() ? Flux.empty() : Flux.just(event);
    }

    private void recordResourceLimit(
            VueTurnContext context,
            VueTurnTranscriptAccumulator.AppendDecision decision) {
        if (decision.resourceLimitExceeded()
                && context.budgetSession().claimResourceLimit()) {
            context.recordControlledTermination(
                    new dev.langchain4j.service.ToolLoopTerminationProtocol
                            .ControlledTermination(
                            ControlledTerminationReason.RESOURCE_LIMIT_EXCEEDED,
                            null));
        }
    }

    private static final class ResourceLimitExceededException
            extends IllegalStateException {

        private ResourceLimitExceededException() {
            super("Vue 稳定正文超过本轮资源上限");
        }
    }

    private static final class VueStreamProtocolException
            extends IllegalStateException {

        private VueStreamProtocolException(String message) {
            super(message);
        }

        private VueStreamProtocolException(
                String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 模型继续使用原始消息，浏览器只接收脱敏副本。 */
    private String realtimeToolExecutedChunk(
            String rawChunk, ToolExecutedMessage executed) {
        String clientChunk = CLIENT_REDACTED_FILE_TOOLS.contains(
                executed.getName())
                ? JSONUtil.toJsonStr(executed.toClientSafeCopy())
                : rawChunk;
        return stripTransportGeneration(clientChunk);
    }

    private String stripTransportGeneration(String json) {
        JSONObject object = JSONUtil.parseObj(json);
        object.remove("generation");
        return JSONUtil.toJsonStr(object);
    }
}
