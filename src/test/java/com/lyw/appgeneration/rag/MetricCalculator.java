package com.lyw.appgeneration.rag;

import java.util.List;

/**
 * RAG 检索质量指标计算工具(纯函数,无任何 Spring/IO 依赖)
 *
 * <p>所有方法接收两个核心参数:
 * <ul>
 *     <li>{@code retrievedIds} — 检索器按相关度降序返回的模板 id 列表</li>
 *     <li>{@code evalCase} — 该 query 的标注信息(含 relevance map)</li>
 * </ul>
 *
 * <p>设计取舍:不引入第三方 IR 评估库(Apache Lucene 的 TestRecall 等),
 * 因为指标定义简单、自实现可控、便于在简历/答辩场景解释每个公式。
 *
 * @author lyw
 */
public final class MetricCalculator {

    private MetricCalculator() {
    }

    /**
     * Recall@K = (前 K 个结果中相关命中数) / (全部相关模板总数)
     *
     * <p>关键点:分母是 ground truth 中所有 relevance &gt;= 1 的模板,
     * 不是检索返回的相关数 — 这是 Recall 与 Precision 的本质区别。
     *
     * @return [0, 1] 区间;若无任何相关模板则返回 0(避免除 0)
     */
    public static double recallAtK(List<String> retrievedIds, EvalCase evalCase, int k) {
        var relevantSet = evalCase.relevantIds();
        if (relevantSet.isEmpty()) {
            return 0.0;
        }
        int hits = 0;
        int limit = Math.min(k, retrievedIds.size());
        for (int i = 0; i < limit; i++) {
            if (relevantSet.contains(retrievedIds.get(i))) {
                hits++;
            }
        }
        return (double) hits / relevantSet.size();
    }

    /**
     * Precision@K = (前 K 个结果中相关命中数) / K
     *
     * <p>注入下游 LLM Prompt 的"纯净度"指标 — 噪声越多,越容易让生成跑偏。
     */
    public static double precisionAtK(List<String> retrievedIds, EvalCase evalCase, int k) {
        if (k <= 0) {
            return 0.0;
        }
        var relevantSet = evalCase.relevantIds();
        int hits = 0;
        int limit = Math.min(k, retrievedIds.size());
        for (int i = 0; i < limit; i++) {
            if (relevantSet.contains(retrievedIds.get(i))) {
                hits++;
            }
        }
        return (double) hits / k;
    }

    /**
     * Hit@K = 前 K 个里至少命中 1 个相关 → 1,否则 0
     *
     * <p>非技术受众友好的"成功率"指标,适合做 Dashboard 第一行的"通过率"展示。
     */
    public static int hitAtK(List<String> retrievedIds, EvalCase evalCase, int k) {
        var relevantSet = evalCase.relevantIds();
        int limit = Math.min(k, retrievedIds.size());
        for (int i = 0; i < limit; i++) {
            if (relevantSet.contains(retrievedIds.get(i))) {
                return 1;
            }
        }
        return 0;
    }

    /**
     * MRR = 1 / 第一个相关结果的排名(1-indexed),无相关则记 0
     *
     * <p>衡量"用户最想要的有没有排在最前面" — 排第 1 还是排第 5,体验差距巨大。
     */
    public static double reciprocalRank(List<String> retrievedIds, EvalCase evalCase) {
        var relevantSet = evalCase.relevantIds();
        for (int i = 0; i < retrievedIds.size(); i++) {
            if (relevantSet.contains(retrievedIds.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /**
     * NDCG@K — 归一化折损累积增益,同时考虑命中和排序质量
     *
     * <p>公式分两步:
     * <ol>
     *     <li>DCG@K = Σ (2^rel_i - 1) / log2(i + 2)  for i = 0..K-1
     *         (i+2 是因为 i 从 0 开始,排名 1 对应 log2(2)=1)</li>
     *     <li>IDCG@K = 用 ground truth 全集中 relevance 最高的前 K 个理想排序计算的 DCG</li>
     *     <li>NDCG@K = DCG@K / IDCG@K</li>
     * </ol>
     *
     * <p>易错点:IDCG 的"理想排序"应该取自 ground truth 全集(包括 relevance=1 的部分相关),
     * 而不是检索返回结果集 — 否则当检索完全 miss 时 NDCG 会被错算成 1.0。
     *
     * @return [0, 1] 区间;ground truth 全为 0 时返回 0
     */
    public static double ndcgAtK(List<String> retrievedIds, EvalCase evalCase, int k) {
        // 步骤 1:实际 DCG
        double dcg = 0.0;
        int limit = Math.min(k, retrievedIds.size());
        for (int i = 0; i < limit; i++) {
            int rel = evalCase.relevanceOf(retrievedIds.get(i));
            if (rel > 0) {
                dcg += (Math.pow(2, rel) - 1) / log2(i + 2);
            }
        }

        // 步骤 2:理想 DCG — 用 ground truth 全集的相关度降序排,取前 K
        var idealRels = evalCase.relevance() == null
                ? List.<Integer>of()
                : evalCase.relevance().values().stream()
                        .filter(v -> v != null && v > 0)
                        .sorted(java.util.Comparator.reverseOrder())
                        .toList();

        double idcg = 0.0;
        int idealLimit = Math.min(k, idealRels.size());
        for (int i = 0; i < idealLimit; i++) {
            idcg += (Math.pow(2, idealRels.get(i)) - 1) / log2(i + 2);
        }

        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }
}
