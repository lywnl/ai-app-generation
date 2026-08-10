package com.lyw.appgeneration.service.rag;

import com.lyw.appgeneration.service.rag.model.RagDocumentKind;

/**
 * Vue 父文档文件集合的 Prompt 预算策略。
 * 上限保证最坏元数据下，固定契约与每个候选文件的最低表示仍可落入对应分区预算。
 */
public final class VueRagBudgetPolicy {

    public static final int SKELETON_CONTEXT_BUDGET = 4000;
    public static final int FEATURE_CONTEXT_BUDGET = 8000;
    public static final int MAX_SKELETON_FILES = 10;
    public static final int MAX_FEATURE_FILES = 5;

    private VueRagBudgetPolicy() {
    }

    public static int maxFiles(RagDocumentKind documentKind) {
        return switch (documentKind) {
            case PROJECT_SKELETON -> MAX_SKELETON_FILES;
            case FEATURE_SNIPPET -> MAX_FEATURE_FILES;
        };
    }
}
