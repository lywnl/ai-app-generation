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

    private static final Set<String> READ_TOOLS = Set.of("readFile", "readDir");

    static final String SUCCESS_MESSAGE = "项目已生成并构建成功。";
    static final String BUILD_FAILED_MESSAGE = "抱歉，系统遇到了一些问题，请您稍后重试修复";
    static final String SYSTEM_ERROR_MESSAGE = "生成过程中遇到系统异常，请稍后重试。";
    static final String PROTOCOL_MESSAGE = "项目尚未通过真实构建，请重新生成。";
    static final String SCOPE_PROTOCOL_MESSAGE = "生成状态异常，系统已停止本次生成，请重新发起。";
    static final String LOOP_LIMIT_MESSAGE = "生成步骤过多，系统已停止本次生成，请稍后重试。";
    static final String TIMEOUT_MESSAGE = "生成与构建超时，请稍后重试。";

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
            StringBuilder canonical = new StringBuilder();
            Set<String> seenToolIds = new HashSet<>();
            Flux<GenerationStreamEvent> body = originFlux.concatMap(chunk ->
                            Flux.fromIterable(handleJsonMessageChunk(
                                    chunk, canonical, seenToolIds)))
                    .filter(StrUtil::isNotEmpty)
                    .map(GenerationStreamEvent::content);
            AtomicBoolean deadlineReached = new AtomicBoolean();
            Mono<Long> deadline = Mono.delay(
                            context.remainingUntilDeadline(), deadlineScheduler)
                    .doOnNext(ignored -> deadlineReached.set(true));
            Flux<GenerationStreamEvent> completed = body.takeUntilOther(deadline)
                    .concatWith(Flux.defer(() -> deadlineReached.get()
                            ? finalizeTimeout(context, canonical.toString())
                            : finalizeSignal(context, canonical.toString(), null)));
            return completed
                    .onErrorResume(error -> {
                        if (!context.isUserCommitted()) {
                            if (context.terminalWinner().isEmpty()) {
                                context.closeResources();
                            }
                            return Flux.error(error);
                        }
                        if (context.terminalWinner().isPresent()) {
                            return Flux.error(error);
                        }
                        return finalizeSignal(
                                context, canonical.toString(), error);
                    })
                    .doOnCancel(() -> cancellationCoordinator.requestCancellation(
                            context, canonical::toString));
        });
    }

    private Flux<GenerationStreamEvent> finalizeTimeout(
            VueTurnContext context, String canonicalPrefix) {
        return cancellationCoordinator.requestTimeout(
                        context, () -> canonicalPrefix)
                .map(result -> result.<GenerationStreamEvent>map(finalized ->
                        GenerationStreamEvent.vueOutcome(
                                finalized.outcome())).flux())
                .orElseGet(Flux::empty);
    }

    private Flux<GenerationStreamEvent> finalizeSignal(
            VueTurnContext context, String canonicalPrefix, Throwable error) {
        VueTurnContext.TerminalTrigger trigger = error == null
                ? VueTurnContext.TerminalTrigger.COMPLETED
                : VueTurnContext.TerminalTrigger.FAILED;
        if (!context.tryClaimTerminal(trigger)) {
            return Flux.empty();
        }
        VueTurnOutcome requested = resolveOutcome(context, canonicalPrefix, error);
        VueTurnFinalizer.FinalizationResult result =
                finalizer.finalizeOnce(context, requested);
        GenerationStreamEvent event = GenerationStreamEvent.vueOutcome(
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
        if (reason == ControlledTerminationReason.CANCELLED
                || phase == VueBuildPhase.CANCELLED) {
            return outcome(phase, VueTurnOutcome.TurnOutcomeType.CANCELLED,
                    stripUntrustedControlledTerminal(prefix),
                    "本次生成已取消。", false);
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

    private List<String> handleJsonMessageChunk(
            String chunk, StringBuilder canonical, Set<String> seenToolIds) {
        StreamMessage streamMessage = JSONUtil.toBean(chunk, StreamMessage.class);
        StreamMessageTypeEnum type = StreamMessageTypeEnum.getEnumByValue(
                streamMessage.getType());
        if (type == null) {
            log.error("不支持的消息类型: {}", streamMessage.getType());
            return List.of();
        }
        return switch (type) {
            case AI_RESPONSE -> {
                String data = JSONUtil.toBean(chunk, AiResponseMessage.class).getData();
                canonical.append(data);
                yield List.of(data);
            }
            case TOOL_REQUEST -> {
                ToolRequestMessage request = JSONUtil.toBean(
                        chunk, ToolRequestMessage.class);
                if (request.getId() != null && seenToolIds.add(request.getId())) {
                    yield List.of(toolManager.getTool(request.getName())
                            .generateToolRequestResponse());
                }
                yield List.of();
            }
            case TOOL_EXECUTED -> {
                ToolExecutedMessage executed = JSONUtil.toBean(
                        chunk, ToolExecutedMessage.class);
                JSONObject arguments = JSONUtil.parseObj(executed.getArguments());
                BaseTool tool = toolManager.getTool(executed.getName());
                String markdown = tool.generateToolExecutedResult(
                        arguments, executed.getResult());
                String output = String.format("\n\n%s\n\n", markdown);
                canonical.append(output);
                yield List.of(realtimeToolExecutedChunk(chunk, executed), output);
            }
            case TOOL_ARGUMENT, TOOL_ARGUMENT_DELTA -> List.of(chunk);
            case TURN_OUTCOME -> List.of();
        };
    }

    /** 读取正文已经返回当前模型，实时事件只保留工具元数据。 */
    private String realtimeToolExecutedChunk(
            String rawChunk, ToolExecutedMessage executed) {
        if (!READ_TOOLS.contains(executed.getName())) {
            return rawChunk;
        }
        JSONObject realtime = JSONUtil.parseObj(rawChunk);
        realtime.set("result", cn.hutool.json.JSONNull.NULL);
        return JSONUtil.toJsonStr(realtime);
    }
}
