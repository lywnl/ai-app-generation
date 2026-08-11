package com.lyw.appgeneration.rag.ingest;

import java.util.List;
import java.util.Set;

/**
 * Vue 向量物理数据核验结果。
 */
public record VueIngestionVerification(
        boolean passed,
        String catalogVersion,
        int expectedCount,
        int actualCount,
        long historicalCount,
        Set<Integer> dimensions,
        List<String> issues) {

    public VueIngestionVerification {
        dimensions = Set.copyOf(dimensions);
        issues = List.copyOf(issues);
    }
}
