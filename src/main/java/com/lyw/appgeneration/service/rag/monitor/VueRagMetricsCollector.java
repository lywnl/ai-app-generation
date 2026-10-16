package com.lyw.appgeneration.service.rag.monitor;

import com.lyw.appgeneration.service.rag.model.RagDocumentKind;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Vue RAG 指标收集器，只接收实际阶段结果，不参与检索领域决策。
 */
@Component
public class VueRagMetricsCollector {

    private final Timer retrievalDuration;
    private final Timer admissionWait;
    private final Counter embeddingCalls;
    private final Map<StageKey, Timer> stageDurations;
    private final Map<StageKey, Timer> queueDurations;
    private final DistributionSummary bm25Candidates;
    private final DistributionSummary denseCandidates;
    private final DistributionSummary rrfCandidates;
    private final DistributionSummary rerankCandidates;
    private final DistributionSummary finalSkeletons;
    private final DistributionSummary finalFeatures;
    private final DistributionSummary contextLength;
    private final Map<VueRagDegradationReason, Counter> degradationCounters;

    public VueRagMetricsCollector(MeterRegistry registry) {
        retrievalDuration = Timer.builder("vue_rag_retrieval_duration_seconds")
                .description("Vue RAG 混合检索总耗时")
                .register(registry);
        admissionWait = Timer.builder("vue_rag_admission_wait_seconds")
                .description("Vue RAG 等待请求许可的耗时")
                .register(registry);
        embeddingCalls = Counter.builder("vue_rag_embedding_calls_total")
                .description("Vue RAG 逻辑查询向量调用次数，不含客户端内部重试")
                .register(registry);
        stageDurations = new HashMap<>();
        queueDurations = new HashMap<>();
        for (VueRagStage stage : VueRagStage.values()) {
            if (stage == VueRagStage.EMBEDDING) {
                registerStage(registry, stage, null);
            } else {
                for (RagDocumentKind kind : List.of(RagDocumentKind.PROJECT_SKELETON,
                        RagDocumentKind.FEATURE_SNIPPET)) {
                    registerStage(registry, stage, kind);
                }
            }
        }
        bm25Candidates = summary(registry, "vue_rag_bm25_candidates", "BM25 候选数量");
        denseCandidates = summary(registry, "vue_rag_dense_candidates", "Dense 候选数量");
        rrfCandidates = summary(registry, "vue_rag_rrf_candidates", "RRF 融合后候选数量");
        rerankCandidates = summary(registry, "vue_rag_rerank_candidates", "Rerank 后候选数量");
        finalSkeletons = summary(registry, "vue_rag_final_skeletons", "最终骨架数量");
        finalFeatures = summary(registry, "vue_rag_final_features", "最终功能片段数量");
        contextLength = summary(registry, "vue_rag_context_length", "最终 Vue RAG 上下文长度");
        degradationCounters = new EnumMap<>(VueRagDegradationReason.class);
        for (VueRagDegradationReason reason : VueRagDegradationReason.values()) {
            degradationCounters.put(reason, Counter.builder("vue_rag_degradations_total")
                    .description("Vue RAG 降级次数")
                    .tag("reason", reason.tagValue())
                    .register(registry));
        }
    }

    public void recordRetrievalDuration(Duration duration) {
        retrievalDuration.record(duration);
    }

    public void recordAdmissionWait(Duration duration) {
        admissionWait.record(duration);
    }

    public void recordEmbeddingCall() {
        embeddingCalls.increment();
    }

    public void recordStageDuration(VueRagStage stage, RagDocumentKind kind, Duration duration) {
        stageDurations.get(new StageKey(stage, kind)).record(duration);
    }

    public void recordExecutorQueueDuration(VueRagStage stage, RagDocumentKind kind, Duration duration) {
        queueDurations.get(new StageKey(stage, kind)).record(duration);
    }

    private void registerStage(MeterRegistry registry, VueRagStage stage, RagDocumentKind kind) {
        StageKey key = new StageKey(stage, kind);
        String kindTag = kind == null ? "shared" : kind.name().toLowerCase(Locale.ROOT);
        stageDurations.put(key, Timer.builder("vue_rag_stage_duration_seconds")
                .description("Vue RAG 外部调用耗时，不含执行器排队")
                .tag("stage", stage.tagValue()).tag("document_kind", kindTag).register(registry));
        queueDurations.put(key, Timer.builder("vue_rag_executor_queue_seconds")
                .description("Vue RAG 子任务在执行器中排队的耗时")
                .tag("stage", stage.tagValue()).tag("document_kind", kindTag).register(registry));
    }

    public void recordBm25Candidates(int count) {
        bm25Candidates.record(count);
    }

    public void recordDenseCandidates(int count) {
        denseCandidates.record(count);
    }

    public void recordRrfCandidates(int count) {
        rrfCandidates.record(count);
    }

    public void recordRerankCandidates(int count) {
        rerankCandidates.record(count);
    }

    public void recordFinalSelection(int skeletonCount, int featureCount) {
        finalSkeletons.record(skeletonCount);
        finalFeatures.record(featureCount);
    }

    public void recordContextLength(int length) {
        contextLength.record(length);
    }

    public void recordDegradation(VueRagDegradationReason reason) {
        degradationCounters.get(reason).increment();
    }

    private DistributionSummary summary(MeterRegistry registry, String name, String description) {
        return DistributionSummary.builder(name)
                .description(description)
                .register(registry);
    }

    private record StageKey(VueRagStage stage, RagDocumentKind kind) {
    }
}
