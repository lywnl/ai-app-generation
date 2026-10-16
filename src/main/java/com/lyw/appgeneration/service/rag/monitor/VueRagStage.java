package com.lyw.appgeneration.service.rag.monitor;

/** 外部调用阶段的固定标签，禁止将查询或模板内容作为指标标签。 */
public enum VueRagStage {
    EMBEDDING("embedding"),
    BM25("bm25"),
    DENSE("dense"),
    RERANK("rerank");

    private final String tagValue;

    VueRagStage(String tagValue) {
        this.tagValue = tagValue;
    }

    public String tagValue() {
        return tagValue;
    }
}
