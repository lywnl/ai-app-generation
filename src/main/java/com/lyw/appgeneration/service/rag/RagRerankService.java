package com.lyw.appgeneration.service.rag;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.service.rag.exception.RerankException;
import com.lyw.appgeneration.service.rag.model.RetrievedSnippet;
import com.lyw.appgeneration.service.rag.model.TemplateDoc;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

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
        List<RerankResult> results = requestRerank(query, docs, topK);

        List<RetrievedSnippet> reranked = new ArrayList<>(results.size());
        for (RerankResult result : results) {
            validateResult(result, n);
            RetrievedSnippet origin = candidates.get(result.index());
            origin.setRerankScore(result.relevanceScore());
            reranked.add(origin);
        }
        reranked.sort(Comparator.comparingDouble((RetrievedSnippet snippet) ->
                snippet.getRerankScore() == null ? 0.0 : snippet.getRerankScore()).reversed());
        return reranked.size() > topK ? reranked.subList(0, topK) : reranked;
    }

    /**
     * 对 Vue 父文档候选重排，不修改父文档，也不把源码发送给 Rerank 服务。
     */
    public List<TemplateDoc> rerankVue(String query, List<TemplateDoc> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            throw new RerankException("候选列表为空,无需 rerank");
        }
        int candidateCount = candidates.size();
        List<String> documents = candidates.stream().map(this::buildVueDocumentText).toList();
        List<RerankResult> results = requestRerank(query, documents, topK);
        validateVueResults(results, candidateCount, Math.min(topK, candidateCount));
        List<VueRerankResult> reranked = new ArrayList<>(results.size());
        for (RerankResult result : results) {
            reranked.add(new VueRerankResult(
                    candidates.get(result.index()), result.relevanceScore()));
        }
        reranked.sort(Comparator.comparingDouble(VueRerankResult::score).reversed()
                .thenComparing(Comparator.comparingDouble(
                        (VueRerankResult result) -> qualityScore(result.document())).reversed())
                .thenComparing(result -> result.document().getId()));
        return immutableTopK(reranked.stream().map(VueRerankResult::document).toList(), topK);
    }

    /**
     * 只拼装父文档检索语义和工程元数据，严禁读取 files[].content。
     */
    String buildVueDocumentText(TemplateDoc document) {
        String dependencies = formatDependencies(document.getDependencies(), document.getDevDependencies());
        String filePaths = document.getFiles() == null ? "" : document.getFiles().stream()
                .filter(file -> file != null && file.getPath() != null && !file.getPath().isBlank())
                .map(TemplateDoc.TemplateFile::getPath)
                .collect(Collectors.joining(", "));
        String text = String.join("\n",
                "标题: " + safeText(document.getTitle()),
                "描述: " + safeText(document.getDescription()),
                "意图: " + safeText(document.getEmbedText()),
                "技术栈: " + formatTechStack(document),
                "依赖: " + dependencies,
                "文件路径: " + filePaths);
        int limit = props.getRerank().getDocCharLimit();
        return text.length() > limit ? text.substring(0, limit) : text;
    }

    private List<RerankResult> requestRerank(String query, List<String> documents, int topK) {
        int candidateCount = documents.size();
        Map<String, Object> body = Map.of(
                "model", props.getRerank().getModelName(),
                "input", Map.of("query", query, "documents", documents),
                "parameters", Map.of("top_n", Math.min(topK, candidateCount), "return_documents", false)
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
        return resp.output().results();
    }

    private void validateVueResults(List<RerankResult> results,
                                    int candidateCount,
                                    int expectedResultCount) {
        if (results.size() != expectedResultCount) {
            throw new RerankException("Vue Rerank 结果数量非法: expected="
                    + expectedResultCount + ", actual=" + results.size());
        }
        Set<Integer> indexes = new HashSet<>();
        for (RerankResult result : results) {
            validateResult(result, candidateCount);
            if (!indexes.add(result.index())) {
                throw new RerankException("Vue Rerank index 重复: " + result.index());
            }
        }
    }

    private void validateResult(RerankResult result, int candidateCount) {
        if (result == null || result.index() == null) {
            throw new RerankException("Rerank 结果缺少 index");
        }
        validateIndex(result.index(), candidateCount);
        if (result.relevanceScore() == null || !Double.isFinite(result.relevanceScore())) {
            throw new RerankException("Rerank relevance_score 缺失或不是有限数");
        }
    }

    private void validateIndex(int index, int candidateCount) {
        if (index < 0 || index >= candidateCount) {
            throw new RerankException("index 越界: " + index + ", candidates size=" + candidateCount);
        }
    }

    private <T> List<T> immutableTopK(List<T> values, int topK) {
        return List.copyOf(values.stream().limit(Math.max(0, topK)).toList());
    }

    private double qualityScore(TemplateDoc document) {
        Double score = document.getQualityScore();
        return score != null && Double.isFinite(score) ? score : 0.0;
    }

    private String formatDependencies(Map<String, String> dependencies,
                                      Map<String, String> devDependencies) {
        Map<String, String> all = new TreeMap<>();
        if (dependencies != null) {
            all.putAll(dependencies);
        }
        if (devDependencies != null) {
            all.putAll(devDependencies);
        }
        return all.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(", "));
    }

    private String formatList(List<String> values) {
        return values == null ? "" : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining(", "));
    }

    private String formatTechStack(TemplateDoc document) {
        List<String> stack = new ArrayList<>();
        stack.add(document.getFramework());
        stack.add(document.getLanguage());
        stack.add(document.getBuildTool());
        if (document.getTech() != null) {
            stack.addAll(document.getTech());
        }
        return formatList(stack);
    }

    private String safeText(String value) {
        return value == null ? "" : value;
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
            Integer index,
            @JsonProperty("relevance_score") Double relevanceScore
    ) {}

    private record Usage(@JsonProperty("total_tokens") int totalTokens) {}

    private record VueRerankResult(TemplateDoc document, double score) {}
}
