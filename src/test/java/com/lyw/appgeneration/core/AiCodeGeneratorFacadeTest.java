package com.lyw.appgeneration.core;

import com.lyw.appgeneration.ai.AiCodeGeneratorService;
import com.lyw.appgeneration.ai.AiGeneratorServiceFactory;
import com.lyw.appgeneration.ai.VueEvaluationCodeGeneratorService;
import com.lyw.appgeneration.ai.image.ImageCollectionService;
import com.lyw.appgeneration.ai.tools.FileToolExecutionScopeManager;
import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.service.rag.RagPromptAssembler;
import com.lyw.appgeneration.service.rag.RagRetrievalService;
import com.lyw.appgeneration.service.rag.model.RetrievedSnippet;
import com.lyw.appgeneration.service.rag.model.TemplateDoc;
import com.lyw.appgeneration.service.rag.model.VueRagContext;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.ToolExecutionGuard;
import dev.langchain4j.service.ToolLoopTerminationProtocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
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

    private RagProperties properties;
    private AiCodeGeneratorFacade facade;
    private FileToolExecutionScopeManager scopeManager;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        scopeManager = new FileToolExecutionScopeManager();
        facade = new AiCodeGeneratorFacade();
        ReflectionTestUtils.setField(facade, "aiGeneratorServiceFactory", serviceFactory);
        ReflectionTestUtils.setField(facade, "imageCollectionService", imageCollectionService);
        ReflectionTestUtils.setField(facade, "ragRetrievalService", retrievalService);
        ReflectionTestUtils.setField(facade, "ragPromptAssembler", promptAssembler);
        ReflectionTestUtils.setField(facade, "ragProperties", properties);
        ReflectionTestUtils.setField(facade, "fileToolExecutionScopeManager", scopeManager);
    }

    @Test
    void hybridFirstVueRetrievesRawQueryThenEnhancesGenerationRequest() {
        stubVueGenerator();
        properties.getHybrid().setEnabled(true);
        VueRagContext context = context("vue-skeleton");
        when(retrievalService.retrieveVueProject(RAW_QUERY)).thenReturn(context);
        when(imageCollectionService.enhancePrompt(RAW_QUERY)).thenReturn(ENHANCED_QUERY);
        when(promptAssembler.assembleVueProject(ENHANCED_QUERY, context)).thenReturn(AUGMENTED_QUERY);

        facade.generateAndSaveCodeStream(RAW_QUERY, CodeGenTypeEnum.VUE_PROJECT, APP_ID, true);

        InOrder order = inOrder(retrievalService, imageCollectionService, promptAssembler, generatorService);
        order.verify(retrievalService).retrieveVueProject(RAW_QUERY);
        order.verify(imageCollectionService).enhancePrompt(RAW_QUERY);
        order.verify(promptAssembler).assembleVueProject(ENHANCED_QUERY, context);
        order.verify(generatorService).generateVueProjectCodeStream(APP_ID, AUGMENTED_QUERY);
        verify(retrievalService, never()).retrieve(any(), any());
        verify(promptAssembler, never()).assemble(any(), anyList());
    }

    @Test
    void hybridNonFirstVueSkipsImagesAndStillRetrievesRawQuery() {
        stubVueGenerator();
        properties.getHybrid().setEnabled(true);
        VueRagContext context = context("vue-skeleton");
        when(retrievalService.retrieveVueProject(RAW_QUERY)).thenReturn(context);
        when(promptAssembler.assembleVueProject(RAW_QUERY, context)).thenReturn("专用拼装");

        facade.generateAndSaveCodeStream(RAW_QUERY, CodeGenTypeEnum.VUE_PROJECT, APP_ID, false);

        verify(retrievalService).retrieveVueProject(RAW_QUERY);
        verify(imageCollectionService, never()).enhancePrompt(any());
        verify(promptAssembler).assembleVueProject(RAW_QUERY, context);
        verify(generatorService).generateVueProjectCodeStream(APP_ID, "专用拼装");
    }

    @Test
    void disabledHybridUsesNewDenseOnlyWithRawQueryThenEnhancesGenerationRequest() {
        stubVueGenerator();
        properties.setEnabled(true);
        properties.getHybrid().setEnabled(false);
        VueRagContext context = context("dense-skeleton");
        when(retrievalService.retrieveVueProjectDenseOnly(RAW_QUERY)).thenReturn(context);
        when(imageCollectionService.enhancePrompt(RAW_QUERY)).thenReturn(ENHANCED_QUERY);
        when(promptAssembler.assembleVueProject(ENHANCED_QUERY, context)).thenReturn(AUGMENTED_QUERY);

        facade.generateAndSaveCodeStream(RAW_QUERY, CodeGenTypeEnum.VUE_PROJECT, APP_ID, true);

        InOrder order = inOrder(retrievalService, imageCollectionService, promptAssembler, generatorService);
        order.verify(retrievalService).retrieveVueProjectDenseOnly(RAW_QUERY);
        order.verify(imageCollectionService).enhancePrompt(RAW_QUERY);
        order.verify(promptAssembler).assembleVueProject(ENHANCED_QUERY, context);
        order.verify(generatorService).generateVueProjectCodeStream(APP_ID, AUGMENTED_QUERY);
        verify(retrievalService, never()).retrieve(any(), any());
        verify(retrievalService, never()).retrieveVueProject(any());
        verify(promptAssembler, never()).assemble(any(), anyList());
    }

    @Test
    void disabledRagSkipsEveryVueRagStageEvenWhenHybridIsEnabled() {
        stubVueGenerator();
        properties.setEnabled(false);
        properties.getHybrid().setEnabled(true);
        when(imageCollectionService.enhancePrompt(RAW_QUERY)).thenReturn(ENHANCED_QUERY);

        facade.generateAndSaveCodeStream(RAW_QUERY, CodeGenTypeEnum.VUE_PROJECT, APP_ID, true);

        verifyNoInteractions(retrievalService, promptAssembler);
        verify(imageCollectionService).enhancePrompt(RAW_QUERY);
        verify(generatorService).generateVueProjectCodeStream(APP_ID, ENHANCED_QUERY);
    }

    @Test
    void hybridRetrievalFailureStillEnhancesAndGeneratesWithEmptyContext() {
        stubVueGenerator();
        properties.getHybrid().setEnabled(true);
        when(retrievalService.retrieveVueProject(RAW_QUERY))
                .thenThrow(new IllegalStateException("检索依赖失败"));
        when(imageCollectionService.enhancePrompt(RAW_QUERY)).thenReturn(ENHANCED_QUERY);
        when(promptAssembler.assembleVueProject(ENHANCED_QUERY, VueRagContext.unavailable()))
                .thenReturn("无 RAG 拼装");

        assertDoesNotThrow(() -> facade.generateAndSaveCodeStream(
                RAW_QUERY, CodeGenTypeEnum.VUE_PROJECT, APP_ID, true));

        verify(imageCollectionService).enhancePrompt(RAW_QUERY);
        verify(promptAssembler).assembleVueProject(ENHANCED_QUERY, VueRagContext.unavailable());
        verify(generatorService).generateVueProjectCodeStream(APP_ID, "无 RAG 拼装");
    }

    @Test
    void imageServiceFallbackKeepsRawGenerationRequest() {
        stubVueGenerator();
        properties.getHybrid().setEnabled(true);
        VueRagContext context = context("vue-skeleton");
        when(retrievalService.retrieveVueProject(RAW_QUERY)).thenReturn(context);
        when(imageCollectionService.enhancePrompt(RAW_QUERY)).thenReturn(RAW_QUERY);
        when(promptAssembler.assembleVueProject(RAW_QUERY, context)).thenReturn("原消息拼装");

        facade.generateAndSaveCodeStream(RAW_QUERY, CodeGenTypeEnum.VUE_PROJECT, APP_ID, true);

        verify(promptAssembler).assembleVueProject(RAW_QUERY, context);
        verify(generatorService).generateVueProjectCodeStream(APP_ID, "原消息拼装");
    }

    @Test
    void htmlKeepsLegacyOrderWithoutImageEnhancement() {
        when(serviceFactory.getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.HTML))
                .thenReturn(generatorService);
        List<RetrievedSnippet> snippets = List.of(snippet("html"));
        when(retrievalService.retrieve(RAW_QUERY, CodeGenTypeEnum.HTML)).thenReturn(snippets);
        when(promptAssembler.assemble(RAW_QUERY, snippets)).thenReturn("HTML 拼装");
        when(generatorService.generateHtmlCodeStream("HTML 拼装")).thenReturn(Flux.empty());

        facade.generateAndSaveCodeStream(RAW_QUERY, CodeGenTypeEnum.HTML, APP_ID, true);

        InOrder order = inOrder(retrievalService, promptAssembler, generatorService);
        order.verify(retrievalService).retrieve(RAW_QUERY, CodeGenTypeEnum.HTML);
        order.verify(promptAssembler).assemble(RAW_QUERY, snippets);
        order.verify(generatorService).generateHtmlCodeStream("HTML 拼装");
        verify(imageCollectionService, never()).enhancePrompt(any());
        verify(retrievalService, never()).retrieveVueProject(any());
    }

    @Test
    void multiFileKeepsImageThenLegacyRetrievalOrder() {
        when(serviceFactory.getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.MULTI_FILE))
                .thenReturn(generatorService);
        List<RetrievedSnippet> snippets = List.of(snippet("multi"));
        when(imageCollectionService.enhancePrompt(RAW_QUERY)).thenReturn(ENHANCED_QUERY);
        when(retrievalService.retrieve(ENHANCED_QUERY, CodeGenTypeEnum.MULTI_FILE))
                .thenReturn(snippets);
        when(promptAssembler.assemble(ENHANCED_QUERY, snippets)).thenReturn("多文件拼装");
        when(generatorService.generateMultiFileCodeStream("多文件拼装")).thenReturn(Flux.empty());

        facade.generateAndSaveCodeStream(RAW_QUERY, CodeGenTypeEnum.MULTI_FILE, APP_ID, true);

        InOrder order = inOrder(imageCollectionService, retrievalService, promptAssembler, generatorService);
        order.verify(imageCollectionService).enhancePrompt(RAW_QUERY);
        order.verify(retrievalService).retrieve(ENHANCED_QUERY, CodeGenTypeEnum.MULTI_FILE);
        order.verify(promptAssembler).assemble(ENHANCED_QUERY, snippets);
        order.verify(generatorService).generateMultiFileCodeStream("多文件拼装");
        verify(retrievalService, never()).retrieveVueProject(any());
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
        verify(evaluationGeneratorService).generate(APP_ID, "评测生成提示词");
        verify(serviceFactory, never()).getAiCodeGeneratorService(
                APP_ID, CodeGenTypeEnum.VUE_PROJECT);
        generation.stream().then().block();
        assertEquals(FileToolExecutionScopeManager.ScopeType.EVALUATION,
                evaluationStream.observedScopeType);
        assertThrows(FileToolExecutionScopeManager.ScopeViolationException.class,
                evaluationStream::executeCapturedGuardAgain,
                "AI 流完成后评测 scope 必须失效");
        assertThrows(FileToolExecutionScopeManager.ScopeViolationException.class,
                evaluationStream::executeCapturedScopeAgain,
                "AI 流完成后被捕获的 scope 也必须失效");
        assertEquals(0, evaluationStream.staleActions.get());
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

    private void stubVueGenerator() {
        when(serviceFactory.getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.VUE_PROJECT))
                .thenReturn(generatorService);
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

    private static final class CapturingTokenStream implements TokenStream {

        private final Terminal terminal;
        private final FileToolExecutionScopeManager scopeManager;
        private final AtomicInteger cancellations = new AtomicInteger();
        private final AtomicInteger rejectionsObservedDuringCancel = new AtomicInteger();
        private final AtomicInteger staleActions = new AtomicInteger();
        private ToolExecutionGuard guard;
        private Consumer<dev.langchain4j.model.chat.response.ChatResponse> completeHandler;
        private Consumer<Throwable> errorHandler;
        private Consumer<ToolLoopTerminationProtocol.ControlledTermination>
                controlledTerminationHandler;
        private FileToolExecutionScopeManager.ScopeType observedScopeType;
        private FileToolExecutionScopeManager.FileToolScope observedScope;
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
            scopeManager.callInScope(observedScope, () -> {
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

    private RetrievedSnippet snippet(String id) {
        return RetrievedSnippet.builder().id(id).title(id).code("示例代码").score(0.9).build();
    }
}
