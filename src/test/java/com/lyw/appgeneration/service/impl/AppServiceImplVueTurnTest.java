package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.AiGeneratorServiceFactory;
import com.lyw.appgeneration.core.AiCodeGeneratorFacade;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import com.lyw.appgeneration.core.handler.StreamHandlerExecutor;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.service.ChatHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppServiceImplVueTurnTest {

    private static final long APP_ID = 7L;
    private static final long USER_ID = 9L;

    private final AiCodeGeneratorFacade facade = mock(AiCodeGeneratorFacade.class);
    private final AiGeneratorServiceFactory factory = mock(AiGeneratorServiceFactory.class);
    private final ChatHistoryService history = mock(ChatHistoryService.class);
    private final StreamHandlerExecutor executor = mock(StreamHandlerExecutor.class);
    private AppOperationLeaseManager operationManager;
    private AppServiceImpl service;

    @BeforeEach
    void setUp() {
        operationManager = new AppOperationLeaseManager();
        service = new AppServiceImpl();
        ReflectionTestUtils.setField(service, "aiCodeGeneratorFacade", facade);
        ReflectionTestUtils.setField(service, "aiGeneratorServiceFactory", factory);
        ReflectionTestUtils.setField(service, "chatHistoryService", history);
        ReflectionTestUtils.setField(service, "streamHandlerExecutor", executor);
        ReflectionTestUtils.setField(service, "appOperationLeaseManager", operationManager);
        ReflectionTestUtils.setField(service, "vueBuildSessionManager",
                new VueBuildSessionManager());
        App app = App.builder().id(APP_ID).userId(USER_ID)
                .codeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue()).build();
        service = org.mockito.Mockito.spy(service);
        org.mockito.Mockito.doReturn(app).when(service).getById(APP_ID);
    }

    @Test
    void serviceCreationAndUserSavePrecedeModelSubscription() {
        AtomicInteger starts = new AtomicInteger();
        when(history.getLastMessage(APP_ID)).thenReturn(null);
        when(facade.generateVueProjectStream(eq("需求"), eq(APP_ID), eq(true), any()))
                .thenReturn(Flux.defer(() -> {
                    starts.incrementAndGet();
                    return Flux.just("raw");
                }));
        when(history.addChatMessage(APP_ID, "需求", "user", USER_ID))
                .thenReturn(true);
        when(executor.doExecuteVue(any(), any())).thenAnswer(invocation ->
                invocation.<Flux<String>>getArgument(0));

        Flux<String> result = service.chatToGenCode(
                APP_ID, "需求", User.builder().id(USER_ID).build());

        assertEquals(0, starts.get(), "返回冷 Flux 时模型尚未启动");
        assertEquals("raw", result.blockFirst());
        assertEquals(1, starts.get());
        InOrder order = inOrder(facade, history, executor);
        order.verify(facade).generateVueProjectStream(
                eq("需求"), eq(APP_ID), eq(true), any());
        order.verify(history).addChatMessage(APP_ID, "需求", "user", USER_ID);
        order.verify(executor).doExecuteVue(any(), any());
    }

    @Test
    void userSaveFalseNeverStartsModelAndReleasesLease() {
        AtomicInteger starts = new AtomicInteger();
        when(history.getLastMessage(APP_ID)).thenReturn(null);
        when(facade.generateVueProjectStream(anyString(), anyLong(),
                anyBoolean(), any())).thenReturn(Flux.defer(() -> {
                    starts.incrementAndGet();
                    return Flux.empty();
                }));
        when(history.addChatMessage(APP_ID, "需求", "user", USER_ID))
                .thenReturn(false);

        assertThrows(RuntimeException.class, () -> service.chatToGenCode(
                APP_ID, "需求", User.builder().id(USER_ID).build()).blockLast());
        assertEquals(0, starts.get());
        verify(executor, never()).doExecuteVue(any(), any());
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
                anyBoolean(), any())).thenReturn(Flux.empty());
        when(history.addChatMessage(APP_ID, "新需求", "user", USER_ID))
                .thenReturn(true);
        when(history.repairOrphanUserTurn(APP_ID, USER_ID,
                "生成过程中遇到系统异常，请稍后重试。"))
                .thenReturn(true);
        when(executor.doExecuteVue(any(), any())).thenReturn(Flux.empty());

        service.chatToGenCode(APP_ID, "新需求",
                User.builder().id(USER_ID).build()).blockLast();

        InOrder order = inOrder(factory, facade, history);
        order.verify(history).repairOrphanUserTurn(APP_ID, USER_ID,
                "生成过程中遇到系统异常，请稍后重试。");
        order.verify(factory).prepareVueColdRebuild(APP_ID);
        order.verify(facade).generateVueProjectStream(
                eq("新需求"), eq(APP_ID), eq(false), any());
        order.verify(history).addChatMessage(APP_ID, "新需求", "user", USER_ID);
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
                anyString(), anyLong(), anyBoolean(), any());
        verify(history, never()).addChatMessage(
                APP_ID, "新需求", "user", USER_ID);
        operationManager.acquire(APP_ID,
                AppOperationLeaseManager.AppOperationType.GENERATE,
                "next-after-repair-failure").close();
    }
}
