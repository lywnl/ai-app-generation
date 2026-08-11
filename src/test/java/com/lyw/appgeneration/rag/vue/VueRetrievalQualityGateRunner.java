package com.lyw.appgeneration.rag.vue;

import com.lyw.appgeneration.rag.ingest.VueIngestionVerification;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 在真实检索评测前执行摄取物理核验，未通过时绝不创建模型或检索服务。
 */
final class VueRetrievalQualityGateRunner {

    VueRetrievalEvaluationReport evaluateWhenIngested(
            Supplier<VueIngestionVerification> verificationSupplier,
            Supplier<VueRetrievalEvaluationReport> evaluationSupplier) {
        VueIngestionVerification verification = Objects.requireNonNull(
                verificationSupplier.get(), "摄取前置核验结果不能为空");
        if (!verification.passed()) {
            List<String> reasons = new ArrayList<>();
            reasons.add("摄取前置条件不满足");
            reasons.addAll(verification.issues());
            return VueRetrievalEvaluationReport.notExecuted(reasons);
        }

        VueRetrievalEvaluationReport report = Objects.requireNonNull(
                evaluationSupplier.get(), "检索评测报告不能为空");
        if (!report.executed()) {
            throw new IllegalStateException("摄取核验通过后，检索评测报告必须为已执行状态");
        }
        return report;
    }
}
