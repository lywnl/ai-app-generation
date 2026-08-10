package com.lyw.appgeneration.service.rag.model;

/**
 * 召回通道返回的父文档级候选，不携带父文档或源码。
 *
 * @param documentId 父文档 ID
 * @param documentKind 父文档类型
 * @param rank 通道内从 1 开始的排名
 * @param score 通道原始分数
 */
public record RankedCandidate(
        String documentId,
        RagDocumentKind documentKind,
        int rank,
        double score
) {
}
