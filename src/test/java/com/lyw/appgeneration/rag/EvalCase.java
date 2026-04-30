package com.lyw.appgeneration.rag;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;

import java.util.Map;

/**
 * RAG 评估单条用例(对应 eval-set.json 中的一条 query)
 *
 * <p>relevance 字段语义:键 = 模板 id,值 = 3 级相关度
 * <ul>
 *     <li>2 = 完全相关(命中即正例)</li>
 *     <li>1 = 部分相关(NDCG 加权时折半)</li>
 *     <li>0 = 不相关(可省略不写,缺省视为 0)</li>
 * </ul>
 *
 * <p>未列入 relevance 的模板 id 一律视为 0(不相关),
 * 这样 ground truth 集等于 relevance.keySet() 中 value &gt;= 1 的部分。
 *
 * @author lyw
 */
public record EvalCase(
        @JsonProperty("queryId") String queryId,
        @JsonProperty("query") String query,
        @JsonProperty("type") String typeRaw,
        @JsonProperty("queryStyle") String queryStyle,
        @JsonProperty("relevance") Map<String, Integer> relevance,
        @JsonProperty("notes") String notes
) {

    /**
     * 把字符串 type(如 "html"/"multi_file"/"vue_project")映射为枚举,
     * 兼容 eval-set.json 的可读性需求,失败时抛出明确异常便于定位错标
     */
    public CodeGenTypeEnum codeGenType() {
        CodeGenTypeEnum t = CodeGenTypeEnum.getEnumByValue(typeRaw);
        if (t == null) {
            throw new IllegalArgumentException(
                    "evalCase[" + queryId + "] type 非法: " + typeRaw +
                    ",合法值: html / multi_file / vue_project");
        }
        return t;
    }

    /** 仅返回相关度 &gt;= 1 的模板 id,作为该 query 的 ground truth 正例集合 */
    public java.util.Set<String> relevantIds() {
        if (relevance == null) {
            return java.util.Set.of();
        }
        return relevance.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() >= 1)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** 取某模板的相关度分数,缺省为 0 */
    public int relevanceOf(String templateId) {
        if (relevance == null || templateId == null) {
            return 0;
        }
        Integer v = relevance.get(templateId);
        return v == null ? 0 : v;
    }
}
