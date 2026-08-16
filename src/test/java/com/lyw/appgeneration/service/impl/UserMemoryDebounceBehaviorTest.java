package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.memory.ChatTokenEstimator;
import com.lyw.appgeneration.ai.memory.ConservativeChatTokenEstimator;
import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.mapper.AppMapper;
import com.lyw.appgeneration.mapper.AppMemoryExtractCursorMapper;
import com.lyw.appgeneration.mapper.AppMemoryMapper;
import com.lyw.appgeneration.model.entity.AppMemoryExtractCursor;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.monitor.MemoryCompressionMetricsCollector;
import com.lyw.appgeneration.monitor.ThrowingMeterRegistry;
import com.lyw.appgeneration.service.ChatHistoryService;
import dev.langchain4j.model.chat.ChatModel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.MockMakers.INLINE;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class UserMemoryDebounceBehaviorTest {

    private static final long USER_ID = 7L;
    private static final long APP_A = 100L;
    private static final long APP_B = 200L;

    private ChatHistoryService chatHistoryService;
    private AppMemoryMapper memoryMapper;
    private AppMemoryExtractCursorMapper cursorMapper;
    private AppMapper appMapper;
    private ChatModel model;
    private StringRedisTemplate redisTemplate;
    private AppDataLifecycleFence lifecycleFence;
    private MutableClock clock;
    private AdvancingTaskScheduler scheduler;
    private QueuedExecutor worker;
    private MemoryTokenProperties properties;
    private ChatTokenEstimator tokenEstimator;
    private SimpleMeterRegistry metricsRegistry;
    private MemoryCompressionMetricsCollector metricsCollector;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void 初始化依赖() {
        chatHistoryService = mock(ChatHistoryService.class);
        memoryMapper = mock(AppMemoryMapper.class);
        cursorMapper = mock(AppMemoryExtractCursorMapper.class);
        appMapper = mock(AppMapper.class);
        model = mock(ChatModel.class);
        redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations =
                mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lifecycleFence = new AppDataLifecycleFence();
        clock = new MutableClock(
                Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC);
        scheduler = new AdvancingTaskScheduler(clock);
        worker = new QueuedExecutor();
        properties = new MemoryTokenProperties();
        properties.setEstimationSafetyFactor(1D);
        tokenEstimator = new ConservativeChatTokenEstimator(properties);
        metricsRegistry = new SimpleMeterRegistry();
        metricsCollector = new MemoryCompressionMetricsCollector(
                metricsRegistry);

        when(memoryMapper.selectListByQuery(any())).thenReturn(List.of());
        when(memoryMapper.insert(any())).thenReturn(1);
        when(memoryMapper.update(any())).thenReturn(1);
        when(cursorMapper.insert(any())).thenReturn(1);
        when(cursorMapper.update(any())).thenReturn(1);
        when(cursorMapper.update(
                any(AppMemoryExtractCursor.class), eq(false))).thenReturn(1);
        when(model.chat(any(String.class))).thenReturn("[]");
        when(chatHistoryService.getLastMessage(anyLong()))
                .thenAnswer(invocation -> {
                    long appId = invocation.getArgument(0);
                    return ChatHistory.builder().id(appId * 100L + 8L)
                            .appId(appId).userId(USER_ID)
                            .messageType("ai").message("最新稳定回复")
                            .build();
                });
    }

    @AfterEach
    void closeMetricsRegistry() {
        metricsRegistry.close();
    }

    @Test
    @DisplayName("0、10、25 秒连续触发时 55 秒前不抽取且只执行一次")
    void 连续触发重置三十秒静默期且等待期不持写许可() {
        UserMemoryServiceImpl service = newService();
        when(cursorMapper.selectOneByQuery(any())).thenReturn(null);
        when(chatHistoryService.listMessagesAfterCursor(
                eq(APP_A), anyLong(), anyInt()))
                .thenReturn(八条完整消息(APP_A, "应用甲"));

        触发(service, APP_A);
        AppDataLifecycleFence.DeletePermit deletion =
                lifecycleFence.beginDelete(APP_A, Duration.ZERO);
        assertNotNull(deletion, "防抖等待期不得持有 writer permit");
        deletion.abortAndReopen();

        scheduler.advance(Duration.ofSeconds(10));
        触发(service, APP_A);
        scheduler.advance(Duration.ofSeconds(15));
        触发(service, APP_A);

        scheduler.advance(Duration.ofSeconds(29));
        assertEquals(0, worker.queuedTaskCount());
        verify(model, never()).chat(any(String.class));

        scheduler.advance(Duration.ofSeconds(1));
        assertEquals(1, worker.queuedTaskCount());
        verify(model, never()).chat(any(String.class));

        worker.runAll();
        verify(model).chat(any(String.class));
        scheduler.advance(Duration.ofMinutes(1));
        worker.runAll();
        verify(model).chat(any(String.class));
        assertEquals(1D, debounceCounter("registered").count());
        assertEquals(2D, debounceCounter("rescheduled").count());
        assertEquals(1D, debounceCounter("submitted").count());
        assertEquals(1D, debounceCounter("completed").count());
    }

    @Test
    @DisplayName("tombstone 后迟到 trigger 不得重建 L2 pending")
    void 删除接管后迟到Trigger不再创建调度任务() {
        UserMemoryServiceImpl service = newService();
        AppDataLifecycleFence.DeletePermit deletion =
                lifecycleFence.beginDelete(APP_A, Duration.ZERO);
        assertNotNull(deletion);
        deletion.commitTombstone();
        service.invalidateCaches(APP_A, USER_ID);

        触发(service, APP_A);

        assertNull(scheduler.oldestTask());
        scheduler.advance(Duration.ofMinutes(10));
        assertEquals(0, worker.queuedTaskCount());
        verify(model, never()).chat(any(String.class));
        verify(cursorMapper, never()).selectOneByQuery(any());
    }

    @Test
    @DisplayName("删除失效后迟到失败 worker 不得恢复 L2 pending")
    void Writer释放后的迟到失败结果不复活待处理状态() throws Exception {
        AppDataLifecycleFence realFence = new AppDataLifecycleFence();
        lifecycleFence = mock(
                AppDataLifecycleFence.class,
                withSettings().mockMaker(INLINE));
        AtomicBoolean workerPhase = new AtomicBoolean();
        CountDownLatch writerReleased = new CountDownLatch(1);
        CountDownLatch allowWorkerFinish = new CountDownLatch(1);
        when(lifecycleFence.tryAcquireWriter(APP_A)).thenAnswer(invocation -> {
            AppDataLifecycleFence.WriterPermit realPermit =
                    realFence.tryAcquireWriter(APP_A);
            if (realPermit == null || !workerPhase.get()) {
                return realPermit;
            }
            return (AppDataLifecycleFence.WriterPermit) () -> {
                realPermit.close();
                writerReleased.countDown();
                try {
                    if (!allowWorkerFinish.await(1, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("等待删除失效超时");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "等待删除失效时被中断", exception);
                }
            };
        });
        UserMemoryServiceImpl service = newService();
        when(cursorMapper.selectOneByQuery(any())).thenReturn(null);
        when(chatHistoryService.listMessagesAfterCursor(
                eq(APP_A), anyLong(), anyInt()))
                .thenReturn(八条完整消息(APP_A, "迟到失败"));
        when(model.chat(any(String.class)))
                .thenThrow(new IllegalStateException("模型暂不可用"));

        触发(service, APP_A);
        scheduler.advance(Duration.ofSeconds(30));
        workerPhase.set(true);
        try (ExecutorService threads = java.util.concurrent.Executors
                .newVirtualThreadPerTaskExecutor()) {
            Future<?> lateWorker = threads.submit(worker::runAll);
            assertTrue(writerReleased.await(1, TimeUnit.SECONDS));
            AppDataLifecycleFence.DeletePermit deletion =
                    realFence.beginDelete(APP_A, Duration.ofSeconds(1));
            assertNotNull(deletion);
            deletion.commitTombstone();
            service.invalidateCaches(APP_A, USER_ID);
            allowWorkerFinish.countDown();
            lateWorker.get(1, TimeUnit.SECONDS);
        }

        scheduler.advance(Duration.ofMinutes(10));
        assertNull(scheduler.oldestTask());
        assertEquals(0, worker.queuedTaskCount());
        verify(model, times(1)).chat(any(String.class));
    }

    @Test
    @DisplayName("新服务先等待静默期再服从数据库剩余退避")
    void 新服务实例遵守数据库持久化退避且不忙等() {
        UserMemoryServiceImpl service = newService();
        Instant databaseRetryAt = clock.instant().plusSeconds(60);
        AppMemoryExtractCursor cursor = 游标(APP_A, 10L);
        cursor.setFailCount(2);
        cursor.setNextRetryTime(LocalDateTime.ofInstant(
                databaseRetryAt, clock.getZone()));
        when(cursorMapper.selectOneByQuery(any())).thenReturn(cursor);
        when(chatHistoryService.listMessagesAfterCursor(
                eq(APP_A), anyLong(), anyInt()))
                .thenReturn(八条完整消息(APP_A, "数据库退避"));

        触发(service, APP_A);
        scheduler.advance(Duration.ofSeconds(30));
        assertEquals(1, worker.queuedTaskCount());
        worker.runAll();

        verify(model, never()).chat(any(String.class));
        verify(cursorMapper, never()).update(any());
        assertEquals(1D, debounceCounter(
                "database_backoff_deferred").count());
        assertNotNull(scheduler.oldestTask());
        assertEquals(databaseRetryAt,
                scheduler.oldestTask().startTime());

        scheduler.advance(Duration.ofSeconds(29));
        assertEquals(0, worker.queuedTaskCount());
        scheduler.advance(Duration.ofSeconds(1));
        assertEquals(1, worker.queuedTaskCount());
        worker.runAll();

        verify(model).chat(any(String.class));
    }

    @Test
    @DisplayName("数据库退避中的 app 不会饿死同用户其他到期 app")
    void 数据库退避只延后目标应用且其他应用继续处理() {
        UserMemoryServiceImpl service = newService();
        Instant appARetryAt = clock.instant().plusSeconds(120);
        AppMemoryExtractCursor appACursor = 游标(APP_A, 10L);
        appACursor.setFailCount(4);
        appACursor.setNextRetryTime(LocalDateTime.ofInstant(
                appARetryAt, clock.getZone()));
        AppMemoryExtractCursor appBCursor = 游标(APP_B, 20L);
        when(cursorMapper.selectOneByQuery(any()))
                .thenReturn(appACursor, appBCursor);
        when(chatHistoryService.listMessagesAfterCursor(
                eq(APP_A), anyLong(), anyInt()))
                .thenReturn(八条完整消息(APP_A, "退避应用"));
        when(chatHistoryService.listMessagesAfterCursor(
                eq(APP_B), anyLong(), anyInt()))
                .thenReturn(八条完整消息(APP_B, "正常应用"));

        触发(service, APP_A);
        触发(service, APP_B);
        scheduler.advance(Duration.ofSeconds(30));
        worker.runAll();

        ArgumentCaptor<AppMemoryExtractCursor> updated =
                ArgumentCaptor.forClass(AppMemoryExtractCursor.class);
        verify(cursorMapper).update(updated.capture(), eq(false));
        assertEquals(APP_B, updated.getValue().getAppId());
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(model).chat(prompt.capture());
        assertTrue(prompt.getValue().contains("正常应用"));
        assertFalse(prompt.getValue().contains("退避应用"));
        assertNotNull(scheduler.oldestTask());
        assertEquals(appARetryAt,
                scheduler.oldestTask().startTime());
    }

    @Test
    @DisplayName("失败元数据写库成功时本地资格与数据库时间一致")
    void 持久化失败时间决定下一次本地调度资格() {
        UserMemoryServiceImpl service = newService();
        AppMemoryExtractCursor cursor = 游标(APP_A, 0L);
        cursor.setFailCount(2);
        when(cursorMapper.selectOneByQuery(any())).thenReturn(cursor);
        when(chatHistoryService.listMessagesAfterCursor(
                eq(APP_A), anyLong(), anyInt()))
                .thenReturn(八条完整消息(APP_A, "失败退避"));
        when(model.chat(any(String.class)))
                .thenThrow(new IllegalStateException("模型暂不可用"));

        触发(service, APP_A);
        scheduler.advance(Duration.ofSeconds(30));
        Instant expectedRetryAt = clock.instant().plusSeconds(20);
        worker.runAll();

        ArgumentCaptor<AppMemoryExtractCursor> failed =
                ArgumentCaptor.forClass(AppMemoryExtractCursor.class);
        verify(cursorMapper).update(failed.capture());
        assertEquals(3, failed.getValue().getFailCount());
        assertEquals(LocalDateTime.ofInstant(
                        expectedRetryAt, clock.getZone()),
                failed.getValue().getNextRetryTime());
        assertNotNull(scheduler.oldestTask());
        assertEquals(expectedRetryAt,
                scheduler.oldestTask().startTime());
    }

    @Test
    @DisplayName("同一用户的两个 app 都保留并按旧游标优先处理")
    void 同用户多个应用按游标从旧到新处理() {
        UserMemoryServiceImpl service = newService();
        when(cursorMapper.selectOneByQuery(any()))
                .thenReturn(游标(APP_A, 50L), 游标(APP_B, 10L));
        when(chatHistoryService.listMessagesAfterCursor(
                eq(APP_A), anyLong(), anyInt()))
                .thenReturn(八条完整消息(APP_A, "应用甲"));
        when(chatHistoryService.listMessagesAfterCursor(
                eq(APP_B), anyLong(), anyInt()))
                .thenReturn(八条完整消息(APP_B, "应用乙"));
        List<String> prompts = new ArrayList<>();
        when(model.chat(any(String.class))).thenAnswer(invocation -> {
            prompts.add(invocation.getArgument(0));
            return "[]";
        });

        触发(service, APP_A);
        触发(service, APP_B);
        scheduler.advance(Duration.ofSeconds(30));
        worker.runAll();

        assertEquals(2, prompts.size());
        assertTrue(prompts.get(0).contains("应用乙"));
        assertTrue(prompts.get(1).contains("应用甲"));
    }

    @Test
    @DisplayName("任一 app 的新稳定回合都重置同用户全部 dirty app 静默期")
    void 跨应用错峰触发共享用户级静默期() {
        UserMemoryServiceImpl service = newService();
        when(cursorMapper.selectOneByQuery(any())).thenReturn(null);
        when(chatHistoryService.listMessagesAfterCursor(
                eq(APP_A), anyLong(), anyInt()))
                .thenReturn(八条完整消息(APP_A, "应用甲"));
        when(chatHistoryService.listMessagesAfterCursor(
                eq(APP_B), anyLong(), anyInt()))
                .thenReturn(八条完整消息(APP_B, "应用乙"));

        触发(service, APP_A);
        scheduler.advance(Duration.ofSeconds(10));
        触发(service, APP_B);
        scheduler.advance(Duration.ofSeconds(20));

        assertEquals(0, worker.queuedTaskCount(),
                "appA 不得在自己的 t=30 提前执行");
        verify(model, never()).chat(any(String.class));

        scheduler.advance(Duration.ofSeconds(10));
        worker.runAll();
        verify(model, times(2)).chat(any(String.class));
    }

    @Test
    @DisplayName("成功清理与并发新 trigger 交错时更高版本进入下一轮")
    void 完成清理不会丢失并发到达的新版本() throws Exception {
        UserMemoryServiceImpl service = newService();
        when(cursorMapper.selectOneByQuery(any())).thenReturn(null);
        when(chatHistoryService.listMessagesAfterCursor(
                eq(APP_A), anyLong(), anyInt()))
                .thenReturn(八条完整消息(APP_A, "版本证据"));
        CountDownLatch cursorWriteEntered = new CountDownLatch(1);
        CountDownLatch allowCursorWrite = new CountDownLatch(1);
        AtomicInteger cursorWrites = new AtomicInteger();
        when(cursorMapper.insert(any())).thenAnswer(invocation -> {
            if (cursorWrites.getAndIncrement() == 0) {
                cursorWriteEntered.countDown();
                assertTrue(allowCursorWrite.await(1, TimeUnit.SECONDS));
            }
            return 1;
        });

        触发(service, APP_A);
        scheduler.advance(Duration.ofSeconds(30));
        try (ExecutorService threads =
                     java.util.concurrent.Executors
                             .newVirtualThreadPerTaskExecutor()) {
            Future<?> firstRun = threads.submit(worker::runAll);
            assertTrue(cursorWriteEntered.await(1, TimeUnit.SECONDS));

            触发(service, APP_A);
            allowCursorWrite.countDown();
            firstRun.get(1, TimeUnit.SECONDS);
        }

        scheduler.advance(Duration.ofSeconds(29));
        assertEquals(0, worker.queuedTaskCount());
        scheduler.advance(Duration.ofSeconds(1));
        worker.runAll();

        verify(model, org.mockito.Mockito.times(2))
                .chat(any(String.class));
    }

    @Test
    @DisplayName("旧轮次处理前发现 app 升版时必须留到新的静默期")
    void 旧轮次不得提前处理执行中升版的应用() throws Exception {
        UserMemoryServiceImpl service = newService();
        when(cursorMapper.selectOneByQuery(any()))
                .thenReturn(游标(APP_A, 10L), 游标(APP_B, 20L));
        when(chatHistoryService.listMessagesAfterCursor(
                eq(APP_A), anyLong(), anyInt()))
                .thenReturn(八条完整消息(APP_A, "应用甲"));
        when(chatHistoryService.listMessagesAfterCursor(
                eq(APP_B), anyLong(), anyInt()))
                .thenReturn(八条完整消息(APP_B, "应用乙新版本"));
        CountDownLatch appAStarted = new CountDownLatch(1);
        CountDownLatch allowAppAComplete = new CountDownLatch(1);
        List<String> processedApps = new ArrayList<>();
        when(model.chat(any(String.class))).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(0);
            if (prompt.contains("应用甲")) {
                processedApps.add("appA");
                appAStarted.countDown();
                assertTrue(allowAppAComplete.await(1, TimeUnit.SECONDS));
            } else {
                processedApps.add("appB");
            }
            return "[]";
        });

        触发(service, APP_A);
        触发(service, APP_B);
        scheduler.advance(Duration.ofSeconds(30));
        try (ExecutorService threads = java.util.concurrent.Executors
                .newVirtualThreadPerTaskExecutor()) {
            Future<?> firstRound = threads.submit(worker::runAll);
            assertTrue(appAStarted.await(1, TimeUnit.SECONDS));

            触发(service, APP_B);
            allowAppAComplete.countDown();
            firstRound.get(1, TimeUnit.SECONDS);
        }

        assertEquals(List.of("appA"), processedApps,
                "旧快照不得在新版本静默期内提前处理 appB");
        scheduler.advance(Duration.ofSeconds(29));
        assertEquals(0, worker.queuedTaskCount());
        scheduler.advance(Duration.ofSeconds(1));
        worker.runAll();
        assertEquals(List.of("appA", "appB"), processedApps);
    }

    @Test
    @DisplayName("任一 app 升版后旧轮次不得继续处理同用户其他 app")
    void 旧轮次不得越过新的用户级静默期() throws Exception {
        UserMemoryServiceImpl service = newService();
        when(cursorMapper.selectOneByQuery(any())).thenReturn(
                游标(APP_A, 10L), 游标(APP_B, 20L),
                游标(APP_A, 10L), 游标(APP_B, 20L));
        when(chatHistoryService.listMessagesAfterCursor(
                eq(APP_A), anyLong(), anyInt()))
                .thenReturn(八条完整消息(APP_A, "应用甲"));
        when(chatHistoryService.listMessagesAfterCursor(
                eq(APP_B), anyLong(), anyInt()))
                .thenReturn(八条完整消息(APP_B, "应用乙"));
        CountDownLatch appAStarted = new CountDownLatch(1);
        CountDownLatch allowAppAComplete = new CountDownLatch(1);
        List<String> processedApps = new ArrayList<>();
        when(model.chat(any(String.class))).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(0);
            if (prompt.contains("应用甲")) {
                processedApps.add("appA");
                if (appAStarted.getCount() > 0L) {
                    appAStarted.countDown();
                    assertTrue(allowAppAComplete.await(
                            1, TimeUnit.SECONDS));
                }
            } else {
                processedApps.add("appB");
            }
            return "[]";
        });

        触发(service, APP_A);
        触发(service, APP_B);
        scheduler.advance(Duration.ofSeconds(30));
        try (ExecutorService threads = java.util.concurrent.Executors
                .newVirtualThreadPerTaskExecutor()) {
            Future<?> firstRound = threads.submit(worker::runAll);
            assertTrue(appAStarted.await(1, TimeUnit.SECONDS));

            触发(service, APP_A);
            allowAppAComplete.countDown();
            firstRound.get(1, TimeUnit.SECONDS);
        }

        assertEquals(List.of("appA"), processedApps,
                "appA 的新 trigger 应让旧用户轮次停止处理 appB");
        scheduler.advance(Duration.ofSeconds(29));
        assertEquals(0, worker.queuedTaskCount());
        scheduler.advance(Duration.ofSeconds(1));
        worker.runAll();
        assertEquals(List.of("appA", "appA", "appB"), processedApps);
    }

    @Test
    @DisplayName("版本复核后到达的新历史不得混入旧轮次 Prompt")
    void 旧轮次按启动时历史上界读取() {
        lifecycleFence = mock(
                AppDataLifecycleFence.class,
                withSettings().mockMaker(INLINE));
        AppDataLifecycleFence.WriterPermit triggerPermit =
                mock(AppDataLifecycleFence.WriterPermit.class);
        AppDataLifecycleFence.WriterPermit writerPermit =
                mock(AppDataLifecycleFence.WriterPermit.class);
        AtomicReference<UserMemoryServiceImpl> serviceRef =
                new AtomicReference<>();
        AtomicInteger writerAcquisitions = new AtomicInteger();
        when(lifecycleFence.tryAcquireWriter(APP_A))
                .thenAnswer(invocation -> {
                    if (writerAcquisitions.incrementAndGet() == 2) {
                        serviceRef.get().triggerPreferenceExtractionAsync(
                                USER_ID, APP_A, 4L);
                        return writerPermit;
                    }
                    return triggerPermit;
                });
        UserMemoryServiceImpl service = newService();
        serviceRef.set(service);
        when(cursorMapper.selectOneByQuery(any())).thenReturn(null);
        when(chatHistoryService.listMessagesAfterCursor(
                eq(APP_A), anyLong(), anyInt())).thenReturn(List.of(
                        ChatHistory.builder().id(1L).appId(APP_A)
                                .userId(USER_ID).messageType("user")
                                .message("旧静默期证据").build(),
                        ChatHistory.builder().id(2L).appId(APP_A)
                                .userId(USER_ID).messageType("ai")
                                .message("旧回合回复").build(),
                        ChatHistory.builder().id(3L).appId(APP_A)
                                .userId(USER_ID).messageType("user")
                                .message("新静默期证据").build(),
                        ChatHistory.builder().id(4L).appId(APP_A)
                                .userId(USER_ID).messageType("ai")
                                .message("新回合回复").build()));
        AtomicReference<String> prompt = new AtomicReference<>();
        when(model.chat(any(String.class))).thenAnswer(invocation -> {
            prompt.set(invocation.getArgument(0));
            return "[]";
        });

        service.triggerPreferenceExtractionAsync(USER_ID, APP_A, 2L);
        scheduler.advance(Duration.ofSeconds(30));
        worker.runAll();

        assertTrue(prompt.get().contains("旧静默期证据"));
        assertTrue(!prompt.get().contains("新静默期证据"),
                "旧轮次不得读取版本复核后新到达的稳定回合");
    }

    @Test
    @DisplayName("新 AI 已提交但 trigger 未登记时旧版本不得跨越已登记历史上界")
    void 未登记新Trigger的稳定回合不得提前进入旧轮次() {
        UserMemoryServiceImpl service = newService();
        when(cursorMapper.selectOneByQuery(any())).thenReturn(null);
        when(chatHistoryService.listMessagesAfterCursor(
                eq(APP_A), anyLong(), anyInt())).thenReturn(List.of(
                        ChatHistory.builder().id(1L).appId(APP_A)
                                .userId(USER_ID).messageType("user")
                                .message("旧静默期证据").build(),
                        ChatHistory.builder().id(2L).appId(APP_A)
                                .userId(USER_ID).messageType("ai")
                                .message("旧回合回复").build(),
                        ChatHistory.builder().id(3L).appId(APP_A)
                                .userId(USER_ID).messageType("user")
                                .message("已提交但尚未登记的新证据").build(),
                        ChatHistory.builder().id(4L).appId(APP_A)
                                .userId(USER_ID).messageType("ai")
                                .message("新回合回复").build()));
        AtomicReference<String> prompt = new AtomicReference<>();
        when(model.chat(any(String.class))).thenAnswer(invocation -> {
            prompt.set(invocation.getArgument(0));
            return "[]";
        });

        service.triggerPreferenceExtractionAsync(USER_ID, APP_A, 2L);
        scheduler.advance(Duration.ofSeconds(30));
        worker.runAll();

        assertTrue(prompt.get().contains("旧静默期证据"));
        assertFalse(prompt.get().contains("已提交但尚未登记的新证据"),
                "旧版本只能读取该次 trigger 明确登记的稳定 AI 边界");
        verify(chatHistoryService, never()).getLastMessage(APP_A);
    }

    @Test
    @DisplayName("迟到 trigger 的边界低于当前游标时不得回退或读取更新历史")
    void 迟到Trigger边界冻结为当前游标() {
        UserMemoryServiceImpl service = newService();
        when(cursorMapper.selectOneByQuery(any()))
                .thenReturn(游标(APP_A, 10L));
        when(chatHistoryService.listMessagesAfterCursor(
                eq(APP_A), anyLong(), anyInt())).thenReturn(List.of(
                        ChatHistory.builder().id(11L).appId(APP_A)
                                .userId(USER_ID).messageType("user")
                                .message("游标后的新用户证据").build(),
                        ChatHistory.builder().id(12L).appId(APP_A)
                                .userId(USER_ID).messageType("ai")
                                .message("游标后的新回复").build()));

        service.triggerPreferenceExtractionAsync(USER_ID, APP_A, 2L);
        scheduler.advance(Duration.ofSeconds(30));
        worker.runAll();

        verify(model, never()).chat(any(String.class));
        verify(cursorMapper, never()).update(any());
    }

    @Test
    @DisplayName("旧定时任务取消后迟到不得驱动同用户重建状态")
    void 旧定时任务迟到不会命中新建状态() {
        UserMemoryServiceImpl service = newService();
        when(cursorMapper.selectOneByQuery(any())).thenReturn(null);
        when(chatHistoryService.listMessagesAfterCursor(
                eq(APP_A), anyLong(), anyInt()))
                .thenReturn(八条完整消息(APP_A, "重建状态"));

        触发(service, APP_A);
        VirtualScheduledTask oldTask = scheduler.oldestTask();
        service.invalidateCaches(APP_A, USER_ID);
        assertTrue(oldTask.isCancelled(), "撤销 dirty app 时必须取消旧定时任务");

        scheduler.advance(Duration.ofSeconds(10));
        触发(service, APP_A);
        oldTask.runIgnoringCancellation();

        assertEquals(0, worker.queuedTaskCount(),
                "旧 state 的回调不得提前驱动新 state");
        scheduler.advance(Duration.ofSeconds(30));
        worker.runAll();
        verify(model).chat(any(String.class));
    }

    @Test
    @DisplayName("防抖指标故障不改变 dirty 版本与工作轮次")
    void 防抖指标故障不改变调度结果() {
        ThrowingMeterRegistry registry = new ThrowingMeterRegistry(
                ThrowingMeterRegistry.FailurePoint.COUNTER_INCREMENT);
        try {
            UserMemoryServiceImpl service = newService(
                    new MemoryCompressionMetricsCollector(registry));
            when(cursorMapper.selectOneByQuery(any())).thenReturn(null);
            when(chatHistoryService.listMessagesAfterCursor(
                    eq(APP_A), anyLong(), anyInt()))
                    .thenReturn(八条完整消息(APP_A, "指标旁路"));

            触发(service, APP_A);
            scheduler.advance(Duration.ofSeconds(30));
            worker.runAll();

            verify(model).chat(any(String.class));
            assertTrue(registry.failureTriggered());
        } finally {
            registry.close();
        }
    }

    @Test
    @DisplayName("旧 worker 完成不得清理撤销后重新登记的同 app 新版本")
    void 旧工作轮次完成不会改写重新登记状态() {
        UserMemoryServiceImpl service = newService();
        when(cursorMapper.selectOneByQuery(any())).thenReturn(null);
        when(chatHistoryService.listMessagesAfterCursor(
                eq(APP_A), anyLong(), anyInt()))
                .thenReturn(八条完整消息(APP_A, "迟到工作轮次"));

        触发(service, APP_A);
        scheduler.advance(Duration.ofSeconds(30));
        assertEquals(1, worker.queuedTaskCount());

        service.invalidateCaches(APP_A, USER_ID);
        触发(service, APP_A);
        worker.runAll();
        verify(model, never()).chat(any(String.class));

        scheduler.advance(Duration.ofSeconds(29));
        assertEquals(0, worker.queuedTaskCount());
        scheduler.advance(Duration.ofSeconds(1));
        worker.runAll();
        verify(model).chat(any(String.class));
    }

    @Test
    @DisplayName("调度器拒绝后 dirty 状态由下一次 trigger 恢复")
    void 调度器拒绝不会丢失待处理版本() {
        UserMemoryServiceImpl service = newService();
        when(cursorMapper.selectOneByQuery(any())).thenReturn(null);
        when(chatHistoryService.listMessagesAfterCursor(
                eq(APP_A), anyLong(), anyInt()))
                .thenReturn(八条完整消息(APP_A, "调度恢复"));
        scheduler.rejectNextSchedule();

        触发(service, APP_A);
        scheduler.advance(Duration.ofSeconds(10));
        触发(service, APP_A);
        scheduler.advance(Duration.ofSeconds(30));
        worker.runAll();

        verify(model).chat(any(String.class));
        assertEquals(1D, debounceCounter("rejected").count());
    }

    @Test
    @DisplayName("worker 拒绝后 dirty 状态在五秒退避到期恢复")
    void 工作线程池拒绝后五秒退避重试() {
        UserMemoryServiceImpl service = newService();
        when(cursorMapper.selectOneByQuery(any())).thenReturn(null);
        when(chatHistoryService.listMessagesAfterCursor(
                eq(APP_A), anyLong(), anyInt()))
                .thenReturn(八条完整消息(APP_A, "工作池恢复"));
        worker.rejectNextExecution();

        触发(service, APP_A);
        scheduler.advance(Duration.ofSeconds(30));
        scheduler.advance(Duration.ofSeconds(4));
        assertEquals(0, worker.queuedTaskCount());
        scheduler.advance(Duration.ofSeconds(1));
        assertEquals(1, worker.queuedTaskCount());

        worker.runAll();
        verify(model).chat(any(String.class));
        assertEquals(1D, debounceCounter("rejected").count());
        assertEquals(1D, debounceCounter("submitted").count());
        assertEquals(1D, debounceCounter("completed").count());
    }

    @Test
    @DisplayName("单个 app 获取写许可异常时同轮其他 app 继续且失败 app 可退避重试")
    void 写许可异常不会卡死用户轮次或阻塞其他应用() {
        lifecycleFence = mock(
                AppDataLifecycleFence.class,
                withSettings().mockMaker(INLINE));
        when(lifecycleFence.tryAcquireWriter(APP_A))
                .thenReturn(mock(AppDataLifecycleFence.WriterPermit.class))
                .thenThrow(new IllegalStateException("写许可暂不可用"));
        when(lifecycleFence.tryAcquireWriter(APP_B))
                .thenReturn(
                        mock(AppDataLifecycleFence.WriterPermit.class),
                        mock(AppDataLifecycleFence.WriterPermit.class));
        UserMemoryServiceImpl service = newService();
        when(cursorMapper.selectOneByQuery(any()))
                .thenReturn(游标(APP_A, 10L), 游标(APP_B, 20L));
        when(chatHistoryService.listMessagesAfterCursor(
                eq(APP_B), anyLong(), anyInt()))
                .thenReturn(八条完整消息(APP_B, "正常应用"));
        List<String> prompts = new ArrayList<>();
        when(model.chat(any(String.class))).thenAnswer(invocation -> {
            prompts.add(invocation.getArgument(0));
            return "[]";
        });

        触发(service, APP_A);
        触发(service, APP_B);
        scheduler.advance(Duration.ofSeconds(30));

        assertEquals(1, worker.queuedTaskCount());
        assertDoesNotThrow(worker::runAll);
        verify(lifecycleFence, times(2)).tryAcquireWriter(APP_A);
        verify(lifecycleFence, times(2)).tryAcquireWriter(APP_B);
        assertEquals(1, prompts.size());
        assertTrue(prompts.getFirst().contains("正常应用"));

        scheduler.advance(Duration.ofSeconds(4));
        assertEquals(0, worker.queuedTaskCount());
        scheduler.advance(Duration.ofSeconds(1));
        assertEquals(1, worker.queuedTaskCount(),
                "失败 app 必须保留 dirty 状态并进入五秒退避重试");
    }

    @Test
    @DisplayName("appA 进入五分钟退避时 appB 仍按自身静默期执行")
    void 单个应用长退避不会饿死同用户其他应用() {
        UserMemoryServiceImpl service = newService();
        AtomicReference<AppMemoryExtractCursor> appACursor =
                new AtomicReference<>();
        AtomicInteger cursorReads = new AtomicInteger();
        when(cursorMapper.selectOneByQuery(any())).thenAnswer(invocation ->
                cursorReads.getAndIncrement() < 7
                        ? appACursor.get() : null);
        when(cursorMapper.insert(any())).thenAnswer(invocation -> {
            AppMemoryExtractCursor cursor = invocation.getArgument(0);
            if (cursor.getAppId() == APP_A) {
                appACursor.set(cursor);
            }
            return 1;
        });
        when(cursorMapper.update(any())).thenAnswer(invocation -> {
            AppMemoryExtractCursor cursor = invocation.getArgument(0);
            if (cursor.getAppId() == APP_A) {
                appACursor.set(cursor);
            }
            return 1;
        });
        when(chatHistoryService.listMessagesAfterCursor(
                eq(APP_A), anyLong(), anyInt()))
                .thenReturn(八条完整消息(APP_A, "失败应用"));
        when(chatHistoryService.listMessagesAfterCursor(
                eq(APP_B), anyLong(), anyInt()))
                .thenReturn(八条完整消息(APP_B, "正常应用"));
        AtomicInteger appAFailures = new AtomicInteger();
        List<String> processedApps = new ArrayList<>();
        when(model.chat(any(String.class))).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(0);
            if (prompt.contains("失败应用")) {
                appAFailures.incrementAndGet();
                processedApps.add("appA");
                throw new IllegalStateException("模型暂不可用");
            }
            processedApps.add("appB");
            return "[]";
        });

        触发(service, APP_A);
        scheduler.advance(Duration.ofSeconds(30));
        worker.runAll();
        for (long delay : List.of(5L, 10L, 20L, 40L, 80L, 160L)) {
            scheduler.advance(Duration.ofSeconds(delay));
            worker.runAll();
        }
        assertEquals(7, appAFailures.get());

        触发(service, APP_B);
        scheduler.advance(Duration.ofSeconds(30));
        worker.runAll();

        assertEquals(7, appAFailures.get(),
                "appA 的下一次资格应仍在五分钟退避之后");
        assertEquals("appB", processedApps.getLast());
    }

    private void 触发(UserMemoryServiceImpl service, long appId) {
        service.triggerPreferenceExtractionAsync(
                USER_ID, appId, appId * 100L + 8L);
    }

    private UserMemoryServiceImpl newService() {
        return newService(metricsCollector);
    }

    private UserMemoryServiceImpl newService(
            MemoryCompressionMetricsCollector collector) {
        return new UserMemoryServiceImpl(
                chatHistoryService,
                memoryMapper,
                cursorMapper,
                appMapper,
                model,
                worker,
                scheduler,
                redisTemplate,
                lifecycleFence,
                tokenEstimator,
                properties,
                TransactionOperations.withoutTransaction(),
                collector,
                clock);
    }

    private Counter debounceCounter(String outcome) {
        Counter counter = metricsRegistry.find("memory_l2_debounce_total")
                .tags("outcome", outcome)
                .counter();
        assertNotNull(counter, () -> "缺少 debounce Counter：" + outcome);
        return counter;
    }

    private AppMemoryExtractCursor 游标(long appId, long lastId) {
        LocalDateTime now = LocalDateTime.ofInstant(
                clock.instant(), clock.getZone());
        return AppMemoryExtractCursor.builder()
                .id(appId)
                .appId(appId)
                .userId(USER_ID)
                .lastExtractedId(lastId)
                .failCount(0)
                .createTime(now)
                .updateTime(now)
                .isDelete(0)
                .build();
    }

    private List<ChatHistory> 八条完整消息(long appId, String marker) {
        List<ChatHistory> messages = new ArrayList<>();
        for (long id = 1L; id <= 8L; id++) {
            boolean user = id % 2L == 1L;
            messages.add(ChatHistory.builder()
                    .id(appId * 100L + id)
                    .appId(appId)
                    .userId(USER_ID)
                    .messageType(user ? "user" : "ai")
                    .message(marker + (user ? "用户" : "回复") + id)
                    .build());
        }
        return List.copyOf(messages);
    }

    private static final class MutableClock extends Clock {

        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId targetZone) {
            return new MutableClock(instant, targetZone);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }

    private static final class AdvancingTaskScheduler
            extends ThreadPoolTaskScheduler {

        private final MutableClock clock;
        private final PriorityQueue<VirtualScheduledTask> tasks =
                new PriorityQueue<>(Comparator
                        .comparing(VirtualScheduledTask::startTime)
                        .thenComparingLong(VirtualScheduledTask::sequence));
        private long nextSequence;
        private boolean rejectNext;

        private AdvancingTaskScheduler(MutableClock clock) {
            this.clock = clock;
        }

        @Override
        public ScheduledFuture<?> schedule(
                Runnable task, Instant startTime) {
            if (rejectNext) {
                rejectNext = false;
                throw new java.util.concurrent.RejectedExecutionException(
                        "虚拟调度器拒绝任务");
            }
            VirtualScheduledTask scheduled = new VirtualScheduledTask(
                    task, startTime, nextSequence++);
            tasks.add(scheduled);
            return scheduled;
        }

        private void rejectNextSchedule() {
            rejectNext = true;
        }

        private void advance(Duration duration) {
            clock.advance(duration);
            while (!tasks.isEmpty()
                    && !tasks.peek().startTime().isAfter(clock.instant())) {
                VirtualScheduledTask task = tasks.remove();
                task.runIfActive();
            }
        }

        private VirtualScheduledTask oldestTask() {
            return tasks.peek();
        }
    }

    private static final class VirtualScheduledTask
            implements ScheduledFuture<Object> {

        private final Runnable task;
        private final Instant startTime;
        private final long sequence;
        private boolean cancelled;
        private boolean done;

        private VirtualScheduledTask(
                Runnable task, Instant startTime, long sequence) {
            this.task = task;
            this.startTime = startTime;
            this.sequence = sequence;
        }

        private Instant startTime() {
            return startTime;
        }

        private long sequence() {
            return sequence;
        }

        private void runIfActive() {
            if (cancelled) {
                return;
            }
            try {
                task.run();
            } finally {
                done = true;
            }
        }

        private void runIgnoringCancellation() {
            task.run();
            done = true;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(
                    Duration.between(Instant.now(), startTime).toNanos(),
                    TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(
                    getDelay(TimeUnit.NANOSECONDS),
                    other.getDelay(TimeUnit.NANOSECONDS));
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (done) {
                return false;
            }
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return done || cancelled;
        }

        @Override
        public Object get() throws InterruptedException, ExecutionException {
            if (!isDone()) {
                throw new IllegalStateException("虚拟任务尚未完成");
            }
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException,
                TimeoutException {
            return get();
        }
    }

    private static final class QueuedExecutor
            extends AbstractExecutorService {

        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private boolean shutdown;
        private boolean rejectNext;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> remaining = List.copyOf(tasks);
            tasks.clear();
            return remaining;
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown && tasks.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }

        @Override
        public void execute(Runnable command) {
            if (shutdown) {
                throw new java.util.concurrent.RejectedExecutionException(
                        "执行器已关闭");
            }
            if (rejectNext) {
                rejectNext = false;
                throw new java.util.concurrent.RejectedExecutionException(
                        "虚拟工作池拒绝任务");
            }
            tasks.add(command);
        }

        private void rejectNextExecution() {
            rejectNext = true;
        }

        private int queuedTaskCount() {
            return tasks.size();
        }

        private void runAll() {
            while (!tasks.isEmpty()) {
                tasks.removeFirst().run();
            }
        }
    }
}
