package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.mapper.AppMapper;
import com.lyw.appgeneration.mapper.AppMemoryExtractCursorMapper;
import com.lyw.appgeneration.mapper.AppMemoryMapper;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.entity.AppMemory;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.monitor.MemoryCompressionMetricsCollector;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.impl.UserMemoryServiceImpl;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.support.TransactionOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** L2 跨 app 集成:appA 抽取偏好 → 落库 → appB 召回带出。 */
class LayeredMemoryL2IntegrationTest {

    private final SimpleMeterRegistry metricsRegistry =
            new SimpleMeterRegistry();

    @AfterEach
    void closeMetricsRegistry() {
        metricsRegistry.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void preferenceExtractedInAppAIsRecalledInAppB() {
        Long user = 7L, appA = 100L, appB = 200L;

        ChatHistoryService chatHistory = mock(ChatHistoryService.class);
        AppMemoryMapper memMapper = mock(AppMemoryMapper.class);
        AppMemoryExtractCursorMapper cursorMapper = mock(AppMemoryExtractCursorMapper.class);
        AppMapper appMapper = mock(AppMapper.class);
        dev.langchain4j.model.chat.ChatModel model = mock(dev.langchain4j.model.chat.ChatModel.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        lenient().when(redis.opsForValue()).thenReturn(ops);

        // 用内存 List 模拟 app_memory 表
        List<AppMemory> table = new ArrayList<>();
        when(memMapper.selectOneByQuery(any())).thenReturn(null); // 两条 name 不同,均走 insert 分支
        doAnswer(inv -> { table.add(inv.getArgument(0)); return 1; }).when(memMapper).insert(any());
        when(memMapper.selectListByQuery(any())).thenReturn(table);

        MemoryTokenProperties tokenProperties = new MemoryTokenProperties();
        tokenProperties.setEstimationSafetyFactor(1D);
        ChatTokenEstimator tokenEstimator =
                new ConservativeChatTokenEstimator(tokenProperties);

        // 跨包使用正式生产构造器，验证 Spring 依赖签名与真实召回契约一致。
        UserMemoryServiceImpl l2 = new UserMemoryServiceImpl(chatHistory, memMapper, cursorMapper,
                appMapper, model, mock(ExecutorService.class),
                mock(TaskScheduler.class), redis,
                new AppDataLifecycleFence(), tokenEstimator,
                tokenProperties,
                TransactionOperations.withoutTransaction(),
                new MemoryCompressionMetricsCollector(metricsRegistry));

        // —— appA:用户表达跨 app 偏好,抽取落库 ——
        when(cursorMapper.selectOneByQuery(any())).thenReturn(null);
        List<ChatHistory> appAHistory = new ArrayList<>();
        appAHistory.add(ChatHistory.builder().id(11L).messageType("user")
                .message("以后所有应用都用简体中文、扁平极简").build());
        appAHistory.add(ChatHistory.builder().id(12L).messageType("ai")
                .message("已完成").build());
        when(chatHistory.listMessagesAfterCursor(eq(appA), eq(0L), anyInt())).thenReturn(appAHistory);
        when(cursorMapper.insert(any())).thenReturn(1);
        when(model.chat(anyString()))
                .thenReturn("""
                        [
                          {"name":"语言偏好","valueCodes":["ZH_CN"],
                           "evidenceType":"EXPLICIT","turnIds":[11]},
                          {"name":"视觉风格","valueCodes":["MINIMAL","FLAT"],
                           "evidenceType":"EXPLICIT","turnIds":[11]}
                        ]
                        """);
        l2.extractNow(user, appA);
        assertEquals(2, table.size(), "appA 抽取应落 2 条偏好");
        assertTrue(table.stream().allMatch(
                memory -> "ACTIVE".equals(memory.getStatus())));

        // —— appB:召回(缓存未命中),应带出 appA 的偏好 ——
        when(appMapper.selectOneById(appB)).thenReturn(App.builder().id(appB).userId(user).build());
        when(ops.get("mem:pref:v2:" + user)).thenReturn(null);

        MessageWindowChatMemory delegate = MessageWindowChatMemory.builder()
                .id(appB)
                .maxMessages(Integer.MAX_VALUE)
                .build();
        delegate.add(UserMessage.from("帮我做个新页面"));
        MemorySummaryService summary = mock(MemorySummaryService.class);
        when(summary.getCurrentSummary(appB)).thenReturn("");

        LayeredChatMemory mem = new LayeredChatMemory(delegate, summary, l2);
        List<ChatMessage> msgs = mem.messages();

        // 首条应是 appB 召回的 L2 偏好,内容来自 appA
        assertTrue(((UserMessage) msgs.get(0)).singleText().contains("简体中文"));
        assertTrue(((UserMessage) msgs.get(0)).singleText().contains("极简、扁平化"));
        verify(ops).get("mem:pref:v2:" + user);
        verify(ops, never()).get("mem:pref:" + user);
    }
}
