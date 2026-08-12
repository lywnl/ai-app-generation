package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.ai.AiCodeGeneratorService;
import com.lyw.appgeneration.ai.AiGeneratorServiceFactory;
import com.lyw.appgeneration.ai.memory.ToolMessageCollapser;
import com.lyw.appgeneration.ai.tools.BaseTool;
import cn.hutool.json.JSONObject;
import com.lyw.appgeneration.constants.AppConstant;
import com.lyw.appgeneration.core.builder.VueProjectBuilder;
import com.lyw.appgeneration.core.AiCodeGeneratorFacade;
import com.lyw.appgeneration.manger.ToolManager;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.model.enums.ChatHistoryMessageTypeEnum;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.UserMemoryService;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.ToolLoopTerminationProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JsonMessageStreamHandlerTest {

    private static final long APP_ID = 123L;
    private static final long USER_ID = 99L;

    @Mock
    private ToolManager toolManager;

    @Mock
    private VueProjectBuilder vueProjectBuilder;

    @Mock
    private AiGeneratorServiceFactory aiGeneratorServiceFactory;

    @Mock
    private AiCodeGeneratorService aiCodeGeneratorService;

    @Mock
    private TokenStream tokenStream;

    @Mock
    private ChatHistoryService chatHistoryService;

    @Mock
    private MemorySummaryService memorySummaryService;

    @Mock
    private UserMemoryService userMemoryService;

    @Mock
    private ToolMessageCollapser toolMessageCollapser;

    @InjectMocks
    private JsonMessageStreamHandler handler;

    @Test
    void handle_shouldKeepCompatibilityBuildUntilGuardIsWired() {
        User loginUser = User.builder().id(USER_ID).build();
        List<String> output = handler.handle(
                Flux.just("{\"type\":\"ai_response\",\"data\":\"ok\"}"),
                chatHistoryService,
                APP_ID,
                loginUser,
                memorySummaryService,
                userMemoryService
        ).collectList().block();

        assertEquals(List.of("ok"), output);

        InOrder inOrder = inOrder(
                chatHistoryService,
                toolMessageCollapser,
                memorySummaryService,
                userMemoryService,
                vueProjectBuilder
        );
        inOrder.verify(chatHistoryService).addChatMessage(
                eq(APP_ID),
                eq("ok"),
                eq(ChatHistoryMessageTypeEnum.AI.getValue()),
                eq(USER_ID)
        );
        inOrder.verify(toolMessageCollapser).collapseLastTurn(APP_ID, "ok");
        inOrder.verify(memorySummaryService).triggerSummarizationAsync(APP_ID);
        inOrder.verify(userMemoryService).triggerPreferenceExtractionAsync(USER_ID, APP_ID);
        inOrder.verify(vueProjectBuilder).buildProjectAsync(
                AppConstant.CODE_OUTPUT_ROOT_DIR + "/vue_project_" + APP_ID);
        verify(aiGeneratorServiceFactory, never())
                .getAiCodeGeneratorService(anyLong(), any());
        verify(aiCodeGeneratorService, never())
                .generateVueProjectCodeStream(anyLong(), anyString());
        verify(tokenStream, never()).start();
        verify(toolMessageCollapser, never()).restore(anyLong(), anyList());
    }

    @Test
    void toolExecutedPassesRawResultToStableRendererAndKeepsEventShape() {
        User loginUser = User.builder().id(USER_ID).build();
        BaseTool tool = mock(BaseTool.class);
        when(toolManager.getTool("writeFile")).thenReturn(tool);
        when(tool.generateToolExecutedResult(any(JSONObject.class), eq("受信原始结果")))
                .thenReturn("稳定文本");
        String event = """
                {"type":"tool_executed","id":"tool-1","name":"writeFile",\
                "arguments":"{\\\"relativeFilePath\\\":\\\"src/App.vue\\\"}",\
                "result":"受信原始结果"}
                """;

        List<String> output = handler.handle(
                Flux.just(event), chatHistoryService, APP_ID, loginUser,
                memorySummaryService, userMemoryService).collectList().block();

        assertEquals(List.of(event, "\n\n稳定文本\n\n"), output);
        verify(tool).generateToolExecutedResult(
                argThat(arguments -> "src/App.vue".equals(
                        arguments.getStr("relativeFilePath"))),
                eq("受信原始结果"));
        verify(vueProjectBuilder).buildProjectAsync(
                AppConstant.CODE_OUTPUT_ROOT_DIR + "/vue_project_" + APP_ID);
    }

    @ParameterizedTest
    @EnumSource(value = ToolLoopTerminationProtocol.ControlledTerminationReason.class,
            names = {"CANCELLED", "PROTOCOL_ERROR", "LOOP_LIMIT_EXCEEDED",
                    "EVALUATION_COMPLETED"})
    void onlineAbnormalControlledTerminationDoesNotRunSuccessfulTurnHooks(
            ToolLoopTerminationProtocol.ControlledTerminationReason reason) {
        User loginUser = User.builder().id(USER_ID).build();
        AiCodeGeneratorFacade.OnlineControlledTerminationException error =
                new AiCodeGeneratorFacade.OnlineControlledTerminationException(reason);

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () ->
                handler.handle(Flux.error(error), chatHistoryService, APP_ID, loginUser,
                        memorySummaryService, userMemoryService)
                        .then().block());

        verify(chatHistoryService).addChatMessage(
                eq(APP_ID),
                eq("AI回复失败: " + error.getMessage()),
                eq(ChatHistoryMessageTypeEnum.AI.getValue()),
                eq(USER_ID));
        verifyNoMoreInteractions(chatHistoryService);
        verifyNoInteractions(toolMessageCollapser, memorySummaryService,
                userMemoryService, vueProjectBuilder);
    }

}
