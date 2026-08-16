package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.memory.ChatTokenEstimator;
import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.mapper.AppMapper;
import com.lyw.appgeneration.mapper.AppMemoryExtractCursorMapper;
import com.lyw.appgeneration.mapper.AppMemoryMapper;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.entity.AppMemory;
import com.lyw.appgeneration.model.entity.AppMemoryExtractCursor;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.monitor.MemoryCompressionMetricsCollector;
import com.lyw.appgeneration.service.ChatHistoryService;
import dev.langchain4j.model.chat.ChatModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserMemoryCacheConsistencyTest {

    private static final long USER_ID = 7L;
    private static final long APP_A = 100L;
    private static final long APP_B = 200L;

    private final SimpleMeterRegistry meterRegistry =
            new SimpleMeterRegistry();

    @AfterEach
    void tearDown() {
        meterRegistry.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 新版缓存删除失败时偏好事务与游标均不提交() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        AppMemoryMapper memoryMapper = mock(AppMemoryMapper.class);
        AppMemoryExtractCursorMapper cursorMapper =
                mock(AppMemoryExtractCursorMapper.class);
        AppMapper appMapper = mock(AppMapper.class);
        ChatModel model = mock(ChatModel.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations =
                mock(ValueOperations.class);
        ChatTokenEstimator estimator = mock(ChatTokenEstimator.class);
        CountingTransactions transactions = new CountingTransactions();
        MemoryTokenProperties properties = new MemoryTokenProperties();
        String cacheKey = "mem:pref:v2:" + USER_ID;
        String legacyKey = "mem:pref:" + USER_ID;
        String oldRecall = "- 视觉风格:偏好浅色界面";

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(cacheKey)).thenReturn(oldRecall);
        doThrow(new IllegalStateException("redis delete down"))
                .when(redisTemplate).delete(cacheKey);
        when(historyService.listMessagesAfterCursor(
                eq(APP_A), anyLong(), anyInt())).thenReturn(List.of(
                history(11L, "user", "以后都使用深色界面"),
                history(12L, "ai", "已调整")));
        when(memoryMapper.selectListByQuery(any())).thenReturn(List.of());
        when(memoryMapper.selectOneByQuery(any())).thenReturn(null);
        when(memoryMapper.insert(any(AppMemory.class))).thenReturn(1);
        when(cursorMapper.selectOneByQuery(any())).thenReturn(null);
        when(cursorMapper.insert(any(AppMemoryExtractCursor.class)))
                .thenReturn(1);
        when(appMapper.selectOneById(APP_B)).thenReturn(
                App.builder().id(APP_B).userId(USER_ID).build());
        when(model.chat(anyString())).thenReturn("""
                [{"name":"视觉风格","content":"偏好深色界面",
                "evidenceType":"EXPLICIT","turnIds":[11]}]
                """);
        when(estimator.estimateText(anyString())).thenReturn(100);

        UserMemoryServiceImpl service = new UserMemoryServiceImpl(
                historyService,
                memoryMapper,
                cursorMapper,
                appMapper,
                model,
                mock(ExecutorService.class),
                mock(TaskScheduler.class),
                redisTemplate,
                new AppDataLifecycleFence(),
                estimator,
                properties,
                transactions,
                new MemoryCompressionMetricsCollector(meterRegistry),
                Clock.fixed(Instant.parse("2026-08-16T08:00:00Z"),
                        ZoneOffset.UTC));

        service.extractNow(USER_ID, APP_A);
        String recalledFromOtherApp = service.recallByApp(APP_B);

        assertEquals(0, transactions.executionCount());
        verify(memoryMapper, never()).insert(any(AppMemory.class));
        ArgumentCaptor<AppMemoryExtractCursor> failedCursor =
                ArgumentCaptor.forClass(AppMemoryExtractCursor.class);
        verify(cursorMapper).insert(failedCursor.capture());
        assertEquals(0L, failedCursor.getValue().getLastExtractedId());
        assertEquals(1, failedCursor.getValue().getFailCount());
        assertFalse(recalledFromOtherApp.contains("深色"));
        assertEquals(oldRecall, recalledFromOtherApp);
        verify(redisTemplate).delete(cacheKey);
        verify(redisTemplate, never()).delete(legacyKey);
    }

    private ChatHistory history(long id, String type, String text) {
        return ChatHistory.builder()
                .id(id)
                .appId(APP_A)
                .userId(USER_ID)
                .messageType(type)
                .message(text)
                .build();
    }

    private static final class CountingTransactions
            implements TransactionOperations {

        private int executionCount;

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            executionCount++;
            TransactionStatus status = new SimpleTransactionStatus();
            return action.doInTransaction(status);
        }

        private int executionCount() {
            return executionCount;
        }
    }
}
