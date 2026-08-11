package com.lyw.appgeneration.rag.vue;

import com.lyw.appgeneration.rag.ingest.VueIngestionVerification;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 在真实检索评测前执行摄取物理核验，未通过时绝不创建模型或检索服务。
 */
final class VueRetrievalQualityGateRunner {

    VueRetrievalEvaluationReport evaluateWhenIngested(
            Supplier<VueIngestionVerification> verificationSupplier,
            Supplier<VueRetrievalEvaluationReport> evaluationSupplier) {
        VueIngestionVerification verification;
        try {
            verification = verificationSupplier.get();
        } catch (RuntimeException exception) {
            return VueRetrievalEvaluationReport.notExecuted(
                    List.of("摄取前置核验失败: " + exception.getClass().getSimpleName()));
        }
        if (verification == null) {
            return VueRetrievalEvaluationReport.notExecuted(List.of("摄取前置核验结果为空"));
        }
        if (!verification.passed()) {
            List<String> reasons = new ArrayList<>();
            reasons.add("摄取前置条件不满足");
            reasons.addAll(verification.issues());
            return VueRetrievalEvaluationReport.notExecuted(reasons);
        }

        try {
            VueRetrievalEvaluationReport report = evaluationSupplier.get();
            return report == null
                    ? VueRetrievalEvaluationReport.notExecuted(List.of("检索评测未返回报告"))
                    : report;
        } catch (RuntimeException exception) {
            return VueRetrievalEvaluationReport.notExecuted(
                    List.of("检索评测执行失败: " + exception.getClass().getSimpleName()));
        }
    }
}
