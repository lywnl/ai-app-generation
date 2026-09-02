package com.lyw.appgeneration.constants;

import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;

import java.util.Map;

/**
 * RAG 相关常量
 *
 * @author lyw
 */
public final class RagConstants {

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
