package com.lyw.appgeneration.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MetricCalculator 纯单元测试 — 不依赖 Spring,CI 默认就跑
 *
 * <p>覆盖五个指标的核心场景:全命中、部分命中、无命中、排序敏感性、空 ground truth。
 * <p>所有期望值用手算或 Excel 验证过,允许浮点误差 1e-6。
 *
 * @author lyw
 */
class MetricCalculatorTest {

    private static final double EPS = 1e-6;

    /** 构造一个 2 个完全相关、1 个部分相关的标注 case */
    private EvalCase mkCase(Map<String, Integer> rel) {
        return new EvalCase("Q-test", "test query", "html",
                "直白型", rel, null);
    }

    // ===== Recall@K =====

    @Test
    void recallAtK_全命中() {
        EvalCase c = mkCase(Map.of("a", 2, "b", 2));
        // 检索结果:[a, b, x] — 前 3 命中全部 2 个相关
        assertEquals(1.0, MetricCalculator.recallAtK(List.of("a", "b", "x"), c, 3), EPS);
    }

    @Test
    void recallAtK_部分命中() {
        EvalCase c = mkCase(Map.of("a", 2, "b", 2, "c", 2));
        // [a, x, y] — 前 3 命中 1 个,共 3 个相关 → 1/3
        assertEquals(1.0 / 3, MetricCalculator.recallAtK(List.of("a", "x", "y"), c, 3), EPS);
    }

    @Test
    void recallAtK_无命中() {
        EvalCase c = mkCase(Map.of("a", 2));
        assertEquals(0.0, MetricCalculator.recallAtK(List.of("x", "y", "z"), c, 3), EPS);
    }

    @Test
    void recallAtK_无相关模板时返回0() {
        EvalCase c = mkCase(Map.of("a", 0)); // 全是 0,等同于 ground truth 为空
        assertEquals(0.0, MetricCalculator.recallAtK(List.of("a"), c, 3), EPS);
    }

    @Test
    void recallAtK_K大于检索结果时也正确() {
        EvalCase c = mkCase(Map.of("a", 2, "b", 2));
        // 只检索出 a,K=10 不会越界
        assertEquals(0.5, MetricCalculator.recallAtK(List.of("a"), c, 10), EPS);
    }

    // ===== Precision@K =====

    @Test
    void precisionAtK_前3里2个相关() {
        EvalCase c = mkCase(Map.of("a", 2, "b", 2));
        assertEquals(2.0 / 3, MetricCalculator.precisionAtK(List.of("a", "b", "x"), c, 3), EPS);
    }

    // ===== Hit@K =====

    @Test
    void hitAtK_至少命中1个为1() {
        EvalCase c = mkCase(Map.of("a", 2));
        assertEquals(1, MetricCalculator.hitAtK(List.of("x", "a", "y"), c, 3));
    }

    @Test
    void hitAtK_无命中为0() {
        EvalCase c = mkCase(Map.of("a", 2));
        assertEquals(0, MetricCalculator.hitAtK(List.of("x", "y", "z"), c, 3));
    }

    // ===== MRR =====

    @Test
    void mrr_第一位命中() {
        EvalCase c = mkCase(Map.of("a", 2));
        assertEquals(1.0, MetricCalculator.reciprocalRank(List.of("a", "x", "y"), c), EPS);
    }

    @Test
    void mrr_第三位命中() {
        EvalCase c = mkCase(Map.of("a", 2));
        assertEquals(1.0 / 3, MetricCalculator.reciprocalRank(List.of("x", "y", "a"), c), EPS);
    }

    @Test
    void mrr_全miss为0() {
        EvalCase c = mkCase(Map.of("a", 2));
        assertEquals(0.0, MetricCalculator.reciprocalRank(List.of("x", "y", "z"), c), EPS);
    }

    // ===== NDCG@K(最容易写错的指标) =====

    @Test
    void ndcgAtK_完美排序为1() {
        // ground truth: a=2, b=1; 检索结果按理想排序返回 → NDCG=1
        EvalCase c = mkCase(Map.of("a", 2, "b", 1));
        assertEquals(1.0, MetricCalculator.ndcgAtK(List.of("a", "b", "x"), c, 3), EPS);
    }

    @Test
    void ndcgAtK_全miss时为0() {
        EvalCase c = mkCase(Map.of("a", 2));
        assertEquals(0.0, MetricCalculator.ndcgAtK(List.of("x", "y", "z"), c, 3), EPS);
        // 关键回归:miss 时 IDCG 用 ground truth 算,绝不能错算成 1.0
    }

    @Test
    void ndcgAtK_排序错位有惩罚() {
        // ground truth: a=2, b=1
        // 理想排序 [a, b]: DCG = (2^2-1)/log2(2) + (2^1-1)/log2(3) = 3 + 0.6309... ≈ 3.6309
        // 错位排序 [b, a]: DCG = (2^1-1)/log2(2) + (2^2-1)/log2(3) = 1 + 1.8927... ≈ 2.8927
        // NDCG = 2.8927 / 3.6309 ≈ 0.7967
        EvalCase c = mkCase(Map.of("a", 2, "b", 1));
        double got = MetricCalculator.ndcgAtK(List.of("b", "a", "x"), c, 3);
        // 期望值用计算器手算:实际为 0.7967...
        assertEquals(0.7967, got, 1e-3);
    }

    @Test
    void ndcgAtK_部分相关也计入分数() {
        // 只命中 b(部分相关 rel=1),a(完全相关 rel=2)未命中
        EvalCase c = mkCase(Map.of("a", 2, "b", 1));
        // DCG = 1/log2(2) = 1.0
        // IDCG = 3 + 0.6309 ≈ 3.6309
        // NDCG ≈ 0.2754
        double got = MetricCalculator.ndcgAtK(List.of("b", "x", "y"), c, 3);
        assertEquals(0.2754, got, 1e-3);
    }
}
