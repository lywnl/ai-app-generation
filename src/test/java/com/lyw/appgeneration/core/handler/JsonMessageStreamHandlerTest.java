package com.lyw.appgeneration.core.handler;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.lyw.appgeneration.ai.model.message.TurnOutcomeMessage;
import com.lyw.appgeneration.ai.tools.BaseTool;
import com.lyw.appgeneration.core.AiCodeGeneratorFacade;
import com.lyw.appgeneration.core.builder.VueBuildPhase;
import com.lyw.appgeneration.manger.ToolManager;
import dev.langchain4j.service.ToolLoopTerminationProtocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JsonMessageStreamHandlerTest {

    private static final long APP_ID = 123L;
    private static final long USER_ID = 99L;

    @Mock private ToolManager toolManager;
    @Mock private VueTurnFinalizer finalizer;
    @Mock private VueTurnCancellationCoordinator cancellationCoordinator;

    private JsonMessageStreamHandler handler;

    @BeforeEach
    void setUp() {
        handler = new JsonMessageStreamHandler(
                toolManager, finalizer, cancellationCoordinator);
    }

    @Test
    void ordinaryCompleteWithoutBuildBecomesProtocolErrorAndOutcomeIsLast() {
        VueTurnContext context = context("turn-no-build", VueBuildPhase.GENERATING);
        when(finalizer.finalizeOnce(eq(context), any())).thenAnswer(invocation -> {
            VueTurnOutcome requested = invocation.getArgument(1);
            assertEquals(VueTurnOutcome.TurnOutcomeType.PROTOCOL_ERROR,
                    requested.outcome());
            assertEquals("正文\n\n项目尚未通过真实构建，请重新生成。",
                    requested.canonicalAiText());
            return new VueTurnFinalizer.FinalizationResult(requested, true);
        });

        List<String> output = handler.handle(Flux.just(
                "{\"type\":\"ai_response\",\"data\":\"正文\"}"), context)
                .collectList().block();

        assertEquals("正文", output.getFirst());
        TurnOutcomeMessage outcome = JSONUtil.toBean(output.getLast(),
                TurnOutcomeMessage.class);
        assertEquals(VueTurnOutcome.TurnOutcomeType.PROTOCOL_ERROR,
                outcome.getOutcome());
        assertFalse(outcome.isShouldRefreshPreview());
    }

    @Test
    void toolExecutedKeepsRawResultInRealtimeEventButCanonicalUsesStableMarkdown() {
        VueTurnContext context = context("turn-tool", VueBuildPhase.GENERATING);
        BaseTool tool = mock(BaseTool.class);
        when(toolManager.getTool("buildProject")).thenReturn(tool);
        when(tool.generateToolExecutedResult(any(JSONObject.class),
                eq("{\"success\":false,\"secretLog\":\"raw\"}")))
                .thenReturn("第 1 次构建失败，正在修复");
        when(finalizer.finalizeOnce(eq(context), any())).thenAnswer(invocation -> {
            VueTurnOutcome requested = invocation.getArgument(1);
            assertFalse(requested.canonicalAiText().contains("secretLog"));
            assertEquals("\n\n第 1 次构建失败，正在修复\n\n\n\n"
                            + "项目尚未通过真实构建，请重新生成。",
                    requested.canonicalAiText());
            return new VueTurnFinalizer.FinalizationResult(requested, true);
        });
        String event = "{\"type\":\"tool_executed\",\"id\":\"tool-1\","
                + "\"name\":\"buildProject\",\"arguments\":\"{}\","
                + "\"result\":\"{\\\"success\\\":false,"
                + "\\\"secretLog\\\":\\\"raw\\\"}\"}";

        List<String> output = handler.handle(Flux.just(event), context)
                .collectList().block();

        assertEquals(event, output.get(0));
        assertEquals("\n\n第 1 次构建失败，正在修复\n\n", output.get(1));
        verify(tool).generateToolExecutedResult(any(JSONObject.class),
                eq("{\"success\":false,\"secretLog\":\"raw\"}"));
    }

    @Test
    void loopLimitUsesFixedMessageExactlyOnce() {
        VueTurnContext context = context("turn-loop", VueBuildPhase.GENERATING);
        context.recordControlledTermination(new ToolLoopTerminationProtocol
                .ControlledTermination(ToolLoopTerminationProtocol
                .ControlledTerminationReason.LOOP_LIMIT_EXCEEDED, null));
        when(finalizer.finalizeOnce(eq(context), any())).thenAnswer(invocation -> {
            VueTurnOutcome requested = invocation.getArgument(1);
            assertEquals(JsonMessageStreamHandler.LOOP_LIMIT_MESSAGE,
                    requested.canonicalAiText());
            return new VueTurnFinalizer.FinalizationResult(requested, true);
        });

        List<String> output = handler.handle(
                Flux.error(new AiCodeGeneratorFacade
                        .OnlineControlledTerminationException(
                        ToolLoopTerminationProtocol.ControlledTerminationReason
                                .LOOP_LIMIT_EXCEEDED)), context)
                .collectList().block();

        assertEquals(1, output.size());
        TurnOutcomeMessage outcome = JSONUtil.toBean(output.getFirst(),
                TurnOutcomeMessage.class);
        assertEquals(JsonMessageStreamHandler.LOOP_LIMIT_MESSAGE,
                outcome.getMessage());
    }

    @Test
    void terminalBuildTimeoutUsesTimedOutOutcomeAndFixedMessage() {
        VueTurnContext context = VueTurnContext.testing(
                APP_ID, USER_ID, "turn-timeout", VueBuildPhase.FAILED, true);
        context.recordControlledTermination(new ToolLoopTerminationProtocol
                .ControlledTermination(ToolLoopTerminationProtocol
                .ControlledTerminationReason.BUILD_FAILED,
                JsonMessageStreamHandler.BUILD_FAILED_MESSAGE));
        when(finalizer.finalizeOnce(eq(context), any())).thenAnswer(invocation -> {
            VueTurnOutcome requested = invocation.getArgument(1);
            assertEquals(VueTurnOutcome.TurnOutcomeType.TIMED_OUT,
                    requested.outcome());
            assertEquals(JsonMessageStreamHandler.TIMEOUT_MESSAGE,
                    requested.canonicalAiText());
            return new VueTurnFinalizer.FinalizationResult(requested, true);
        });

        TurnOutcomeMessage outcome = JSONUtil.toBean(handler.handle(
                        Flux.just("{\"type\":\"ai_response\",\"data\":\""
                                + JsonMessageStreamHandler.BUILD_FAILED_MESSAGE
                                + "\"}"), context).blockLast(),
                TurnOutcomeMessage.class);

        assertEquals(VueTurnOutcome.TurnOutcomeType.TIMED_OUT,
                outcome.getOutcome());
        assertEquals(JsonMessageStreamHandler.TIMEOUT_MESSAGE,
                outcome.getMessage());
    }

    @Test
    void protocolTerminationReplacesLegacyFinalResponseInsteadOfDuplicatingTerminalText() {
        VueTurnContext context = context("turn-protocol", VueBuildPhase.GENERATING);
        context.recordControlledTermination(new ToolLoopTerminationProtocol
                .ControlledTermination(ToolLoopTerminationProtocol
                .ControlledTerminationReason.PROTOCOL_ERROR,
                JsonMessageStreamHandler.BUILD_FAILED_MESSAGE));
        when(finalizer.finalizeOnce(eq(context), any())).thenAnswer(invocation -> {
            VueTurnOutcome requested = invocation.getArgument(1);
            assertEquals(JsonMessageStreamHandler.SCOPE_PROTOCOL_MESSAGE,
                    requested.canonicalAiText());
            return new VueTurnFinalizer.FinalizationResult(requested, true);
        });

        List<String> output = handler.handle(Flux.concat(
                Flux.just("{\"type\":\"ai_response\",\"data\":\""
                        + JsonMessageStreamHandler.BUILD_FAILED_MESSAGE + "\"}"),
                Flux.error(new AiCodeGeneratorFacade
                        .OnlineControlledTerminationException(
                        ToolLoopTerminationProtocol.ControlledTerminationReason
                                .PROTOCOL_ERROR))), context).collectList().block();

        TurnOutcomeMessage outcome = JSONUtil.toBean(output.getLast(),
                TurnOutcomeMessage.class);
        assertEquals(JsonMessageStreamHandler.SCOPE_PROTOCOL_MESSAGE,
                outcome.getMessage());
    }

    private VueTurnContext context(String turnId, VueBuildPhase phase) {
        return VueTurnContext.testing(APP_ID, USER_ID, turnId, phase);
    }
}
