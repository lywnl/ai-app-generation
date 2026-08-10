package com.lyw.appgeneration.rag.vue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Vue 双层检索指标快照。
 *
 * <p>无期望功能的用例，其 Feature Recall@4 明确定义为 1.0。
 */
public record VueRetrievalMetrics(
        double skeletonHitAt1,
        double featureRecallAt4,
        int queryCount,
        Map<String, StyleSlice> styleSlices
) {

    public VueRetrievalMetrics {
        styleSlices = styleSlices == null ? Map.of() : Map.copyOf(styleSlices);
    }

    public static VueRetrievalMetrics calculate(List<VueRetrievalObservation> observations) {
        List<VueRetrievalObservation> safeObservations = observations == null
                ? List.of()
                : List.copyOf(observations);
        Map<String, List<VueRetrievalObservation>> byStyle = safeObservations.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        observation -> observation.evalCase().queryStyle(),
                        LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));
        Map<String, StyleSlice> slices = new LinkedHashMap<>();
        byStyle.forEach((style, rows) -> slices.put(style, new StyleSlice(
                averageSkeletonHit(rows),
                averageFeatureRecall(rows),
                rows.size())));
        return new VueRetrievalMetrics(
                averageSkeletonHit(safeObservations),
                averageFeatureRecall(safeObservations),
                safeObservations.size(),
                slices);
    }

    private static double averageSkeletonHit(List<VueRetrievalObservation> rows) {
        return rows.stream()
                .mapToDouble(VueRetrievalMetrics::skeletonHit)
                .average()
                .orElse(0.0);
    }

    private static double averageFeatureRecall(List<VueRetrievalObservation> rows) {
        return rows.stream()
                .mapToDouble(VueRetrievalMetrics::featureRecall)
                .average()
                .orElse(0.0);
    }

    private static double skeletonHit(VueRetrievalObservation observation) {
        String actual = observation.retrievedSkeletonId();
        return actual != null && observation.evalCase().expectedSkeletonIds().contains(actual)
                ? 1.0
                : 0.0;
    }

    private static double featureRecall(VueRetrievalObservation observation) {
        List<String> expected = observation.evalCase().expectedFeatureIds();
        if (expected.isEmpty()) {
            return 1.0;
        }
        long matches = observation.retrievedFeatureIds().stream()
                .limit(4)
                .distinct()
                .filter(expected::contains)
                .count();
        return (double) matches / expected.size();
    }

    public record StyleSlice(
            double skeletonHitAt1,
            double featureRecallAt4,
            int queryCount
    ) {
    }
}
