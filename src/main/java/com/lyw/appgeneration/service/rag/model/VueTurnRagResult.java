package com.lyw.appgeneration.service.rag.model;

/** 初始完整参考与后续功能参考不能混用格式化入口。 */
public record VueTurnRagResult(VueRagContext initial, VueFeatureContext followUp) {
    public VueTurnRagResult {
        if ((initial == null) == (followUp == null)) throw new IllegalArgumentException("RAG结果必须且只能有一种范围");
    }
    public static VueTurnRagResult initial(VueRagContext context) { return new VueTurnRagResult(context, null); }
    public static VueTurnRagResult followUp(VueFeatureContext context) { return new VueTurnRagResult(null, context); }
    public int featureCount() { return initial != null ? initial.features().size() : followUp.features().size(); }
    public boolean degraded() { return initial != null ? initial.degraded() : followUp.degraded(); }
}
