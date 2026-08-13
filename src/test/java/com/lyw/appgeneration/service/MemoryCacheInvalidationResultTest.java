package com.lyw.appgeneration.service;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MemoryCacheInvalidationResultTest {

    @Test
    void mergeKeepsEveryFailedTargetForIndependentRetry() {
        MemoryCacheInvalidationResult merged =
                MemoryCacheInvalidationResult.failure(
                                "L0_REDIS", new IllegalStateException("l0"))
                        .merge(MemoryCacheInvalidationResult.success())
                        .merge(MemoryCacheInvalidationResult.failure(
                                "L2_PREFERENCE_REDIS",
                                new IllegalStateException("l2")));

        assertEquals(Set.of("L0_REDIS", "L2_PREFERENCE_REDIS"),
                merged.failedTargets());
    }
}
