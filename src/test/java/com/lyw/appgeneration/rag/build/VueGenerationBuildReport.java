package com.lyw.appgeneration.rag.build;

import com.lyw.appgeneration.core.builder.BuildResult;
import com.lyw.appgeneration.rag.eval.EvaluationReportSanitizer;

import java.util.List;

/**
 * 十条真实 Vue 生成与构建质量报告。
 */
public final class VueGenerationBuildReport {

    private final boolean executed;
    private final List<String> reasons;
    private final List<VueGenerationBuildRow> rows;

    private VueGenerationBuildReport(
            boolean executed,
            List<String> reasons,
            List<VueGenerationBuildRow> rows) {
        this.executed = executed;
        this.reasons = List.copyOf(reasons);
        this.rows = List.copyOf(rows);
    }

    public static VueGenerationBuildReport notExecuted(List<String> reasons) {
        return new VueGenerationBuildReport(
                false, reasons == null ? List.of() : reasons, List.of());
    }

    public static VueGenerationBuildReport executed(List<VueGenerationBuildRow> rows) {
        return new VueGenerationBuildReport(
                true, List.of(), rows == null ? List.of() : rows);
    }

    public boolean executed() {
        return executed;
    }

    public boolean passed() {
        return executed && rows.size() == 10
                && rows.stream().allMatch(VueGenerationBuildRow::successful);
    }

    public String renderMarkdown() {
        StringBuilder output = new StringBuilder("# Vue 十条真实生成构建报告\n\n");
        if (!executed) {
            output.append("状态：未执行\n\n原因：\n\n");
            if (reasons.isEmpty()) {
                output.append("- 未提供可执行环境\n");
            } else {
                reasons.forEach(reason -> output.append("- ")
                        .append(reason).append('\n'));
            }
            return EvaluationReportSanitizer.sanitize(output.toString());
        }

        long successCount = rows.stream().filter(VueGenerationBuildRow::successful).count();
        output.append("状态：").append(passed() ? "通过" : "未通过").append("\n\n");
        output.append("构建成功数：").append(successCount).append('/').append(rows.size()).append("\n\n");
        output.append("首版不做模型二次修复；以下均为首次生成结果。\n\n");
        output.append("| Case | 类别 | 生成完成 | 骨架 | 功能片段 | 阶段 | 退出码 | 超时 | 尾部/错误 |\n");
        output.append("|---|---|---|---|---|---|---:|---|---|\n");
        rows.forEach(row -> appendRow(output, row));
        return EvaluationReportSanitizer.sanitize(output.toString());
    }

    private void appendRow(StringBuilder output, VueGenerationBuildRow row) {
        BuildResult result = row.buildResult();
        output.append("| ").append(escape(row.testCase().caseId()))
                .append(" | ").append(escape(row.testCase().category()))
                .append(" | ").append(row.generationCompleted())
                .append(" | ").append(escape(row.selectedSkeletonId()))
                .append(" | ").append(escape(String.join(", ", row.selectedFeatureIds())))
                .append(" | ").append(result == null ? "" : result.stage())
                .append(" | ").append(result == null || result.exitCode() == null ? "" : result.exitCode())
                .append(" | ").append(result != null && result.timedOut())
                .append(" | ").append(escape(combinedDiagnostic(row)))
                .append(" |\n");
    }

    private String combinedDiagnostic(VueGenerationBuildRow row) {
        String outputTail = row.buildResult() == null ? "" : row.buildResult().outputTail();
        String error = row.error() == null ? "" : row.error();
        String combined = outputTail.isBlank() ? error : outputTail + (error.isBlank() ? "" : "\n" + error);
        return combined;
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("|", "\\|").replace("\n", "<br>");
    }
}
