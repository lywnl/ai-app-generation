package com.lyw.appgeneration.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.service.rag.RagRetrievalService;
import com.lyw.appgeneration.service.rag.model.RetrievedSnippet;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * RAG 检索质量评估主测试类
 *
 * <p><b>运行方式</b>:此测试默认禁用 — 因为完整跑需要 PGVector 数据库 + DashScope API,
 * 不适合 CI 默认执行。手动运行需设置环境变量:
 * <pre>
 *   RAG_EVAL=true mvn test -Dtest=RagEvaluationTest
 * </pre>
 *
 * <p><b>评估流程</b>:
 * <ol>
 *     <li>从 classpath:rag/eval-set.json 加载所有标注 query</li>
 *     <li>对每条 query 调用 {@link RagRetrievalService#retrieve} 拿到 top-K 结果</li>
 *     <li>用 {@link MetricCalculator} 计算 Recall/Precision/MRR/NDCG/Hit</li>
 *     <li>汇总到 {@link EvalReport} → 渲染 Markdown + 保存 JSON baseline</li>
 *     <li>如果配置了 RAG_EVAL_REGRESSION_CHECK=true,与历史 baseline 对比退化项</li>
 * </ol>
 *
 * <p><b>实验命名约定</b>:每次改动检索参数/模型,通过环境变量 {@code RAG_EVAL_NAME} 指定独立名字,
 * 例如 "rerank-on-v1" / "rerank-off" / "minScore-0.25"。Markdown 与 JSON 都按此名字落盘,
 * 便于在 reports/ 目录里横向对比。
 *
 * @author lyw
 */
@SpringBootTest
@Slf4j
@EnabledIfEnvironmentVariable(named = "RAG_EVAL", matches = "true")
class RagEvaluationTest {

    private static final String DEFAULT_EXPERIMENT = "default";
    private static final String EVAL_SET_PATH = "rag/eval-set.json";
    private static final Path REPORT_DIR = Paths.get("target/rag-eval");

    @Autowired
    private RagRetrievalService retrievalService;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void evaluateRetrievalQuality() throws IOException {
        // 1. 解析实验名(决定输出文件名,便于横向对比)
        String experiment = System.getenv().getOrDefault("RAG_EVAL_NAME", DEFAULT_EXPERIMENT);
        log.info("[RagEval] 启动评估,experiment={}", experiment);

        // 2. 加载评估集
        List<EvalCase> dataset = loadDataset();
        log.info("[RagEval] 已加载 {} 条标注 query", dataset.size());

        // 3. 跑检索 + 评分
        EvalReport report = new EvalReport();
        long start = System.currentTimeMillis();
        for (EvalCase c : dataset) {
            try {
                List<RetrievedSnippet> hits = retrievalService.retrieve(c.query(), c.codeGenType());
                List<String> ids = hits.stream().map(RetrievedSnippet::getId).toList();
                report.record(c, ids, List.of(3, 5, 10));
            } catch (Exception e) {
                // 单条失败不阻断整体评估,但记录为空检索结果(等同于全 miss)
                log.error("[RagEval] queryId={} 检索异常,记为空命中", c.queryId(), e);
                report.record(c, List.of(), List.of(3, 5, 10));
            }
        }
        long elapsed = System.currentTimeMillis() - start;
        log.info("[RagEval] 全部 query 处理完毕,耗时={}ms,平均 P50={}ms",
                elapsed, dataset.isEmpty() ? 0 : elapsed / dataset.size());

        // 4. 输出 Markdown 报告 + JSON baseline
        Path mdFile = report.writeMarkdown(REPORT_DIR, experiment);
        Path jsonFile = report.saveBaseline(
                REPORT_DIR.resolve(experiment + "-baseline.json"), experiment);

        // 5. 控制台打印关键指标(方便 IDE 直接看到结果)
        log.info("[RagEval] 汇总指标: {}", report.aggregate());
        log.info("[RagEval] 报告: {}", mdFile.toAbsolutePath());
        log.info("[RagEval] 基线: {}", jsonFile.toAbsolutePath());

        // 6. 可选:与上一次基线对比,退化超阈值则 fail(用于回归门禁)
        if ("true".equalsIgnoreCase(System.getenv("RAG_EVAL_REGRESSION_CHECK"))) {
            Path previousBaseline = REPORT_DIR.resolve("baseline-latest.json");
            List<String> regressions = report.compareWithBaseline(previousBaseline, 0.05);
            if (!regressions.isEmpty()) {
                String msg = "RAG 检索质量回归:\n  - " + String.join("\n  - ", regressions);
                log.error("[RagEval] {}", msg);
                throw new AssertionError(msg);
            }
            // 通过则更新 latest 基线
            Files.copy(jsonFile, previousBaseline,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 从 classpath 读取 eval-set.json,反序列化为 EvalCase 列表 */
    private List<EvalCase> loadDataset() throws IOException {
        try (var in = getClass().getClassLoader().getResourceAsStream(EVAL_SET_PATH)) {
            if (in == null) {
                throw new IllegalStateException(
                        "评估集未找到: " + EVAL_SET_PATH +
                        ",请在 src/test/resources/rag/eval-set.json 创建");
            }
            // 评估集顶层结构:{ version, totalQueries, queries: [...] }
            Map<String, Object> root = mapper.readValue(in, new TypeReference<>() {});
            Object queries = root.get("queries");
            if (queries == null) {
                throw new IllegalStateException("eval-set.json 缺少 queries 字段");
            }
            return mapper.convertValue(queries, new TypeReference<>() {});
        }
    }
}
