package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.AiCodeGeneratorService;
import com.lyw.appgeneration.ai.AiGeneratorServiceFactory;
import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.core.AiCodeGeneratorFacade;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import com.lyw.appgeneration.core.handler.GenerationStreamEvent;
import com.lyw.appgeneration.core.handler.StreamHandlerExecutor;
import com.lyw.appgeneration.exception.GenerationPreflightException;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.monitor.AppLifecycleMetricsCollector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemoryCacheInvalidationResult;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.UserMemoryService;
import com.lyw.appgeneration.service.rag.RagPromptAssembler;
import com.lyw.appgeneration.service.rag.RagRetrievalService;
import dev.langchain4j.service.TokenStream;
import org.mockito.Answers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppServiceSimpleTurnLifecycleTest {

    private static final long APP_ID = 7L;
    private static final long USER_ID = 9L;

    private final AiCodeGeneratorFacade facade = mock(AiCodeGeneratorFacade.class);
    private final AiCodeGeneratorService generator =
            mock(AiCodeGeneratorService.class);
    private final AiGeneratorServiceFactory aiFactory =
            mock(AiGeneratorServiceFactory.class);
    private final ChatHistoryService history = mock(ChatHistoryService.class);
    private final MemorySummaryService summaries = mock(MemorySummaryService.class);
    private final UserMemoryService userMemory = mock(UserMemoryService.class);
    private final StreamHandlerExecutor streamExecutor =
            spy(new StreamHandlerExecutor());
    private AppOperationLeaseManager leases;
    private AppDataLifecycleFence fence;
    private AppServiceImpl service;
    private SimpleMeterRegistry metricsRegistry;

    @BeforeEach
    void setUp() {
        leases = new AppOperationLeaseManager();
        metricsRegistry = new SimpleMeterRegistry();
        fence = new AppDataLifecycleFence();
        ReflectionTestUtils.setField(
                streamExecutor, "memorySummaryService", summaries);
        ReflectionTestUtils.setField(
                streamExecutor, "userMemoryService", userMemory);
        ReflectionTestUtils.setField(
                streamExecutor, "appDataLifecycleFence", fence);
        service = spy(new AppServiceImpl());
        ReflectionTestUtils.setField(service, "aiCodeGeneratorFacade", facade);
        ReflectionTestUtils.setField(service, "aiGeneratorServiceFactory", aiFactory);
        ReflectionTestUtils.setField(service, "chatHistoryService", history);
        ReflectionTestUtils.setField(
                service, "streamHandlerExecutor", streamExecutor);
        ReflectionTestUtils.setField(service, "appOperationLeaseManager", leases);
        ReflectionTestUtils.setField(service, "appLifecycleMetricsCollector",
                new AppLifecycleMetricsCollector(metricsRegistry));
        ReflectionTestUtils.setField(service, "appDataLifecycleFence", fence);
        when(history.addChatMessage(APP_ID, "需求", "user", USER_ID))
                .thenReturn(true);
        when(history.addChatMessage(APP_ID, "回答", "ai", USER_ID))
                .thenReturn(true);
        when(history.addChatMessage(
                APP_ID, com.lyw.appgeneration.core.handler.SimpleTextStreamHandler
                        .FAILURE_MESSAGE, "ai", USER_ID))
                .thenReturn(true);
        when(aiFactory.invalidateAndClearMemory(
                APP_ID, CodeGenTypeEnum.HTML))
                .thenReturn(MemoryCacheInvalidationResult.success());
        when(facade.prepareSimpleGenerator(
                org.mockito.ArgumentMatchers.anyLong(),
                any(CodeGenTypeEnum.class))).thenReturn(generator);
        org.mockito.Mockito.doReturn(app(APP_ID)).when(service).getById(APP_ID);
    }

    @ParameterizedTest
    @EnumSource(value = AppOperationLeaseManager.AppOperationType.class,
            names = {"DEPLOY", "DOWNLOAD", "DELETE"})
    void 活跃冲突操作时订阅普通生成不保存用户也不启动模型(
            AppOperationLeaseManager.AppOperationType operationType) {
        Flux<GenerationStreamEvent> result = service.chatToGenCode(
                APP_ID, "需求", user());
        try (var ignored = leases.acquire(
                APP_ID, operationType, "冲突操作")) {
            StepVerifier.create(result)
                    .expectErrorMatches(error -> error instanceof
                            GenerationPreflightException preflight
                            && preflight.kind()
                            == GenerationPreflightException.Kind.BUSINESS
                            && preflight.safeMessage().equals(
                            "应用正在执行其他操作，请稍后再生成"))
                    .verify();
        }

        verify(history, never()).addChatMessage(APP_ID, "需求", "user", USER_ID);
        verify(facade, never()).generateAndSaveCodeStream(
                any(), any(), eq(APP_ID), anyBoolean(), any(), any());
        assertEquals(1.0, metricsRegistry.get("app_operations_total")
                .tags("operation", "generate", "result", "rejected",
                        "conflict_with", operationType.name().toLowerCase())
                .counter().count());
    }

    @Test
    void 冷缓存服务必须在当前用户消息落库前完成重建() {
        AiCodeGeneratorService generator = mock(AiCodeGeneratorService.class);
        AiCodeGeneratorFacade realFacade = realSimpleFacade();
        when(aiFactory.getAiCodeGeneratorService(
                APP_ID, CodeGenTypeEnum.HTML)).thenReturn(generator);
        TokenStream neverStream = mock(
                TokenStream.class, Answers.RETURNS_SELF);
        when(generator.generateHtmlCodeStream("需求")).thenReturn(neverStream);
        ReflectionTestUtils.setField(service, "aiCodeGeneratorFacade", realFacade);

        Disposable client = service.chatToGenCode(
                APP_ID, "需求", user()).subscribe();

        var order = inOrder(aiFactory, history, generator);
        order.verify(aiFactory).getAiCodeGeneratorService(
                APP_ID, CodeGenTypeEnum.HTML);
        order.verify(history).addChatMessage(
                APP_ID, "需求", "user", USER_ID);
        order.verify(generator).generateHtmlCodeStream("需求");
        verify(aiFactory, times(1)).getAiCodeGeneratorService(
                APP_ID, CodeGenTypeEnum.HTML);
        assertEquals(1.0, metricsRegistry.get("app_operations_total")
                .tags("operation", "generate", "result", "acquired",
                        "conflict_with", "none").counter().count());
        client.dispose();
    }

    @Test
    void 冷缓存同步准备失败不保存当前用户并释放租约() {
        AiCodeGeneratorFacade realFacade = realSimpleFacade();
        when(aiFactory.getAiCodeGeneratorService(
                APP_ID, CodeGenTypeEnum.HTML))
                .thenThrow(new IllegalStateException("冷缓存重建失败"));
        ReflectionTestUtils.setField(service, "aiCodeGeneratorFacade", realFacade);

        StepVerifier.create(service.chatToGenCode(APP_ID, "需求", user()))
                .expectErrorMatches(error -> error instanceof
                        GenerationPreflightException preflight
                        && preflight.kind()
                        == GenerationPreflightException.Kind.SYSTEM
                        && preflight.safeMessage().equals(
                        "生成服务暂时不可用，请稍后重试。")
                        && "冷缓存重建失败".equals(
                        preflight.getCause().getMessage()))
                .verify();

        verify(history, never()).addChatMessage(
                APP_ID, "需求", "user", USER_ID);
        assertLeaseReleased("准备失败后");
    }

    @Test
    void 保存用户失败属于前置错误且不启动模型() {
        when(history.addChatMessage(APP_ID, "需求", "user", USER_ID))
                .thenReturn(false);

        StepVerifier.create(service.chatToGenCode(APP_ID, "需求", user()))
                .expectErrorMatches(error -> error instanceof
                        GenerationPreflightException preflight
                        && preflight.kind()
                        == GenerationPreflightException.Kind.BUSINESS
                        && preflight.safeMessage().equals(
                        "保存用户消息失败"))
                .verify();

        verify(facade, never()).generateAndSaveCodeStream(
                any(), any(), eq(APP_ID), anyBoolean(), any(), any());
        assertLeaseReleased("保存用户失败后");
    }

    @Test
    void 用户已保存后的Handler同步失败不得伪装成前置错误() {
        org.mockito.Mockito.doThrow(
                        new IllegalStateException("Handler初始化失败"))
                .when(streamExecutor).doExecute(
                        any(), eq(history), eq(APP_ID), any(),
                        eq(CodeGenTypeEnum.HTML), any());

        StepVerifier.create(service.chatToGenCode(APP_ID, "需求", user()))
                .expectErrorMatches(error -> !(error instanceof
                        GenerationPreflightException)
                        && "Handler初始化失败".equals(error.getMessage()))
                .verify();

        verify(history).addChatMessage(
                APP_ID, "需求", "user", USER_ID);
        assertLeaseReleased("Handler同步失败后");
    }

    @Test
    void 活跃普通生成拒绝部署和下载并在取消后释放() {
        when(facade.generateAndSaveCodeStream(
                any(), any(), eq(APP_ID), anyBoolean(), any(), any()))
                .thenReturn(Flux.never());
        Disposable subscription = service.chatToGenCode(
                APP_ID, "需求", user()).subscribe();

        assertThrows(AppOperationLeaseManager.ActiveAppOperationException.class,
                () -> leases.acquire(APP_ID,
                        AppOperationLeaseManager.AppOperationType.DEPLOY, "部署"));
        assertThrows(AppOperationLeaseManager.ActiveAppOperationException.class,
                () -> leases.acquire(APP_ID,
                        AppOperationLeaseManager.AppOperationType.DOWNLOAD, "下载"));

        subscription.dispose();
        assertDoesNotThrow(() -> leases.acquire(APP_ID,
                AppOperationLeaseManager.AppOperationType.GENERATE, "下一轮").close());
    }

    @Test
    void 正常与模型异常都释放租约() {
        when(facade.generateAndSaveCodeStream(
                any(), any(), eq(APP_ID), anyBoolean(), any(), any()))
                .thenReturn(Flux.just("回答"), Flux.error(
                        new IllegalStateException("供应商异常")));

        StepVerifier.create(service.chatToGenCode(APP_ID, "需求", user()))
                .expectNext(GenerationStreamEvent.content("回答"))
                .verifyComplete();
        assertLeaseReleased("正常后");

        StepVerifier.create(service.chatToGenCode(APP_ID, "需求", user()))
                .expectErrorMatches(error -> !(error instanceof
                        GenerationPreflightException)
                        && "供应商异常".equals(error.getMessage()))
                .verify();
        assertLeaseReleased("异常后");
    }

    @Test
    void 删除主动取消完整回合且无需客户端断开即可取得删除租约()
            throws Exception {
        CountDownLatch sourceSubscribed = new CountDownLatch(1);
        CountDownLatch sourceCancelled = new CountDownLatch(1);
        doAnswer(invocation -> {
            com.lyw.appgeneration.core.handler.SimpleGenerationTurnContext context =
                    invocation.getArgument(4);
            return Flux.<String>never()
                    .doOnSubscribe(subscription -> {
                        context.bindUpstream(subscription::cancel);
                        sourceSubscribed.countDown();
                    })
                    .doOnCancel(sourceCancelled::countDown);
        }).when(facade).generateAndSaveCodeStream(
                any(), any(), eq(APP_ID), anyBoolean(), any(), any());
        Disposable client = service.chatToGenCode(
                APP_ID, "需求", user()).subscribe();
        assertTrue(sourceSubscribed.await(1, TimeUnit.SECONDS));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var deletion = executor.submit(() -> leases.cancelAndAcquireDelete(
                    APP_ID, "删除", Duration.ofSeconds(1)));

            assertTrue(sourceCancelled.await(1, TimeUnit.SECONDS));
            try (var ignored = deletion.get(2, TimeUnit.SECONDS)) {
                assertTrue(client.isDisposed());
            }
        } finally {
            client.dispose();
        }
    }

    @Test
    void 模型或文件异常会清除不可信普通L0() {
        when(facade.generateAndSaveCodeStream(
                any(), any(), eq(APP_ID), anyBoolean(), any(), any()))
                .thenReturn(Flux.error(new IllegalStateException("内部失败")));

        StepVerifier.create(service.chatToGenCode(APP_ID, "需求", user()))
                .expectErrorMessage("内部失败")
                .verify();

        verify(aiFactory).invalidateAndClearMemory(
                APP_ID, CodeGenTypeEnum.HTML);
    }

    @Test
    void 客户端取消会清除不完整普通L0() {
        when(facade.generateAndSaveCodeStream(
                any(), any(), eq(APP_ID), anyBoolean(), any(), any()))
                .thenReturn(Flux.never());
        Disposable client = service.chatToGenCode(
                APP_ID, "需求", user()).subscribe();

        client.dispose();

        verify(aiFactory).invalidateAndClearMemory(
                APP_ID, CodeGenTypeEnum.HTML);
    }

    @Test
    void 删除门已关闭时不保存用户也不启动模型() {
        AppDataLifecycleFence.DeletePermit delete =
                fence.beginDelete(APP_ID, java.time.Duration.ZERO);

        StepVerifier.create(service.chatToGenCode(APP_ID, "需求", user()))
                .expectErrorMatches(error -> error instanceof
                        GenerationPreflightException preflight
                        && preflight.kind()
                        == GenerationPreflightException.Kind.BUSINESS
                        && preflight.safeMessage().equals(
                        "应用已进入删除流程，无法继续生成"))
                .verify();

        verify(history, never()).addChatMessage(APP_ID, "需求", "user", USER_ID);
        verify(facade, never()).generateAndSaveCodeStream(
                any(), any(), eq(APP_ID), anyBoolean(), any(), any());
        assertLeaseReleased("删除关门后");
        delete.abortAndReopen();
    }

    @Test
    void 不同应用可以并行生成() {
        long anotherAppId = 8L;
        org.mockito.Mockito.doReturn(app(anotherAppId))
                .when(service).getById(anotherAppId);
        when(history.addChatMessage(anotherAppId, "需求", "user", USER_ID))
                .thenReturn(true);
        when(facade.generateAndSaveCodeStream(
                any(), any(), org.mockito.ArgumentMatchers.anyLong(),
                anyBoolean(), any(), any()))
                .thenReturn(Flux.never());

        Disposable first = service.chatToGenCode(APP_ID, "需求", user()).subscribe();
        Disposable second = service.chatToGenCode(
                anotherAppId, "需求", user()).subscribe();

        assertThrows(AppOperationLeaseManager.ActiveAppOperationException.class,
                () -> leases.acquire(APP_ID,
                        AppOperationLeaseManager.AppOperationType.DEPLOY, "部署一"));
        assertThrows(AppOperationLeaseManager.ActiveAppOperationException.class,
                () -> leases.acquire(anotherAppId,
                        AppOperationLeaseManager.AppOperationType.DEPLOY, "部署二"));
        first.dispose();
        second.dispose();
    }

    private void assertLeaseReleased(String owner) {
        leases.acquire(APP_ID,
                AppOperationLeaseManager.AppOperationType.GENERATE, owner).close();
    }

    private AiCodeGeneratorFacade realSimpleFacade() {
        AiCodeGeneratorFacade realFacade = new AiCodeGeneratorFacade();
        RagRetrievalService retrieval = mock(RagRetrievalService.class);
        RagPromptAssembler assembler = mock(RagPromptAssembler.class);
        when(retrieval.retrieve("需求", CodeGenTypeEnum.HTML))
                .thenReturn(java.util.List.of());
        when(assembler.assemble("需求", java.util.List.of()))
                .thenReturn("需求");
        ReflectionTestUtils.setField(
                realFacade, "aiGeneratorServiceFactory", aiFactory);
        ReflectionTestUtils.setField(
                realFacade, "ragRetrievalService", retrieval);
        ReflectionTestUtils.setField(
                realFacade, "ragPromptAssembler", assembler);
        ReflectionTestUtils.setField(
                realFacade, "ragProperties", new RagProperties());
        ReflectionTestUtils.setField(
                realFacade, "appDataLifecycleFence", fence);
        return realFacade;
    }

    private App app(long appId) {
        return App.builder().id(appId).userId(USER_ID)
                .codeGenType(CodeGenTypeEnum.HTML.getValue()).build();
    }

    private User user() {
        return User.builder().id(USER_ID).build();
    }
}
