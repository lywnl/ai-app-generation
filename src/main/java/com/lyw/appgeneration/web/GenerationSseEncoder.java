package com.lyw.appgeneration.web;

import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.lyw.appgeneration.core.handler.GenerationStreamEvent;
import com.lyw.appgeneration.exception.GenerationPreflightException;
import org.springframework.http.codec.ServerSentEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 单个 SSE 响应的业务帧与终止帧编码器。 */
public final class GenerationSseEncoder {

    public static final String STREAM_PROTOCOL = "generation-stream/v1";
    public static final String ERROR_PROTOCOL = "generation-error/v1";
    private static final String VUE_TURN_PROTOCOL = "vue-turn/v1";
    private static final String ROLLBACK_PROTOCOL =
            "internal-output-rollback/v1";
    private static final String RECOVERY_PROTOCOL =
            "internal-output-recovery/v1";

    private long sequence;
    private boolean done;
    private boolean businessError;

    public synchronized ServerSentEvent<String> business(
            GenerationStreamEvent event) {
        Objects.requireNonNull(event, "业务事件不能为空");
        ensureBusinessOpen();
        long next = candidateSequence();
        ServerSentEvent<String> encoded = switch (event) {
            case GenerationStreamEvent.SimpleText text -> message(
                    next, "simple_text", text.text(), null);
            case GenerationStreamEvent.AiText text -> message(
                    next, "ai_text", text.text(), text.generation());
            case GenerationStreamEvent.StructuredToolEvent tool -> message(
                    next, "structured_tool_event", tool.json(),
                    tool.generation());
            case GenerationStreamEvent.TrustedToolDisplay display -> {
                var message = display.message();
                yield event("trusted-tool-display", object(
                        "protocol", "trusted-tool-display/v1",
                        "sequence", next,
                        "generation", generation(message.generation()),
                        "toolRequestId", message.toolRequestId(),
                        "stage", message.stage().name(),
                        "text", message.text()));
            }
            case GenerationStreamEvent.Rollback rollback -> {
                var message = rollback.message();
                List<String> requestIds = new ArrayList<>(
                        message.getProvisionalToolRequestIds());
                requestIds.sort(Comparator.naturalOrder());
                yield event("internal-output-rollback", object(
                        "protocol", ROLLBACK_PROTOCOL,
                        "sequence", next,
                        "failedGeneration", generation(
                                message.getFailedGeneration()),
                        "codePoints", message.getCodePoints(),
                        "provisionalToolRequestIds", requestIds));
            }
            case GenerationStreamEvent.InternalRecovery recovery -> {
                var message = recovery.message();
                yield event("internal-output-recovery", objectWithNulls(
                        "protocol", RECOVERY_PROTOCOL,
                        "sequence", next,
                        "phase", message.getPhase().name(),
                        "originalFailedGeneration", generation(
                                message.getOriginalFailedGeneration()),
                        "recoveryGeneration", optionalGeneration(
                                message.getRecoveryGeneration()),
                        "failedGeneration", optionalGeneration(
                                message.getFailedGeneration()),
                        "message", internalRecoveryMessage(
                                message.getPhase())));
            }
            case GenerationStreamEvent.ContextCompression compression -> {
                var message = compression.message();
                yield event("context-compression", object(
                        "protocol", message.protocol(),
                        "sequence", next,
                        "phase", message.phase().name(),
                        "message", message.message()));
            }
            case GenerationStreamEvent.ToolProtocolRecovery recovery -> {
                var message = recovery.message();
                yield event("tool-protocol-recovery", object(
                        "protocol", message.protocol(),
                        "sequence", next,
                        "phase", message.phase().name(),
                        "message", message.message()));
            }
            case GenerationStreamEvent.IncompleteToolChainRecovery recovery -> {
                var message = recovery.message();
                yield event("incomplete-tool-chain-recovery", object(
                        "protocol", message.protocol(),
                        "sequence", next,
                        "phase", message.phase().name(),
                        "message", message.message()));
            }
            case GenerationStreamEvent.TurnOutcome turn -> {
                var message = turn.message();
                yield event("turn-outcome", object(
                        "protocol", VUE_TURN_PROTOCOL,
                        "sequence", next,
                        "outcome", message.getOutcome().name(),
                        "message", message.getMessage(),
                        "refreshPreview",
                        message.isShouldRefreshPreview()));
            }
        };
        sequence = next;
        return encoded;
    }

    public synchronized ServerSentEvent<String> businessError(
            GenerationPreflightException error) {
        if (done || businessError) {
            throw new IllegalStateException("业务错误已经发送或响应已结束");
        }
        ServerSentEvent<String> encoded = event("business-error", object(
                "protocol", ERROR_PROTOCOL,
                "kind", error.kind().name(),
                "code", error.code(),
                "message", error.safeMessage()));
        businessError = true;
        return encoded;
    }

    public synchronized ServerSentEvent<String> heartbeat(long timestamp) {
        if (done || businessError) {
            throw new IllegalStateException(
                    "业务错误或响应结束后不能发送心跳");
        }
        return event("heartbeat", object("timestamp", timestamp));
    }

    public synchronized ServerSentEvent<String> done() {
        if (done) {
            throw new IllegalStateException("done 只能发送一次");
        }
        long next = candidateSequence();
        ServerSentEvent<String> encoded = event("done", object(
                "protocol", STREAM_PROTOCOL,
                "sequence", next));
        sequence = next;
        done = true;
        return encoded;
    }

    public synchronized String preflightWire(
            GenerationPreflightException error) {
        ServerSentEvent<String> business = businessError(error);
        ServerSentEvent<String> terminal = done();
        return wire(business) + wire(terminal);
    }

    private void ensureBusinessOpen() {
        if (done || businessError) {
            throw new IllegalStateException(
                    "业务错误或 done 后不能继续发送业务帧");
        }
    }

    private long candidateSequence() {
        if (sequence == Integer.MAX_VALUE) {
            throw new IllegalStateException("SSE 业务帧数量超过安全上限");
        }
        return sequence + 1L;
    }

    private ServerSentEvent<String> message(
            long sequence, String kind, String data, Long generation) {
        JSONObject payload = object(
                "protocol", STREAM_PROTOCOL,
                "sequence", sequence,
                "kind", kind,
                "data", data);
        if (generation != null) {
            payload.set("generation", generation(generation));
        }
        return event("message", payload);
    }

    private ServerSentEvent<String> event(
            String event, JSONObject data) {
        return ServerSentEvent.<String>builder()
                .event(event)
                .data(JSONUtil.toJsonStr(data))
                .build();
    }

    private JSONObject object(Object... values) {
        JSONObject object = new JSONObject();
        for (int index = 0; index < values.length; index += 2) {
            object.set((String) values[index], values[index + 1]);
        }
        return object;
    }

    private JSONObject objectWithNulls(Object... values) {
        JSONObject object = new JSONObject(
                JSONConfig.create().setIgnoreNullValue(false));
        for (int index = 0; index < values.length; index += 2) {
            object.set((String) values[index], values[index + 1]);
        }
        return object;
    }

    private String generation(long generation) {
        return Long.toString(generation);
    }

    private String optionalGeneration(Long generation) {
        return generation == null ? null : generation(generation);
    }

    private String internalRecoveryMessage(
            dev.langchain4j.service.GenerationStreamSignal.Recovery.Phase
                    phase) {
        return switch (phase) {
            case STARTED -> "检测到生成状态异常，正在重新生成…";
            case RECOVERED -> "生成状态已恢复，继续处理…";
            case FAILED -> "生成状态异常，系统已停止本次生成，请重新发起。";
        };
    }

    private String wire(ServerSentEvent<String> event) {
        return "event: " + event.event() + "\n"
                + "data: " + event.data() + "\n\n";
    }
}
