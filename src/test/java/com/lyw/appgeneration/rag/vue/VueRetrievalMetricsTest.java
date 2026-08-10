package com.lyw.appgeneration.rag.vue;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VueRetrievalMetricsTest {

    private static final double EPSILON = 1e-9;

    @Test
    void calculatesHitRecallStyleSlicesAndEmptyFeatureExpectationAsOne() {
        List<VueRetrievalObservation> observations = List.of(
                observation("q1", "精确技术词", "s1", List.of("f1", "x"),
                        List.of("s1"), List.of("f1", "f2")),
                observation("q2", "陷阱", "wrong", List.of(),
                        List.of("s2"), List.of()),
                observation("q3", "精确技术词", "s3", List.of("f3"),
                        List.of("s3"), List.of("f3")));

        VueRetrievalMetrics metrics = VueRetrievalMetrics.calculate(observations);

        assertEquals(2.0 / 3.0, metrics.skeletonHitAt1(), EPSILON);
        assertEquals(5.0 / 6.0, metrics.featureRecallAt4(), EPSILON);
        assertEquals(1.0, metrics.styleSlices().get("精确技术词").skeletonHitAt1(), EPSILON);
        assertEquals(0.0, metrics.styleSlices().get("陷阱").skeletonHitAt1(), EPSILON);
        assertEquals(1.0, metrics.styleSlices().get("陷阱").featureRecallAt4(), EPSILON);
    }

    @Test
    void comparesHybridWithDenseAndAppliesAbsoluteAndDeltaGates() {
        VueRetrievalMetrics hybrid = metrics(0.92, 0.86);
        VueRetrievalMetrics dense = metrics(0.96, 0.90);

        VueRetrievalComparison passing = VueRetrievalComparison.compare(hybrid, dense);

        assertEquals(-0.04, passing.skeletonDelta(), EPSILON);
        assertEquals(-0.04, passing.featureDelta(), EPSILON);
        assertTrue(passing.passed());

        VueRetrievalComparison absoluteFailure = VueRetrievalComparison.compare(
                metrics(0.89, 0.90), metrics(0.89, 0.90));
        assertFalse(absoluteFailure.passed());

        VueRetrievalComparison regressionFailure = VueRetrievalComparison.compare(
                metrics(0.94, 0.85), metrics(1.0, 0.91));
        assertFalse(regressionFailure.passed());
    }

    private VueRetrievalObservation observation(
            String queryId,
            String style,
            String skeleton,
            List<String> features,
            List<String> expectedSkeletons,
            List<String> expectedFeatures) {
        return new VueRetrievalObservation(
                new VueEvalCase(queryId, "query", style, expectedSkeletons, expectedFeatures),
                skeleton,
                features,
                null);
    }

    private VueRetrievalMetrics metrics(double skeletonHit, double featureRecall) {
        return new VueRetrievalMetrics(skeletonHit, featureRecall, 1, java.util.Map.of());
    }
}
