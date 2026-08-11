package com.lyw.appgeneration.rag.vue;

import com.lyw.appgeneration.rag.eval.EvaluationReportSanitizer;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Vue 双层检索真实评测报告。
 */
public final class VueRetrievalEvaluationReport {

    private static final int REQUIRED_QUERY_COUNT = 30;

    private final boolean executed;
    private final boolean failed;
    private final String runId;
    private final List<String> notExecutedReasons;
    private final VueRetrievalComparison comparison;
    private final List<VueRetrievalObservation> hybridRows;
    private final List<VueRetrievalObservation> denseRows;

    private VueRetrievalEvaluationReport(
            boolean executed,
            boolean failed,
            String runId,
            List<String> notExecutedReasons,
            VueRetrievalComparison comparison,
            List<VueRetrievalObservation> hybridRows,
            List<VueRetrievalObservation> denseRows) {
        this.executed = executed;
        this.failed = failed;
        this.runId = runId;
        this.notExecutedReasons = List.copyOf(notExecutedReasons);
        this.comparison = comparison;
        this.hybridRows = List.copyOf(hybridRows);
        this.denseRows = List.copyOf(denseRows);
    }

    public static VueRetrievalEvaluationReport notExecuted(List<String> reasons) {
        return notExecuted(newRunId(), reasons);
    }

    public static VueRetrievalEvaluationReport notExecuted(String runId, List<String> reasons) {
        return new VueRetrievalEvaluationReport(
                false, false, runId, reasons == null ? List.of() : reasons,
                null, List.of(), List.of());
    }

    public static VueRetrievalEvaluationReport failed(String runId, List<String> reasons) {
        return new VueRetrievalEvaluationReport(
                false, true, runId, reasons == null ? List.of() : reasons,
                null, List.of(), List.of());
    }

    public static VueRetrievalEvaluationReport executed(
            VueRetrievalComparison comparison,
            List<VueRetrievalObservation> hybridRows,
            List<VueRetrievalObservation> denseRows) {
        return executed(newRunId(), comparison, hybridRows, denseRows);
    }

    public static VueRetrievalEvaluationReport executed(
            String runId,
            VueRetrievalComparison comparison,
            List<VueRetrievalObservation> hybridRows,
            List<VueRetrievalObservation> denseRows) {
        return new VueRetrievalEvaluationReport(
                true, false, runId, List.of(), comparison, hybridRows, denseRows);
    }

    public boolean executed() {
        return executed;
    }

    public boolean passed() {
        return executed
                && comparison.passed()
                && comparison.hybrid().queryCount() == REQUIRED_QUERY_COUNT
                && comparison.denseOnly().queryCount() == REQUIRED_QUERY_COUNT
                && hybridRows.size() == REQUIRED_QUERY_COUNT
                && denseRows.size() == REQUIRED_QUERY_COUNT;
    }

    public VueRetrievalComparison comparison() {
        return comparison;
    }

    public VueRetrievalEvaluationReport withRunId(String currentRunId) {
        return new VueRetrievalEvaluationReport(
                executed, failed, currentRunId, notExecutedReasons,
                comparison, hybridRows, denseRows);
    }

    public String renderMarkdown() {
        StringBuilder output = new StringBuilder("# Vue 双层检索真实评测报告\n\n");
        output.append("运行标识：").append(runId).append("\n\n");
        if (!executed) {
            output.append("状态：").append(failed ? "未通过" : "未执行").append("\n\n");
            output.append("原因：\n\n");
            if (notExecutedReasons.isEmpty()) {
                output.append("- 未提供可执行环境\n");
            } else {
                notExecutedReasons.forEach(reason -> output.append("- ").append(reason).append('\n'));
            }
            return EvaluationReportSanitizer.sanitize(output.toString());
        }

        output.append("状态：").append(passed() ? "通过" : "未通过").append("\n\n");
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

    private static String newRunId() {
        return UUID.randomUUID().toString();
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
