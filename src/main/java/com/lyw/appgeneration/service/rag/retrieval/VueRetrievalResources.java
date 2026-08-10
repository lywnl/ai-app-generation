package com.lyw.appgeneration.service.rag.retrieval;

import com.lyw.appgeneration.service.rag.catalog.TemplateCatalog;

import java.util.Optional;

/**
 * 一致目录版本下的 Vue 模板目录与 BM25 索引。
 */
public record VueRetrievalResources(
        TemplateCatalog catalog,
        Optional<Bm25Retriever> bm25Retriever
) {

    public VueRetrievalResources(TemplateCatalog catalog, Bm25Retriever bm25Retriever) {
        this(catalog, Optional.ofNullable(bm25Retriever));
    }
}
