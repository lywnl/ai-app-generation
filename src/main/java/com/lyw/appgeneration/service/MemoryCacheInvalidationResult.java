package com.lyw.appgeneration.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 缓存清理的逐项目结果，供删除编排汇总和有限重试。 */
public record MemoryCacheInvalidationResult(Map<String, String> failures) {

    public MemoryCacheInvalidationResult {
        failures = Map.copyOf(failures);
    }

    public static MemoryCacheInvalidationResult success() {
        return new MemoryCacheInvalidationResult(Map.of());
    }

    public static MemoryCacheInvalidationResult failure(
            String target, Exception exception) {
        return new MemoryCacheInvalidationResult(Map.of(
                target, exception.getClass().getSimpleName() + ": "
                        + exception.getMessage()));
    }

    public Set<String> failedTargets() {
        return failures.keySet();
    }

    public MemoryCacheInvalidationResult merge(
            MemoryCacheInvalidationResult other) {
        LinkedHashMap<String, String> merged = new LinkedHashMap<>(failures);
        merged.putAll(other.failures);
        return new MemoryCacheInvalidationResult(merged);
    }
}
