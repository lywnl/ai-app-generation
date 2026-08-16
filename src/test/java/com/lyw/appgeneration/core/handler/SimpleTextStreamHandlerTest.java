package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.UserMemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SimpleTextStreamHandlerTest {

    private static final long APP_ID = 7L;
    private static final long USER_ID = 9L;
    private static final long AI_MESSAGE_ID = 11L;

    private final ChatHistoryService history = mock(ChatHistoryService.class);
    private final MemorySummaryService summaries = mock(MemorySummaryService.class);
    private final UserMemoryService userMemory = mock(UserMemoryService.class);
    private final AppDataLifecycleFence fence = new AppDataLifecycleFence();
    private final SimpleTextStreamHandler handler = new SimpleTextStreamHandler();
    private SimpleGenerationTurnContext context;

    @BeforeEach
    void setUp() {
        var lease = new AppOperationLeaseManager().acquire(
                APP_ID, AppOperationLeaseManager.AppOperationType.GENERATE, "普通回合");
        context = new SimpleGenerationTurnContext(lease);
        when(history.addChatMessageAndReturn(
                APP_ID, "完整回答", "ai", USER_ID))
                .thenReturn(已保存消息("完整回答", AI_MESSAGE_ID));
        when(history.addChatMessageAndReturn(
                APP_ID, SimpleTextStreamHandler.FAILURE_MESSAGE, "ai", USER_ID))
                .thenReturn(已保存消息(
                        SimpleTextStreamHandler.FAILURE_MESSAGE,
                        AI_MESSAGE_ID + 1));
    }

    @Test
    void 正常完成只保存一次成功历史并触发稳定记忆() {
        StepVerifier.create(handle(Flux.just("完整", "回答")))
                .expectNext("完整", "回答")
                .verifyComplete();

        verify(history).addChatMessageAndReturn(
                APP_ID, "完整回答", "ai", USER_ID);
        verify(history, never()).addChatMessageAndReturn(
                APP_ID, SimpleTextStreamHandler.FAILURE_MESSAGE, "ai", USER_ID);
        verify(summaries).triggerSummarizationAsync(APP_ID);
        verify(userMemory).triggerPreferenceExtractionAsync(
                USER_ID, APP_ID, AI_MESSAGE_ID);
        context.close();
    }

    @Test
    void 稳定AI消息ID非法时保留已保存结果但不触发L2() {
        when(history.addChatMessageAndReturn(
                APP_ID, "完整回答", "ai", USER_ID))
                .thenReturn(已保存消息("完整回答", null));

        StepVerifier.create(handle(Flux.just("完整回答")))
                .expectNext("完整回答")
                .verifyComplete();

        verify(summaries).triggerSummarizationAsync(APP_ID);
        verify(userMemory, never()).triggerPreferenceExtractionAsync(
                anyLong(), anyLong(), anyLong());
        context.close();
    }

    @Test
    void 模型错误只保存固定失败文案且不泄漏原始异常() {
        StepVerifier.create(handle(Flux.error(
                        new IllegalStateException("供应商密钥 secret-token"))))
                .expectErrorMessage("供应商密钥 secret-token")
                .verify();

        verify(history).addChatMessageAndReturn(
                APP_ID, SimpleTextStreamHandler.FAILURE_MESSAGE, "ai", USER_ID);
        verify(history, never()).addChatMessageAndReturn(
                APP_ID, "AI回复失败: 供应商密钥 secret-token", "ai", USER_ID);
        verify(summaries, never()).triggerSummarizationAsync(APP_ID);
        verify(userMemory, never()).triggerPreferenceExtractionAsync(
                anyLong(), anyLong(), anyLong());
        context.close();
    }

    @Test
    void 删除关门后晚到完成不写历史和记忆() {
        AppDataLifecycleFence.DeletePermit delete =
                fence.beginDelete(APP_ID, java.time.Duration.ZERO);

        StepVerifier.create(handle(Flux.just("完整回答")))
                .expectNext("完整回答")
                .verifyComplete();

        verify(history, never()).addChatMessageAndReturn(
                APP_ID, "完整回答", "ai", USER_ID);
        verify(summaries, never()).triggerSummarizationAsync(APP_ID);
        verify(userMemory, never()).triggerPreferenceExtractionAsync(
                anyLong(), anyLong(), anyLong());
        context.close();
        delete.abortAndReopen();
    }

    @Test
    void 客户端取消不执行成功或失败持久化() {
        Sinks.Many<String> source = Sinks.many().unicast().onBackpressureBuffer();
        AtomicBoolean cancelled = new AtomicBoolean();
        Disposable subscription = handle(source.asFlux().doOnCancel(
                () -> cancelled.set(true))).subscribe();

        source.tryEmitNext("半截回答");
        subscription.dispose();

        org.junit.jupiter.api.Assertions.assertTrue(cancelled.get());
        verify(history, never()).addChatMessageAndReturn(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong());
        context.close();
    }

    @Test
    void 成功AI消息保存返回null时进入错误终态且不触发记忆() {
        when(history.addChatMessageAndReturn(
                APP_ID, "完整回答", "ai", USER_ID))
                .thenReturn(null);

        StepVerifier.create(handle(Flux.just("完整回答")))
                .expectNext("完整回答")
                .expectErrorMessage("保存 AI 回复失败")
                .verify();

        verify(history).addChatMessageAndReturn(
                APP_ID, "完整回答", "ai", USER_ID);
        verify(history).addChatMessageAndReturn(
                APP_ID, SimpleTextStreamHandler.FAILURE_MESSAGE, "ai", USER_ID);
        verify(summaries, never()).triggerSummarizationAsync(APP_ID);
        verify(userMemory, never()).triggerPreferenceExtractionAsync(
                anyLong(), anyLong(), anyLong());
        context.close();
    }

    @Test
    void 文件保存异常只写固定失败消息不写成功AI历史() {
        StepVerifier.create(handle(Flux.concat(
                        Flux.just("半截代码"),
                        Flux.error(new IllegalStateException("文件保存失败")))))
                .expectNext("半截代码")
                .expectErrorMessage("文件保存失败")
                .verify();

        verify(history, never()).addChatMessageAndReturn(
                APP_ID, "半截代码", "ai", USER_ID);
        verify(history).addChatMessageAndReturn(
                APP_ID, SimpleTextStreamHandler.FAILURE_MESSAGE, "ai", USER_ID);
        context.close();
    }

    @Test
    void AI消息已进入写票据时删除必须等待持久化退出() throws Exception {
        CountDownLatch writerEntered = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        doAnswer(invocation -> {
            writerEntered.countDown();
            assertTrue(releaseWriter.await(1, TimeUnit.SECONDS));
            return 已保存消息("完整回答", AI_MESSAGE_ID);
        }).when(history).addChatMessageAndReturn(
                APP_ID, "完整回答", "ai", USER_ID);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var generation = executor.submit(() ->
                    handle(Flux.just("完整回答")).then().block());
            assertTrue(writerEntered.await(1, TimeUnit.SECONDS));

            var deletion = executor.submit(() ->
                    fence.beginDelete(APP_ID, Duration.ofSeconds(2)));
            assertFalse(deletion.isDone());
            releaseWriter.countDown();

            generation.get(2, TimeUnit.SECONDS);
            deletion.get(2, TimeUnit.SECONDS).abortAndReopen();
        }
        context.close();
    }

    private Flux<String> handle(Flux<String> source) {
        return handler.handle(source, history, APP_ID,
                User.builder().id(USER_ID).build(), summaries, userMemory,
                fence, context);
    }

    private ChatHistory 已保存消息(String message, Long id) {
        return ChatHistory.builder()
                .id(id)
                .appId(APP_ID)
                .userId(USER_ID)
                .messageType("ai")
                .message(message)
                .build();
    }
}
