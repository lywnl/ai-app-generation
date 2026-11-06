package com.lyw.appgeneration.service.rag.retrieval;

import com.lyw.appgeneration.service.rag.model.RagDocumentKind;
import com.lyw.appgeneration.service.rag.model.RankedCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RrfFusionServiceTest {

    private final RrfFusionService fusionService = new RrfFusionService();

    @Test
    void calculatesExactRrfScoreAndRaisesIntersectionCandidate() {
        List<RankedCandidate> bm25 = List.of(candidate("only-bm25", 1), candidate("intersection", 2));
        List<RankedCandidate> dense = List.of(candidate("only-dense", 1), candidate("intersection", 3));

        List<RankedCandidate> result = fusionService.fuse(bm25, dense, Map.of(), 15);

        assertEquals("intersection", result.getFirst().documentId());
        assertEquals(1.0 / 62.0 + 1.0 / 63.0, result.getFirst().score(), 1.0e-12);
        assertEquals(1.0 / 61.0, result.get(1).score(), 1.0e-12);
        assertEquals(1.0 / 61.0, result.get(2).score(), 1.0e-12);
    }

    @Test
    void preservesSingleChannelOrderAndCreatesRanksFromOne() {
        List<RankedCandidate> dense = List.of(
                candidate("document-c", 1),
                candidate("document-a", 2),
                candidate("document-b", 3));

        List<RankedCandidate> result = fusionService.fuse(List.of(), dense, Map.of(), 15);

        assertEquals(List.of("document-c", "document-a", "document-b"), result.stream()
                .map(RankedCandidate::documentId).toList());
        assertEquals(List.of(1, 2, 3), result.stream().map(RankedCandidate::rank).toList());
    }

    @Test
    void usesOnlyBestRankForDuplicateDocumentInEachChannel() {
        List<RankedCandidate> bm25 = List.of(
                candidate("duplicate", 4),
                candidate("duplicate", 1),
                candidate("other", 2));

        List<RankedCandidate> result = fusionService.fuse(bm25, List.of(), Map.of(), 15);

        assertEquals(2, result.size());
        assertEquals("duplicate", result.getFirst().documentId());
        assertEquals(1.0 / 61.0, result.getFirst().score(), 1.0e-12);
    }

    @Test
    void ignoresOriginalChannelScoresAndUsesQualityThenIdForFinalTies() {
        List<RankedCandidate> bm25 = List.of(
                candidate("document-z", 1, 999.0),
                candidate("document-b", 1, -100.0),
                candidate("document-a", 1, 0.25));
        Map<String, Double> qualityScores = Map.of(
                "document-z", 0.8,
                "document-b", 0.9,
                "document-a", 0.9);

        List<RankedCandidate> result = fusionService.fuse(bm25, List.of(), qualityScores, 15);

        assertEquals(List.of("document-a", "document-b", "document-z"), result.stream()
                .map(RankedCandidate::documentId).toList());
        assertTrue(result.stream().map(RankedCandidate::score).distinct().count() == 1);
    }

    @Test
    void 两路各八个不重合父模板默认融合前十个并拒绝非法候选() {
        List<RankedCandidate> bm25 = java.util.stream.IntStream.rangeClosed(1, 8)
                .mapToObj(index -> candidate("bm25-%02d".formatted(index), index))
                .toList();
        List<RankedCandidate> dense = java.util.stream.IntStream.rangeClosed(1, 8)
                .mapToObj(index -> candidate("dense-%02d".formatted(index), index))
                .toList();
        List<RankedCandidate> withInvalid = new java.util.ArrayList<>(bm25);
        withInvalid.add(new RankedCandidate("", RagDocumentKind.FEATURE_SNIPPET, 1, 1.0));
        withInvalid.add(new RankedCandidate("bad-rank", RagDocumentKind.FEATURE_SNIPPET, 0, 1.0));

        List<RankedCandidate> result = fusionService.fuse(withInvalid, dense, Map.of());

        assertEquals(List.of("bm25-01", "dense-01", "bm25-02", "dense-02", "bm25-03",
                "dense-03", "bm25-04", "dense-04", "bm25-05", "dense-05"),
                result.stream().map(RankedCandidate::documentId).toList());
        assertEquals(6, fusionService.fuse(withInvalid, dense, Map.of(), 6).size());
    }

    @Test
    void 两路重复父模板只合并一次且不足和空结果不填充() {
        List<RankedCandidate> result = fusionService.fuse(
                List.of(candidate("shared", 1), candidate("shared", 3)),
                List.of(candidate("shared", 1), candidate("dense-only", 2)), Map.of());

        assertEquals(List.of("shared", "dense-only"), result.stream().map(RankedCandidate::documentId).toList());
        assertEquals(2.0 / 61.0, result.getFirst().score(), 1.0e-12);
        assertTrue(fusionService.fuse(List.of(), List.of(), Map.of()).isEmpty());
        assertTrue(fusionService.fuse(null, null, null).isEmpty());
        assertTrue(fusionService.fuse(result, List.of(), Map.of(), 0).isEmpty());
    }

    private RankedCandidate candidate(String documentId, int rank) {
        return candidate(documentId, rank, 123.45);
    }

    private RankedCandidate candidate(String documentId, int rank, double score) {
        return new RankedCandidate(documentId, RagDocumentKind.FEATURE_SNIPPET, rank, score);
    }
}
