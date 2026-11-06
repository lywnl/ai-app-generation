package com.lyw.appgeneration.constants;

import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;

import java.util.Map;

/**
 * RAG 相关常量
 *
 * @author lyw
 */
public final class RagConstants {

    /** 每条 Vue 召回通道的子块搜索上限，父文档聚合后允许不足。 */
    public static final int VUE_CHANNEL_TOP_K = 8;
    /** 每个文档池经过 RRF 融合后交给重排的父模板上限。 */
    public static final int VUE_FUSION_TOP_K = 10;
    public static final int VUE_SKELETON_TOP_K = 1;
    public static final int VUE_INITIAL_FEATURE_TOP_K = 4;
    public static final int VUE_FOLLOWUP_FEATURE_TOP_K = 2;

    /** Vue 召回同时使用稠密向量和 Milvus 原生 BM25。 */
    public static final String VUE_BM25_COLLECTION = "templates_vue_bm25";

    private RagConstants() {
    }

    /**
     * 每种代码生成类型对应的 Milvus Collection 名称
     */
    public static final Map<CodeGenTypeEnum, String> TYPE_TO_COLLECTION = Map.of(
            CodeGenTypeEnum.HTML, "templates_html",
            CodeGenTypeEnum.MULTI_FILE, "templates_multi",
            CodeGenTypeEnum.VUE_PROJECT, VUE_BM25_COLLECTION
    );

    public static final Map<CodeGenTypeEnum, String> TYPE_TO_DIR = Map.of(
            CodeGenTypeEnum.HTML, "html",
            CodeGenTypeEnum.MULTI_FILE, "multi-file",
            CodeGenTypeEnum.VUE_PROJECT, "vue-project"
    );
}
