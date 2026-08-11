package com.lyw.appgeneration.rag.vue;

import com.lyw.appgeneration.service.rag.RagRetrievalService;
import com.lyw.appgeneration.service.rag.model.TemplateDoc;
import com.lyw.appgeneration.service.rag.model.VueRagContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VueRetrievalEvaluatorTest {

    @Test
    void evaluatesHybridAndDenseOnlyThroughExplicitRealServiceEntries() {
        RagRetrievalService retrievalService = mock(RagRetrievalService.class);
        TemplateDoc skeleton = document("s1");
        TemplateDoc feature = document("f1");
        when(retrievalService.retrieveVueProject("真实需求"))
                .thenReturn(new VueRagContext(skeleton, List.of(feature), "catalog", false));
        when(retrievalService.retrieveVueProjectDenseOnlyForEvaluation("真实需求"))
                .thenReturn(new VueRagContext(skeleton, List.of(), "catalog", false));
        VueEvalCase evalCase = new VueEvalCase(
                "q1", "真实需求", "同义", List.of("s1"), List.of("f1"));

        VueRetrievalEvaluationReport report = new VueRetrievalEvaluator(retrievalService)
                .evaluate(List.of(evalCase));

        assertFalse(report.passed(), "单样本只验证计算逻辑，不能冒充 30 条真实门禁");
        assertTrue(report.comparison().passed());
        assertEquals(1.0, report.comparison().hybrid().skeletonHitAt1());
        assertEquals(1.0, report.comparison().hybrid().featureRecallAt4());
        assertEquals(0.0, report.comparison().denseOnly().featureRecallAt4());
        verify(retrievalService).retrieveVueProject("真实需求");
        verify(retrievalService).retrieveVueProjectDenseOnlyForEvaluation("真实需求");
    }

    @Test
    void 将Hybrid退化和Dense异常写入结构化观察结果() {
        RagRetrievalService retrievalService = mock(RagRetrievalService.class);
        TemplateDoc skeleton = document("s1");
        when(retrievalService.retrieveVueProject("真实需求"))
                .thenReturn(new VueRagContext(skeleton, List.of(), "catalog", true));
        when(retrievalService.retrieveVueProjectDenseOnlyForEvaluation("真实需求"))
                .thenThrow(new IllegalStateException("Dense 异常"));
        List<VueEvalCase> cases = java.util.stream.IntStream.range(0, 30)
                .mapToObj(index -> new VueEvalCase(
                        "q" + index, "真实需求", "同义", List.of("s1"), List.of()))
                .toList();

        VueRetrievalEvaluationReport report = new VueRetrievalEvaluator(retrievalService)
                .evaluate(cases);

        assertFalse(report.passed());
        assertTrue(report.renderMarkdown().contains("| true |"));
        assertTrue(report.renderMarkdown().contains("IllegalStateException"));
    }

    private TemplateDoc document(String id) {
        TemplateDoc document = new TemplateDoc();
        document.setId(id);
        return document;
    }
}
