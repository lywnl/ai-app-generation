package com.lyw.appgeneration.rag.vue;

/**
 * Hybrid 与 Dense-only 的核心指标比较及硬门槛结论。
 */
public record VueRetrievalComparison(
        VueRetrievalMetrics hybrid,
        VueRetrievalMetrics denseOnly,
        double skeletonDelta,
        double featureDelta,
        boolean passed
) {

    private static final double MIN_SKELETON_HIT_AT_1 = 0.90;
    private static final double MIN_FEATURE_RECALL_AT_4 = 0.85;
    private static final double MAX_REGRESSION = 0.05;
    private static final double EPSILON = 1e-12;

    public static VueRetrievalComparison compare(
            VueRetrievalMetrics hybrid,
            VueRetrievalMetrics denseOnly) {
        double skeletonDelta = hybrid.skeletonHitAt1() - denseOnly.skeletonHitAt1();
        double featureDelta = hybrid.featureRecallAt4() - denseOnly.featureRecallAt4();
        boolean passed = hybrid.skeletonHitAt1() + EPSILON >= MIN_SKELETON_HIT_AT_1
                && hybrid.featureRecallAt4() + EPSILON >= MIN_FEATURE_RECALL_AT_4
                && skeletonDelta + EPSILON >= -MAX_REGRESSION
                && featureDelta + EPSILON >= -MAX_REGRESSION;
        return new VueRetrievalComparison(
                hybrid, denseOnly, skeletonDelta, featureDelta, passed);
    }
}
