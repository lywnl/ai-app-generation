package com.lyw.appgeneration.ai;

import com.lyw.appgeneration.ai.tools.BaseTool;
import com.lyw.appgeneration.manger.ToolManager;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.UserMemoryService;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiGeneratorServiceFactoryTest {

    @Test
    void onlineAndEvaluationUseExplicitDisjointTerminalToolWhitelists() {
        AiGeneratorServiceFactory factory = new AiGeneratorServiceFactory();
        ToolManager toolManager = mock(ToolManager.class);
        ReflectionTestUtils.setField(factory, "toolManager", toolManager);

        Set<String> online = factory.onlineVueToolNames();
        Set<String> evaluation = factory.evaluationVueToolNames();
        factory.onlineVueTools();
        factory.evaluationVueTools();

        assertEquals(Set.of(
                "writeFile", "readFile", "modifyFile", "deleteFile", "readDir",
                "buildProject"), online);
        assertEquals(Set.of(
                "writeFile", "readFile", "modifyFile", "deleteFile", "readDir", "exit"),
                evaluation);
        verify(toolManager).getTools(online);
        verify(toolManager).getTools(evaluation);
    }

    @Test
    void eachEvaluationCreatesIndependentAgentAndModelWithoutPersistentMemoryServices() {
        AiGeneratorServiceFactory factory = spy(new AiGeneratorServiceFactory());
        ToolManager toolManager = mock(ToolManager.class);
        RedisChatMemoryStore redisStore = mock(RedisChatMemoryStore.class);
        ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);
        MemorySummaryService memorySummaryService = mock(MemorySummaryService.class);
        UserMemoryService userMemoryService = mock(UserMemoryService.class);
        StreamingChatModel firstModel = mock(StreamingChatModel.class);
        StreamingChatModel secondModel = mock(StreamingChatModel.class);
        doReturn(firstModel, secondModel).when(factory).evaluationStreamingChatModel();
        when(toolManager.getTools(any())).thenReturn(new BaseTool[]{new EvaluationTools()});
        ReflectionTestUtils.setField(factory, "toolManager", toolManager);
        ReflectionTestUtils.setField(factory, "redisChatMemoryStore", redisStore);
        ReflectionTestUtils.setField(factory, "chatHistoryService", chatHistoryService);
        ReflectionTestUtils.setField(factory, "memorySummaryService", memorySummaryService);
        ReflectionTestUtils.setField(factory, "userMemoryService", userMemoryService);

        VueEvaluationCodeGeneratorService first =
                factory.getVueEvaluationCodeGeneratorService(7L);
        VueEvaluationCodeGeneratorService second =
                factory.getVueEvaluationCodeGeneratorService(7L);

        assertNotSame(first, second, "同一 appId 的两个评测也必须使用不同代理");
        verify(factory, times(2)).evaluationStreamingChatModel();
        verifyNoInteractions(redisStore, chatHistoryService,
                memorySummaryService, userMemoryService);
    }

    @Test
    void failedColdHistoryLoadDoesNotReturnOrCacheVueService() {
        AiGeneratorServiceFactory factory = new AiGeneratorServiceFactory();
        RedisChatMemoryStore redisStore = mock(RedisChatMemoryStore.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        ReflectionTestUtils.setField(factory, "redisChatMemoryStore", redisStore);
        ReflectionTestUtils.setField(factory, "chatHistoryService", history);
        when(history.loadChatHistoryToMemory(any(), any(), any(Integer.class)))
                .thenReturn(ChatHistoryService.HistoryLoadResult.failed());

        assertThrows(IllegalStateException.class, () -> factory
                .getAiCodeGeneratorService(7L, com.lyw.appgeneration.model.enums.CodeGenTypeEnum.VUE_PROJECT));
        assertThrows(IllegalStateException.class, () -> factory
                .getAiCodeGeneratorService(7L, com.lyw.appgeneration.model.enums.CodeGenTypeEnum.VUE_PROJECT));

        verify(history, times(2)).loadChatHistoryToMemory(any(), any(), any(Integer.class));
    }

    private static final class EvaluationTools extends BaseTool {

        @Tool("写入") public String writeFile() { return "{}"; }
        @Tool("读取") public String readFile() { return "{}"; }
        @Tool("修改") public String modifyFile() { return "{}"; }
        @Tool("删除") public String deleteFile() { return "{}"; }
        @Tool("目录") public String readDir() { return "{}"; }
        @Tool("退出") public String exit() { return "{}"; }

        @Override public String getToolName() { return "evaluationTools"; }
        @Override public String getDisplayName() { return "评测测试工具"; }
        @Override public String generateToolExecutedResult(
                cn.hutool.json.JSONObject arguments) { return "{}"; }
    }
}
