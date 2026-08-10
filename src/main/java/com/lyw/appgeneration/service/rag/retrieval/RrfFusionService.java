package com.lyw.appgeneration.service.rag.retrieval;

import com.lyw.appgeneration.service.rag.model.RagDocumentKind;
import com.lyw.appgeneration.service.rag.model.RankedCandidate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仅基于通道排名执行 Reciprocal Rank Fusion。
 */
@Component
public class RrfFusionService {

    public static final int DEFAULT_K = 60;
    public static final int DEFAULT_TOP_K = 15;
    private static final double DEFAULT_CHANNEL_WEIGHT = 1.0;

    /**
     * 使用固定参数 k=60、两路权重 1.0，最多返回 15 个候选。
     */
    public List<RankedCandidate> fuse(List<RankedCandidate> bm25Candidates,
                                      List<RankedCandidate> denseCandidates,
                                      Map<String, Double> qualityScores) {
        return fuse(bm25Candidates, denseCandidates, qualityScores, DEFAULT_TOP_K);
    }

    /**
     * 融合两路候选；通道原始分数不参与任何计算。
     */
    public List<RankedCandidate> fuse(List<RankedCandidate> bm25Candidates,
                                      List<RankedCandidate> denseCandidates,
                                      Map<String, Double> qualityScores,
                                      int topK) {
        if (topK <= 0) {
            return List.of();
        }
        Map<String, FusionCandidate> fused = new HashMap<>();
        addChannel(fused, bm25Candidates);
        addChannel(fused, denseCandidates);

        Map<String, Double> safeQualityScores = qualityScores == null ? Map.of() : qualityScores;
        List<FusionCandidate> sorted = new ArrayList<>(fused.values());
        sorted.sort(Comparator.comparingDouble(FusionCandidate::score).reversed()
                .thenComparing(Comparator.comparingDouble(
                        (FusionCandidate candidate) -> qualityScore(
                                safeQualityScores, candidate.documentId())).reversed())
                .thenComparing(FusionCandidate::documentId));
        return toRankedCandidates(sorted, topK);
    }

    private void addChannel(Map<String, FusionCandidate> fused,
                            List<RankedCandidate> candidates) {
        Map<String, RankedCandidate> bestByDocument = bestRanks(candidates);
        for (RankedCandidate candidate : bestByDocument.values()) {
            double contribution = DEFAULT_CHANNEL_WEIGHT / (DEFAULT_K + candidate.rank());
            fused.compute(candidate.documentId(), (documentId, existing) -> existing == null
                    ? new FusionCandidate(documentId, candidate.documentKind(), contribution)
                    : existing.add(contribution));
        }
    }

    private Map<String, RankedCandidate> bestRanks(List<RankedCandidate> candidates) {
        Map<String, RankedCandidate> bestByDocument = new LinkedHashMap<>();
        if (candidates == null) {
            return bestByDocument;
        }
        for (RankedCandidate candidate : candidates) {
            if (!isValid(candidate)) {
                continue;
            }
            bestByDocument.merge(candidate.documentId(), candidate,
                    (existing, incoming) -> incoming.rank() < existing.rank() ? incoming : existing);
        }
        return bestByDocument;
    }

    private boolean isValid(RankedCandidate candidate) {
        return candidate != null
                && candidate.documentId() != null
                && !candidate.documentId().isBlank()
                && candidate.documentKind() != null
                && candidate.rank() > 0;
    }

    private double qualityScore(Map<String, Double> qualityScores, String documentId) {
        Double score = qualityScores.get(documentId);
        return score != null && Double.isFinite(score) ? score : 0.0;
    }

    private List<RankedCandidate> toRankedCandidates(List<FusionCandidate> sorted, int topK) {
        List<RankedCandidate> result = new ArrayList<>();
        int limit = Math.min(topK, sorted.size());
        for (int index = 0; index < limit; index++) {
            FusionCandidate candidate = sorted.get(index);
            result.add(new RankedCandidate(candidate.documentId(), candidate.documentKind(),
                    index + 1, candidate.score()));
        }
        return List.copyOf(result);
    }

    private record FusionCandidate(
            String documentId,
            RagDocumentKind documentKind,
            double score
    ) {

        private FusionCandidate add(double contribution) {
            return new FusionCandidate(documentId, documentKind, score + contribution);
        }
    }
}
