package com.lyw.appgeneration.ai;

import com.github.benmanes.caffeine.cache.Cache;
import com.lyw.appgeneration.ai.memory.ChatTokenEstimator;
import com.lyw.appgeneration.ai.memory.CompressionAwareChatMemory;
import com.lyw.appgeneration.ai.memory.LayeredChatMemory;
import com.lyw.appgeneration.ai.memory.TokenAwareChatMemory;
import com.lyw.appgeneration.ai.memory.AtomicChatMemoryStore;
import com.lyw.appgeneration.ai.tools.BaseTool;
import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.manger.ToolManager;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.UserMemoryService;
import com.lyw.appgeneration.service.MemoryCacheInvalidationResult;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.monitor.VueBuildRepairMetricsCollector;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiGeneratorServiceFactoryTest {

    @Test
    @SuppressWarnings("unchecked")
    void invalidationTargetsOneServiceKeyAndStillClearsCaffeineWhenRedisFails() {
        AiGeneratorServiceFactory factory = new AiGeneratorServiceFactory();
        AtomicChatMemoryStore redisStore = atomicStore(
                failingDeleteStore());
        ReflectionTestUtils.setField(factory, "atomicChatMemoryStore", redisStore);
        Cache<String, AiCodeGeneratorService> cache =
                (Cache<String, AiCodeGeneratorService>) ReflectionTestUtils.getField(
                        factory, "serviceCache");
        AiCodeGeneratorService vue = mock(AiCodeGeneratorService.class);
        AiCodeGeneratorService html = mock(AiCodeGeneratorService.class);
        AiCodeGeneratorService multiFile = mock(AiCodeGeneratorService.class);
        AiCodeGeneratorService otherAppVue = mock(AiCodeGeneratorService.class);
        cache.put("7_vue_project", vue);
        cache.put("7_html", html);
        cache.put("7_multi_file", multiFile);
        cache.put("8_vue_project", otherAppVue);
        MemoryCacheInvalidationResult result = factory.invalidateAndClearMemory(
                7L, CodeGenTypeEnum.VUE_PROJECT);

        assertEquals(null, cache.getIfPresent("7_vue_project"));
        assertEquals(html, cache.getIfPresent("7_html"));
        assertEquals(multiFile, cache.getIfPresent("7_multi_file"));
        assertEquals(otherAppVue, cache.getIfPresent("8_vue_project"));
        assertEquals(Set.of("L0_REDIS"), result.failedTargets());
    }

    @Test
    @SuppressWarnings("unchecked")
    void caffeineFailureDoesNotPreventAppRedisCleanup() {
        AiGeneratorServiceFactory factory = new AiGeneratorServiceFactory();
        dev.langchain4j.store.memory.chat.ChatMemoryStore delegate =
                mock(dev.langchain4j.store.memory.chat.ChatMemoryStore.class);
        AtomicChatMemoryStore redisStore = atomicStore(delegate);
        Cache<String, AiCodeGeneratorService> cache = mock(Cache.class);
        doThrow(new IllegalStateException("caffeine down"))
                .when(cache).invalidate("7_vue_project");
        ReflectionTestUtils.setField(factory, "atomicChatMemoryStore", redisStore);
        ReflectionTestUtils.setField(factory, "serviceCache", cache);

        MemoryCacheInvalidationResult result = factory.invalidateAndClearMemory(
                7L, CodeGenTypeEnum.VUE_PROJECT);

        assertEquals(Set.of("L0_SERVICE_CAFFEINE"), result.failedTargets());
        verify(delegate).deleteMessages(7L);
    }

    @Test
    void invalidationRejectsInvalidScopeBeforeSideEffects() {
        AiGeneratorServiceFactory factory = new AiGeneratorServiceFactory();
        dev.langchain4j.store.memory.chat.ChatMemoryStore delegate =
                mock(dev.langchain4j.store.memory.chat.ChatMemoryStore.class);
        AtomicChatMemoryStore redisStore = atomicStore(delegate);
        ReflectionTestUtils.setField(factory, "atomicChatMemoryStore", redisStore);

        assertThrows(IllegalArgumentException.class, () ->
                factory.invalidateAndClearMemory(0L, CodeGenTypeEnum.VUE_PROJECT));
        assertThrows(IllegalArgumentException.class, () ->
                factory.invalidateAndClearMemory(7L, null));
        verifyNoInteractions(delegate);
    }

    @Test
    void onlineAndEvaluationUseExplicitDisjointTerminalToolWhitelists() {
        AiGeneratorServiceFactory factory = new AiGeneratorServiceFactory();
        ToolManager toolManager = mock(ToolManager.class);
        ReflectionTestUtils.setField(factory, "toolManager", toolManager);

        List<String> online = factory.onlineVueToolNames();
        List<String> evaluation = factory.evaluationVueToolNames();
        factory.onlineVueTools();
        factory.evaluationVueTools();

        assertEquals(List.of(
                "writeFile", "readFile", "modifyFile", "deleteFile", "readDir",
                "buildProject"), online);
        assertEquals(List.of(
                "writeFile", "readFile", "modifyFile", "deleteFile", "readDir", "exit"),
                evaluation);
        verify(toolManager).requireTools(online.toArray(String[]::new));
        verify(toolManager).requireTools(evaluation.toArray(String[]::new));
    }

    @Test
    void eachEvaluationCreatesIndependentAgentAndModelWithoutPersistentMemoryServices() {
        AiGeneratorServiceFactory factory = spy(new AiGeneratorServiceFactory());
        ToolManager toolManager = mock(ToolManager.class);
        dev.langchain4j.store.memory.chat.ChatMemoryStore delegate =
                mock(dev.langchain4j.store.memory.chat.ChatMemoryStore.class);
        AtomicChatMemoryStore redisStore = atomicStore(delegate);
        ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);
        MemorySummaryService memorySummaryService = mock(MemorySummaryService.class);
        UserMemoryService userMemoryService = mock(UserMemoryService.class);
        StreamingChatModel firstModel = mock(StreamingChatModel.class);
        StreamingChatModel secondModel = mock(StreamingChatModel.class);
        doReturn(firstModel, secondModel).when(factory).evaluationStreamingChatModel();
        when(toolManager.requireTools(any(String[].class)))
                .thenReturn(new BaseTool[]{new EvaluationTools()});
        ReflectionTestUtils.setField(factory, "toolManager", toolManager);
        ReflectionTestUtils.setField(factory, "atomicChatMemoryStore", redisStore);
        ReflectionTestUtils.setField(factory, "chatHistoryService", chatHistoryService);
        ReflectionTestUtils.setField(factory, "memorySummaryService", memorySummaryService);
        ReflectionTestUtils.setField(factory, "userMemoryService", userMemoryService);

        VueEvaluationCodeGeneratorService first =
                factory.getVueEvaluationCodeGeneratorService(7L);
        VueEvaluationCodeGeneratorService second =
                factory.getVueEvaluationCodeGeneratorService(7L);

        assertNotSame(first, second, "同一 appId 的两个评测也必须使用不同代理");
        verify(factory, times(2)).evaluationStreamingChatModel();
        verifyNoInteractions(delegate, chatHistoryService,
                memorySummaryService, userMemoryService);
    }

    @Test
    void onlineMemoryUsesTokenAwareUnlimitedWindowAndTokenColdLoad() {
        AiGeneratorServiceFactory factory = new AiGeneratorServiceFactory();
        AtomicChatMemoryStore redisStore = statefulRedisStore();
        ChatHistoryService history = mock(ChatHistoryService.class);
        MemorySummaryService summary = mock(MemorySummaryService.class);
        ChatTokenEstimator estimator = mock(ChatTokenEstimator.class);
        MemoryTokenProperties properties = new MemoryTokenProperties();
        ReflectionTestUtils.setField(factory, "atomicChatMemoryStore", redisStore);
        ReflectionTestUtils.setField(factory, "chatHistoryService", history);
        ReflectionTestUtils.setField(factory, "chatTokenEstimator", estimator);
        ReflectionTestUtils.setField(factory, "memoryTokenProperties", properties);
        ReflectionTestUtils.setField(factory, "memorySummaryService", summary);
        ReflectionTestUtils.setField(factory, "userMemoryService",
                mock(UserMemoryService.class));
        when(history.loadRecentCompleteTurnsToMemory(
                any(), any(), any(Integer.class), any()))
                .thenReturn(ChatHistoryService.HistoryLoadResult.empty());
        when(history.loadRecentCompleteTurnsToMemory(
                any(), any(Long.class), any(), any(Integer.class), any()))
                .thenReturn(ChatHistoryService.HistoryLoadResult.empty());
        when(summary.lastSummarizedId(7L)).thenReturn(4L);

        LayeredChatMemory layered = factory.createOnlineChatMemory(
                7L, CodeGenTypeEnum.HTML);

        ArgumentCaptor<ChatMemory> memoryCaptor =
                ArgumentCaptor.forClass(ChatMemory.class);
        verify(summary).lastSummarizedId(7L);
        verify(history).loadRecentCompleteTurnsToMemory(
                eq(7L), eq(4L), memoryCaptor.capture(), eq(30_720),
                same(estimator));
        ChatMemory l0 = memoryCaptor.getValue();
        assertInstanceOf(TokenAwareChatMemory.class, l0);
        assertInstanceOf(CompressionAwareChatMemory.class, layered);
        assertEquals(7L, layered.id());
        for (int turn = 0; turn < 60; turn++) {
            layered.add(UserMessage.from("问题-" + turn));
            layered.add(AiMessage.from("回复-" + turn));
        }
        assertEquals(120, l0.messages().size());
        verify(history, never()).loadChatHistoryToMemory(
                any(), any(), any(Integer.class));
    }

    @Test
    void failedColdHistoryLoadDoesNotReturnOrCacheVueService() {
        AiGeneratorServiceFactory factory = new AiGeneratorServiceFactory();
        AtomicChatMemoryStore redisStore = emptyAtomicStore();
        ChatHistoryService history = mock(ChatHistoryService.class);
        MemorySummaryService summary = mock(MemorySummaryService.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(factory, "atomicChatMemoryStore", redisStore);
        ReflectionTestUtils.setField(factory, "chatHistoryService", history);
        ReflectionTestUtils.setField(factory, "memorySummaryService", summary);
        ReflectionTestUtils.setField(factory, "vueBuildRepairMetricsCollector",
                new VueBuildRepairMetricsCollector(registry));
        ReflectionTestUtils.setField(factory, "chatTokenEstimator",
                mock(ChatTokenEstimator.class));
        ReflectionTestUtils.setField(factory, "memoryTokenProperties",
                new MemoryTokenProperties());
        when(summary.lastSummarizedId(7L)).thenReturn(0L);
        when(history.loadRecentCompleteTurnsToMemory(
                any(), any(Long.class), any(), any(Integer.class), any()))
                .thenReturn(ChatHistoryService.HistoryLoadResult.failed());

        assertThrows(IllegalStateException.class, () -> factory
                .getAiCodeGeneratorService(7L, CodeGenTypeEnum.VUE_PROJECT));
        assertThrows(IllegalStateException.class, () -> factory
                .getAiCodeGeneratorService(7L, CodeGenTypeEnum.VUE_PROJECT));

        verify(history, times(2)).loadRecentCompleteTurnsToMemory(
                any(), any(Long.class), any(), any(Integer.class), any());
        assertEquals(2.0, registry.get("vue_memory_l0_sync_total")
                .tags("action", "rebuild", "result", "failed")
                .counter().count());
    }

    @Test
    void cursorReadFailureClosesColdRebuildWithoutLoadingHistory() {
        AiGeneratorServiceFactory factory = new AiGeneratorServiceFactory();
        ChatHistoryService history = mock(ChatHistoryService.class);
        MemorySummaryService summary = mock(MemorySummaryService.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(factory, "atomicChatMemoryStore",
                emptyAtomicStore());
        ReflectionTestUtils.setField(factory, "chatHistoryService", history);
        ReflectionTestUtils.setField(factory, "memorySummaryService", summary);
        ReflectionTestUtils.setField(factory, "vueBuildRepairMetricsCollector",
                new VueBuildRepairMetricsCollector(registry));
        ReflectionTestUtils.setField(factory, "chatTokenEstimator",
                mock(ChatTokenEstimator.class));
        ReflectionTestUtils.setField(factory, "memoryTokenProperties",
                new MemoryTokenProperties());
        when(summary.lastSummarizedId(7L))
                .thenThrow(new IllegalStateException("cursor read failed"));

        assertThrows(IllegalStateException.class, () ->
                factory.createOnlineChatMemory(7L,
                        CodeGenTypeEnum.VUE_PROJECT));

        verifyNoInteractions(history);
        assertEquals(1.0, registry.get("vue_memory_l0_sync_total")
                .tags("action", "rebuild", "result", "failed")
                .counter().count());
    }

    @ParameterizedTest
    @EnumSource(value = CodeGenTypeEnum.class, names = {"HTML", "MULTI_FILE"})
    void nonVueColdHistoryLoadFailureDoesNotRecordVueMetric(
            CodeGenTypeEnum codeGenType) {
        AiGeneratorServiceFactory factory = new AiGeneratorServiceFactory();
        ChatHistoryService history = mock(ChatHistoryService.class);
        MemorySummaryService summary = mock(MemorySummaryService.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(factory, "atomicChatMemoryStore",
                emptyAtomicStore());
        ReflectionTestUtils.setField(factory, "chatHistoryService", history);
        ReflectionTestUtils.setField(factory, "memorySummaryService", summary);
        ReflectionTestUtils.setField(factory, "vueBuildRepairMetricsCollector",
                new VueBuildRepairMetricsCollector(registry));
        ReflectionTestUtils.setField(factory, "chatTokenEstimator",
                mock(ChatTokenEstimator.class));
        ReflectionTestUtils.setField(factory, "memoryTokenProperties",
                new MemoryTokenProperties());
        when(summary.lastSummarizedId(7L)).thenReturn(0L);
        when(history.loadRecentCompleteTurnsToMemory(
                any(), any(Long.class), any(), any(Integer.class), any()))
                .thenReturn(ChatHistoryService.HistoryLoadResult.failed());

        assertThrows(IllegalStateException.class, () -> factory
                .getAiCodeGeneratorService(7L, codeGenType));

        assertEquals(null, registry.find("vue_memory_l0_sync_total").counter());
    }

    private AtomicChatMemoryStore statefulRedisStore() {
        dev.langchain4j.store.memory.chat.ChatMemoryStore delegate = mock(
                dev.langchain4j.store.memory.chat.ChatMemoryStore.class);
        AtomicReference<List<ChatMessage>> messages =
                new AtomicReference<>(List.of());
        when(delegate.getMessages(any())).thenAnswer(invocation ->
                messages.get());
        org.mockito.Mockito.doAnswer(invocation -> {
            List<ChatMessage> updated = invocation.getArgument(1);
            messages.set(List.copyOf(updated));
            return null;
        }).when(delegate).updateMessages(any(), anyList());
        return new AtomicChatMemoryStore(delegate);
    }

    private AtomicChatMemoryStore emptyAtomicStore() {
        return atomicStore(mock(
                dev.langchain4j.store.memory.chat.ChatMemoryStore.class));
    }

    private AtomicChatMemoryStore atomicStore(
            dev.langchain4j.store.memory.chat.ChatMemoryStore delegate) {
        return new AtomicChatMemoryStore(delegate);
    }

    private dev.langchain4j.store.memory.chat.ChatMemoryStore
            failingDeleteStore() {
        dev.langchain4j.store.memory.chat.ChatMemoryStore delegate = mock(
                dev.langchain4j.store.memory.chat.ChatMemoryStore.class);
        doThrow(new IllegalStateException("redis down"))
                .when(delegate).deleteMessages(7L);
        return delegate;
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
