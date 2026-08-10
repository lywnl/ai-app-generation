package com.lyw.appgeneration.rag.vue;

import com.lyw.appgeneration.rag.eval.EvaluationReportSanitizer;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Vue 双层检索真实评测报告。
 */
public final class VueRetrievalEvaluationReport {

    private final boolean executed;
    private final List<String> notExecutedReasons;
    private final VueRetrievalComparison comparison;
    private final List<VueRetrievalObservation> hybridRows;
    private final List<VueRetrievalObservation> denseRows;

    private VueRetrievalEvaluationReport(
            boolean executed,
            List<String> notExecutedReasons,
            VueRetrievalComparison comparison,
            List<VueRetrievalObservation> hybridRows,
            List<VueRetrievalObservation> denseRows) {
        this.executed = executed;
        this.notExecutedReasons = List.copyOf(notExecutedReasons);
        this.comparison = comparison;
        this.hybridRows = List.copyOf(hybridRows);
        this.denseRows = List.copyOf(denseRows);
    }

    public static VueRetrievalEvaluationReport notExecuted(List<String> reasons) {
        return new VueRetrievalEvaluationReport(
                false, reasons == null ? List.of() : reasons, null, List.of(), List.of());
    }

    public static VueRetrievalEvaluationReport executed(
            VueRetrievalComparison comparison,
            List<VueRetrievalObservation> hybridRows,
            List<VueRetrievalObservation> denseRows) {
        return new VueRetrievalEvaluationReport(
                true, List.of(), comparison, hybridRows, denseRows);
    }

    public boolean executed() {
        return executed;
    }

    public boolean passed() {
        return executed && comparison.passed();
    }

    public VueRetrievalComparison comparison() {
        return comparison;
    }

    public String renderMarkdown() {
        StringBuilder output = new StringBuilder("# Vue 双层检索真实评测报告\n\n");
        if (!executed) {
            output.append("状态：未执行\n\n");
            output.append("原因：\n\n");
            if (notExecutedReasons.isEmpty()) {
                output.append("- 未提供可执行环境\n");
            } else {
                notExecutedReasons.forEach(reason -> output.append("- ").append(reason).append('\n'));
            }
            return EvaluationReportSanitizer.sanitize(output.toString());
        }

        output.append("状态：").append(comparison.passed() ? "通过" : "未通过").append("\n\n");
        output.append("无期望功能的用例按 Feature Recall@4 = 1.0 计入平均值。\n\n");
        output.append("| 指标 | Hybrid | Dense-only | Hybrid - Dense | 门槛 |\n");
        output.append("|---|---:|---:|---:|---|\n");
        output.append(metricRow(
                "Skeleton Hit@1",
                comparison.hybrid().skeletonHitAt1(),
                comparison.denseOnly().skeletonHitAt1(),
                comparison.skeletonDelta(),
                ">= 0.90，且 delta >= -0.05"));
        output.append(metricRow(
                "Feature Recall@4",
                comparison.hybrid().featureRecallAt4(),
                comparison.denseOnly().featureRecallAt4(),
                comparison.featureDelta(),
                ">= 0.85，且 delta >= -0.05"));
        appendStyleSlices(output, comparison.hybrid().styleSlices(), "Hybrid");
        appendStyleSlices(output, comparison.denseOnly().styleSlices(), "Dense-only");
        appendRows(output, "Hybrid 逐条结果", hybridRows);
        appendRows(output, "Dense-only 逐条结果", denseRows);
        return EvaluationReportSanitizer.sanitize(output.toString());
    }

    private String metricRow(
            String name,
            double hybrid,
            double dense,
            double delta,
            String threshold) {
        return "| %s | %s | %s | %s | %s |%n".formatted(
                name, format(hybrid), format(dense), format(delta), threshold);
    }

    private void appendStyleSlices(
            StringBuilder output,
            Map<String, VueRetrievalMetrics.StyleSlice> slices,
            String chain) {
        output.append("\n## ").append(chain).append(" 按 queryStyle 切片\n\n");
        output.append("| queryStyle | 样本数 | Skeleton Hit@1 | Feature Recall@4 |\n");
        output.append("|---|---:|---:|---:|\n");
        slices.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> output.append("| ").append(entry.getKey())
                        .append(" | ").append(entry.getValue().queryCount())
                        .append(" | ").append(format(entry.getValue().skeletonHitAt1()))
                        .append(" | ").append(format(entry.getValue().featureRecallAt4()))
                        .append(" |\n"));
    }

    private void appendRows(
            StringBuilder output,
            String heading,
            List<VueRetrievalObservation> rows) {
        output.append("\n## ").append(heading).append("\n\n");
        output.append("| QueryID | Style | Skeleton | Features | Error |\n");
        output.append("|---|---|---|---|---|\n");
        rows.forEach(row -> output.append("| ").append(escape(row.evalCase().queryId()))
                .append(" | ").append(escape(row.evalCase().queryStyle()))
                .append(" | ").append(escape(row.retrievedSkeletonId()))
                .append(" | ").append(escape(String.join(", ", row.retrievedFeatureIds())))
                .append(" | ").append(escape(row.error()))
                .append(" |\n"));
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("|", "\\|").replace("\n", " ");
    }
}
