package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.mapper.AppMapper;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.mapper.AppMemoryExtractCursorMapper;
import com.lyw.appgeneration.mapper.AppMemoryMapper;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.entity.AppMemory;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.impl.UserMemoryServiceImpl;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** L2 跨 app 集成:appA 抽取偏好 → 落库 → appB 召回带出。 */
class LayeredMemoryL2IntegrationTest {

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

        // 用公共生产构造器(默认阈值 8),对齐 L1 集成测试范式——跨包无法访问包级测试构造器
        UserMemoryServiceImpl l2 = new UserMemoryServiceImpl(chatHistory, memMapper, cursorMapper,
                appMapper, model, Executors.newSingleThreadExecutor(), redis,
                new AppDataLifecycleFence());

        // —— appA:用户表达跨 app 偏好,抽取落库 ——
        when(cursorMapper.selectOneByQuery(any())).thenReturn(null);
        // 补足到 8 条以触发默认抽取阈值(首条承载跨 app 偏好,其余为填充)
        List<ChatHistory> appAHistory = new ArrayList<>();
        appAHistory.add(ChatHistory.builder().id(11L).messageType("user")
                .message("以后所有应用都用简体中文、扁平极简").build());
        for (long i = 12L; i <= 18L; i++) {
            appAHistory.add(ChatHistory.builder().id(i)
                    .messageType(i % 2 == 0 ? "ai" : "user").message("第" + i + "轮").build());
        }
        when(chatHistory.listMessagesAfterCursor(eq(appA), eq(0L), anyInt())).thenReturn(appAHistory);
        when(model.chat(anyString()))
                .thenReturn("[{\"name\":\"语言偏好\",\"content\":\"简体中文\"},{\"name\":\"视觉风格\",\"content\":\"扁平极简\"}]");
        l2.extractNow(user, appA);
        assertEquals(2, table.size(), "appA 抽取应落 2 条偏好");

        // —— appB:召回(缓存未命中),应带出 appA 的偏好 ——
        when(appMapper.selectOneById(appB)).thenReturn(App.builder().id(appB).userId(user).build());
        when(ops.get("mem:pref:" + user)).thenReturn(null);

        MessageWindowChatMemory delegate = MessageWindowChatMemory.builder().id(appB).maxMessages(100).build();
        delegate.add(UserMessage.from("帮我做个新页面"));
        MemorySummaryService summary = mock(MemorySummaryService.class);
        when(summary.getCurrentSummary(appB)).thenReturn("");

        LayeredChatMemory mem = new LayeredChatMemory(delegate, summary, l2);
        List<ChatMessage> msgs = mem.messages();

        // 首条应是 appB 召回的 L2 偏好,内容来自 appA
        assertTrue(((UserMessage) msgs.get(0)).singleText().contains("简体中文"));
        assertTrue(((UserMessage) msgs.get(0)).singleText().contains("扁平极简"));
    }
}
