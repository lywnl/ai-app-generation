package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.ai.AiCodeGeneratorService;
import com.lyw.appgeneration.ai.AiGeneratorServiceFactory;
import com.lyw.appgeneration.constants.AppConstant;
import com.lyw.appgeneration.core.builder.VueProjectBuilder;
import com.lyw.appgeneration.manger.ToolManager;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.model.enums.ChatHistoryMessageTypeEnum;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.mybatisflex.core.query.QueryWrapper;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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

    @InjectMocks
    private JsonMessageStreamHandler handler;

    @SuppressWarnings("unchecked")
    @Test
    void handle_shouldCheckWithAiBeforeBuild() {
        AtomicReference<Consumer<ChatResponse>> onComplete = new AtomicReference<>();

        when(aiGeneratorServiceFactory.getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.VUE_PROJECT))
                .thenReturn(aiCodeGeneratorService);
        when(aiCodeGeneratorService.generateVueProjectCodeStream(eq(APP_ID), anyString()))
                .thenReturn(tokenStream);
        when(tokenStream.onPartialResponse(any())).thenReturn(tokenStream);
        when(tokenStream.onCompleteResponse(any())).thenAnswer(invocation -> {
            onComplete.set((Consumer<ChatResponse>) invocation.getArgument(0));
            return tokenStream;
        });
        when(tokenStream.onError(any())).thenReturn(tokenStream);
        doAnswer(invocation -> {
            Consumer<ChatResponse> callback = onComplete.get();
            if (callback != null) {
                callback.accept(null);
            }
            return null;
        }).when(tokenStream).start();

        User loginUser = User.builder().id(USER_ID).build();
        List<String> output = handler.handle(
                Flux.just("{\"type\":\"ai_response\",\"data\":\"ok\"}"),
                chatHistoryService,
                APP_ID,
                loginUser,
                memorySummaryService
        ).collectList().block();

        assertEquals(List.of("ok"), output);

        String projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + "/vue_project_" + APP_ID;
        InOrder inOrder = inOrder(
                chatHistoryService,
                aiGeneratorServiceFactory,
                aiCodeGeneratorService,
                tokenStream,
                vueProjectBuilder
        );
        inOrder.verify(chatHistoryService).addChatMessage(
                eq(APP_ID),
                eq("ok"),
                eq(ChatHistoryMessageTypeEnum.AI.getValue()),
                eq(USER_ID)
        );
        inOrder.verify(aiGeneratorServiceFactory)
                .getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.VUE_PROJECT);
        inOrder.verify(aiCodeGeneratorService)
                .generateVueProjectCodeStream(eq(APP_ID), contains("构建前代码自检"));
        inOrder.verify(tokenStream).onPartialResponse(any());
        inOrder.verify(tokenStream).start();
        inOrder.verify(vueProjectBuilder).buildProjectAsync(projectPath);
    }

    @Test
    void handle_shouldSkipPreBuildCheckWhenNotFirstDialogue() {
        when(chatHistoryService.count(any(QueryWrapper.class))).thenReturn(1L);

        User loginUser = User.builder().id(USER_ID).build();
        List<String> output = handler.handle(
                Flux.just("{\"type\":\"ai_response\",\"data\":\"ok\"}"),
                chatHistoryService,
                APP_ID,
                loginUser,
                memorySummaryService
        ).collectList().block();

        assertEquals(List.of("ok"), output);

        String projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + "/vue_project_" + APP_ID;
        verify(chatHistoryService).addChatMessage(
                eq(APP_ID),
                eq("ok"),
                eq(ChatHistoryMessageTypeEnum.AI.getValue()),
                eq(USER_ID)
        );
        verify(aiGeneratorServiceFactory, never())
                .getAiCodeGeneratorService(anyLong(), any());
        verify(aiCodeGeneratorService, never())
                .generateVueProjectCodeStream(anyLong(), anyString());
        verify(tokenStream, never()).start();
        verify(vueProjectBuilder).buildProjectAsync(projectPath);
    }
}
