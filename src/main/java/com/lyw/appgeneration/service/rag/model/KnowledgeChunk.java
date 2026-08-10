package com.lyw.appgeneration.service.rag.model;

/**
 * 面向检索的 Vue RAG 子知识块。
 *
 * @param chunkId 子块唯一 ID
 * @param documentId 父文档 ID
 * @param documentKind 父文档类型
 * @param chunkKind 子块类型
 * @param searchText 检索文本，不含完整源码
 */
public record KnowledgeChunk(
        String chunkId,
        String documentId,
        RagDocumentKind documentKind,
        RagChunkKind chunkKind,
        String searchText
) {
}
