package com.lyw.appgeneration.rag.vue;

import com.lyw.appgeneration.service.rag.RagRetrievalService;
import com.lyw.appgeneration.service.rag.model.TemplateDoc;
import com.lyw.appgeneration.service.rag.model.VueRagContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 对同一批标注依次运行真实 Hybrid 与 Dense-only 入口。
 */
public final class VueRetrievalEvaluator {

    private final RagRetrievalService retrievalService;

    public VueRetrievalEvaluator(RagRetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    public VueRetrievalEvaluationReport evaluate(List<VueEvalCase> cases) {
        List<VueRetrievalObservation> hybrid = new ArrayList<>();
        List<VueRetrievalObservation> dense = new ArrayList<>();
        for (VueEvalCase evalCase : cases) {
            hybrid.add(observe(evalCase, true));
            dense.add(observe(evalCase, false));
        }
        VueRetrievalComparison comparison = VueRetrievalComparison.compare(
                VueRetrievalMetrics.calculate(hybrid),
                VueRetrievalMetrics.calculate(dense));
        return VueRetrievalEvaluationReport.executed(comparison, hybrid, dense);
    }

    private VueRetrievalObservation observe(VueEvalCase evalCase, boolean hybrid) {
        try {
            VueRagContext context = hybrid
                    ? retrievalService.retrieveVueProject(evalCase.query())
                    : retrievalService.retrieveVueProjectDenseOnlyForEvaluation(evalCase.query());
            String skeletonId = context == null || context.skeleton() == null
                    ? null
                    : context.skeleton().getId();
            List<String> featureIds = context == null
                    ? List.of()
                    : context.features().stream().map(TemplateDoc::getId).toList();
            return new VueRetrievalObservation(evalCase, skeletonId, featureIds, null);
        } catch (RuntimeException exception) {
            return new VueRetrievalObservation(
                    evalCase, null, List.of(), safeError(exception));
        }
    }

    private String safeError(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : exception.getClass().getSimpleName() + ": " + message;
    }
}
