package com.lyw.appgeneration.service.rag.retrieval;

import com.lyw.appgeneration.service.rag.catalog.TemplateCatalog;

/**
 * 一致目录版本下的 Vue 模板目录快照。
 */
public record VueRetrievalResources(
        TemplateCatalog catalog
) {
}
