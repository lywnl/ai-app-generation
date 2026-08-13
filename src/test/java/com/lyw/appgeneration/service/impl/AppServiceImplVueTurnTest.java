package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.AiGeneratorServiceFactory;
import com.lyw.appgeneration.ai.AiCodeGeneratorService;
import com.lyw.appgeneration.ai.image.ImageCollectionService;
import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.core.AiCodeGeneratorFacade;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import com.lyw.appgeneration.core.handler.StreamHandlerExecutor;
import com.lyw.appgeneration.core.handler.VueTurnContext;
import com.lyw.appgeneration.core.handler.GenerationStreamEvent;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.monitor.AppLifecycleMetricsCollector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.rag.RagPromptAssembler;
import com.lyw.appgeneration.service.rag.RagRetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AppServiceImplVueTurnTest {

    private static final long APP_ID = 7L;
    private static final long USER_ID = 9L;

    private final AiCodeGeneratorFacade facade = mock(AiCodeGeneratorFacade.class);
    private final AiGeneratorServiceFactory factory = mock(AiGeneratorServiceFactory.class);
    private final ChatHistoryService history = mock(ChatHistoryService.class);
    private final StreamHandlerExecutor executor = mock(StreamHandlerExecutor.class);
    private final AiCodeGeneratorService generator = mock(AiCodeGeneratorService.class);
    private AppOperationLeaseManager operationManager;
    private AppServiceImpl service;
    private SimpleMeterRegistry metricsRegistry;

    @BeforeEach
    void setUp() {
        operationManager = new AppOperationLeaseManager();
        metricsRegistry = new SimpleMeterRegistry();
        service = new AppServiceImpl();
        ReflectionTestUtils.setField(service, "aiCodeGeneratorFacade", facade);
        ReflectionTestUtils.setField(service, "aiGeneratorServiceFactory", factory);
        ReflectionTestUtils.setField(service, "chatHistoryService", history);
        ReflectionTestUtils.setField(service, "streamHandlerExecutor", executor);
        ReflectionTestUtils.setField(service, "appOperationLeaseManager", operationManager);
        ReflectionTestUtils.setField(service, "appLifecycleMetricsCollector",
                new AppLifecycleMetricsCollector(metricsRegistry));
        ReflectionTestUtils.setField(service, "vueBuildSessionManager",
                new VueBuildSessionManager());
        App app = App.builder().id(APP_ID).userId(USER_ID)
                .codeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue()).build();
        service = org.mockito.Mockito.spy(service);
        org.mockito.Mockito.doReturn(app).when(service).getById(APP_ID);
        when(factory.getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.VUE_PROJECT))
                .thenReturn(generator);
        when(facade.prepareVueGenerator(APP_ID)).thenReturn(generator);
    }

    @Test
    void serviceCreationAndUserSavePrecedeModelSubscription() {
        AtomicInteger starts = new AtomicInteger();
        when(history.getLastMessage(APP_ID)).thenReturn(null);
        when(facade.generateVueProjectStream(
                eq("需求"), eq(APP_ID), eq(true), any(), eq(generator)))
                .thenReturn(Flux.defer(() -> {
                    starts.incrementAndGet();
                    return Flux.just("raw");
                }));
        when(history.addChatMessage(APP_ID, "需求", "user", USER_ID))
                .thenReturn(true);
        when(executor.doExecuteVue(any(), any())).thenAnswer(invocation ->
                invocation.<Flux<String>>getArgument(0)
                        .map(GenerationStreamEvent::content));

        Flux<GenerationStreamEvent> result = service.chatToGenCode(
                APP_ID, "需求", User.builder().id(USER_ID).build());

        assertEquals(0, starts.get(), "返回冷 Flux 时模型尚未启动");
        assertEquals("raw", ((GenerationStreamEvent.Content)
                result.blockFirst()).text());
        assertEquals(1, starts.get());
        assertEquals(1.0, metricsRegistry.get("app_operations_total")
                .tags("operation", "generate", "result", "acquired",
                        "conflict_with", "none").counter().count());
        InOrder order = inOrder(history, facade, executor);
        order.verify(executor).doExecuteVue(any(), any());
        order.verify(facade).prepareVueGenerator(APP_ID);
        order.verify(history).addChatMessage(APP_ID, "需求", "user", USER_ID);
        order.verify(facade).generateVueProjectStream(
                eq("需求"), eq(APP_ID), eq(true), any(), eq(generator));
    }

    @Test
    void activeDeployRejectsVueGenerationAndRecordsConflict() {
        Flux<GenerationStreamEvent> result = service.chatToGenCode(
                APP_ID, "需求", User.builder().id(USER_ID).build());
        try (var ignored = operationManager.acquire(
                APP_ID, AppOperationLeaseManager.AppOperationType.DEPLOY,
                "部署中")) {
            StepVerifier.create(result)
                    .expectErrorMessage("应用正在执行其他操作，请稍后再生成")
                    .verify();
        }

        assertEquals(1.0, metricsRegistry.get("app_operations_total")
                .tags("operation", "generate", "result", "rejected",
                        "conflict_with", "deploy").counter().count());
        verifyNoInteractions(history, facade, executor);
    }

    @Test
    void userSaveFalseNeverStartsModelAndReleasesLease() {
        ImageCollectionService imageCollection = mock(ImageCollectionService.class);
        RagRetrievalService retrieval = mock(RagRetrievalService.class);
        RagPromptAssembler assembler = mock(RagPromptAssembler.class);
        RagProperties ragProperties = new RagProperties();
        ragProperties.setEnabled(true);
        AiCodeGeneratorFacade realFacade = new AiCodeGeneratorFacade();
        ReflectionTestUtils.setField(realFacade, "aiGeneratorServiceFactory", factory);
        ReflectionTestUtils.setField(realFacade, "imageCollectionService", imageCollection);
        ReflectionTestUtils.setField(realFacade, "ragRetrievalService", retrieval);
        ReflectionTestUtils.setField(realFacade, "ragPromptAssembler", assembler);
        ReflectionTestUtils.setField(realFacade, "ragProperties", ragProperties);
        ReflectionTestUtils.setField(service, "aiCodeGeneratorFacade", realFacade);
        when(history.getLastMessage(APP_ID)).thenReturn(null);
        when(history.addChatMessage(APP_ID, "需求", "user", USER_ID))
                .thenReturn(false);
        when(executor.doExecuteVue(any(), any())).thenAnswer(invocation -> {
            VueTurnContext context = invocation.getArgument(1);
            return invocation.<Flux<String>>getArgument(0)
                    .doOnError(ignored -> context.closeResources())
                    .map(GenerationStreamEvent::content);
        });

        assertThrows(RuntimeException.class, () -> service.chatToGenCode(
                APP_ID, "需求", User.builder().id(USER_ID).build()).blockLast());
        verify(executor).doExecuteVue(any(), any());
        verifyNoInteractions(imageCollection, retrieval, assembler, generator);
        operationManager.acquire(APP_ID,
                AppOperationLeaseManager.AppOperationType.GENERATE,
                "next-turn").close();
    }

    @Test
    void orphanUserForcesColdRebuildBeforeServiceCreation() {
        when(history.getLastMessage(APP_ID)).thenReturn(ChatHistory.builder()
                .id(5L).appId(APP_ID).userId(USER_ID).message("旧需求")
                .messageType("user").build());
        when(facade.generateVueProjectStream(anyString(), anyLong(),
                anyBoolean(), any(), any())).thenReturn(Flux.empty());
        when(history.addChatMessage(APP_ID, "新需求", "user", USER_ID))
                .thenReturn(true);
        when(history.repairOrphanUserTurn(APP_ID, USER_ID,
                "生成过程中遇到系统异常，请稍后重试。"))
                .thenReturn(true);
        when(executor.doExecuteVue(any(), any())).thenAnswer(invocation ->
                invocation.<Flux<String>>getArgument(0)
                        .map(GenerationStreamEvent::content));

        service.chatToGenCode(APP_ID, "新需求",
                User.builder().id(USER_ID).build()).blockLast();

        InOrder order = inOrder(factory, facade, history);
        order.verify(history).repairOrphanUserTurn(APP_ID, USER_ID,
                "生成过程中遇到系统异常，请稍后重试。");
        order.verify(factory).prepareVueColdRebuild(APP_ID);
        order.verify(history).addChatMessage(APP_ID, "新需求", "user", USER_ID);
        order.verify(facade).generateVueProjectStream(
                eq("新需求"), eq(APP_ID), eq(false), any(), eq(generator));
    }

    @Test
    void orphanRepairFalseDoesNotClearL0SaveCurrentUserOrStartModel() {
        when(history.getLastMessage(APP_ID)).thenReturn(ChatHistory.builder()
                .id(5L).appId(APP_ID).userId(USER_ID).message("旧需求")
                .messageType("user").build());
        when(history.repairOrphanUserTurn(APP_ID, USER_ID,
                "生成过程中遇到系统异常，请稍后重试。"))
                .thenReturn(false);

        assertThrows(RuntimeException.class, () -> service.chatToGenCode(
                APP_ID, "新需求", User.builder().id(USER_ID).build()).blockLast());

        verify(factory, never()).prepareVueColdRebuild(APP_ID);
        verify(facade, never()).generateVueProjectStream(
                anyString(), anyLong(), anyBoolean(), any(), any());
        verify(history, never()).addChatMessage(
                APP_ID, "新需求", "user", USER_ID);
        operationManager.acquire(APP_ID,
                AppOperationLeaseManager.AppOperationType.GENERATE,
                "next-after-repair-failure").close();
    }

    @Test
    void failureAfterUserCommitEntersVueHandlerForSystemErrorFinalization() {
        IllegalStateException preparationFailure =
                new IllegalStateException("图片增强失败");
        when(history.getLastMessage(APP_ID)).thenReturn(null);
        when(history.addChatMessage(APP_ID, "需求", "user", USER_ID))
                .thenReturn(true);
        when(facade.generateVueProjectStream(
                eq("需求"), eq(APP_ID), eq(true), any(), eq(generator)))
                .thenThrow(preparationFailure);
        when(executor.doExecuteVue(any(), any())).thenAnswer(invocation -> {
            Flux<String> failed = invocation.getArgument(0);
            VueTurnContext context = invocation.getArgument(1);
            Throwable routed = assertThrows(
                    Throwable.class, failed::blockLast);
            assertSame(preparationFailure, routed);
            context.closeResources();
            return Flux.just(GenerationStreamEvent.content(
                    "system-error-outcome"));
        });

        assertEquals("system-error-outcome",
                ((GenerationStreamEvent.Content) service.chatToGenCode(
                        APP_ID, "需求", User.builder().id(USER_ID).build())
                        .blockLast()).text());

        verify(executor).doExecuteVue(any(), any());
        operationManager.acquire(APP_ID,
                AppOperationLeaseManager.AppOperationType.GENERATE,
                "after-system-error-finalization").close();
    }

    @Test
    void vueHandlerAndDeadlineAreInstalledBeforeSynchronousPreparation() {
        when(history.getLastMessage(APP_ID)).thenReturn(null);
        when(history.addChatMessage(APP_ID, "需求", "user", USER_ID))
                .thenReturn(true);
        when(facade.generateVueProjectStream(
                eq("需求"), eq(APP_ID), eq(true), any(), eq(generator)))
                .thenReturn(Flux.empty());
        when(executor.doExecuteVue(any(), any())).thenAnswer(invocation ->
                invocation.<Flux<String>>getArgument(0)
                        .map(GenerationStreamEvent::content));

        service.chatToGenCode(APP_ID, "需求",
                User.builder().id(USER_ID).build()).blockLast();

        InOrder order = inOrder(executor, history, facade);
        order.verify(executor).doExecuteVue(any(), any());
        order.verify(history).getLastMessage(APP_ID);
        order.verify(facade).prepareVueGenerator(APP_ID);
        order.verify(history).addChatMessage(APP_ID, "需求", "user", USER_ID);
    }
}
