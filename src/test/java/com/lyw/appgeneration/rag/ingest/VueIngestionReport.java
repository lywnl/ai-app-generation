package com.lyw.appgeneration.rag.ingest;

import com.lyw.appgeneration.rag.eval.EvaluationReportSanitizer;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Vue 知识真实摄取的脱敏质量报告。
 *
 * <p>报告只保留状态、目标、版本、计数、维度和受控问题类别，不能承载物理行、检索文本、向量或异常消息。</p>
 */
public final class VueIngestionReport {

    private static final Pattern TARGET = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9.-]*:[0-9]{1,5}/[A-Za-z0-9_-]+");
    private static final Pattern TARGET_ENDPOINT = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9.-]*:[0-9]{1,5}");
    private static final Pattern CATALOG_VERSION = Pattern.compile("[a-fA-F0-9]{64}");
    private static final Pattern EXCEPTION_SIMPLE_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    private enum Status {
        NOT_EXECUTED,
        FAILED,
        VERIFIED
    }

    private final Status status;
    private final String target;
    private final String catalogVersion;
    private final VueIngestionVerification verification;
    private final List<String> reasons;

    private VueIngestionReport(
            Status status,
            String target,
            String catalogVersion,
            VueIngestionVerification verification,
            List<String> reasons) {
        this.status = Objects.requireNonNull(status);
        this.target = controlledTarget(target);
        this.catalogVersion = controlledCatalogVersion(catalogVersion);
        this.verification = verification;
        this.reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public static VueIngestionReport notExecuted(String target, List<String> reasons) {
        return new VueIngestionReport(Status.NOT_EXECUTED, target, "", null, reasons);
    }

    public static VueIngestionReport failed(
            String target,
            String catalogVersion,
            List<String> reasons) {
        return new VueIngestionReport(Status.FAILED, target, catalogVersion, null, reasons);
    }

    public static VueIngestionReport verified(
            String target,
            VueIngestionVerification verification) {
        return new VueIngestionReport(
                Status.VERIFIED,
                target,
                verification == null ? "" : verification.catalogVersion(),
                Objects.requireNonNull(verification),
                verification.issues());
    }

    public boolean passed() {
        return status == Status.VERIFIED && verification != null && verification.passed();
    }

    public String renderMarkdown() {
        StringBuilder output = new StringBuilder("# Vue 知识摄取质量报告\n\n");
        output.append("状态：").append(statusText()).append("\n\n");
        output.append("目标：").append(target).append("/templates_vue\n\n");
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
        if (reason.startsWith("PGVector 端口不可达: ")) {
            String endpoint = reason.substring("PGVector 端口不可达: ".length());
            return TARGET_ENDPOINT.matcher(endpoint).matches()
                    ? "PGVector 端口不可达: " + endpoint
                    : "PGVector 端口不可达";
        }
        if (reason.startsWith("真实摄取依赖失败: ")) {
            String exceptionName = reason.substring("真实摄取依赖失败: ".length());
            return EXCEPTION_SIMPLE_NAME.matcher(exceptionName).matches()
                    ? "真实摄取依赖失败: " + exceptionName
                    : "真实摄取依赖失败";
        }
        return "已检测到受控问题";
    }

    private static String controlledTarget(String target) {
        if (target != null && TARGET.matcher(target).matches()) {
            return target;
        }
        return "未提供";
    }

    private static String controlledCatalogVersion(String catalogVersion) {
        if (catalogVersion != null && CATALOG_VERSION.matcher(catalogVersion).matches()) {
            return catalogVersion;
        }
        return "";
    }
}
