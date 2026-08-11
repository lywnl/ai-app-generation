package com.lyw.appgeneration.rag.ingest;

import com.lyw.appgeneration.rag.eval.EvaluationReportSanitizer;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Vue 知识真实摄取的脱敏质量报告。
 *
 * <p>报告只保留状态、目标、版本、计数、维度和受控问题类别，不能承载物理行、检索文本、向量或异常消息。</p>
 */
public final class VueIngestionReport {

    private static final String TARGET = "PGVector/templates_vue";

    private enum Status {
        NOT_EXECUTED,
        FAILED,
        VERIFIED
    }

    private final Status status;
    private final String catalogVersion;
    private final VueIngestionVerification verification;
    private final List<String> reasons;

    private VueIngestionReport(
            Status status,
            String catalogVersion,
            VueIngestionVerification verification,
            List<String> reasons) {
        this.status = Objects.requireNonNull(status);
        this.catalogVersion = catalogVersion == null ? "" : catalogVersion;
        this.verification = verification;
        this.reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public static VueIngestionReport notExecuted(List<String> reasons) {
        return new VueIngestionReport(Status.NOT_EXECUTED, "", null, reasons);
    }

    public static VueIngestionReport failed(
            VueIngestionExpectedSnapshot expected,
            List<String> reasons) {
        return new VueIngestionReport(
                Status.FAILED, catalogVersion(expected), null, reasons);
    }

    public static VueIngestionReport verified(
            VueIngestionExpectedSnapshot expected,
            VueIngestionVerification verification) {
        return new VueIngestionReport(
                Status.VERIFIED,
                catalogVersion(expected),
                Objects.requireNonNull(verification),
                verification.issues());
    }

    public boolean passed() {
        return status == Status.VERIFIED && verification != null && verification.passed();
    }

    public String renderMarkdown() {
        StringBuilder output = new StringBuilder("# Vue 知识摄取质量报告\n\n");
        output.append("状态：").append(statusText()).append("\n\n");
        output.append("目标：").append(TARGET).append("\n\n");
        output.append("模型：text-embedding-v4（1024 维）\n\n");
        appendCatalogVersion(output);
        appendVerification(output);
        appendReasons(output);
        return EvaluationReportSanitizer.sanitize(output.toString());
    }

    private String statusText() {
        return switch (status) {
            case NOT_EXECUTED -> "未执行";
            case FAILED -> "未通过";
            case VERIFIED -> passed() ? "通过" : "未通过";
        };
    }

    private void appendCatalogVersion(StringBuilder output) {
        if (!catalogVersion.isBlank()) {
            output.append("目录版本：").append(catalogVersion).append("\n\n");
        }
    }

    private void appendVerification(StringBuilder output) {
        if (verification == null) {
            return;
        }
        output.append("当前版本行数：")
                .append(verification.actualCount())
                .append('/')
                .append(verification.expectedCount())
                .append("\n\n");
        output.append("历史版本行数：")
                .append(verification.historicalCount())
                .append("\n\n");
        output.append("向量维度集合：")
                .append(verification.dimensions().stream()
                        .sorted(Comparator.naturalOrder())
                        .map(String::valueOf)
                        .toList())
                .append("\n\n");
    }

    private void appendReasons(StringBuilder output) {
        if (reasons.isEmpty()) {
            return;
        }
        output.append("问题：\n\n");
        reasons.forEach(reason -> output.append("- ").append(controlledReason(reason)).append('\n'));
    }

    private String controlledReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "已检测到受控问题";
        }
        if (reason.equals("RAG_VUE_INGEST 未设置为 true")
                || reason.equals("缺少环境变量 DASHSCOPE_API_KEY")
                || reason.equals("缺少环境变量 SPRING_DATASOURCE_PASSWORD")) {
            return EvaluationReportSanitizer.sanitize(reason);
        }
        if (reason.startsWith("PGVector 端口不可达: ")
                || reason.equals("PGVector 端口不可达")) {
            return "PGVector 端口不可达";
        }
        if (reason.startsWith("真实摄取依赖失败: ")
                || reason.equals("真实摄取依赖失败")) {
            return "真实摄取依赖失败";
        }
        return "已检测到受控问题";
    }

    private static String catalogVersion(VueIngestionExpectedSnapshot expected) {
        return expected == null ? "" : expected.catalogVersion();
    }
}
