package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.mapper.AppMemorySummaryMapper;
import com.lyw.appgeneration.model.entity.AppMemorySummary;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.monitor.MemoryCompressionMetricsCollector;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemoryCompressionResult;
import com.lyw.appgeneration.service.UserMemoryService;
import com.lyw.appgeneration.service.impl.MemorySummaryDraftEngine;
import com.lyw.appgeneration.service.impl.MemorySummaryServiceImpl;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 分层记忆一期集成测试:长对话 → 触发摘要 → 冷启动重建带摘要。
 *
 * <p>真实串联 {@link MemorySummaryServiceImpl} + {@link LayeredChatMemory} + {@code MessageWindowChatMemory},
 * 仅 mock 外部边界(DB mapper / 摘要模型 / 历史查询),无需 MySQL/Redis/真实模型即可运行。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
class LayeredMemoryIntegrationTest {

    private static final long APP_ID = 777L;
    private static final String CANNED_SUMMARY = """
            # 应用目标与定位
            待办清单App
            # 用户偏好与硬约束
            不要登录注册;主色 #4A90D9
            # 已否决的方案
            深色模式(用户嫌花)
            # 关键设计决策与理由
            原生JS(用户要轻量)
            # 当前进度速览
            增删改查完成""";

    @Mock
    ChatHistoryService chatHistoryService;
    @Mock
    AppMemorySummaryMapper summaryMapper;
    @Mock
    ChatModel summarizationModel;
    @Mock
    StringRedisTemplate redisTemplate;
    @Mock
    ValueOperations<String, String> valueOps;
    @Mock
    UserMemoryService userMemoryService; // L1 集成测试不验 L2,未表态(recallByApp→null)→L2 层跳过

    private MemorySummaryServiceImpl summaryService;
    private ExecutorService modelExecutor;
    private SimpleMeterRegistry metricsRegistry;
    /** 内存 store 模拟 app_memory_summary 单行持久化。 */
    private final AtomicReference<AppMemorySummary> store = new AtomicReference<>();

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        when(summaryMapper.selectOneByQuery(any())).thenAnswer(inv -> store.get());
        when(summaryMapper.insert(any(AppMemorySummary.class))).thenAnswer(inv -> {
            store.set(inv.getArgument(0));
            return 1;
        });
        when(summaryMapper.update(any(AppMemorySummary.class))).thenAnswer(inv -> {
            store.set(inv.getArgument(0));
            return 1;
        });
        when(summaryMapper.update(
                any(AppMemorySummary.class), eq(false))).thenAnswer(inv -> {
            store.set(inv.getArgument(0));
            return 1;
        });
        when(redisTemplate.opsForValue()).thenReturn(valueOps); // 缓存未命中(get→null)→回退 store(MySQL mock)
        modelExecutor = java.util.concurrent.Executors
                .newVirtualThreadPerTaskExecutor();
        metricsRegistry = new SimpleMeterRegistry();
        MemoryTokenProperties properties = new MemoryTokenProperties();
        ChatTokenEstimator tokenEstimator =
                new ConservativeChatTokenEstimator(properties);
        MemorySummaryDraftEngine draftEngine = new MemorySummaryDraftEngine(
                chatHistoryService,
                summarizationModel,
                modelExecutor,
                tokenEstimator,
                properties);
        PlatformTransactionManager transactionManager = mock(
                PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(
                mock(TransactionStatus.class));
        ObjectProvider<PlatformTransactionManager> transactionManagerProvider =
                mock(ObjectProvider.class);
        when(transactionManagerProvider.getIfAvailable())
                .thenReturn(transactionManager);
        // 直接构造真实摘要服务，外部模型、数据库和 Redis 保持 mock。
        summaryService = new MemorySummaryServiceImpl(
                summaryMapper,
                draftEngine,
                mock(ExecutorService.class),
                redisTemplate,
                new AppDataLifecycleFence(),
                tokenEstimator,
                properties,
                new MemoryCompressionMetricsCollector(metricsRegistry),
                transactionManagerProvider);
    }

    @AfterEach
    void tearDown() {
        modelExecutor.shutdownNow();
        metricsRegistry.close();
    }

    private ChatHistory msg(long id, String type, String text) {
        return ChatHistory.builder().id(id).messageType(type).message(text).build();
    }

    @Test
    void longConversationSummarizedThenColdStartRebuildCarriesSummary() {
        // 1. 长对话：游标之后有 5 个完整回合。
        List<ChatHistory> history = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            history.add(msg(i, i % 2 == 1 ? "user" : "ai", "第" + i + "条对话"));
        }
        when(chatHistoryService.listMessagesAfterCursor(eq(APP_ID), any(), anyInt())).thenReturn(history);
        when(summarizationModel.chat(anyString())).thenReturn(CANNED_SUMMARY);

        // 2. 同步提炼:断言摘要落库 + 游标推进到最后一条 id
        MemoryCompressionResult compression = summaryService.compressNow(
                APP_ID, 10L, Duration.ofSeconds(60));
        assertEquals(MemoryCompressionResult.Status.COMPRESSED,
                compression.status());
        AppMemorySummary persisted = store.get();
        assertNotNull(persisted, "摘要应已落库");
        assertEquals(10L, persisted.getLastSummarizedId(), "游标应推进到最新消息id");
        assertTrue(persisted.getSummary().contains("不要登录"), "摘要应保留用户硬约束(最不可推导)");

        // 3. 冷启动:全新空 delegate(模拟 Redis 失效/重启)+ 回填最近原文 + 装饰器包裹
        MessageWindowChatMemory delegate = MessageWindowChatMemory.builder()
                .id(APP_ID)
                .maxMessages(Integer.MAX_VALUE)
                .build();
        delegate.add(UserMessage.from("继续:加个搜索框"));   // 模拟 loadChatHistoryToMemory 回填的最近原文
        delegate.add(AiMessage.from("已添加搜索框"));
        LayeredChatMemory mem = new LayeredChatMemory(delegate, summaryService, userMemoryService);

        // 4. messages() = [摘要User, 确认Ai, 回填原文...];第0条含摘要,且全程无连续同角色
        List<ChatMessage> msgs = mem.messages();
        assertEquals(4, msgs.size(), "摘要对(2) + 回填原文(2)");
        assertInstanceOf(UserMessage.class, msgs.get(0));
        assertTrue(((UserMessage) msgs.get(0)).singleText().contains("不要登录"),
                "冷启动后早期硬约束应由 L1 摘要带回(解决长对话失忆 + 冷启动断裂)");
        assertInstanceOf(AiMessage.class, msgs.get(1));
        for (int i = 1; i < msgs.size(); i++) {
            assertNotEquals(msgs.get(i - 1).type(), msgs.get(i).type(),
                    "位置 " + i + " 出现连续同角色,会被 DeepSeek/OpenAI API 拒绝");
        }
    }
}
