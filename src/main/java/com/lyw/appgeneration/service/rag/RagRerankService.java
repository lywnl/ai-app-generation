package com.lyw.appgeneration.service.rag;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.service.rag.exception.RerankException;
import com.lyw.appgeneration.service.rag.model.RetrievedSnippet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * RAG 精排服务:基于阿里云 DashScope gte-rerank-v2 对粗召回结果做 Cross-Encoder 级别打分
 *
 * 设计要点:
 * 1. 无状态 Service,HTTP 调用通过 Spring RestClient 完成(Spring Boot 3.2+ 原生)
 * 2. 任意失败(HTTP、超时、空响应、index 越界)统一抛 RerankException,由上层降级
 * 3. 不做任何"兜底"排序逻辑,失败即失败 — 避免掩盖问题导致线上难以察觉
 *
 * API 文档:https://help.aliyun.com/zh/model-studio/text-rerank-api
 *
 * @author lyw
 */
@Service
@Slf4j
public class RagRerankService {

    private final RagProperties props;
    private final RestClient restClient;

    public RagRerankService(RagProperties props,
                            @Value("${dashscope.api-key}") String apiKey) {
        this.props = props;
        RagProperties.Rerank cfg = props.getRerank();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(cfg.getTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(cfg.getTimeoutMs()));
        this.restClient = RestClient.builder()
                .baseUrl(cfg.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .requestFactory(factory)
                .build();
    }

    /**
     * 对粗召回结果做精排并取 topK
     *
     * @param query      用户原始查询(同向量召回的 query)
     * @param candidates 粗召回片段(通常 topN=10 条),本方法不修改原集合
     * @param topK       最终保留条数(通常 3)
     * @return 按 rerankScore 降序的 topK 片段(复用原 candidates 实例并回填 rerankScore)
     * @throws RerankException 任何失败都抛,交由调用方降级
     */
    public List<RetrievedSnippet> rerank(String query, List<RetrievedSnippet> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            throw new RerankException("候选列表为空,无需 rerank");
        }
        int n = candidates.size();

        List<String> docs = candidates.stream().map(this::buildDocText).toList();
        Map<String, Object> body = Map.of(
                "model", props.getRerank().getModelName(),
                "input", Map.of("query", query, "documents", docs),
                "parameters", Map.of("top_n", Math.min(topK, n), "return_documents", false)
        );

        DashScopeRerankResponse resp;
        try {
            resp = restClient.post()
                    .body(body)
                    .retrieve()
                    .body(DashScopeRerankResponse.class);
        } catch (RestClientResponseException e) {
            throw new RerankException("DashScope 返回错误状态: " + e.getStatusCode(), e);
        } catch (ResourceAccessException e) {
            throw new RerankException("DashScope 网络/超时失败", e);
        } catch (Exception e) {
            throw new RerankException("DashScope 调用异常", e);
        }

        if (resp == null || resp.output() == null || resp.output().results() == null || resp.output().results().isEmpty()) {
            throw new RerankException("DashScope 响应为空或无 results");
        }

        List<RetrievedSnippet> reranked = new ArrayList<>(resp.output().results().size());
        for (RerankResult r : resp.output().results()) {
            if (r.index() < 0 || r.index() >= n) {
                throw new RerankException("index 越界: " + r.index() + ", candidates size=" + n);
            }
            RetrievedSnippet origin = candidates.get(r.index());
            origin.setRerankScore(r.relevanceScore());
            reranked.add(origin);
        }
        reranked.sort(Comparator.comparingDouble((RetrievedSnippet s) -> s.getRerankScore() == null ? 0.0 : s.getRerankScore()).reversed());
        return reranked.size() > topK ? reranked.subList(0, topK) : reranked;
    }

    /**
     * 拼装送入 rerank 的文档文本
     * 策略:title + 换行 + 截断后的 code;满足 gte-rerank-v2 单条 4000 token 限制
     */
    private String buildDocText(RetrievedSnippet s) {
        String title = s.getTitle() == null ? "" : s.getTitle();
        String code = s.getCode() == null ? "" : s.getCode();
        int limit = props.getRerank().getDocCharLimit();
        if (code.length() > limit) {
            code = code.substring(0, limit);
        }
        return title + "\n" + code;
    }

    /** DashScope rerank 响应顶层结构 */
    private record DashScopeRerankResponse(
            Output output,
            Usage usage,
            @JsonProperty("request_id") String requestId
    ) {}

    private record Output(List<RerankResult> results) {}

    private record RerankResult(
            int index,
            @JsonProperty("relevance_score") double relevanceScore
    ) {}

    private record Usage(@JsonProperty("total_tokens") int totalTokens) {}
}
