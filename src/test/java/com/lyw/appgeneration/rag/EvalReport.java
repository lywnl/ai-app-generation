package com.lyw.appgeneration.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评估报告聚合器 — 累积每条 query 的指标 → 输出 Markdown 报告 + JSON baseline
 *
 * <p>三个核心职责:
 * <ol>
 *     <li>{@link #record} — 实时累积每条 query 的指标值</li>
 *     <li>{@link #renderMarkdown} — 渲染人类可读的 Markdown 总报告(汇总 + 按 queryStyle 切片)</li>
 *     <li>{@link #saveBaseline}/{@link #compareWithBaseline} — JSON 留底 + 回归对比</li>
 * </ol>
 *
 * <p>不直接输出到控制台 — 所有 IO 操作由调用方决定路径,便于在不同环境(本地/CI)落盘到不同位置。
 *
 * @author lyw
 */
@Slf4j
public class EvalReport {

    private final List<RowResult> rows = new ArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** 单条 query 的所有指标快照(对外为 record,语义不可变) */
    public record RowResult(
            String queryId,
            String query,
            String queryStyle,
            String type,
            List<String> retrievedIds,
            int relevantCount,
            double recallAt3,
            double recallAt5,
            double recallAt10,
            double precisionAt3,
            int hitAt3,
            double mrr,
            double ndcgAt3
    ) {}

    /**
     * 记录一条 query 的检索结果与指标
     *
     * @param ks Recall@K 计算时使用的 K 值序列(本设计固定为 [3, 5, 10],下方硬编码)
     */
    public void record(EvalCase evalCase, List<String> retrievedIds, List<Integer> ks) {
        // 本设计将 K=[3,5,10] 写死,ks 参数保留用于未来扩展(如外部配置不同 K 列表)
        rows.add(new RowResult(
                evalCase.queryId(),
                evalCase.query(),
                evalCase.queryStyle() == null ? "未分类" : evalCase.queryStyle(),
                evalCase.typeRaw(),
                retrievedIds,
                evalCase.relevantIds().size(),
                MetricCalculator.recallAtK(retrievedIds, evalCase, 3),
                MetricCalculator.recallAtK(retrievedIds, evalCase, 5),
                MetricCalculator.recallAtK(retrievedIds, evalCase, 10),
                MetricCalculator.precisionAtK(retrievedIds, evalCase, 3),
                MetricCalculator.hitAtK(retrievedIds, evalCase, 3),
                MetricCalculator.reciprocalRank(retrievedIds, evalCase),
                MetricCalculator.ndcgAtK(retrievedIds, evalCase, 3)
        ));
    }

    /** 全量平均指标(写 baseline 用) */
    public Map<String, Double> aggregate() {
        Map<String, Double> agg = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            return agg;
        }
        agg.put("recall@3",    avg(RowResult::recallAt3));
        agg.put("recall@5",    avg(RowResult::recallAt5));
        agg.put("recall@10",   avg(RowResult::recallAt10));
        agg.put("precision@3", avg(RowResult::precisionAt3));
        agg.put("hit@3",       avg(r -> (double) r.hitAt3()));
        agg.put("mrr",         avg(RowResult::mrr));
        agg.put("ndcg@3",      avg(RowResult::ndcgAt3));
        return agg;
    }

    /** 渲染 Markdown 报告 — 含汇总表、按 queryStyle 分组、Bad Case Top10 */
    public String renderMarkdown(String experimentName) {
        StringBuilder sb = new StringBuilder();
        sb.append("# RAG 检索质量评估报告\n\n");
        sb.append("- 实验名: `").append(experimentName).append("`\n");
        sb.append("- 生成时间: ").append(LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
        sb.append("- 评估 query 总数: ").append(rows.size()).append("\n\n");

        // 汇总指标
        sb.append("## 1. 汇总指标(全量平均)\n\n");
        sb.append("| 指标 | 数值 |\n|---|---|\n");
        aggregate().forEach((k, v) ->
                sb.append("| ").append(k).append(" | ").append(fmt(v)).append(" |\n"));

        // 按 queryStyle 分组
        sb.append("\n## 2. 按 query 风格分组\n\n");
        sb.append("| 风格 | 样本数 | Recall@3 | NDCG@3 | MRR | Hit@3 |\n");
        sb.append("|---|---|---|---|---|---|\n");
        Map<String, List<RowResult>> byStyle = new HashMap<>();
        for (RowResult r : rows) {
            byStyle.computeIfAbsent(r.queryStyle(), x -> new ArrayList<>()).add(r);
        }
        byStyle.forEach((style, list) -> sb.append("| ").append(style)
                .append(" | ").append(list.size())
                .append(" | ").append(fmt(list.stream().mapToDouble(RowResult::recallAt3).average().orElse(0)))
                .append(" | ").append(fmt(list.stream().mapToDouble(RowResult::ndcgAt3).average().orElse(0)))
                .append(" | ").append(fmt(list.stream().mapToDouble(RowResult::mrr).average().orElse(0)))
                .append(" | ").append(fmt(list.stream().mapToDouble(r -> r.hitAt3()).average().orElse(0)))
                .append(" |\n"));

        // Bad Case Top10(NDCG@3 最低的 10 条,优先排查)
        sb.append("\n## 3. Bad Case Top 10(按 NDCG@3 升序)\n\n");
        sb.append("| QueryID | Query | NDCG@3 | Recall@3 | 检索结果 (top3) |\n");
        sb.append("|---|---|---|---|---|\n");
        rows.stream()
                .sorted(java.util.Comparator.comparingDouble(RowResult::ndcgAt3))
                .limit(10)
                .forEach(r -> sb.append("| ").append(r.queryId())
                        .append(" | ").append(escape(r.query()))
                        .append(" | ").append(fmt(r.ndcgAt3()))
                        .append(" | ").append(fmt(r.recallAt3()))
                        .append(" | ").append(top3(r.retrievedIds()))
                        .append(" |\n"));

        return sb.toString();
    }

    /** 落盘 Markdown 报告 */
    public Path writeMarkdown(Path outputDir, String experimentName) throws IOException {
        Files.createDirectories(outputDir);
        Path file = outputDir.resolve(experimentName + ".md");
        Files.writeString(file, renderMarkdown(experimentName));
        log.info("[RagEval] Markdown 报告已写入: {}", file.toAbsolutePath());
        return file;
    }

    /** 把当前实验的聚合指标 + 所有 row 落地为 JSON,作为后续回归对比的基线 */
    public Path saveBaseline(Path baselineFile, String experimentName) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("experiment", experimentName);
        payload.put("createdAt", LocalDateTime.now().toString());
        payload.put("totalQueries", rows.size());
        payload.put("aggregate", aggregate());
        payload.put("rows", rows);
        Files.createDirectories(baselineFile.getParent());
        mapper.writeValue(baselineFile.toFile(), payload);
        log.info("[RagEval] baseline 已写入: {}", baselineFile.toAbsolutePath());
        return baselineFile;
    }

    /**
     * 与历史 baseline 比较,返回退化指标的描述列表
     *
     * @param baselineFile 基线 JSON 文件
     * @param tolerance    允许的退化幅度(如 0.05 = 5pp);超过即标记 regression
     * @return 退化项描述列表;空列表表示无退化
     */
    @SuppressWarnings("unchecked")
    public List<String> compareWithBaseline(Path baselineFile, double tolerance) throws IOException {
        if (!Files.exists(baselineFile)) {
            log.warn("[RagEval] 基线文件不存在,跳过对比: {}", baselineFile);
            return List.of();
        }
        Map<String, Object> baseline = mapper.readValue(baselineFile.toFile(), Map.class);
        Map<String, Number> baselineAgg = (Map<String, Number>) baseline.get("aggregate");
        Map<String, Double> currentAgg = aggregate();

        List<String> regressions = new ArrayList<>();
        baselineAgg.forEach((metric, baseValue) -> {
            double base = baseValue.doubleValue();
            double curr = currentAgg.getOrDefault(metric, 0.0);
            double delta = curr - base;
            if (delta < -tolerance) {
                regressions.add(String.format(
                        "%s 退化:基线 %.4f → 当前 %.4f (Δ %.4f, 超过容差 %.4f)",
                        metric, base, curr, delta, tolerance));
            }
        });
        return regressions;
    }

    /** 暴露所有 row,便于测试断言或外部进一步处理 */
    public List<RowResult> rows() {
        return List.copyOf(rows);
    }

    // ----- 内部工具 -----

    private double avg(java.util.function.ToDoubleFunction<RowResult> f) {
        return rows.stream().mapToDouble(f).average().orElse(0.0);
    }

    private static String fmt(double v) {
        return String.format("%.4f", v);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("|", "\\|").replace("\n", " ");
    }

    private static String top3(List<String> ids) {
        if (ids == null || ids.isEmpty()) return "(空)";
        return String.join(", ", ids.subList(0, Math.min(3, ids.size())));
    }
}
