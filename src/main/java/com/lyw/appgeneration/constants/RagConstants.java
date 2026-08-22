package com.lyw.appgeneration.constants;

import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;

import java.util.Map;

/**
 * RAG 相关常量
 *
 * @author lyw
 */
public final class RagConstants {

    private RagConstants() {
    }

    /**
     * 每种代码生成类型对应的 Milvus Collection 名称
     */
    public static final Map<CodeGenTypeEnum, String> TYPE_TO_COLLECTION = Map.of(
            CodeGenTypeEnum.HTML, "templates_html",
            CodeGenTypeEnum.MULTI_FILE, "templates_multi",
            CodeGenTypeEnum.VUE_PROJECT, "templates_vue"
    );

    public static final Map<CodeGenTypeEnum, String> TYPE_TO_DIR = Map.of(
            CodeGenTypeEnum.HTML, "html",
            CodeGenTypeEnum.MULTI_FILE, "multi-file",
            CodeGenTypeEnum.VUE_PROJECT, "vue-project"
    );
}
