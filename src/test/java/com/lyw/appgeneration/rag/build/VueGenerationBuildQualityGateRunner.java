package com.lyw.appgeneration.rag.build;

import com.lyw.appgeneration.rag.ingest.VueIngestionVerification;
import com.lyw.appgeneration.rag.vue.VueRetrievalEvaluationReport;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 按物理核验、真实检索、生成构建的固定顺序执行同轮质量门禁。
 */
final class VueGenerationBuildQualityGateRunner {

    VueGenerationBuildReport evaluate(
            Supplier<VueIngestionVerification> verificationSupplier,
            Supplier<VueRetrievalEvaluationReport> retrievalSupplier,
            Supplier<VueGenerationBuildReport> generationSupplier) {
        VueIngestionVerification verification = Objects.requireNonNull(
                verificationSupplier.get(), "摄取前置核验结果不能为空");
        if (!verification.passed()) {
            List<String> reasons = new ArrayList<>();
            reasons.add("摄取物理核验未通过");
            reasons.addAll(verification.issues());
            return VueGenerationBuildReport.notExecuted(reasons);
        }

        VueRetrievalEvaluationReport retrieval = Objects.requireNonNull(
                retrievalSupplier.get(), "真实检索报告不能为空");
        if (!retrieval.passed()) {
            String reason = retrieval.executed()
                    ? "同轮真实检索未达到质量门槛"
                    : "同轮真实检索未执行";
            return VueGenerationBuildReport.notExecuted(List.of(reason));
        }

        return Objects.requireNonNull(
                generationSupplier.get(), "生成构建报告不能为空");
    }
}
