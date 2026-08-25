package com.lyw.appgeneration.core;

import com.lyw.appgeneration.ai.AiCodeGeneratorService;
import com.lyw.appgeneration.ai.AiGeneratorServiceFactory;
import com.lyw.appgeneration.ai.VueEvaluationCodeGeneratorService;
import com.lyw.appgeneration.ai.image.ImageCollectionService;
import com.lyw.appgeneration.ai.tools.FileToolExecutionScopeManager;
import com.lyw.appgeneration.ai.tools.FileToolBudgetGuard;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.core.concurrency.VueTurnAdmissionController;
import com.lyw.appgeneration.core.handler.SimpleGenerationTurnContext;
import com.lyw.appgeneration.core.handler.VueTurnContext;
import com.lyw.appgeneration.core.handler.GenerationStreamEvent;
import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.service.rag.RagPromptAssembler;
import com.lyw.appgeneration.service.rag.RagRetrievalService;
import com.lyw.appgeneration.service.rag.model.RetrievedSnippet;
import com.lyw.appgeneration.service.rag.model.TemplateDoc;
import com.lyw.appgeneration.service.rag.model.VueRagContext;
import com.lyw.appgeneration.monitor.VueBuildRepairMetricsCollector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import dev.langchain4j.service.ModelRequestGate;
import dev.langchain4j.service.GenerationStreamSignal;
import dev.langchain4j.service.InternalOutputRecoveryPolicy;
import dev.langchain4j.service.InternalOutputProtocolException;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.ToolExecutionGuard;
import dev.langchain4j.service.ToolLoopTerminationProtocol;
import dev.langchain4j.service.ToolProtocolRecoveryPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiCodeGeneratorFacadeTest {

    private static final long APP_ID = 7L;
    private static final String RAW_QUERY = "生成一个带数据看板的 Vue 后台";
    private static final String ENHANCED_QUERY = RAW_QUERY + "\n图片资源";
    private static final String AUGMENTED_QUERY = "RAG 上下文\n" + ENHANCED_QUERY;

    @Mock
    private AiGeneratorServiceFactory serviceFactory;

    @Mock
    private AiCodeGeneratorService generatorService;

    @Mock
    private VueEvaluationCodeGeneratorService evaluationGeneratorService;

    @Mock
    private ImageCollectionService imageCollectionService;

    @Mock
    private RagRetrievalService retrievalService;

    @Mock
    private RagPromptAssembler promptAssembler;

    @Mock
    private TokenStream tokenStream;

    @Mock
    private ModelRequestGate modelRequestGate;

    private RagProperties properties;
    private AiCodeGeneratorFacade facade;
    private FileToolExecutionScopeManager scopeManager;

    @Test
    void 普通流式入口必须暴露可取消的TokenStream() throws Exception {
        assertEquals(TokenStream.class,
                AiCodeGeneratorService.class.getMethod(
                        "generateHtmlCodeStream", String.class)
                        .getReturnType());
        assertEquals(TokenStream.class,
                AiCodeGeneratorService.class.getMethod(
                        "generateMultiFileCodeStream", String.class)
                        .getReturnType());
    }

    @Test
    void 在线生成不得公开无真实回合上下文的四参数入口() {
        assertThrows(NoSuchMethodException.class,
                () -> AiCodeGeneratorFacade.class.getMethod(
                        "generateAndSaveCodeStream",
                        String.class,
                        CodeGenTypeEnum.class,
                        long.class,
                        boolean.class));
    }

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        scopeManager = new FileToolExecutionScopeManager(new FileToolBudgetGuard());
        facade = new AiCodeGeneratorFacade();
        ReflectionTestUtils.setField(facade, "aiGeneratorServiceFactory", serviceFactory);
        ReflectionTestUtils.setField(facade, "imageCollectionService", imageCollectionService);
        ReflectionTestUtils.setField(facade, "ragRetrievalService", retrievalService);
        ReflectionTestUtils.setField(facade, "ragPromptAssembler", promptAssembler);
        ReflectionTestUtils.setField(facade, "ragProperties", properties);
        ReflectionTestUtils.setField(facade, "fileToolExecutionScopeManager", scopeManager);
        ReflectionTestUtils.setField(facade, "modelRequestGate", modelRequestGate);
    }

    @Test
    void 普通在线生成安装统一门禁和真实回合原子门() {
        when(generatorService.generateHtmlCodeStream(RAW_QUERY))
                .thenReturn(tokenStream);
        var operation = new AppOperationLeaseManager().acquire(
                APP_ID, AppOperationLeaseManager.AppOperationType.GENERATE,
                "普通门禁安装");
        SimpleGenerationTurnContext context =
                new SimpleGenerationTurnContext(operation);

        facade.generateAndSaveCodeStream(
                RAW_QUERY, CodeGenTypeEnum.HTML, APP_ID, false,
                context, generatorService);

        verify(tokenStream).modelRequestGate(modelRequestGate, context);
        var policyCaptor = org.mockito.ArgumentCaptor.forClass(
                dev.langchain4j.service.InternalOutputRecoveryPolicy.class);
        verify(tokenStream).internalOutputRecoveryPolicy(
                policyCaptor.capture());
        assertEquals(dev.langchain4j.service.InternalOutputRecoveryPolicy
                        .Mode.FAIL_FAST,
                policyCaptor.getValue().mode());
        assertEquals(com.lyw.appgeneration.ai.memory
                        .SyntheticMemoryMessageProtocol.RESERVED_PREFIX,
                policyCaptor.getValue().reservedPrefix());
        context.close();
    }

    @Test
    void Vue在线生成安装统一门禁和真实回合原子门() {
        properties.setEnabled(false);
        when(generatorService.generateVueProjectCodeStream(APP_ID, RAW_QUERY))
                .thenReturn(tokenStream);
        AppOperationLeaseManager operationManager =
                new AppOperationLeaseManager();
        VueBuildSessionManager sessionManager = new VueBuildSessionManager();
        var operation = operationManager.acquire(
                APP_ID, AppOperationLeaseManager.AppOperationType.GENERATE,
                "turn-gate-install");
        var lease = sessionManager.open(
                operation, 9L, "turn-gate-install");
        VueTurnContext context = new VueTurnContext(
                APP_ID, 9L, "turn-gate-install", operation, lease,
                admissionPermit(),
                new FileToolBudgetGuard().newSession());

        facade.generateVueProjectStream(
                RAW_QUERY, APP_ID, false, context, generatorService);

        verify(tokenStream).modelRequestGate(modelRequestGate, context);
        var policyCaptor = org.mockito.ArgumentCaptor.forClass(
                ToolProtocolRecoveryPolicy.class);
        verify(tokenStream).toolProtocolRecoveryPolicy(policyCaptor.capture());
        assertEquals(Set.of(
                        "writeFile", "readFile", "modifyFile", "deleteFile",
                        "readDir", "buildProject"),
                policyCaptor.getValue().registeredToolNames());
        var internalPolicyCaptor = org.mockito.ArgumentCaptor.forClass(
                InternalOutputRecoveryPolicy.class);
        verify(tokenStream).internalOutputRecoveryPolicy(
                internalPolicyCaptor.capture());
        assertEquals(InternalOutputRecoveryPolicy.Mode.RECOVER_ONCE,
                internalPolicyCaptor.getValue().mode());
        context.closeResources();
    }

    @Test
    void Vue统一信号必须按generation序列化为在线内部消息() {
        properties.setEnabled(false);
        UnifiedGenerationTokenStream stream =
                new UnifiedGenerationTokenStream();
        when(generatorService.generateVueProjectCodeStream(
                APP_ID, RAW_QUERY)).thenReturn(stream);
        VueTurnContext context = newVueTurnContext(
                "unified-generation-stream");

        List<String> output = facade.generateVueProjectStream(
                        RAW_QUERY, APP_ID, false, context,
                        generatorService)
                .collectList().block();

        assertEquals(5, output.size());
        assertTrue(output.get(0).contains("\"type\":\"ai_response\""));
        assertTrue(output.get(0).contains("\"generation\":1"));
        assertTrue(output.get(1).contains("\"type\":\"tool_request\""));
        assertTrue(output.get(1).contains("\"generation\":1"));
        assertTrue(output.get(2).contains(
                "\"type\":\"tool_executed\""));
        assertTrue(output.get(2).contains("\"generation\":1"));
        assertTrue(output.get(3).contains(
                "\"type\":\"internal_output_rollback\""));
        assertTrue(output.get(4).contains(
                "\"type\":\"internal_output_recovery\""));
        assertEquals(0, stream.legacyBusinessRegistrations.get());
        context.closeResources();
    }

    @Test
    void 回滚临时工具请求不得finish并补发未完成参数() {
        properties.setEnabled(false);
        RollbackPartialToolTokenStream stream =
                new RollbackPartialToolTokenStream();
        when(generatorService.generateVueProjectCodeStream(
                APP_ID, RAW_QUERY)).thenReturn(stream);
        VueTurnContext context = newVueTurnContext(
                "rollback-partial-tool");

        List<String> output = facade.generateVueProjectStream(
                        RAW_QUERY, APP_ID, false, context,
                        generatorService)
                .collectList().block();

        assertEquals(2, output.size());
        assertTrue(output.getFirst().contains(
                "\"type\":\"tool_request\""));
        assertTrue(output.getLast().contains(
                "\"type\":\"internal_output_rollback\""));
        assertTrue(output.stream().noneMatch(message ->
                        message.contains("tool_argument")),
                "回滚 provisional parser 时不得 finish 补发参数");
        context.closeResources();
    }

    @Test
    void 普通在线生成不得安装Vue工具协议恢复策略() {
        when(generatorService.generateHtmlCodeStream(RAW_QUERY))
                .thenReturn(tokenStream);
        var context = newSimpleTurnContext("simple-no-tool-recovery");

        facade.generateAndSaveCodeStream(
                RAW_QUERY, CodeGenTypeEnum.HTML, APP_ID, false,
                context, generatorService);

        verify(tokenStream, never()).toolProtocolRecoveryPolicy(any());
        context.close();
    }

    @Test
    void 普通内部协议失败必须抛专用异常且不得进入文件保存() {
        OnlineControlledTokenStream stream = new OnlineControlledTokenStream(
                ToolLoopTerminationProtocol.ControlledTerminationReason
                        .PROTOCOL_ERROR);
        when(generatorService.generateHtmlCodeStream(RAW_QUERY))
                .thenReturn(stream);
        var context = newSimpleTurnContext("simple-protocol-error");
        AppDataLifecycleFence fence = new AppDataLifecycleFence();
        ReflectionTestUtils.setField(facade, "appDataLifecycleFence", fence);

        StepVerifier.create(facade.generateAndSaveCodeStream(
                        RAW_QUERY, CodeGenTypeEnum.HTML, APP_ID, false,
                        context, generatorService))
                .expectError(dev.langchain4j.service
                        .InternalOutputProtocolException.class)
                .verify();

        assertTrue(fence.isOpen(APP_ID),
                "协议失败不得进入文件保存或改变删除栅栏状态");
        context.close();
    }

    @Test
    void 完整文本文件保存边界必须再次拒绝保留标记() {
        var context = newSimpleTurnContext("simple-final-file-guard");
        ReflectionTestUtils.setField(facade, "appDataLifecycleFence",
                new AppDataLifecycleFence());

        assertThrows(InternalOutputProtocolException.class,
                () -> ReflectionTestUtils.invokeMethod(
                        facade, "saveSimpleCode",
                        "<html>[[server.synthetic-memory/test]]</html>",
                        CodeGenTypeEnum.HTML, APP_ID, context));

        context.close();
    }

    @Test
    void Vue恢复策略阶段必须进入受信进度通道且终态后迟到事件被丢弃() {
        properties.setEnabled(false);
        CapturingRecoveryTokenStream stream =
                new CapturingRecoveryTokenStream();
        when(generatorService.generateVueProjectCodeStream(APP_ID, RAW_QUERY))
                .thenReturn(stream);
        VueTurnContext context = newVueTurnContext("trusted-recovery-progress");
        Flux<GenerationStreamEvent> merged = context.mergeProgress(
                facade.generateVueProjectStream(
                                RAW_QUERY, APP_ID, false, context,
                                generatorService)
                        .thenMany(Flux.just(
                                GenerationStreamEvent.content("正文"))));

        stream.beforeStart = () -> {
            stream.publish(ToolProtocolRecoveryPolicy.Phase.STARTED);
            stream.publish(ToolProtocolRecoveryPolicy.Phase.RECOVERED);
        };

        StepVerifier.create(merged)
                .assertNext(event -> assertEquals(
                        com.lyw.appgeneration.ai.model.message
                                .ToolProtocolRecoveryMessage.Phase.STARTED,
                        ((GenerationStreamEvent.ToolProtocolRecovery) event)
                                .message().phase()))
                .assertNext(event -> assertEquals(
                        com.lyw.appgeneration.ai.model.message
                                .ToolProtocolRecoveryMessage.Phase.RECOVERED,
                        ((GenerationStreamEvent.ToolProtocolRecovery) event)
                                .message().phase()))
                .expectNext(GenerationStreamEvent.content("正文"))
                .verifyComplete();

        assertTrue(context.tryStartFinalization(
                VueTurnContext.TerminalTrigger.COMPLETED));
        stream.publish(ToolProtocolRecoveryPolicy.Phase.FAILED);
        assertEquals(3, stream.publishedPhases.get(),
                "策略监听器可收到迟到阶段，但回合通道必须负责丢弃");
        context.closeResources();
    }

    @Test
    void hybridFirstVueEnhancesGenerationRequestAndRetrievesRawQuery() {
        stubVueGenerator();
        properties.getHybrid().setEnabled(true);
        VueRagContext context = context("vue-skeleton");
        VueTurnContext turnContext = newVueTurnContext("hybrid-first");
        when(retrievalService.retrieveVueProject(RAW_QUERY)).thenReturn(context);
        when(imageCollectionService.enhancePrompt(RAW_QUERY)).thenReturn(ENHANCED_QUERY);
        when(promptAssembler.assembleVueProject(ENHANCED_QUERY, context)).thenReturn(AUGMENTED_QUERY);

        facade.generateVueProjectStream(
                RAW_QUERY, APP_ID, true, turnContext, generatorService);

        InOrder order = inOrder(retrievalService, imageCollectionService, promptAssembler, generatorService);
        order.verify(imageCollectionService).enhancePrompt(RAW_QUERY);
        order.verify(retrievalService).retrieveVueProject(RAW_QUERY);
        order.verify(promptAssembler).assembleVueProject(ENHANCED_QUERY, context);
        order.verify(generatorService).generateVueProjectCodeStream(APP_ID, AUGMENTED_QUERY);
        verify(retrievalService, never()).retrieve(any(), any());
        verify(promptAssembler, never()).assemble(any(), anyList());
        turnContext.closeResources();
    }

    @Test
    void hybridNonFirstVueSkipsImagesAndRag() {
        stubVueGenerator();
        properties.getHybrid().setEnabled(true);
        VueTurnContext turnContext = newVueTurnContext("hybrid-non-first");

        facade.generateVueProjectStream(
                RAW_QUERY, APP_ID, false, turnContext, generatorService);

        verify(imageCollectionService, never()).enhancePrompt(any());
        verifyNoInteractions(retrievalService, promptAssembler);
        verify(generatorService).generateVueProjectCodeStream(APP_ID, RAW_QUERY);
        turnContext.closeResources();
    }

    @Test
    void disabledHybridEnhancesGenerationRequestAndUsesRawQueryDenseOnly() {
        stubVueGenerator();
        properties.setEnabled(true);
        properties.getHybrid().setEnabled(false);
        VueRagContext context = context("dense-skeleton");
        VueTurnContext turnContext = newVueTurnContext("dense-only");
        when(retrievalService.retrieveVueProjectDenseOnly(RAW_QUERY)).thenReturn(context);
        when(imageCollectionService.enhancePrompt(RAW_QUERY)).thenReturn(ENHANCED_QUERY);
        when(promptAssembler.assembleVueProject(ENHANCED_QUERY, context)).thenReturn(AUGMENTED_QUERY);

        facade.generateVueProjectStream(
                RAW_QUERY, APP_ID, true, turnContext, generatorService);

        InOrder order = inOrder(retrievalService, imageCollectionService, promptAssembler, generatorService);
        order.verify(imageCollectionService).enhancePrompt(RAW_QUERY);
        order.verify(retrievalService).retrieveVueProjectDenseOnly(RAW_QUERY);
        order.verify(promptAssembler).assembleVueProject(ENHANCED_QUERY, context);
        order.verify(generatorService).generateVueProjectCodeStream(APP_ID, AUGMENTED_QUERY);
        verify(retrievalService, never()).retrieve(any(), any());
        verify(retrievalService, never()).retrieveVueProject(any());
        verify(promptAssembler, never()).assemble(any(), anyList());
        turnContext.closeResources();
    }

    @Test
    void disabledRagSkipsEveryVueRagStageEvenWhenHybridIsEnabled() {
        stubVueGenerator();
        properties.setEnabled(false);
        properties.getHybrid().setEnabled(true);
        VueTurnContext turnContext = newVueTurnContext("rag-disabled");
        when(imageCollectionService.enhancePrompt(RAW_QUERY)).thenReturn(ENHANCED_QUERY);

        facade.generateVueProjectStream(
                RAW_QUERY, APP_ID, true, turnContext, generatorService);

        verifyNoInteractions(retrievalService, promptAssembler);
        verify(imageCollectionService).enhancePrompt(RAW_QUERY);
        verify(generatorService).generateVueProjectCodeStream(APP_ID, ENHANCED_QUERY);
        turnContext.closeResources();
    }

    @Test
    void hybridRetrievalFailureStillEnhancesAndGeneratesWithEmptyContext() {
        stubVueGenerator();
        properties.getHybrid().setEnabled(true);
        VueTurnContext turnContext = newVueTurnContext("rag-failure");
        when(retrievalService.retrieveVueProject(RAW_QUERY))
                .thenThrow(new IllegalStateException("检索依赖失败"));
        when(imageCollectionService.enhancePrompt(RAW_QUERY)).thenReturn(ENHANCED_QUERY);
        when(promptAssembler.assembleVueProject(ENHANCED_QUERY, VueRagContext.unavailable()))
                .thenReturn("无 RAG 拼装");

        assertDoesNotThrow(() -> facade.generateVueProjectStream(
                RAW_QUERY, APP_ID, true, turnContext, generatorService));

        verify(imageCollectionService).enhancePrompt(RAW_QUERY);
        verify(promptAssembler).assembleVueProject(ENHANCED_QUERY, VueRagContext.unavailable());
        verify(generatorService).generateVueProjectCodeStream(APP_ID, "无 RAG 拼装");
        turnContext.closeResources();
    }

    @Test
    void imageServiceFallbackKeepsRawGenerationRequest() {
        stubVueGenerator();
        properties.getHybrid().setEnabled(true);
        VueRagContext context = context("vue-skeleton");
        VueTurnContext turnContext = newVueTurnContext("image-fallback");
        when(retrievalService.retrieveVueProject(RAW_QUERY)).thenReturn(context);
        when(imageCollectionService.enhancePrompt(RAW_QUERY)).thenReturn(RAW_QUERY);
        when(promptAssembler.assembleVueProject(RAW_QUERY, context)).thenReturn("原消息拼装");

        facade.generateVueProjectStream(
                RAW_QUERY, APP_ID, true, turnContext, generatorService);

        verify(promptAssembler).assembleVueProject(RAW_QUERY, context);
        verify(generatorService).generateVueProjectCodeStream(APP_ID, "原消息拼装");
        turnContext.closeResources();
    }

    @ParameterizedTest
    @EnumSource(value = ToolLoopTerminationProtocol.ControlledTerminationReason.class,
            names = {"CANCELLED", "PROTOCOL_ERROR", "LOOP_LIMIT_EXCEEDED",
                    "REPEATED_READ_LOOP", "INCOMPLETE_TOOL_CHAIN",
                    "RESOURCE_LIMIT_EXCEEDED",
                    "EVALUATION_COMPLETED"})
    void onlineNonSuccessfulControlledTerminationFailsFlux(
            ToolLoopTerminationProtocol.ControlledTerminationReason reason) {
        OnlineControlledTokenStream stream = new OnlineControlledTokenStream(reason);
        stubOnlineControlledGenerator(stream);
        VueTurnContext context = newVueTurnContext(
                "controlled-failure-" + reason);

        RuntimeException error = assertThrows(RuntimeException.class, () ->
                facade.generateVueProjectStream(
                        RAW_QUERY, APP_ID, false, context, generatorService)
                        .then().block());

        assertTrue(error instanceof AiCodeGeneratorFacade
                .OnlineControlledTerminationException);
        assertEquals(reason, ((AiCodeGeneratorFacade.OnlineControlledTerminationException)
                error).reason());
        context.closeResources();
    }

    @ParameterizedTest
    @EnumSource(value = ToolLoopTerminationProtocol.ControlledTerminationReason.class,
            names = {"BUILD_SUCCEEDED", "BUILD_FAILED"})
    void onlineBuildControlledTerminationCompletesFlux(
            ToolLoopTerminationProtocol.ControlledTerminationReason reason) {
        OnlineControlledTokenStream stream = new OnlineControlledTokenStream(reason);
        stubOnlineControlledGenerator(stream);
        VueTurnContext context = newVueTurnContext(
                "controlled-success-" + reason);

        assertDoesNotThrow(() -> facade.generateVueProjectStream(
                RAW_QUERY, APP_ID, false, context, generatorService)
                .then().block());
        context.closeResources();
    }

    @Test
    void onlineCompleteOnlyToolRequestIsEmittedBeforeToolExecution() {
        properties.setEnabled(false);
        CompleteOnlyToolRequestTokenStream stream =
                new CompleteOnlyToolRequestTokenStream();
        when(serviceFactory.getAiCodeGeneratorService(
                APP_ID, CodeGenTypeEnum.VUE_PROJECT)).thenReturn(generatorService);
        when(generatorService.generateVueProjectCodeStream(APP_ID, RAW_QUERY))
                .thenReturn(stream);
        AppOperationLeaseManager operationManager = new AppOperationLeaseManager();
        VueBuildSessionManager sessionManager = new VueBuildSessionManager();
        var operation = operationManager.acquire(
                APP_ID, AppOperationLeaseManager.AppOperationType.GENERATE,
                "turn-complete-tool-request");
        var lease = sessionManager.open(
                operation, 9L, "turn-complete-tool-request");
        VueTurnContext context = new VueTurnContext(
                APP_ID, 9L, "turn-complete-tool-request", operation, lease,
                admissionPermit(),
                new FileToolBudgetGuard().newSession());

        List<String> output = facade.generateVueProjectStream(
                        RAW_QUERY, APP_ID, false, context)
                .collectList().block();

        assertEquals(2, output.size());
        assertTrue(output.get(0).contains("\"type\":\"tool_request\""),
                output.toString());
        assertTrue(output.get(0).contains("\"name\":\"buildProject\""),
                output.toString());
        assertTrue(output.get(1).contains("\"type\":\"tool_executed\""),
                output.toString());
        context.closeResources();
    }

    @Test
    void onlineTurnRunsRealToolThreadInExactScopeAndRecordsTermination() {
        properties.setEnabled(false);
        CapturingTokenStream stream = new CapturingTokenStream(
                CapturingTokenStream.Terminal.COMPLETE, scopeManager);
        when(serviceFactory.getAiCodeGeneratorService(APP_ID,
                CodeGenTypeEnum.VUE_PROJECT)).thenReturn(generatorService);
        when(generatorService.generateVueProjectCodeStream(APP_ID, RAW_QUERY))
                .thenReturn(stream);
        AppOperationLeaseManager operationManager = new AppOperationLeaseManager();
        VueBuildSessionManager sessionManager = new VueBuildSessionManager();
        var operation = operationManager.acquire(APP_ID,
                AppOperationLeaseManager.AppOperationType.GENERATE, "turn-online");
        var lease = sessionManager.open(operation, 9L, "turn-online");
        VueTurnContext context = new VueTurnContext(
                APP_ID, 9L, "turn-online", operation, lease,
                admissionPermit(),
                new FileToolBudgetGuard().newSession());

        assertDoesNotThrow(() -> facade.generateVueProjectStream(
                RAW_QUERY, APP_ID, false, context).then().block());

        assertEquals(FileToolExecutionScopeManager.ScopeType.ONLINE,
                stream.observedScopeType);
        assertEquals("turn-online", stream.observedScope.ownerToken());
        assertTrue(context.controlledTermination().isEmpty());
        assertEquals(1, stream.gateInstallations.get());
        context.closeResources();
    }

    @Test
    void onlineStreamingMutationStopsBeforeBroadcastLimitAndCompletesToolCard() {
        FileToolBudgetGuard guard = new FileToolBudgetGuard();
        guard.setMaxSingleFileCodePoints(4);
        guard.setMaxCumulativeMutationCodePoints(6);
        guard.setMaxCanonicalAiTextCodePoints(64);
        guard.setMaxReadFileCodePoints(4);
        guard.setMaxReadDirCodePoints(4);
        scopeManager = new FileToolExecutionScopeManager(guard);
        ReflectionTestUtils.setField(
                facade, "fileToolExecutionScopeManager", scopeManager);
        ResourceLimitedTokenStream stream = new ResourceLimitedTokenStream();
        properties.setEnabled(false);
        when(serviceFactory.getAiCodeGeneratorService(
                APP_ID, CodeGenTypeEnum.VUE_PROJECT)).thenReturn(generatorService);
        when(generatorService.generateVueProjectCodeStream(APP_ID, RAW_QUERY))
                .thenReturn(stream);
        AppOperationLeaseManager operationManager = new AppOperationLeaseManager();
        VueBuildSessionManager sessionManager = new VueBuildSessionManager();
        var operation = operationManager.acquire(APP_ID,
                AppOperationLeaseManager.AppOperationType.GENERATE,
                "turn-resource-limit");
        var lease = sessionManager.open(operation, 9L, "turn-resource-limit");
        VueTurnContext context = new VueTurnContext(
                APP_ID, 9L, "turn-resource-limit", operation, lease,
                admissionPermit(),
                guard.newSession());

        List<String> output = new CopyOnWriteArrayList<>();
        RuntimeException error = assertThrows(RuntimeException.class, () ->
                facade.generateVueProjectStream(
                                RAW_QUERY, APP_ID, false, context)
                        .doOnNext(output::add).then().block());

        assertTrue(error instanceof AiCodeGeneratorFacade
                .OnlineControlledTerminationException);
        assertEquals(ToolLoopTerminationProtocol.ControlledTerminationReason
                        .RESOURCE_LIMIT_EXCEEDED,
                ((AiCodeGeneratorFacade.OnlineControlledTerminationException)
                        error).reason());
        String all = String.join("\n", output);
        assertFalse(all.contains("A😀BCD"), all);
        assertTrue(all.contains("A😀BC"), all);
        assertTrue(all.contains("RESOURCE_LIMIT_EXCEEDED"), all);
        assertEquals(1, stream.resourceTerminations.get());
        assertEquals(1, stream.cancellations.get());
        context.closeResources();
    }

    @Test
    void modifyFile旧内容必须在完整值进入SSE前受预算终止() {
        FileToolBudgetGuard guard = new FileToolBudgetGuard();
        guard.setMaxSingleFileCodePoints(4);
        guard.setMaxCumulativeMutationCodePoints(6);
        guard.setMaxCanonicalAiTextCodePoints(64);
        guard.setMaxReadFileCodePoints(4);
        guard.setMaxReadDirCodePoints(4);
        scopeManager = new FileToolExecutionScopeManager(guard);
        ReflectionTestUtils.setField(
                facade, "fileToolExecutionScopeManager", scopeManager);
        ResourceLimitedTokenStream stream = new ResourceLimitedTokenStream(
                "modifyFile",
                "{\"relativeFilePath\":\"src/App.vue\","
                        + "\"oldContent\":\"A😀BCD\",\"newContent\":\"X\"}");
        properties.setEnabled(false);
        when(serviceFactory.getAiCodeGeneratorService(
                APP_ID, CodeGenTypeEnum.VUE_PROJECT)).thenReturn(generatorService);
        when(generatorService.generateVueProjectCodeStream(APP_ID, RAW_QUERY))
                .thenReturn(stream);
        AppOperationLeaseManager operationManager = new AppOperationLeaseManager();
        VueBuildSessionManager sessionManager = new VueBuildSessionManager();
        var operation = operationManager.acquire(APP_ID,
                AppOperationLeaseManager.AppOperationType.GENERATE,
                "turn-old-content-limit");
        var lease = sessionManager.open(
                operation, 9L, "turn-old-content-limit");
        VueTurnContext context = new VueTurnContext(
                APP_ID, 9L, "turn-old-content-limit", operation, lease,
                admissionPermit(),
                guard.newSession());

        List<String> output = new CopyOnWriteArrayList<>();
        RuntimeException error = assertThrows(RuntimeException.class, () ->
                facade.generateVueProjectStream(
                                RAW_QUERY, APP_ID, false, context)
                        .doOnNext(output::add).then().block());

        assertTrue(error instanceof AiCodeGeneratorFacade
                .OnlineControlledTerminationException);
        String all = String.join("\n", output);
        assertFalse(all.contains("A😀BCD"), all);
        assertTrue(all.contains("A😀BC"), all);
        assertTrue(all.contains("RESOURCE_LIMIT_EXCEEDED"), all);
        assertEquals(1, stream.resourceTerminations.get());
        assertEquals(1, stream.cancellations.get());
        assertEquals(1, output.stream()
                .filter(message -> message.contains("\"type\":\"tool_executed\""))
                .count());
        context.closeResources();
    }

    @Test
    void executedResourceLimitResultRedactsOriginalArgumentsFromSse() {
        ExecutedResourceLimitTokenStream stream =
                new ExecutedResourceLimitTokenStream();
        properties.setEnabled(false);
        when(serviceFactory.getAiCodeGeneratorService(
                APP_ID, CodeGenTypeEnum.VUE_PROJECT)).thenReturn(generatorService);
        when(generatorService.generateVueProjectCodeStream(APP_ID, RAW_QUERY))
                .thenReturn(stream);
        AppOperationLeaseManager operationManager = new AppOperationLeaseManager();
        VueBuildSessionManager sessionManager = new VueBuildSessionManager();
        var operation = operationManager.acquire(APP_ID,
                AppOperationLeaseManager.AppOperationType.GENERATE,
                "turn-executed-resource-limit");
        var lease = sessionManager.open(
                operation, 9L, "turn-executed-resource-limit");
        VueTurnContext context = new VueTurnContext(
                APP_ID, 9L, "turn-executed-resource-limit", operation, lease,
                admissionPermit(),
                new FileToolBudgetGuard().newSession());

        List<String> output = new CopyOnWriteArrayList<>();
        assertThrows(RuntimeException.class, () -> facade.generateVueProjectStream(
                        RAW_QUERY, APP_ID, false, context)
                .doOnNext(output::add).then().block());

        String all = String.join("\n", output);
        assertFalse(all.contains(ExecutedResourceLimitTokenStream.SECRET), all);
        assertTrue(all.contains("RESOURCE_LIMIT_EXCEEDED"), all);
        assertTrue(all.contains("\"arguments\":\"{}\""), all);
        context.closeResources();
    }

    @Test
    void htmlKeepsLegacyOrderWithoutImageEnhancement() {
        List<RetrievedSnippet> snippets = List.of(snippet("html"));
        SimpleGenerationTurnContext context =
                newSimpleTurnContext("html-legacy-order");
        when(retrievalService.retrieve(RAW_QUERY, CodeGenTypeEnum.HTML)).thenReturn(snippets);
        when(promptAssembler.assemble(RAW_QUERY, snippets)).thenReturn("HTML 拼装");
        when(generatorService.generateHtmlCodeStream("HTML 拼装"))
                .thenReturn(tokenStream);

        facade.generateAndSaveCodeStream(
                RAW_QUERY, CodeGenTypeEnum.HTML, APP_ID, true,
                context, generatorService);

        InOrder order = inOrder(retrievalService, promptAssembler, generatorService);
        order.verify(retrievalService).retrieve(RAW_QUERY, CodeGenTypeEnum.HTML);
        order.verify(promptAssembler).assemble(RAW_QUERY, snippets);
        order.verify(generatorService).generateHtmlCodeStream("HTML 拼装");
        verify(imageCollectionService, never()).enhancePrompt(any());
        verify(retrievalService, never()).retrieveVueProject(any());
        context.close();
    }

    @Test
    void htmlNonFirstTurnSkipsRagAndUsesRawQuery() {
        SimpleGenerationTurnContext context =
                newSimpleTurnContext("html-non-first");
        when(generatorService.generateHtmlCodeStream(RAW_QUERY))
                .thenReturn(tokenStream);

        facade.generateAndSaveCodeStream(
                RAW_QUERY, CodeGenTypeEnum.HTML, APP_ID, false,
                context, generatorService);

        verifyNoInteractions(
                imageCollectionService, retrievalService, promptAssembler);
        verify(generatorService).generateHtmlCodeStream(RAW_QUERY);
        context.close();
    }

    @Test
    void disabledRagSkipsHtmlRetrievalEvenOnFirstTurn() {
        properties.setEnabled(false);
        SimpleGenerationTurnContext context =
                newSimpleTurnContext("html-rag-disabled");
        when(generatorService.generateHtmlCodeStream(RAW_QUERY))
                .thenReturn(tokenStream);

        facade.generateAndSaveCodeStream(
                RAW_QUERY, CodeGenTypeEnum.HTML, APP_ID, true,
                context, generatorService);

        verifyNoInteractions(
                imageCollectionService, retrievalService, promptAssembler);
        verify(generatorService).generateHtmlCodeStream(RAW_QUERY);
        context.close();
    }

    @Test
    void multiFileKeepsImageThenLegacyRetrievalOrder() {
        List<RetrievedSnippet> snippets = List.of(snippet("multi"));
        SimpleGenerationTurnContext context =
                newSimpleTurnContext("multi-legacy-order");
        when(imageCollectionService.enhancePrompt(RAW_QUERY)).thenReturn(ENHANCED_QUERY);
        when(retrievalService.retrieve(ENHANCED_QUERY, CodeGenTypeEnum.MULTI_FILE))
                .thenReturn(snippets);
        when(promptAssembler.assemble(ENHANCED_QUERY, snippets)).thenReturn("多文件拼装");
        when(generatorService.generateMultiFileCodeStream("多文件拼装"))
                .thenReturn(tokenStream);

        facade.generateAndSaveCodeStream(
                RAW_QUERY, CodeGenTypeEnum.MULTI_FILE, APP_ID, true,
                context, generatorService);

        InOrder order = inOrder(imageCollectionService, retrievalService, promptAssembler, generatorService);
        order.verify(imageCollectionService).enhancePrompt(RAW_QUERY);
        order.verify(retrievalService).retrieve(ENHANCED_QUERY, CodeGenTypeEnum.MULTI_FILE);
        order.verify(promptAssembler).assemble(ENHANCED_QUERY, snippets);
        order.verify(generatorService).generateMultiFileCodeStream("多文件拼装");
        verify(retrievalService, never()).retrieveVueProject(any());
        context.close();
    }

    @Test
    void multiFileNonFirstTurnSkipsImagesAndRagAndUsesRawQuery() {
        SimpleGenerationTurnContext context =
                newSimpleTurnContext("multi-non-first");
        when(generatorService.generateMultiFileCodeStream(RAW_QUERY))
                .thenReturn(tokenStream);

        facade.generateAndSaveCodeStream(
                RAW_QUERY, CodeGenTypeEnum.MULTI_FILE, APP_ID, false,
                context, generatorService);

        verifyNoInteractions(
                imageCollectionService, retrievalService, promptAssembler);
        verify(generatorService).generateMultiFileCodeStream(RAW_QUERY);
        context.close();
    }

    @Test
    void disabledRagKeepsFirstTurnMultiFileImageEnhancement() {
        properties.setEnabled(false);
        SimpleGenerationTurnContext context =
                newSimpleTurnContext("multi-rag-disabled");
        when(imageCollectionService.enhancePrompt(RAW_QUERY))
                .thenReturn(ENHANCED_QUERY);
        when(generatorService.generateMultiFileCodeStream(ENHANCED_QUERY))
                .thenReturn(tokenStream);

        facade.generateAndSaveCodeStream(
                RAW_QUERY, CodeGenTypeEnum.MULTI_FILE, APP_ID, true,
                context, generatorService);

        verify(imageCollectionService).enhancePrompt(RAW_QUERY);
        verifyNoInteractions(retrievalService, promptAssembler);
        verify(generatorService).generateMultiFileCodeStream(ENHANCED_QUERY);
        context.close();
    }

    @Test
    void 普通文件保存异常向流传播() {
        when(serviceFactory.getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.MULTI_FILE))
                .thenReturn(generatorService);
        when(imageCollectionService.enhancePrompt(RAW_QUERY)).thenReturn(RAW_QUERY);
        when(retrievalService.retrieve(RAW_QUERY, CodeGenTypeEnum.MULTI_FILE))
                .thenReturn(List.of());
        when(promptAssembler.assemble(RAW_QUERY, List.of())).thenReturn(RAW_QUERY);
        when(generatorService.generateMultiFileCodeStream(RAW_QUERY))
                .thenReturn(SimpleTokenStream.completed("没有可保存的代码块"));
        AppDataLifecycleFence fence = new AppDataLifecycleFence();
        ReflectionTestUtils.setField(facade, "appDataLifecycleFence", fence);
        var operation = new AppOperationLeaseManager().acquire(
                APP_ID, AppOperationLeaseManager.AppOperationType.GENERATE, "普通回合");
        SimpleGenerationTurnContext context =
                new SimpleGenerationTurnContext(operation);

        StepVerifier.create(facade.generateAndSaveCodeStream(
                        RAW_QUERY, CodeGenTypeEnum.MULTI_FILE, APP_ID, true, context))
                .expectNext("没有可保存的代码块")
                .expectErrorMessage("HTML代码内容不能为空")
                .verify();

        context.close();
    }

    @Test
    void 删除早于订阅时取消真实TokenStream且禁止启动模型() {
        ManualSimpleTokenStream stream = new ManualSimpleTokenStream();
        stubSimpleHtmlGenerator(stream);
        AppOperationLeaseManager leases = new AppOperationLeaseManager();
        var operation = leases.acquire(
                APP_ID, AppOperationLeaseManager.AppOperationType.GENERATE,
                "普通回合");
        SimpleGenerationTurnContext context =
                new SimpleGenerationTurnContext(operation);
        Flux<String> result = facade.generateAndSaveCodeStream(
                RAW_QUERY, CodeGenTypeEnum.HTML, APP_ID, false, context);
        operation.requestCancellation();

        StepVerifier.create(result).verifyComplete();

        assertEquals(1, stream.cancellations.get());
        assertEquals(0, stream.starts.get(), "已取消回合不能再启动模型");
        context.close();
    }

    @Test
    void 客户端取消传播到真实TokenStream且丢弃晚到回调() {
        ManualSimpleTokenStream stream = new ManualSimpleTokenStream();
        stubSimpleHtmlGenerator(stream);
        var operation = new AppOperationLeaseManager().acquire(
                APP_ID, AppOperationLeaseManager.AppOperationType.GENERATE,
                "普通回合");
        SimpleGenerationTurnContext context =
                new SimpleGenerationTurnContext(operation);
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        reactor.core.Disposable client = facade.generateAndSaveCodeStream(
                        RAW_QUERY, CodeGenTypeEnum.HTML, APP_ID, false, context)
                .subscribe(received::add);
        stream.emitPartial("已发送");

        client.dispose();
        stream.emitPartial("晚到内容");
        stream.complete();

        assertEquals(List.of("已发送"), received);
        assertEquals(1, stream.starts.get());
        assertEquals(1, stream.cancellations.get());
        context.close();
    }

    @Test
    void evaluationEntryReturnsTheExactContextUsedByRealVueGeneration() {
        CapturingTokenStream evaluationStream = new CapturingTokenStream(
                CapturingTokenStream.Terminal.COMPLETE, scopeManager);
        when(serviceFactory.getVueEvaluationCodeGeneratorService(APP_ID))
                .thenReturn(evaluationGeneratorService);
        when(evaluationGeneratorService.generate(eq(APP_ID), any()))
                .thenReturn(evaluationStream);
        properties.getHybrid().setEnabled(true);
        VueRagContext context = context("selected-skeleton");
        when(retrievalService.retrieveVueProject(RAW_QUERY)).thenReturn(context);
        when(promptAssembler.assembleVueProject(RAW_QUERY, context)).thenReturn("评测生成提示词");

        AiCodeGeneratorFacade.VueProjectGeneration generation =
                facade.generateVueProjectForEvaluation(RAW_QUERY, APP_ID);

        assertEquals(context, generation.context());
        verify(imageCollectionService, never()).enhancePrompt(any());
        verify(serviceFactory, never()).getAiCodeGeneratorService(
                APP_ID, CodeGenTypeEnum.VUE_PROJECT);
        generation.stream().then().block();
        verify(evaluationGeneratorService).generate(APP_ID, "评测生成提示词");
        assertEquals(FileToolExecutionScopeManager.ScopeType.EVALUATION,
                evaluationStream.observedScopeType);
        assertThrows(FileToolExecutionScopeManager.ScopeViolationException.class,
                evaluationStream::executeCapturedGuardAgain,
                "AI 流完成后评测 scope 必须失效");
        assertThrows(FileToolExecutionScopeManager.ScopeViolationException.class,
                evaluationStream::executeCapturedScopeAgain,
                "AI 流完成后被捕获的 scope 也必须失效");
        assertEquals(0, evaluationStream.staleActions.get());
        assertEquals(0, evaluationStream.gateInstallations.get(),
                "离线 Vue 评测不得安装在线上下文门禁");
    }

    @Test
    void evaluationScopeCoversRealToolThreadAndReleasesOnError() {
        CapturingTokenStream evaluationStream = new CapturingTokenStream(
                CapturingTokenStream.Terminal.ERROR, scopeManager);
        stubEvaluationGenerator(evaluationStream);

        AiCodeGeneratorFacade.VueProjectGeneration generation =
                facade.generateVueProjectForEvaluation(RAW_QUERY, APP_ID);

        assertThrows(RuntimeException.class, () -> generation.stream().then().block());
        assertEquals(FileToolExecutionScopeManager.ScopeType.EVALUATION,
                evaluationStream.observedScopeType);
        assertThrows(FileToolExecutionScopeManager.ScopeViolationException.class,
                evaluationStream::executeCapturedGuardAgain);
        assertThrows(FileToolExecutionScopeManager.ScopeViolationException.class,
                evaluationStream::executeCapturedScopeAgain);
        assertEquals(0, evaluationStream.staleActions.get());
    }

    @Test
    void evaluationCancellationCancelsTokenStreamAndReleasesScope() {
        CapturingTokenStream evaluationStream = new CapturingTokenStream(
                CapturingTokenStream.Terminal.NONE, scopeManager);
        stubEvaluationGenerator(evaluationStream);

        reactor.core.Disposable subscription = facade
                .generateVueProjectForEvaluation(RAW_QUERY, APP_ID)
                .stream()
                .subscribe();
        subscription.dispose();

        assertEquals(1, evaluationStream.cancellations.get());
        assertEquals(1, evaluationStream.rejectionsObservedDuringCancel.get(),
                "调用底层 cancel 前必须先撤销评测 scope");
        assertThrows(FileToolExecutionScopeManager.ScopeViolationException.class,
                evaluationStream::executeCapturedGuardAgain);
        assertThrows(FileToolExecutionScopeManager.ScopeViolationException.class,
                evaluationStream::executeCapturedScopeAgain);
        assertEquals(0, evaluationStream.staleActions.get());
    }

    @Test
    void trustedEvaluationExitTerminatesToolLoopAndReleasesScope() {
        CapturingTokenStream evaluationStream = new CapturingTokenStream(
                CapturingTokenStream.Terminal.CONTROLLED, scopeManager);
        stubEvaluationGenerator(evaluationStream);

        facade.generateVueProjectForEvaluation(RAW_QUERY, APP_ID)
                .stream().then().block();

        assertEquals(ToolLoopTerminationProtocol.ControlledTerminationReason
                        .EVALUATION_COMPLETED,
                evaluationStream.observedTermination.reason());
        assertThrows(FileToolExecutionScopeManager.ScopeViolationException.class,
                evaluationStream::executeCapturedScopeAgain);
    }

    @Test
    void nonSuccessfulControlledTerminationFailsEvaluationAndReleasesScope() {
        CapturingTokenStream evaluationStream = new CapturingTokenStream(
                CapturingTokenStream.Terminal.LOOP_LIMIT, scopeManager);
        stubEvaluationGenerator(evaluationStream);

        RuntimeException error = assertThrows(RuntimeException.class, () ->
                facade.generateVueProjectForEvaluation(RAW_QUERY, APP_ID)
                        .stream().then().block());

        assertTrue(error.getMessage().contains("LOOP_LIMIT_EXCEEDED"));
        assertThrows(FileToolExecutionScopeManager.ScopeViolationException.class,
                evaluationStream::executeCapturedScopeAgain);
    }

    @Test
    void overlappingEvaluationsForSameAppUseIndependentScopes() {
        CapturingTokenStream firstStream = new CapturingTokenStream(
                CapturingTokenStream.Terminal.NONE, scopeManager);
        CapturingTokenStream secondStream = new CapturingTokenStream(
                CapturingTokenStream.Terminal.NONE, scopeManager);
        properties.setEnabled(true);
        properties.getHybrid().setEnabled(true);
        VueRagContext context = context("evaluation-skeleton");
        when(retrievalService.retrieveVueProject(RAW_QUERY)).thenReturn(context);
        when(promptAssembler.assembleVueProject(RAW_QUERY, context))
                .thenReturn("评测生成提示词");
        when(serviceFactory.getVueEvaluationCodeGeneratorService(APP_ID))
                .thenReturn(evaluationGeneratorService);
        when(evaluationGeneratorService.generate(APP_ID, "评测生成提示词"))
                .thenReturn(firstStream, secondStream);

        reactor.core.Disposable first = facade
                .generateVueProjectForEvaluation(RAW_QUERY, APP_ID).stream().subscribe();
        reactor.core.Disposable second = facade
                .generateVueProjectForEvaluation(RAW_QUERY, APP_ID).stream().subscribe();

        assertNotEquals(firstStream.observedScope.ownerToken(),
                secondStream.observedScope.ownerToken());
        first.dispose();
        assertThrows(FileToolExecutionScopeManager.ScopeViolationException.class,
                firstStream::executeCapturedScopeAgain);
        assertDoesNotThrow(secondStream::executeCapturedScopeAgain,
                "关闭一个评测不能撤销另一个同 appId 评测的 scope");
        second.dispose();
        assertThrows(FileToolExecutionScopeManager.ScopeViolationException.class,
                secondStream::executeCapturedScopeAgain);
        assertEquals(0, firstStream.staleActions.get());
        assertEquals(1, secondStream.staleActions.get(),
                "第二个 scope 关闭前只应执行一次显式验证动作");
    }

    @Test
    void sameEvaluationColdFluxCreatesIndependentResourcesForEverySubscription() {
        CapturingTokenStream firstStream = new CapturingTokenStream(
                CapturingTokenStream.Terminal.COMPLETE, scopeManager);
        CapturingTokenStream secondStream = new CapturingTokenStream(
                CapturingTokenStream.Terminal.COMPLETE, scopeManager);
        properties.setEnabled(true);
        properties.getHybrid().setEnabled(true);
        VueRagContext context = context("evaluation-skeleton");
        when(retrievalService.retrieveVueProject(RAW_QUERY)).thenReturn(context);
        when(promptAssembler.assembleVueProject(RAW_QUERY, context))
                .thenReturn("评测生成提示词");
        when(serviceFactory.getVueEvaluationCodeGeneratorService(APP_ID))
                .thenReturn(evaluationGeneratorService);
        when(evaluationGeneratorService.generate(APP_ID, "评测生成提示词"))
                .thenReturn(firstStream, secondStream);

        AiCodeGeneratorFacade.VueProjectGeneration generation =
                facade.generateVueProjectForEvaluation(RAW_QUERY, APP_ID);

        verify(evaluationGeneratorService, never()).generate(anyLong(), anyString());
        generation.stream().then().block();
        generation.stream().then().block();

        verify(evaluationGeneratorService, times(2))
                .generate(APP_ID, "评测生成提示词");
        assertNotSame(firstStream.guard, secondStream.guard);
        assertNotSame(firstStream.observedScope, secondStream.observedScope);
        assertNotSame(firstStream.observedBudgetSession,
                secondStream.observedBudgetSession);
        assertNotEquals(firstStream.observedScope.ownerToken(),
                secondStream.observedScope.ownerToken());
        assertThrows(FileToolExecutionScopeManager.ScopeViolationException.class,
                firstStream::executeCapturedScopeAgain);
        assertThrows(FileToolExecutionScopeManager.ScopeViolationException.class,
                secondStream::executeCapturedScopeAgain);
    }

    @Test
    void evaluationEntryRejectsDisabledHybridInsteadOfSilentlyUsingLegacyChain() {
        properties.setEnabled(true);
        properties.getHybrid().setEnabled(false);

        assertThrows(IllegalStateException.class,
                () -> facade.generateVueProjectForEvaluation(RAW_QUERY, APP_ID));

        verify(serviceFactory, never()).getAiCodeGeneratorService(any(Long.class), any());
    }

    @Test
    void evaluationEntryRejectsDisabledRagBeforeObtainingGeneratorService() {
        properties.setEnabled(false);
        properties.getHybrid().setEnabled(true);

        assertThrows(IllegalStateException.class,
                () -> facade.generateVueProjectForEvaluation(RAW_QUERY, APP_ID));

        verify(serviceFactory, never()).getAiCodeGeneratorService(any(Long.class), any());
        verifyNoInteractions(retrievalService, promptAssembler, imageCollectionService);
    }

    private VueRagContext context(String skeletonId) {
        TemplateDoc skeleton = new TemplateDoc();
        skeleton.setId(skeletonId);
        return new VueRagContext(skeleton, List.of(), "catalog-v7", false);
    }

    private SimpleGenerationTurnContext newSimpleTurnContext(String ownerToken) {
        var operation = new AppOperationLeaseManager().acquire(
                APP_ID, AppOperationLeaseManager.AppOperationType.GENERATE,
                ownerToken);
        return new SimpleGenerationTurnContext(operation);
    }

    private VueTurnContext newVueTurnContext(String turnId) {
        AppOperationLeaseManager operationManager =
                new AppOperationLeaseManager();
        VueBuildSessionManager sessionManager = new VueBuildSessionManager();
        var operation = operationManager.acquire(
                APP_ID, AppOperationLeaseManager.AppOperationType.GENERATE,
                turnId);
        var lease = sessionManager.open(operation, 9L, turnId);
        return new VueTurnContext(
                APP_ID, 9L, turnId, operation, lease,
                admissionPermit(),
                new FileToolBudgetGuard().newSession());
    }

    private void stubVueGenerator() {
        when(generatorService.generateVueProjectCodeStream(eq(APP_ID), any()))
                .thenReturn(tokenStream);
    }

    private void stubEvaluationGenerator(CapturingTokenStream evaluationStream) {
        properties.setEnabled(true);
        properties.getHybrid().setEnabled(true);
        VueRagContext context = context("evaluation-skeleton");
        when(retrievalService.retrieveVueProject(RAW_QUERY)).thenReturn(context);
        when(promptAssembler.assembleVueProject(RAW_QUERY, context))
                .thenReturn("评测生成提示词");
        when(serviceFactory.getVueEvaluationCodeGeneratorService(APP_ID))
                .thenReturn(evaluationGeneratorService);
        when(evaluationGeneratorService.generate(APP_ID, "评测生成提示词"))
                .thenReturn(evaluationStream);
    }

    private void stubOnlineControlledGenerator(OnlineControlledTokenStream stream) {
        properties.setEnabled(false);
        when(generatorService.generateVueProjectCodeStream(APP_ID, RAW_QUERY))
                .thenReturn(stream);
    }

    private void stubSimpleHtmlGenerator(TokenStream stream) {
        when(serviceFactory.getAiCodeGeneratorService(
                APP_ID, CodeGenTypeEnum.HTML)).thenReturn(generatorService);
        when(generatorService.generateHtmlCodeStream(RAW_QUERY))
                .thenReturn(stream);
        ReflectionTestUtils.setField(
                facade, "appDataLifecycleFence", new AppDataLifecycleFence());
    }

    private static final class OnlineControlledTokenStream implements TokenStream {

        private final ToolLoopTerminationProtocol.ControlledTerminationReason reason;
        private Consumer<dev.langchain4j.model.chat.response.ChatResponse> completeHandler;
        private Consumer<ToolLoopTerminationProtocol.ControlledTermination> handler;

        private OnlineControlledTokenStream(
                ToolLoopTerminationProtocol.ControlledTerminationReason reason) {
            this.reason = reason;
        }

        @Override public TokenStream onPartialResponse(Consumer<String> handler) { return this; }
        @Override public TokenStream onPartialToolExecutionRequest(
                BiConsumer<Integer, dev.langchain4j.agent.tool.ToolExecutionRequest> handler) {
            return this;
        }
        @Override public TokenStream onCompleteToolExecutionRequest(
                BiConsumer<Integer, dev.langchain4j.agent.tool.ToolExecutionRequest> handler) {
            return this;
        }
        @Override public TokenStream onRetrieved(
                Consumer<List<dev.langchain4j.rag.content.Content>> handler) { return this; }
        @Override public TokenStream onToolExecuted(
                Consumer<dev.langchain4j.service.tool.ToolExecution> handler) { return this; }
        @Override public TokenStream onCompleteResponse(
                Consumer<dev.langchain4j.model.chat.response.ChatResponse> handler) {
            this.completeHandler = handler;
            return this;
        }
        @Override public TokenStream onError(Consumer<Throwable> handler) { return this; }
        @Override public TokenStream onControlledTermination(
                Consumer<ToolLoopTerminationProtocol.ControlledTermination> handler) {
            this.handler = handler;
            return this;
        }
        @Override public TokenStream ignoreErrors() { return this; }

        @Override
        public void start() {
            // 受控终止不能复用普通完成回调；若底层恢复这种旧时序，
            // facade 会先完成 Flux，随后类型化终止被 Reactor 丢弃。
            assertTrue(completeHandler != null);
            handler.accept(new ToolLoopTerminationProtocol.ControlledTermination(
                    reason, controlledFinalResponse(reason)));
        }

        private static String controlledFinalResponse(
                ToolLoopTerminationProtocol.ControlledTerminationReason reason) {
            return switch (reason) {
                case BUILD_SUCCEEDED -> "项目已生成并构建成功。";
                case BUILD_FAILED ->
                        "抱歉，系统遇到了一些问题，请您稍后重试修复";
                case CANCELLED, PROTOCOL_ERROR, LOOP_LIMIT_EXCEEDED,
                        REPEATED_READ_LOOP, INCOMPLETE_TOOL_CHAIN,
                        RESOURCE_LIMIT_EXCEEDED,
                        EVALUATION_COMPLETED -> null;
            };
        }
    }

    /** 模拟供应商只上报完整无参数工具请求，不产生参数分片回调。 */
    private static final class CompleteOnlyToolRequestTokenStream
            implements TokenStream {

        private Consumer<GenerationStreamSignal> generationHandler;
        private Consumer<dev.langchain4j.model.chat.response.ChatResponse>
                completeResponseHandler;

        @Override public TokenStream onPartialResponse(
                Consumer<String> handler) { return this; }
        @Override public TokenStream onPartialToolExecutionRequest(
                BiConsumer<Integer,
                        dev.langchain4j.agent.tool.ToolExecutionRequest> handler) {
            return this;
        }
        @Override public TokenStream onCompleteToolExecutionRequest(
                BiConsumer<Integer,
                        dev.langchain4j.agent.tool.ToolExecutionRequest> handler) {
            return this;
        }
        @Override public TokenStream onRetrieved(
                Consumer<List<dev.langchain4j.rag.content.Content>> handler) {
            return this;
        }
        @Override public TokenStream onToolExecuted(
                Consumer<dev.langchain4j.service.tool.ToolExecution> handler) {
            return this;
        }
        @Override public TokenStream onGenerationStreamSignal(
                Consumer<GenerationStreamSignal> handler) {
            generationHandler = handler;
            return this;
        }
        @Override public TokenStream onCompleteResponse(
                Consumer<dev.langchain4j.model.chat.response.ChatResponse> handler) {
            completeResponseHandler = handler;
            return this;
        }
        @Override public TokenStream onError(Consumer<Throwable> handler) {
            return this;
        }
        @Override public TokenStream ignoreErrors() { return this; }

        @Override
        public void start() {
            var request = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                    .id("build-complete-only")
                    .name("buildProject")
                    .arguments("{}")
                    .build();
            generationHandler.accept(
                    new GenerationStreamSignal.CompleteToolRequest(
                            1L, 0, request));
            generationHandler.accept(new GenerationStreamSignal.ToolExecuted(
                    1L, dev.langchain4j.service.tool.ToolExecution.builder()
                            .request(request)
                            .result("{\"protocol\":\"vue-build/v1\","
                                    + "\"invocationStatus\":\"COMPLETED\","
                                    + "\"success\":true,\"attempt\":1}")
                            .build()));
            completeResponseHandler.accept(
                    dev.langchain4j.model.chat.response.ChatResponse.builder()
                            .aiMessage(dev.langchain4j.data.message.AiMessage.from("完成"))
                            .build());
        }
    }

    private static final class SimpleTokenStream implements TokenStream {

        private final List<String> chunks;
        private final Throwable failure;
        private Consumer<String> partialHandler;
        private Consumer<dev.langchain4j.model.chat.response.ChatResponse>
                completeHandler;
        private Consumer<Throwable> errorHandler;

        private SimpleTokenStream(List<String> chunks, Throwable failure) {
            this.chunks = chunks;
            this.failure = failure;
        }

        private static SimpleTokenStream completed(String... chunks) {
            return new SimpleTokenStream(List.of(chunks), null);
        }

        @Override
        public TokenStream onPartialResponse(Consumer<String> handler) {
            partialHandler = handler;
            return this;
        }

        @Override
        public TokenStream onPartialToolExecutionRequest(
                BiConsumer<Integer,
                        dev.langchain4j.agent.tool.ToolExecutionRequest> handler) {
            return this;
        }

        @Override
        public TokenStream onCompleteToolExecutionRequest(
                BiConsumer<Integer,
                        dev.langchain4j.agent.tool.ToolExecutionRequest> handler) {
            return this;
        }

        @Override
        public TokenStream onRetrieved(
                Consumer<List<dev.langchain4j.rag.content.Content>> handler) {
            return this;
        }

        @Override
        public TokenStream onToolExecuted(
                Consumer<dev.langchain4j.service.tool.ToolExecution> handler) {
            return this;
        }

        @Override
        public TokenStream onCompleteResponse(
                Consumer<dev.langchain4j.model.chat.response.ChatResponse> handler) {
            completeHandler = handler;
            return this;
        }

        @Override
        public TokenStream onError(Consumer<Throwable> handler) {
            errorHandler = handler;
            return this;
        }

        @Override
        public TokenStream ignoreErrors() {
            return this;
        }

        @Override
        public void start() {
            chunks.forEach(partialHandler);
            if (failure == null) {
                completeHandler.accept(null);
            } else {
                errorHandler.accept(failure);
            }
        }
    }

    private static final class ResourceLimitedTokenStream implements TokenStream {

        private final AtomicInteger resourceTerminations = new AtomicInteger();
        private final AtomicInteger cancellations = new AtomicInteger();
        private final String toolName;
        private final String arguments;
        private Consumer<GenerationStreamSignal> generationHandler;
        private Consumer<ToolLoopTerminationProtocol.ControlledTermination>
                controlledHandler;
        private Consumer<dev.langchain4j.model.chat.response.ChatResponse>
                completeHandler;

        private ResourceLimitedTokenStream() {
            this("writeFile", "{\"relativeFilePath\":\"src/App.vue\","
                    + "\"content\":\"A😀BCD\"}");
        }

        private ResourceLimitedTokenStream(String toolName, String arguments) {
            this.toolName = toolName;
            this.arguments = arguments;
        }

        @Override
        public TokenStream onPartialResponse(Consumer<String> handler) {
            return this;
        }

        @Override
        public TokenStream onPartialToolExecutionRequest(
                BiConsumer<Integer,
                        dev.langchain4j.agent.tool.ToolExecutionRequest> handler) {
            return this;
        }

        @Override
        public TokenStream onGenerationStreamSignal(
                Consumer<GenerationStreamSignal> handler) {
            generationHandler = handler;
            return this;
        }

        @Override
        public TokenStream onCompleteToolExecutionRequest(
                BiConsumer<Integer,
                        dev.langchain4j.agent.tool.ToolExecutionRequest> handler) {
            return this;
        }

        @Override
        public TokenStream onRetrieved(
                Consumer<List<dev.langchain4j.rag.content.Content>> handler) {
            return this;
        }

        @Override
        public TokenStream onToolExecuted(
                Consumer<dev.langchain4j.service.tool.ToolExecution> handler) {
            return this;
        }

        @Override
        public TokenStream onCompleteResponse(
                Consumer<dev.langchain4j.model.chat.response.ChatResponse> handler) {
            completeHandler = handler;
            return this;
        }

        @Override
        public TokenStream onError(Consumer<Throwable> handler) {
            return this;
        }

        @Override
        public TokenStream onControlledTermination(
                Consumer<ToolLoopTerminationProtocol.ControlledTermination> handler) {
            controlledHandler = handler;
            return this;
        }

        @Override
        public TokenStream requestControlledTermination(
                ToolLoopTerminationProtocol.ControlledTermination termination) {
            resourceTerminations.incrementAndGet();
            cancel();
            controlledHandler.accept(termination);
            return this;
        }

        @Override
        public void cancel() {
            cancellations.incrementAndGet();
        }

        @Override
        public TokenStream ignoreErrors() {
            return this;
        }

        @Override
        public void start() {
            generationHandler.accept(
                    new GenerationStreamSignal.PartialToolRequest(
                            1L, 0,
                            dev.langchain4j.agent.tool.ToolExecutionRequest
                                    .builder()
                                    .id("tool-large")
                                    .name(toolName)
                                    .arguments(arguments)
                                    .build()));
            if (resourceTerminations.get() == 0) {
                completeHandler.accept(null);
            }
        }
    }

    private static final class ExecutedResourceLimitTokenStream
            implements TokenStream {

        private static final String SECRET = "绝不能进入SSE的完整超限代码";
        private Consumer<GenerationStreamSignal> generationHandler;
        private Consumer<ToolLoopTerminationProtocol.ControlledTermination>
                controlledHandler;

        @Override public TokenStream onPartialResponse(Consumer<String> handler) { return this; }
        @Override public TokenStream onPartialToolExecutionRequest(
                BiConsumer<Integer, dev.langchain4j.agent.tool.ToolExecutionRequest> handler) {
            return this;
        }
        @Override public TokenStream onCompleteToolExecutionRequest(
                BiConsumer<Integer, dev.langchain4j.agent.tool.ToolExecutionRequest> handler) {
            return this;
        }
        @Override public TokenStream onRetrieved(
                Consumer<List<dev.langchain4j.rag.content.Content>> handler) { return this; }
        @Override public TokenStream onToolExecuted(
                Consumer<dev.langchain4j.service.tool.ToolExecution> handler) {
            return this;
        }
        @Override public TokenStream onGenerationStreamSignal(
                Consumer<GenerationStreamSignal> handler) {
            generationHandler = handler;
            return this;
        }
        @Override public TokenStream onCompleteResponse(
                Consumer<dev.langchain4j.model.chat.response.ChatResponse> handler) {
            return this;
        }
        @Override public TokenStream onError(Consumer<Throwable> handler) { return this; }
        @Override public TokenStream onControlledTermination(
                Consumer<ToolLoopTerminationProtocol.ControlledTermination> handler) {
            controlledHandler = handler;
            return this;
        }
        @Override public TokenStream ignoreErrors() { return this; }

        @Override
        public void start() {
            var request = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                    .id("executed-large")
                    .name("writeFile")
                    .arguments("{\"content\":\"" + SECRET + "\"}")
                    .build();
            String result = """
                    {"protocol":"file-tool/v1","operation":"writeFile",\
                    "status":"REJECTED","relativePath":null,"changed":false,\
                    "message":"工具内容超过本轮资源上限",\
                    "failureReason":"RESOURCE_LIMIT_EXCEEDED","content":null}
                    """;
            generationHandler.accept(new GenerationStreamSignal.ToolExecuted(
                    1L, dev.langchain4j.service.tool.ToolExecution.builder()
                            .request(request).result(result).build()));
        }

        @Override
        public TokenStream requestControlledTermination(
                ToolLoopTerminationProtocol.ControlledTermination termination) {
            controlledHandler.accept(termination);
            return this;
        }
    }

    private static final class ManualSimpleTokenStream implements TokenStream {

        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger cancellations = new AtomicInteger();
        private Consumer<String> partialHandler;
        private Consumer<dev.langchain4j.model.chat.response.ChatResponse>
                completeHandler;
        private Consumer<Throwable> errorHandler;
        private Consumer<ToolLoopTerminationProtocol.ControlledTermination>
                controlledHandler;

        @Override
        public void cancel() {
            cancellations.incrementAndGet();
            if (controlledHandler != null) {
                controlledHandler.accept(
                        new ToolLoopTerminationProtocol.ControlledTermination(
                                ToolLoopTerminationProtocol
                                        .ControlledTerminationReason.CANCELLED,
                                null));
            }
        }

        @Override
        public TokenStream onPartialResponse(Consumer<String> handler) {
            partialHandler = handler;
            return this;
        }

        @Override
        public TokenStream onPartialToolExecutionRequest(
                BiConsumer<Integer,
                        dev.langchain4j.agent.tool.ToolExecutionRequest> handler) {
            return this;
        }

        @Override
        public TokenStream onCompleteToolExecutionRequest(
                BiConsumer<Integer,
                        dev.langchain4j.agent.tool.ToolExecutionRequest> handler) {
            return this;
        }

        @Override
        public TokenStream onRetrieved(
                Consumer<List<dev.langchain4j.rag.content.Content>> handler) {
            return this;
        }

        @Override
        public TokenStream onToolExecuted(
                Consumer<dev.langchain4j.service.tool.ToolExecution> handler) {
            return this;
        }

        @Override
        public TokenStream onCompleteResponse(
                Consumer<dev.langchain4j.model.chat.response.ChatResponse> handler) {
            completeHandler = handler;
            return this;
        }

        @Override
        public TokenStream onError(Consumer<Throwable> handler) {
            errorHandler = handler;
            return this;
        }

        @Override
        public TokenStream onControlledTermination(
                Consumer<ToolLoopTerminationProtocol.ControlledTermination> handler) {
            controlledHandler = handler;
            return this;
        }

        @Override
        public TokenStream ignoreErrors() {
            return this;
        }

        @Override
        public void start() {
            starts.incrementAndGet();
        }

        private void emitPartial(String chunk) {
            partialHandler.accept(chunk);
        }

        private void complete() {
            completeHandler.accept(null);
        }

        @SuppressWarnings("unused")
        private void fail(Throwable failure) {
            errorHandler.accept(failure);
        }
    }

    private static final class CapturingTokenStream implements TokenStream {

        private final Terminal terminal;
        private final FileToolExecutionScopeManager scopeManager;
        private final AtomicInteger cancellations = new AtomicInteger();
        private final AtomicInteger rejectionsObservedDuringCancel = new AtomicInteger();
        private final AtomicInteger staleActions = new AtomicInteger();
        private final AtomicInteger gateInstallations = new AtomicInteger();
        private ToolExecutionGuard guard;
        private Consumer<dev.langchain4j.model.chat.response.ChatResponse> completeHandler;
        private Consumer<Throwable> errorHandler;
        private Consumer<ToolLoopTerminationProtocol.ControlledTermination>
                controlledTerminationHandler;
        private FileToolExecutionScopeManager.ScopeType observedScopeType;
        private FileToolExecutionScopeManager.FileToolScope observedScope;
        private FileToolBudgetGuard.Session observedBudgetSession;
        private ToolLoopTerminationProtocol.ControlledTermination observedTermination;

        private CapturingTokenStream(
                Terminal terminal, FileToolExecutionScopeManager scopeManager) {
            this.terminal = terminal;
            this.scopeManager = scopeManager;
        }

        @Override
        public void cancel() {
            cancellations.incrementAndGet();
            try {
                executeCapturedGuardAgain();
            } catch (FileToolExecutionScopeManager.ScopeViolationException exception) {
                rejectionsObservedDuringCancel.incrementAndGet();
            }
        }

        @Override
        public TokenStream toolExecutionGuard(ToolExecutionGuard guard) {
            this.guard = guard;
            return this;
        }

        @Override
        public TokenStream modelRequestGate(
                ModelRequestGate gate,
                ModelRequestGate.ContinuationGate continuationGate) {
            gateInstallations.incrementAndGet();
            return this;
        }

        @Override public TokenStream onPartialResponse(Consumer<String> handler) { return this; }
        @Override public TokenStream onPartialToolExecutionRequest(
                BiConsumer<Integer, dev.langchain4j.agent.tool.ToolExecutionRequest> handler) {
            return this;
        }
        @Override public TokenStream onCompleteToolExecutionRequest(
                BiConsumer<Integer, dev.langchain4j.agent.tool.ToolExecutionRequest> handler) {
            return this;
        }
        @Override public TokenStream onRetrieved(
                Consumer<List<dev.langchain4j.rag.content.Content>> handler) { return this; }
        @Override public TokenStream onToolExecuted(
                Consumer<dev.langchain4j.service.tool.ToolExecution> handler) { return this; }
        @Override public TokenStream onCompleteResponse(
                Consumer<dev.langchain4j.model.chat.response.ChatResponse> handler) {
            this.completeHandler = handler;
            return this;
        }
        @Override public TokenStream onError(Consumer<Throwable> handler) {
            this.errorHandler = handler;
            return this;
        }
        @Override public TokenStream onControlledTermination(
                Consumer<ToolLoopTerminationProtocol.ControlledTermination> handler) {
            this.controlledTerminationHandler = handler;
            return this;
        }
        @Override public TokenStream ignoreErrors() { return this; }

        @Override
        public void start() {
            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                String toolName = terminal == Terminal.CONTROLLED ? "exit" : "writeFile";
                ToolExecutionGuard.GuardedToolExecution execution = executor.submit(() ->
                        guard.execute(toolName, APP_ID, () -> {
                    observedScope = scopeManager.requireCurrent(APP_ID, toolName);
                    observedScopeType = observedScope.type();
                    observedBudgetSession = observedScope.budgetSession();
                    return terminal == Terminal.CONTROLLED
                            ? "{\"protocol\":\"file-tool/v1\",\"operation\":\"exit\","
                            + "\"status\":\"APPLIED\",\"relativePath\":null,"
                            + "\"changed\":false,\"message\":\"生成完毕\"}"
                            : "{}";
                })).get();
                if (terminal == Terminal.CONTROLLED) {
                    observedTermination = execution.controlledTermination();
                    controlledTerminationHandler.accept(observedTermination);
                } else if (terminal == Terminal.LOOP_LIMIT) {
                    observedTermination = new ToolLoopTerminationProtocol.ControlledTermination(
                            ToolLoopTerminationProtocol.ControlledTerminationReason
                                    .LOOP_LIMIT_EXCEEDED,
                            null);
                    controlledTerminationHandler.accept(observedTermination);
                }
            } catch (Exception exception) {
                throw new IllegalStateException("异步工具执行失败", exception);
            }
            if (terminal == Terminal.COMPLETE) {
                completeHandler.accept(dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(dev.langchain4j.data.message.AiMessage.from("完成"))
                        .build());
            } else if (terminal == Terminal.ERROR) {
                errorHandler.accept(new IllegalStateException("评测流失败"));
            }
        }

        private void executeCapturedGuardAgain() {
            guard.execute("writeFile", APP_ID, () -> {
                staleActions.incrementAndGet();
                return "{}";
            });
        }

        private void executeCapturedScopeAgain() {
            scopeManager.callInScope(observedScope, "writeFile", () -> {
                staleActions.incrementAndGet();
                scopeManager.requireCurrent(APP_ID, "writeFile");
                return "{}";
            });
        }

        private enum Terminal {
            COMPLETE,
            ERROR,
            CONTROLLED,
            LOOP_LIMIT,
            NONE
        }
    }

    private static final class CapturingRecoveryTokenStream
            implements TokenStream {

        private Consumer<ToolProtocolRecoveryPolicy.Phase> recoveryListener;
        private Consumer<dev.langchain4j.model.chat.response.ChatResponse>
                completeHandler;
        private Runnable beforeStart = () -> { };
        private final AtomicInteger publishedPhases = new AtomicInteger();

        @Override
        public TokenStream toolProtocolRecoveryPolicy(
                ToolProtocolRecoveryPolicy policy) {
            recoveryListener = phase -> {
                publishedPhases.incrementAndGet();
                try {
                    var method = ToolProtocolRecoveryPolicy.class
                            .getDeclaredMethod("publish",
                                    ToolProtocolRecoveryPolicy.Phase.class);
                    method.setAccessible(true);
                    method.invoke(policy, phase);
                } catch (ReflectiveOperationException exception) {
                    throw new IllegalStateException(exception);
                }
            };
            return this;
        }

        private void publish(ToolProtocolRecoveryPolicy.Phase phase) {
            recoveryListener.accept(phase);
        }

        @Override public TokenStream onPartialResponse(Consumer<String> handler) { return this; }
        @Override public TokenStream onPartialToolExecutionRequest(
                BiConsumer<Integer, dev.langchain4j.agent.tool.ToolExecutionRequest> handler) {
            return this;
        }
        @Override public TokenStream onCompleteToolExecutionRequest(
                BiConsumer<Integer, dev.langchain4j.agent.tool.ToolExecutionRequest> handler) {
            return this;
        }
        @Override public TokenStream onRetrieved(
                Consumer<List<dev.langchain4j.rag.content.Content>> handler) { return this; }
        @Override public TokenStream onToolExecuted(
                Consumer<dev.langchain4j.service.tool.ToolExecution> handler) { return this; }
        @Override public TokenStream onCompleteResponse(
                Consumer<dev.langchain4j.model.chat.response.ChatResponse> handler) {
            completeHandler = handler;
            return this;
        }
        @Override public TokenStream onError(Consumer<Throwable> handler) { return this; }
        @Override public TokenStream ignoreErrors() { return this; }

        @Override
        public void start() {
            beforeStart.run();
            completeHandler.accept(dev.langchain4j.model.chat.response
                    .ChatResponse.builder()
                    .aiMessage(dev.langchain4j.data.message.AiMessage.from(""))
                    .build());
        }
    }

    private static final class UnifiedGenerationTokenStream
            implements TokenStream {

        private Consumer<GenerationStreamSignal> generationHandler;
        private Consumer<dev.langchain4j.model.chat.response.ChatResponse>
                completeHandler;
        private final AtomicInteger legacyBusinessRegistrations =
                new AtomicInteger();

        @Override
        public TokenStream internalOutputRecoveryPolicy(
                InternalOutputRecoveryPolicy policy) {
            assertEquals(InternalOutputRecoveryPolicy.Mode.RECOVER_ONCE,
                    policy.mode());
            return this;
        }

        @Override
        public TokenStream onGenerationStreamSignal(
                Consumer<GenerationStreamSignal> handler) {
            generationHandler = handler;
            return this;
        }

        @Override
        public TokenStream onPartialResponse(Consumer<String> handler) {
            legacyBusinessRegistrations.incrementAndGet();
            return this;
        }

        @Override
        public TokenStream onPartialToolExecutionRequest(
                BiConsumer<Integer,
                        dev.langchain4j.agent.tool.ToolExecutionRequest>
                        handler) {
            legacyBusinessRegistrations.incrementAndGet();
            return this;
        }

        @Override
        public TokenStream onCompleteToolExecutionRequest(
                BiConsumer<Integer,
                        dev.langchain4j.agent.tool.ToolExecutionRequest>
                        handler) {
            legacyBusinessRegistrations.incrementAndGet();
            return this;
        }

        @Override
        public TokenStream onToolExecuted(
                Consumer<dev.langchain4j.service.tool.ToolExecution> handler) {
            legacyBusinessRegistrations.incrementAndGet();
            return this;
        }

        @Override
        public TokenStream onRetrieved(
                Consumer<List<dev.langchain4j.rag.content.Content>> handler) {
            return this;
        }

        @Override
        public TokenStream onCompleteResponse(
                Consumer<dev.langchain4j.model.chat.response.ChatResponse>
                        handler) {
            completeHandler = handler;
            return this;
        }

        @Override
        public TokenStream onError(Consumer<Throwable> handler) {
            return this;
        }

        @Override
        public TokenStream ignoreErrors() {
            return this;
        }

        @Override
        public void start() {
            var request = dev.langchain4j.agent.tool.ToolExecutionRequest
                    .builder().id("tool-1").name("writeFile")
                    .arguments("{\"relativeFilePath\":\"src/App.vue\"}")
                    .build();
            generationHandler.accept(
                    new GenerationStreamSignal.AiText(1L, "正文"));
            generationHandler.accept(
                    new GenerationStreamSignal.CompleteToolRequest(
                            1L, 0, request));
            generationHandler.accept(
                    new GenerationStreamSignal.ToolExecuted(
                            1L, dev.langchain4j.service.tool.ToolExecution
                            .builder().request(request).result("{}").build()));
            generationHandler.accept(
                    new GenerationStreamSignal.Rollback(
                            1L, 2, Set.of()));
            generationHandler.accept(
                    new GenerationStreamSignal.Recovery(
                            GenerationStreamSignal.Recovery.Phase.STARTED,
                            1L, 2L, null));
            completeHandler.accept(dev.langchain4j.model.chat.response
                    .ChatResponse.builder()
                    .aiMessage(dev.langchain4j.data.message.AiMessage.from(""))
                    .build());
        }
    }

    private static final class RollbackPartialToolTokenStream
            implements TokenStream {

        private Consumer<GenerationStreamSignal> generationHandler;
        private Consumer<dev.langchain4j.model.chat.response.ChatResponse>
                completeHandler;

        @Override
        public TokenStream onGenerationStreamSignal(
                Consumer<GenerationStreamSignal> handler) {
            generationHandler = handler;
            return this;
        }

        @Override
        public TokenStream onPartialResponse(Consumer<String> handler) {
            return this;
        }

        @Override
        public TokenStream onPartialToolExecutionRequest(
                BiConsumer<Integer,
                        dev.langchain4j.agent.tool.ToolExecutionRequest>
                        handler) {
            return this;
        }

        @Override
        public TokenStream onCompleteToolExecutionRequest(
                BiConsumer<Integer,
                        dev.langchain4j.agent.tool.ToolExecutionRequest>
                        handler) {
            return this;
        }

        @Override
        public TokenStream onRetrieved(
                Consumer<List<dev.langchain4j.rag.content.Content>> handler) {
            return this;
        }

        @Override
        public TokenStream onToolExecuted(
                Consumer<dev.langchain4j.service.tool.ToolExecution> handler) {
            return this;
        }

        @Override
        public TokenStream onCompleteResponse(
                Consumer<dev.langchain4j.model.chat.response.ChatResponse>
                        handler) {
            completeHandler = handler;
            return this;
        }

        @Override
        public TokenStream onError(Consumer<Throwable> handler) {
            return this;
        }

        @Override
        public TokenStream ignoreErrors() {
            return this;
        }

        @Override
        public void start() {
            var request = dev.langchain4j.agent.tool.ToolExecutionRequest
                    .builder()
                    .id("provisional-1")
                    .name("writeFile")
                    .arguments("{\"relativeFilePath\":\"src/App.vue")
                    .build();
            generationHandler.accept(
                    new GenerationStreamSignal.PartialToolRequest(
                            1L, 0, request));
            generationHandler.accept(new GenerationStreamSignal.Rollback(
                    1L, 0, Set.of("provisional-1")));
            completeHandler.accept(dev.langchain4j.model.chat.response
                    .ChatResponse.builder()
                    .aiMessage(dev.langchain4j.data.message.AiMessage.from(""))
                    .build());
        }
    }

    private RetrievedSnippet snippet(String id) {
        TemplateDoc document = new TemplateDoc();
        document.setId(id);
        document.setTitle(id);
        document.setDescription("示例模板");
        TemplateDoc.TemplateFile file = new TemplateDoc.TemplateFile();
        file.setPath("index.html");
        file.setContent("示例代码");
        document.setFiles(List.of(file));
        return RetrievedSnippet.builder().id(id).title(id).document(document).score(0.9).build();
    }

    private VueTurnAdmissionController.AdmissionPermit admissionPermit() {
        return new VueTurnAdmissionController(
                new VueBuildRepairMetricsCollector(new SimpleMeterRegistry()))
                .tryAcquire().orElseThrow();
    }
}
