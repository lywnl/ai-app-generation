package com.lyw.appgeneration.service.rag.retrieval;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyw.appgeneration.service.rag.model.RagChunkKind;
import com.lyw.appgeneration.service.rag.model.RagDocumentKind;
import com.lyw.appgeneration.service.rag.model.RankedCandidate;
import com.lyw.appgeneration.service.rag.store.MilvusBm25SearchClient;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Vue Milvus BM25 父文档召回器，服务端完成分词和 BM25 评分。
 */
@Component
@Slf4j
public class MilvusBm25Retriever {

    private final MilvusBm25SearchClient searchClient;

    public MilvusBm25Retriever(MilvusBm25SearchClient searchClient) {
        this.searchClient = searchClient;
    }

    public List<RankedCandidate> retrieve(String rawQuery,
                                          String catalogVersion,
                                          RagDocumentKind documentKind,
                                          int topK) {
        if (rawQuery == null || rawQuery.isBlank()
                || catalogVersion == null || catalogVersion.isBlank()
                || documentKind == null || topK <= 0) {
            return List.of();
        }
        SearchResp response = searchClient.search(
                rawQuery, catalogVersion, documentKind, MilvusBm25SearchClient.QUERY_TOP_K);
        return aggregate(response, catalogVersion, documentKind, topK);
    }

    private List<RankedCandidate> aggregate(SearchResp response,
                                            String catalogVersion,
                                            RagDocumentKind documentKind,
                                            int topK) {
        Map<String, Double> maxScores = new HashMap<>();
        int invalidCount = 0;
        if (response == null || response.getSearchResults() == null
                || response.getSearchResults().isEmpty()) {
            return List.of();
        }
        List<SearchResp.SearchResult> results = response.getSearchResults().getFirst();
        for (SearchResp.SearchResult result : results == null ? List.<SearchResp.SearchResult>of() : results) {
            Bm25Match match = toValidMatch(result, catalogVersion, documentKind);
            if (match == null) {
                invalidCount++;
                continue;
            }
            maxScores.merge(match.documentId(), match.score(), Math::max);
        }
        if (invalidCount > 0) {
            log.warn("[Vue RAG][BM25] 跳过非法 metadata 候选,catalogVersion={},count={}",
                    catalogVersion, invalidCount);
        }
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(maxScores.entrySet());
        sorted.sort(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry::getKey));
        int limit = Math.min(topK, sorted.size());
        List<RankedCandidate> candidates = new ArrayList<>(limit);
        for (int index = 0; index < limit; index++) {
            Map.Entry<String, Double> entry = sorted.get(index);
            candidates.add(new RankedCandidate(entry.getKey(), documentKind, index + 1, entry.getValue()));
        }
        return List.copyOf(candidates);
    }

    private Bm25Match toValidMatch(SearchResp.SearchResult result,
                                   String catalogVersion,
                                   RagDocumentKind documentKind) {
        if (result == null || result.getScore() == null || !Float.isFinite(result.getScore())) {
            return null;
        }
        Map<String, Object> metadata = metadata(result.getEntity());
        String chunkId = text(metadata.get("chunkId"));
        String documentId = text(metadata.get("documentId"));
        String metadataDocumentKind = text(metadata.get("documentKind"));
        String chunkKind = text(metadata.get("chunkKind"));
        String metadataCatalogVersion = text(metadata.get("catalogVersion"));
        if (chunkId == null || documentId == null
                || !documentKind.name().equals(metadataDocumentKind)
                || !catalogVersion.equals(metadataCatalogVersion)
                || !isValidChunkKind(chunkKind)) {
            return null;
        }
        return new Bm25Match(documentId, result.getScore().doubleValue());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> metadata(Map<String, Object> entity) {
        if (entity == null) {
            return Map.of();
        }
        Object rawMetadata = entity.get("metadata");
        if (rawMetadata instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (rawMetadata instanceof JsonObject jsonObject) {
            Map<String, Object> values = new HashMap<>();
            jsonObject.entrySet().forEach(entry -> values.put(entry.getKey(), jsonValue(entry.getValue())));
            return values;
        }
        if (rawMetadata instanceof String json) {
            try {
                JsonElement parsed = JsonParser.parseString(json);
                if (parsed.isJsonObject()) {
                    return metadata(Map.of("metadata", parsed.getAsJsonObject()));
                }
            } catch (RuntimeException exception) {
                return Map.of();
            }
        }
        return Map.of();
    }

    private Object jsonValue(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (value.isJsonPrimitive()) {
            return value.getAsJsonPrimitive().isString() ? value.getAsString() : value;
        }
        return value;
    }

    private String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private boolean isValidChunkKind(String value) {
        try {
            RagChunkKind.valueOf(value);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private record Bm25Match(String documentId, double score) {
    }
}
