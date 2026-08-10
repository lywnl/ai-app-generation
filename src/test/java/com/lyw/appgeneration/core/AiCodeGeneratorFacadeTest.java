package com.lyw.appgeneration.core;

import com.lyw.appgeneration.ai.AiCodeGeneratorService;
import com.lyw.appgeneration.ai.AiGeneratorServiceFactory;
import com.lyw.appgeneration.ai.image.ImageCollectionService;
import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.service.rag.RagPromptAssembler;
import com.lyw.appgeneration.service.rag.RagRetrievalService;
import com.lyw.appgeneration.service.rag.model.RetrievedSnippet;
import com.lyw.appgeneration.service.rag.model.TemplateDoc;
import com.lyw.appgeneration.service.rag.model.VueRagContext;
import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    private ImageCollectionService imageCollectionService;

    @Mock
    private RagRetrievalService retrievalService;

    @Mock
    private RagPromptAssembler promptAssembler;

    @Mock
    private TokenStream tokenStream;

    private RagProperties properties;
    private AiCodeGeneratorFacade facade;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        facade = new AiCodeGeneratorFacade();
        ReflectionTestUtils.setField(facade, "aiGeneratorServiceFactory", serviceFactory);
        ReflectionTestUtils.setField(facade, "imageCollectionService", imageCollectionService);
        ReflectionTestUtils.setField(facade, "ragRetrievalService", retrievalService);
        ReflectionTestUtils.setField(facade, "ragPromptAssembler", promptAssembler);
        ReflectionTestUtils.setField(facade, "ragProperties", properties);
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
        stubVueGenerator();
        properties.getHybrid().setEnabled(true);
        VueRagContext context = context("selected-skeleton");
        when(retrievalService.retrieveVueProject(RAW_QUERY)).thenReturn(context);
        when(promptAssembler.assembleVueProject(RAW_QUERY, context)).thenReturn("评测生成提示词");

        AiCodeGeneratorFacade.VueProjectGeneration generation =
                facade.generateVueProjectForEvaluation(RAW_QUERY, APP_ID);

        assertEquals(context, generation.context());
        verify(imageCollectionService, never()).enhancePrompt(any());
        verify(generatorService).generateVueProjectCodeStream(APP_ID, "评测生成提示词");
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

    private RetrievedSnippet snippet(String id) {
        return RetrievedSnippet.builder().id(id).title(id).code("示例代码").score(0.9).build();
    }
}
