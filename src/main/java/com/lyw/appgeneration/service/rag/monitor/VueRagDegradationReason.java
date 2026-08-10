package com.lyw.appgeneration.service.rag.monitor;

/**
 * Vue RAG 的有限降级原因，枚举值是唯一允许进入指标标签的取值。
 */
public enum VueRagDegradationReason {

    BM25_FAILED("bm25_failed"),
    DENSE_FAILED("dense_failed"),
    RERANK_FAILED("rerank_failed"),
    FALLBACK_SKELETON("fallback_skeleton"),
    CATALOG_UNAVAILABLE("catalog_unavailable");

    private final String tagValue;

    VueRagDegradationReason(String tagValue) {
        this.tagValue = tagValue;
    }

    public String tagValue() {
        return tagValue;
    }
}
