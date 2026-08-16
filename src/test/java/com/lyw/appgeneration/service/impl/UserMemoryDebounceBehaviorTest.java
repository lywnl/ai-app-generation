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
import com.lyw.appgeneration.service.ChatHistoryService;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

        when(memoryMapper.selectListByQuery(any())).thenReturn(List.of());
        when(memoryMapper.insert(any())).thenReturn(1);
        when(memoryMapper.update(any())).thenReturn(1);
        when(cursorMapper.insert(any())).thenReturn(1);
        when(cursorMapper.update(any())).thenReturn(1);
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
        AppDataLifecycleFence.WriterPermit writerPermit =
                mock(AppDataLifecycleFence.WriterPermit.class);
        AtomicReference<UserMemoryServiceImpl> serviceRef =
                new AtomicReference<>();
        doAnswer(invocation -> {
            serviceRef.get().triggerPreferenceExtractionAsync(
                    USER_ID, APP_A, 4L);
            return writerPermit;
        }).when(lifecycleFence).tryAcquireWriter(APP_A);
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
    }

    @Test
    @DisplayName("单个 app 获取写许可异常时同轮其他 app 继续且失败 app 可退避重试")
    void 写许可异常不会卡死用户轮次或阻塞其他应用() {
        lifecycleFence = mock(
                AppDataLifecycleFence.class,
                withSettings().mockMaker(INLINE));
        doThrow(new IllegalStateException("写许可暂不可用"))
                .when(lifecycleFence).tryAcquireWriter(APP_A);
        doReturn(mock(AppDataLifecycleFence.WriterPermit.class))
                .when(lifecycleFence).tryAcquireWriter(APP_B);
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
        verify(lifecycleFence).tryAcquireWriter(APP_A);
        verify(lifecycleFence).tryAcquireWriter(APP_B);
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
        when(cursorMapper.selectOneByQuery(any())).thenReturn(null);
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
                clock);
    }

    private AppMemoryExtractCursor 游标(long appId, long lastId) {
        return AppMemoryExtractCursor.builder()
                .appId(appId)
                .userId(USER_ID)
                .lastExtractedId(lastId)
                .failCount(0)
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
