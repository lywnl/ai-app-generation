package com.lyw.appgeneration.service.rag.retrieval;

import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.service.rag.model.RagChunkKind;
import com.lyw.appgeneration.service.rag.model.RagDocumentKind;
import com.lyw.appgeneration.service.rag.model.RankedCandidate;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * Vue 稠密向量召回通道，只返回聚合后的父文档级候选。
 */
@Component
@Slf4j
public class DenseRetriever {

    public static final int DEFAULT_TOP_K = 10;

    private final EmbeddingModel embeddingModel;
    private final Map<CodeGenTypeEnum, EmbeddingStore<TextSegment>> embeddingStores;
    private final RagProperties properties;

    public DenseRetriever(EmbeddingModel embeddingModel,
                          Map<CodeGenTypeEnum, EmbeddingStore<TextSegment>> embeddingStores,
                          RagProperties properties) {
        this.embeddingModel = embeddingModel;
        this.embeddingStores = embeddingStores;
        this.properties = properties;
    }

    /**
     * 使用默认 Top10 从当前 Vue 目录版本召回指定父文档类型。
     */
    public List<RankedCandidate> retrieve(String rawQuery,
                                          String catalogVersion,
                                          RagDocumentKind documentKind) {
        return retrieve(rawQuery, catalogVersion, documentKind, DEFAULT_TOP_K);
    }

    /**
     * 从 Vue 专用向量表召回，并按父文档最高块分数聚合。
     */
    public List<RankedCandidate> retrieve(String rawQuery,
                                          String catalogVersion,
                                          RagDocumentKind documentKind,
                                          int topK) {
        if (isInvalid(rawQuery, catalogVersion, documentKind, topK)) {
            return List.of();
        }
        EmbeddingStore<TextSegment> store = embeddingStores.get(CodeGenTypeEnum.VUE_PROJECT);
        if (store == null) {
            throw new IllegalStateException("Vue 稠密向量存储不可用");
        }

        Embedding queryEmbedding = embeddingModel.embed(rawQuery).content();
        Filter metadataFilter = metadataKey("catalogVersion").isEqualTo(catalogVersion)
                .and(metadataKey("documentKind").isEqualTo(documentKind.name()));
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(topK)
                .minScore(properties.getRetrieval().getMinScore())
                .filter(metadataFilter)
                .build();

        List<EmbeddingMatch<TextSegment>> matches = store.search(request).matches();
        return aggregate(matches, catalogVersion, documentKind, topK);
    }

    private boolean isInvalid(String rawQuery,
                              String catalogVersion,
                              RagDocumentKind documentKind,
                              int topK) {
        return rawQuery == null || rawQuery.isBlank()
                || catalogVersion == null || catalogVersion.isBlank()
                || documentKind == null || topK <= 0;
    }

    private List<RankedCandidate> aggregate(List<EmbeddingMatch<TextSegment>> matches,
                                            String catalogVersion,
                                            RagDocumentKind documentKind,
                                            int topK) {
        Map<String, Double> maxScores = new HashMap<>();
        int invalidMetadataCount = 0;
        for (EmbeddingMatch<TextSegment> match : matches == null ? List.<EmbeddingMatch<TextSegment>>of() : matches) {
            DenseMatch denseMatch = toValidMatch(match, catalogVersion, documentKind);
            if (denseMatch == null) {
                invalidMetadataCount++;
                continue;
            }
            maxScores.merge(denseMatch.documentId(), denseMatch.score(), Math::max);
        }
        if (invalidMetadataCount > 0) {
            log.warn("[Vue RAG][Dense] 跳过非法 metadata 候选,documentKind={},catalogVersion={},count={}",
                    documentKind, catalogVersion, invalidMetadataCount);
        }
        return toRankedCandidates(maxScores, documentKind, topK);
    }

    private DenseMatch toValidMatch(EmbeddingMatch<TextSegment> match,
                                    String catalogVersion,
                                    RagDocumentKind documentKind) {
        if (match == null || match.embedded() == null || match.score() == null
                || !Double.isFinite(match.score())) {
            return null;
        }
        Metadata metadata = match.embedded().metadata();
        String chunkId = metadataText(metadata, "chunkId");
        String documentId = metadataText(metadata, "documentId");
        String metadataDocumentKind = metadataText(metadata, "documentKind");
        String chunkKind = metadataText(metadata, "chunkKind");
        String metadataCatalogVersion = metadataText(metadata, "catalogVersion");
        if (chunkId == null || documentId == null
                || !documentKind.name().equals(metadataDocumentKind)
                || !catalogVersion.equals(metadataCatalogVersion)
                || !isValidChunkKind(chunkKind)) {
            return null;
        }
        return new DenseMatch(documentId, match.score());
    }

    private String metadataText(Metadata metadata, String key) {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.toMap().get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private boolean isValidChunkKind(String chunkKind) {
        if (chunkKind == null) {
            return false;
        }
        try {
            RagChunkKind.valueOf(chunkKind);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private List<RankedCandidate> toRankedCandidates(Map<String, Double> maxScores,
                                                      RagDocumentKind documentKind,
                                                      int topK) {
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(maxScores.entrySet());
        sorted.sort(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry::getKey));
        List<RankedCandidate> candidates = new ArrayList<>();
        int limit = Math.min(topK, sorted.size());
        for (int index = 0; index < limit; index++) {
            Map.Entry<String, Double> entry = sorted.get(index);
            candidates.add(new RankedCandidate(
                    entry.getKey(), documentKind, index + 1, entry.getValue()));
        }
        return List.copyOf(candidates);
    }

    private record DenseMatch(String documentId, double score) {
    }
}
