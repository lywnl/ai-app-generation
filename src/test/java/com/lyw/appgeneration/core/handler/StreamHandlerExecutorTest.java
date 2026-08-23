package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.UserMemoryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StreamHandlerExecutorTest {

    @Test
    void 普通生成只能输出SimpleText且不能进入Vue入口() {
        StreamHandlerExecutor executor = executor();
        ChatHistoryService history = mock(ChatHistoryService.class);
        when(history.addAiMessageAndReturn(
                anyLong(), anyString(), anyString(), any(), anyLong()))
                .thenReturn(ChatHistory.builder().id(11L).build());
        AppOperationLeaseManager leases = new AppOperationLeaseManager();
        SimpleGenerationTurnContext context =
                new SimpleGenerationTurnContext(leases.acquire(
                        7L,
                        AppOperationLeaseManager.AppOperationType.GENERATE,
                        "普通生成"));

        StepVerifier.create(executor.doExecute(
                        Flux.just("第一段", "第二段"), history,
                        7L, User.builder().id(9L).build(),
                        CodeGenTypeEnum.HTML, context))
                .assertNext(event -> assertEquals(
                        new GenerationStreamEvent.SimpleText("第一段"), event))
                .assertNext(event -> assertEquals(
                        new GenerationStreamEvent.SimpleText("第二段"), event))
                .verifyComplete();

        context.close();
    }

    @Test
    void Vue禁止退回普通文本入口() {
        StreamHandlerExecutor executor = executor();
        AppOperationLeaseManager leases = new AppOperationLeaseManager();
        SimpleGenerationTurnContext context =
                new SimpleGenerationTurnContext(leases.acquire(
                        7L,
                        AppOperationLeaseManager.AppOperationType.GENERATE,
                        "错误Vue入口"));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> executor.doExecute(
                        Flux.just("不得降级"), mock(ChatHistoryService.class),
                        7L, User.builder().id(9L).build(),
                        CodeGenTypeEnum.VUE_PROJECT, context));

        assertEquals("Vue 流必须使用绑定精确回合上下文的 doExecuteVue",
                error.getMessage());
        context.close();
    }

    private StreamHandlerExecutor executor() {
        StreamHandlerExecutor executor = new StreamHandlerExecutor();
        ReflectionTestUtils.setField(
                executor, "jsonMessageStreamHandler",
                new JsonMessageStreamHandler(
                        mock(com.lyw.appgeneration.manger.ToolManager.class),
                        mock(VueTurnFinalizer.class),
                        mock(VueTurnCancellationCoordinator.class)));
        ReflectionTestUtils.setField(
                executor, "memorySummaryService",
                mock(MemorySummaryService.class));
        ReflectionTestUtils.setField(
                executor, "userMemoryService",
                mock(UserMemoryService.class));
        ReflectionTestUtils.setField(
                executor, "appDataLifecycleFence",
                new AppDataLifecycleFence());
        return executor;
    }
}
