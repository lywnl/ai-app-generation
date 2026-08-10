package com.lyw.appgeneration.service.rag;

import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.service.rag.exception.RerankException;
import com.lyw.appgeneration.service.rag.model.RetrievedSnippet;
import com.lyw.appgeneration.service.rag.model.VueRagContext;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * RAG 检索服务:按生成类型选择向量表,召回 → (可选)rerank 精排 → 取 topK
 *
 * 核心设计:
 * 1. 向量召回彻底失败(embedding / pgvector 异常)→ 返回空,主生成链路无 RAG 增强
 * 2. Rerank 失败 → 降级为原向量 score 排序取 topK,保留向量召回能力
 * 3. 候选数 ≤ topK 时跳过 rerank(无意义),节省一次 HTTP
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
    private final RagRerankService rerankService;
    private final VueHybridRetrievalService vueHybridRetrievalService;

    /**
     * 使用原始需求执行 Vue 工程双链混合检索。
     *
     * @param rawQuery 未经过图片增强或 Prompt 拼装的原始需求
     * @return Vue 工程 RAG 上下文
     */
    public VueRagContext retrieveVueProject(String rawQuery) {
        return vueHybridRetrievalService.retrieve(rawQuery);
    }

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
        int topK = props.getRetrieval().getTopK();
        boolean rerankOn = props.getRerank().isEnabled();
        int recallSize = rerankOn ? Math.max(props.getRerank().getTopN(), topK) : topK;

        try {
            Embedding queryEmbedding = ragEmbeddingModel.embed(userPrompt).content();

            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(recallSize)
                    .minScore(props.getRetrieval().getMinScore())
                    .build();

            EmbeddingSearchResult<TextSegment> result = store.search(request);
            List<RetrievedSnippet> candidates = result.matches().stream()
                    .map(this::toSnippet)
                    .toList();

            if (candidates.isEmpty()) {
                log.info("[RAG] 召回 type={}, 条数=0, 耗时={}ms", type, System.currentTimeMillis() - start);
                return List.of();
            }

            if (!rerankOn || candidates.size() <= topK) {
                log.info("[RAG] 召回 type={}, 条数={}, 耗时={}ms, rerank=skipped",
                        type, candidates.size(), System.currentTimeMillis() - start);
                return candidates.stream().limit(topK).toList();
            }

            try {
                List<RetrievedSnippet> reranked = rerankService.rerank(userPrompt, candidates, topK);
                log.info("[RAG] 召回 type={}, 粗召回={}, rerank 后={}, 总耗时={}ms",
                        type, candidates.size(), reranked.size(), System.currentTimeMillis() - start);
                return reranked;
            } catch (RerankException re) {
                log.warn("[RAG][Rerank] 失败,降级为原向量排序取 topK={}, type={}, 粗召回={}, 总耗时={}ms, reason={}",
                        topK, type, candidates.size(), System.currentTimeMillis() - start, re.getMessage());
                return candidates.stream().limit(topK).toList();
            }
        } catch (Exception e) {
            log.warn("[RAG] 检索失败,降级为无 RAG 生成,type={}, 耗时={}ms",
                    type, System.currentTimeMillis() - start, e);
            return List.of();
        }
    }

    private RetrievedSnippet toSnippet(EmbeddingMatch<TextSegment> match) {
        TextSegment seg = match.embedded();
        return RetrievedSnippet.builder()
                .id(seg.metadata().getString("id"))
                .title(seg.metadata().getString("title"))
                .category(seg.metadata().getString("category"))
                .code(seg.metadata().getString("code"))
                .score(match.score())
                .build();
    }
}
