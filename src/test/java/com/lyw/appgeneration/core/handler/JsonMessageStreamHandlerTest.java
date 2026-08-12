package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.ai.AiCodeGeneratorService;
import com.lyw.appgeneration.ai.AiGeneratorServiceFactory;
import com.lyw.appgeneration.ai.memory.ToolMessageCollapser;
import com.lyw.appgeneration.constants.AppConstant;
import com.lyw.appgeneration.core.builder.VueProjectBuilder;
import com.lyw.appgeneration.manger.ToolManager;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.model.enums.ChatHistoryMessageTypeEnum;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.UserMemoryService;
import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    private VueProjectBuilder vueProjectBuilder;

    @Mock
    private ToolManager toolManager;

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
    void handle_shouldBuildWithoutAdditionalAiCheck() {
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

        String projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + "/vue_project_" + APP_ID;
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
        inOrder.verify(vueProjectBuilder).buildProjectAsync(projectPath);
        verify(aiGeneratorServiceFactory, never())
                .getAiCodeGeneratorService(anyLong(), any());
        verify(aiCodeGeneratorService, never())
                .generateVueProjectCodeStream(anyLong(), anyString());
        verify(tokenStream, never()).start();
        verify(toolMessageCollapser, never()).restore(anyLong(), anyList());
    }

}
