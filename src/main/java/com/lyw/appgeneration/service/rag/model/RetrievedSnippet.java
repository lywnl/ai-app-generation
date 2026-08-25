package com.lyw.appgeneration.service.rag.model;

import lombok.Builder;
import lombok.Data;

/**
 * 召回结果(从向量库查出后,供 prompt 拼装器使用)
 *
 * @author lyw
 */
@Data
@Builder
public class RetrievedSnippet {

    /** 模板唯一 ID */
    private String id;

    /** 模板标题 */
    private String title;

    /** 业务分类 */
    private String category;

    /** 从当前受校验本地目录回查的完整父文档 */
    private TemplateDoc document;

    /** 相似度分数 [0, 1],越大越相似 */
    private Double score;

    /** Rerank 精排分数 [0, 1],null 表示未经过 rerank 或已降级 */
    private Double rerankScore;
}
