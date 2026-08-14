package com.lyw.appgeneration.core.handler;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.lyw.appgeneration.ai.model.message.AiResponseMessage;
import com.lyw.appgeneration.ai.model.message.StreamMessage;
import com.lyw.appgeneration.ai.model.message.StreamMessageTypeEnum;
import com.lyw.appgeneration.ai.model.message.ToolExecutedMessage;
import com.lyw.appgeneration.ai.model.message.ToolRequestMessage;
import com.lyw.appgeneration.ai.tools.BaseTool;
import com.lyw.appgeneration.ai.tools.FileToolBudgetGuard;
import com.lyw.appgeneration.core.builder.VueBuildPhase;
import com.lyw.appgeneration.manger.ToolManager;
import dev.langchain4j.service.ToolLoopTerminationProtocol.ControlledTerminationReason;
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
import java.util.concurrent.atomic.AtomicBoolean;

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
            FileToolBudgetGuard.CanonicalAccumulator canonical =
                    context.budgetSession().newCanonicalAccumulator(
                            TERMINAL_RESERVE_CODE_POINTS);
            Set<String> seenToolIds = new HashSet<>();
            Flux<GenerationStreamEvent> body = originFlux.concatMap(chunk ->
                            handleJsonMessageChunk(
                                    chunk, canonical, seenToolIds, context))
                    .filter(StrUtil::isNotEmpty)
                    .map(GenerationStreamEvent::content);
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
                                ? finalizeTimeout(context, canonical.content())
                                : finalizeSignal(
                                        context, canonical.content(), null);
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
                                context, canonical.content(), error);
                    });
            Flux<GenerationStreamEvent> deleteFlow = deleteTakeover
                    .flatMapMany(request -> cancellationCoordinator
                            .requestDeleteTakeover(
                                    context, request, canonical::content)
                            .<Flux<GenerationStreamEvent>>map(finalization ->
                                    finalization.map(result ->
                                            (GenerationStreamEvent)
                                                    GenerationStreamEvent.turnOutcome(
                                                            result.outcome())).flux())
                            .orElseGet(Flux::empty));
            return Flux.merge(guardedNormalFlow, deleteFlow)
                    .takeUntil(event ->
                            event instanceof GenerationStreamEvent.TurnOutcome)
                    .doOnCancel(() -> cancellationCoordinator.requestCancellation(
                            context, canonical::content));
        });
    }

    private Flux<GenerationStreamEvent> finalizeTimeout(
            VueTurnContext context, String canonicalPrefix) {
        return cancellationCoordinator.requestTimeout(
                        context, () -> canonicalPrefix)
                .map(result -> result.<GenerationStreamEvent>map(finalized ->
                        GenerationStreamEvent.turnOutcome(
                                finalized.outcome())).flux())
                .orElseGet(Flux::empty);
    }

    private Flux<GenerationStreamEvent> finalizeSignal(
            VueTurnContext context, String canonicalPrefix, Throwable error) {
        VueTurnContext.TerminalTrigger trigger = error == null
                ? VueTurnContext.TerminalTrigger.COMPLETED
                : VueTurnContext.TerminalTrigger.FAILED;
        if (!context.tryStartFinalization(trigger)) {
            return Flux.empty();
        }
        VueTurnOutcome requested = resolveOutcome(context, canonicalPrefix, error);
        VueTurnFinalizer.FinalizationResult result =
                finalizer.finalizeOnce(context, requested);
        GenerationStreamEvent event = GenerationStreamEvent.turnOutcome(
                result.outcome());
        return Flux.just(event);
    }

    private VueTurnOutcome resolveOutcome(
            VueTurnContext context, String prefix, Throwable error) {
        ControlledTerminationReason reason = context.controlledTermination()
                .map(termination -> termination.reason()).orElse(null);
        VueBuildPhase phase = context.phase();
        if (reason == ControlledTerminationReason.BUILD_SUCCEEDED
                && phase == VueBuildPhase.SUCCEEDED) {
            return outcome(phase, VueTurnOutcome.TurnOutcomeType.SUCCEEDED,
                    prefix, SUCCESS_MESSAGE, true);
        }
        if (reason == ControlledTerminationReason.BUILD_FAILED
                && phase == VueBuildPhase.FAILED) {
            if (context.timedOut()) {
                return outcome(phase, VueTurnOutcome.TurnOutcomeType.TIMED_OUT,
                        stripTrustedTerminal(prefix, BUILD_FAILED_MESSAGE),
                        TIMEOUT_MESSAGE, false);
            }
            return outcome(phase, VueTurnOutcome.TurnOutcomeType.FAILED,
                    prefix, BUILD_FAILED_MESSAGE, false);
        }
        if (reason == ControlledTerminationReason.LOOP_LIMIT_EXCEEDED) {
            return outcome(phase, VueTurnOutcome.TurnOutcomeType.SYSTEM_ERROR,
                    stripUntrustedControlledTerminal(prefix),
                    LOOP_LIMIT_MESSAGE, false);
        }
        if (reason == ControlledTerminationReason.RESOURCE_LIMIT_EXCEEDED) {
            return outcome(phase, VueTurnOutcome.TurnOutcomeType.SYSTEM_ERROR,
                    stripUntrustedControlledTerminal(prefix),
                    RESOURCE_LIMIT_MESSAGE, false);
        }
        if (reason == ControlledTerminationReason.CANCELLED
                || phase == VueBuildPhase.CANCELLED) {
            return outcome(phase, VueTurnOutcome.TurnOutcomeType.CANCELLED,
                    stripUntrustedControlledTerminal(prefix),
                    CANCELLED_MESSAGE, false);
        }
        if (reason == ControlledTerminationReason.PROTOCOL_ERROR
                || reason == ControlledTerminationReason.EVALUATION_COMPLETED) {
            return outcome(phase, VueTurnOutcome.TurnOutcomeType.PROTOCOL_ERROR,
                    stripUntrustedControlledTerminal(prefix),
                    SCOPE_PROTOCOL_MESSAGE, false);
        }
        if (error != null) {
            return outcome(phase, VueTurnOutcome.TurnOutcomeType.SYSTEM_ERROR,
                    prefix, SYSTEM_ERROR_MESSAGE, false);
        }
        return outcome(phase, VueTurnOutcome.TurnOutcomeType.PROTOCOL_ERROR,
                prefix, PROTOCOL_MESSAGE, false);
    }

    private VueTurnOutcome outcome(
            VueBuildPhase phase, VueTurnOutcome.TurnOutcomeType type,
            String prefix, String message, boolean refresh) {
        return new VueTurnOutcome(
                phase, type, appendTerminalText(prefix, message), refresh, message);
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

    private Flux<String> handleJsonMessageChunk(
            String chunk, FileToolBudgetGuard.CanonicalAccumulator canonical,
            Set<String> seenToolIds, VueTurnContext context) {
        StreamMessage streamMessage = JSONUtil.toBean(chunk, StreamMessage.class);
        StreamMessageTypeEnum type = StreamMessageTypeEnum.getEnumByValue(
                streamMessage.getType());
        if (type == null) {
            log.error("不支持的消息类型: {}", streamMessage.getType());
            return Flux.empty();
        }
        return switch (type) {
            case AI_RESPONSE -> {
                String data = JSONUtil.toBean(chunk, AiResponseMessage.class).getData();
                FileToolBudgetGuard.AppendDecision decision = canonical.append(data);
                recordResourceLimit(context, decision);
                yield decision.resourceLimitExceeded()
                        ? resourceLimitAfter(decision.acceptedPrefix())
                        : Flux.just(decision.acceptedPrefix());
            }
            case TOOL_REQUEST -> {
                ToolRequestMessage request = JSONUtil.toBean(
                        chunk, ToolRequestMessage.class);
                if (request.getId() != null && seenToolIds.add(request.getId())) {
                    yield Flux.just(toolManager.getTool(request.getName())
                            .generateToolRequestResponse());
                }
                yield Flux.empty();
            }
            case TOOL_EXECUTED -> {
                ToolExecutedMessage executed = JSONUtil.toBean(
                        chunk, ToolExecutedMessage.class);
                JSONObject arguments = JSONUtil.parseObj(executed.getArguments());
                BaseTool tool = toolManager.getTool(executed.getName());
                String markdown = tool.generateToolExecutedResult(
                        arguments, executed.getResult());
                String output = String.format("\n\n%s\n\n", markdown);
                FileToolBudgetGuard.AppendDecision decision = canonical.append(output);
                recordResourceLimit(context, decision);
                yield decision.resourceLimitExceeded()
                        ? Flux.just(realtimeToolExecutedChunk(chunk, executed))
                        .concatWith(Flux.error(new ResourceLimitExceededException()))
                        : Flux.just(realtimeToolExecutedChunk(chunk, executed),
                        decision.acceptedPrefix());
            }
            case TOOL_ARGUMENT, TOOL_ARGUMENT_DELTA -> Flux.just(chunk);
            case TURN_OUTCOME -> Flux.empty();
        };
    }

    private Flux<String> resourceLimitAfter(String acceptedPrefix) {
        Flux<String> prefix = acceptedPrefix == null || acceptedPrefix.isEmpty()
                ? Flux.empty() : Flux.just(acceptedPrefix);
        return prefix.concatWith(Flux.error(new ResourceLimitExceededException()));
    }

    private void recordResourceLimit(
            VueTurnContext context,
            FileToolBudgetGuard.AppendDecision decision) {
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

    /** 模型继续使用原始消息，浏览器只接收脱敏副本。 */
    private String realtimeToolExecutedChunk(
            String rawChunk, ToolExecutedMessage executed) {
        if (!CLIENT_REDACTED_FILE_TOOLS.contains(executed.getName())) {
            return rawChunk;
        }
        return JSONUtil.toJsonStr(executed.toClientSafeCopy());
    }
}
