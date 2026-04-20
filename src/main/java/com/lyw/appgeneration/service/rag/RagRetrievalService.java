package com.lyw.appgeneration.service.rag;

import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.service.rag.model.RetrievedSnippet;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * RAG 检索服务:按生成类型选择向量表,召回 top-K 模板片段
 * 核心设计:任何异常都降级为返回空列表,永不阻塞主生成链路
 *
 * @author lyw
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagRetrievalService {

    private final EmbeddingModel ragEmbeddingModel;
    private final Map<CodeGenTypeEnum, EmbeddingStore<TextSegment>> embeddingStoreByType;
    private final RagProperties props;

    /**
     * 按用户提示词与代码生成类型召回相关模板片段
     *
     * @param userPrompt 用户原始提示词
     * @param type       代码生成类型
     * @return 召回片段列表(可能为空表示无 RAG 增强)
     */
    public List<RetrievedSnippet> retrieve(String userPrompt, CodeGenTypeEnum type) {
        if (!props.isEnabled()) {
            return List.of();
        }
        if (userPrompt == null || userPrompt.isBlank() || type == null) {
            return List.of();
        }

        EmbeddingStore<TextSegment> store = embeddingStoreByType.get(type);
        if (store == null) {
            log.debug("[RAG] 未找到 type={} 对应的向量存储,跳过召回", type);
            return List.of();
        }

        long start = System.currentTimeMillis();
        try {
            Embedding queryEmbedding = ragEmbeddingModel.embed(userPrompt).content();

            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(props.getRetrieval().getTopK())
                    .minScore(props.getRetrieval().getMinScore())
                    .build();

            EmbeddingSearchResult<TextSegment> result = store.search(request);
            List<RetrievedSnippet> snippets = result.matches().stream()
                    .map(match -> {
                        TextSegment seg = match.embedded();
                        return RetrievedSnippet.builder()
                                .id(seg.metadata().getString("id"))
                                .title(seg.metadata().getString("title"))
                                .category(seg.metadata().getString("category"))
                                .code(seg.metadata().getString("code"))
                                .score(match.score())
                                .build();
                    })
                    .toList();

            log.info("[RAG] 召回 type={}, 条数={}, 耗时={}ms",
                    type, snippets.size(), System.currentTimeMillis() - start);
            return snippets;
        } catch (Exception e) {
            log.warn("[RAG] 检索失败,降级为无 RAG 生成,type={}, 耗时={}ms",
                    type, System.currentTimeMillis() - start, e);
            return List.of();
        }
    }
}
